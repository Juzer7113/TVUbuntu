package com.ubuntucontroller

import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 ADB sync 协议（SEND/DATA/DONE/OKAY）的二进制推送 —— 即官方 `adb push` 的通道。
 *
 * 为什么必须换掉 shell 流 + cat 方案：
 *   旧方案 `shell,raw:cat - > file` 依赖「关闭输出流(CLSE) → 设备侧 cat 的 stdin 收到 EOF → cat
 *   退出 → 回显 MARKER」这条链路。但在部分 Android 版本的 adbd 上，客户端发 CLSE 并不会让
 *   cat 的 stdin 收到 EOF（流被整体关闭而非半关），于是 cat 永远等待、`ins.read()` 无限阻塞 ——
 *   这是「推送 proot 二进制」连续两次卡死的根因。
 *
 * sync 协议是 adb 官方文件传输通道：
 *   - SEND 建远端文件（路径,模式）→ DATA 流式写内容 → DONE 结束（附 mtime）；
 *   - 设备在传输完成后回 OKAY/FAIL，完成握手明确，不依赖 EOF/PTY/close 语义；
 *   - 二进制安全（无 shell 解析、无终端规则），大文件（28MB rootfs）同样可靠。
 *
 * 所有 uint32 均为 little-endian；命令 id 为 4 个 ASCII 字符（"SEND"/"DATA"/"DONE"/"OKAY"/"FAIL"）。
 */
fun AdbConnection.pushSync(local: File, remote: String, mode: String): Boolean {
    val size = local.length()
    RuntimeLog.append("adb", "push(sync) 开始：${local.name} ${size}B → $remote (mode=$mode)")
    val done = AtomicBoolean(false)
    val worker = Thread {
        var stream: AdbStream? = null
        try {
            stream = open("sync:")
            val outs = stream.openOutputStream()
            val ins = stream.openInputStream()

            // 1) SEND：建立远端文件（路径,模式）。模式为 3 位八进制字符串，如 755 / 644。
            val target = "$remote,$mode"
            outs.write(packet("SEND", target.toByteArray(Charsets.UTF_8)))
            outs.flush()

            // 2) DATA：流式写入文件内容（32KB 块，4MB 打一次进度日志便于定位卡点）
            val buf = ByteArray(32 * 1024)
            var sent = 0L
            var nextLogAt = 4L * 1024 * 1024
            local.inputStream().use { fis ->
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    outs.write(packet("DATA", buf.copyOf(n)))
                    sent += n
                    if (sent >= nextLogAt) {
                        RuntimeLog.append("adb", "push(sync) 进度 ${sent}/${size} 字节")
                        nextLogAt += 4L * 1024 * 1024
                    }
                }
            }

            // 3) DONE：结束传输（附 mtime，int32 LE）
            val mtime = (System.currentTimeMillis() / 1000L).toInt()
            val mt = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mtime).array()
            outs.write(packet("DONE", mt))
            outs.flush()

            // 4) 读设备响应：OKAY=成功 / FAIL=失败（附错误信息）
            val hdr = ByteArray(8)
            if (!readFully(ins, hdr)) {
                RuntimeLog.append("adb", "push(sync) 失败：设备未返回响应 $remote")
                return@Thread
            }
            val id = String(hdr, 0, 4, Charsets.US_ASCII)
            when (id) {
                "OKAY" -> done.set(true)
                "FAIL" -> {
                    val len = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val msg = ByteArray(len)
                    if (readFully(ins, msg)) {
                        RuntimeLog.append("adb", "push(sync) FAIL：${String(msg, Charsets.UTF_8)}")
                    }
                }
                else -> RuntimeLog.append("adb", "push(sync) 未知响应：$id")
            }
        } catch (e: Exception) {
            RuntimeLog.append("adb", "push(sync) 异常：${e.message} ($remote)")
        } finally {
            try { stream?.close() } catch (_: Exception) {}
        }
    }
    worker.start()
    worker.join(PUSH_TIMEOUT_MS)
    if (worker.isAlive) {
        RuntimeLog.append("adb", "push(sync) 超时（${PUSH_TIMEOUT_MS / 1000}s）：强制断开连接 $remote")
        try { close() } catch (_: Exception) {}
        try { worker.join(2000) } catch (_: Exception) {}
        return false
    }
    val ok = done.get()
    RuntimeLog.append("adb", "push(sync) ${if (ok) "成功" else "失败"}：$remote")
    return ok
}

private const val PUSH_TIMEOUT_MS = 300_000L   // 5 分钟兜底：proot(0.9MB)+rootfs(28MB) 余量

/** 构造 sync 命令包：id(4B ASCII) + len(int32 LE) + data */
private fun packet(id: String, data: ByteArray): ByteArray {
    val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    hdr.put(id.toByteArray(Charsets.US_ASCII))
    hdr.putInt(data.size)
    return hdr.array() + data
}

/** 读满 dst；读到 EOF 返回 false */
private fun readFully(ins: InputStream, dst: ByteArray): Boolean {
    var off = 0
    while (off < dst.size) {
        val n = ins.read(dst, off, dst.size - off)
        if (n < 0) return false
        off += n
    }
    return true
}
