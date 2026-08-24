package com.ubuntucontroller

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * @deprecated v1.2.6 起自启语义改为「软件启动时启动 Ubuntu」，开机广播链路（BootReceiver →
 * BootService）已从 AndroidManifest 移除，本类不会再被启动。保留文件仅作历史参考，可安全删除。
 * 原功能：在后台完成 Ubuntu rootfs 部署与 SSH 启动，避免 Android 10+ 限制从
 * BroadcastReceiver 直接启动 Activity。
 */
class BootService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationId = 1
    private val channelId = "ubuntu_boot"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!UbuntuService.getAutoStart(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // targetSdk 34：Android 14+ 必须用带类型的 startForeground（manifest 已声明 specialUse），
        // 否则抛 MissingForegroundServiceTypeException 导致开机自启崩溃
        val notification = buildNotification(getString(R.string.boot_service_starting), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }

        serviceScope.launch {
            UbuntuRuntime.start(this@BootService) { pct, msg ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(notificationId, buildNotification(msg, pct))
            }
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.boot_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.boot_service_channel_desc)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String, progress: Int): android.app.Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.boot_service_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_ubuntu)
            .setOngoing(true)
            .setProgress(100, progress, progress <= 0)
            .setOnlyAlertOnce(true)
            .build()
    }
}
