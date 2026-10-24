package dev.sidebike.core.engine

import dev.sidebike.core.api.DnsAnswer
import dev.sidebike.core.api.DnsResolver
import dev.sidebike.core.api.DnsStats
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeEngineFactory
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.dns.DefaultDnsResolver
import dev.sidebike.core.dns.DnsCodec
import dev.sidebike.core.dns.DnsEndpoint
import dev.sidebike.core.dns.DnsTransport
import dev.sidebike.core.dns.DnsType
import dev.sidebike.core.dns.IpAnswer
import dev.sidebike.core.proxy.RawHttpClient
import dev.sidebike.core.proxy.TestConfigs
import dev.sidebike.core.proxy.TestHttpServer
import dev.sidebike.core.proxy.textResponse
import java.io.File
import java.nio.file.Files
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
 * Engine-level integration tests: lifecycle, factory registration, HTTP proxy through the engine, the
 * anti-poisoning DNS resolver wired with a fake transport (raw tun-DNS path, hermetic) and CA exposure.
 */
class DefaultSidebikeEngineTest {

    private val tempDirs = ArrayList<File>()
    private val engines = ArrayList<DefaultSidebikeEngine>()

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.close() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun newTempDir(): File {
        val dir = Files.createTempDirectory("ds-engine-test").toFile()
        tempDirs.add(dir)
        return dir
    }

    /** Offline DNS transport: answers A queries with a fixed public IP, never touches the network. */
    private class FakeDnsTransport(private val ip: String = "93.184.216.34") : DnsTransport {
        val exchanges = AtomicInteger(0)

        override suspend fun exchange(endpoint: DnsEndpoint, query: ByteArray, timeoutMs: Int): ByteArray? {
            exchanges.incrementAndGet()
            val parsed = DnsCodec.parse(query) ?: return null
            if (parsed.isResponse || parsed.questions.isEmpty()) return null
            val type = parsed.questions[0].type
            val answers = when (type) {
                DnsType.A -> listOf(IpAnswer(ip, 60, 4))
                DnsType.AAAA -> emptyList()
                else -> emptyList()
            }
            return DnsCodec.buildReply(parsed, answers)
        }
    }

    private fun newEngine(
        config: SidebikeConfig = TestConfigs.baseConfig(port = 0),
        logger: SidebikeLogger? = null,
        transport: DnsTransport = FakeDnsTransport(),
        dataDir: File = newTempDir(),
    ): DefaultSidebikeEngine {
        val engine = DefaultSidebikeEngine(
            dataDir = dataDir,
            initialConfig = config,
            logger = logger,
            dnsResolverFactory = { cfg, log -> DefaultDnsResolver(cfg, log, transport) },
        )
        engines.add(engine)
        return engine
    }

    // ------------------------------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------------------------------

    @Test
    fun startStopIsIdempotentAndProxyAccepts() {
        val upstream = TestHttpServer { textResponse(200, "engine-proxy-ok") }
        try {
            val engine = newEngine()
            assertFalse(engine.isRunning)
            assertFalse(engine.stats().proxyRunning)

            engine.start()
            engine.start() // idempotent
            assertTrue(engine.isRunning)
            assertTrue(engine.stats().proxyRunning)
            val proxyPort = engine.stats().traffic.proxyPort
            assertTrue("ephemeral proxy port expected, was $proxyPort", proxyPort > 0)

            val response = RawHttpClient.request(proxyPort, "GET", "http://127.0.0.1:${upstream.port}/x")
            assertEquals(200, response.status)
            assertEquals("engine-proxy-ok", RawHttpClient.requestBodyText(response))

            engine.stop()
            engine.stop() // idempotent
            assertFalse(engine.isRunning)
            assertFalse(engine.stats().proxyRunning)
            assertNull(engine.stats().traffic.proxyPort.takeIf { it > 0 })
        } finally {
            upstream.close()
        }
    }

    @Test
    fun engineCanRestartAfterStop() {
        val upstream = TestHttpServer { textResponse(200, "ok") }
        try {
            val engine = newEngine()
            engine.start()
            engine.stop()
            engine.start()
            assertTrue(engine.isRunning)
            val response = RawHttpClient.request(engine.stats().traffic.proxyPort, "GET", "http://127.0.0.1:${upstream.port}/y")
            assertEquals(200, response.status)
            assertEquals(2, engine.startCount())
        } finally {
            upstream.close()
        }
    }

    @Test
    fun startFailureOnBusyPortIsReportedWithoutThrowing() {
        val busy = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            val config = TestConfigs.baseConfig(port = busy.localPort)
            val engine = newEngine(config)
            engine.start() // must not throw
            assertFalse(engine.isRunning)
            assertNotNull("start failure must be surfaced through lastError", engine.stats().lastError)
        } finally {
            busy.close()
        }
    }

    @Test
    fun factoryInstallCreatesDefaultEngine() {
        DefaultSidebikeEngine.install()
        val dir = newTempDir()
        val engine = SidebikeEngineFactory.create(dir, TestConfigs.baseConfig(port = 0))
        try {
            assertTrue(engine is DefaultSidebikeEngine)
            assertNotNull(engine.logger())
        } finally {
            runCatching { engine.close() }
        }
    }

    // ------------------------------------------------------------------------------------------
    // DNS (raw tun path) with a fake transport
    // ------------------------------------------------------------------------------------------

    @Test
    fun resolveRawDnsQueryReturnsValidAReply() = runBlocking {
        val transport = FakeDnsTransport(ip = "140.82.121.3")
        val engine = newEngine(transport = transport)
        engine.start()

        val query = DnsCodec.buildQuery("github.com", DnsType.A, id = 0x1234, edns0 = false)
        val reply = engine.resolveRawDnsQuery(query)
        assertNotNull("raw DNS reply expected", reply)
        val parsed = DnsCodec.parse(reply!!)
        assertNotNull(parsed)
        assertTrue(parsed!!.isResponse)
        assertEquals("NOERROR expected, rcode=${parsed.rcode}", 0, parsed.rcode)
        assertFalse("reply must not be truncated (TC=0)", parsed.isTruncated)
        val ips = parsed.ipAnswers().map { it.ip }
        assertTrue("expected fake A answer, got $ips", ips.contains("140.82.121.3"))
        assertTrue("the fake transport must have been used", transport.exchanges.get() > 0)
    }

    @Test
    fun selfCheckDnsReturnsTrueWithWorkingTransport() = runBlocking {
        val transport = FakeDnsTransport(ip = "1.2.3.4")
        val engine = newEngine(transport = transport)
        engine.start()
        assertTrue(engine.selfCheckDns("example.com", timeoutMs = 3000))
        assertTrue("the fake DNS transport must have been queried", transport.exchanges.get() > 0)
        assertTrue(engine.stats().traffic.proxyPort > 0)
    }

    @Test
    fun selfCheckDnsReturnsFalseWhenTransportIsDead() = runBlocking {
        val deadTransport = object : DnsTransport {
            override suspend fun exchange(endpoint: DnsEndpoint, query: ByteArray, timeoutMs: Int): ByteArray? = null
        }
        val engine = newEngine(transport = deadTransport)
        engine.start()
        assertFalse(engine.selfCheckDns("example.com", timeoutMs = 1500))
        assertTrue(
            "engine must log the failed DNS self-check",
            engine.logger().history().any { it.message.contains("DNS self-check") },
        )
    }

    @Test
    fun resolveRawDnsQueryReturnsNullForGarbage() = runBlocking {
        val engine = newEngine()
        assertNull(engine.resolveRawDnsQuery(ByteArray(3) { 0x42 }))
    }

    // ------------------------------------------------------------------------------------------
    // config + CA + stats
    // ------------------------------------------------------------------------------------------

    @Test
    fun updateConfigIsAppliedAndQueryable() {
        val engine = newEngine()
        engine.start()
        val updated = engine.currentConfig().copy(
            proxy = engine.currentConfig().proxy.copy(maxConnections = 32),
        )
        engine.updateConfig(updated)
        assertEquals(32, engine.currentConfig().proxy.maxConnections)
        assertTrue(engine.isRunning)
    }

    @Test
    fun caCertificateExposureFollowsMitmToggle() {
        // MITM disabled: caCertificatePem() must be null; ensureCaCertificate() still provisions on demand.
        val engine = newEngine(TestConfigs.baseConfig(port = 0, mitmEnabled = false))
        engine.start()
        assertNull(engine.caCertificatePem())
        val ensured = engine.ensureCaCertificate()
        assertNotNull("explicit ensure must generate the CA", ensured)
        assertTrue(ensured!!.startsWith("-----BEGIN CERTIFICATE-----"))
        assertNull("still hidden while MITM is disabled", engine.caCertificatePem())

        // MITM enabled: the PEM becomes visible and stats.caReady flips.
        val mitmEngine = newEngine(TestConfigs.baseConfig(port = 0, mitmEnabled = true))
        mitmEngine.start()
        val pem = mitmEngine.ensureCaCertificate()
        assertNotNull(pem)
        assertEquals(pem, mitmEngine.caCertificatePem())
        assertTrue(mitmEngine.stats().caReady)
    }

    @Test
    fun statsAggregateProxyAndDnsCounters() = runBlocking {
        val upstream = TestHttpServer { textResponse(200, "ok") }
        val transport = FakeDnsTransport()
        try {
            val engine = newEngine(transport = transport)
            engine.start()
            val port = engine.stats().traffic.proxyPort
            RawHttpClient.request(port, "GET", "http://127.0.0.1:${upstream.port}/a")
            engine.resolveRawDnsQuery(DnsCodec.buildQuery("example.com", DnsType.A))
            val stats = engine.stats()
            assertEquals(1, stats.traffic.totalConnections)
            assertEquals(1, stats.traffic.successfulConnections)
            assertTrue(stats.traffic.bytesUp > 0)
            assertTrue(stats.traffic.bytesDown > 0)
            assertTrue("the DNS resolver must have been exercised", transport.exchanges.get() > 0)
            assertTrue(stats.traffic.uptimeMs >= 0)
            assertTrue(stats.dnsCacheEntries >= 0)
            assertTrue(stats.proxyRunning)
        } finally {
            upstream.close()
        }
    }
}
