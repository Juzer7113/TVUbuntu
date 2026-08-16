package com.ubuntucontroller

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object ShellExecutor {

    data class ShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    // su 实现模式：0=未探测 1=交互式(su 会话 stdin 喂命令) 2=单条式(su -c)
    // 不同盒子/模拟器的 su (Magisk/SuperSU/AOSP) 对两种模式支持不一，自动探测并缓存
    private val suMode = AtomicInteger(0)

    private fun writeCommand(os: DataOutputStream, command: String) {
        // UTF-8 写入，避免用户设置含中文的密码等参数被截断为单字节
        os.write(command.toByteArray(Charsets.UTF_8))
        os.write("\n".toByteArray(Charsets.UTF_8))
    }

    // 后台线程并发消费 stderr，防止输出量大时管道缓冲区写满导致进程互相等待（死锁）
    private fun drainAsync(reader: BufferedReader, sink: (String) -> Unit): Thread {
        val thread = Thread {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    sink(line)
                }
            } catch (_: Exception) {
            }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    fun executeAsRoot(command: String): ShellResult {
        if (suMode.get() != 2) {
            return try {
                val process = Runtime.getRuntime().exec("su")
                val errBuilder = StringBuilder()
                val errThread = drainAsync(BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))) {
                    synchronized(errBuilder) { errBuilder.append(it).append("\n") }
                }
                DataOutputStream(process.outputStream).use { os ->
                    writeCommand(os, command)
                    os.writeBytes("exit\n")
                    os.flush()
                }
                val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).readText()
                val exitCode = process.waitFor()
                errThread.join(2000)
                if (suMode.get() == 0 && exitCode == 0) suMode.set(1)
                ShellResult(exitCode, stdout.trim(), synchronized(errBuilder) { errBuilder.toString().trim() })
            } catch (e: Exception) {
                fallbackToSuC(command)
            }
        }
        return suMinusC(command)
    }

    /**
     * 以 root 执行命令，带超时保护。
     * @param timeoutMs 超时时间（毫秒），超过未结束则强制 kill 并返回 exitCode=-999。
     */
    fun executeAsRoot(command: String, timeoutMs: Long): ShellResult {
        return executeAsRootStreaming(command, timeoutMs) { }
    }

    private fun fallbackToSuC(command: String): ShellResult {
        suMode.set(2)
        return suMinusC(command)
    }

    private fun suMinusC(command: String): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val errBuilder = StringBuilder()
            val errThread = drainAsync(BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))) {
                synchronized(errBuilder) { errBuilder.append(it).append("\n") }
            }
            val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).readText()
            val exitCode = process.waitFor()
            errThread.join(2000)
            ShellResult(exitCode, stdout.trim(), synchronized(errBuilder) { errBuilder.toString().trim() })
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Unknown error")
        }
    }

    fun execute(command: String): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val errBuilder = StringBuilder()
            val errThread = drainAsync(BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))) {
                synchronized(errBuilder) { errBuilder.append(it).append("\n") }
            }
            val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).readText()
            val exitCode = process.waitFor()
            errThread.join(2000)
            ShellResult(exitCode, stdout.trim(), synchronized(errBuilder) { errBuilder.toString().trim() })
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Unknown error")
        }
    }

    fun checkRootAccess(): Boolean {
        val result = executeAsRoot("id")
        return result.exitCode == 0 && result.output.contains("uid=0")
    }

    /**
     * 以 root 执行命令，并逐行把 stdout 回调出去（用于实时进度展示）。
     * @param timeoutMs 超时时间（毫秒）。0 表示不超时；超过该时间未结束则强制 kill 并返回 exitCode=-999。
     */
    fun executeAsRootStreaming(
        command: String,
        timeoutMs: Long = 0,
        onLine: (String) -> Unit
    ): ShellResult {
        if (suMode.get() != 2) {
            return try {
                val process = Runtime.getRuntime().exec("su")
                val result = streamProcess(process, command, timeoutMs, onLine)
                if (suMode.get() == 0 && result.exitCode == 0) suMode.set(1)
                result
            } catch (e: Exception) {
                suMinusCStreaming(command, timeoutMs, onLine)
            }
        }
        return suMinusCStreaming(command, timeoutMs, onLine)
    }

    private fun streamProcess(
        process: Process,
        command: String,
        timeoutMs: Long,
        onLine: (String) -> Unit
    ): ShellResult {
        val errBuilder = StringBuilder()
        val errThread = drainAsync(BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))) {
            synchronized(errBuilder) { errBuilder.append(it).append("\n") }
        }

        val outBuilder = StringBuilder()

        val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val readThread = Thread {
            try {
                var line: String?
                while (stdout.readLine().also { line = it } != null) {
                    line?.let {
                        outBuilder.append(it).append("\n")
                        onLine(it)
                    }
                }
            } catch (_: Exception) {
                // 进程被 destroy 时会抛出，忽略即可
            }
        }
        readThread.isDaemon = true
        readThread.start()

        DataOutputStream(process.outputStream).use { os ->
            writeCommand(os, command)
            os.writeBytes("exit\n")
            os.flush()
        }

        val exitCode = try {
            if (timeoutMs > 0) {
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    -999
                } else {
                    process.exitValue()
                }
            } else {
                process.waitFor()
            }
        } catch (e: Exception) {
            -1
        }

        readThread.join(2000)
        errThread.join(2000)

        return if (exitCode == -999) {
            ShellResult(
                -999,
                outBuilder.toString().trim(),
                "命令执行超时（${timeoutMs / 1000} 秒），已被强制终止。请检查网络或查看日志。"
            )
        } else {
            ShellResult(exitCode, outBuilder.toString().trim(), errBuilder.toString().trim())
        }
    }

    private fun suMinusCStreaming(
        command: String,
        timeoutMs: Long,
        onLine: (String) -> Unit
    ): ShellResult {
        suMode.set(2)
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            streamProcess(process, command, timeoutMs, onLine)
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Unknown error")
        }
    }
}
