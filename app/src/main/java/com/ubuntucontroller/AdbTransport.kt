package com.ubuntucontroller

import java.io.File

/**
 * ADB 传输统一接口：无线 TLS 通道（AdbWirelessTransport）与传统 5555 明文通道（AdbClient）
 * 都实现本接口，使 AdbProotService 的启动/探测/命令逻辑与具体传输解耦。
 */
interface AdbTransport {
    /** 建立连接（无线通道已连，此处为空操作；传统通道按构造参数连接）。 */
    fun connect()

    /** 推送本地文件到设备远程路径，成功返回 true。 */
    fun push(local: File, remote: String, mode: String = "644"): Boolean

    /** 一次性 shell 命令，返回 stdout+stderr 文本。 */
    fun exec(cmd: String): String

    /** 持久启动 shell 命令（proot 常驻），内部持有流；关闭 transport 时一并关闭。 */
    fun startPersistent(cmd: String, logLine: (String) -> Unit): Boolean

    /** 关闭整个连接（proot 随连接关闭而退出）。 */
    fun close()

    /** 中断正在进行的连接。 */
    fun cancel()
}
