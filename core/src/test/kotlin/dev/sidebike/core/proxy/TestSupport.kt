package dev.sidebike.core.proxy

import dev.sidebike.core.api.DnsAnswer
import dev.sidebike.core.api.DnsResolver
import dev.sidebike.core.api.DnsStats
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.RouteKind
import dev.sidebike.core.api.RoutePlan
import dev.sidebike.core.api.RuleEngine
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.cert.CertificateAuthority
import dev.sidebike.core.log.NoopSidebikeLogger
import dev.sidebike.core.util.Clock
import dev.sidebike.core.util.HttpCodec
import dev.sidebike.core.util.HttpHeaders
import dev.sidebike.core.util.IoSupport
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIMatcher
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.StandardConstants

/**
 * Hermetic JVM test harness for the proxy/MITM workstream. Everything binds to 127.0.0.1 on ephemeral
 * ports; no external network or real DNS is ever touched.
 */

data class TestRequest(
    val method: String,
    val target: String,
    val headers: HttpHeaders,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers.get(name)
}

data class TestResponse(
    val status: Int,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val chunked: Boolean = false,
    val raw: ByteArray? = null,
)

fun textResponse(status: Int = 200, body: String = "ok", contentType: String = "text/plain; charset=utf-8"): TestResponse =
    TestResponse(
        status = status,
        headers = listOf("Content-Type" to contentType),
        body = body.toByteArray(Charsets.UTF_8),
    )

fun httpDate(epochMs: Long): String {
    val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
    return fmt.format(java.util.Date(epochMs))
}

/** Minimal local upstream HTTP/1.1 server: one request per connection, `Connection: close`. */
class TestHttpServer(
    private val responseFactory: (TestRequest) -> TestResponse = { textResponse() },
) : Closeable {

    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "test-http").apply { isDaemon = true }
    }
    private val acceptor = Thread({ acceptLoop() }, "test-http-accept").apply { isDaemon = true }

    val requests = CopyOnWriteArrayList<TestRequest>()
    val connectionCount = AtomicInteger(0)
    val port: Int = server.localPort

    init {
        acceptor.start()
    }

    fun url(path: String): String = "http://127.0.0.1:$port$path"

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            connectionCount.incrementAndGet()
            executor.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 5000
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val request = HttpCodec.readRequestHead(input) ?: return
            val bodyStream = HttpCodec.requestBodyStream(request, input)
            val body = IoSupport.readAllLimited(bodyStream, 4L * 1024 * 1024)
            val recorded = TestRequest(request.method, request.target, request.headers, body)
            requests.add(recorded)
            val response = try {
                responseFactory(recorded)
            } catch (e: Exception) {
                TestResponse(500, emptyList(), "handler error: ${e.message}".toByteArray())
            }
            writeResponse(socket, response)
        } catch (_: IOException) {
            // client went away
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
                // ignore
            }
        }
    }

    private fun writeResponse(socket: Socket, response: TestResponse) {
        val out = socket.getOutputStream()
        if (response.raw != null) {
            out.write(response.raw)
            out.flush()
            return
        }
        val headers = HttpHeaders()
        for ((name, value) in response.headers) headers.add(name, value)
        headers.set("Connection", "close")
        if (response.chunked) {
            headers.set("Transfer-Encoding", "chunked")
        } else {
            headers.set("Content-Length", response.body.size.toString())
        }
        HttpCodec.writeResponseHead(out, response.status, "", headers)
        if (response.chunked) {
            HttpCodec.writeChunkedBody(response.body.inputStream(), out)
        } else {
            out.write(response.body)
        }
        out.flush()
    }

    override fun close() {
        running.set(false)
        try {
            server.close()
        } catch (_: IOException) {
            // ignore
        }
        acceptor.interrupt()
        executor.shutdownNow()
    }
}

/** Echo server used to prove CONNECT tunnels relay bytes transparently (incl. half-close). */
class TestEchoServer : Closeable {
    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "test-echo").apply { isDaemon = true }
    }
    private val acceptor = Thread({ acceptLoop() }, "test-echo-accept").apply { isDaemon = true }

    val received = java.io.ByteArrayOutputStream()
    val port: Int = server.localPort

    init {
        acceptor.start()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            executor.execute {
                try {
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val out = socket.getOutputStream()
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        synchronized(received) { received.write(buf, 0, n) }
                        out.write(buf, 0, n)
                        out.flush()
                    }
                    try {
                        socket.shutdownOutput()
                    } catch (_: IOException) {
                        // ignore
                    }
                } catch (_: IOException) {
                    // ignore
                } finally {
                    try {
                        socket.close()
                    } catch (_: IOException) {
                        // ignore
                    }
                }
            }
        }
    }

    override fun close() {
        running.set(false)
        try {
            server.close()
        } catch (_: IOException) {
            // ignore
        }
        acceptor.interrupt()
        executor.shutdownNow()
    }
}

/** Local TLS upstream server with SNI capture (used to prove decoy-SNI rewriting). */
class TestTlsHttpServer(
    ca: CertificateAuthority,
    host: String = "example.test",
    private val responseFactory: (TestRequest) -> TestResponse = { textResponse(body = "tls-ok") },
) : Closeable {

    private val keyCert = ca.leaf(host)
    private val server: SSLServerSocket
    private val running = AtomicBoolean(true)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "test-tls").apply { isDaemon = true }
    }
    private val acceptor: Thread

    val capturedSnis = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<TestRequest>()
    val port: Int

    init {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            "server",
            keyCert.keyPair.private,
            charArrayOf(),
            arrayOf(keyCert.certificate, ca.caCertificate),
        )
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, charArrayOf())
        val context = SSLContext.getInstance("TLS")
        context.init(kmf.keyManagers, null, null)
        server = context.serverSocketFactory.createServerSocket(0, 64, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server.soTimeout = 5000
        port = server.localPort
        val params = server.sslParameters
        params.sniMatchers = listOf(
            object : SNIMatcher(StandardConstants.SNI_HOST_NAME) {
                override fun matches(serverName: SNIServerName): Boolean {
                    val name = (serverName as? SNIHostName)?.asciiName
                        ?: serverName.encoded.toString(Charsets.US_ASCII)
                    capturedSnis.add(name)
                    return true
                }
            },
        )
        server.sslParameters = params
        acceptor = Thread({ acceptLoop() }, "test-tls-accept").apply { isDaemon = true }
        acceptor.start()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                server.accept() as SSLSocket
            } catch (_: IOException) {
                break
            }
            executor.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()
            val request = HttpCodec.readRequestHead(input) ?: return
            val body = IoSupport.readAllLimited(HttpCodec.requestBodyStream(request, input), 4L * 1024 * 1024)
            val recorded = TestRequest(request.method, request.target, request.headers, body)
            requests.add(recorded)
            val response = responseFactory(recorded)
            val out = socket.getOutputStream()
            val headers = HttpHeaders()
            for ((name, value) in response.headers) headers.add(name, value)
            headers.set("Connection", "close")
            headers.set("Content-Length", response.body.size.toString())
            HttpCodec.writeResponseHead(out, response.status, "", headers)
            out.write(response.body)
            out.flush()
        } catch (_: IOException) {
            // ignore
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
                // ignore
            }
        }
    }

    override fun close() {
        running.set(false)
        try {
            server.close()
        } catch (_: IOException) {
            // ignore
        }
        acceptor.interrupt()
        executor.shutdownNow()
    }
}

/** Rule engine stub: callers fully control the plan; everything else is a harmless default. */
class TestRuleEngine(
    private val planner: (String, Int, String?) -> RoutePlan = { host, port, _ -> RoutePlan(RouteKind.DIRECT, host, port) },
    private val whitelist: Set<String> = emptySet(),
    private val presets: Map<String, List<String>> = emptyMap(),
    private val actionMatcher: ((String, String?) -> InterceptAction?)? = null,
) : RuleEngine {

    override fun updateConfig(config: SidebikeConfig) = Unit
    override fun match(host: String, pathAndQuery: String?): InterceptAction? = actionMatcher?.invoke(host, pathAndQuery)
    override fun plan(host: String, port: Int, pathAndQuery: String?): RoutePlan = planner(host, port, pathAndQuery)
    override fun isWhitelisted(host: String): Boolean = whitelist.contains(host)
    override fun isExcluded(host: String): Boolean = false
    override fun isDomestic(host: String): Boolean = false
    override fun presetIps(host: String): List<String> = presets[host] ?: emptyList()
    override fun domainMatches(pattern: String, host: String): Boolean = pattern == host
}

/** DNS stub: IP-literal dial targets never call it; named hosts resolve through [answers]. */
class TestDnsResolver(
    private val answers: MutableMap<String, List<String>> = mutableMapOf(),
    private val clock: Clock = Clock.SYSTEM,
) : DnsResolver {
    val resolveCalls = CopyOnWriteArrayList<String>()
    private val cache = mutableMapOf<String, DnsAnswer>()

    fun register(host: String, vararg ips: String) {
        answers[host] = ips.toList()
    }

    override suspend fun resolve(host: String): DnsAnswer {
        resolveCalls.add(host)
        cache[host]?.let { return it.copy(fromCache = true) }
        val ips = answers[host] ?: emptyList()
        val answer = DnsAnswer(host, ips, "test", false, 60, 1)
        if (ips.isNotEmpty()) cache[host] = answer
        return answer
    }

    override suspend fun resolveRawQuery(query: ByteArray): ByteArray? = null
    override fun clearCache() = cache.clear()
    override fun stats(): DnsStats = DnsStats(queries = resolveCalls.size.toLong(), cacheEntries = cache.size)
}

/** Raw (non-TLS) client helpers. */
object RawHttpClient {

    fun connect(proxyPort: Int): Socket {
        val socket = Socket("127.0.0.1", proxyPort)
        socket.soTimeout = 8000
        socket.tcpNoDelay = true
        return socket
    }

    /** Sends an absolute-form request through the proxy and reads the response (connection: close). */
    fun request(
        proxyPort: Int,
        method: String,
        url: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0),
    ): TestResponse = connect(proxyPort).use { socket ->
        val out = socket.getOutputStream()
        val host = url.substringAfter("://").substringBefore('/')
        val sb = StringBuilder()
        sb.append("$method $url HTTP/1.1\r\n")
        sb.append("Host: $host\r\n")
        sb.append("Connection: close\r\n")
        for ((name, value) in headers) sb.append("$name: $value\r\n")
        if (body.isNotEmpty()) sb.append("Content-Length: ${body.size}\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
        readResponse(socket.getInputStream())
    }

    /** Sends a pre-built raw request (used for malformed-request tests). */
    fun raw(proxyPort: Int, rawRequest: String): String = connect(proxyPort).use { socket ->
        socket.getOutputStream().write(rawRequest.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        val input = socket.getInputStream()
        IoSupport.readAllLimited(input, 256L * 1024).toString(Charsets.UTF_8)
    }

    fun readResponse(input: InputStream): TestResponse {
        val head = HttpCodec.readResponseHead(input)
        val body = HttpCodec.responseBodyStream("GET", head, input)?.let { IoSupport.readAllLimited(it, 16L * 1024 * 1024) }
            ?: ByteArray(0)
        return TestResponse(head.status, head.headers.entries(), body)
    }

    fun requestBodyText(response: TestResponse): String = response.body.toString(Charsets.UTF_8)

    fun header(response: TestResponse, name: String): String? =
        response.headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

/** TLS client helpers for MITM end-to-end tests (client trusts only the generated local CA). */
object MitmTestClient {

    fun trustContext(caCertPem: String): SSLContext {
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        val caCert = certFactory.generateCertificate(caCertPem.byteInputStream())
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry("local-ca", caCert)
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    /**
     * Opens a CONNECT tunnel through the proxy and wraps it in TLS, exactly like a browser would.
     * The proxy's `200 Connection Established` response is parsed without reading ahead so no TLS byte is
     * consumed by the HTTP parser.
     */
    fun connect(
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        caCertPem: String,
        sni: String = targetHost,
        trustCertificate: Boolean = true,
    ): SSLSocket {
        val raw = RawHttpClient.connect(proxyPort)
        raw.getOutputStream().write(
            "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1),
        )
        raw.getOutputStream().flush()
        val connectResponse = HttpCodec.readResponseHead(raw.getInputStream())
        check(connectResponse.status == 200) {
            "CONNECT failed: ${connectResponse.status} ${connectResponse.reason}"
        }
        val context = if (trustCertificate) trustContext(caCertPem) else trustContext(caCertPem)
        val ssl = context.socketFactory.createSocket(raw, targetHost, targetPort, true) as SSLSocket
        ssl.useClientMode = true
        ssl.soTimeout = 8000
        val params = ssl.sslParameters
        try {
            params.serverNames = listOf(SNIHostName(sni))
        } catch (_: Throwable) {
            // SNI is already derived from the host argument
        }
        ssl.sslParameters = params
        ssl.startHandshake()
        return ssl
    }

    fun request(ssl: SSLSocket, method: String, path: String, headers: List<Pair<String, String>> = emptyList()): TestResponse {
        val out = ssl.outputStream
        val sb = StringBuilder()
        sb.append("$method $path HTTP/1.1\r\n")
        for ((name, value) in headers) sb.append("$name: $value\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        return RawHttpClient.readResponse(ssl.inputStream)
    }
}

/** Named logger that records messages, useful to assert warnings in tests. */
class RecordingLogger : SidebikeLogger {
    val messages = CopyOnWriteArrayList<Pair<String, String>>()

    override fun log(level: dev.sidebike.core.api.LogLevel, tag: String, message: String, throwable: Throwable?) {
        messages.add(level.name to "$tag: $message")
    }

    override fun history(): List<dev.sidebike.core.api.LogEntry> = emptyList()
    override fun clear() = messages.clear()
    override fun subscribe(listener: (dev.sidebike.core.api.LogEntry) -> Unit): AutoCloseable = AutoCloseable { }
}

fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

object TestConfigs {
    fun baseConfig(port: Int = 0, mitmEnabled: Boolean = false, maxConnections: Int = 64): SidebikeConfig =
        SidebikeConfig(
            proxy = dev.sidebike.core.api.ProxySettings(
                port = port,
                mitmEnabled = mitmEnabled,
                connectTimeoutMs = 3000,
                readTimeoutMs = 5000,
                idleTimeoutMs = 5000,
                maxConnections = maxConnections,
            ),
            mitm = dev.sidebike.core.api.MitmSettings(enabled = mitmEnabled),
        )
}

object TestCAs {
    val DEFAULT_LOGGER: SidebikeLogger = NoopSidebikeLogger

    fun newCa(clock: Clock = Clock.SYSTEM): CertificateAuthority =
        CertificateAuthority.generate(clock, DEFAULT_LOGGER)
}
