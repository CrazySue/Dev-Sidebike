package dev.sidebike.core.mitm

import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.RouteKind
import dev.sidebike.core.api.RoutePlan
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.cert.CertificateAuthority
import dev.sidebike.core.proxy.HttpProxyServer
import dev.sidebike.core.proxy.MitmTestClient
import dev.sidebike.core.proxy.RecordingLogger
import dev.sidebike.core.proxy.RawHttpClient
import dev.sidebike.core.proxy.TestCAs
import dev.sidebike.core.proxy.TestConfigs
import dev.sidebike.core.proxy.TestDnsResolver
import dev.sidebike.core.proxy.TestHttpServer
import dev.sidebike.core.proxy.TestResponse
import dev.sidebike.core.proxy.TestRuleEngine
import dev.sidebike.core.proxy.TestTlsHttpServer
import dev.sidebike.core.proxy.awaitTrue
import dev.sidebike.core.proxy.textResponse
import dev.sidebike.core.util.FakeClock
import dev.sidebike.core.util.HttpCodec
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Hermetic end-to-end MITM tests: a real TLS client trusts only the locally generated CA, a local TLS
 * server plays the upstream, and SNI matchers capture the decoy SNI the proxy sends upstream.
 *
 * No external network and no real DNS: hostnames are mapped to 127.0.0.1 through [TestDnsResolver] or the
 * CONNECT target resolves to an IP literal.
 */
class MitmEngineTest {

    private val closeables = ArrayList<Closeable>()
    private val proxies = ArrayList<HttpProxyServer>()
    private val tempDirs = ArrayList<File>()
    private val logger = RecordingLogger()
    private val clock = FakeClock()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() {
        // nothing global; each test builds its own loopback servers
    }

    @After
    fun tearDown() {
        proxies.forEach { runCatching { it.stop() } }
        closeables.reversed().forEach { runCatching { it.close() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun <T : Closeable> T.track(): T {
        closeables.add(this)
        return this
    }

    private fun intercept(jsonText: String): InterceptAction =
        json.decodeFromString(InterceptAction.serializer(), jsonText)

    private fun newTempDir(): File {
        val dir = Files.createTempDirectory("ds-mitm-test").toFile()
        tempDirs.add(dir)
        return dir
    }

    /** Starts a proxy with MITM enabled; returns proxy + its generated CA PEM (for the client trust store). */
    private fun startMitmProxy(
        ruleEngine: TestRuleEngine,
        dns: TestDnsResolver = TestDnsResolver(),
        config: SidebikeConfig = TestConfigs.baseConfig(mitmEnabled = true),
        dataDir: File = newTempDir(),
    ): Pair<HttpProxyServer, String> {
        val proxy = HttpProxyServer(dataDir, config, ruleEngine, dns, logger)
        val caPem = proxy.ensureCaPem()
        assertNotNull("CA should be generated for MITM", caPem)
        proxy.start(0)
        proxies.add(proxy)
        return proxy to caPem!!
    }

    private fun closeTlsClient(ssl: SSLSocket) {
        runCatching { ssl.close() }
    }

    // ------------------------------------------------------------------------------------------
    // end-to-end
    // ------------------------------------------------------------------------------------------

    @Test
    fun mitmE2eForwardsHttpsRequestThroughGeneratedCa() {
        val upstreamCa = TestCAs.newCa(clock)
        val upstream = TestTlsHttpServer(upstreamCa, "example.test") { request ->
            textResponse(200, "tls-ok:${request.header("x-mitm")}")
        }.track()
        val action = intercept("""{"requestReplace":{"headers":{"x-mitm":"1"}}}""")
        val ruleEngine = TestRuleEngine(planner = { host, port, _ ->
            RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port, action = action, reason = "test")
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine)

        val ssl = MitmTestClient.connect(proxy.port, "example.test", upstream.port, caPem)
        val response = MitmTestClient.request(ssl, "GET", "/hello")
        closeTlsClient(ssl)

        assertEquals(200, response.status)
        assertEquals("tls-ok:1", RawHttpClient.requestBodyText(response))
        assertEquals(1, upstream.requests.size)
        assertEquals("/hello", upstream.requests[0].target)
        assertEquals("1", upstream.requests[0].header("x-mitm"))
        // the client must have received a leaf certificate issued for the CONNECT host
        awaitTrue { proxy.trafficSnapshot().successfulConnections == 1L }
    }

    @Test
    fun mitmSelectsLeafCertificateByClientSni() {
        val upstreamCa = TestCAs.newCa(clock)
        val upstream = TestTlsHttpServer(upstreamCa, "example.test") { textResponse(200, "ok") }.track()
        val dns = TestDnsResolver()
        dns.register("example.test", "127.0.0.1")
        val action = intercept("""{"requestReplace":{"headers":{"x-mitm":"1"}}}""")
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port, action = action, reason = "test")
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine, dns)

        // CONNECT authority differs from the SNI: the leaf must be issued for the SNI (RFC 6066) name.
        val ssl = MitmTestClient.connect(
            proxyPort = proxy.port,
            targetHost = "placeholder.test",
            targetPort = upstream.port,
            caCertPem = caPem,
            sni = "example.test",
        )
        val peer = ssl.session.peerCertificates.first() as X509Certificate
        closeTlsClient(ssl)

        val subject = peer.subjectX500Principal.name
        assertTrue("leaf CN should follow the SNI name, was: $subject", subject.contains("CN=example.test"))
        assertTrue(subject.contains("CN=example.test"))
    }

    @Test
    fun mitmSendsDecoySniUpstream() {
        val upstreamCa = TestCAs.newCa(clock)
        val upstream = TestTlsHttpServer(upstreamCa, "upstream.test") { textResponse(200, "decoy-ok") }.track()
        val dns = TestDnsResolver()
        dns.register("target.test", "127.0.0.1")
        val action = intercept("""{"sni":"baidu.com"}""")
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "target.test", upstream.port, sni = "baidu.com", action = action, reason = "sni")
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine, dns)

        val ssl = MitmTestClient.connect(proxy.port, "target.test", upstream.port, caPem)
        val response = MitmTestClient.request(ssl, "GET", "/sni", headers = listOf("Host" to "target.test:${upstream.port}"))
        closeTlsClient(ssl)

        assertEquals(200, response.status)
        assertEquals("decoy-ok", RawHttpClient.requestBodyText(response))
        assertTrue(
            "upstream should observe the decoy SNI, captured=${upstream.capturedSnis}",
            upstream.capturedSnis.contains("baidu.com"),
        )
    }

    @Test
    fun mitmSyntheticSuccessResponseInsideTls() {
        val action = intercept("""{"success":{"json":"{\"ok\":true}","headers":{"x-synth":"yes"}}}""")
        val ruleEngine = TestRuleEngine(planner = { host, port, _ ->
            RoutePlan(RouteKind.SYNTHETIC, host, port, action = action, reason = "success")
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine)
        // no upstream server at all: the synthetic response must come from the interceptor
        val ssl = MitmTestClient.connect(proxy.port, "synthetic.test", 443, caPem)
        val response = MitmTestClient.request(ssl, "GET", "/x")
        closeTlsClient(ssl)

        assertEquals(200, response.status)
        assertTrue(RawHttpClient.requestBodyText(response).contains("\"ok\":true"))
        assertEquals("yes", RawHttpClient.header(response, "x-synth"))
        assertTrue(
            "content type should follow json body field",
            RawHttpClient.header(response, "Content-Type")!!.contains("application/json"),
        )
    }

    @Test
    fun mitmCacheDaysServesSecondRequestFromMemory() {
        val upstreamCa = TestCAs.newCa(clock)
        val upstream = TestTlsHttpServer(upstreamCa, "cached.test") { textResponse(200, "cache-body") }.track()
        val dns = TestDnsResolver()
        dns.register("cached.test", "127.0.0.1")
        val action = intercept("""{"cacheDays":1}""")
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "cached.test", upstream.port, action = action, reason = "cache")
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine, dns)

        val first = MitmTestClient.connect(proxy.port, "cached.test", upstream.port, caPem)
        val firstResponse = MitmTestClient.request(first, "GET", "/asset")
        closeTlsClient(first)
        assertEquals(200, firstResponse.status)
        assertEquals("cache-body", RawHttpClient.requestBodyText(firstResponse))

        val second = MitmTestClient.connect(proxy.port, "cached.test", upstream.port, caPem)
        val secondResponse = MitmTestClient.request(second, "GET", "/asset")
        closeTlsClient(second)

        assertEquals(200, secondResponse.status)
        assertEquals("cache-body", RawHttpClient.requestBodyText(secondResponse))
        assertEquals("HIT", RawHttpClient.header(secondResponse, "DS-Cache"))
        assertEquals("upstream must be hit once", 1, upstream.requests.size)
    }

    @Test
    fun mitmAppliesScriptInjectionAndResponseReplace() {
        val upstreamCa = TestCAs.newCa(clock)
        val upstream = TestTlsHttpServer(upstreamCa, "html.test") {
            textResponse(200, "<html><body>original</body></html>", "text/html; charset=utf-8")
        }.track()
        val dns = TestDnsResolver()
        dns.register("html.test", "127.0.0.1")
        val action = intercept(
            """{"script":"window.__ds=1","responseReplace":{"headers":{"x-replaced":"1","server":"[remove]"}}}""",
        )
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "html.test", upstream.port, action = action, reason = "script")
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine, dns)
        val ssl = MitmTestClient.connect(proxy.port, "html.test", upstream.port, caPem)
        val response = MitmTestClient.request(ssl, "GET", "/page")
        closeTlsClient(ssl)

        val body = RawHttpClient.requestBodyText(response)
        assertEquals(200, response.status)
        assertTrue("script not injected: $body", body.contains("<script type=\"application/javascript\">window.__ds=1</script>"))
        assertTrue(body.contains("original"))
        assertEquals("1", RawHttpClient.header(response, "x-replaced"))
        assertNull(RawHttpClient.header(response, "server"))
    }

    @Test
    fun mitmStatusOverrideInsideTls() {
        val upstreamCa = TestCAs.newCa(clock)
        val upstream = TestTlsHttpServer(upstreamCa, "status.test") { textResponse(200, "body") }.track()
        val dns = TestDnsResolver()
        dns.register("status.test", "127.0.0.1")
        val action = intercept("""{"status":418}""")
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "status.test", upstream.port, action = action, reason = "status")
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine, dns)
        val ssl = MitmTestClient.connect(proxy.port, "status.test", upstream.port, caPem)
        val response = MitmTestClient.request(ssl, "GET", "/x")
        closeTlsClient(ssl)
        assertEquals(418, response.status)
    }

    @Test
    fun mitmRejectRuleAnswers403InsideTls() {
        val action = intercept("""{"requestReplace":{"headers":{"x-engage":"1"}}}""")
        val ruleEngine = TestRuleEngine(planner = { host, port, path ->
            if (path == null) {
                RoutePlan(RouteKind.DIRECT, "127.0.0.1", 1, action = action, reason = "test")
            } else {
                RoutePlan(RouteKind.REJECT, host, port, reason = "abort")
            }
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine)
        val ssl = MitmTestClient.connect(proxy.port, "blocked.test", 443, caPem)
        val response = MitmTestClient.request(ssl, "GET", "/x")
        closeTlsClient(ssl)
        assertEquals(403, response.status)
    }

    @Test
    fun mitmDisabledTunnelsRawTlsToUpstream() {
        val upstreamCa = TestCAs.newCa(clock)
        val upstream = TestTlsHttpServer(upstreamCa, "tunnel.test") { textResponse(200, "raw-tunnel-ok") }.track()
        val dns = TestDnsResolver()
        dns.register("tunnel.test", "127.0.0.1")
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "tunnel.test", upstream.port, reason = "direct")
        })
        val dir = newTempDir()
        val proxy = HttpProxyServer(dir, TestConfigs.baseConfig(mitmEnabled = false), ruleEngine, dns, logger)
        proxy.start(0)
        proxies.add(proxy)

        // Client speaks TLS directly to the upstream certificate (no MITM: trust the upstream test CA).
        val ssl = MitmTestClient.connect(
            proxy.port, "tunnel.test", upstream.port, upstreamCa.caCertificatePem,
        )
        val response = MitmTestClient.request(ssl, "GET", "/x")
        closeTlsClient(ssl)

        assertEquals(200, response.status)
        assertEquals("raw-tunnel-ok", RawHttpClient.requestBodyText(response))
        assertTrue("upstream SNI should be the real host", upstream.capturedSnis.contains("tunnel.test"))
    }

    @Test
    fun mitmClientThatDoesNotTrustCaFailsGracefullyAndProxyKeepsWorking() {
        val upstreamCa = TestCAs.newCa(clock)
        val upstream = TestTlsHttpServer(upstreamCa, "trust.test") { textResponse(200, "ok") }.track()
        val dns = TestDnsResolver()
        dns.register("trust.test", "127.0.0.1")
        val action = intercept("""{"requestReplace":{"headers":{"x-engage":"1"}}}""")
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "trust.test", upstream.port, action = action, reason = "test")
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine, dns)

        // 1) A client trusting an unrelated CA must fail the handshake without taking the proxy down.
        val unrelatedCaPem = TestCAs.newCa(clock).caCertificatePem
        try {
            val ssl = MitmTestClient.connect(proxy.port, "trust.test", upstream.port, unrelatedCaPem)
            // Some JVMs defer certificate validation to the first read; force it.
            ssl.inputStream.read()
            closeTlsClient(ssl)
            fail("handshake should have failed for an untrusted CA")
        } catch (_: SSLHandshakeException) {
            // expected
        } catch (_: java.io.IOException) {
            // socket reset while handshaking is also acceptable
        }

        // 2) A correctly-trusting client still works afterwards.
        val ssl = MitmTestClient.connect(proxy.port, "trust.test", upstream.port, caPem)
        val response = MitmTestClient.request(ssl, "GET", "/x")
        closeTlsClient(ssl)
        assertEquals(200, response.status)
    }

    @Test
    fun mitmRequestPatternPlanIsReEvaluatedPerRequestPath() {
        val upstreamCa = TestCAs.newCa(clock)
        val upstream = TestTlsHttpServer(upstreamCa, "path.test") { textResponse(200, "matched") }.track()
        val dns = TestDnsResolver()
        dns.register("path.test", "127.0.0.1")
        val successAction = intercept("""{"success":{"text":"synthetic-for-api"}}""")
        val engage = intercept("""{"requestReplace":{"headers":{"x-engage":"1"}}}""")
        val ruleEngine = TestRuleEngine(planner = { host, port, path ->
            when {
                path == null -> RoutePlan(RouteKind.DIRECT, "path.test", upstream.port, action = engage, reason = "pending")
                path.startsWith("/api/") -> RoutePlan(RouteKind.SYNTHETIC, host, port, action = successAction, reason = "success")
                else -> RoutePlan(RouteKind.DIRECT, "path.test", upstream.port, action = engage, reason = "direct")
            }
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine, dns)

        val api = MitmTestClient.connect(proxy.port, "path.test", upstream.port, caPem)
        val apiResponse = MitmTestClient.request(api, "GET", "/api/users")
        closeTlsClient(api)
        assertEquals(200, apiResponse.status)
        assertEquals("synthetic-for-api", RawHttpClient.requestBodyText(apiResponse))

        val page = MitmTestClient.connect(proxy.port, "path.test", upstream.port, caPem)
        val pageResponse = MitmTestClient.request(page, "GET", "/index.html")
        closeTlsClient(page)
        assertEquals(200, pageResponse.status)
        assertEquals("matched", RawHttpClient.requestBodyText(pageResponse))
    }

    @Test
    fun mitmChunkedUpstreamResponseIsPassedThrough() {
        val upstreamCa = TestCAs.newCa(clock)
        val upstream = TestTlsHttpServer(upstreamCa, "chunked.test") {
            TestResponse(
                status = 200,
                headers = listOf("Content-Type" to "text/plain"),
                body = "chunked-body-payload".toByteArray(),
                chunked = true,
            )
        }.track()
        val dns = TestDnsResolver()
        dns.register("chunked.test", "127.0.0.1")
        val action = intercept("""{"requestReplace":{"headers":{"x-engage":"1"}}}""")
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port, action = action, reason = "test")
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine, dns)
        val ssl = MitmTestClient.connect(proxy.port, "chunked.test", upstream.port, caPem)
        val response = MitmTestClient.request(ssl, "GET", "/chunked")
        closeTlsClient(ssl)
        assertEquals(200, response.status)
        assertEquals("chunked-body-payload", RawHttpClient.requestBodyText(response))
    }

    @Test
    fun mitmInjectsScriptIntoGzipEncodedHtml() {
        val gzipHtml = gzipBytes("<html><body>gzipped</body></html>")
        val upstreamCa = TestCAs.newCa(clock)
        val upstream = TestTlsHttpServer(upstreamCa, "gzip.test") {
            TestResponse(
                status = 200,
                headers = listOf(
                    "Content-Type" to "text/html; charset=utf-8",
                    "Content-Encoding" to "gzip",
                ),
                body = gzipHtml,
            )
        }.track()
        val dns = TestDnsResolver()
        dns.register("gzip.test", "127.0.0.1")
        val action = intercept("""{"script":"window.__gzip=1"}""")
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "gzip.test", upstream.port, action = action, reason = "script")
        })
        val (proxy, caPem) = startMitmProxy(ruleEngine, dns)
        val ssl = MitmTestClient.connect(proxy.port, "gzip.test", upstream.port, caPem)
        val response = MitmTestClient.request(ssl, "GET", "/page")
        closeTlsClient(ssl)

        assertEquals(200, response.status)
        assertEquals("gzip", RawHttpClient.header(response, "Content-Encoding"))
        val decoded = gunzipBytes(response.body)
        val html = decoded.toString(Charsets.UTF_8)
        assertTrue("injected script missing in decompressed body: $html", html.contains("window.__gzip=1"))
        assertTrue(html.contains("gzipped"))
        assertTrue("injection must happen before </body>", html.indexOf("__gzip") < html.indexOf("</body>"))
        // Content-Length must describe the re-compressed body, not the original one.
        assertEquals(response.body.size.toString(), RawHttpClient.header(response, "Content-Length"))
    }

    private fun gzipBytes(text: String): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val gzipOut = java.util.zip.GZIPOutputStream(buffer)
        gzipOut.write(text.toByteArray(Charsets.UTF_8))
        gzipOut.close()
        return buffer.toByteArray()
    }

    private fun gunzipBytes(bytes: ByteArray): ByteArray {
        val gzipIn = java.util.zip.GZIPInputStream(bytes.inputStream())
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val read = gzipIn.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        gzipIn.close()
        return buffer.toByteArray()
    }
}
