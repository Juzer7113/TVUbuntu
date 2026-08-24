package com.ubuntucontroller

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无线调试（Android 11+）免弹窗配对编排。
 *
 * 流程：
 *  1) discoverPairing()  —— mDNS 发现 _adb-tls-pairing（设备「无线调试」里点「使用配对码配对设备」后出现）
 *  2) pair()             —— 用 6 位配对码静默授权本机公钥（写入设备 adb_keys，无弹窗）
 *  3) connectTls()       —— 发现 _adb-tls-connect 并建 TLS 长连接，返回可复用的 AdbWirelessTransport
 *
 * 三个函数均为阻塞式（网络 IO），调用方需自行放到后台线程。
 *
 * 关键坑（v1.5.3 修复，ECONNREFUSED 根因）：
 *  本 App 要配对的 adbd 就在本机。Android 的 NsdManager 对「本机自己广播的 _adb-tls-* 服务」
 *  经常解析出回环地址 127.0.0.1，而 adbd 的 TLS 配对/连接服务器实际监听在 Wi-Fi 网卡 IP 上
 *  （不在回环）。直接拿 127.0.0.1 去 pair()/connect() 必然 ECONNREFUSED。
 *  因此 [resolveHost] 对回环/任意地址一律替换为本机非回环 IPv4。
 *
 *  connectTls() 同样不再依赖库内 connectTls()（其内部 mDNS 对本机服务也会解析出 127.0.0.1），
 *  改为自己 discover（带回环替换）后走 mgr.connect(host, port)——libadb 对 _adb-tls-connect
 *  端口的连接由 daemon 侧发 A_STLS 自动升级 TLS，无需显式 TLS 入口。
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
                // 本机 adbd：NsdManager 常把本地服务解析成 127.0.0.1，而 TLS 服务监听在 Wi-Fi IP，
                // 回环/任意地址一律替换为本机非回环 IPv4，否则 pair()/connect() 必 ECONNREFUSED
                onFound(Discovered(resolveHost(address?.hostAddress ?: ""), port))
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
            val target = resolveHost(host)   // 兜底：手动填 127.0.0.1 同样替换成本机 Wi-Fi IP
            val mgr = UbuntuAdbManager(context)
            mgr.setHostAddress(target)
            mgr.setApi(Build.VERSION.SDK_INT)
            val ok = mgr.pair(port, code)
            if (ok) true to "配对成功，设备已授权本机公钥" else false to "配对失败（未知原因）"
        } catch (e: Exception) {
            false to "配对异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 配对后经 TLS 连接端点建连（自动发现 _adb-tls-connect），返回可复用的传输。
     *
     * 不用库内 connectTls()：其内部 mDNS 对本机服务同样会解析出 127.0.0.1。
     * 这里自己 discover（回环→本机 Wi-Fi IP）后走 mgr.connect(host, port)。
     */
    fun connectTls(context: Context, timeoutMs: Long = 8000): Pair<AdbWirelessTransport?, String> {
        var discovered: Discovered? = null
        val latch = CountDownLatch(1)
        discoverConnect(context, timeoutMs) { d ->
            discovered = d
            latch.countDown()
        }
        val waited = try {
            latch.await(timeoutMs + 1000L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        val d = discovered
        if (!waited || d == null) return null to "TLS 连接失败（未发现 _adb-tls-connect 服务）"
        return try {
            val mgr = UbuntuAdbManager(context)
            mgr.setApi(Build.VERSION.SDK_INT)
            mgr.setTimeout(30_000L, TimeUnit.MILLISECONDS)
            val ok = mgr.connect(d.host, d.port)
            if (!ok) return null to "TLS 连接失败（${d.host}:${d.port}）"
            val conn = mgr.adbConnection ?: return null to "TLS 连接为空"
            AdbWirelessTransport(conn) to "TLS 已连接"
        } catch (e: AdbPairingRequiredException) {
            // 设备要求先配对（即未走「无线调试配对码」）——提示改用经典网络 ADB（弹窗/烘焙）
            null to "该设备需要先用「无线调试」配对码配对，或不支持无线调试；请改在「配置」开启 ADB 授权自动点击，或把公钥烘焙进固件后走经典网络 ADB。"
        } catch (e: Exception) {
            null to "TLS 连接异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 把回环/任意地址替换为本机非回环 IPv4（mDNS 对本机服务常解析出 127.0.0.1）。
     * 远程设备地址（非回环）原样保留。
     */
    private fun resolveHost(host: String): String {
        val h = host.trim()
        if (h.isEmpty()) return localIpAddress()
        val ip = h.substringBefore('%')   // 去掉 IPv6 zone index
        return when (ip) {
            "127.0.0.1", "::1", "0.0.0.0", "::" -> localIpAddress()
            else -> h
        }
    }

    /** 本机非回环 IPv4（与 AdbProotService 的 SSH 地址同源逻辑）。 */
    private fun localIpAddress(): String {
        try {
            val nis = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addrs = ni.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }
}
