package com.ubuntucontroller

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TVUbuntu Proot-ADB 模式服务核。
 *
 * 双通道（统一走 AdbTransport 接口）：
 *  1) 无线调试 TLS（Android 11+，免弹窗）：经 mDNS 发现 _adb-tls-pairing，用 6 位配对码静默
 *     将本机公钥写入设备 adb_keys，再经 _adb-tls-connect 建 TLS 长连接。全程无「允许 USB 调试」
 *     弹窗（配对码即授权）。优先使用。
 *  2) 传统网络 ADB（端口 5555，弹窗/烘焙）：兼容旧固件或已把公钥烘焙进 /data/misc/adb/adb_keys 的盒子。
 */
object AdbProotService {

    private const val REMOTE_DIR = "/data/local/tmp/ubuntu"
    private const val ADB_LAUNCHED_MARKER = ".adb_launched"
    private const val DEFAULT_ADB_PORT = 5555
    private const val DEFAULT_ADB_HOST = "127.0.0.1"

    // ADB 是否已获取（任意路径成功：按钮/无线配对/自动获取）。启动时据此判断是否复用 ADB。
    private const val PREF_ADB = "adb_proot_state"
    private const val KEY_ACQUIRED = "acquired"
    fun isAdbAcquired(context: Context): Boolean =
        context.getSharedPreferences(PREF_ADB, Context.MODE_PRIVATE).getBoolean(KEY_ACQUIRED, false)
    private fun markAdbAcquired(context: Context) =
        context.getSharedPreferences(PREF_ADB, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACQUIRED, true).apply()
    private fun clearAdbAcquired(context: Context) =
        context.getSharedPreferences(PREF_ADB, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACQUIRED, false).apply()

    /** 传统通道鉴权等待看门狗时长（毫秒）。 */
    private const val AUTH_WATCHDOG_MS = 90_000L

    // 持久连接（proot 常驻）；stop() 时关闭
    private var transport: AdbTransport? = null
    // 无线 TLS 持久连接（优先通道）
    private var wirelessTransport: AdbWirelessTransport? = null
    // 传统通道鉴权看门狗进行中的连接，供「取消/重试」中断
    private var pendingAuthClient: AdbClassicTransport? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** ADB 鉴权状态（供 UI 显示）。 */
    enum class AuthState { CONNECTING, UNREACHABLE, AWAITING, CONNECTED, WIRELESS, ERROR }

    /** 鉴权结果：状态 + 人类可读信息。 */
    data class AdbAuthResult(val state: AuthState, val message: String)

    private fun adbHost(context: Context): String = UbuntuService.getAdbHost(context)
    private fun adbPort(context: Context): Int = UbuntuService.getAdbPort(context)
    private fun prootRoot(context: Context): String = ProotUbuntuService.prootRoot(context)

    private fun unreachableResult(context: Context): AdbAuthResult =
        AdbAuthResult(
            AuthState.UNREACHABLE,
            context.getString(R.string.adb_error_unreachable, adbHost(context), adbPort(context))
        )

    private fun runOnMain(r: () -> Unit) = mainHandler.post(r)

    private fun rootfsTar(context: Context): File {
        val version = UbuntuService.getUbuntuVersion(context)
        val arch = UbuntuService.rootfsArch(context)
        val vSafe = version.replace(".", "_")
        return File(prootRoot(context), "ubuntu-$vSafe-$arch.tar")
    }

    private fun prootBinary(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    // ---------- 密钥 / 公钥 ----------

    /** 加载内置固定 adb 密钥（私钥+证书，assets）。返回 null=成功，否则为失败原因字符串。 */
    fun ensureKey(context: Context): String? {
        return if (AdbKeyStore.load(context)) null else (AdbKeyStore.lastError ?: "未知错误")
    }

    /** 导出 adb 公钥文本（adb 格式），用于烘焙进固件 /data/misc/adb/adb_keys。 */
    fun exportPubkey(context: Context): String = AdbKeyStore.getAdbPublicKeyText(context)

    // ---------- 无线 TLS 通道状态 ----------

    fun hasWireless(): Boolean = wirelessTransport?.isConnected() == true

    fun setWirelessTransport(t: AdbWirelessTransport) {
        wirelessTransport = t
        transport = t
    }

    fun clearWireless() {
        wirelessTransport = null
    }

    fun wirelessAuthResult(): AdbAuthResult =
        AdbAuthResult(AuthState.WIRELESS, "ADB：无线 TLS 已连接（免弹窗配对）")

    // ---------- 传统通道：TCP 探测 + 鉴权（弹窗/烘焙） ----------

    /** 设备 adbd 是否可连通（TCP 探测，不依赖密钥授权）。带连接超时，避免不可达时无限阻塞。 */
    fun isAdbReachable(context: Context): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(adbHost(context), adbPort(context)), 5_000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 主动连接 adbd 并等待设备授权（传统 5555 通道）。授权成功后 adbd 会缓存本 App 公钥，
     * 关闭握手连接即可。回调在调用线程返回，UI 更新需切主线程。
     */
    fun requestAdbAuth(context: Context, onResult: (AdbAuthResult) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            onResult(AdbAuthResult(AuthState.ERROR, "系统版本过低，需 API 23+ 才能用 adb 密钥"))
            return
        }
        val keyErr = ensureKey(context)
        if (keyErr != null) {
            onResult(AdbAuthResult(AuthState.ERROR, "加载内置 adb 密钥失败：$keyErr"))
            return
        }
        if (!isAdbReachable(context)) {
            // 不可达先尝试自愈（Root 开 5555 并重启 adbd），部分盒子 persist 被清后可用；无 root 则静默失败。
            try { AdbNetworkEnabler.enableViaRoot(context) } catch (_: Throwable) {}
            if (!isAdbReachable(context)) {
                onResult(
                    AdbAuthResult(
                        AuthState.UNREACHABLE,
                        "设备 adbd 不可达（${adbHost(context)}:${adbPort(context)}）。请在开发者选项开启「网络 ADB 调试」，或用「无线配对」免弹窗。"
                    )
                )
                return
            }
        }
        onResult(AdbAuthResult(AuthState.CONNECTING, "正在连接 adbd，请在盒子上点『允许 USB 调试』…"))
        val adb = AdbClassicTransport(context, adbHost(context), adbPort(context))
        pendingAuthClient = adb
        val done = AtomicBoolean(false)
        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                if (!done.get()) try { adb.cancel() } catch (_: Exception) {}
            }
        }, AUTH_WATCHDOG_MS)
        val result = try {
            adb.connect()
            try { adb.close() } catch (_: Exception) {} // 授权已缓存，关闭握手连接
            AdbAuthResult(AuthState.CONNECTED, "ADB 已连接并授权")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            // 设备要求配对（AdbPairingRequiredException 经梯子抛出）→ 返回 ERROR，
            // 由 MainActivity.onManualAdbFailed 弹出无线配对码填写弹窗（满足「ADB 拿不到就弹配对码」）。
            if (msg.contains("配对码") || msg.contains("需要配对")) {
                AdbAuthResult(AuthState.ERROR, msg)
            } else {
                AdbAuthResult(AuthState.AWAITING, "请在盒子上用遥控器点『允许 USB 调试』，然后点本按钮重试")
            }
        } finally {
            done.set(true)
            timer.cancel()
            pendingAuthClient = null
        }
        if (result.state == AuthState.CONNECTED) {
            markAdbAcquired(context)
            AdbAutoAcquire.setLastMode(context, AdbAutoAcquire.Mode.CLASSIC)
        }
        onResult(result)
    }

    /** 取消进行中的鉴权连接（中断阻塞读）。 */
    fun cancelAuth() {
        try { pendingAuthClient?.cancel() } catch (_: Exception) {}
        pendingAuthClient = null
    }

    /**
     * 带看门狗的鉴权连接（传统通道）：成功返回 (client, CONNECTED)，否则 (null, 状态)。
     * adbd 不可达 → UNREACHABLE；连接上但用户未授权超时 → AWAITING。
     */
    private fun connectWithAuthWatchdog(context: Context, timeoutMs: Long = AUTH_WATCHDOG_MS): Pair<AdbClassicTransport?, AuthState> {
        if (!isAdbReachable(context)) return null to AuthState.UNREACHABLE
        val adb = AdbClassicTransport(context, adbHost(context), adbPort(context), timeoutMs)
        val done = AtomicBoolean(false)
        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                if (!done.get()) try { adb.cancel() } catch (_: Exception) {}
            }
        }, AUTH_WATCHDOG_MS)
        return try {
            adb.connect()
            adb to AuthState.CONNECTED
        } catch (e: Exception) {
            null to AuthState.AWAITING
        } finally {
            done.set(true)
            timer.cancel()
        }
    }

    // ---------- 无线调试配对（免弹窗） ----------

    /**
     * 用 6 位配对码 + 配对地址端口静默授权本机公钥，并自动经 TLS 连接端点建连。
     * 成功后在主线程回调 onResult(true, 提示)，并将可用 transport 存入 [wirelessTransport]。
     * 调用方需自行在后台线程调用本函数（内部已起线程）。
     */
    fun pairWireless(
        context: Context,
        host: String,
        port: Int,
        code: String,
        onResult: (Boolean, String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            runOnMain { onResult(false, "无线调试需 Android 11（API 30）及以上") }
            return
        }
        if (AdbKeyStore.load(context).not()) {
            runOnMain { onResult(false, "密钥加载失败：${AdbKeyStore.lastError}") }
            return
        }
        Thread {
            val (ok, msg) = AdbWirelessPairing.pair(context, host, port, code)
            if (!ok) {
                runOnMain { onResult(false, msg) }
                return@Thread
            }
            val (t, msg2) = AdbWirelessPairing.connectTls(context, 8000)
            if (t == null) {
                runOnMain { onResult(false, msg2) }
                return@Thread
            }
            setWirelessTransport(t)
            markAdbAcquired(context)
            AdbAutoAcquire.setLastMode(context, AdbAutoAcquire.Mode.WIRELESS)
            runOnMain { onResult(true, "配对并连接成功，ADB 已就绪（免弹窗）") }
        }.start()
    }

    /**
     * 尝试自动经 TLS 连接（适用于已配对过、设备仍开启无线调试的场景：开屏自动直连免弹窗）。
     * 未发现 _adb-tls-connect 或握手失败则回调 false（不影响传统通道）。
     */
    fun connectWirelessIfPaired(context: Context, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            runOnMain { onResult(false) }
            return
        }
        if (wirelessTransport?.isConnected() == true) {
            runOnMain { onResult(true) }
            return
        }
        Thread {
            val (t, _) = AdbWirelessPairing.connectTls(context, 6000)
            if (t != null) {
                setWirelessTransport(t)
                runOnMain { onResult(true) }
            } else {
                runOnMain { onResult(false) }
            }
        }.start()
    }

    // ---------- 启动 ----------

    /**
     * 同步获取可用于启动 Ubuntu 的 transport（供 ProotUbuntuService「无 root 先走 ADB」流程调用）。
     *
     * 定序（与应用管家双通道同构，但本 App 固定密钥 + 固件烘焙路线）：
     *   1) 尽量 WRITE_SECURE_SETTINGS 开启无障碍自动点击（免去手动点「允许 USB 调试」）；
     *   2) Android 11+ 且设备开启无线调试 → TLS 配对直连（免弹窗）；
     *   3) 否则经典 5555：adbd 未监听先自愈（Root setprop），再鉴权；
     *   4) 鉴权成功把 5555 持久化，下次自动直连。
     *
     * 返回非 null = 已获得可用且已授权的 transport；null = 获取失败（调用方应回退本地 proot）。
     * 首次手动点「允许 USB 调试」属正常（无障碍未授权时）。
     */
    fun acquireTransportForLaunch(context: Context): AdbTransport? {
        return try {
            acquireTransportForLaunchInner(context)
        } catch (e: Throwable) {
            Log.e("AdbProotService", "获取 ADB transport 异常，回退本地 proot", e)
            null
        }
    }

    private fun acquireTransportForLaunchInner(context: Context): AdbTransport? {
        // 1) 尽量开启无障碍自动点击（无 WRITE_SECURE_SETTINGS 时静默失败，退化为手动点）
        try { AdbAccessibilityHelper.tryEnable(context) } catch (_: Throwable) {}

        // 2) 无线 TLS 优先（Android 11+，免弹窗）：开屏自动直连
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val latch = CountDownLatch(1)
            var wt: AdbTransport? = null
            connectWirelessIfPaired(context) { ok ->
                if (ok) wt = wirelessTransport
                latch.countDown()
            }
            latch.await(12, TimeUnit.SECONDS)
            if (wt != null) { markAdbAcquired(context); return wt }
        }

        // 3) 经典 5555：不可达先自愈（Root 开 5555）
        if (!isAdbReachable(context)) {
            AdbNetworkEnabler.enableViaRoot(context)
            if (isAdbReachable(context)) {
                try { Thread.sleep(3000) } catch (_: Throwable) {} // 等 adbd 重启
            }
        }

        // 4) 经典通道鉴权（等待用户/自动点击，首次手动授权正常）
        val (adb, state) = connectWithAuthWatchdog(context)
        if (adb != null && state == AuthState.CONNECTED) {
            // 成功连通后把 5555 持久化（persist.* 多数设备重启仍生效），下次自动直连
            AdbNetworkEnabler.enableViaAdb(context)
            markAdbAcquired(context)
            return adb
        }
        try { adb?.close() } catch (_: Throwable) {}
        return null
    }

    /**
     * 复用「已获取」的 ADB 启动 Ubuntu，**绝不重新获取**（不弹无障碍、不重配对、不长期等授权）。
     * 判定依据：同会话无线已连，或此前任一路径成功获取过 ADB（持久标记 + 上次通道类型）。
     * - 同会话无线已连 → 直接复用；
     * - 上次走无线 → 仅重连（免配对码、免弹窗，快速）；
     * - 上次走经典 → 仅在可达且密钥已授权时快速连接，需重新授权则放弃；
     * 任何情况拿不到可用 transport 都返回 null（调用方退回纯 proot，不阻塞、不闪退）。
     */
    fun reuseAcquiredTransport(context: Context): AdbTransport? {
        // 1) 同会话无线已连 → 直接复用
        val wt = wirelessTransport
        if (wt != null && wt.isConnected()) return wt

        if (!isAdbAcquired(context)) return null
        val mode = AdbAutoAcquire.getLastMode(context)

        // 2) 上次走无线 → 仅重连（不需配对码、不弹窗）
        if (mode == AdbAutoAcquire.Mode.WIRELESS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val latch = CountDownLatch(1)
            var reconnected: AdbTransport? = null
            connectWirelessIfPaired(context) { ok ->
                if (ok) reconnected = wirelessTransport
                latch.countDown()
            }
            latch.await(8, TimeUnit.SECONDS)
            if (reconnected != null) return reconnected
        }

        // 3) 上次走经典 → 仅在可达且密钥已授权时快速复用（短超时，不长期等授权）
        if (mode == AdbAutoAcquire.Mode.CLASSIC && isAdbReachable(context)) {
            val (adb, state) = connectWithAuthWatchdog(context, 12_000L)
            if (adb != null && state == AuthState.CONNECTED) return adb
            try { adb?.close() } catch (_: Throwable) {}
        }
        return null
    }

    fun startUbuntu(
        context: Context,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): UbuntuService.ServiceState {
        // 共用前置：rootfs 必须已下载
        if (!ProotUbuntuService.ensureRootfs(context, onProgress)) {
            return UbuntuService.ServiceState(
                UbuntuService.Status.ERROR,
                "Ubuntu 系统下载失败（请确认设备已联网后重试）",
                null
            )
        }
        val tar = rootfsTar(context)
        if (!tar.exists()) {
            return UbuntuService.ServiceState(
                UbuntuService.Status.ERROR,
                "rootfs tar 不存在: ${tar.absolutePath}",
                null
            )
        }
        val keyErr = ensureKey(context)
        if (keyErr != null) {
            return UbuntuService.ServiceState(
                UbuntuService.Status.ERROR,
                "加载内置 adb 密钥失败：$keyErr",
                null
            )
        }

        // 获取可用于启动的 transport（无线 TLS 优先 → 经典 5555 自愈+鉴权）
        RuntimeLog.append("deploy", "获取 ADB 连接…")
        onProgress(2, "获取 ADB 连接…")
        val t = acquireTransportForLaunch(context) ?: return UbuntuService.ServiceState(
            UbuntuService.Status.ERROR,
            "ADB 获取失败：设备 adbd 不可达或未授权。请在开发者选项开启「网络 ADB 调试」，或用「无线配对」免弹窗。",
            null
        )
        return launchViaTransport(context, t, onProgress)
    }

    fun launchViaTransport(
        context: Context,
        t: AdbTransport,
        onProgress: (Int, String) -> Unit
    ): UbuntuService.ServiceState {
        val logP: (Int, String) -> Unit = { p, m -> onProgress(p, m); RuntimeLog.append("deploy", m) }
        val version = UbuntuService.getUbuntuVersion(context)
        val arch = UbuntuService.rootfsArch(context)
        val prootBin = prootBinary(context)

        // 确保远端目录存在（否则 cat 写文件会失败导致推送异常）
        t.exec("mkdir -p $REMOTE_DIR")
        logP(15, "推送 proot 二进制…")
        if (!t.push(File(prootBin), "$REMOTE_DIR/libproot.so", "0755")) {
            RuntimeLog.append("deploy", "推送 libproot.so 失败")
            return UbuntuService.ServiceState(UbuntuService.Status.ERROR, "推送 libproot.so 失败", null)
        }
        logP(35, "推送 Ubuntu rootfs（约 28MB，请稍候）…")
        if (!t.push(rootfsTar(context), "$REMOTE_DIR/ubuntu-$version-$arch.tar", "0644")) {
            RuntimeLog.append("deploy", "推送 rootfs tar 失败")
            return UbuntuService.ServiceState(UbuntuService.Status.ERROR, "推送 rootfs tar 失败", null)
        }
        logP(60, "推送启动/安装脚本…")
        pushAsset(t, context, "install_ssh_proot.sh", "$REMOTE_DIR/install_ssh_proot.sh")
        pushAsset(t, context, "run_ubuntu_proot.sh", "$REMOTE_DIR/run_ubuntu_proot.sh")
        pushAsset(t, context, "start_proot_adb.sh", "$REMOTE_DIR/start_proot_adb.sh")

        // 权限/标签自愈 + 诊断：sync 推送的文件在部分设备上权限位或 SELinux 标签不对，
        // 直接 `sh script` 会报 Permission denied（此前多次「无日志/卡住」的根因）。
        // chmod 修复权限位；cp+mv 重建文件使标签变为 shell_data_file（shell 域可执行）；
        // ls -lZ 输出真实权限与标签，供日志定位。
        val diag = t.exec(
            "chmod 755 $REMOTE_DIR/libproot.so $REMOTE_DIR/*.sh 2>/dev/null; " +
                "for f in libproot.so start_proot_adb.sh run_ubuntu_proot.sh install_ssh_proot.sh; do " +
                "cp -f $REMOTE_DIR/\$f $REMOTE_DIR/\$f.tmp 2>/dev/null && mv -f $REMOTE_DIR/\$f.tmp $REMOTE_DIR/\$f 2>/dev/null; " +
                "chmod 755 $REMOTE_DIR/\$f 2>/dev/null; done; " +
                "ls -lZ $REMOTE_DIR 2>/dev/null | head -20"
        )
        if (diag.isNotBlank()) RuntimeLog.append("deploy", "设备端文件权限/标签诊断:\n${diag.trim()}")

        logP(75, "经 adb 持久启动 proot + SSH…")
        val port = UbuntuService.getProotSshPort(context)
        val password = UbuntuService.getSshPassword(context)
        val safePassword = password.replace("'", "'\\''")
        val launchCmd = "sh $REMOTE_DIR/start_proot_adb.sh $port '$safePassword' $version $arch"
        // 设备侧 stdout（sh 解析错误 / 解压报错 / proot 启动错误等）实时记入运行时日志，
        // 不再丢弃——ubuntu.log 为空/写入失败时也能定位「脚本到底有没有跑起来」
        t.startPersistent(launchCmd) { line ->
            if (line.isNotBlank()) RuntimeLog.append("proot-out", line.trim())
        }
        transport = t

        // 标记：后续 detectStatus/stop/runInUbuntu 按此路由
        File(prootRoot(context), ADB_LAUNCHED_MARKER).writeText("1")

        // 探测一次：SSH 就绪即报成功；否则【不误报失败】——
        // 首次启动需解压 80MB rootfs + apt 安装 SSH（1-3 分钟），期间属正常「启动中」，
        // 详细进度一律进日志（复制日志按钮），主页只显示简洁状态
        Thread.sleep(8000)
        val probe = probeState(context)
        return when {
            probe.running && probe.sshUp -> {
                RuntimeLog.append("deploy", "SSH 就绪：Ubuntu 启动成功（adb 路径）")
                UbuntuService.ServiceState(
                    UbuntuService.Status.RUNNING,
                    "Ubuntu 已启动",
                    buildSSHInfo(context, true)
                )
            }
            probe.running -> {
                RuntimeLog.append("deploy", "proot 已运行，SSH 未就绪（首次安装可能需 1-3 分钟，复制日志查看进度）")
                UbuntuService.ServiceState(
                    UbuntuService.Status.RUNNING,
                    "Ubuntu 启动中",
                    buildSSHInfo(context, false)
                )
            }
            else -> {
                RuntimeLog.append("deploy", "proot 尚未探测到，正在后台启动（首次需解压 rootfs + 安装 SSH，复制日志查看进度）")
                UbuntuService.ServiceState(
                    UbuntuService.Status.RUNNING,
                    "Ubuntu 启动中",
                    null
                )
            }
        }
    }

    private fun pushAsset(t: AdbTransport, context: Context, asset: String, remote: String) {
        try {
            context.assets.open(asset).use { ins ->
                val tmp = File(context.cacheDir, asset)
                tmp.outputStream().use { ins.copyTo(it) }
                t.push(tmp, remote, "0755")
                tmp.delete()
            }
        } catch (e: Exception) {
            // 单个脚本推送失败不致命（脚本已随 rootfs 拷贝逻辑兜底），记录即可
        }
    }

    // ---------- 停止 ----------

    fun stopUbuntu(context: Context): UbuntuService.ServiceState {
        clearAdbAcquired(context)
        try { transport?.close() } catch (_: Exception) {}
        transport = null
        wirelessTransport = null
        // 兜底：经传统 5555 再清一次残留进程（无线通道关闭时 proot 已随连接退出）
        try {
            val adb = AdbClassicTransport(context, adbHost(context), adbPort(context))
            adb.connect()
            adb.exec("pkill -f libproot.so 2>/dev/null; pkill -x dropbear 2>/dev/null; pkill -x sshd 2>/dev/null || true")
            adb.close()
        } catch (_: Exception) {}
        File(prootRoot(context), ADB_LAUNCHED_MARKER).delete()
        return UbuntuService.ServiceState(UbuntuService.Status.STOPPED, "Ubuntu 已停止（adb 路径）", null)
    }

    // ---------- 状态 ----------

    /** 上次记日志的探测状态（running, sshUp）；仅变化时记录，避免轮询刷屏。 */
    private var lastLogProbe: Pair<Boolean, Boolean>? = null

    fun detectStatus(context: Context): UbuntuService.ServiceState {
        val probe = probeState(context)
        val sshInfo = if (probe.running) buildSSHInfo(context, probe.sshUp) else null
        // 主页只显示简洁状态；SSH 未就绪等详细原因进日志（复制日志按钮可查）
        val cur = probe.running to probe.sshUp
        if (cur != lastLogProbe) {
            RuntimeLog.append(
                "deploy",
                "detectStatus: running=${probe.running} sshUp=${probe.sshUp}" +
                    if (probe.running && !probe.sshUp) "（SSH 未就绪，可能在安装或启动失败，复制日志查看）" else ""
            )
            lastLogProbe = cur
        }
        val message = when {
            probe.running && probe.sshUp -> "Ubuntu 运行中"
            probe.running -> "Ubuntu 启动中"
            else -> "Ubuntu 未运行"
        }
        return UbuntuService.ServiceState(
            if (probe.running) UbuntuService.Status.RUNNING else UbuntuService.Status.STOPPED,
            message, sshInfo
        )
    }

    fun isRunning(context: Context): Boolean = probeState(context).running

    private data class Probe(val running: Boolean, val sshUp: Boolean)

    /** 经当前可用通道执行一条探测命令。优先复用已建立的 transport（在其连接上开新流，
     *  毫秒级，避免每次新建 5555 连接拖慢/撞上 adbd 偶发忙）；无活动连接才新建。
     *  探测都是短命令，超时 20s 足够，避免异常时拖死状态轮询。 */
    private fun quickExec(context: Context, cmd: String, timeoutMs: Long = 20_000L): String {
        return try {
            val t = transport
            when {
                t != null && t.isConnected() -> t.exec(cmd, timeoutMs)
                else -> {
                    val wt = wirelessTransport
                    if (wt != null && wt.isConnected()) {
                        wt.exec(cmd, timeoutMs)
                    } else {
                        val adb = AdbClassicTransport(context, adbHost(context), adbPort(context))
                        adb.connect()
                        val out = adb.exec(cmd, timeoutMs)
                        adb.close()
                        out
                    }
                }
            }
        } catch (e: Exception) {
            // 失败原因写日志：否则 readLog/探测静默返回空，用户无法区分「真没日志」还是「读取失败」
            RuntimeLog.append("adb", "quickExec 失败：${e.message}；cmd=${cmd.take(80)}")
            ""
        }
    }

    private fun probeState(context: Context): Probe {
        // 注意：绝不能用 pgrep -f 匹配 "libproot.so"——执行本探测命令的 sh 自身命令行里
        // 就含字面 "libproot.so"（来自 pgrep 参数），会误报 proot 已运行。
        // 只用 pgrep -x 按进程名（comm）精确匹配：proot 进程 comm 即 libproot.so（<15 字符）。
        // SSH 就绪以宿主侧 .ssh_up 内容为 "1" 为准（dropbear 起来后 echo 1；预建值为 0，防误报）。
        val out = quickExec(
            context,
            "(pgrep -x libproot.so >/dev/null 2>&1) && echo UC_PROOT; " +
                    "(pgrep -x dropbear >/dev/null 2>&1 || pgrep -x sshd >/dev/null 2>&1) && echo UC_RUN; " +
                    "grep -q '^1$' $REMOTE_DIR/.ssh_up 2>/dev/null && echo UC_SSHUP"
        )
        return Probe(out.contains("UC_PROOT") || out.contains("UC_RUN"), out.contains("UC_SSHUP"))
    }

    // ---------- 命令控制台（per-command proot，经 adb shell） ----------

    fun runInUbuntu(context: Context, command: String, timeoutMs: Long = 60_000): ProotExecutor.ShellResult {
        val version = UbuntuService.getUbuntuVersion(context)
        val arch = UbuntuService.rootfsArch(context)
        val rootfs = "$REMOTE_DIR/rootfs.$version.$arch"
        val prootBin = "$REMOTE_DIR/libproot.so"
        val resolv = "$REMOTE_DIR/resolv.conf"
        val esc = command.replace("\\", "\\\\").replace("\"", "\\\"")
        // 与 start_proot_adb.sh 一致：PROOT_TMP_DIR 必须是【宿主绝对路径】，否则 proot 启动即崩
        // （Android 宿主无 /tmp；f2fs probe/glue rootfs 需要可写临时目录）
        val adbCmd =
            "export PROOT_TMP_DIR=$REMOTE_DIR/tmp; mkdir -p $REMOTE_DIR/tmp; " +
                    "$prootBin -r $rootfs -0 -w /root -b /dev -b /proc -b /sys " +
                    "-b $resolv:/etc/resolv.conf " +
                    "/bin/bash -c \"export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $esc\""
        return try {
            val out = quickExec(context, "($adbCmd)")
            ProotExecutor.ShellResult(0, out, "")
        } catch (e: Exception) {
            ProotExecutor.ShellResult(-1, "", e.message ?: "adb 执行失败")
        }
    }

    // ---------- 日志 ----------

    fun readLog(context: Context): String {
        var out = quickExec(context, "cat $REMOTE_DIR/ubuntu.log 2>/dev/null || echo '(无日志文件)'")
        if (out.isBlank()) {
            // 可能撞上 adbd 忙（安装期 CPU 高 / 并发 shell），重试一次再下结论
            out = quickExec(context, "cat $REMOTE_DIR/ubuntu.log 2>/dev/null || echo '(无日志文件)'")
            if (out.isBlank()) {
                RuntimeLog.append("adb", "readLog 两次读取均为空（adb 忙或日志文件为空）")
                return "(读取失败：adb 忙或日志为空，请稍后重试)"
            }
        }
        return out.trim().ifBlank { "(无日志内容)" }
    }

    private fun buildSSHInfo(context: Context, sshUp: Boolean): UbuntuService.SSHInfo {
        return UbuntuService.SSHInfo(
            host = localIpAddress(),   // 真实本机局域网 IP（如 172.16.2.8），非 127.0.0.1
            port = UbuntuService.getProotSshPort(context),
            username = UbuntuService.getSshUser(context),
            password = UbuntuService.getSshPassword(context),
            isRunning = sshUp
        )
    }

    /** 获取本机非回环 IPv4 地址（SSH 客户端真实可达地址，如 172.16.2.8）。 */
    private fun localIpAddress(): String {
        try {
            val nis = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addrs = ni.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    /** 是否由 adb 路径启动（标记存在）。 */
    fun isAdbLaunched(context: Context): Boolean =
        File(prootRoot(context), ADB_LAUNCHED_MARKER).exists()
}
