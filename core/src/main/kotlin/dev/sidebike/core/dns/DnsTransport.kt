package dev.sidebike.core.dns

import dev.sidebike.core.api.DnsProtocol
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.log.NoopSidebikeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * DNS 上游传输层（UDP / TCP / DoT / DoH）。
 *
 * 设计要点：
 * - 纯 JDK API（Android 兼容；**不用 java.net.http**）；
 * - 所有网络 IO 在 [Dispatchers.IO]，单次调用受 [withContext] 外层超时与 socket soTimeout 双重保护；
 * - 只接受 [DnsEndpoint.connectIps] 提供的字面 IP；**绝不调用 InetAddress.getByName(hostname) 解析 provider 主机名**
 *   （自举 IP 见 `api.DnsProvider.bootstrapIps`）；
 * - DoH：RFC 8484 POST `application/dns-message`，HTTP/1.1，支持 Content-Length / chunked；
 * - UDP 收到 TC（截断）标记时自动降级到 TCP。
 */
interface DnsTransport {
    /** 交换一次 DNS 报文；失败/超时/无可用 bootstrap 返回 null（由调用方回退）。 */
    suspend fun exchange(endpoint: DnsEndpoint, query: ByteArray, timeoutMs: Int): ByteArray?

    /**
     * 最近一次该 provider 的失败详情（诊断/health 用，成功后可清空）。
     * 默认实现返回 null，实现方可覆盖以暴露 SNI/握手/证书等具体错误。
     */
    fun lastError(provider: String): String? = null
}

class SocketDnsTransport(
    private val logger: SidebikeLogger = NoopSidebikeLogger,
) : DnsTransport {

    private val lastErrorByProvider = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun lastError(provider: String): String? = lastErrorByProvider[provider]

    override suspend fun exchange(endpoint: DnsEndpoint, query: ByteArray, timeoutMs: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!endpoint.usable) {
                val message = "无可用 bootstrapIps（server 非字面 IP），禁止使用系统 DNS 自举"
                lastErrorByProvider[endpoint.name] = message
                logger.warn(TAG, "provider '${endpoint.name}' $message")
                return@withContext null
            }
            val safeTimeout = timeoutMs.coerceIn(200, 30_000)
            try {
                when (endpoint.protocol) {
                    DnsProtocol.UDP -> udpExchange(endpoint, query, safeTimeout)
                    DnsProtocol.TCP -> tcpExchange(endpoint.connectIps(), endpoint.port, query, safeTimeout)
                    DnsProtocol.DOT -> dotExchange(endpoint, query, safeTimeout)
                    DnsProtocol.DOH -> dohExchange(endpoint, query, safeTimeout)
                    DnsProtocol.AUTO -> udpExchange(endpoint, query, safeTimeout)
                }?.also {
                    lastErrorByProvider.remove(endpoint.name)
                }
            } catch (e: Exception) {
                val detail = "${e.javaClass.simpleName}: ${e.message}"
                lastErrorByProvider[endpoint.name] = detail
                logger.debug(
                    TAG,
                    "provider '${endpoint.name}' 查询失败（sni=${endpoint.tlsServerName ?: "(none)"}）: $detail",
                )
                null
            }
        }

    // ------------------------------------------------------------------ UDP

    private fun udpExchange(endpoint: DnsEndpoint, query: ByteArray, timeoutMs: Int): ByteArray? {
        val ip = endpoint.connectIps().firstOrNull() ?: return null
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetSocketAddress(InetAddress.getByAddress(literalBytes(ip)), endpoint.port)
            socket.send(DatagramPacket(query, query.size, address))
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (packet.length < 12) continue
                if (buffer[0] != query[0] || buffer[1] != query[1]) continue // id 不匹配
                val response = buffer.copyOf(packet.length)
                val truncated = (response[2].toInt() and 0x02) != 0
                return if (truncated) {
                    tcpExchange(endpoint.connectIps(), endpoint.port, query, timeoutMs)
                } else {
                    response
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ TCP / DoT

    private fun tcpExchange(ips: List<String>, port: Int, query: ByteArray, timeoutMs: Int): ByteArray? {
        val ip = ips.firstOrNull() ?: return null
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(InetAddress.getByAddress(literalBytes(ip)), port), timeoutMs)
            writeLengthPrefixed(socket, query)
            return readLengthPrefixed(socket, timeoutMs)
        }
    }

    private fun dotExchange(endpoint: DnsEndpoint, query: ByteArray, timeoutMs: Int): ByteArray? {
        val ip = endpoint.connectIps().firstOrNull() ?: return null
        connectTlsWithDecoyFallback(endpoint, ip, endpoint.port, timeoutMs).use { socket ->
            writeLengthPrefixed(socket, query)
            return readLengthPrefixed(socket, timeoutMs)
        }
    }

    /**
     * 与上游 Dev-Sidecar 相同的 "SNI 诱饵" 能力：按配置的 `provider.sni` 连接 TLS；
     * 若目标边缘不接受该诱饵 SNI（handshake failure，实测 Cloudflare 系常见），自动回退到
     * 「真实主机名 SNI」重试一次，并记录 warning——而不是把整个 provider 当失败。
     */
    private fun connectTlsWithDecoyFallback(
        endpoint: DnsEndpoint,
        ip: String,
        port: Int,
        timeoutMs: Int,
    ): SSLSocket {
        try {
            return connectTls(ip, port, timeoutMs, endpoint.tlsServerName, endpoint.trustAllCerts)
        } catch (e: Exception) {
            val realServerName = endpoint.host.takeIf { !IpLiterals.isLiteral(it) }
            if (endpoint.tlsServerName == null || endpoint.tlsServerName == realServerName) {
                throw e as? Exception ?: IllegalStateException(e)
            }
            logger.warn(
                TAG,
                "provider '${endpoint.name}' 使用诱饵 SNI='${endpoint.tlsServerName}' 握手失败" +
                    "（${e.javaClass.simpleName}: ${e.message}），回退真实 SNI='${realServerName ?: "(none)"}' 重试",
            )
            return connectTls(ip, port, timeoutMs, realServerName, endpoint.trustAllCerts)
        }
    }

    private fun writeLengthPrefixed(socket: Socket, query: ByteArray) {
        val out = socket.getOutputStream()
        out.write((query.size ushr 8) and 0xFF)
        out.write(query.size and 0xFF)
        out.write(query)
        out.flush()
    }

    private fun readLengthPrefixed(socket: Socket, timeoutMs: Int): ByteArray {
        val input = BufferedInputStream(socket.getInputStream())
        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) throw EOFException("DoT/TCP 响应为空")
        val length = (high shl 8) or low
        if (length <= 0 || length > 65535) throw EOFException("非法 DNS 响应长度: $length")
        val body = ByteArray(length)
        var read = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw EOFException("DoT/TCP 响应不完整")
            read += n
            if (System.currentTimeMillis() > deadline) throw EOFException("DoT/TCP 读取超时")
        }
        return body
    }

    // ------------------------------------------------------------------ DoH

    private fun dohExchange(endpoint: DnsEndpoint, query: ByteArray, timeoutMs: Int): ByteArray? {
        val ip = endpoint.connectIps().firstOrNull() ?: return null
        val socket: Socket = if (endpoint.secure) {
            connectTlsWithDecoyFallback(endpoint, ip, endpoint.port, timeoutMs)
        } else {
            Socket().apply {
                tcpNoDelay = true
                soTimeout = timeoutMs
                connect(InetSocketAddress(InetAddress.getByAddress(literalBytes(ip)), endpoint.port), timeoutMs)
            }
        }
        socket.use {
            val hostHeader = if (endpoint.isIpHost) {
                if ((endpoint.secure && endpoint.port == 443) || (!endpoint.secure && endpoint.port == 80)) {
                    endpoint.host
                } else {
                    "${endpoint.host}:${endpoint.port}"
                }
            } else {
                if ((endpoint.secure && endpoint.port == 443) || (!endpoint.secure && endpoint.port == 80)) {
                    endpoint.host
                } else {
                    "${endpoint.host}:${endpoint.port}"
                }
            }
            val path = endpoint.path.ifEmpty { "/dns-query" }
            val header = buildString {
                append("POST ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                append("Accept: application/dns-message\r\n")
                append("Content-Type: application/dns-message\r\n")
                append("Content-Length: ").append(query.size).append("\r\n")
                append("User-Agent: Dev-Sidebike/0.1\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = socket.getOutputStream()
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(query)
            out.flush()
            return readHttpBody(BufferedInputStream(socket.getInputStream()))
        }
    }

    private fun readHttpBody(input: InputStream): ByteArray {
        val statusLine = readLine(input) ?: throw EOFException("DoH 无响应")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val body = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            readChunked(input)
        } else {
            val length = headers["content-length"]?.toIntOrNull()
            if (length != null) readN(input, length) else input.readBytes()
        }
        if (status != 200) throw EOFException("DoH 返回 HTTP $status")
        if (body.size > 65535) throw EOFException("DoH 响应过大: ${body.size}")
        return body
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw EOFException("chunked 截断")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw EOFException("非法 chunk size")
            if (size == 0) {
                readLine(input) // 结尾 CRLF
                break
            }
            out.write(readN(input, size))
            readLine(input) // chunk 后 CRLF
            if (out.size() > 65535) throw EOFException("chunked 响应过大")
        }
        return out.toByteArray()
    }

    private fun readN(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) throw EOFException("响应体截断")
            read += n
        }
        return bytes
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 8192) return null
        }
        return out.toString(Charsets.US_ASCII.name())
    }

    // ------------------------------------------------------------------ TLS

    private fun connectTls(
        ip: String,
        port: Int,
        timeoutMs: Int,
        serverName: String?,
        trustAll: Boolean,
    ): SSLSocket {
        val context = if (trustAll) trustAllContext() else SSLContext.getDefault()
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress(InetAddress.getByAddress(literalBytes(ip)), port), timeoutMs)
        val params = socket.sslParameters
        val sniName = serverName?.takeIf { !IpLiterals.isLiteral(it) }
        if (sniName != null) {
            params.serverNames = listOf(SNIHostName(sniName))
        }
        params.endpointIdentificationAlgorithm = null
        socket.sslParameters = params
        socket.startHandshake()
        if (!trustAll && serverName != null) {
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(serverName, socket.session)) {
                throw javax.net.ssl.SSLPeerUnverifiedException("TLS 证书与 $serverName 不匹配")
            }
        }
        return socket
    }

    private fun trustAllContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }

    /** 字面 IP → 4/16 字节（不触发 DNS）。 */
    private fun literalBytes(ip: String): ByteArray {
        if (ip.contains(':')) {
            return InetAddress.getByName(ip).address // IPv6 字面量：getByName 不解析 DNS
        }
        val parts = ip.split('.')
        require(parts.size == 4) { "非法 IPv4: $ip" }
        return ByteArray(4) { index -> parts[index].toInt().toByte() }
    }

    private companion object {
        const val TAG = "DnsTransport"
    }
}
