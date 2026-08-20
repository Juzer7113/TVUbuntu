package com.ubuntucontroller

import android.content.Context

/**
 * TVUbuntu 运行时路由。
 *
 * 根据用户设置的「运行模式」和当前设备是否已 Root，自动选择走：
 * - UbuntuService（原生 root + chroot）
 * - ProotUbuntuService（无 root 用户空间方案）
 *
 * 所有对外的启动/停止/状态/日志接口统一在此分发。
 */
object UbuntuRuntime {

    /**
     * 当前实际生效的运行模式。
     * AUTO 模式下会探测 root；若已 Root 则优先走 Root 方案，否则回退 Proot。
     */
    fun currentMode(context: Context): RuntimeMode {
        return when (UbuntuService.getRuntimeMode(context)) {
            RuntimeMode.AUTO -> if (ShellExecutor.checkRootAccess()) RuntimeMode.ROOT else RuntimeMode.PROOT
            RuntimeMode.ROOT -> RuntimeMode.ROOT
            RuntimeMode.PROOT -> RuntimeMode.PROOT
        }
    }

    fun start(context: Context, onProgress: (Int, String) -> Unit): UbuntuService.ServiceState {
        return if (currentMode(context) == RuntimeMode.PROOT) {
            ProotUbuntuService.startUbuntu(context, onProgress)
        } else {
            UbuntuService.startUbuntu(context, onProgress)
        }
    }

    fun stop(context: Context): UbuntuService.ServiceState {
        return if (currentMode(context) == RuntimeMode.PROOT) {
            ProotUbuntuService.stopUbuntu(context)
        } else {
            UbuntuService.stopUbuntu(context)
        }
    }

    fun detectStatus(context: Context): UbuntuService.ServiceState {
        return if (currentMode(context) == RuntimeMode.PROOT) {
            ProotUbuntuService.detectStatus(context)
        } else {
            UbuntuService.detectStatus(context)
        }
    }

    fun isRunning(context: Context): Boolean {
        return if (currentMode(context) == RuntimeMode.PROOT) {
            ProotUbuntuService.isRunning(context)
        } else {
            UbuntuService.isRunning(context)
        }
    }

    fun readLog(context: Context): String {
        return if (currentMode(context) == RuntimeMode.PROOT) {
            ProotUbuntuService.readLog(context)
        } else {
            UbuntuService.readLog()
        }
    }
}
