package com.ubuntucontroller

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ubuntucontroller.AdbNetworkEnabler
import com.ubuntucontroller.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupButtons()
        setupTvFocus()
        binding.btnAdbAutoClick.setOnClickListener { openAdbAutoClick() }
        binding.btnEnableNetAdb.setOnClickListener { enableNetAdb() }
        // 初始按当前无障碍服务状态显示按钮文案
        binding.btnAdbAutoClick.setText(
            if (AdbAccessibilityHelper.isEnabled(this)) R.string.adb_auto_click_btn_on
            else R.string.adb_auto_click_btn_off
        )
        // TV: 初始焦点放在「开启 ADB 授权自动点击」按钮上
        binding.btnAdbAutoClick.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 返回键：等同「取消」，返回首页
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        // 方向键：手动驱动焦点导航兜底
        if (handleTvDpad(binding.root, keyCode)) return true
        if (isDpadKey(keyCode) && currentFocus == null) {
            binding.rbUbuntu22.requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // 给配置页所有可操作控件统一绑定 TV 焦点放大动画（光标指示）
    private fun setupTvFocus() {
        binding.rbModeAuto.applyTvFocus(1.05f)
        binding.rbModeRoot.applyTvFocus(1.05f)
        binding.rbModeProot.applyTvFocus(1.05f)
        binding.rbUbuntu22.applyTvFocus(1.05f)
        binding.rbUbuntu24.applyTvFocus(1.05f)
        binding.rbUbuntu26.applyTvFocus(1.05f)
        binding.rbArchAuto.applyTvFocus(1.05f)
        binding.rbArchAmd64.applyTvFocus(1.05f)
        binding.rbArchArm64.applyTvFocus(1.05f)
        binding.rbArchArmhf.applyTvFocus(1.05f)
        binding.swAutoStart.applyTvFocus(1.05f)
        binding.etAdbHost.applyTvFocus(1f)
        binding.etAdbPort.applyTvFocus(1f)
        binding.etStartScript.applyTvFocus(1f)
        binding.etStopScript.applyTvFocus(1f)
        binding.etSshPort.applyTvFocus(1f)
        binding.etProotSshPort.applyTvFocus(1f)
        binding.etSshUser.applyTvFocus(1f)
        binding.etSshPassword.applyTvFocus(1f)
        binding.btnCancel.applyTvButtonFocus()
        binding.btnSave.applyTvButtonFocus()
        binding.btnAdbAutoClick.applyTvButtonFocus()
        binding.btnEnableNetAdb.applyTvButtonFocus()
    }

    private fun loadSettings() {
        binding.etStartScript.setText(UbuntuService.getStartScriptPath(this))
        binding.etStopScript.setText(UbuntuService.getStopScriptPath(this))
        binding.etSshPort.setText(UbuntuService.getSshPort(this).toString())
        binding.etProotSshPort.setText(UbuntuService.getProotSshPort(this).toString())
        binding.etAdbHost.setText(UbuntuService.getAdbHost(this))
        binding.etAdbPort.setText(UbuntuService.getAdbPort(this).toString())
        binding.etSshUser.setText(UbuntuService.getSshUser(this))
        binding.etSshPassword.setText(UbuntuService.getSshPassword(this))
        binding.swAutoStart.isChecked = UbuntuService.getAutoStart(this)

        when (UbuntuService.getRuntimeMode(this)) {
            RuntimeMode.ROOT -> binding.rbModeRoot.isChecked = true
            RuntimeMode.PROOT -> binding.rbModeProot.isChecked = true
            else -> binding.rbModeAuto.isChecked = true
        }

        when (UbuntuService.getUbuntuVersion(this)) {
            UbuntuService.UBUNTU_24 -> binding.rbUbuntu24.isChecked = true
            UbuntuService.UBUNTU_26 -> binding.rbUbuntu26.isChecked = true
            else -> binding.rbUbuntu22.isChecked = true
        }

        when (UbuntuService.getArchOverride(this)) {
            UbuntuService.ARCH_AMD64 -> binding.rbArchAmd64.isChecked = true
            UbuntuService.ARCH_ARM64 -> binding.rbArchArm64.isChecked = true
            UbuntuService.ARCH_ARMHF -> binding.rbArchArmhf.isChecked = true
            else -> binding.rbArchAuto.isChecked = true
        }
    }

    private fun selectedRuntimeMode(): RuntimeMode {
        return when {
            binding.rbModeRoot.isChecked -> RuntimeMode.ROOT
            binding.rbModeProot.isChecked -> RuntimeMode.PROOT
            else -> RuntimeMode.AUTO
        }
    }

    private fun selectedUbuntuVersion(): String {
        return when {
            binding.rbUbuntu24.isChecked -> UbuntuService.UBUNTU_24
            binding.rbUbuntu26.isChecked -> UbuntuService.UBUNTU_26
            else -> UbuntuService.UBUNTU_22
        }
    }

    private fun selectedArch(): String {
        return when {
            binding.rbArchAmd64.isChecked -> UbuntuService.ARCH_AMD64
            binding.rbArchArm64.isChecked -> UbuntuService.ARCH_ARM64
            binding.rbArchArmhf.isChecked -> UbuntuService.ARCH_ARMHF
            else -> UbuntuService.ARCH_AUTO
        }
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            val startScript = binding.etStartScript.text.toString().trim()
            val stopScript = binding.etStopScript.text.toString().trim()
            val portText = binding.etSshPort.text.toString().trim()
            val prootPortText = binding.etProotSshPort.text.toString().trim()
            val adbHost = binding.etAdbHost.text.toString().trim()
            val adbPortText = binding.etAdbPort.text.toString().trim()
            val user = binding.etSshUser.text.toString().trim()
            val password = binding.etSshPassword.text.toString().trim()
            val autoStart = binding.swAutoStart.isChecked
            val runtimeMode = selectedRuntimeMode()

            if (startScript.isEmpty() || stopScript.isEmpty()) {
                Toast.makeText(this, "脚本路径不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val port = portText.toIntOrNull()
            if (port == null || port < 1 || port > 65535) {
                Toast.makeText(this, "Root 模式端口号无效 (1-65535)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prootPort = prootPortText.toIntOrNull()
            if (prootPort == null || prootPort < 1024 || prootPort > 65535) {
                Toast.makeText(this, "Proot 模式端口必须 >=1024 且 <=65535", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (adbHost.isEmpty()) {
                Toast.makeText(this, getString(R.string.adb_invalid_host), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val adbPort = adbPortText.toIntOrNull()
            if (adbPort == null || adbPort < 1 || adbPort > 65535) {
                Toast.makeText(this, getString(R.string.adb_invalid_port), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val version = selectedUbuntuVersion().takeIf { it in UbuntuService.UBUNTU_VERSIONS }
                ?: UbuntuService.UBUNTU_22
            val arch = selectedArch().takeIf { it in UbuntuService.ARCH_OVERRIDES }
                ?: UbuntuService.ARCH_AUTO

            if (user.isEmpty()) {
                Toast.makeText(this, "用户名不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // C 修复：密码字符集校验。拒绝空格与控制字符，避免传给 `sh -c` 时破坏参数或被注入。
            val effectivePassword = if (password.isEmpty()) {
                Toast.makeText(this, "密码不能为空，已使用默认密码 Aa123456", Toast.LENGTH_SHORT).show()
                "Aa123456"
            } else {
                if (password.contains("\\s".toRegex())) {
                    Toast.makeText(this, "密码不能包含空格", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (password.any { it < ' ' || it > '~' }) {
                    Toast.makeText(this, "密码只能包含可见 ASCII 字符", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                password
            }
            UbuntuService.setSshPassword(this, effectivePassword)

            UbuntuService.setStartScriptPath(this, startScript)
            UbuntuService.setStopScriptPath(this, stopScript)
            UbuntuService.setSshPort(this, port)
            UbuntuService.setProotSshPort(this, prootPort)
            UbuntuService.setAdbHost(this, adbHost)
            UbuntuService.setAdbPort(this, adbPort)
            UbuntuService.setSshUser(this, user)
            UbuntuService.setUbuntuVersion(this, version)
            UbuntuService.setArchOverride(this, arch)
            UbuntuService.setAutoStart(this, autoStart)
            UbuntuService.setRuntimeMode(this, runtimeMode)

            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }

    /**
     * 开启/跳转「ADB 授权自动点击」无障碍服务。
     * 优先尝试以 WRITE_SECURE_SETTINGS 直接开启（系统签名 / adb pm grant 时有效）；
     * 失败则跳转系统无障碍设置页，由用户手动开启本 App 的无障碍服务。
     */
    private fun openAdbAutoClick() {
        if (AdbAccessibilityHelper.isEnabled(this)) {
            Toast.makeText(this, R.string.adb_auto_click_btn_on, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.adb_auto_click_opening, Toast.LENGTH_SHORT).show()
        val ok = AdbAccessibilityHelper.tryEnable(this)
        if (ok) {
            binding.btnAdbAutoClick.setText(R.string.adb_auto_click_btn_on)
            Toast.makeText(this, R.string.adb_auto_click_btn_on, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.adb_auto_click_open_settings, Toast.LENGTH_LONG).show()
            AdbAccessibilityHelper.openSettings(this)
        }
    }

    /**
     * 网络 ADB 自愈：在已获特权（Root / 已授权 adb）时把 adbd 切到 TCP 5555 并持久化，
     * 之后开软件/开机可自动直连，免去每次手动开「网络 ADB 调试」。
     */
    private fun enableNetAdb() {
        Thread {
            val ok = AdbNetworkEnabler.enableViaRoot(this) ||
                AdbNetworkEnabler.enableViaAdb(this)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "已尝试开启网络 ADB（5555），请等待数秒后自动连接"
                    else "开启失败：需要 Root 或已授权 adb 通道",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

}
