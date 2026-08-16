package com.ubuntucontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启：监听系统开机广播，通过 BootService 在后台启动 Ubuntu。
 * 使用前台服务可适配 Android 10+ 对后台 Activity 启动的限制，
 * 同时避免用户普通打开 App 时自动触发启动。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        if (!UbuntuService.getAutoStart(context)) return

        val service = Intent(context, BootService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
