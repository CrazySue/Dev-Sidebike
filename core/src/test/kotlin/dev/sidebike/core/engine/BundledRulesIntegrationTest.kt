package dev.sidebike.core.engine

import dev.sidebike.core.api.RouteKind
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.config.BundledConfig
import dev.sidebike.core.mitm.InterceptorActions
import dev.sidebike.core.proxy.HttpProxyServer
import dev.sidebike.core.proxy.MitmTestClient
import dev.sidebike.core.proxy.RawHttpClient
import dev.sidebike.core.proxy.RecordingLogger
import dev.sidebike.core.proxy.TestCAs
import dev.sidebike.core.proxy.TestDnsResolver
import dev.sidebike.core.proxy.TestTlsHttpServer
import dev.sidebike.core.proxy.textResponse
import dev.sidebike.core.rules.DefaultRuleEngine
import dev.sidebike.core.util.HttpHeaders
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration test of the REAL bundled rule snapshot (69 domains / 77 path rules copied from the running
 * Dev-Sidecar installation) driving the rule engine and the MITM proxy end-to-end.
 *
 * This is the headline parity check: the official config's `sni: baidu.com` decoy rule for github.com must
 * make the proxy terminate TLS for github.com and send `baidu.com` as upstream SNI, without any hand-written
 * rules in the test.
 */
class BundledRulesIntegrationTest {

    private val logger = RecordingLogger()
    private val tempDirs = ArrayList<File>()
    private val proxies = ArrayList<HttpProxyServer>()
    private val closeables = ArrayList<AutoCloseable>()

    @After
    fun tearDown() {
        proxies.forEach { runCatching { it.stop() } }
        closeables.reversed().forEach { runCatching { it.close() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun newTempDir(): File {
        val dir = Files.createTempDirectory("ds-bundled-test").toFile()
        tempDirs.add(dir)
        return dir
    }

    private fun baseConfig(): SidebikeConfig = BundledConfig.load(logger)

    @Test
    fun bundledRulesProduceExpectedPlans() {
        assertTrue("bundled rules snapshot missing from resources", BundledConfig.isSnapshotPresent())
        val config = baseConfig()
        assertEquals("expected 69 seeded intercept domains", 69, config.intercepts.size)
        val ruleEngine = DefaultRuleEngine(config, logger)

        // github.com: host has path rules -> CONNECT phase must ask for MITM.
        assertTrue(ruleEngine.requiresMitm("github.com"))
        assertFalse(ruleEngine.requiresMitm("example.org"))
        val pending = ruleEngine.plan("github.com", 443, null)
        assertNotNull("CONNECT-phase plan must carry the merged action", pending.action)
        assertEquals("path-rule-pending-mitm", pending.reason)

        // path rules: repo pages carry the sni decoy (+ userscript) from the official config
        val page = ruleEngine.plan("github.com", 443, "/owner/repo")
        assertEquals(RouteKind.DIRECT, page.kind)
        assertEquals("baidu.com", page.sni)
        assertNotNull(page.action?.script)

        // mirror rule: fonts.googleapis.com -> fonts.googleapis.cn on the same TLS port
        val mirror = ruleEngine.plan("fonts.googleapis.com", 443, "/css?family=Roboto")
        assertEquals(RouteKind.MIRROR_PROXY, mirror.kind)
        assertEquals("fonts.googleapis.cn", mirror.connectHost)
        assertEquals(443, mirror.connectPort)
        assertEquals("fonts.googleapis.cn", mirror.action?.proxy)

        // synthetic success with sibling status override: collector.github.com { success: true, status: 204 }
        val syntheticPlan = ruleEngine.plan("collector.github.com", 443, "/github/collect")
        assertEquals(RouteKind.SYNTHETIC, syntheticPlan.kind)
        val synthetic = InterceptorActions.buildSynthetic(syntheticPlan.action, HttpHeaders())
        assertNotNull(synthetic)
        assertEquals(204, synthetic!!.status)
        assertEquals(0, synthetic.body.size)

        // whitelist from the real config (localhost / 127.*) must not be intercepted
        val whitelisted = ruleEngine.plan("localhost", 80, "/")
        assertEquals(RouteKind.DIRECT, whitelisted.kind)
        assertTrue(ruleEngine.isWhitelisted("127.0.0.1"))
    }

    @Test
    fun bundledRulesDriveMitmWithDecoySni() {
        val logger = RecordingLogger()
        val base = BundledConfig.load(logger)
        val config = base.copy(
            proxy = base.proxy.copy(
                port = 0,
                mitmEnabled = true,
                connectTimeoutMs = 3000,
                readTimeoutMs = 5000,
                idleTimeoutMs = 5000,
            ),
            mitm = base.mitm.copy(enabled = true),
            // Clear preset IPs so the hermetic DNS stub maps github.com to loopback (no external network).
            preSetIpList = emptyMap(),
        )
        val ruleEngine = DefaultRuleEngine(config, logger)
        val dns = TestDnsResolver()
        dns.register("github.com", "127.0.0.1")
        val dir = newTempDir()
        val proxy = HttpProxyServer(
            dataDir = dir,
            initialConfig = config,
            ruleEngine = ruleEngine,
            dnsResolver = dns,
            logger = logger,
            hostNeedsMitm = { host -> ruleEngine.requiresMitm(host) },
        )
        val caPem = proxy.ensureCaPem()!!
        proxy.start(0)
        proxies.add(proxy)

        val upstreamCa = TestCAs.newCa()
        val upstream = TestTlsHttpServer(upstreamCa, "github.com") { textResponse(200, "bundled-parity-ok") }
        closeables.add(upstream)

        val ssl = MitmTestClient.connect(proxy.port, "github.com", upstream.port, caPem)
        val response = try {
            MitmTestClient.request(ssl, "GET", "/owner/repo", headers = listOf("Host" to "github.com:${upstream.port}"))
        } finally {
            runCatching { ssl.close() }
        }

        assertEquals(200, response.status)
        assertEquals("bundled-parity-ok", RawHttpClient.requestBodyText(response))
        assertEquals(1, upstream.requests.size)
        assertEquals("/owner/repo", upstream.requests[0].target)
        assertTrue(
            "official github.com rule must send the decoy SNI 'baidu.com' upstream, captured=${upstream.capturedSnis}",
            upstream.capturedSnis.contains("baidu.com"),
        )
    }
}
