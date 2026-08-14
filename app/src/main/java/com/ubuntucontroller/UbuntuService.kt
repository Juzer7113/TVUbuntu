package com.ubuntucontroller

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

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
    private const val PREFS_NAME = "ubuntu_controller_prefs"
    private const val KEY_START_SCRIPT = "start_script_path"
    private const val KEY_STOP_SCRIPT = "stop_script_path"
    private const val KEY_SSH_PORT = "ssh_port"
    private const val KEY_SSH_USER = "ssh_user"

    fun getStartScriptPath(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_START_SCRIPT, DEFAULT_START_SCRIPT) ?: DEFAULT_START_SCRIPT
    }

    fun setStartScriptPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_START_SCRIPT, path).apply()
    }

    fun getStopScriptPath(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STOP_SCRIPT, DEFAULT_STOP_SCRIPT) ?: DEFAULT_STOP_SCRIPT
    }

    fun setStopScriptPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_STOP_SCRIPT, path).apply()
    }

    fun getSshPort(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SSH_PORT, DEFAULT_SSH_PORT)
    }

    fun setSshPort(context: Context, port: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SSH_PORT, port).apply()
    }

    fun getSshUser(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SSH_USER, DEFAULT_SSH_USER) ?: DEFAULT_SSH_USER
    }

    fun setSshUser(context: Context, user: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SSH_USER, user).apply()
    }

    fun startUbuntu(context: Context): ServiceState {
        val scriptPath = getStartScriptPath(context)

        val checkExists = ShellExecutor.executeAsRoot("test -f $scriptPath && echo 'exists'")
        if (checkExists.output != "exists") {
            return ServiceState(
                Status.ERROR,
                "启动脚本不存在: $scriptPath",
                null
            )
        }

        val result = ShellExecutor.executeAsRoot("sh $scriptPath")
        return if (result.exitCode == 0) {
            Thread.sleep(2000)
            val sshInfo = detectSSHInfo(context)
            ServiceState(Status.RUNNING, "Ubuntu 启动成功", sshInfo)
        } else {
            ServiceState(
                Status.ERROR,
                "启动失败: ${result.error.ifBlank { result.output }}",
                null
            )
        }
    }

    fun stopUbuntu(context: Context): ServiceState {
        val scriptPath = getStopScriptPath(context)

        val checkExists = ShellExecutor.executeAsRoot("test -f $scriptPath && echo 'exists'")
        if (checkExists.output != "exists") {
            val fallbackResult = ShellExecutor.executeAsRoot(
                "pkill -f 'chroot.*ubuntu' || true; " +
                "umount -l /data/local/ubuntu/mnt/* 2>/dev/null || true"
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
        val isRunning = checkUbuntuRunning()
        val sshInfo = if (isRunning) detectSSHInfo(context) else null
        return ServiceState(
            if (isRunning) Status.RUNNING else Status.STOPPED,
            if (isRunning) "Ubuntu 运行中" else "Ubuntu 未运行",
            sshInfo
        )
    }

    private fun checkUbuntuRunning(): Boolean {
        val result = ShellExecutor.executeAsRoot(
            "pgrep -f 'chroot.*ubuntu|proot.*ubuntu|ubuntu.*init' || echo ''"
        )
        return result.output.isNotBlank()
    }

    private fun detectSSHInfo(context: Context): SSHInfo? {
        val port = getSshPort(context)
        val user = getSshUser(context)
        val host = getDeviceIP(context) ?: "unknown"

        val sshCheck = ShellExecutor.executeAsRoot(
            "ss -tlnp | grep ':$port' || netstat -tlnp 2>/dev/null | grep ':$port'"
        )
        val isRunning = sshCheck.output.isNotBlank()

        return SSHInfo(
            host = host,
            port = port,
            username = user,
            isRunning = isRunning
        )
    }

    private fun getDeviceIP(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    (ipInt >> 8) and 0xff,
                    (ipInt >> 16) and 0xff,
                    (ipInt >> 24) and 0xff
                )
            }
        } catch (_: Exception) {
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name.startsWith("eth") || iface.name.startsWith("wlan")) {
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
        return null
    }
}
