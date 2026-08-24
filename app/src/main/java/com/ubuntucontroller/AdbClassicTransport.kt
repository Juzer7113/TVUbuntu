package com.ubuntucontroller

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream as LibAdbStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 基于 libadb-android 的经典 TCP（5555）传输实现（AdbTransport）。
 *
 * 与 AdbWirelessTransport 同根：底层都走 libadb-android 的 AdbConnection/AdbStream 流 API，
 * 只是连接方式不同——本类用经典 TCP [connect][io.github.muntashirakon.adb.AbsAdbConnectionManager.connect]
 * （对应应用管家 ba/u.java 的 L() 主路径），而无线通道用 TLS 配对后 connectTls。
 *
 * 为什么用库而不是手搓 AdbClient：libadb-android 的 connect() 自带握手 + 授权 + 超时
 * （setTimeout），是应用管家「成功获取 ADB 授权」的可靠实现；手搓 AdbClient 缺超时、握手
 * 需自写，正是此前「等弹窗卡死」的 bug 根源。
 *
 * 推送二进制的关键修复（本类与原 AdbClient 的通病）：
 *   - push 走 ADB sync 协议（SEND/DATA/DONE/OKAY，见 AdbSyncPush.kt），即官方 adb push 通道，
 *     二进制安全、完成握手明确。不用 shell 流 + cat：CLSE 在部分 Android 版本不会让 cat 的
 *     stdin 收到 EOF，导致 ins.read() 永久阻塞 —— 「推送 proot 二进制」连续两次卡死的根因。
 *   - push/exec 全程包在 worker 线程 + join(timeout) 内，超时即强制取消连接，绝不阻塞部署线程。
 */
class AdbClassicTransport(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Long = 60_000L
) : AdbTransport {

    private var conn: AdbConnection? = null
    private var persistentStream: LibAdbStream? = null
    private var persistentThread: Thread? = null

    /**
     * 建立经典 TCP 连接并完成 ADB 授权握手。
     *
     * 完整复刻应用管家 ba/u.java 的 L()「招数梯子」——这是它「成功获取 ADB 授权」且从不卡死的根本：
     *   招1 直连经典 5555；失败 →
     *   招2 库自带 autoConnect（root 开 tcp / 已连 USB adb 时智能重连）；
     *      若设备要求配对（AdbPairingRequiredException）→ 抛「需要配对码」，上层弹无线配对窗；
     *   仍失败且本机有 root →
     *   招4 用 root 把 adbd 切到【配置端口】(setprop service.adb.tcp.port + ctl.restart adbd) 再 autoConnect；
     *   最后 → 招5 再直连一次（用户可能刚好点完「允许 USB 调试」）。
     * 每步均受 setTimeout 约束，设备不可达/未授权不会无限阻塞。
     */
    override fun connect() {
        RuntimeLog.append("adb", "经典通道 connect 开始 host=$host port=$port")
        val mgr = UbuntuAdbManager(context)
        mgr.setApi(Build.VERSION.SDK_INT)
        mgr.setTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        val ok = try {
            mgr.connect(host, port)                       // 招1：直连经典 5555
        } catch (_: Exception) {                          // 招1 失败（含设备要求配对，但先让库再试）
            try {
                mgr.autoConnect(context, 3333L)           // 招2：库智能重连
            } catch (pair: AdbPairingRequiredException) {
                throw AdbClient.AdbException("连接adb需要配对码")   // 招3：上层弹配对码弹窗
            } catch (_: Exception) {
                try {                                      // 招4：真·root 把 adbd 切到配置端口并重启
                    if (AdbNetworkEnabler.enableViaRoot(context)) Thread.sleep(2000)
                } catch (_: Throwable) {
                }
                try {
                    mgr.autoConnect(context, 3333L)        // 招4b：重启后再智能重连
                } catch (pair: AdbPairingRequiredException) {
                    throw AdbClient.AdbException("连接adb需要配对码") // 招3b
                } catch (_: Exception) {
                    mgr.connect(host, port)               // 招5：用户可能刚点完允许
                }
            }
        }
        if (!ok) {
            RuntimeLog.append("adb", "经典通道 connect 失败 host=$host port=$port")
            throw AdbClient.AdbException("adb 经典通道连接/授权失败（${host}:${port}）")
        }
        conn = mgr.adbConnection ?: run {
            RuntimeLog.append("adb", "经典通道连接为空 host=$host port=$port")
            throw AdbClient.AdbException("adb 经典通道连接为空（${host}:${port}）")
        }
        RuntimeLog.append("adb", "经典通道 connect 成功 host=$host port=$port")
    }

    override fun push(local: File, remote: String, mode: String): Boolean {
        val c = conn ?: run {
            RuntimeLog.append("adb", "push 失败：未连接 ($remote)")
            throw AdbClient.AdbException("未连接")
        }
        // sync 协议（SEND/DATA/DONE/OKAY）——官方 adb push 通道，二进制安全、有明确完成握手。
        // 不用 shell,raw:cat：CLSE 在部分 Android 版本不会让 cat 的 stdin 收到 EOF，导致永久卡死。
        return c.pushSync(local, remote, mode)
    }

    override fun exec(cmd: String, timeoutMs: Long): String {
        val c = conn ?: throw AdbClient.AdbException("未连接")
        val out = StringBuilder()
        val worker = Thread {
            try {
                // 文本命令走标准 shell:（PTY 可接受）；raw 亦可但部分命令依赖 tty，保持现状
                val stream = c.open("shell:$cmd")
                val ins = stream.openInputStream()
                val b = ByteArray(8192)
                var r: Int
                while (ins.read(b).also { r = it } != -1) {
                    out.append(String(b, 0, r, Charsets.UTF_8))
                }
                try { stream.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e("AdbClassicTransport", "exec 失败: ${e.message}")
            }
        }
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) {
            RuntimeLog.append("adb", "exec 超时（${timeoutMs / 1000}s）：强制取消连接；cmd=${cmd.take(160)}")
            try { cancel() } catch (_: Exception) {}
            try { worker.join(2000) } catch (_: Exception) {}
        }
        return out.toString()
    }

    override fun startPersistent(cmd: String, logLine: (String) -> Unit): Boolean {
        val c = conn ?: throw AdbClient.AdbException("未连接")
        RuntimeLog.append("adb", "startPersistent：经 adb 常驻启动 proot；cmd=${cmd.take(160)}")
        // shell,raw: 关闭 PTY：proot 不需要 tty；raw 流在命令退出时干净关闭，
        // 避免 PTY 会话残留导致 adbd 的 shell 服务被拖住（此前探测/readLog 卡 120s 的诱因之一）
        val stream = c.open("shell,raw:$cmd")
        val ins = stream.openInputStream()
        persistentStream = stream
        val reader = Thread {
            try {
                val b = ByteArray(8192)
                var r: Int
                while (ins.read(b).also { r = it } != -1) {
                    logLine(String(b, 0, r, Charsets.UTF_8))
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()
        persistentThread = reader
        return true
    }

    override fun close() {
        try { persistentStream?.close() } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        persistentStream = null
        persistentThread = null
        conn = null
    }

    override fun cancel() {
        try { conn?.close() } catch (_: Exception) {}
    }

    override fun isConnected(): Boolean = conn?.isConnected() == true
}
