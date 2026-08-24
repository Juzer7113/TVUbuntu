package com.ubuntucontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启接收器。
 *
 * BOOT_COMPLETED（含部分厂商快启广播）后做两件事：
 *  1) 开机自动获取 ADB（与「软件启动时启动 Ubuntu」解耦，仅拿授权不启动系统）：
 *     无 root 且「ADB 自动获取」开关开启时后台尝试无线 TLS / 经典 5555，让盒子开机即
 *     「ADB 就绪」，配合自启即可一键拉起 Ubuntu。
 *  2) 开机自启 Ubuntu：「开机自启」开关开启且当前未运行时，拉起 BootService（前台服务）
 *     在后台按运行模式（root chroot / proot / adb 路径）启动 Ubuntu。
 *     Android 10+ 禁止从广播直接 startActivity / 长时间后台工作，必须经前台服务；
 *     BOOT_COMPLETED 广播属系统豁免，允许 startForegroundService。
 */
class AdbBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return
        Thread {
            runCatching {
                val hasRoot = ShellExecutor.checkRootAccess()

                // 1) 开机自动获取 ADB（保持 v1.5.1 行为，不启动 Ubuntu）
                if (!hasRoot && AdbAutoAcquire.isEnabled(context)) {
                    AdbAutoAcquire.acquire(context, {}, {})
                }
                // 网络 ADB 开关：开启后按配置地址端口自持（开机即把 adbd 切到该端口）
                if (AdbNetworkEnabler.isEnabled(context)) {
                    AdbNetworkEnabler.enable(context)
                }

                // 2) 开机自启 Ubuntu（「开机自启」开关开启时）
                if (UbuntuService.getAutoStart(context)) {
                    val svc = Intent(context, BootService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svc)
                    } else {
                        context.startService(svc)
                    }
                }
            }
        }.start()
    }
}
