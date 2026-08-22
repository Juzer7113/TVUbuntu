package com.ubuntucontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自动获取 ADB（与「软件启动时启动 Ubuntu」解耦）：仅负责在 BOOT_COMPLETED 后后台尝试
 * 拿到 ADB 授权（无线 TLS 或经典 5555），不启动 Ubuntu。便于全志/H618 盒子、手机、平板开机即
 * 处于「ADB 就绪」状态，配合「软件启动时启动」即可一键拉起 Ubuntu。
 *
 * 仅当「ADB 自动获取」开关开启且设备无 root 时触发（有 root 走 chroot，不需要 adb 通道）。
 */
class AdbBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Thread {
            runCatching {
                val hasRoot = ShellExecutor.checkRootAccess()
                if (!hasRoot && AdbAutoAcquire.isEnabled(context)) {
                    AdbAutoAcquire.acquire(context, {}, {})
                }
            }
        }.start()
    }
}
