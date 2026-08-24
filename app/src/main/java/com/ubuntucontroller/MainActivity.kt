package com.ubuntucontroller

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ubuntucontroller.AdbAutoAcquire
import com.ubuntucontroller.AdbNetworkEnabler
import com.ubuntucontroller.AdbWirelessPairing
import com.ubuntucontroller.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        setupTvFocus()
        setupTerminal()
        checkRootAndStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // TV: 进入页面显式把焦点放到可用按钮，保证遥控有初始焦点和可见高亮
        binding.btnStart.post {
            if (binding.btnStart.isFocusable) binding.btnStart.requestFocus()
            else binding.btnStop.requestFocus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 终端聚焦时，返回键退出输入框
        if (binding.etTerminal.isFocused && keyCode == KeyEvent.KEYCODE_BACK) {
            exitCommandInput()
            return true
        }
        // 方向键：手动驱动焦点导航（低性能机顶盒框架默认焦点搜索可能失效的兜底）
        if (handleTvDpad(binding.root, keyCode)) return true
        // 焦点意外丢失时，方向键把焦点拉回可用按钮
        if (isDpadKey(keyCode) && currentFocus == null) {
            (if (binding.btnStart.isFocusable) binding.btnStart else binding.btnStop).requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // 给首页可操作控件统一绑定 TV 焦点放大 + 文字加粗动画（光标指示）
    private fun setupTvFocus() {
        binding.btnStart.applyTvButtonFocus()
        binding.btnStop.applyTvButtonFocus()
        binding.btnCopyLog.applyTvButtonFocus()
        binding.btnSettings.applyTvButtonFocus()
        binding.btnAdb.applyTvButtonFocus()
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener { triggerStart() }

        binding.btnStop.setOnClickListener { triggerStop() }

        binding.btnSettings.setOnClickListener {
            SettingsActivity.launch(this)
        }

        binding.btnCopyLog.setOnClickListener { copyLog() }

        // ADB 按钮：点击即（重新）申请 adb 授权 / 重试连接（传统 5555 通道）。
        // 第三级递进：手动点击仍连不上（adbd 不可达 / 版本过低）→ 自动弹无线配窗口。
        binding.btnAdb.setOnClickListener { requestAdbAuth() }
    }

    // 复制完整运行日志到剪贴板，并落盘到外部存储，便于直接分享 / adb 拉取
    private fun copyLog() {
        lifecycleScope.launch {
            // 应用内运行时日志（记录启动/ADB获取/推送/启动每步）+ 设备侧 ubuntu.log 合并导出。
            // 即使卡在「推送 proot 二进制」阶段（设备日志尚未生成），运行时日志也已记录卡点，可定位问题。
            val runtime = withContext(Dispatchers.IO) { RuntimeLog.getLog() }
            val device = withContext(Dispatchers.IO) {
                runCatching { UbuntuRuntime.readLog(this@MainActivity) }.getOrDefault("(读取设备日志失败)")
            }
            val log = buildString {
                append("# ===== TVUbuntu 运行时日志（应用内步骤） =====\n")
                append(runtime.ifBlank { "(无应用内日志)" })
                append("\n\n# ===== 设备 ubuntu.log =====\n")
                // 空日志给出明确解释：ubuntu.log 由 proot 启动后期脚本生成，启动早期为空属正常
                val deviceDisplay = when (device) {
                    "(无日志文件)" -> "（尚未生成：proot 启动脚本可能未执行或正在启动，请以【应用内日志】为准）"
                    "(无日志内容)" -> "（为空：proot 正在启动或尚未写入日志，请以【应用内日志】为准）"
                    else -> device
                }
                append(deviceDisplay)
            }
            if (runtime.isBlank() &&
                (device == "(无日志内容)" || device == "(无日志文件)" || device == "(读取设备日志失败)")
            ) {
                Toast.makeText(this@MainActivity, getString(R.string.log_empty), Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 1) 复制到剪贴板（整份日志，未截断）
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ubuntu_log", log))
            // 2) 同时落盘到外部存储（应用私有外部目录，无需授权），方便分享 / adb pull
            val extDir = getExternalFilesDir(null)
            var savedPath: String? = null
            if (extDir != null) {
                try {
                    val f = java.io.File(extDir, "ubuntu_full_log.txt")
                    f.writeText(log)
                    savedPath = f.absolutePath
                } catch (_: Exception) {
                    savedPath = null
                }
            }
            // 3) 提示：剪贴板 + 文件路径（adb pull 该路径即可取到完整日志）
            val msg = if (savedPath != null) {
                getString(R.string.log_saved, savedPath)
            } else {
                getString(R.string.log_copied)
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ===== 终端式命令控制台：输入与输出同一个框 =====
    private lateinit var prompt: String
    private var terminalActive = false
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    // 防「互抢」：自动启动流程（onDone 回调 + 20s 兜底）只准触发一次 proot，避免双开。
    private var autoStartTriggered = false

    private fun setupTerminal() {
        prompt = "${UbuntuService.getSshUser(this)}@ubuntu:~$ "
        binding.etTerminal.setText(prompt)
        binding.etTerminal.setSelection(binding.etTerminal.text.length)
        // 终端始终可聚焦：未启动时按 OK 会提示先启动服务，不进入输入
        binding.etTerminal.setCursorVisible(false) // 未进入前光标不闪

        // 离开终端时重置「已进入输入」状态并隐藏光标
        binding.etTerminal.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                terminalActive = false
                binding.etTerminal.setCursorVisible(false)
            }
        }

        // 终端按键状态机：未进入=OK 才进入；已进入=OK/回车执行、上下翻历史
        binding.etTerminal.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false // 交给 onKeyDown
            if (terminalActive) {
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_DPAD_CENTER -> {
                        if (event.repeatCount == 0) runCommand()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> { if (event.repeatCount == 0) recallHistory(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.repeatCount == 0) recallHistory(1); true }
                    else -> false
                }
            } else {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.repeatCount == 0) activateTerminal()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveFocusToButtons()
                        true
                    }
                    else -> true // 未进入时吞掉其它输入
                }
            }
        }
    }

    private fun activateTerminal() {
        lifecycleScope.launch {
            val running = withContext(Dispatchers.IO) {
                UbuntuRuntime.isRunning(this@MainActivity)
            }
            if (!running) {
                Toast.makeText(this@MainActivity, getString(R.string.command_not_running), Toast.LENGTH_LONG).show()
                return@launch
            }
            terminalActive = true
            binding.etTerminal.setCursorVisible(true) // 进入后光标闪
            binding.etTerminal.setSelection(binding.etTerminal.text.length)
        }
    }

    // 未进入输入状态时，任意方向键都回到顶部按钮（终端在底部，无其它焦点目标）
    private fun moveFocusToButtons(): Boolean {
        val target = when {
            binding.btnCopyLog.isFocusable -> binding.btnCopyLog
            binding.btnSettings.isFocusable -> binding.btnSettings
            binding.btnStop.isFocusable -> binding.btnStop
            else -> binding.btnStart
        }
        target.requestFocus()
        return true
    }

    // 执行当前输入行命令，输出追加到同一个终端框
    private fun runCommand() {
        val full = binding.etTerminal.text.toString()
        val idx = full.lastIndexOf(prompt)
        if (idx < 0) {
            binding.etTerminal.append(prompt)
            binding.etTerminal.setSelection(binding.etTerminal.text.length)
            return
        }
        val cmd = full.substring(idx + prompt.length).trim()
        // 空命令：像正常 SSH 一样直接换行，回到新提示符
        if (cmd.isEmpty()) {
            binding.etTerminal.append("\n")
            binding.etTerminal.append(prompt)
            binding.etTerminal.setSelection(binding.etTerminal.text.length)
            return
        }
        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
            commandHistory.add(cmd)
        }
        historyIndex = -1
        binding.etTerminal.append("\n")
        lifecycleScope.launch {
            val running = withContext(Dispatchers.IO) {
                UbuntuRuntime.isRunning(this@MainActivity)
            }
            if (!running) {
                binding.etTerminal.append(getString(R.string.command_not_running))
                binding.etTerminal.append("\n")
                binding.etTerminal.append(prompt)
                binding.etTerminal.setSelection(binding.etTerminal.text.length)
                Toast.makeText(this@MainActivity, getString(R.string.command_not_running), Toast.LENGTH_LONG).show()
                return@launch
            }
            val (exitCode, output, error) = withContext(Dispatchers.IO) {
                if (UbuntuRuntime.currentMode(this@MainActivity) == RuntimeMode.PROOT) {
                    val r = ProotUbuntuService.runInUbuntu(this@MainActivity, cmd, timeoutMs = 120_000)
                    Triple(r.exitCode, r.output, r.error)
                } else {
                    val r = UbuntuService.runInUbuntu(this@MainActivity, cmd, timeoutMs = 120_000)
                    Triple(r.exitCode, r.output, r.error)
                }
            }
            val out = buildString {
                if (output.isNotBlank()) append(output)
                if (error.isNotBlank()) {
                    if (length > 0) append("\n")
                    append(error)
                }
            }
            if (out.isNotBlank()) {
                binding.etTerminal.append(out)
                binding.etTerminal.append("\n")
            }
            if (exitCode != 0) {
                binding.etTerminal.append("[exit $exitCode]")
                binding.etTerminal.append("\n")
            }
            binding.etTerminal.append(prompt)
            binding.etTerminal.setSelection(binding.etTerminal.text.length)
        }
    }

    // 上下键回显历史：-1 更旧，+1 更新；翻到最新再向下则回到空白
    private fun recallHistory(direction: Int) {
        if (commandHistory.isEmpty()) return
        val size = commandHistory.size
        when {
            historyIndex == -1 && direction < 0 -> historyIndex = size - 1
            historyIndex == -1 && direction > 0 -> return
            else -> {
                val next = historyIndex + direction
                historyIndex = when {
                    next < 0 -> 0
                    next >= size -> -1
                    else -> next
                }
            }
        }
        val full = binding.etTerminal.text.toString()
        val idx = full.lastIndexOf(prompt)
        if (idx < 0) return
        val prefix = full.substring(0, idx + prompt.length)
        val replacement = if (historyIndex == -1) "" else commandHistory[historyIndex]
        binding.etTerminal.setText(prefix + replacement)
        binding.etTerminal.setSelection(binding.etTerminal.text.length)
    }

    // 退出终端：隐藏光标、清除焦点、隐藏软键盘，焦点交回主按钮
    private fun exitCommandInput() {
        terminalActive = false
        binding.etTerminal.setCursorVisible(false)
        binding.etTerminal.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etTerminal.windowToken, 0)
        (if (binding.btnStart.isFocusable) binding.btnStart else binding.btnStop).requestFocus()
    }

    private fun triggerStart() {
        setOperating()
        showProgress(true)
        binding.pbInstall.progress = 0
        updateStatus(UbuntuService.Status.STARTING, getString(R.string.status_installing), "")
        lifecycleScope.launch {
            var state = withContext(Dispatchers.IO) {
                UbuntuRuntime.start(this@MainActivity) { pct, msg ->
                    runOnUiThread {
                        binding.pbInstall.progress = pct
                        binding.tvProgress.text = msg
                    }
                }
            }
            // 部署返回后若 SSH 尚未就绪（proot 解压/首次安装 SSH 需几分钟，24.04 全新安装
            // 甚至超过 3 分钟）：进度条保持可见并后台持续探测，直到「完全启动(SSH 就绪)」
            // 进度才到 100%；超时仍未就绪则正常报错（写日志 + 主页 ERROR），绝不留在「启动中」。
            if (state.status == UbuntuService.Status.RUNNING && state.sshInfo?.isRunning != true) {
                // 5 分钟上限：首次安装需解压 rootfs + apt 装 dropbear，网络差时可能超过 3 分钟
                val deadline = System.currentTimeMillis() + 300_000L
                var pct = 80
                while (System.currentTimeMillis() < deadline) {
                    delay(10_000)
                    state = withContext(Dispatchers.IO) {
                        UbuntuRuntime.detectStatus(this@MainActivity)
                    }
                    if (state.sshInfo?.isRunning == true) break
                    if (state.status == UbuntuService.Status.ERROR) break
                    // 未完全启动前进度条永不提前到 100
                    pct = (pct + 2).coerceAtMost(99)
                    binding.pbInstall.progress = pct
                    binding.tvProgress.text = getString(R.string.status_installing)
                }
                if (state.sshInfo?.isRunning == true) {
                    binding.pbInstall.progress = 100
                } else if (state.status != UbuntuService.Status.ERROR) {
                    if (state.status == UbuntuService.Status.STOPPED) {
                        // proot 也没跑 → 真失败（启动进程退出/脚本报错），正常报错
                        RuntimeLog.append("deploy", "启动超时：5 分钟内 SSH 未就绪且 proot 未运行（可能在首次安装或网络受限），请复制日志查看设备侧错误")
                        state = UbuntuService.ServiceState(
                            UbuntuService.Status.ERROR,
                            "Ubuntu 启动超时：SSH 未就绪（复制日志查看原因）",
                            state.sshInfo
                        )
                    } else {
                        // proot 仍在运行但 SSH 未就绪：apt 安装可能因网络慢仍在进行，
                        // 不误报失败——保持「启动中」，提示用户复制日志查看（由下次探测/刷新收敛）
                        RuntimeLog.append("deploy", "超过 5 分钟 SSH 仍未就绪，但 proot 仍在运行（apt 安装可能受网络影响仍在进行），保持启动中状态")
                        state = UbuntuService.ServiceState(
                            UbuntuService.Status.RUNNING,
                            "Ubuntu 启动中（安装可能仍在进行，复制日志查看进度）",
                            state.sshInfo
                        )
                    }
                }
            }
            showProgress(false)
            updateUI(state)
            if (state.status == UbuntuService.Status.ERROR) {
                Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun triggerStop() {
        setOperating()
        updateStatus(UbuntuService.Status.STOPPING, getString(R.string.status_stopping), "")
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                UbuntuRuntime.stop(this@MainActivity)
            }
            updateUI(state)
            if (state.status == UbuntuService.Status.ERROR) {
                Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkRootAndStatus() {
        lifecycleScope.launch {
            val mode = withContext(Dispatchers.IO) {
                UbuntuRuntime.currentMode(this@MainActivity)
            }
            val hasRoot = withContext(Dispatchers.IO) {
                ShellExecutor.checkRootAccess()
            }

            // 根据当前生效模式显示 Root/Proot 状态
            when (mode) {
                RuntimeMode.PROOT -> {
                    binding.tvRootStatus.text = "Proot 模式"
                    binding.tvRootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_running))
                }
                else -> if (hasRoot) {
                    binding.tvRootStatus.text = getString(R.string.root_granted)
                    binding.tvRootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_running))
                } else {
                    binding.tvRootStatus.text = getString(R.string.root_denied)
                    binding.tvRootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
                }
            }

            val state = withContext(Dispatchers.IO) {
                UbuntuRuntime.detectStatus(this@MainActivity)
            }
            updateUI(state)

            // ADB 按钮仅在无 root 时显示（有 root 走 ROOT/chroot，不需要 adb 通道）
            updateAdbVisibility(hasRoot)

            // 勾选「软件启动时启动」且当前未运行时，打开 App 自动拉起服务。
            // Proot：先自动获取 ADB，拿到（或失败）后再自启 Ubuntu；若 ADB 获取较慢
            // （如首次等待手动授权），最多等 20s 兜底以纯 proot 拉起，避免「一直不启动」。
            // Root：无需 ADB，直接自启。
            val shouldAutoStart = UbuntuService.getAutoStart(this@MainActivity) &&
                state.status == UbuntuService.Status.STOPPED

            if (!hasRoot && AdbAutoAcquire.isEnabled(this@MainActivity)) {
                // 无 root 时需要 adb 通道：软件一打开就自动获取 ADB（无线 TLS 优先，否则经典 5555）。
                // 首次手动授权属正常；之后开软件/开机自动直连。
                var adbAcquireDone = false
                AdbAutoAcquire.acquire(
                    this@MainActivity,
                    onProgress = { msg -> binding.tvAdbStatus.text = msg },
                    onDone = { res ->
                        adbAcquireDone = true
                        onAdbAutoAcquireDone(res)
                        // ADB 获取完毕（无论成败）才自启 —— 保证「先有 ADB，再启动」
                        // 仅首个触发者生效（autoStartTriggered 防护，杜绝 onDone 与 20s 兜底双开）。
                        if (shouldAutoStart && !isFinishing && !autoStartTriggered) {
                            autoStartTriggered = true
                            triggerStart()
                        }
                    }
                )
                // 兜底：ADB 获取较慢（如等手动点允许）时，最多等 20s 仍以纯 proot 拉起，
                // 绝不卡死；若 20s 内 ADB 已就绪，上面 onDone 已先拉起（autoStartTriggered 已置），这里跳过。
                if (shouldAutoStart) {
                    lifecycleScope.launch {
                        delay(20_000)
                        if (!adbAcquireDone && !isFinishing && !autoStartTriggered) {
                            autoStartTriggered = true
                            triggerStart()
                        }
                    }
                }
            } else if (shouldAutoStart) {
                // 有 root 或已关闭 ADB 自动获取：无需等 ADB，直接自启
                triggerStart()
            }

            // 网络 ADB 开关：开启后按配置地址端口自持（开软件即把 adbd 切到该端口）
            if (AdbNetworkEnabler.isEnabled(this@MainActivity)) {
                lifecycleScope.launch(Dispatchers.IO) {
                    AdbNetworkEnabler.enable(this@MainActivity)
                }
            }
        }
    }

    // ===== ADB 鉴权按钮 =====

    /** 主动连接 adbd 并等待设备授权；结果回主线程刷新按钮与提示。
     *  第三级递进：手动点击仍连不上（adbd 不可达 / 版本过低）→ 自动弹无线配窗口（Android 11+），
     *  低版本设备给引导提示。AWAITING（等盒子上点「允许 USB 调试」）属正常手动授权步骤，不弹。 */
    private fun requestAdbAuth() {
        lifecycleScope.launch(Dispatchers.IO) {
            AdbProotService.requestAdbAuth(this@MainActivity) { res ->
                runOnUiThread {
                    updateAdbButton(res)
                    if (res.state == AdbProotService.AuthState.UNREACHABLE ||
                        res.state == AdbProotService.AuthState.ERROR
                    ) {
                        onManualAdbFailed()
                    }
                }
            }
        }
    }

    /** 手动点 ADB 仍失败后的兜底：Android 11+ 自动弹无线配窗口；低版本引导去开网络 ADB / 无障碍。 */
    private fun onManualAdbFailed() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            showPairDialog()
        } else {
            Toast.makeText(this, getString(R.string.adb_manual_failed_hint), Toast.LENGTH_LONG).show()
        }
    }

    /** 根据鉴权状态刷新 ADB 按钮：就绪=绿色「ADB就绪」，其余=红色「无ADB」；状态行给更细说明。 */
    private fun updateAdbButton(res: AdbProotService.AdbAuthResult) {
        val ready = res.state == AdbProotService.AuthState.CONNECTED
        if (ready) {
            binding.btnAdb.setText(R.string.adb_ready)
            binding.btnAdb.setBackgroundResource(R.drawable.btn_adb_ready)
            binding.tvAdbStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running))
        } else {
            binding.btnAdb.setText(R.string.adb_no_adb)
            binding.btnAdb.setBackgroundResource(R.drawable.btn_adb_red)
            binding.tvAdbStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error))
        }
        binding.btnAdb.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        binding.tvAdbStatus.text = res.message
        if (res.state == AdbProotService.AuthState.AWAITING) {
            Toast.makeText(this, getString(R.string.adb_allow_hint), Toast.LENGTH_LONG).show()
        }
    }

    /** AdbAutoAcquire 的结果映射到 ADB 按钮：成功=绿「ADB就绪」，失败=红 + 说明。 */
    private fun onAdbAutoAcquireDone(res: AdbAutoAcquire.Result) {
        if (res.success) {
            if (res.mode == AdbAutoAcquire.Mode.WIRELESS) {
                markWirelessReady()
            } else {
                binding.btnAdb.setText(R.string.adb_ready)
                binding.btnAdb.setBackgroundResource(R.drawable.btn_adb_ready)
                binding.btnAdb.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                binding.tvAdbStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running))
                binding.tvAdbStatus.text = res.message
            }
        } else {
            binding.btnAdb.setText(R.string.adb_no_adb)
            binding.btnAdb.setBackgroundResource(R.drawable.btn_adb_red)
            binding.btnAdb.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            binding.tvAdbStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            binding.tvAdbStatus.text = res.message
        }
    }

    /** ADB 按钮仅在设备无 root 时显示（有 root 走 ROOT/chroot，不需要 adb 通道）。同时重接焦点链跨过隐藏按钮。 */
    private fun updateAdbVisibility(hasRoot: Boolean) {
        if (hasRoot) {
            binding.btnAdb.visibility = View.GONE
            binding.tvAdbStatus.visibility = View.GONE
            binding.btnStart.nextFocusLeftId = R.id.btnSettings
            binding.btnSettings.nextFocusRightId = R.id.btnStart
        } else {
            binding.btnAdb.visibility = View.VISIBLE
            binding.tvAdbStatus.visibility = View.VISIBLE
            binding.btnStart.nextFocusLeftId = R.id.btnAdb
            binding.btnSettings.nextFocusRightId = R.id.btnAdb
            binding.btnAdb.nextFocusLeftId = R.id.btnSettings
            binding.btnAdb.nextFocusRightId = R.id.btnStart
        }
    }

    // ===== 无线调试免弹窗配对对话框 =====

    /** 标记无线 TLS 已连接：ADB 按钮变绿「ADB就绪」，状态行说明免弹窗。 */
    private fun markWirelessReady() {
        binding.btnAdb.setText(R.string.adb_ready)
        binding.btnAdb.setBackgroundResource(R.drawable.btn_adb_ready)
        binding.btnAdb.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        binding.tvAdbStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running))
        binding.tvAdbStatus.text = getString(R.string.adb_wireless_ready)
    }

    /** 弹出无线调试配对对话框：mDNS 自动发现 + 6 位配对码静默授权（免弹窗）。 */
    private fun showPairDialog() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Toast.makeText(this, getString(R.string.pair_adb_low), Toast.LENGTH_LONG).show()
            return
        }
        val view = layoutInflater.inflate(R.layout.pairing_dialog, null)
        val etHost = view.findViewById<EditText>(R.id.etPairHost)
        val etPort = view.findViewById<EditText>(R.id.etPairPort)
        val etCode = view.findViewById<EditText>(R.id.etPairCode)
        // 配对地址/端口预填 127.0.0.1:5555（经典网络 ADB 默认），保持可手动修改
        etHost.setText("127.0.0.1")
        etPort.setText("5555")
        val tvStatus = view.findViewById<TextView>(R.id.tvPairStatus)
        val btnDiscover = view.findViewById<Button>(R.id.btnPairDiscover)
        val btnConnect = view.findViewById<Button>(R.id.btnPairConnect)
        val btnCancel = view.findViewById<Button>(R.id.btnPairCancel)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        // 弹窗窗口背景用配置页同款 bg_primary，保证与设置页视觉一致（标题已在布局内自定义）
        dialog.window?.setBackgroundDrawableResource(R.color.bg_primary)
        // 软键盘弹出时压缩窗口，避免遮挡底部按钮（手机场景）
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        btnDiscover.setOnClickListener {
            tvStatus.text = getString(R.string.pair_status, "发现中…")
            AdbWirelessPairing.discoverPairing(this, 8000) { found ->
                runOnUiThread {
                    if (found != null) {
                        etHost.setText(found.host)
                        etPort.setText(found.port.toString())
                        tvStatus.text = getString(R.string.pair_status, "已发现 ${found.host}:${found.port}")
                    } else {
                        tvStatus.text = getString(R.string.pair_status, "未发现，请手动填入")
                    }
                }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConnect.setOnClickListener {
            val host = etHost.text.toString().trim()
            val portStr = etPort.text.toString().trim()
            val code = etCode.text.toString().trim()
            if (host.isEmpty() || portStr.isEmpty() || code.isEmpty()) {
                tvStatus.text = getString(R.string.pair_status, "地址/端口/配对码均不能为空")
                return@setOnClickListener
            }
            val port = portStr.toIntOrNull()
            if (port == null || port <= 0 || port > 65535) {
                tvStatus.text = getString(R.string.pair_status, "端口无效（1-65535）")
                return@setOnClickListener
            }
            btnConnect.isEnabled = false
            tvStatus.text = getString(R.string.pair_status, getString(R.string.pair_pairing))
            AdbProotService.pairWireless(this, host, port, code) { ok, msg ->
                runOnUiThread {
                    btnConnect.isEnabled = true
                    tvStatus.text = getString(R.string.pair_status, msg)
                    if (ok) {
                        markWirelessReady()
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()

        // 机顶盒（遥控器 D-pad）：初始焦点落在主操作按钮，避免一进弹窗就自动弹软键盘（电视无物理键盘）
        view.post { btnConnect.requestFocus() }

        // 窗口尺寸上限：宽 ≤ min(95%屏宽, 480dp)，高 ≤ 90%屏高；
        // 内容超出时 ScrollView 滚动，保证小平屏手机「配对并连接」按钮必可达（不再被裁切、触摸滑不动）
        val metrics = resources.displayMetrics
        val maxW = minOf((metrics.widthPixels * 0.95f).toInt(), (480 * metrics.density).toInt())
        val maxH = (metrics.heightPixels * 0.90f).toInt()
        dialog.window?.setLayout(maxW, maxH)

        // 反向键（返回键）焦点控制：弹窗内按返回 = 关闭弹窗并归位焦点到主界面 ADB 按钮（遥控器可预期）
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.setOnDismissListener { binding.btnAdb.requestFocus() }
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                UbuntuRuntime.detectStatus(this@MainActivity)
            }
            updateUI(state)
        }
    }

    private fun updateUI(state: UbuntuService.ServiceState) {
        val modeLabel = when (UbuntuRuntime.currentMode(this)) {
            RuntimeMode.PROOT -> "Proot"
            RuntimeMode.ROOT -> "Root"
            else -> "Auto"
        }
        binding.tvUbuntuVersion.text = "${UbuntuService.getUbuntuVersion(this)} · $modeLabel 模式"
        updateStatus(state.status, state.message, "")
        updateSSHInfo(state.sshInfo)
        applyButtonState(state)
    }

    private fun updateStatus(status: UbuntuService.Status, text: String, detail: String) {
        // 主页只显示状态标题；完整日志统一由「复制日志」按钮一键复制，不堆在主页
        binding.tvStatus.text = text
        binding.tvStatusDetail.text = detail

        val colorRes = when (status) {
            UbuntuService.Status.RUNNING -> R.color.status_running
            UbuntuService.Status.STARTING -> R.color.status_starting
            UbuntuService.Status.STOPPING -> R.color.status_starting
            UbuntuService.Status.STOPPED -> R.color.status_stopped
            UbuntuService.Status.ERROR -> R.color.status_error
        }
        binding.statusIndicator.background.setTint(ContextCompat.getColor(this, colorRes))
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun updateSSHInfo(sshInfo: UbuntuService.SSHInfo?) {
        if (sshInfo == null) {
            binding.tvSSHStatus.text = getString(R.string.ssh_not_running)
            binding.tvSSHStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped))
            binding.tvSSHHost.text = "--"
            binding.tvSSHUser.text = "--"
            binding.tvSSHPassword.text = "--"
            return
        }

        if (sshInfo.isRunning) {
            binding.tvSSHStatus.text = getString(R.string.ssh_running)
            binding.tvSSHStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running))
        } else {
            binding.tvSSHStatus.text = getString(R.string.ssh_not_ready)
            binding.tvSSHStatus.setTextColor(ContextCompat.getColor(this, R.color.status_starting))
        }

        binding.tvSSHHost.text = "${sshInfo.host}:${sshInfo.port}"
        binding.tvSSHUser.text = sshInfo.username
        binding.tvSSHPassword.text = sshInfo.password
    }

    // 按钮可用性：
    //   RUNNING    → 启动禁用、停止可用
    //   STARTING / STOPPING → 两个都禁用（安装/启动中禁止重复按「启动」，避免再拉一份 start_proot.sh）
    //   其余(STOPPED/ERROR) → 启动可用、停止禁用
    // 禁用按钮同时不可聚焦，遥控自动跳过。
    private fun applyButtonState(state: UbuntuService.ServiceState) {
        val running = state.status == UbuntuService.Status.RUNNING
        val busy = state.status == UbuntuService.Status.STARTING ||
            state.status == UbuntuService.Status.STOPPING
        binding.btnStart.isEnabled = !running && !busy
        binding.btnStop.isEnabled = running
        binding.btnStart.isFocusable = !running && !busy
        binding.btnStop.isFocusable = running
        // 若焦点正落在刚被禁用的按钮上，自动转移到可用按钮，避免焦点丢失
        if (currentFocus === binding.btnStart && !binding.btnStart.isFocusable) {
            (if (binding.btnStop.isFocusable) binding.btnStop else null)?.requestFocus()
        } else if (currentFocus === binding.btnStop && !binding.btnStop.isFocusable) {
            binding.btnStart.requestFocus()
        }
    }

    // 执行启动/停止过程中，两个按钮都临时禁用且不可聚焦
    private fun setOperating() {
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = false
        binding.btnStart.isFocusable = false
        binding.btnStop.isFocusable = false
    }

    private fun showProgress(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        binding.pbInstall.visibility = visibility
        binding.tvProgress.visibility = visibility
        if (!show) binding.tvProgress.text = ""
    }
}
