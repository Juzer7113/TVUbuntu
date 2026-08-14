package com.ubuntucontroller

import android.os.Bundle
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
        checkRootAndStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            setButtonsEnabled(false)
            updateStatus(UbuntuService.Status.STARTING, getString(R.string.status_starting), "")
            lifecycleScope.launch {
                val state = withContext(Dispatchers.IO) {
                    UbuntuService.startUbuntu(this@MainActivity)
                }
                updateUI(state)
                setButtonsEnabled(true)
                if (state.status == UbuntuService.Status.ERROR) {
                    Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnStop.setOnClickListener {
            setButtonsEnabled(false)
            updateStatus(UbuntuService.Status.STOPPING, getString(R.string.status_stopping), "")
            lifecycleScope.launch {
                val state = withContext(Dispatchers.IO) {
                    UbuntuService.stopUbuntu(this@MainActivity)
                }
                updateUI(state)
                setButtonsEnabled(true)
                if (state.status == UbuntuService.Status.ERROR) {
                    Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnSettings.setOnClickListener {
            SettingsActivity.launch(this)
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
            refreshStatus()
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
            binding.tvSSHPort.text = "--"
            binding.tvSSHUser.text = "--"
            binding.tvSSHCommand.text = "--"
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
        binding.tvSSHPort.text = sshInfo.port.toString()
        binding.tvSSHUser.text = sshInfo.username
        binding.tvSSHCommand.text = "ssh ${sshInfo.username}@${sshInfo.host} -p ${sshInfo.port}"
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnStart.isEnabled = enabled
        binding.btnStop.isEnabled = enabled
        binding.btnStart.alpha = if (enabled) 1f else 0.5f
        binding.btnStop.alpha = if (enabled) 1f else 0.5f
    }
}
