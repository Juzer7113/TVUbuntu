package com.ubuntucontroller

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        // TV: 初始焦点放在运行模式第一个选项上
        binding.rbModeAuto.requestFocus()
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
        binding.etStartScript.applyTvFocus(1f)
        binding.etStopScript.applyTvFocus(1f)
        binding.etSshPort.applyTvFocus(1f)
        binding.etProotSshPort.applyTvFocus(1f)
        binding.etSshUser.applyTvFocus(1f)
        binding.etSshPassword.applyTvFocus(1f)
        binding.btnCancel.applyTvButtonFocus()
        binding.btnSave.applyTvButtonFocus()
    }

    private fun loadSettings() {
        binding.etStartScript.setText(UbuntuService.getStartScriptPath(this))
        binding.etStopScript.setText(UbuntuService.getStopScriptPath(this))
        binding.etSshPort.setText(UbuntuService.getSshPort(this).toString())
        binding.etProotSshPort.setText(UbuntuService.getProotSshPort(this).toString())
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
}
