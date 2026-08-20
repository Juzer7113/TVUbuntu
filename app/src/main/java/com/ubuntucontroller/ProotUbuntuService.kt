package com.ubuntucontroller

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * TVUbuntu Proot 模式服务核。
 *
 * 与 UbuntuService（root chroot 方案）完全并行，不调用 su、不执行 mount/chroot。
 * rootfs、脚本、日志全部存放在应用私有目录 /data/data/<package>/files/ubuntu/ 下，
 * proot 二进制从 nativeLibraryDir 调用以绕过 Android W^X 限制。
 */
object ProotUbuntuService {

    private const val PROOT_ROOT_NAME = "ubuntu"
    private const val TAR_NAME = "ubuntu-%s-%s.tar"
    private const val SSH_UP_FLAG = ".ssh_up"

    /** proot 模式默认 SSH 端口（>=1024，无需 root） */
    const val DEFAULT_PROOT_SSH_PORT = 8022

    /** proot 工作根目录：/data/data/<package>/files/ubuntu */
    fun prootRoot(context: Context): String = File(context.filesDir, PROOT_ROOT_NAME).absolutePath

    /** proot 二进制绝对路径，从 nativeLibraryDir 获取 */
    fun prootBinary(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    // ---------- 资源部署 ----------

    /**
     * 将 assets 里的 proot 脚本释放到应用私有目录，并复制到 proot 工作目录。
     * 此过程以 App 自身身份执行，无需 root。
     */
    fun deployAssets(context: Context) {
        val assets = listOf("start_proot.sh", "stop_proot.sh", "install_ssh_proot.sh", "run_ubuntu_proot.sh")
        val root = prootRoot(context)
        val cmd = StringBuilder("mkdir -p $root")

        for (asset in assets) {
            try {
                context.assets.open(asset).use { ins ->
                    FileOutputStream(File(context.filesDir, asset)).use { outs -> ins.copyTo(outs) }
                }
                cmd.append(" && cp ${File(context.filesDir, asset).absolutePath} $root/$asset")
            } catch (e: Exception) {
                Log.e("ProotUbuntuService", "部署资源失败: $asset", e)
            }
        }
        cmd.append(" && chmod 755 $root/*.sh")
        ProotExecutor.execute(cmd.toString())
    }

    // ---------- rootfs 下载/解压 ----------

    /**
     * 下载并解压当前设置对应的 Ubuntu rootfs tar 到应用私有目录。
     * gzip 由 App 侧解压，盒子上只需执行 tar -xf。
     */
    fun ensureRootfs(context: Context, onProgress: (Int, String) -> Unit): Boolean {
        val version = UbuntuService.getUbuntuVersion(context)
        val arch = UbuntuService.rootfsArch(context)
        val root = prootRoot(context)
        val vSafe = version.replace(".", "_")
        val targetTar = "$root/${String.format(TAR_NAME, vSafe, arch)}"

        val tarExists = ProotExecutor.execute("test -f $targetTar && echo ok").output.trim() == "ok"
        if (tarExists) {
            onProgress(70, "Ubuntu $version ($arch) 系统已就绪")
            return true
        }

        onProgress(5, "正在下载 Ubuntu $version（$arch，约 28MB）…")
        val tmpGz = File(context.cacheDir, "ubuntu_proot.tar.gz")
        val tmpTar = File(context.cacheDir, "ubuntu_proot.tar")
        tmpGz.delete()
        tmpTar.delete()

        return try {
            val conn = URL(UbuntuService.rootfsUrl(version, arch)).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return false
            }
            val total = conn.contentLength.toLong()
            BufferedInputStream(conn.inputStream).use { ins ->
                FileOutputStream(tmpGz).use { outs ->
                    val buf = ByteArray(32 * 1024)
                    var read: Int
                    var done: Long = 0
                    while (ins.read(buf).also { read = it } != -1) {
                        outs.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            var pct = (done * 65 / total).toInt() + 5
                            if (pct > 70) pct = 70
                            onProgress(
                                pct,
                                "正在下载 Ubuntu $version: ${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB"
                            )
                        }
                    }
                }
            }
            conn.disconnect()

            onProgress(70, "正在解压系统…")
            GZIPInputStream(BufferedInputStream(FileInputStream(tmpGz))).use { gin ->
                FileOutputStream(tmpTar).use { outs -> gin.copyTo(outs) }
            }
            tmpGz.delete()

            ProotExecutor.execute("mkdir -p $root")
            val moved = tmpTar.renameTo(File(targetTar))
            if (!moved) {
                val cp = ProotExecutor.execute("cp ${tmpTar.absolutePath} $targetTar && echo ok")
                tmpTar.delete()
                if (cp.output.trim() != "ok") return false
            }
            ProotExecutor.execute("chmod 644 $targetTar")
            onProgress(72, "系统下载完成")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            tmpGz.delete()
            tmpTar.delete()
            false
        }
    }

    // ---------- 启动/停止 ----------

    fun startUbuntu(
        context: Context,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): UbuntuService.ServiceState {
        deployAssets(context)

        val root = prootRoot(context)
        val port = UbuntuService.getProotSshPort(context)
        val password = UbuntuService.getSshPassword(context)
        val version = UbuntuService.getUbuntuVersion(context)
        val arch = UbuntuService.rootfsArch(context)
        val hwArch = UbuntuService.detectHardwareArch()

        if (!UbuntuService.isArchCompatible(arch, hwArch)) {
            return UbuntuService.ServiceState(
                UbuntuService.Status.ERROR,
                "当前设备硬件是 $hwArch，无法运行 $arch 系统；请到「配置」把架构改为「自动」或 $hwArch",
                null
            )
        }

        val prootBin = prootBinary(context)
        if (!File(prootBin).exists()) {
            return UbuntuService.ServiceState(
                UbuntuService.Status.ERROR,
                "proot 二进制不存在: $prootBin",
                null
            )
        }

        if (!ensureRootfs(context, onProgress)) {
            return UbuntuService.ServiceState(
                UbuntuService.Status.ERROR,
                "Ubuntu $version 系统下载失败（请确认设备已联网后重试）",
                null
            )
        }

        val script = "$root/start_proot.sh"
        val check = ProotExecutor.execute(
            "test -f $script && echo 'E'; test -x $prootBin && echo 'X'"
        )
        if (!check.output.contains("E")) {
            return UbuntuService.ServiceState(
                UbuntuService.Status.ERROR,
                "启动脚本不存在: $script",
                null
            )
        }
        if (!check.output.contains("X")) {
            return UbuntuService.ServiceState(
                UbuntuService.Status.ERROR,
                "proot 二进制不可执行: $prootBin",
                null
            )
        }

        // 密码经 `sh -c` 传递，需单引号包裹并转义内部单引号，防止空格/特殊字符破坏参数或被注入
        val safePassword = password.replace("'", "'\\''")
        val result = ProotExecutor.executeStreaming(
            "sh $script $port '$safePassword' $version $arch $prootBin",
            timeoutMs = 900_000
        ) { line ->
            if (line.startsWith("UC_PROGRESS|")) {
                val body = line.removePrefix("UC_PROGRESS|").split("|", limit = 2)
                val pct = body.getOrNull(0)?.toIntOrNull() ?: 0
                val msg = body.getOrNull(1) ?: ""
                onProgress(pct, msg)
            }
        }

        return when {
            result.exitCode == 0 -> {
                Thread.sleep(3000)
                val probe = probeState(context)
                when {
                    probe.running -> {
                        val sshInfo = buildSSHInfo(context, probe.sshUp)
                        val msg = if (sshInfo.isRunning) {
                            "Ubuntu 启动成功"
                        } else {
                            "Ubuntu 已启动，但 SSH 未就绪（点「复制日志」可获取完整日志）"
                        }
                        UbuntuService.ServiceState(UbuntuService.Status.RUNNING, msg, sshInfo)
                    }
                    // 本实例被防重入锁拦截，但另一实例仍在安装/启动 → 报「启动中」而非误报成功
                    probe.starting -> UbuntuService.ServiceState(
                        UbuntuService.Status.STARTING,
                        "另一实例正在安装/启动中，请稍候…",
                        null
                    )
                    else -> UbuntuService.ServiceState(
                        UbuntuService.Status.RUNNING,
                        "Ubuntu 已启动，但 SSH 未就绪（点「复制日志」可获取完整日志）",
                        null
                    )
                }
            }
            result.exitCode == -999 -> UbuntuService.ServiceState(
                UbuntuService.Status.ERROR,
                "启动超时（15 分钟），apt 安装可能被网络卡住，请点击「复制日志」查看详情",
                null
            )
            else -> UbuntuService.ServiceState(
                UbuntuService.Status.ERROR,
                "启动失败: ${result.error.ifBlank { result.output }}",
                null
            )
        }
    }

    fun stopUbuntu(context: Context): UbuntuService.ServiceState {
        deployAssets(context)
        val script = "${prootRoot(context)}/stop_proot.sh"
        val check = ProotExecutor.execute("test -f $script && echo ok")
        if (check.output.trim() != "ok") {
            ProotExecutor.execute(
                "pkill -x sshd 2>/dev/null; pkill -x dropbear 2>/dev/null; pkill -f 'run_ubuntu_proot.sh' 2>/dev/null; pkill -f 'sleep infinity' 2>/dev/null || true"
            )
            return UbuntuService.ServiceState(
                UbuntuService.Status.STOPPED,
                "已执行强制停止",
                null
            )
        }

        val result = ProotExecutor.execute("sh $script")
        return if (result.exitCode == 0) {
            UbuntuService.ServiceState(UbuntuService.Status.STOPPED, "Ubuntu 已停止", null)
        } else {
            UbuntuService.ServiceState(
                UbuntuService.Status.ERROR,
                "停止失败: ${result.error.ifBlank { result.output }}",
                null
            )
        }
    }

    // ---------- 状态探测 ----------

    fun detectStatus(context: Context): UbuntuService.ServiceState {
        val probe = probeState(context)
        val sshInfo = if (probe.running) buildSSHInfo(context, probe.sshUp) else null
        val message = when {
            probe.running && probe.sshUp -> "Ubuntu 运行中"
            probe.running -> "Ubuntu 已启动，但 SSH 未就绪（点「复制日志」可获取完整日志）"
            probe.starting -> "Ubuntu 正在安装/启动中…"
            else -> "Ubuntu 未运行"
        }
        val status = when {
            probe.running -> UbuntuService.Status.RUNNING
            probe.starting -> UbuntuService.Status.STARTING
            else -> UbuntuService.Status.STOPPED
        }
        return UbuntuService.ServiceState(status, message, sshInfo)
    }

    fun isRunning(context: Context): Boolean = probeState(context).running

    private data class Probe(val running: Boolean, val sshUp: Boolean, val starting: Boolean)

    private fun probeState(context: Context): Probe {
        val port = UbuntuService.getProotSshPort(context)
        val root = prootRoot(context)
        // starting：有 start_proot.sh 进程在跑（首次安装 / 等待 SSH 就绪阶段）。
        // 用 [s]tart_proot.sh 正则技巧避免匹配到本探测命令自身的 sh -c 包装进程。
        val r = ProotExecutor.execute(
            "(pgrep -x sshd || pgrep -x dropbear) >/dev/null 2>&1 && echo 'UC_RUN'; " +
                "{ test -f $root/$SSH_UP_FLAG || ss -tln 2>/dev/null | grep -q ':$port ' || " +
                "netstat -tln 2>/dev/null | grep -q ':$port '; } && echo 'UC_SSHUP'; " +
                "pgrep -f '[s]tart_proot.sh' >/dev/null 2>&1 && echo 'UC_STARTING'"
        )
        return Probe(
            r.output.contains("UC_RUN"),
            r.output.contains("UC_SSHUP"),
            r.output.contains("UC_STARTING")
        )
    }

    private fun buildSSHInfo(context: Context, sshUp: Boolean): UbuntuService.SSHInfo {
        return UbuntuService.SSHInfo(
            host = UbuntuService.getDeviceIP(context) ?: "unknown",
            port = UbuntuService.getProotSshPort(context),
            username = UbuntuService.getSshUser(context),
            password = UbuntuService.getSshPassword(context),
            isRunning = sshUp
        )
    }

    // ---------- 日志/命令控制台 ----------

    fun readLog(context: Context): String {
        val r = ProotExecutor.execute("cat ${prootRoot(context)}/ubuntu.log 2>/dev/null || echo '(无日志文件)'")
        return r.output.trim().ifBlank { "(无日志内容)" }
    }

    /**
     * 在 proot Ubuntu 内执行命令（无需 SSH，直接在本进程内调用 proot）。
     * 注意：proot 内进程依赖 /dev /proc /sys 绑定，未运行时部分命令会失败。
     */
    fun runInUbuntu(
        context: Context,
        command: String,
        timeoutMs: Long = 60_000
    ): ProotExecutor.ShellResult {
        val version = UbuntuService.getUbuntuVersion(context)
        val arch = UbuntuService.rootfsArch(context)
        val root = prootRoot(context)
        val rootfs = "$root/rootfs.$version.$arch"
        val exists = ProotExecutor.execute("test -d $rootfs && echo ok").output.trim() == "ok"
        if (!exists) {
            return ProotExecutor.ShellResult(
                -1,
                "",
                "Ubuntu 未安装（未找到 rootfs: $rootfs），请先点击「启动」完成安装"
            )
        }
        val prootBin = prootBinary(context)
        val resolv = "$root/resolv.conf"
        // 与 start_proot.sh 保持一致：必须设置 PROOT_TMP_DIR（Android 宿主无 /tmp，
        // 不设置会导致 proot 初始化即失败：can't canonicalize /tmp / Unable to create
        // temp directory for f2fs bug probe / can't create temporary file，连带
        // execve("/usr/bin/bash") 失败）。
        val tmpDir = "$root/tmp"
        val escaped = command.replace("'", "'\\''")
        val cmd = "export PROOT_TMP_DIR=$tmpDir; mkdir -p $tmpDir; " +
                "$prootBin -r $rootfs -0 -w /root " +
                "-b /dev -b /proc -b /sys " +
                "-b $resolv:/etc/resolv.conf " +
                "/bin/bash -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $escaped'"
        return ProotExecutor.execute(cmd, timeoutMs)
    }
}
