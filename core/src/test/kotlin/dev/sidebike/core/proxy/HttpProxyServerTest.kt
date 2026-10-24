package dev.sidebike.core.proxy

import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.ProxyResult
import dev.sidebike.core.api.RouteKind
import dev.sidebike.core.api.RoutePlan
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.util.HttpCodec
import dev.sidebike.core.util.HttpHeaders
import java.io.Closeable
import java.io.File
import java.net.ConnectException
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for [HttpProxyServer]: plain HTTP forwarding, CONNECT tunnels, rule routing
 * (DIRECT / MIRROR_PROXY / REJECT / SYNTHETIC), interceptor actions, timeouts, malformed requests,
 * concurrency and clean shutdown. All servers are local loopback sockets - no external network/DNS.
 */
class HttpProxyServerTest {

    private val closeables = ArrayList<Closeable>()
    private val proxies = ArrayList<HttpProxyServer>()
    private val tempDirs = ArrayList<File>()
    private val logger = RecordingLogger()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @After
    fun tearDown() {
        proxies.forEach { runCatching { it.stop() } }
        closeables.reversed().forEach { runCatching { it.close() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private fun newProxy(
        ruleEngine: TestRuleEngine,
        config: SidebikeConfig = TestConfigs.baseConfig(),
        dns: TestDnsResolver = TestDnsResolver(),
    ): HttpProxyServer {
        val dir = Files.createTempDirectory("ds-proxy-test").toFile()
        tempDirs.add(dir)
        val proxy = HttpProxyServer(dir, config, ruleEngine, dns, logger)
        proxy.start(0)
        proxies.add(proxy)
        return proxy
    }

    private fun <T : Closeable> T.track(): T {
        closeables.add(this)
        return this
    }

    private fun intercept(jsonText: String): InterceptAction =
        json.decodeFromString(InterceptAction.serializer(), jsonText)

    /** Result/listener callbacks fire on the proxy worker thread after the response: poll in tests. */
    private fun awaitCondition(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    private fun planAction(host: String, port: Int, action: InterceptAction, kind: RouteKind = RouteKind.DIRECT): RoutePlan =
        RoutePlan(kind, host, port, sni = action.sni, action = action, reason = "test")

    // ------------------------------------------------------------------------------------------
    // plain HTTP
    // ------------------------------------------------------------------------------------------

    @Test
    fun plainHttpGetIsForwardedAndCounted() {
        val upstream = TestHttpServer { request -> textResponse(200, "hello-${request.method}") }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ -> RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port) })
        val proxy = newProxy(ruleEngine)
        val results = CopyOnWriteArrayList<ProxyResult>()
        proxy.setResultListener { _, result -> results.add(result) }

        val response = RawHttpClient.request(proxy.port, "GET", "http://example.test:${upstream.port}/hello?a=1")

        assertEquals(200, response.status)
        assertEquals("hello-GET", RawHttpClient.requestBodyText(response))
        assertEquals(1, upstream.requests.size)
        assertEquals("/hello?a=1", upstream.requests[0].target)
        assertEquals("example.test:${upstream.port}", upstream.requests[0].header("Host"))
        awaitCondition { results.size == 1 }
        assertTrue(results[0].success)
        assertTrue(results[0].bytesUp > 0)
        assertTrue(results[0].bytesDown > 0)
        val stats = proxy.trafficSnapshot()
        assertEquals(1, stats.totalConnections)
        assertEquals(1, stats.successfulConnections)
        assertTrue(stats.bytesUp > 0)
        assertTrue(stats.bytesDown > 0)
    }

    @Test
    fun plainHttpPostBodyIsForwarded() {
        val upstream = TestHttpServer { request -> textResponse(200, "received:${request.body.size}") }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ -> RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port) })
        val proxy = newProxy(ruleEngine)
        val body = "payload=1&x=2".toByteArray()

        val response = RawHttpClient.request(
            proxy.port, "POST", "http://example.test:${upstream.port}/submit", body = body,
        )

        assertEquals(200, response.status)
        assertEquals("received:${body.size}", RawHttpClient.requestBodyText(response))
        assertArrayEquals(body, upstream.requests[0].body)
        assertEquals("POST", upstream.requests[0].method)
    }

    @Test
    fun plainHttpRejectRuleReturns403() {
        val ruleEngine = TestRuleEngine(planner = { host, port, _ ->
            RoutePlan(RouteKind.REJECT, host, port, reason = "abort rule")
        })
        val proxy = newProxy(ruleEngine)
        val response = RawHttpClient.request(proxy.port, "GET", "http://blocked.test/x")
        assertEquals(403, response.status)
    }

    @Test
    fun plainHttpSyntheticSuccessResponse() {
        val action = intercept("""{"success":true}""")
        val ruleEngine = TestRuleEngine(planner = { host, port, _ -> planAction(host, port, action, RouteKind.SYNTHETIC) })
        val proxy = newProxy(ruleEngine)

        val response = RawHttpClient.request(proxy.port, "GET", "http://collector.test/collect")

        assertEquals(200, response.status)
        assertTrue(RawHttpClient.requestBodyText(response).contains("success"))
        assertEquals("success", RawHttpClient.header(response, "DS-Interceptor"))
    }

    @Test
    fun plainHttpSyntheticStatusOverride() {
        // mirrors upstream config `collector.github.com { success: true, status: 204 }`
        val action = intercept("""{"success":true,"status":204}""")
        val ruleEngine = TestRuleEngine(planner = { host, port, _ -> planAction(host, port, action, RouteKind.SYNTHETIC) })
        val proxy = newProxy(ruleEngine)
        val response = RawHttpClient.request(proxy.port, "GET", "http://collector.test/github/collect")
        assertEquals(204, response.status)
        assertEquals(0, response.body.size)
    }

    @Test
    fun plainHttpRequestReplaceHeaders() {
        val action = intercept(
            """{"requestReplace":{"headers":{"accept-language":"en-US,en;q=0.8","x-remove-me":"[remove]"}}}""",
        )
        val upstream = TestHttpServer { textResponse(200, "ok") }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port, action = action, reason = "test")
        })
        val proxy = newProxy(ruleEngine)

        val response = RawHttpClient.request(
            proxy.port, "GET", "http://example.test:${upstream.port}/x",
            headers = listOf("x-remove-me" to "please"),
        )

        assertEquals(200, response.status)
        assertEquals("en-US,en;q=0.8", upstream.requests[0].header("accept-language"))
        assertNull(upstream.requests[0].header("x-remove-me"))
    }

    @Test
    fun plainHttpResponseReplaceHeadersAndBody() {
        val action = intercept(
            """{"responseReplace":{"headers":{"x-rewritten":"yes","server":"[remove]"},"text":"replaced-body"}}""",
        )
        val upstream = TestHttpServer {
            TestResponse(
                status = 200,
                headers = listOf("Server" to "test-server", "Content-Type" to "text/plain; charset=utf-8"),
                body = "original-body".toByteArray(),
            )
        }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port, action = action, reason = "test")
        })
        val proxy = newProxy(ruleEngine)

        val response = RawHttpClient.request(proxy.port, "GET", "http://example.test:${upstream.port}/x")

        assertEquals(200, response.status)
        assertEquals("replaced-body", RawHttpClient.requestBodyText(response))
        assertEquals("yes", RawHttpClient.header(response, "x-rewritten"))
        assertNull(RawHttpClient.header(response, "server"))
    }

    @Test
    fun plainHttpStatusOverrideOnUpstreamResponse() {
        val action = intercept("""{"status":201}""")
        val upstream = TestHttpServer { textResponse(200, "created") }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port, action = action, reason = "test")
        })
        val proxy = newProxy(ruleEngine)
        val response = RawHttpClient.request(proxy.port, "GET", "http://example.test:${upstream.port}/x")
        assertEquals(201, response.status)
    }

    @Test
    fun plainHttpScriptInjectionIntoHtml() {
        val action = intercept("""{"script":"console.log('injected')"}""")
        val upstream = TestHttpServer {
            textResponse(200, "<html><head><title>t</title></head><body>hi</body></html>", "text/html; charset=utf-8")
        }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port, action = action, reason = "test")
        })
        val proxy = newProxy(ruleEngine)

        val response = RawHttpClient.request(proxy.port, "GET", "http://example.test:${upstream.port}/page")
        val body = RawHttpClient.requestBodyText(response)

        assertEquals(200, response.status)
        assertTrue("injected script missing: $body", body.contains("<script type=\"application/javascript\">console.log('injected')</script>"))
        assertTrue("injection must be before </body>: $body", body.indexOf("injected") < body.indexOf("</body>"))
    }

    @Test
    fun plainHttpScriptInjectionExternalUrl() {
        val action = intercept("""{"script":"https://cdn.example.com/app.js"}""")
        val upstream = TestHttpServer {
            textResponse(200, "<html><body>hi</body></html>", "text/html; charset=utf-8")
        }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port, action = action, reason = "test")
        })
        val proxy = newProxy(ruleEngine)
        val body = RawHttpClient.requestBodyText(
            RawHttpClient.request(proxy.port, "GET", "http://example.test:${upstream.port}/page"),
        )
        assertTrue(body.contains("src=\"https://cdn.example.com/app.js\""))
        assertTrue(body.contains("</script>"))
    }

    @Test
    fun plainHttpCacheDaysServesFromMemoryAndHonours304() {
        val action = intercept("""{"cacheDays":1}""")
        val upstream = TestHttpServer { textResponse(200, "cached-payload") }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port, action = action, reason = "test")
        })
        val proxy = newProxy(ruleEngine)

        val first = RawHttpClient.request(proxy.port, "GET", "http://example.test:${upstream.port}/asset")
        assertEquals(200, first.status)
        assertEquals("cached-payload", RawHttpClient.requestBodyText(first))
        assertNotNull(RawHttpClient.header(first, "Cache-Control"))
        assertTrue(RawHttpClient.header(first, "Cache-Control")!!.contains("max-age"))

        val second = RawHttpClient.request(proxy.port, "GET", "http://example.test:${upstream.port}/asset")
        assertEquals(200, second.status)
        assertEquals("cached-payload", RawHttpClient.requestBodyText(second))
        assertEquals("HIT", RawHttpClient.header(second, "DS-Cache"))
        assertEquals("upstream must only be hit once", 1, upstream.requests.size)

        val lastModified = RawHttpClient.header(first, "Last-Modified")
        assertNotNull(lastModified)
        val third = RawHttpClient.request(
            proxy.port, "GET", "http://example.test:${upstream.port}/asset",
            headers = listOf("If-Modified-Since" to lastModified!!),
        )
        assertEquals(304, third.status)
        assertEquals(0, third.body.size)
        assertEquals(1, upstream.requests.size)
    }

    @Test
    fun plainHttpCacheControlNoStoreBypassesCache() {
        val action = intercept("""{"cacheDays":1}""")
        val upstream = TestHttpServer { textResponse(200, "fresh") }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ ->
            RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port, action = action, reason = "test")
        })
        val proxy = newProxy(ruleEngine)
        RawHttpClient.request(proxy.port, "GET", "http://example.test:${upstream.port}/x")
        RawHttpClient.request(
            proxy.port, "GET", "http://example.test:${upstream.port}/x",
            headers = listOf("Cache-Control" to "no-store"),
        )
        assertEquals(2, upstream.requests.size)
    }

    @Test
    fun mirrorProxyConnectsToAlternateHostKeepingOriginalHostHeader() {
        val mirror = TestHttpServer { textResponse(200, "from-mirror") }.track()
        val ruleEngine = TestRuleEngine(planner = { host, port, _ ->
            RoutePlan(RouteKind.MIRROR_PROXY, "127.0.0.1", mirror.port, action = intercept("""{"proxy":"127.0.0.1"}"""), reason = "proxy")
        })
        val proxy = newProxy(ruleEngine)
        val response = RawHttpClient.request(proxy.port, "GET", "http://fonts.example.test/recaptcha/x")
        assertEquals(200, response.status)
        assertEquals("from-mirror", RawHttpClient.requestBodyText(response))
        assertEquals(1, mirror.requests.size)
        assertEquals("/recaptcha/x", mirror.requests[0].target)
        assertEquals("fonts.example.test", mirror.requests[0].header("Host"))
    }

    @Test
    fun upstreamConnectFailureReturns502() {
        val deadPort = freePort()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ -> RoutePlan(RouteKind.DIRECT, "127.0.0.1", deadPort) })
        val proxy = newProxy(ruleEngine)
        val response = RawHttpClient.request(proxy.port, "GET", "http://example.test/x")
        assertEquals(502, response.status)
        assertTrue(RawHttpClient.requestBodyText(response).contains("DevSidecar"))
    }

    // ------------------------------------------------------------------------------------------
    // CONNECT tunnels
    // ------------------------------------------------------------------------------------------

    @Test
    fun connectTunnelRelaysBytesBothDirections() {
        val echo = TestEchoServer().track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ -> RoutePlan(RouteKind.DIRECT, "127.0.0.1", echo.port) })
        val proxy = newProxy(ruleEngine)
        val results = CopyOnWriteArrayList<ProxyResult>()
        proxy.setResultListener { _, result -> results.add(result) }

        RawHttpClient.connect(proxy.port).use { socket ->
            socket.getOutputStream().write(
                "CONNECT example.test:443 HTTP/1.1\r\nHost: example.test:443\r\n\r\n".toByteArray(),
            )
            socket.getOutputStream().flush()
            val connectResponse = HttpCodec.readResponseHead(socket.getInputStream())
            assertEquals(200, connectResponse.status)

            val payload = "hello-through-tunnel".toByteArray()
            socket.getOutputStream().write(payload)
            socket.getOutputStream().flush()
            socket.shutdownOutput()

            val echoed = socket.getInputStream().readNBytes(payload.size)
            assertArrayEquals(payload, echoed)
        }

        assertTrue(echo.received.size() >= 20)
        awaitCondition { results.size == 1 }
        assertTrue(results[0].success)
        assertTrue("bytesUp should count tunnelled bytes", results[0].bytesUp >= 20)
    }

    @Test
    fun connectWithSniActionAndMitmDisabledFallsBackToDirectTunnel() {
        val echo = TestEchoServer().track()
        val action = intercept("""{"sni":"baidu.com"}""")
        val ruleEngine = TestRuleEngine(
            planner = { _, _, _ -> RoutePlan(RouteKind.DIRECT, "127.0.0.1", echo.port, sni = "baidu.com", action = action, reason = "sni") },
        )
        val proxy = newProxy(ruleEngine, config = TestConfigs.baseConfig(mitmEnabled = false))

        RawHttpClient.connect(proxy.port).use { socket ->
            socket.getOutputStream().write("CONNECT example.test:443 HTTP/1.1\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            assertEquals(200, HttpCodec.readResponseHead(socket.getInputStream()).status)
            socket.getOutputStream().write("abc".toByteArray())
            socket.getOutputStream().flush()
            assertArrayEquals("abc".toByteArray(), socket.getInputStream().readNBytes(3))
        }
        assertTrue("expected a warning about sni being ignored", logger.messages.any { (_, text) -> text.contains("sni") })
    }

    @Test
    fun connectRejectRuleReturns403WithoutTunnel() {
        val ruleEngine = TestRuleEngine(planner = { host, port, _ ->
            RoutePlan(RouteKind.REJECT, host, port, reason = "abort")
        })
        val proxy = newProxy(ruleEngine)
        RawHttpClient.connect(proxy.port).use { socket ->
            socket.getOutputStream().write("CONNECT blocked.test:443 HTTP/1.1\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            assertEquals(403, HttpCodec.readResponseHead(socket.getInputStream()).status)
        }
    }

    @Test
    fun connectToClosedPortReturnsProxyConnectError() {
        val deadPort = freePort()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ -> RoutePlan(RouteKind.DIRECT, "127.0.0.1", deadPort) })
        val proxy = newProxy(ruleEngine)
        RawHttpClient.connect(proxy.port).use { socket ->
            socket.getOutputStream().write("CONNECT example.test:443 HTTP/1.1\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            val status = HttpCodec.readResponseHead(socket.getInputStream()).status
            assertTrue("unexpected status $status", status == 400 || status == 408 || status == 403)
        }
    }

    @Test
    fun connectSyntheticRuleWithMitmDisabledIsRejectedWith403() {
        val action = intercept("""{"success":true}""")
        val ruleEngine = TestRuleEngine(planner = { host, port, _ ->
            RoutePlan(RouteKind.SYNTHETIC, host, port, action = action, reason = "success")
        })
        val proxy = newProxy(ruleEngine, config = TestConfigs.baseConfig(mitmEnabled = false))
        RawHttpClient.connect(proxy.port).use { socket ->
            socket.getOutputStream().write("CONNECT synthetic.test:443 HTTP/1.1\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            assertEquals(403, HttpCodec.readResponseHead(socket.getInputStream()).status)
        }
    }

    // ------------------------------------------------------------------------------------------
    // protocol robustness / lifecycle
    // ------------------------------------------------------------------------------------------

    @Test
    fun malformedRequestLineReturns400() {
        val proxy = newProxy(TestRuleEngine())
        val raw = RawHttpClient.raw(proxy.port, "NOT-A-REQUEST\r\n\r\n")
        assertTrue("expected 400, got: ${raw.take(120)}", raw.contains("400"))
    }

    @Test
    fun originFormPlainRequestIsRejectedWith400() {
        val proxy = newProxy(TestRuleEngine())
        val raw = RawHttpClient.raw(proxy.port, "GET /relative HTTP/1.1\r\nHost: example.test\r\n\r\n")
        assertTrue("expected 400, got: ${raw.take(120)}", raw.contains("400"))
    }

    @Test
    fun oversizedHeaderBlockReturns400() {
        val proxy = newProxy(TestRuleEngine())
        val bigHeaderValue = "a".repeat(15 * 1024)
        val request = buildString {
            append("GET http://example.test/ HTTP/1.1\r\n")
            append("Host: example.test\r\n")
            repeat(6) { append("X-Big-$it: $bigHeaderValue\r\n") }
            append("\r\n")
        }
        val result = AtomicReference<String>()
        val writer = Thread {
            runCatching {
                RawHttpClient.connect(proxy.port).use { socket ->
                    socket.getOutputStream().write(request.toByteArray())
                    socket.getOutputStream().flush()
                    result.set(HttpCodec.readResponseHead(socket.getInputStream()).status.toString())
                }
            }
        }
        writer.isDaemon = true
        writer.start()
        writer.join(5000)
        assertEquals("400", result.get())
    }

    @Test
    fun connectionLimitReturns503ForExcessConnection() {
        val echo = TestEchoServer().track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ -> RoutePlan(RouteKind.DIRECT, "127.0.0.1", echo.port) })
        val proxy = newProxy(ruleEngine, config = TestConfigs.baseConfig(maxConnections = 1))

        // Hold the only allowed connection open.
        val first = RawHttpClient.connect(proxy.port)
        try {
            first.getOutputStream().write("CONNECT example.test:443 HTTP/1.1\r\n\r\n".toByteArray())
            first.getOutputStream().flush()
            assertEquals(200, HttpCodec.readResponseHead(first.getInputStream()).status)

            val second = RawHttpClient.request(proxy.port, "GET", "http://example.test/x")
            assertEquals(503, second.status)
        } finally {
            runCatching { first.close() }
        }
    }

    @Test
    fun concurrentRequestsAllSucceed() {
        val upstream = TestHttpServer { textResponse(200, "ok") }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ -> RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port) })
        val proxy = newProxy(ruleEngine)
        val clients = 20
        val latch = CountDownLatch(clients)
        val failures = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(clients)
        repeat(clients) { index ->
            executor.execute {
                try {
                    val response = RawHttpClient.request(proxy.port, "GET", "http://example.test/c$index")
                    if (response.status != 200) failures.add("client $index -> ${response.status}")
                } catch (e: Exception) {
                    failures.add("client $index: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue("clients did not finish", latch.await(30, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertTrue("failures: $failures", failures.isEmpty())
        assertEquals(clients, upstream.requests.size)
        assertEquals(clients.toLong(), proxy.trafficSnapshot().totalConnections)
        assertEquals(0, proxy.trafficSnapshot().activeConnections)
    }

    @Test
    fun stopIsIdempotentAndServerCanRestart() {
        val upstream = TestHttpServer { textResponse(200, "ok") }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ -> RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port) })
        val proxy = newProxy(ruleEngine)
        val port = proxy.port
        assertTrue(proxy.isRunning)

        proxy.stop()
        proxy.stop()
        assertFalse(proxy.isRunning)
        var refused = false
        try {
            Socket("127.0.0.1", port).use { }
        } catch (_: ConnectException) {
            refused = true
        }
        assertTrue("connect to a stopped proxy must be refused", refused)

        proxy.start(0)
        assertTrue(proxy.isRunning)
        val response = RawHttpClient.request(proxy.port, "GET", "http://example.test/x")
        assertEquals(200, response.status)
    }

    @Test
    fun requestListenerReceivesHostAndBytes() {
        val upstream = TestHttpServer { textResponse(200, "12345") }.track()
        val ruleEngine = TestRuleEngine(planner = { _, _, _ -> RoutePlan(RouteKind.DIRECT, "127.0.0.1", upstream.port) })
        val proxy = newProxy(ruleEngine)
        val receivedHost = AtomicReference<String>()
        proxy.setResultListener { host, _ -> receivedHost.set(host) }
        RawHttpClient.request(proxy.port, "GET", "http://callback.test:${upstream.port}/x")
        awaitCondition { receivedHost.get() != null }
        assertEquals("callback.test", receivedHost.get())
    }
}
