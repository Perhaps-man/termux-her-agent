package com.termux.app

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.net.Uri
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.lang.ref.WeakReference
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import android.content.Intent
import android.provider.ContactsContract
import android.provider.AlarmClock
import android.telephony.SmsManager
import android.provider.CalendarContract
import android.content.ContentValues
import java.util.TimeZone
import android.content.ClipData
import android.content.ClipboardManager
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object HerSystemRuntime {

    private var activityRef: WeakReference<ComponentActivity>? = null

    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var takePictureLauncher: ActivityResultLauncher<Uri>? = null

    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var pendingTakePicture: CompletableDeferred<Boolean>? = null
    private const val PERMISSION_REQUEST_TIMEOUT_MS = 20_000L
    private const val TAKE_PICTURE_TIMEOUT_MS = 30_000L
    private const val SENSOR_READ_TIMEOUT_MS = 5_000L
    suspend fun addCalendarEvent(
        context: Context,
        title: String,
        startMillis: Long,
        endMillis: Long,
        description: String = ""
    ): Boolean {

        val ok = ensurePermissions(
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CALENDAR
        )

        if (!ok) return false

        try {

            // ⭐ 找默认日历账户
            val projection = arrayOf(
                CalendarContract.Calendars._ID
            )

            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            ) ?: return false

            if (!cursor.moveToFirst()) {
                cursor.close()
                return false
            }

            val calendarId = cursor.getLong(0)
            cursor.close()

            // ⭐ 写入事件
            val values = ContentValues().apply {

                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE,
                    TimeZone.getDefault().id)
            }

            context.contentResolver.insert(
                CalendarContract.Events.CONTENT_URI,
                values
            )

            return true

        } catch (t: Throwable) {

            return false
        }
    }

    fun bind(activity: ComponentActivity) {
        activityRef = WeakReference(activity)

        permissionLauncher =
            activity.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val granted = result.values.all { it }
                pendingPermission?.complete(granted)
                pendingPermission = null
            }

        takePictureLauncher =
            activity.registerForActivityResult(
                ActivityResultContracts.TakePicture()
            ) { success ->
                pendingTakePicture?.complete(success)
                pendingTakePicture = null
            }
    }

    fun withActivity(block: (ComponentActivity) -> Unit) {
        val act = activityRef?.get() ?: return
        block(act)
    }

    private fun requireActivity(): ComponentActivity {
        return activityRef?.get()
            ?: error("HerSystemRuntime not bound. Call HerSystemRuntime.bind(activity) in MainActivity.onCreate().")
    }

    suspend fun ensurePermissions(vararg perms: String): Boolean {
        val act = requireActivity()

        val already = perms.all {
            act.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (already) return true

        val d = CompletableDeferred<Boolean>()
        pendingPermission = d
        @Suppress("UNCHECKED_CAST")
        permissionLauncher?.launch(perms as Array<String>)
        val granted = withTimeoutOrNull(PERMISSION_REQUEST_TIMEOUT_MS) { d.await() } ?: false
        if (!granted && pendingPermission === d) pendingPermission = null
        return granted
    }
    suspend fun callTarget(context: Context, target: String): Boolean {

        val ok = ensurePermissions(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )

        if (!ok) return false

        val phone = if (target.any { it.isDigit() }) {
            target
        } else {
            findPhoneByName(context, target)
        } ?: return false

        val act = requireActivity()

        act.runOnUiThread {

            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phone")
            }

            act.startActivity(intent)
        }

        return true
    }


    suspend fun takePhotoReturnPath(context: Context): String {
        val ok = ensurePermissions(Manifest.permission.CAMERA)
        if (!ok) return ""

        val (uri, file) = newPhotoOutput(context)

        val d = CompletableDeferred<Boolean>()
        pendingTakePicture = d
        val act = requireActivity()
        act.runOnUiThread {
            takePictureLauncher?.launch(uri)
        }
        val success = withTimeoutOrNull(TAKE_PICTURE_TIMEOUT_MS) { d.await() } ?: false
        if (!success && pendingTakePicture === d) pendingTakePicture = null
        return if (success) file.absolutePath else ""
    }
    fun showNotification(context: Context, title: String, text: String): Boolean {
        return try {

            val channelId = "her_termux_channel"

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    channelId,
                    "Her Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(ch)
            }

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)

            nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())

            true
        } catch (e: Throwable) {
            false
        }
    }
    fun wifiEnable(context: Context, enable: Boolean): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled = enable
            true
        } catch (_: Throwable) {
            false
        }
    }
    suspend fun wifiConnectionInfo(context: Context): String {
        ensurePermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo ?: return "{}"
        return """{
        "ssid":"${info.ssid}",
        "bssid":"${info.bssid}",
        "ip":${info.ipAddress},
        "speed":${info.linkSpeed}
    }"""
    }
    suspend fun wifiScanInfo(context: Context): String {
        val ok = ensurePermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!ok) return "[]"
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val list = wm.scanResults ?: emptyList()

        val arr = list.joinToString(prefix="[", postfix="]") {
            """{"ssid":"${it.SSID}","bssid":"${it.BSSID}","level":${it.level}}"""
        }
        return arr
    }
    fun httpGet(url: String): String {
        return runCatching {
            URL(url).readText()
        }.getOrDefault("")
    }
    fun httpPost(url: String, body: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.inputStream.bufferedReader().readText()
        } catch (e: Throwable) {
            ""
        }
    }
    fun showDialog(context: Context, message: String) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            AlertDialog.Builder(context)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }
    suspend fun getContacts(context: Context): String {

        val ok = ensurePermissions(Manifest.permission.READ_CONTACTS)
        if (!ok) return "permission denied"

        val sb = StringBuilder()

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        ) ?: return "no contacts"

        while (cursor.moveToNext()) {
            val name = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
            )
            val number = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
            )
            sb.append("$name : $number\n")
        }

        cursor.close()
        return sb.toString()
    }
    suspend fun getSms(context: Context): String {

        val ok = ensurePermissions(Manifest.permission.READ_SMS)
        if (!ok) return "permission denied"

        val sb = StringBuilder()

        val cursor = context.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            null, null, null, "date DESC"
        ) ?: return "no sms"

        while (cursor.moveToNext()) {
            val body = cursor.getString(cursor.getColumnIndexOrThrow("body"))
            val addr = cursor.getString(cursor.getColumnIndexOrThrow("address"))
            sb.append("$addr : $body\n")
        }

        cursor.close()
        return sb.toString()
    }
    private var recorder: MediaRecorder? = null

    suspend fun startRecording(context: Context): Boolean {

        val ok = ensurePermissions(Manifest.permission.RECORD_AUDIO)
        if (!ok) return false

        val file = File(context.filesDir, "record.mp3")

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        return true
    }
    fun stopRecording() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
    }
    private var wakeLock: PowerManager.WakeLock? = null

    fun wakeLock(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Her::WakeLock")
        wakeLock?.acquire()
    }

    fun wakeUnlock() {
        wakeLock?.release()
        wakeLock = null
    }
    fun setWallpaper(context: Context, path: String, mode: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) return false

            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ?: return false

            val wm = WallpaperManager.getInstance(context)

            when (mode.lowercase()) {
                "lock" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                    } else {
                        return false
                    }
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                    } else {
                        wm.setBitmap(bitmap)
                    }
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    fun openUrl(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    fun shareFile(context: Context, path: String): Boolean {
        return try {
            val act = activityRef?.get() ?: return false
            val file = File(path)
            if (!file.exists()) return false

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            act.runOnUiThread {
                context.startActivity(Intent.createChooser(intent, "Share via"))
            }

            true
        } catch (_: Throwable) {
            false
        }
    }
    fun removeNotification(context: Context, id: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(id)
    }
    fun downloadFile(context: Context, url: String): String {
        return runCatching {
            val file = File(context.getExternalFilesDir(null), "dl_${System.currentTimeMillis()}")
            URL(url).openStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        }.getOrDefault("")
    }

    fun openFile(context: Context, path: String): Boolean {
        return try {
            val src = File(path)
            if (!src.exists()) return false

            // 复制到 cache 再打开，避免 Termux home 等路径与 FileProvider 配置不匹配
            val ext = path.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "bin"
            val cacheFile = File(context.cacheDir, "open_${System.currentTimeMillis()}.$ext")
            src.copyTo(cacheFile, overwrite = true)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )

            val type = when {
                path.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                path.endsWith(".doc", ignoreCase = true) -> "application/msword"
                path.endsWith(".docx", ignoreCase = true) ->
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                path.endsWith(".txt", ignoreCase = true) -> "text/plain"
                else -> "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("", uri)
            }

            val act = activityRef?.get()
            if (act != null) {
                act.runOnUiThread {
                    context.startActivity(Intent.createChooser(intent, "打开附件"))
                }
            } else {
                context.startActivity(Intent.createChooser(intent, "打开附件"))
            }
            true
        } catch (e: Throwable) {
            android.util.Log.e("HerSystemRuntime", "openFile failed: $path", e)
            false
        }
    }
    fun sensorList(context: Context): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val list = sm.getSensorList(Sensor.TYPE_ALL)
        val arr = list.joinToString(prefix="[", postfix="]") {
            "\"${it.name}\""
        }
        return arr
    }
    fun sensorGet(context: Context, typeName: String): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getSensorList(Sensor.TYPE_ALL).find {
            it.name.contains(typeName, ignoreCase = true)
        } ?: return "{}"

        val d = CompletableDeferred<FloatArray?>()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                d.complete(event.values.clone())
                sm.unregisterListener(this)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)

        val values = runCatching {
            runBlocking {
                withTimeoutOrNull(SENSOR_READ_TIMEOUT_MS) { d.await() }
            }
        }.getOrNull()
        sm.unregisterListener(listener)
        if (values == null) return "{}"

        return values.joinToString(prefix="[", postfix="]")
    }
    private var tts: TextToSpeech? = null

    fun ttsSpeak(context: Context, text: String): Boolean {
        try {
            if (tts == null) {
                tts = TextToSpeech(context) {}
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "her-tts")
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun ttsStop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun ttsEngines(context: Context): String {
        return try {
            val tts = TextToSpeech(context) { }
            val engines = tts.engines // <-- Kotlin 里是可用的 getter
            val arr = JSONArray()

            for (e in engines) {
                arr.put(e.label)
            }

            arr.toString()
        } catch (e: Exception) {
            "[]"
        }
    }
    fun setBrightness(context: Context, level: Int): Boolean {
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                level.coerceIn(0, 255)
            )
        } catch (e: Exception) {
            false
        }
    }
    fun setVolume(context: Context, stream: String, level: Int): Boolean {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamType = when (stream.lowercase()) {
                "music" -> AudioManager.STREAM_MUSIC
                "alarm" -> AudioManager.STREAM_ALARM
                "ring" -> AudioManager.STREAM_RING
                "call" -> AudioManager.STREAM_VOICE_CALL
                "system" -> AudioManager.STREAM_SYSTEM
                else -> AudioManager.STREAM_MUSIC
            }

            val max = audio.getStreamMaxVolume(streamType)
            audio.setStreamVolume(streamType, level.coerceIn(0, max), 0)
            true
        } catch (e: Exception) {
            false
        }
    }
    suspend fun torch(context: Context, on: Boolean): Boolean {
        val ok = ensurePermissions(Manifest.permission.CAMERA)
        if (!ok) return false
        return try {
            val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cam.cameraIdList.firstOrNull() ?: return false
            cam.setTorchMode(id, on)
            true
        } catch (e: Exception) {
            false
        }
    }
    suspend fun telephonyDeviceInfo(context: Context): String {
        val ok = ensurePermissions(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (!ok) return "{}"
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val json = JSONObject()

        try {
            json.put("networkType", tm.networkType)
            json.put("simState", tm.simState)
            json.put("operator", tm.networkOperatorName)
            json.put("phoneCount", tm.phoneCount)
        } catch (_: Exception) {}

        return json.toString()
    }
    suspend fun telephonyCellInfo(context: Context): String {
        val ok = ensurePermissions(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (!ok) return "{}"
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val arr = JSONArray()

        try {
            tm.allCellInfo?.forEach { info ->
                arr.put(info.toString())
            }
        } catch (_: Exception) {}

        return arr.toString()
    }
    fun mediaScan(context: Context, path: String): Boolean {
        return try {
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            true
        } catch (e: Exception) {
            false
        }
    }
    fun findPackageByAppName(
        context: Context,
        target: String
    ): String? {

        val pm = context.packageManager

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps = pm.queryIntentActivities(intent, 0)

        var bestScore = -1
        var bestPkg: String? = null

        for (app in apps) {

            val label = app.loadLabel(pm).toString()

            val score = matchScore(label, target)

            if (score > bestScore) {
                bestScore = score
                bestPkg = app.activityInfo.packageName
            }
        }

        return bestPkg
    }
    fun fileExists(path: String): Boolean {
        return File(path).exists()
    }

    fun fileRead(path: String): String {
        return runCatching { File(path).readText() }.getOrDefault("")
    }

    fun fileWrite(path: String, text: String): Boolean {
        return runCatching {
            File(path).appendText(text)
            true
        }.getOrDefault(false)
    }

    fun fileDelete(path: String): Boolean {
        return runCatching { File(path).delete() }.getOrDefault(false)
    }
    private var mediaPlayer: MediaPlayer? = null

    fun mediaPlay(context: Context, path: String) {
        runCatching {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
            }
        }
    }

    fun mediaStop() {
        mediaPlayer?.run {
            stop()
            release()
        }
        mediaPlayer = null
    }
    suspend fun termuxLocation(
        context: Context,
        provider: String,
        request: String,
        minTime: Long,
        minDistance: Float
    ): String = withContext(Dispatchers.Main) {

        val ok = ensurePermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (!ok) return@withContext """{"error":"permission denied"}"""

        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val realProvider = when (provider) {
            "gps" -> LocationManager.GPS_PROVIDER
            "network" -> LocationManager.NETWORK_PROVIDER
            "passive" -> LocationManager.PASSIVE_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }

        when (request) {

            "last" -> {
                val location = locationManager.getLastKnownLocation(realProvider)
                    ?: return@withContext """{"error":"no last location"}"""

                return@withContext buildJson(location)
            }

            "once" -> {
                return@withContext getSingleUpdate(locationManager, realProvider)
            }

            "updates" -> {
                startContinuousUpdates(
                    locationManager,
                    realProvider,
                    minTime,
                    minDistance
                )
                return@withContext """{"status":"updates_started"}"""
            }

            else -> {
                return@withContext """{"error":"invalid request"}"""
            }
        }
    }
    private var updateListener: LocationListener? = null
    fun stopContinuousUpdates(locationManager: LocationManager) {
        updateListener?.let {
            locationManager.removeUpdates(it)
            updateListener = null
        }
    }
    private fun buildJson(location: Location): String {
        return JSONObject().apply {
            put("provider", location.provider)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy", location.accuracy)
            put("altitude", location.altitude)
            put("speed", location.speed)
            put("bearing", location.bearing)
            put("time", location.time)
        }.toString()
    }
    private fun startContinuousUpdates(
        locationManager: LocationManager,
        provider: String,
        minTime: Long,
        minDistance: Float
    ) {

        stopContinuousUpdates(locationManager)

        updateListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {

                val json = buildJson(location)

                // 这里可以直接输出到终端
                herTerminalSession?.write(json + "\n")
            }
        }

        locationManager.requestLocationUpdates(
            provider,
            minTime,
            minDistance,
            updateListener!!,
            Looper.getMainLooper()
        )
    }
    private suspend fun getSingleUpdate(
        locationManager: LocationManager,
        provider: String
    ): String {

        val deferred = CompletableDeferred<Location?>()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!deferred.isCompleted) {
                    deferred.complete(location)
                }
            }
        }

        locationManager.requestSingleUpdate(
            provider,
            listener,
            Looper.getMainLooper()
        )

        val location = withTimeoutOrNull(15_000) {
            deferred.await()
        }

        locationManager.removeUpdates(listener)

        if (location == null)
            return """{"error":"timeout"}"""

        return buildJson(location)
    }
    fun findPhoneByName(context: Context, name: String): String? {

        val resolver = context.contentResolver

        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )

        cursor?.use {

            if (it.moveToFirst()) {

                return it.getString(0)
            }
        }

        return null
    }
    fun levenshtein(a: String, b: String): Int {

        val dp = Array(a.length + 1) {
            IntArray(b.length + 1)
        }

        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {

                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] +
                            if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
        }

        return dp[a.length][b.length]
    }

    fun levenshteinScore(a: String, b: String): Int {

        val dist = levenshtein(a, b)

        return 30 - dist.coerceAtMost(30)
    }

    fun matchScore(label: String, target: String): Int {

        val a = label.lowercase()
        val b = target.lowercase()

        return when {
            a == b -> 100
            a.startsWith(b) -> 70
            a.contains(b) -> 40
            else -> levenshteinScore(a, b)
        }
    }

    private fun newPhotoOutput(context: Context): Pair<Uri, File> {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val file = File(dir, "her_${System.currentTimeMillis()}.jpg")

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return uri to file
    }

    fun openApp(
        context: Context,
        target: String
    ): Boolean {

        val pm = context.packageManager

        val packageName = if (target.contains(".")) {
            target
        } else {
            findPackageByAppName(context, target)
        } ?: return false

        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: return false

        val act = requireActivity()

        act.runOnUiThread {
            act.startActivity(intent)
        }

        return true
    }

    suspend fun sendSms(
        context: Context,
        target: String,
        text: String
    ): Boolean {

        val ok = ensurePermissions(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )

        if (!ok) return false

        // ⭐ 解析目标：号码 or 联系人
        val phone = if (target.any { it.isDigit() }) {
            target
        } else {
            findPhoneByName(context, target)
        } ?: return false

        return try {

            val sms = SmsManager.getDefault()

            sms.sendTextMessage(
                phone,
                null,
                text,
                null,
                null
            )

            true

        } catch (t: Throwable) {

            false
        }
    }

    fun setAlarm(
        hour: Int,
        minute: Int,
        label: String? = null,
    ): Boolean {

        val act = requireActivity()

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)

            if (!label.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }

            // ⭐ 是否跳过确认界面（部分厂商可能无效）
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }

        return try {
            act.runOnUiThread {
                act.startActivity(intent)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 模仿 termux-battery-status：返回简单电量 JSON */
    fun getBatteryStatusJson(context: Context): String {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val statusIntent = context.registerReceiver(null, ifilter) ?: return ""
        val level = statusIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = statusIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = statusIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = statusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val temp = statusIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
        val voltage = statusIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        return """{"percentage":$percent,"plugged":$plugged,"charging":$charging,"temperature":$temp,"voltage":$voltage}"""
    }

    /** 模仿 termux-clipboard-get */
    fun clipboardGet(context: Context): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount <= 0) return ""
        return clip.getItemAt(0).coerceToText(context).toString()
    }

    /** 模仿 termux-clipboard-set */
    fun clipboardSet(context: Context, text: String): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = ClipData.newPlainText("termux", text)
        cm.setPrimaryClip(clip)
        return true
    }

    /** 模仿 termux-toast */
    fun toast(context: Context, msg: String) {
        val act = activityRef?.get()
        if (act != null) {
            act.runOnUiThread {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** 模仿 termux-vibrate，默认 400ms */
    fun vibrate(context: Context, millis: Long = 400L): Boolean {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(millis)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
