package com.ubuntucontroller

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Proot 模式专用执行器：以 App 自身身份执行命令，不调用 su。
 *
 * 所有命令都在应用沙盒内操作（/data/data/com.ubuntucontroller/...），
 * 因此无需 root 权限。输出编码统一使用 UTF-8，避免中文密码/路径被截断。
 */
object ProotExecutor {

    data class ShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

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

    fun execute(command: String): ShellResult {
        return execute(command, 0)
    }

    fun execute(command: String, timeoutMs: Long): ShellResult {
        return executeStreaming(command, timeoutMs) { }
    }

    fun executeStreaming(
        command: String,
        timeoutMs: Long = 0,
        onLine: (String) -> Unit
    ): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            streamProcess(process, command, timeoutMs, onLine)
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Unknown error")
        }
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
            }
        }
        readThread.isDaemon = true
        readThread.start()

        // 命令已通过 `sh -c <command>` 的 argv 传入，无需（也不应）再把命令本身写入子进程 stdin；
        // 否则容器内程序一旦读取 stdin 就会拿到命令文本而行为异常。直接关闭输出流，避免子进程阻塞在 stdin。
        try {
            process.outputStream.close()
        } catch (_: Exception) {
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
                "命令执行超时（${timeoutMs / 1000} 秒），已被强制终止。"
            )
        } else {
            ShellResult(exitCode, outBuilder.toString().trim(), errBuilder.toString().trim())
        }
    }
}
