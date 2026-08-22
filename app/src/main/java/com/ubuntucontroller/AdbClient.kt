package com.ubuntucontroller

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 纯 Kotlin 实现的极简 adb 客户端（不依赖 NDK / 外部 so）。
 *
 * 用途：在「untrusted_app 域被 SELinux 拒绝执行 app_data_file」的收紧固件上，
 * 让 App 借助设备自带的 adbd（system 域守护进程）起一个 shell 域子进程来跑 proot，
 * 从而绕开 App 自身 execve guest ELF 的 EACCES 限制。
 *
 * 连接语义：
 *  - exec() / push() 各自新建一条独立连接（connect → 操作 → close）。
 *  - startPersistent() 持有长连接（proot 常驻），stop() 时 close() 关闭 → 进程退出。
 *
 * RSA 私钥内置在 assets/adb_key.pem（固定 RSA-2048 密钥对，PKCS#8），签名按 adb
 * RSA_sign(NID_sha1, token, 20) 的 PKCS#1 v1.5 语义生成。固定公钥恒定，可一次性烘焙进固件 /data/misc/adb/adb_keys
 * 免弹窗授权（另一种做法是在固件 build.prop 设 ro.adb.secure=0 关闭认证）。
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val keyHelper: AdbKeyHelper
) : AdbTransport {

    companion object {
        private const val VERSION = 0x01000000
        private const val MAX_PAYLOAD = 256 * 1024
        private const val INITIAL_AUTH_TIMEOUT_MS = 10_000
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_PUBKEY = 3

        internal fun cmd(s: String): Int =
            (s[0].code and 0xff) or
                    ((s[1].code and 0xff) shl 8) or
                    ((s[2].code and 0xff) shl 16) or
                    ((s[3].code and 0xff) shl 24)

        private val CMD_CNXN = cmd("CNXN")
        private val CMD_Auth = cmd("AUTH")
        private val CMD_OPEN = cmd("OPEN")
        private val CMD_OKAY = cmd("OKAY")
        private val CMD_CLSE = cmd("CLSE")
        private val CMD_WRTE = cmd("WRTE")

        private const val PUSH_DONE_MARKER = "__UC_PUSH_DONE__"

        private val CRC_TABLE = IntArray(256) { i ->
            var c = i
            repeat(8) {
                c = if (c and 1 != 0) (0xedb88320.toInt() ushr 0) xor (c ushr 1)
                else (c ushr 1)
            }
            c
        }

        internal fun crc32(data: ByteArray): Int {
            var crc = 0xffffffff.toInt()
            for (b in data) {
                crc = CRC_TABLE[(crc xor (b.toInt() and 0xff)) and 0xff] xor (crc ushr 8)
            }
            return crc
        }

        private fun hex(v: Int): String = "0x%08x".format(v)
    }

    private var socket: Socket? = null
    private var din: DataInputStream? = null
    private var dout: DataOutputStream? = null
    private val localId: Int = 0x100

    /**
     * 连接并完成 CNXN/AUTH 握手。
     * - persistent=true：授权后恢复阻塞读（常驻连接读线程不退出）。
     * - 兼容两种固件：
     *   1) adb 认证开启（ro.adb.secure=1）：收到 AUTH_TOKEN 立即回 AUTH_SIGNATURE，
     *      并主动补发 AUTH_PUBKEY 让 adbd 存下本 App 公钥（只认 OpenSSH `ssh-rsa` 格式），
     *      用户点「允许 USB 调试」后 adbd 回 CNXN。
     *   2) adb 认证关闭（ro.adb.secure=0，多数 TV 盒子固件，故「另一软件免弹窗直连」）：
     *      adbd 不发 AUTH_TOKEN、也不回第二个 CNXN。握手阶段用 2s 窗口侦测，
     *      超时未收到 AUTH_TOKEN 即判定免认证，直接视为已连接（这是此前「卡在连接中」的根因）。
     */
    fun connect(persistent: Boolean = false) {
        val s = Socket(host, port)
        // 握手阶段短超时：用于侦测设备是否发 AUTH_TOKEN（区分认证开/关）。
        // 真机 TV 盒子的 adbd 有时比模拟器慢，窗口太短会错过 AUTH_TOKEN，导致授权弹窗无法触发。
        s.soTimeout = INITIAL_AUTH_TIMEOUT_MS
        socket = s
        din = DataInputStream(s.getInputStream())
        dout = DataOutputStream(s.getOutputStream())

        val banner = "host::adbmini\u0000".toByteArray(Charsets.UTF_8)
        writeMessage(CMD_CNXN, VERSION, MAX_PAYLOAD, banner)

        // -- 阶段一：侦测认证模式。未收到 AUTH_TOKEN 即免认证，直接就绪。
        val first = try {
            readMessage()
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
        if (first == null) {
            if (persistent) s.soTimeout = 0
            return
        }

        // -- 阶段二：需要鉴权（auth-on），阻塞等待用户授权。
        s.soTimeout = if (persistent) 0 else 20_000
        var authed = false
        var sentSignature = false
        var m: AdbMessage? = first
        var rounds = 0
        while (!authed && rounds < 200) {
            rounds++
            when (m?.command) {
                null -> throw AdbException("握手中断（连接关闭）")
                CMD_Auth -> when (m.arg0) {
                    AUTH_TOKEN -> {
                        if (!sentSignature) {
                            writeMessage(CMD_Auth, AUTH_SIGNATURE, 0, keyHelper.sign(m.data))
                            sentSignature = true
                        } else {
                            // 第二次 token 说明签名还未被信任，发送 ADB 公钥触发系统授权框。
                            writeMessage(CMD_Auth, AUTH_PUBKEY, 0, keyHelper.pubkeyBytes())
                        }
                    }
                    AUTH_PUBKEY -> writeMessage(CMD_Auth, AUTH_PUBKEY, 0, keyHelper.pubkeyBytes())
                    else -> throw AdbException("AUTH 未知子类型: ${m.arg0}")
                }
                CMD_CNXN -> authed = true
                CMD_CLSE -> throw AdbException("握手被设备关闭（AUTH 失败，可能密钥未授权）")
                else -> throw AdbException("握手收到意外消息: ${hex(m.command)}")
            }
            if (!authed) m = readMessage() ?: throw AdbException("握手中断（连接关闭）")
        }
        if (!authed) throw AdbException("AUTH 握手未完成（设备未回 CNXN，请在盒子上点『允许 USB 调试』后重试）")
    }

    /** 中断正在进行的连接（关闭 socket，使阻塞的读线程抛异常退出）。 */
    override fun cancel() {
        try { socket?.close() } catch (_: Exception) {}
    }

    /** 实现 AdbTransport：无参连接（默认非持久，匹配接口签名）。 */
    override fun connect() {
        connect(false)
    }

    /** 一次性 shell 命令，返回 stdout+stderr 文本。 */
    override fun exec(command: String): String {
        val stream = openStream()
        writeMessage(CMD_OPEN, localId, 0, "shell:$command".toByteArray(Charsets.UTF_8))
        return readUntilClose(stream, background = false).first
    }

    /** 推送本地文件到设备远程路径（shell:cat> 流式写 stdin）。带 adb 窗口流控。 */
    override fun push(local: File, remote: String, mode: String): Boolean {
        val stream = openStream()
        writeMessage(
            CMD_OPEN, localId, 0,
            "shell:cat - > '$remote' && chmod $mode '$remote'; echo $PUSH_DONE_MARKER"
                .toByteArray(Charsets.UTF_8)
        )
        var remoteId = 0
        var window = MAX_PAYLOAD
        // 读取直到拿到 OPEN 的 OKAY（remoteId）
        while (remoteId == 0) {
            val m = readMessage() ?: throw AdbException("push 等待 OKAY 时连接关闭")
            when (m.command) {
                CMD_OKAY -> remoteId = m.arg1
                CMD_WRTE -> writeMessage(CMD_OKAY, localId, m.arg1, ByteArray(0))
                CMD_CLSE -> throw AdbException("push 未写文件即被关闭")
                else -> throw AdbException("push 收到意外消息: ${hex(m.command)}")
            }
        }
        stream.remoteId = remoteId
        val buf = ByteArray(64 * 1024)
        local.inputStream().use { ins ->
            var read: Int
            while (ins.read(buf).also { read = it } != -1) {
                // 窗口流控：窗口不足时读取 OKAY 补充（WRTE 仅回 ACK，不补充窗口）
                while (window < read) {
                    val m = readMessage() ?: throw AdbException("push 写期间连接关闭")
                    when (m.command) {
                        CMD_OKAY -> window += MAX_PAYLOAD
                        CMD_WRTE -> writeMessage(CMD_OKAY, localId, m.arg1, ByteArray(0))
                        CMD_CLSE -> throw AdbException("push 写期间被关闭")
                        else -> throw AdbException("push 写期间意外消息: ${hex(m.command)}")
                    }
                }
                stream.write(buf.copyOf(read))
                window -= read
            }
        }
        stream.close() // 关闭 stdin → cat 结束 → 设备回显 MARKER 后 CLSE
        val (out, _) = readUntilClose(stream, background = false)
        return out.contains(PUSH_DONE_MARKER)
    }

    /** 持久启动 shell 命令（proot 常驻），保持连接；返回 true 表示已启动。 */
    override fun startPersistent(command: String, logLine: (String) -> Unit): Boolean {
        val stream = openStream()
        stream.logLine = logLine
        writeMessage(CMD_OPEN, localId, 0, "shell:$command".toByteArray(Charsets.UTF_8))
        // 后台读取设备输出（proot 日志），不阻塞调用方
        val reader = Thread {
            try {
                readUntilClose(stream, background = true)
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()
        stream.readerThread = reader
        return true
    }

    private fun openStream(): AdbStream = AdbStream(localId, dout ?: throw AdbException("未连接"))

    /** 读取流直到设备 CLSE。background=true 时仅记录日志、不聚合返回值。 */
    private fun readUntilClose(stream: AdbStream, background: Boolean): Pair<String, Unit> {
        val out = StringBuilder()
        var closed = false
        val deadline = if (background) Long.MAX_VALUE else System.currentTimeMillis() + 90_000
        while (!closed) {
            if (!background && System.currentTimeMillis() > deadline) break
            val m = readMessage() ?: break
            when (m.command) {
                CMD_OKAY -> stream.remoteId = m.arg1
                CMD_WRTE -> {
                    stream.remoteId = m.arg1
                    val text = String(m.data, Charsets.UTF_8)
                    if (background) stream.logLine(text) else out.append(text)
                    writeMessage(CMD_OKAY, stream.localId, stream.remoteId, ByteArray(0))
                }
                CMD_CLSE -> {
                    closed = true
                    writeMessage(CMD_CLSE, stream.localId, stream.remoteId, ByteArray(0))
                }
                CMD_CNXN -> { /* 忽略 */ }
                else -> throw AdbException("流中收到意外消息: ${hex(m.command)}")
            }
        }
        return Pair(out.toString(), Unit)
    }

    private fun readMessage(): AdbMessage? {
        val dis = din ?: return null
        val header = ByteArray(24)
        var off = 0
        while (off < 24) {
            val n = dis.read(header, off, 24 - off)
            if (n < 0) return null
            off += n
        }
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        val dataCrc = bb.int
        val magic = bb.int
        if (magic != (command xor 0xffffffff.toInt())) throw AdbException("消息 magic 校验失败")
        val data = if (len > 0) {
            val raw = ByteArray(len)
            var o = 0
            while (o < len) {
                val n = dis.read(raw, o, len - o)
                if (n < 0) break
                o += n
            }
            val pad = (4 - (len and 3)) and 3
            if (pad > 0) dis.skipFully(pad)
            if (dataCrc != 0 && crc32(raw) != dataCrc) throw AdbException("消息 CRC32 校验失败")
            raw
        } else ByteArray(0)
        return AdbMessage(command, arg0, arg1, data)
    }

    private fun DataInputStream.skipFully(n: Int) {
        var left = n
        while (left > 0) {
            val s = skip(left.toLong())
            if (s <= 0) {
                val b = ByteArray(1)
                if (read(b) < 0) break
                left -= 1
            } else left -= s.toInt()
        }
    }

    private fun writeMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val dout = dout ?: throw AdbException("未连接")
        val len = data.size
        val pad = (4 - (len and 3)) and 3
        val payload = if (pad > 0) data + ByteArray(pad) else data
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(len)
        header.putInt(crc32(data))
        header.putInt(command xor 0xffffffff.toInt())
        synchronized(dout) {
            dout.write(header.array())
            if (payload.isNotEmpty()) dout.write(payload)
            dout.flush()
        }
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    data class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    class AdbException(msg: String) : Exception(msg)
}

/**
 * 持有打开的 adb 流，可写入（WRTE）并被 close()（CLSE）关闭。
 */
class AdbStream(
    val localId: Int,
    private val dout: DataOutputStream,
    internal var logLine: (String) -> Unit = {}
) {
    var remoteId: Int = 0
    var readerThread: Thread? = null

    fun write(data: ByteArray) {
        val len = data.size
        val pad = (4 - (len and 3)) and 3
        val payload = if (pad > 0) data + ByteArray(pad) else data
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(AdbClient.cmd("WRTE"))
        header.putInt(localId)
        header.putInt(remoteId)
        header.putInt(len)
        header.putInt(AdbClient.crc32(data))
        header.putInt(AdbClient.cmd("WRTE") xor 0xffffffff.toInt())
        synchronized(dout) {
            dout.write(header.array())
            if (payload.isNotEmpty()) dout.write(payload)
            dout.flush()
        }
    }

    fun close() {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(AdbClient.cmd("CLSE"))
        header.putInt(localId)
        header.putInt(remoteId)
        header.putInt(0)
        header.putInt(0)
        header.putInt(AdbClient.cmd("CLSE") xor 0xffffffff.toInt())
        synchronized(dout) {
            try { dout.write(header.array()); dout.flush() } catch (_: Exception) {}
        }
    }
}

/**
 * adb RSA 密钥助手：使用 App 内置的**固定** RSA-2048 密钥对（assets/adb_key.pem，PKCS#8）。
 *
 * 为什么是固定密钥？
 *  - TV 盒子固件通常 ro.adb.secure=1，只认 /data/misc/adb/adb_keys 里的白名单公钥；
 *    系统「允许 USB 调试」弹窗在 Launcher 前台固件上不显示，无法交互授权。
 *  - 把固定公钥烘焙进固件一次，App 即可免弹窗直连（另一种做法：固件 build.prop 设 ro.adb.secure=0）。
 *  - 固定密钥保证「一次烘焙、永久有效」，不受重装影响（AndroidKeyStore 每装随机一把做不到这点）。
 *
 * 签名按 adb RSA_sign(NID_sha1, token, 20) 的 PKCS#1 v1.5 语义生成。
 */
class AdbKeyHelper(private val context: android.content.Context) {
    private val comment = "tvubuntu"

    private var cachedPrivate: java.security.PrivateKey? = null
    private var cachedPublic: java.security.interfaces.RSAPublicKey? = null
    /** 最后一次加载失败的原因（供 UI 显示，避免「日志为空」无从排查）。 */
    var lastError: String? = null
        private set

    /** 加载内置固定密钥对（私钥+证书，来自 assets，经 AdbKeyStore 统一管理）。失败返回 false，原因见 [lastError]。 */
    fun ensureKey(): Boolean {
        lastError = null
        if (cachedPrivate != null && cachedPublic != null) return true
        return if (AdbKeyStore.load(context)) {
            cachedPrivate = AdbKeyStore.getPrivateKey(context)
            cachedPublic = AdbKeyStore.getPublicKey(context)
            true
        } else {
            lastError = AdbKeyStore.lastError
            false
        }
    }

    fun sign(token: ByteArray): ByteArray {
        if (!ensureKey()) throw AdbClient.AdbException("adb 私钥未加载（assets/adb_key.pem 缺失？）")
        if (token.size != 20) throw AdbClient.AdbException("adb AUTH token 长度异常: ${token.size}")

        // ADB host uses RSA_sign(NID_sha1, token, 20), where token is already
        // the 20-byte challenge. SHA1withRSA would hash it again.
        val sha1DigestInfoPrefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val digestInfo = sha1DigestInfoPrefix + token

        return try {
            val sig = java.security.Signature.getInstance("NONEwithRSA")
            sig.initSign(cachedPrivate)
            sig.update(digestInfo)
            sig.sign()
        } catch (_: Exception) {
            val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, cachedPrivate)
            cipher.doFinal(digestInfo)
        }
    }

    fun pubkeyBytes(): ByteArray {
        if (!ensureKey()) throw AdbClient.AdbException("adb 公钥未加载（assets/adb_key.pem 缺失？）")
        return exportPubkeyText()
            .toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    }

    /** 导出 adb 公钥文本，用于触发系统授权框或烘焙进 /data/misc/adb/adb_keys。 */
    fun exportPubkeyText(): String {
        if (!ensureKey()) throw AdbClient.AdbException("adb 公钥未加载（assets/adb_key.pem 缺失？）")
        return buildAdbPublicKeyStatic(cachedPublic!!)
    }

    companion object {
        /** 由 RSA 公钥按 adb 格式构造可烘焙进固件 adb_keys 的文本（与证书公钥同源）。 */
        fun buildAdbPublicKeyStatic(pub: java.security.interfaces.RSAPublicKey): String {
            val modulus = pub.modulus
            val exponent = pub.publicExponent.toLong()
            val words = modulus.bitLength().let { (it + 31) / 32 }
            val bytes = words * 4
            val r32 = java.math.BigInteger.ONE.shiftLeft(32)
            val n0 = modulus.mod(r32)
            val n0inv = r32.subtract(n0.modInverse(r32)).and(r32.subtract(java.math.BigInteger.ONE)).toLong()
            val rr = java.math.BigInteger.ONE.shiftLeft(bytes * 8).mod(modulus)
                .let { it.multiply(it).mod(modulus) }

            val payload = ByteBuffer.allocate(4 + 4 + bytes + bytes + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            payload.putInt(words)
            payload.putInt(n0inv.toInt())
            payload.put(fixedLittleEndian(modulus, bytes))
            payload.put(fixedLittleEndian(rr, bytes))
            payload.putInt(exponent.toInt())

            val b64 = android.util.Base64.encodeToString(payload.array(), android.util.Base64.NO_WRAP)
            return "$b64 tvubuntu"
        }

        private fun fixedLittleEndian(value: java.math.BigInteger, size: Int): ByteArray {
            val big = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            val out = ByteArray(size)
            for (i in big.indices) {
                if (i >= size) break
                out[i] = big[big.size - 1 - i]
            }
            return out
        }
    }
}
