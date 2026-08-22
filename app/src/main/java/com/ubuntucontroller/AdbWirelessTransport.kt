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
 * openInputStream()/openOutputStream()，与 AdbClient 的 shell:cat 管道思路一致。
 */
class AdbWirelessTransport(private val conn: AdbConnection) : AdbTransport {

    private var persistentStream: LibAdbStream? = null
    private var persistentThread: Thread? = null

    override fun connect() {
        // 连接已在配对阶段建立，这里为空操作
    }

    override fun push(local: File, remote: String, mode: String): Boolean {
        val cmd = "shell:cat - > '$remote' && chmod $mode '$remote'; echo __UC_PUSH_DONE__"
        val stream = conn.open(cmd)
        val outs = stream.openOutputStream()
        val ins = stream.openInputStream()
        try {
            local.inputStream().use { fis ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    outs.write(buf, 0, read)
                }
                outs.flush()
            }
            // 关闭输出 → 设备侧 cat 读到 EOF → 执行 chmod + 回显 MARKER
            outs.close()
            val out = StringBuilder()
            val b = ByteArray(8192)
            var r: Int
            while (ins.read(b).also { r = it } != -1) {
                out.append(String(b, 0, r, Charsets.UTF_8))
            }
            return out.contains("__UC_PUSH_DONE__")
        } catch (e: Exception) {
            Log.e("AdbWirelessTransport", "push 失败: ${e.message}")
            return false
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
    }

    override fun exec(cmd: String): String {
        val stream = conn.open("shell:$cmd")
        val ins = stream.openInputStream()
        val out = StringBuilder()
        val b = ByteArray(8192)
        val deadline = System.currentTimeMillis() + 120_000L
        return try {
            var r: Int
            while (System.currentTimeMillis() < deadline) {
                r = ins.read(b)
                if (r == -1) break
                out.append(String(b, 0, r, Charsets.UTF_8))
            }
            out.toString()
        } catch (e: Exception) {
            out.toString()
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
    }

    override fun startPersistent(cmd: String, logLine: (String) -> Unit): Boolean {
        val stream = conn.open("shell:$cmd")
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

    fun isConnected(): Boolean = conn.isConnected()
}
