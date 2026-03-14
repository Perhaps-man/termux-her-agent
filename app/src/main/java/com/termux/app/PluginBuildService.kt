package com.termux.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.termux.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 退出界面后触发的插件构建服务：调用 AI 生成文件列表，用 Termux 编译为 dex，生成 properties。
 * 下次启动时 tryStartDynamicPlugin 会加载该 dex 作为 Activity。
 */
class PluginBuildService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sendStartNotification()
        startForeground(NOTIFICATION_ID, createNotification())
        serviceScope.launch(Dispatchers.IO) {
            try {
                PluginBuildPipeline.run(applicationContext)
            } catch (t: Throwable) {
                Log.e(TAG, "插件构建失败", t)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    /** 发送「开始构建」通知，便于在返回桌面时立即看到 */
    private fun sendStartNotification() {
        val channelId = "plugin_build_start"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "插件构建", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val n = NotificationCompat.Builder(this, channelId)
            .setContentTitle("开始构建")
            .setContentText("插件正在后台编译…")
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID_START, n)
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "插件构建",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在构建插件…")
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PluginBuildService"
        private const val CHANNEL_ID = "plugin_build"
        private const val NOTIFICATION_ID = 9001
        private const val NOTIFICATION_ID_START = 9000
    }
}
