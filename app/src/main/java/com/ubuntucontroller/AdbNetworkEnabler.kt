package com.ubuntucontroller

import android.content.Context

/**
 * 网络 ADB「自愈」：在已获得任意特权 shell（已授权 adb / Root）时，
 * 把 adbd 切到 TCP 5555 并持久化（persist.* 多数设备重启后仍生效），使后续开软件/开机可自动直连。
 *
 * 与应用管家 ba/u.java:221-226 的 setprop service.adb.tcp.port 5555 + ctl.restart adbd 同构。
 */
object AdbNetworkEnabler {

    private const val CMD =
        "setprop service.adb.tcp.port 5555; " +
        "setprop persist.adb.tcp.port 5555; " +
        "setprop ctl.restart adbd; " +
        "exit 0"

    /** 经典 adb 已授权时，借其 shell 开 5555（无需额外权限）。 */
    fun enableViaAdb(context: Context): Boolean {
        return try {
            val adb = AdbClient(
                UbuntuService.getAdbHost(context),
                UbuntuService.getAdbPort(context),
                AdbKeyHelper(context)
            )
            adb.connect()
            adb.exec(CMD)
            adb.close()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Root shell 开 5555。 */
    fun enableViaRoot(context: Context): Boolean {
        return try {
            val r = ShellExecutor.executeAsRoot(CMD)
            r.exitCode == 0 || r.output.isNotBlank()
        } catch (_: Throwable) {
            false
        }
    }
}
