package dev.sidebike.core.dns

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

/** 构造一条上游 DNS 应答（A/AAAA 记录），用于 fake transport / 本地测试服务器。 */
fun responseFor(query: ByteArray, ips: List<String>, ttl: Int = 60): ByteArray {
    val parsed = DnsCodec.parseOrThrow(query)
    return DnsCodec.buildReply(
        parsed,
        ips.map { ip -> IpAnswer(ip, ttl, if (ip.contains(':')) 6 else 4) },
        rcode = 0,
        maxUdpPayload = 4096,
    )
}

fun emptyResponseFor(query: ByteArray): ByteArray {
    val parsed = DnsCodec.parseOrThrow(query)
    return DnsCodec.buildReply(parsed, emptyList(), rcode = 0, maxUdpPayload = 4096)
}

fun servFailResponseFor(query: ByteArray): ByteArray {
    val parsed = DnsCodec.parseOrThrow(query)
    return DnsCodec.buildReply(parsed, emptyList(), rcode = 2, maxUdpPayload = 4096)
}

/**
 * 可编程的 fake DNS transport：按 provider 名返回预置应答/延迟/失败，记录调用。
 * 用于 resolver 单测（完全 hermetic，不发任何网络包）。
 */
class FakeDnsTransport : DnsTransport {

    data class Call(val endpoint: String, val query: ByteArray)

    val calls = CopyOnWriteArrayList<Call>()
    private val handlers = ConcurrentHashMap<String, (ByteArray) -> ByteArray?>()
    private val delaysMs = ConcurrentHashMap<String, Long>()

    fun onQuery(provider: String, handler: (ByteArray) -> ByteArray?) {
        handlers[provider] = handler
    }

    fun respond(provider: String, ips: List<String>, ttl: Int = 60) {
        handlers[provider] = { query -> responseFor(query, ips, ttl) }
    }

    fun respondEmpty(provider: String) {
        handlers[provider] = { query -> emptyResponseFor(query) }
    }

    fun delay(provider: String, ms: Long) {
        delaysMs[provider] = ms
    }

    fun callsFor(provider: String): List<Call> = calls.filter { it.endpoint == provider }

    override suspend fun exchange(endpoint: DnsEndpoint, query: ByteArray, timeoutMs: Int): ByteArray? {
        calls.add(Call(endpoint.name, query))
        delaysMs[endpoint.name]?.let { if (it > 0) delay(it) }
        return handlers[endpoint.name]?.invoke(query)
    }
}

/** 本地 UDP DNS 服务器（回环随机端口），用于 transport 集成测试。 */
class LocalUdpDnsServer(private val handler: (ByteArray) -> ByteArray?) : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val worker = thread(isDaemon = true) {
        val buffer = ByteArray(4096)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val query = buffer.copyOf(packet.length)
                val response = handler(query) ?: continue
                socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
            } catch (_: Exception) {
                break
            }
        }
    }

    val port: Int get() = socket.localPort

    override fun close() {
        runCatching { socket.close() }
        runCatching { worker.join(500) }
    }
}

/** 本地 TCP DNS 服务器（2 字节长度前缀），用于 transport 集成测试。 */
class LocalTcpDnsServer(private val handler: (ByteArray) -> ByteArray?) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val worker = thread(isDaemon = true) {
        while (!server.isClosed) {
            try {
                server.accept().use { socket -> handle(socket, handler) }
            } catch (_: Exception) {
                break
            }
        }
    }

    val port: Int get() = server.localPort

    private fun handle(socket: Socket, handler: (ByteArray) -> ByteArray?) {
        val input = BufferedInputStream(socket.getInputStream())
        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) return
        val length = (high shl 8) or low
        val query = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(query, read, length - read)
            if (n < 0) return
            read += n
        }
        val response = handler(query) ?: return
        val out = socket.getOutputStream()
        out.write((response.size ushr 8) and 0xFF)
        out.write(response.size and 0xFF)
        out.write(response)
        out.flush()
    }

    override fun close() {
        runCatching { server.close() }
        runCatching { worker.join(500) }
    }
}

/** 本地 HTTP DoH 服务器（http://，无 TLS），用于验证 DoH 请求/响应编码。 */
class LocalHttpDohServer(private val handler: (ByteArray) -> ByteArray?) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val worker = thread(isDaemon = true) {
        while (!server.isClosed) {
            try {
                server.accept().use { socket -> handle(socket) }
            } catch (_: Exception) {
                break
            }
        }
    }

    val port: Int get() = server.localPort

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val headers = LinkedHashMap<String, String>()
        var requestLine = ""
        var line = readLine(input)
        if (line != null) requestLine = line
        while (true) {
            line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val response = handler(body) ?: return
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/dns-message\r\n")
            append("Content-Length: ").append(response.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(response)
        out.flush()
        // 记录 requestLine 供测试断言（可选）
        lastRequestLine = requestLine
    }

    var lastRequestLine: String = ""
        private set

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.US_ASCII.name())
    }

    override fun close() {
        runCatching { server.close() }
        runCatching { worker.join(500) }
    }
}

/** 本地纯 HTTP 服务器（回环），用于“拒绝系统 DNS/超时”等测试。 */
class SilentUdpServer : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val worker = thread(isDaemon = true) {
        val buffer = ByteArray(4096)
        while (!socket.isClosed) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
                // 故意不回包，用于超时测试
            } catch (_: Exception) {
                break
            }
        }
    }

    val port: Int get() = socket.localPort

    override fun close() {
        runCatching { socket.close() }
        runCatching { worker.join(500) }
    }
}
