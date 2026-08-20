package com.ubuntucontroller

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.zip.GZIPInputStream

object UbuntuService {

    enum class Status {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }

    data class SSHInfo(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val isRunning: Boolean
    )

    data class ServiceState(
        val status: Status,
        val message: String,
        val sshInfo: SSHInfo?
    )

    private const val DEFAULT_START_SCRIPT = "/data/local/ubuntu/start.sh"
    private const val DEFAULT_STOP_SCRIPT = "/data/local/ubuntu/stop.sh"
    private const val DEFAULT_SSH_PORT = 22
    private const val DEFAULT_SSH_USER = "root"
    private const val DEFAULT_SSH_PASSWORD = "Aa123456"
    private const val SSH_UP_FLAG = "/data/local/ubuntu/.ssh_up"
    private const val PREFS_NAME = "ubuntu_controller_prefs"
    private const val SECURE_PREFS_NAME = "ubuntu_controller_secure"
    private const val KEY_START_SCRIPT = "start_script_path"
    private const val KEY_STOP_SCRIPT = "stop_script_path"
    private const val KEY_SSH_PORT = "ssh_port"
    private const val KEY_SSH_USER = "ssh_user"
    private const val KEY_SSH_PASSWORD = "ssh_password"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_UBUNTU_VERSION = "ubuntu_version"
    private const val KEY_ARCH_OVERRIDE = "arch_override"
    private const val KEY_RUNTIME_MODE = "runtime_mode"
    private const val KEY_PROOT_SSH_PORT = "proot_ssh_port"

    private const val UBUNTU_DIR = "/data/local/ubuntu"
    // tar 文件名按「版本+架构」命名，避免切换版本后误用旧文件
    private const val TAR_NAME = "ubuntu-%s-%s.tar"

    // 可选 Ubuntu 版本（服务版最小 base rootfs）
    const val UBUNTU_22 = "22.04"
    const val UBUNTU_24 = "24.04"
    const val UBUNTU_26 = "26.04"
    val UBUNTU_VERSIONS = listOf(UBUNTU_22, UBUNTU_24, UBUNTU_26)

    // 架构选择：auto / amd64 / arm64 / armhf
    // i386(32位 x86) 已被 Ubuntu 淘汰（22.04/24.04 不再提供 base 镜像），故不支持
    const val ARCH_AUTO = "auto"
    const val ARCH_AMD64 = "amd64"
    const val ARCH_ARM64 = "arm64"
    const val ARCH_ARMHF = "armhf"
    val ARCH_OVERRIDES = listOf(ARCH_AUTO, ARCH_AMD64, ARCH_ARM64, ARCH_ARMHF)

    /**
     * 自动探测设备真实硬件架构（不含手动覆盖）。
     * 以 uname -m 为「设备自报架构」基准，仅当【真实架构是 x86_64 且 uname 不是 x86_64】时纠正成 x86_64。
     *   - MuMu 等 x86 模拟器把 uname -m 伪装成 aarch64，但 getprop 暴露真 x86 → 纠正为 amd64
     *   - 真实 arm64 盒子 → aarch64
     *   - 真实 armhf 老设备 → armv7l → armhf
     *   - 真实 x86 盒子/电脑 → x86_64 → amd64
     */
    fun detectHardwareArch(): String {
        val props = ShellExecutor.execute(
            "getprop ro.product.cpu.abilist; getprop ro.product.cpu.abi"
        ).output
        val realX86 = props.contains("x86_64")
        val r = ShellExecutor.execute("uname -m").output.trim()
        return when {
            realX86 && !r.contains("x86_64") -> ARCH_AMD64
            r.contains("x86_64") -> ARCH_AMD64
            r.contains("aarch64") -> ARCH_ARM64
            r.contains("armv7") || r.contains("armv8") -> ARCH_ARMHF
            props.contains("armeabi-v7a") && !props.contains("arm64-v8a") -> ARCH_ARMHF
            else -> ARCH_ARM64
        }
    }

    // 目标 rootfs 架构：手动覆盖优先，否则自动探测
    fun rootfsArch(context: Context? = null): String {
        if (context != null) {
            val override = getArchOverride(context)
            if (override == ARCH_AMD64 || override == ARCH_ARM64 || override == ARCH_ARMHF) return override
        }
        return detectHardwareArch()
    }

    // 判断目标架构能否在当前硬件上运行：32 位设备跑不了 64 位系统，x86 跑不了 ARM 等
    fun isArchCompatible(target: String, hardware: String): Boolean {
        return when (hardware) {
            ARCH_AMD64 -> target == ARCH_AMD64
            ARCH_ARM64 -> target == ARCH_ARM64 || target == ARCH_ARMHF
            ARCH_ARMHF -> target == ARCH_ARMHF
            else -> true
        }
    }

    // 非敏感配置（版本/架构/端口/用户/脚本路径/自启）用普通 SharedPreferences，稳定不受 Keystore 影响
    private var cachedPlainPrefs: SharedPreferences? = null
    private fun plainPrefs(context: Context): SharedPreferences {
        return cachedPlainPrefs
            ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { cachedPlainPrefs = it }
    }

    // 仅 SSH 密码用加密存储，单独文件，避免影响其它配置；加密初始化失败回退明文（独立文件）
    private var cachedSecurePrefs: SharedPreferences? = null
    private fun securePrefs(context: Context): SharedPreferences {
        return cachedSecurePrefs ?: run {
            val sp = try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.w("UbuntuService", "加密存储初始化失败，密码回退明文存储", e)
                context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            }
            cachedSecurePrefs = sp
            sp
        }
    }

    fun getArchOverride(context: Context): String {
        return plainPrefs(context).getString(KEY_ARCH_OVERRIDE, ARCH_AUTO) ?: ARCH_AUTO
    }

    fun setArchOverride(context: Context, arch: String) {
        val a = if (arch in ARCH_OVERRIDES) arch else ARCH_AUTO
        plainPrefs(context).edit().putString(KEY_ARCH_OVERRIDE, a).apply()
    }

    fun getRuntimeMode(context: Context): RuntimeMode {
        return RuntimeMode.from(plainPrefs(context).getString(KEY_RUNTIME_MODE, RuntimeMode.AUTO.value))
    }

    fun setRuntimeMode(context: Context, mode: RuntimeMode) {
        plainPrefs(context).edit().putString(KEY_RUNTIME_MODE, mode.value).apply()
    }

    fun getProotSshPort(context: Context): Int {
        return plainPrefs(context).getInt(KEY_PROOT_SSH_PORT, ProotUbuntuService.DEFAULT_PROOT_SSH_PORT)
    }

    fun setProotSshPort(context: Context, port: Int) {
        plainPrefs(context).edit().putInt(KEY_PROOT_SSH_PORT, port.coerceAtLeast(1024)).apply()
    }

    fun getUbuntuVersion(context: Context): String {
        return plainPrefs(context).getString(KEY_UBUNTU_VERSION, UBUNTU_22) ?: UBUNTU_22
    }

    fun setUbuntuVersion(context: Context, version: String) {
        val v = if (version in UBUNTU_VERSIONS) version else UBUNTU_22
        plainPrefs(context).edit().putString(KEY_UBUNTU_VERSION, v).apply()
    }

    // rootfs 下载 URL：根据 Ubuntu 版本和 CPU 架构自动选择
    fun rootfsUrl(version: String = UBUNTU_22, arch: String = rootfsArch()): String {
        val archSuffix = when (arch) {
            ARCH_AMD64 -> "amd64"
            ARCH_ARMHF -> "armhf"
            else -> "arm64"
        }
        val fileName = when (version) {
            UBUNTU_22 -> "ubuntu-base-22.04.4-base-$archSuffix.tar.gz"
            UBUNTU_24 -> "ubuntu-base-24.04.4-base-$archSuffix.tar.gz"
            UBUNTU_26 -> "ubuntu-base-26.04-base-$archSuffix.tar.gz"
            else -> "ubuntu-base-22.04.4-base-$archSuffix.tar.gz"
        }
        return "https://cdimage.ubuntu.com/ubuntu-base/releases/$version/release/$fileName"
    }

    // 当前版本+架构对应的 tar 目标路径
    private fun targetTar(context: Context): String {
        val v = getUbuntuVersion(context).replace(".", "_")
        val a = rootfsArch(context)
        return "$UBUNTU_DIR/${String.format(TAR_NAME, v, a)}"
    }

    fun getStartScriptPath(context: Context): String {
        return plainPrefs(context).getString(KEY_START_SCRIPT, DEFAULT_START_SCRIPT) ?: DEFAULT_START_SCRIPT
    }

    fun setStartScriptPath(context: Context, path: String) {
        plainPrefs(context).edit().putString(KEY_START_SCRIPT, path).apply()
    }

    fun getStopScriptPath(context: Context): String {
        return plainPrefs(context).getString(KEY_STOP_SCRIPT, DEFAULT_STOP_SCRIPT) ?: DEFAULT_STOP_SCRIPT
    }

    fun setStopScriptPath(context: Context, path: String) {
        plainPrefs(context).edit().putString(KEY_STOP_SCRIPT, path).apply()
    }

    fun getSshPort(context: Context): Int {
        return plainPrefs(context).getInt(KEY_SSH_PORT, DEFAULT_SSH_PORT)
    }

    fun setSshPort(context: Context, port: Int) {
        plainPrefs(context).edit().putInt(KEY_SSH_PORT, port).apply()
    }

    fun getSshUser(context: Context): String {
        return plainPrefs(context).getString(KEY_SSH_USER, DEFAULT_SSH_USER) ?: DEFAULT_SSH_USER
    }

    fun setSshUser(context: Context, user: String) {
        plainPrefs(context).edit().putString(KEY_SSH_USER, user).apply()
    }

    fun getSshPassword(context: Context): String {
        return securePrefs(context).getString(KEY_SSH_PASSWORD, DEFAULT_SSH_PASSWORD) ?: DEFAULT_SSH_PASSWORD
    }

    fun setSshPassword(context: Context, password: String) {
        securePrefs(context).edit().putString(KEY_SSH_PASSWORD, password).apply()
    }

    fun getAutoStart(context: Context): Boolean {
        return plainPrefs(context).getBoolean(KEY_AUTO_START, false)
    }

    fun setAutoStart(context: Context, enabled: Boolean) {
        plainPrefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    // 把 assets 里的 start.sh / stop.sh 部署到盒子（chroot 方案，无需 proot 二进制）。
    // 合并为单次 root 会话执行（低配盒子上频繁起 su 进程开销明显）。
    private fun deployAssets(context: Context) {
        val files = listOf(
            "start.sh" to "start.sh",
            "stop.sh" to "stop.sh"
        )

        val cmd = StringBuilder("mkdir -p $UBUNTU_DIR")
        for ((asset, dst) in files) {
            try {
                context.assets.open(asset).use { ins ->
                    FileOutputStream(File(context.filesDir, dst)).use { outs -> ins.copyTo(outs) }
                }
                cmd.append(" && cp ${context.filesDir}/$dst $UBUNTU_DIR/$dst")
            } catch (e: Exception) {
                // 单个资源部署失败不阻塞其它资源，但记录日志便于排查
                Log.e("UbuntuService", "部署资源失败: $asset", e)
            }
        }
        cmd.append(" && chmod 755 $UBUNTU_DIR/start.sh $UBUNTU_DIR/stop.sh")
        ShellExecutor.executeAsRoot(cmd.toString())
    }

    // 由 App 侧下载 rootfs（不依赖盒子上的 curl/wget）。已存在则跳过。下载完以 root 落到目标路径。
    private fun ensureRootfs(
        context: Context,
        tarExists: Boolean,
        onProgress: (Int, String) -> Unit
    ): Boolean {
        val version = getUbuntuVersion(context)
        val arch = rootfsArch(context)
        val targetTar = targetTar(context)

        if (tarExists) {
            onProgress(70, "Ubuntu $version ($arch) 系统已就绪")
            return true
        }

        onProgress(5, "正在下载 Ubuntu $version（$arch，约 28MB）…")
        val tmpGz = File(context.cacheDir, "ubuntu.tar.gz")
        val tmpTar = File(context.cacheDir, "ubuntu.tar")
        tmpGz.delete()
        tmpTar.delete()
        try {
            val conn = URL(rootfsUrl(version, arch)).openConnection() as HttpURLConnection
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

            // App 侧解压 gzip → 纯 tar：盒子的 toybox tar 常不支持 -z，故在此解压，盒子上只需 tar -xf
            onProgress(70, "正在解压系统…")
            GZIPInputStream(BufferedInputStream(FileInputStream(tmpGz))).use { gin ->
                FileOutputStream(tmpTar).use { outs -> gin.copyTo(outs) }
            }
            tmpGz.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            tmpGz.delete()
            tmpTar.delete()
            return false
        }

        val cp = ShellExecutor.executeAsRoot(
            "cp ${tmpTar.absolutePath} $targetTar && chmod 644 $targetTar && echo ok"
        )
        tmpTar.delete()
        if (cp.output.trim() != "ok") return false
        onProgress(72, "系统下载完成，正在解压…")
        return true
    }

    fun startUbuntu(context: Context, onProgress: (Int, String) -> Unit = { _, _ -> }): ServiceState {
        deployAssets(context)
        val scriptPath = getStartScriptPath(context)
        val port = getSshPort(context)
        val password = getSshPassword(context)
        val version = getUbuntuVersion(context)
        val arch = rootfsArch(context)
        // 硬件兼容校验：32 位设备选 arm64/amd64 等不兼容架构时，明确报错，不静默降级
        val hwArch = detectHardwareArch()
        if (!isArchCompatible(arch, hwArch)) {
            return ServiceState(
                Status.ERROR,
                "当前设备硬件是 $hwArch，无法运行 $arch 系统；请到「配置」把架构改为「自动」或 $hwArch",
                null
            )
        }
        val targetTar = targetTar(context)

        // 单次 root 会话完成前置检查：脚本是否存在 + 当前版本/架构的 tar 是否已下载
        val precheck = ShellExecutor.executeAsRoot(
            "test -f $scriptPath && echo 'E'; test -f $targetTar && echo 'T'"
        )
        if (!precheck.output.contains("E")) {
            return ServiceState(
                Status.ERROR,
                "启动脚本不存在: $scriptPath",
                null
            )
        }

        if (!ensureRootfs(context, precheck.output.contains("T"), onProgress)) {
            return ServiceState(
                Status.ERROR,
                "Ubuntu $version 系统下载失败（请确认设备已联网后重试）",
                null
            )
        }

        val result = ShellExecutor.executeAsRootStreaming(
            "sh $scriptPath $port $password $version $arch",
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
                val sshInfo = if (probe.running) buildSSHInfo(context, probe.sshUp) else null
                val msg = if (sshInfo?.isRunning == true) {
                    "Ubuntu 启动成功"
                } else {
                    "Ubuntu 已启动，但 SSH 未就绪（点「复制日志」可获取完整日志）"
                }
                ServiceState(Status.RUNNING, msg, sshInfo)
            }
            result.exitCode == -999 -> {
                ServiceState(
                    Status.ERROR,
                    "启动超时（15 分钟），apt 安装可能被网络卡住，请点击「复制日志」查看详情",
                    null
                )
            }
            else -> {
                ServiceState(
                    Status.ERROR,
                    "启动失败: ${result.error.ifBlank { result.output }}",
                    null
                )
            }
        }
    }

    fun stopUbuntu(context: Context): ServiceState {
        deployAssets(context)
        val scriptPath = getStopScriptPath(context)

        val checkExists = ShellExecutor.executeAsRoot("test -f $scriptPath && echo 'exists'")
        if (checkExists.output != "exists") {
            ShellExecutor.executeAsRoot(
                "pkill -x sshd 2>/dev/null; pkill -f 'run_ubuntu.sh' 2>/dev/null; pkill -f 'sleep infinity' 2>/dev/null || true"
            )
            return ServiceState(
                Status.STOPPED,
                "已执行强制停止 (fallback)",
                null
            )
        }

        val result = ShellExecutor.executeAsRoot("sh $scriptPath")
        return if (result.exitCode == 0) {
            ServiceState(Status.STOPPED, "Ubuntu 已停止", null)
        } else {
            ServiceState(
                Status.ERROR,
                "停止失败: ${result.error.ifBlank { result.output }}",
                null
            )
        }
    }

    fun detectStatus(context: Context): ServiceState {
        val probe = probeState(context)
        val sshInfo = if (probe.running) buildSSHInfo(context, probe.sshUp) else null
        val message = when {
            !probe.running -> "Ubuntu 未运行"
            probe.sshUp -> "Ubuntu 运行中"
            else -> "Ubuntu 已启动，但 SSH 未就绪（点「复制日志」可获取完整日志）"
        }
        return ServiceState(
            if (probe.running) Status.RUNNING else Status.STOPPED,
            message,
            sshInfo
        )
    }

    // 是否已启动（容器在运行，sshd 已拉起）。命令控制台执行前用它判断。
    fun isRunning(context: Context): Boolean = probeState(context).running

    private data class Probe(val running: Boolean, val sshUp: Boolean)

    // 一次 root 会话同时探测：sshd 是否运行 + SSH 端口是否就绪
    private fun probeState(context: Context): Probe {
        val port = getSshPort(context)
        val r = ShellExecutor.executeAsRoot(
            "pgrep -x sshd >/dev/null 2>&1 && echo 'UC_RUN'; " +
                "{ test -f $SSH_UP_FLAG || ss -tln 2>/dev/null | grep -q ':$port ' || " +
                "netstat -tln 2>/dev/null | grep -q ':$port '; } && echo 'UC_SSHUP'"
        )
        return Probe(r.output.contains("UC_RUN"), r.output.contains("UC_SSHUP"))
    }

    // 供界面「复制日志」按钮读取完整日志（不截断）
    fun readLog(): String {
        val r = ShellExecutor.executeAsRoot(
            "cat $UBUNTU_DIR/ubuntu.log 2>/dev/null || echo '(无日志文件)'"
        )
        val out = r.output.trim()
        return if (out.isNotBlank()) out else "(无日志内容)"
    }

    /**
     * 在 Ubuntu 容器内执行命令（离线替代 SSH）。
     * 要求 rootfs 已解压（首次「启动」完成后即可）；proc/sys/dev 仅在容器运行时挂载，
     * 未运行时部分依赖挂载的命令会失败，属预期行为。
     */
    fun runInUbuntu(context: Context, command: String, timeoutMs: Long = 60_000): ShellExecutor.ShellResult {
        val version = getUbuntuVersion(context)
        val arch = rootfsArch(context)
        val rootfs = "/data/local/ubuntu/rootfs.$version.$arch"
        val exists = ShellExecutor.executeAsRoot("test -d $rootfs && echo ok")
        if (exists.output.trim() != "ok") {
            return ShellExecutor.ShellResult(
                -1,
                "",
                "Ubuntu 未安装（未找到 rootfs: $rootfs），请先点击「启动」完成安装"
            )
        }
        val escaped = command.replace("'", "'\\''")
        val cmd = "chroot $rootfs /bin/bash -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $escaped'"
        return ShellExecutor.executeAsRoot(cmd, timeoutMs)
    }

    private fun buildSSHInfo(context: Context, sshUp: Boolean): SSHInfo {
        return SSHInfo(
            host = getDeviceIP(context) ?: "unknown",
            port = getSshPort(context),
            username = getSshUser(context),
            password = getSshPassword(context),
            isRunning = sshUp
        )
    }

    internal fun getDeviceIP(context: Context): String? {
        // API 23+ 优先使用 ConnectivityManager 获取 IPv4 地址
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                val linkProperties = activeNetwork?.let { cm.getLinkProperties(it) }
                linkProperties?.linkAddresses?.forEach { linkAddr ->
                    val addr = linkAddr.address
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 兜底 1：遍历网卡（优先有线/无线接口）
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name.startsWith("eth") ||
                    iface.name.startsWith("wlan") ||
                    iface.name.startsWith("wl")
                ) {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 兜底 2：已废弃的 WifiManager（兼容旧设备）
        @Suppress("DEPRECATION")
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    (ipInt shr 8) and 0xff,
                    (ipInt shr 16) and 0xff,
                    (ipInt shr 24) and 0xff
                )
            }
        } catch (_: Exception) {
        }

        return null
    }
}
