package com.ubuntucontroller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ubuntucontroller.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
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
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener { triggerStart() }

        binding.btnStop.setOnClickListener { triggerStop() }

        binding.btnSettings.setOnClickListener {
            SettingsActivity.launch(this)
        }

        binding.btnCopyLog.setOnClickListener { copyLog() }
    }

    // 复制完整运行日志到剪贴板，并落盘到外部存储，便于直接分享 / adb 拉取
    private fun copyLog() {
        lifecycleScope.launch {
            val log = withContext(Dispatchers.IO) {
                UbuntuRuntime.readLog(this@MainActivity)
            }
            if (log.isEmpty() || log == "(无日志内容)" || log == "(无日志文件)") {
                Toast.makeText(this@MainActivity, getString(R.string.log_empty), Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 1) 复制到剪贴板（整份日志，含 [proot-inner] 段，未截断）
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ubuntu_log", log))
            // 2) 同时落盘到外部存储（应用私有外部目录，无需授权），方便分享 / adb pull
            val extDir = getExternalFilesDir(null)
            var savedPath: String? = null
            if (extDir != null) {
                try {
                    val f = java.io.File(extDir, "ubuntu_proot_log.txt")
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
            val state = withContext(Dispatchers.IO) {
                UbuntuRuntime.start(this@MainActivity) { pct, msg ->
                    runOnUiThread {
                        binding.pbInstall.progress = pct
                        binding.tvProgress.text = msg
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
            // 勾选「软件启动时启动」且当前未运行时，打开 App 自动按生效模式拉起服务
            if (UbuntuService.getAutoStart(this@MainActivity) &&
                state.status == UbuntuService.Status.STOPPED
            ) {
                triggerStart()
            }
        }
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
