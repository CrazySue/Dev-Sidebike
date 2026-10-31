package dev.sidebike.core.proxy

import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.RouteKind
import dev.sidebike.core.api.RoutePlan
import dev.sidebike.core.mitm.InterceptorActions
import dev.sidebike.core.util.HttpCodec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
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
 * 0.1.1 MITM safety fallback: MITM enabled but the CA is missing/unreadable/generation fails must NOT break
 * TLS for every rule-matched host - the proxy degrades those hosts to a direct tunnel (WARN once) instead of
 * terminating TLS with a certificate the client cannot verify.
 */
class MitmCaUnavailableFallbackTest {

    private val logger = RecordingLogger()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val closeables = ArrayList<AutoCloseable>()
    private val proxies = ArrayList<HttpProxyServer>()
    private val tempDirs = ArrayList<File>()

    @After
    fun tearDown() {
        proxies.forEach { runCatching { it.stop() } }
        closeables.reversed().forEach { runCatching { it.close() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun intercept(jsonText: String): InterceptAction =
        json.decodeFromString(InterceptAction.serializer(), jsonText)

    /** A path that cannot host the CA files: `dataDir` exists as a regular file, so writes fail. */
    private fun unusableCaDir(): File {
        val file = Files.createTempFile("ds-ca-not-a-dir", ".bin").toFile()
        tempDirs.add(file)
        return file
    }

    @Test
    fun mitmEnabledWithUnusableCaFallsBackToDirectTunnel() {
        val echo = TestEchoServer().also { closeables.add(it) }
        val action = intercept("""{"requestReplace":{"headers":{"x-mitm":"1"}}}""")
        val ruleEngine = TestRuleEngine(
            planner = { _, _, _ ->
                RoutePlan(RouteKind.DIRECT, "127.0.0.1", echo.port, action = action, reason = "test")
            },
        )
        val dataDir = unusableCaDir()
        val proxy = HttpProxyServer(
            dataDir = dataDir,
            initialConfig = TestConfigs.baseConfig(mitmEnabled = true),
            ruleEngine = ruleEngine,
            dnsResolver = TestDnsResolver(),
            logger = logger,
        )
        proxies.add(proxy)

        // CA generation must fail gracefully (no throw) and report MITM as not ready.
        assertNull(proxy.ensureCaPem())
        assertFalse(proxy.mitmReady())

        proxy.start(0)
        RawHttpClient.connect(proxy.port).use { socket ->
            socket.getOutputStream().write("CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            val status = HttpCodec.readResponseHead(socket.getInputStream()).status
            assertEquals("must fall back to a transparent direct tunnel", 200, status)
            socket.getOutputStream().write("fallback-bytes".toByteArray())
            socket.getOutputStream().flush()
            assertArrayEquals("fallback-bytes".toByteArray(), socket.getInputStream().readNBytes(14))
        }
        assertTrue("echo target should receive tunnelled bytes", echo.received.size() >= 14)
        assertTrue(
            "expected a one-time WARN about the MITM->direct downgrade, got: ${logger.messages}",
            logger.messages.any { (level, text) -> level == "WARN" && text.contains("MITM 不可用") },
        )
    }

    @Test
    fun healthyCaReportsMitmReady() {
        val dir = Files.createTempDirectory("ds-ca-ok").toFile()
        tempDirs.add(dir)
        val ruleEngine = TestRuleEngine(planner = { host, port, _ -> RoutePlan(RouteKind.DIRECT, host, port) })
        val proxy = HttpProxyServer(
            dataDir = dir,
            initialConfig = TestConfigs.baseConfig(mitmEnabled = true),
            ruleEngine = ruleEngine,
            dnsResolver = TestDnsResolver(),
            logger = logger,
        )
        proxies.add(proxy)
        assertNotNull(proxy.ensureCaPem())
        assertTrue(proxy.mitmReady())

        // With MITM disabled in config, readiness is false regardless of an existing CA.
        val dir2 = Files.createTempDirectory("ds-ca-ok2").toFile()
        tempDirs.add(dir2)
        val proxyNoMitm = HttpProxyServer(
            dataDir = dir2,
            initialConfig = TestConfigs.baseConfig(mitmEnabled = false),
            ruleEngine = TestRuleEngine(),
            dnsResolver = TestDnsResolver(),
            logger = logger,
        )
        proxies.add(proxyNoMitm)
        assertNotNull(proxyNoMitm.ensureCaPem()) // ensureCaCertificate works regardless of toggle
        assertFalse(proxyNoMitm.mitmReady())
    }
}
