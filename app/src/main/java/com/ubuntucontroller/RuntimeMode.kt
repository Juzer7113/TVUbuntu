package com.ubuntucontroller

/**
 * TVUbuntu 运行模式。
 *
 * - ROOT：强制使用原生 root + chroot 方案（需要设备已 Root）。
 * - PROOT：强制使用 proot 用户空间方案（无需 Root，但受 Android 沙盒限制）。
 * - AUTO：优先尝试 Root 方案；检测不到 root 时自动回退到 proot 方案。
 */
enum class RuntimeMode(val value: String) {
    ROOT("root"),
    PROOT("proot"),
    AUTO("auto");

    companion object {
        fun from(value: String?): RuntimeMode {
            return values().find { it.value == value } ?: AUTO
        }
    }
}
