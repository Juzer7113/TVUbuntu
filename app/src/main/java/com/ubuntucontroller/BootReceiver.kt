package com.ubuntucontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：监听系统开机广播，自动拉起主界面。
 * 机顶盒开机后进入本 App，配合「开机自动启动 Ubuntu」开关可实现全自动运行。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val launch = Intent(context, MainActivity::class.java)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
    }
}
