package com.ubuntucontroller

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * libadb-android 的 AbsAdbConnectionManager 实现。
 *
 * 仅需提供本 App 的固定私钥 + 自签证书 + 设备名，配对（pair）与 TLS 连接（connectTls）
 * 由基类完成。密钥/证书来自 AdbKeyStore（assets 内置，公钥恒定）。
 */
class UbuntuAdbManager(private val context: Context) : AbsAdbConnectionManager() {
    override fun getPrivateKey(): PrivateKey = AdbKeyStore.getPrivateKey(context)
    override fun getCertificate(): Certificate = AdbKeyStore.getCertificate(context)
    override fun getDeviceName(): String = AdbKeyStore.getDeviceName()
}
