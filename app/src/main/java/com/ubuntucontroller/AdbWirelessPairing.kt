package com.ubuntucontroller

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import java.net.InetAddress

/**
 * 无线调试（Android 11+）免弹窗配对编排。
 *
 * 流程：
 *  1) discoverPairing()  —— mDNS 发现 _adb-tls-pairing（设备「无线调试」里点「使用配对码配对设备」后出现）
 *  2) pair()             —— 用 6 位配对码静默授权本机公钥（写入设备 adb_keys，无弹窗）
 *  3) connectTls()       —— mDNS 发现 _adb-tls-connect 并建 TLS 长连接，返回可复用的 AdbWirelessTransport
 *
 * 三个函数均为阻塞式（网络 IO），调用方需自行放到后台线程。
 */
object AdbWirelessPairing {

    data class Discovered(val host: String, val port: Int)

    /** 发现无线调试「配对」服务（_adb-tls-pairing）。timeoutMs 内找到一个即回调并停止。 */
    fun discoverPairing(context: Context, timeoutMs: Long = 8000, onFound: (Discovered?) -> Unit) {
        discover(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs, onFound)
    }

    /** 发现无线调试「连接」服务（_adb-tls-connect，配对后设备才广播）。 */
    fun discoverConnect(context: Context, timeoutMs: Long = 8000, onFound: (Discovered?) -> Unit) {
        discover(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, timeoutMs, onFound)
    }

    private fun discover(
        context: Context,
        serviceType: String,
        timeoutMs: Long,
        onFound: (Discovered?) -> Unit
    ) {
        var finished = false
        var mdns: AdbMdns? = null
        val listener = object : AdbMdns.OnAdbDaemonDiscoveredListener {
            override fun onPortChanged(address: InetAddress?, port: Int) {
                if (finished) return
                finished = true
                try { mdns?.stop() } catch (_: Exception) {}
                onFound(Discovered(address?.hostAddress ?: address?.hostName ?: "", port))
            }
        }
        mdns = AdbMdns(context, serviceType, listener)
        try { mdns.start() } catch (e: Exception) {
            onFound(null)
            return
        }
        Thread {
            Thread.sleep(timeoutMs)
            if (finished) return@Thread
            finished = true
            try { mdns.stop() } catch (_: Exception) {}
            onFound(null)
        }.start()
    }

    /** 配对：用 6 位码 + 配对地址端口静默授权本机公钥（写入设备 adb_keys，无弹窗）。 */
    fun pair(context: Context, host: String, port: Int, code: String): Pair<Boolean, String> {
        return try {
            val mgr = UbuntuAdbManager(context)
            mgr.setHostAddress(host)
            mgr.setApi(Build.VERSION.SDK_INT)
            val ok = mgr.pair(port, code)
            if (ok) true to "配对成功，设备已授权本机公钥" else false to "配对失败（未知原因）"
        } catch (e: Exception) {
            false to "配对异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 配对后经 TLS 连接端点建连（自动 mDNS 发现 _adb-tls-connect），返回可复用的传输。 */
    fun connectTls(context: Context, timeoutMs: Long = 8000): Pair<AdbWirelessTransport?, String> {
        return try {
            val mgr = UbuntuAdbManager(context)
            val ok = mgr.connectTls(context, timeoutMs)
            if (!ok) return null to "TLS 连接失败（未发现 _adb-tls-connect 或握手失败）"
            val conn = mgr.adbConnection ?: return null to "TLS 连接为空"
            AdbWirelessTransport(conn) to "TLS 已连接"
        } catch (e: AdbPairingRequiredException) {
            // 设备要求先配对（即未走「无线调试配对码」）——提示改用经典网络 ADB（弹窗/烘焙）
            null to "该设备需要先用「无线调试」配对码配对，或不支持无线调试；请改在「配置」开启 ADB 授权自动点击，或把公钥烘焙进固件后走经典网络 ADB。"
        } catch (e: Exception) {
            null to "TLS 连接异常：${e.message ?: e.javaClass.simpleName}"
        }
    }
}
