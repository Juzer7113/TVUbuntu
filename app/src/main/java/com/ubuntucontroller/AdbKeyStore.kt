package com.ubuntucontroller

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

/**
 * 统一的 ADB 密钥/证书存储。
 *
 * 私钥与证书均内置在 assets（同一把 RSA-2048 密钥）：
 *  - adb_key.pem   —— PKCS#8 私钥（与旧版 AdbClient 同源，公钥恒定可烘焙进固件 adb_keys）
 *  - adb_cert.pem  —— 由同一私钥生成的 X.509 自签证书（CN=TVUbuntu）
 *
 * 无线调试（Android 11+）配对时，libadb 用「私钥 + 证书」做 TLS 客户端认证，设备把证书里的
 * RSA 公钥写入自己的 adb_keys；该公钥与本 App 走传统 5555 通道用的公钥完全一致，因此：
 *   - 配对码即授权，全程无「允许 USB 调试」弹窗；
 *   - 已烘焙进固件的旧方案依旧有效（公钥未变）。
 */
object AdbKeyStore {
    private var privateKey: PrivateKey? = null
    private var certificate: java.security.cert.X509Certificate? = null
    private var publicKey: RSAPublicKey? = null

    /** 最后一次加载失败的原因（供 UI 显示）。 */
    var lastError: String? = null
        private set

    fun load(context: Context): Boolean {
        lastError = null
        if (privateKey != null && certificate != null && publicKey != null) return true
        return try {
            val keyPem = context.assets.open("adb_key.pem").bufferedReader().use { it.readText() }
            val keyB64 = keyPem.lineSequence()
                .filter { !it.trim().startsWith("-----") }
                .joinToString("").replace("\\s".toRegex(), "")
            val keyDer = Base64.decode(keyB64, Base64.DEFAULT)
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(keyDer)) as RSAPrivateCrtKey

            val certPem = context.assets.open("adb_cert.pem").bufferedReader().use { it.readText() }
            val certB64 = certPem.lineSequence()
                .filter { !it.trim().startsWith("-----") }
                .joinToString("").replace("\\s".toRegex(), "")
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(
                ByteArrayInputStream(Base64.decode(certB64, Base64.DEFAULT))
            ) as java.security.cert.X509Certificate

            val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent)) as RSAPublicKey
            privateKey = priv
            certificate = cert
            publicKey = pub
            true
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
            false
        }
    }

    fun getPrivateKey(context: Context): PrivateKey {
        if (!load(context)) throw IllegalStateException("adb 私钥加载失败: ${lastError}")
        return privateKey!!
    }

    fun getCertificate(context: Context): java.security.cert.X509Certificate {
        if (!load(context)) throw IllegalStateException("adb 证书加载失败: ${lastError}")
        return certificate!!
    }

    fun getPublicKey(context: Context): RSAPublicKey {
        if (!load(context)) throw IllegalStateException("adb 公钥加载失败: ${lastError}")
        return publicKey!!
    }

    fun getDeviceName(): String = "TVUbuntu"

    /** 导出 adb 公钥文本（OpenSSH 风格，adb 格式），用于烘焙进固件 /data/misc/adb/adb_keys。 */
    fun getAdbPublicKeyText(context: Context): String =
        AdbKeyHelper.buildAdbPublicKeyStatic(getPublicKey(context))
}
