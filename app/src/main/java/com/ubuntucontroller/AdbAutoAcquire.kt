package com.ubuntucontroller

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ADB 自动获取编排器（开 App / 开机触发）。
 *
 * 全局统一定序（与应用管家「双通道」同构，但采用本 App 固定密钥 + 固件烘焙路线）：
 *   1) 尽量以 WRITE_SECURE_SETTINGS 开启无障碍自动点击（免去在盒子上手动点「允许 USB 调试」）；
 *   2) Android 11+ 且设备开启无线调试 → 走 TLS 配对连接（免弹窗，开屏即可用）；
 *   3) 否则经典 5555：若 adbd 未监听，先尝试「网络 ADB 自愈」（Root setprop）再连；
 *   4) 经典通道鉴权（AdbProotService 内部等待用户/自动点击，首次手动授权属正常）→ 成功后把 5555 持久化。
 *
 * 两种调用场景：
 *   - MainActivity 打开时：带 UI 回调，刷新 ADB 按钮；
 *   - AdbBootReceiver 开机时：带空回调，纯后台获取（不启动 Ubuntu）。
 */
object AdbAutoAcquire {

    enum class Mode { WIRELESS, CLASSIC, NONE }

    data class Result(val success: Boolean, val mode: Mode, val message: String)

    private const val PREFS = "adb_auto_acquire"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_MODE = "last_mode"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 默认开启：开 App 即尝试获取 ADB（用户可在配置关闭）。 */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun getLastMode(context: Context): Mode {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_MODE, "") ?: ""
        return when (s) {
            "wireless" -> Mode.WIRELESS
            "classic" -> Mode.CLASSIC
            else -> Mode.NONE
        }
    }

    fun setLastMode(context: Context, mode: Mode) {
        val v = when (mode) {
            Mode.WIRELESS -> "wireless"
            Mode.CLASSIC -> "classic"
            else -> ""
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_MODE, v).apply()
    }

    /**
     * 自动获取 ADB。后台线程执行；[onProgress]/[onDone] 均在主线程回调。
     * 幂等：重复调用安全（内部每次新建连接）。
     */
    fun acquire(
        context: Context,
        onProgress: (String) -> Unit = {},
        onDone: (Result) -> Unit = {}
    ) {
        val progress: (String) -> Unit = { msg -> mainHandler.post { onProgress(msg) } }
        val done: (Result) -> Unit = { r -> mainHandler.post { onDone(r) } }

        Thread {
            // 1) 尽量开启无障碍自动点击（无 WRITE_SECURE_SETTINGS 时静默失败，退化为手动点）
            try { AdbAccessibilityHelper.tryEnable(context) } catch (_: Throwable) {}

            // 2) 无线 TLS（Android 11+，免弹窗）：开屏自动直连
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val latch = CountDownLatch(1)
                var wirelessOk = false
                AdbProotService.connectWirelessIfPaired(context) { ok ->
                    wirelessOk = ok
                    latch.countDown()
                }
                latch.await(12, TimeUnit.SECONDS)
                if (wirelessOk) {
                    setLastMode(context, Mode.WIRELESS)
                    done(Result(true, Mode.WIRELESS, context.getString(R.string.adb_wireless_ready)))
                    return@Thread
                }
            }

            // 3) 经典 5555：不可达先自愈（Root 开 5555），再鉴权
            if (!AdbProotService.isAdbReachable(context)) {
                progress("设备 adbd 未在 5555 监听，尝试自动开启网络 ADB…")
                if (AdbNetworkEnabler.enableViaRoot(context)) {
                    Thread.sleep(3000) // 等 adbd 重启
                }
            }

            // 4) 经典通道鉴权（等待用户/自动点击，首次手动授权正常）
            val latch2 = CountDownLatch(1)
            var authRes: AdbProotService.AdbAuthResult? = null
            AdbProotService.requestAdbAuth(context) { r ->
                authRes = r
                latch2.countDown()
            }
            latch2.await(100, TimeUnit.SECONDS)
            val ar = authRes
            when {
                ar != null && ar.state == AdbProotService.AuthState.CONNECTED -> {
                    setLastMode(context, Mode.CLASSIC)
                    // 成功连通后把 5555 持久化（persist.* 多数设备重启仍生效），下次自动直连
                    AdbNetworkEnabler.enableViaAdb(context)
                    done(Result(true, Mode.CLASSIC, ar.message))
                }
                ar != null && ar.state == AdbProotService.AuthState.AWAITING -> {
                    done(
                        Result(
                            false, Mode.CLASSIC,
                            "ADB 待授权：请在设备点『允许 USB 调试』（已尝试自动点击），之后会自动连接"
                        )
                    )
                }
                else -> done(Result(false, Mode.NONE, ar?.message ?: "ADB 获取失败"))
            }
        }.start()
    }
}
