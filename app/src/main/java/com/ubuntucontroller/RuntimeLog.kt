package com.ubuntucontroller

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内运行时日志。
 *
 * 问题背景：此前「复制日志」按钮只读设备侧 ubuntu.log，而部署卡在「推送 proot 二进制」
 * 阶段时该文件尚未生成，导致用户「复制不到日志、无法定位问题」。
 *
 * 本单例在 App 进程内记录每一步（启动 / ADB 获取 / 推送 / 启动 / 错误），既保留在内存
 * 环形缓冲供按钮即时复制，又持续落盘到外部私有目录 ubuntu_runtime.log，即使界面卡死也可
 * `adb pull <path>` 取回。所有方法自带异常吞掉，绝不因日志失败影响主流程。
 */
object RuntimeLog {
    private const val MAX_LINES = 3000
    private const val MAX_DISK_BYTES = 2L * 1024 * 1024 // 2MB 轮转阈值
    private val buf = mutableListOf<String>()
    private var logFile: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: run { logFile = null; return }
            logFile = File(dir, "ubuntu_runtime.log")
            // 磁盘日志轮转：超过阈值则保留为 .old（覆盖式），新文件从空开始，避免无限膨胀
            if (logFile!!.exists() && logFile!!.length() > MAX_DISK_BYTES) {
                val old = File(dir, "ubuntu_runtime.log.old")
                try { old.delete() } catch (_: Exception) {}
                try { logFile!!.renameTo(old) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            logFile = null
        }
    }

    @Synchronized
    fun append(tag: String, msg: String) {
        val line = try {
            "${fmt.format(Date())} [$tag] $msg"
        } catch (_: Exception) {
            "[$tag] $msg"
        }
        buf.add(line)
        if (buf.size > MAX_LINES) buf.removeAt(0)
        try {
            logFile?.appendText("$line\n")
        } catch (_: Exception) {
            // 落盘失败不影响内存日志
        }
    }

    @Synchronized
    fun getLog(): String = if (buf.isEmpty()) "" else buf.joinToString("\n")

    @Synchronized
    fun clear() {
        buf.clear()
        try {
            logFile?.writeText("")
        } catch (_: Exception) {
        }
    }
}
