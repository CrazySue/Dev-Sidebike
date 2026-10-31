package dev.sidebike.core.rules

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sidebike.core.api.RuleSource
import dev.sidebike.core.api.RuleSourceFormat
import dev.sidebike.core.api.RuleSourceManagerFactory
import dev.sidebike.core.proxy.RecordingLogger
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for [DefaultRuleSourceManager] against a local `com.sun.net.httpserver.HttpServer`
 * (JVM tests only): 200/304/error/redirect/oversized responses, conditional headers, UA, format
 * auto-detection, disabled sources, refreshAll isolation and factory installation.
 */
class RuleSourceManagerTest {

    private val logger = RecordingLogger()
    private val servers = ArrayList<HttpServer>()
    private val requests = CopyOnWriteArrayList<Map<String, String>>()

    @After
    fun tearDown() {
        servers.forEach { it.stop(0) }
        servers.clear()
        requests.clear()
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.firstOrNull().orEmpty() }
            requests.add(headers)
            try {
                handler(exchange)
            } finally {
                try {
                    exchange.close()
                } catch (_: Throwable) {
                    // ignore
                }
            }
        }
        server.executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "test-rule-source").apply { isDaemon = true }
        }
        server.start()
        servers.add(server)
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        for ((name, value) in headers) exchange.responseHeaders.add(name, value)
        if (status == 304) {
            exchange.sendResponseHeaders(304, -1)
            return
        }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.responseBody.flush()
    }

    private fun manager(): DefaultRuleSourceManager = DefaultRuleSourceManager(
        logger = logger,
        connectTimeoutMs = 3000,
        readTimeoutMs = 5000,
        maxBodyBytes = 5 * 1024 * 1024,
    )

    private fun source(url: String, format: RuleSourceFormat = RuleSourceFormat.AUTO, enabled: Boolean = true) =
        RuleSource(id = "test", name = "测试规则源", url = url, format = format, enabled = enabled)

    private val jsonBody = """
        {"intercepts":{"ads.example.com":{".*":{"abort":true}}},"whiteList":{"internal.example.com":true}}
    """.trimIndent()

    // ------------------------------------------------------------------------------------------
    // refresh + conditional requests
    // ------------------------------------------------------------------------------------------

    @Test
    fun refresh200ThenConditional304ThenForcedRefresh() = runBlocking {
        val base = startServer { exchange ->
            val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
            if (ifNoneMatch == "\"v1\"") {
                respond(exchange, 304, ByteArray(0))
            } else {
                respond(
                    exchange,
                    200,
                    jsonBody.toByteArray(),
                    mapOf("ETag" to "\"v1\"", "Last-Modified" to "Wed, 21 Oct 2026 07:28:00 GMT", "Content-Type" to "application/json"),
                )
            }
        }
        val mgr = manager()

        val first = mgr.refresh(source("$base/rules.json"))
        assertNull(first.error)
        assertFalse(first.notModified)
        assertNotNull(first.imported)
        assertEquals(2, first.imported!!.ruleCount)
        assertEquals("\"v1\"", first.source.etag)
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", first.source.lastModified)
        assertTrue(first.source.lastImported > 0)
        assertEquals("Dev-Sidebike", requests[0]["user-agent"]!!.substringBefore('/'))

        val second = mgr.refresh(first.source)
        assertTrue("304 must map to notModified=true", second.notModified)
        assertNull(second.imported)
        assertNull(second.error)
        assertEquals("\"v1\"", requests[1]["if-none-match"])

        val forced = mgr.refresh(first.source, force = true)
        assertFalse(forced.notModified)
        assertNotNull(forced.imported)
        assertNull("force must not send If-None-Match", requests[2]["if-none-match"])
    }

    @Test
    fun httpErrorReturnsErrorAndNeverThrows() = runBlocking {
        val base = startServer { exchange -> respond(exchange, 500, "boom".toByteArray()) }
        val result = manager().refresh(source("$base/rules.json"))

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("500"))
        assertNull(result.imported)
        assertFalse(result.notModified)
        assertNotNull(result.source.lastError)
    }

    @Test
    fun notFoundReturnsError() = runBlocking {
        val base = startServer { exchange -> respond(exchange, 404, "nope".toByteArray()) }
        val result = manager().refresh(source("$base/missing.list"))
        assertTrue(result.error!!.contains("404"))
        assertNull(result.imported)
    }

    @Test
    fun redirectIsFollowed() = runBlocking {
        val base = startServer { exchange ->
            when (exchange.requestURI.path) {
                "/old.json" -> respond(exchange, 302, ByteArray(0), mapOf("Location" to "/new.json"))
                "/new.json" -> respond(exchange, 200, jsonBody.toByteArray(), mapOf("Content-Type" to "application/json"))
                else -> respond(exchange, 404, ByteArray(0))
            }
        }
        val result = manager().refresh(source("$base/old.json"))
        assertNull(result.error)
        assertNotNull(result.imported)
        assertEquals(2, requests.size)
    }

    @Test
    fun oversizedBodyIsRejectedByDeclaredLength() = runBlocking {
        val base = startServer { exchange ->
            val body = ByteArray(6 * 1024 * 1024)
            respond(exchange, 200, body, mapOf("Content-Type" to "text/plain"))
        }
        val result = manager().refresh(source("$base/big.list"))
        assertNotNull(result.error)
        assertTrue("expected size limit error, was: ${result.error}", result.error!!.contains("上限") || result.error!!.contains("过大"))
        assertNull(result.imported)
    }

    @Test
    fun disabledSourceIsSkippedWithoutNetwork() = runBlocking {
        val base = startServer { exchange -> respond(exchange, 200, jsonBody.toByteArray()) }
        val result = manager().refresh(source("$base/rules.json", enabled = false))
        assertNull(result.error)
        assertNull(result.imported)
        assertFalse(result.notModified)
        assertEquals("disabled sources must not hit the network", 0, requests.size)
    }

    @Test
    fun invalidUrlsFailCleanly() = runBlocking {
        val ftp = manager().refresh(source("ftp://example.com/rules.json"))
        assertNotNull(ftp.error)
        assertNull(ftp.imported)

        val garbage = manager().refresh(source("not a url at all"))
        assertNotNull(garbage.error)
        assertNull(garbage.imported)
    }

    // ------------------------------------------------------------------------------------------
    // formats through the network path
    // ------------------------------------------------------------------------------------------

    @Test
    fun shadowrocketConfIsParsedFromNetwork() = runBlocking {
        val conf = """
            [General]
            skip-proxy = 127.0.0.1
            [Rule]
            DOMAIN-SUFFIX,example.com,DIRECT
            DOMAIN-SUFFIX,ads.example.com,REJECT
            IP-CIDR,10.0.0.0/8,DIRECT
            [Host]
            0.0.0.0 ignored.example.com
        """.trimIndent()
        val base = startServer { exchange -> respond(exchange, 200, conf.toByteArray(), mapOf("Content-Type" to "text/plain; charset=utf-8")) }
        val result = manager().refresh(source("$base/sr_cnip.conf"))
        assertNull(result.error)
        val imported = result.imported!!
        assertTrue(imported.directPassDomains.contains("*.example.com"))
        assertEquals(true, imported.intercepts["*.ads.example.com"]?.get(".*")?.abort)
        assertFalse(imported.directPassDomains.contains("ignored.example.com"))
        assertTrue(imported.warnings.isNotEmpty())
    }

    @Test
    fun autoFormatSniffsContentWhenUrlHasNoSuffix() = runBlocking {
        val base = startServer { exchange ->
            respond(exchange, 200, "DOMAIN-SUFFIX,auto.example.com,DIRECT".toByteArray())
        }
        val result = manager().refresh(source("$base/download"))
        assertNull(result.error)
        assertTrue(result.imported!!.directPassDomains.contains("*.auto.example.com"))
    }

    @Test
    fun utf8BomContentIsAccepted() = runBlocking {
        val body = "\uFEFFdomain-one.example.com\ndomain-two.example.com\n".toByteArray(Charsets.UTF_8)
        val base = startServer { exchange -> respond(exchange, 200, body) }
        val result = manager().refresh(source("$base/list.txt", RuleSourceFormat.DOMAIN_LIST))
        assertNull(result.error)
        assertEquals(2, result.imported!!.directPassDomains.size)
        assertTrue(result.imported!!.directPassDomains.contains("domain-one.example.com"))
    }

    @Test
    fun refreshAllIsolatesPerSourceFailures() = runBlocking {
        val base = startServer { exchange ->
            when (exchange.requestURI.path) {
                "/good.json" -> respond(exchange, 200, jsonBody.toByteArray())
                "/bad.json" -> respond(exchange, 500, "boom".toByteArray())
                else -> respond(exchange, 404, ByteArray(0))
            }
        }
        val results = manager().refreshAll(
            listOf(source("$base/good.json"), source("$base/bad.json"), source("$base/missing.json")),
        )
        assertEquals(3, results.size)
        assertNotNull(results[0].imported)
        assertNull(results[0].error)
        assertNotNull(results[1].error)
        assertNotNull(results[2].error)
    }

    // ------------------------------------------------------------------------------------------
    // parse() + factory installation
    // ------------------------------------------------------------------------------------------

    @Test
    fun factoryInstallationAndInMemoryParse() {
        DefaultRuleSourceManager.install()
        val mgr = RuleSourceManagerFactory.create()
        val set = mgr.parse(jsonBody, RuleSourceFormat.SIDEBIKE_JSON, "clipboard", "")
        assertEquals(true, set.intercepts["ads.example.com"]?.get(".*")?.abort)

        // custom install() override keeps the factory pattern usable by tests/app variants
        DefaultRuleSourceManager.install { DefaultRuleSourceManager(logger = logger) }
        assertNotNull(RuleSourceManagerFactory.create())
        DefaultRuleSourceManager.install() // restore default for other tests
    }
}
