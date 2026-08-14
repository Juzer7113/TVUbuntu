package com.ubuntucontroller

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
    }

    private fun loadSettings() {
        binding.etStartScript.setText(UbuntuService.getStartScriptPath(this))
        binding.etStopScript.setText(UbuntuService.getStopScriptPath(this))
        binding.etSshPort.setText(UbuntuService.getSshPort(this).toString())
        binding.etSshUser.setText(UbuntuService.getSshUser(this))
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            val startScript = binding.etStartScript.text.toString().trim()
            val stopScript = binding.etStopScript.text.toString().trim()
            val portText = binding.etSshPort.text.toString().trim()
            val user = binding.etSshUser.text.toString().trim()

            if (startScript.isEmpty() || stopScript.isEmpty()) {
                Toast.makeText(this, "脚本路径不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val port = portText.toIntOrNull()
            if (port == null || port < 1 || port > 65535) {
                Toast.makeText(this, "端口号无效 (1-65535)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (user.isEmpty()) {
                Toast.makeText(this, "用户名不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            UbuntuService.setStartScriptPath(this, startScript)
            UbuntuService.setStopScriptPath(this, stopScript)
            UbuntuService.setSshPort(this, port)
            UbuntuService.setSshUser(this, user)

            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }
}
