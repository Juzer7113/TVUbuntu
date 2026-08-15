package com.ubuntucontroller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
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

    // 复制完整运行日志到剪贴板，便于快速把日志发给开发者排查
    private fun copyLog() {
        lifecycleScope.launch {
            val log = withContext(Dispatchers.IO) {
                UbuntuService.readLog()
            }
            if (log.isEmpty() || log == "(无日志内容)" || log == "(无日志文件)") {
                Toast.makeText(this@MainActivity, getString(R.string.log_empty), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ubuntu_log", log))
            Toast.makeText(this@MainActivity, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerStart() {
        setOperating()
        showProgress(true)
        binding.pbInstall.progress = 0
        updateStatus(UbuntuService.Status.STARTING, getString(R.string.status_installing), "")
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                UbuntuService.startUbuntu(this@MainActivity) { pct, msg ->
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
                UbuntuService.stopUbuntu(this@MainActivity)
            }
            updateUI(state)
            if (state.status == UbuntuService.Status.ERROR) {
                Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkRootAndStatus() {
        lifecycleScope.launch {
            val hasRoot = withContext(Dispatchers.IO) {
                ShellExecutor.checkRootAccess()
            }
            if (hasRoot) {
                binding.tvRootStatus.text = getString(R.string.root_granted)
                binding.tvRootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_running))
            } else {
                binding.tvRootStatus.text = getString(R.string.root_denied)
                binding.tvRootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
            }

            val state = withContext(Dispatchers.IO) {
                UbuntuService.detectStatus(this@MainActivity)
            }
            updateUI(state)
            // 开机自动启动：已授权 Root 且开启开关，且当前未运行 -> 自动拉起
            if (hasRoot && UbuntuService.getAutoStart(this@MainActivity) && state.status != UbuntuService.Status.RUNNING) {
                triggerStart()
            }
        }
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                UbuntuService.detectStatus(this@MainActivity)
            }
            updateUI(state)
        }
    }

    private fun updateUI(state: UbuntuService.ServiceState) {
        updateStatus(state.status, state.message, "")
        updateSSHInfo(state.sshInfo)
        applyButtonState(state.status == UbuntuService.Status.RUNNING)
    }

    private fun updateStatus(status: UbuntuService.Status, text: String, detail: String) {
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

        binding.tvSSHHost.text = sshInfo.host
        binding.tvSSHUser.text = sshInfo.username
        binding.tvSSHPassword.text = sshInfo.password
    }

    // 运行中：启动按钮禁用、停止按钮可用；停止时反之。禁用按钮同时不可聚焦，遥控自动跳过。
    private fun applyButtonState(running: Boolean) {
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.btnStart.isFocusable = !running
        binding.btnStop.isFocusable = running
        // 若焦点正落在刚被禁用的按钮上，自动转移到可用按钮，避免焦点丢失
        if (currentFocus === binding.btnStart && !binding.btnStart.isFocusable) {
            binding.btnStop.requestFocus()
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
