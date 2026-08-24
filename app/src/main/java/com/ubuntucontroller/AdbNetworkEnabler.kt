package com.ubuntucontroller

import android.content.Context

/**
 * 网络 ADB「自愈」：在已获得任意特权 shell（已授权 adb / Root）时，
 * 把 adbd 切到「配置地址端口」（默认 5555）并持久化（persist.* 多数设备重启后仍生效），
 * 使后续开软件/开机可自动直连。端口来自设置页，不再写死。
 *
 * 与应用管家 ba/u.java:221-226 的 setprop service.adb.tcp.port + ctl.restart adbd 同构。
 */
object AdbNetworkEnabler {

    private const val PREFS = "adb_net_adb"
    private const val KEY_ENABLED = "enabled"

    /** 开关：开软件/开机是否按配置地址端口自动开启网络 ADB。默认关闭，由设置页打开。 */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 端口取自配置（默认 5555），不再写死。 */
    private fun cmdFor(port: Int): String =
        "setprop service.adb.tcp.port $port; " +
        "setprop persist.adb.tcp.port $port; " +
        "setprop ctl.restart adbd; " +
        "exit 0"

    /** 经典 adb 已授权时，借其 shell 把 adbd 切到配置端口（无需额外权限）。host/port 取自配置。 */
    fun enableViaAdb(
        context: Context,
        host: String = UbuntuService.getAdbHost(context),
        port: Int = UbuntuService.getAdbPort(context)
    ): Boolean {
        return try {
            val adb = AdbClassicTransport(context, host, port)
            adb.connect()
            adb.exec(cmdFor(port))
            adb.close()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Root shell 把 adbd 切到配置端口（默认 5555）。 */
    fun enableViaRoot(context: Context, port: Int = UbuntuService.getAdbPort(context)): Boolean {
        return try {
            val r = ShellExecutor.executeAsRoot(cmdFor(port))
            r.exitCode == 0 || r.output.isNotBlank()
        } catch (_: Throwable) {
            false
        }
    }

    /** 一键开启：Root 优先，否则借已授权 adb。使用配置地址端口。 */
    fun enable(context: Context): Boolean =
        enableViaRoot(context) || enableViaAdb(context)
}
