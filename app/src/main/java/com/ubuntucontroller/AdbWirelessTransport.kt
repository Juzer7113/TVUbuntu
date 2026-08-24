package com.ubuntucontroller

import android.util.Log
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream as LibAdbStream
import java.io.File

/**
 * 基于 libadb-android 的无线 TLS 传输实现（AdbTransport）。
 *
 * 连接由 AdbWirelessPairing 经 mDNS + TLS 配对成功后建立，这里只负责在其上跑
 * shell 命令 / 推送文件 / 常驻启动 proot。读写基于 AdbStream 的
 * openInputStream()/openOutputStream()。
 *
 * 推送走 ADB sync 协议（SEND/DATA/DONE/OKAY，见 AdbSyncPush.kt）——官方 adb push 通道，
 * 二进制安全、完成握手明确，与 AdbClassicTransport 保持一致，不用 shell,raw:cat。
 */
class AdbWirelessTransport(private val conn: AdbConnection) : AdbTransport {

    private var persistentStream: LibAdbStream? = null
    private var persistentThread: Thread? = null

    override fun connect() {
        // 连接已在配对阶段建立，这里为空操作
    }

    override fun push(local: File, remote: String, mode: String): Boolean {
        // sync 协议（SEND/DATA/DONE/OKAY）——官方 adb push 通道，二进制安全、有明确完成握手。
        // 不用 shell,raw:cat：CLSE 在部分 Android 版本不会让 cat 的 stdin 收到 EOF，导致永久卡死。
        return conn.pushSync(local, remote, mode)
    }

    override fun exec(cmd: String, timeoutMs: Long): String {
        val out = StringBuilder()
        val worker = Thread {
            try {
                val stream = conn.open("shell:$cmd")
                val ins = stream.openInputStream()
                val b = ByteArray(8192)
                var r: Int
                while (ins.read(b).also { r = it } != -1) {
                    out.append(String(b, 0, r, Charsets.UTF_8))
                }
                try { stream.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e("AdbWirelessTransport", "exec 失败: ${e.message}")
            }
        }
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) {
            RuntimeLog.append("adb", "exec(无线) 超时（${timeoutMs / 1000}s）：cmd=${cmd.take(160)}")
            try { cancel() } catch (_: Exception) {}
            try { worker.join(2000) } catch (_: Exception) {}
        }
        return out.toString()
    }

    override fun startPersistent(cmd: String, logLine: (String) -> Unit): Boolean {
        RuntimeLog.append("adb", "startPersistent(无线)：cmd=${cmd.take(160)}")
        // shell,raw: 关闭 PTY，避免 PTY 会话残留拖住 adbd shell 服务（与经典通道一致）
        val stream = conn.open("shell,raw:$cmd")
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
        try { conn.close() } catch (_: Exception) {}
        persistentStream = null
        persistentThread = null
    }

    override fun cancel() {
        try { conn.close() } catch (_: Exception) {}
    }

    override fun isConnected(): Boolean = conn.isConnected()
}
