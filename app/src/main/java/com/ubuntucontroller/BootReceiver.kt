package com.ubuntucontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * @deprecated v1.2.6 起自启语义改为「软件启动时启动 Ubuntu」：是否自动启动由 MainActivity
 * 打开时按设置开关决定，不再监听系统开机广播。本类与 BootService 已从 AndroidManifest 移除
 * 注册/声明（不会再被触发），保留文件仅作历史参考，可安全删除。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // v1.2.6 起废弃：开机自启已移除，这里不再做任何事。
    }
}
