package com.ubuntucontroller

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * ADB 授权自动点击（无障碍服务）开关辅助。
 *
 * - [isEnabled]：判断服务是否已在系统「已启用的无障碍服务」列表中。
 * - [tryEnable]：尝试以 WRITE_SECURE_SETTINGS 权限直接写入系统设置开启（需 App 持有该权限，
 *   如系统签名 / adb `pm grant`；普通第三方签名会抛异常，调用方应退化到 [openSettings] 引导手动开启）。
 * - [openSettings]：跳转系统无障碍设置页，由用户手动开启。
 */
object AdbAccessibilityHelper {

    private const val SERVICE_FLAT = AdbAuthAutoClickService.SERVICE_FLAT

    private fun enabledValue(context: Context): String =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

    fun isEnabled(context: Context): Boolean {
        val enabled = enabledValue(context)
        if (enabled.isEmpty()) return false
        return enabled.split(":").any {
            it.equals(SERVICE_FLAT, ignoreCase = true) ||
                    it.equals(".AdbAuthAutoClickService", ignoreCase = true)
        }
    }

    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun tryEnable(context: Context): Boolean {
        return try {
            val enabled = enabledValue(context)
            val newVal = if (enabled.isEmpty() || enabled == ":") {
                SERVICE_FLAT
            } else if (enabled.split(":").any {
                it.equals(SERVICE_FLAT, ignoreCase = true)
            }) {
                enabled
            } else {
                "$enabled:$SERVICE_FLAT"
            }
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newVal
            )
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            isEnabled(context)
        } catch (_: Exception) {
            false
        }
    }

    fun tryDisable(context: Context): Boolean {
        return try {
            val enabled = enabledValue(context)
            val filtered = enabled.split(":")
                .filter {
                    !it.equals(SERVICE_FLAT, ignoreCase = true) &&
                            !it.equals(".AdbAuthAutoClickService", ignoreCase = true)
                }
                .joinToString(":")
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                filtered
            )
            if (filtered.isEmpty()) {
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
