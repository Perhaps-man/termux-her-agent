package com.termux.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.LocalServerSocket
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.content.ContentValues
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.termux.api.TermuxApiReceiver
import com.termux.api.apis.AudioAPI
import com.termux.api.apis.BatteryStatusAPI
import com.termux.api.apis.BrightnessAPI
import com.termux.api.apis.CallLogAPI
import com.termux.api.apis.CameraInfoAPI
import com.termux.api.apis.ClipboardAPI
import com.termux.api.apis.ContactListAPI
import com.termux.api.apis.DownloadAPI
import com.termux.api.apis.InfraredAPI
import com.termux.api.apis.LocationAPI
import com.termux.api.apis.MediaScannerAPI
import com.termux.api.apis.SmsInboxAPI
import com.termux.api.apis.TelephonyAPI
import com.termux.api.apis.TorchAPI
import com.termux.api.apis.VibrateAPI
import com.termux.api.apis.VolumeAPI
import com.termux.api.apis.WifiAPI
import com.termux.api.util.ResultReturner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone

/**
 * In-process bridge to the official termux-api library source.
 *
 * API classes that use ResultReturner.returnData() are called via a local Unix socket pair
 * (the same mechanism used by the real termux-api CLI).  APIs not available in termux-api
 * (file I/O, HTTP, alarms, etc.) use direct Android calls.
 */
object TermuxApiRunner {

    /** A no-op receiver whose goAsync() returns null (safe to use outside onReceive). */
    private val noop: TermuxApiReceiver by lazy { TermuxApiReceiver() }
    private const val SENSOR_READ_TIMEOUT_MS = 5_000L

    // ─── Core socket-capture helper ──────────────────────────────────────────

    /**
     * Sets up a LocalServerSocket, injects its abstract address as "socket_output" into
     * the intent, invokes [apiCall] (which starts a background thread to connect and write),
     * then reads and returns whatever the API wrote.
     */
    suspend fun capture(
        context: Context,
        timeoutMs: Long = 15_000L,
        intentSetup: Intent.() -> Unit = {},
        apiCall: (Intent) -> Unit
    ): String = coroutineScope {
        ResultReturner.setContext(context)
        val socketName = "her_api_${System.nanoTime()}"
        val serverSocket = LocalServerSocket(socketName)

        val intent = Intent().apply {
            putExtra("socket_output", socketName)
            intentSetup()
        }

        val resultDeferred = async(Dispatchers.IO) {
            try {
                serverSocket.accept().use { clientSocket ->
                    clientSocket.inputStream.bufferedReader().readText()
                }
            } finally {
                runCatching { serverSocket.close() }
            }
        }

        try {
            apiCall(intent)
        } catch (e: Throwable) {
            resultDeferred.cancel()
            runCatching { serverSocket.close() }
            return@coroutineScope "error: ${e.message}"
        }

        val result = withTimeoutOrNull(timeoutMs) { resultDeferred.await() }
        when {
            result == null -> "error: timed out after ${timeoutMs}ms"
            result.isBlank() -> "ok"
            else -> result
        }
    }

    // ─── Battery ─────────────────────────────────────────────────────────────

    suspend fun batteryStatus(context: Context): String =
        capture(context) { BatteryStatusAPI.onReceive(noop, context, it) }

    // ─── Clipboard ───────────────────────────────────────────────────────────

    suspend fun clipboardGet(context: Context): String =
        capture(context) { ClipboardAPI.onReceive(noop, context, it) }

    suspend fun clipboardSet(context: Context, text: String): String =
        capture(context, intentSetup = { putExtra("text", text) }) {
            ClipboardAPI.onReceive(noop, context, it)
        }

    // ─── Contacts ────────────────────────────────────────────────────────────

    suspend fun contactList(context: Context): String =
        capture(context) { ContactListAPI.onReceive(noop, context, it) }

    // ─── SMS inbox ───────────────────────────────────────────────────────────

    suspend fun smsInbox(context: Context, limit: Int = 25, offset: Int = 0): String {
        val raw = capture(
            context,
            timeoutMs = if (limit > 100) 30_000L else 15_000L,
            intentSetup = {
                putExtra("limit", limit)
                putExtra("offset", offset)
            }
        ) {
            SmsInboxAPI.onReceive(noop, context, it)
        }
        if (raw == "[" || (raw.startsWith("[") && !raw.trimEnd().endsWith("]"))) {
            return "error: SMS 返回被截断，请查看 logcat 中 SmsInboxAPI 日志"
        }
        return raw
    }

    // ─── SMS send (direct — official API needs WithStringInput socket_input) ─

    fun smsSend(context: Context, phone: String, text: String): String =
        runCatching {
            @Suppress("DEPRECATION")
            val sms = SmsManager.getDefault()
            sms.sendTextMessage(phone, null, text, null, null)
            "短信已发送"
        }.getOrElse { "短信发送失败: ${it.message}" }

    // ─── Location ────────────────────────────────────────────────────────────

    suspend fun location(
        context: Context,
        provider: String = "gps",
        request: String = "once"
    ): String = capture(context, 20_000L,
        intentSetup = {
            putExtra("provider", provider)
            putExtra("request", request)
        }
    ) { LocationAPI.onReceive(noop, context, it) }

    // ─── WiFi ────────────────────────────────────────────────────────────────

    suspend fun wifiConnectionInfo(context: Context): String =
        capture(context) { WifiAPI.onReceiveWifiConnectionInfo(noop, context, it) }

    suspend fun wifiScanInfo(context: Context): String =
        capture(context) { WifiAPI.onReceiveWifiScanInfo(noop, context, it) }

    suspend fun wifiEnable(context: Context, enabled: Boolean): String =
        capture(context, intentSetup = { putExtra("enabled", enabled) }) {
            WifiAPI.onReceiveWifiEnable(noop, context, it)
        }

    // ─── Telephony ───────────────────────────────────────────────────────────

    suspend fun telephonyDeviceInfo(context: Context): String =
        capture(context) { TelephonyAPI.onReceiveTelephonyDeviceInfo(noop, context, it) }

    suspend fun telephonyCellInfo(context: Context): String =
        capture(context) { TelephonyAPI.onReceiveTelephonyCellInfo(noop, context, it) }

    // ─── Media scanner ───────────────────────────────────────────────────────

    suspend fun mediaScan(context: Context, path: String): String =
        capture(context, 10_000L,
            intentSetup = { putStringArrayListExtra("paths", arrayListOf(path)) }
        ) { MediaScannerAPI.onReceive(noop, context, it) }

    // ─── Download ────────────────────────────────────────────────────────────

    suspend fun download(context: Context, url: String, title: String = ""): String =
        capture(context, 60_000L,
            intentSetup = {
                data = Uri.parse(url)
                if (title.isNotBlank()) putExtra("title", title)
            }
        ) { DownloadAPI.onReceive(noop, context, it) }

    // ─── Torch ──────────────────────────────────────────────────────────────
    // 直接调用系统 API，避免 noteDone/goAsync 在非广播场景下阻塞
    fun torch(context: Context, enabled: Boolean): String = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return "手电筒不可用"
        val id = cm.cameraIdList?.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "未找到支持闪光灯的摄像头"
        cm.setTorchMode(id, enabled)
        if (enabled) "手电筒已开启" else "手电筒已关闭"
    }.getOrElse { e ->
        if (e is CameraAccessException) "手电筒不可用: ${e.message}" else "手电筒异常: ${e.message}"
    }

    // ─── Vibrate ─────────────────────────────────────────────────────────────
    // 直接调用 Vibrator，避免 noteDone/goAsync 阻塞
    fun vibrate(context: Context, durationMs: Int = 400): String = runCatching {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return "震动不可用"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs.toLong())
        }
        "已震动 ${durationMs}ms"
    }.getOrElse { "震动失败: ${it.message}" }

    // ─── Brightness ──────────────────────────────────────────────────────────
    // 直接写 Settings.System，避免 noteDone/goAsync 阻塞导致一直等待
    fun brightness(context: Context, level: Int): String = runCatching {
        val cr = context.contentResolver
        val clamped = level.coerceIn(0, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, clamped)
        "亮度已设置为 $clamped"
    }.getOrElse { "亮度设置失败: ${it.message}" }

    // ─── Volume ──────────────────────────────────────────────────────────────
    // 直接调用 AudioManager，避免 noteDone/goAsync 阻塞
    private val volumeStreamMap = mapOf(
        "alarm" to AudioManager.STREAM_ALARM,
        "music" to AudioManager.STREAM_MUSIC,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "ring" to AudioManager.STREAM_RING,
        "system" to AudioManager.STREAM_SYSTEM,
        "call" to AudioManager.STREAM_VOICE_CALL
    )

    /** Set a stream's volume (direct, no socket). */
    fun setVolume(context: Context, stream: String, level: Int): String = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "音频服务不可用"
        val streamType = volumeStreamMap[stream.lowercase()] ?: return "未知声道: $stream"
        val maxVol = am.getStreamMaxVolume(streamType)
        val clamped = level.coerceIn(0, maxVol)
        am.setStreamVolume(streamType, clamped, 0)
        "已设置 $stream 音量为 $clamped"
    }.getOrElse { "设置音量失败: ${it.message}" }

    /** Get current volume levels for all streams. */
    suspend fun getVolumes(context: Context): String =
        capture(context) { VolumeAPI.onReceive(noop, context, it) }

    // ─── Toast (direct — official ToastAPI uses WithStringInput) ─────────────

    fun toast(context: Context, text: String): String {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
        return "toast 已显示"
    }

    // ─── TTS (direct — official TextToSpeechAPI starts a service) ────────────

    private var tts: TextToSpeech? = null

    fun ttsSpeak(context: Context, text: String): String =
        runCatching {
            if (tts == null) tts = TextToSpeech(context) {}
            tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, "her-tts")
            "tts 开始播报"
        }.getOrElse { "tts 失败: ${it.message}" }

    fun ttsStop(): String = runCatching {
        if (tts == null) "tts 未初始化"
        else {
            tts?.stop()
            "tts 已停止"
        }
    }.getOrElse { "tts 停止失败: ${it.message}" }

    fun ttsEngines(context: Context): String =
        runCatching {
            val t = TextToSpeech(context) {}
            val arr = JSONArray()
            t.engines.forEach { arr.put(it.label) }
            arr.toString()
        }.getOrDefault("[]")

    // ─── Notification (direct — official NotificationAPI uses R.layout.*) ────

    fun showNotification(context: Context, title: String, text: String): String =
        runCatching {
            val channelId = "her_termux_channel"
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Her Notifications",
                        NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            nm.notify(
                (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build()
            )
            "通知已显示"
        }.getOrElse { "通知显示失败: ${it.message}" }

    fun removeNotification(context: Context, id: Int): String = runCatching {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
        "通知已移除"
    }.getOrElse { "通知移除失败: ${it.message}" }

    // ─── Wallpaper (direct — official WallpaperAPI has extra deps) ───────────

    fun setWallpaper(context: Context, path: String, mode: String): String =
        runCatching {
            val bitmap = BitmapFactory.decodeFile(path) ?: return "壁纸设置失败: 无法读取图片"
            val wm = WallpaperManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val flag = if (mode.equals("lock", ignoreCase = true))
                    WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
                wm.setBitmap(bitmap, null, true, flag)
            } else {
                wm.setBitmap(bitmap)
            }
            "壁纸已设置 ($mode)"
        }.getOrElse { "壁纸设置失败: ${it.message}" }

    // ─── Media player (direct — official MediaPlayerAPI uses PluginUtils) ────

    private var mediaPlayer: MediaPlayer? = null

    fun mediaPlay(path: String): String = runCatching {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply { setDataSource(path); prepare(); start() }
        "开始播放: $path"
    }.getOrElse { "播放失败: ${it.message}" }

    fun mediaStop(): String = runCatching {
        if (mediaPlayer == null) {
            "当前没有播放中的媒体"
        } else {
            mediaPlayer?.run { stop(); release() }
            mediaPlayer = null
            "媒体已停止"
        }
    }.getOrElse { "停止播放失败: ${it.message}" }

    // ─── Microphone recorder (direct — official MicRecorderAPI uses activities)

    private var recorder: MediaRecorder? = null

    fun micRecordStart(context: Context): String = runCatching {
        val file = File(context.filesDir, "record.mp3")
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare(); start()
        }
        "录音已开始: ${file.absolutePath}"
    }.getOrElse { "录音启动失败: ${it.message}" }

    fun micRecordStop(): String = runCatching {
        if (recorder == null) {
            "当前没有进行中的录音"
        } else {
            recorder?.run { stop(); release() }
            recorder = null
            "录音已停止"
        }
    }.getOrElse { "录音停止失败: ${it.message}" }

    // ─── Wake lock ───────────────────────────────────────────────────────────

    private var wakeLock: PowerManager.WakeLock? = null

    fun wakeLock(context: Context): String = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Her::WakeLock").also { it.acquire() }
        "wake lock 已启用"
    }.getOrElse { "wake lock 启用失败: ${it.message}" }

    fun wakeUnlock(): String = runCatching {
        if (wakeLock == null) {
            "wake lock 未持有"
        } else {
            wakeLock?.release()
            wakeLock = null
            "wake lock 已释放"
        }
    }.getOrElse { "wake lock 释放失败: ${it.message}" }

    // ─── Sensor (direct — official SensorAPI uses PluginUtils) ──────────────

    fun sensorList(context: Context): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val arr = JSONArray()
        sm.getSensorList(Sensor.TYPE_ALL).forEach { arr.put(it.name) }
        return arr.toString()
    }

    fun sensorGet(context: Context, typeName: String): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getSensorList(Sensor.TYPE_ALL).firstOrNull {
            it.name.contains(typeName, ignoreCase = true)
        } ?: return "error: 未找到传感器 $typeName"
        val deferred = CompletableDeferred<FloatArray?>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                deferred.complete(event.values.clone())
                sm.unregisterListener(this)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        val values = runCatching {
            kotlinx.coroutines.runBlocking {
                withTimeoutOrNull(SENSOR_READ_TIMEOUT_MS) { deferred.await() }
            }
        }.getOrNull()
        sm.unregisterListener(listener)
        if (values == null) return "error: 读取传感器超时或失败"
        val arr = JSONArray()
        values.forEach { arr.put(it.toDouble()) }
        return arr.toString()
    }

    // ─── Open URL ────────────────────────────────────────────────────────────

    fun openUrl(context: Context, url: String): String = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        "已打开链接"
    }.getOrElse { "打开链接失败: ${it.message}" }

    // ─── Open app ────────────────────────────────────────────────────────────

    fun openApp(context: Context, target: String): String = runCatching {
        val pm = context.packageManager
        val pkg = if (target.contains('.')) target else findPackageByAppName(context, target)
            ?: return "未找到应用"
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "未找到应用启动入口"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        "已打开应用"
    }.getOrElse { "打开应用失败: ${it.message}" }

    private fun findPackageByAppName(context: Context, name: String): String? {
        val pm = context.packageManager
        return pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).maxByOrNull { matchScore(it.loadLabel(pm).toString(), name) }
            ?.activityInfo?.packageName
    }

    private fun matchScore(label: String, target: String): Int {
        val a = label.lowercase(); val b = target.lowercase()
        return when { a == b -> 100; a.startsWith(b) -> 70; a.contains(b) -> 40; else -> 0 }
    }

    // ─── Alarm ───────────────────────────────────────────────────────────────

    fun setAlarm(context: Context, hour: Int, minute: Int, label: String = ""): String =
        runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "闹钟已设置"
        }.getOrElse { "闹钟设置失败: ${it.message}" }

    // ─── Calendar ────────────────────────────────────────────────────────────

    fun addCalendarEvent(
        context: Context,
        title: String,
        startMillis: Long,
        endMillis: Long,
        description: String = ""
    ): String = runCatching {
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID), null, null, null
        ) ?: return "日历事件创建失败: 无可用日历"
        if (!cursor.moveToFirst()) { cursor.close(); return "日历事件创建失败: 无可用日历" }
        val calId = cursor.getLong(0); cursor.close()
        context.contentResolver.insert(
            CalendarContract.Events.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        )
        "日历事件已创建"
    }.getOrElse { "日历事件创建失败: ${it.message}" }

    // ─── Share file ──────────────────────────────────────────────────────────

    fun shareFile(context: Context, path: String): String = runCatching {
        val file = File(path); if (!file.exists()) return "分享失败: 文件不存在"
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "分享面板已打开"
    }.getOrElse { "分享失败: ${it.message}" }

    // ─── HTTP (direct — not in termux-api) ───────────────────────────────────

    fun httpGet(url: String): String = runCatching { URL(url).readText() }.getOrElse { "error: ${it.message}" }

    fun httpPost(url: String, body: String): String = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = "POST"; doOutput = true
            outputStream.use { it.write(body.toByteArray()) }
            inputStream.bufferedReader().readText()
        }
    }.getOrElse { "error: ${it.message}" }

    // ─── File ops (direct — not in termux-api) ───────────────────────────────

    fun fileExists(path: String): String = if (File(path).exists()) "true" else "false"
    fun fileRead(path: String): String = runCatching { File(path).readText() }.getOrElse { "error: ${it.message}" }
    fun fileWrite(path: String, text: String): String =
        runCatching { File(path).appendText(text); "写入成功" }.getOrElse { "写入失败: ${it.message}" }
    fun fileDelete(path: String): String = runCatching {
        if (File(path).delete()) "删除成功" else "删除失败"
    }.getOrElse { "删除失败: ${it.message}" }

    // ─── Audio info ──────────────────────────────────────────────────────────

    suspend fun audioInfo(context: Context): String =
        capture(context) { AudioAPI.onReceive(noop, context, it) }

    // ─── Camera info ─────────────────────────────────────────────────────────

    suspend fun cameraInfo(context: Context): String =
        capture(context) { CameraInfoAPI.onReceive(noop, context, it) }

    // ─── Call log ────────────────────────────────────────────────────────────

    suspend fun callLog(context: Context, limit: Int = 50, offset: Int = 0): String =
        capture(context, intentSetup = {
            putExtra("limit", limit)
            putExtra("offset", offset)
        }) { CallLogAPI.onReceive(context, it) }

    // ─── Infrared ────────────────────────────────────────────────────────────

    suspend fun infraredFrequencies(context: Context): String =
        capture(context) { InfraredAPI.onReceiveCarrierFrequency(noop, context, it) }

    /** pattern: comma-separated pulse/pause durations in microseconds, e.g. "9000,4500,560" */
    suspend fun infraredTransmit(context: Context, frequency: Int, pattern: String): String {
        val patternInts = pattern.split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
        return capture(context, intentSetup = {
            putExtra("frequency", frequency)
            putExtra("pattern", patternInts)
        }) { InfraredAPI.onReceiveTransmit(noop, context, it) }
    }
}
