package dev.sidebike.core.dns

import dev.sidebike.core.api.DnsMode
import dev.sidebike.core.api.DnsProvider
import dev.sidebike.core.api.DnsProtocol
import dev.sidebike.core.api.DnsSettings
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.log.NoopSidebikeLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultDnsResolverTest {

    private fun config(
        providers: Map<String, DnsProvider> = linkedMapOf("udp" to DnsProvider(server = "1.1.1.1")),
        mapping: Map<String, String> = emptyMap(),
        fallbacks: List<String> = listOf("udp"),
        parallel: Boolean = false,
        familyMapping: Map<String, String> = emptyMap(),
        hosts: Map<String, String> = emptyMap(),
        preset: Map<String, Map<String, Boolean>> = emptyMap(),
        blockIpv6: Boolean = false,
        mode: DnsMode = DnsMode.SMART,
    ): SidebikeConfig = SidebikeConfig(
        dns = DnsSettings(
            providers = providers,
            mapping = mapping,
            familyMapping = familyMapping,
            hosts = hosts,
            fallbackProviders = fallbacks,
            parallelProviders = parallel,
            blockIpv6 = blockIpv6,
            mode = mode,
            queryTimeoutMs = 800,
            cacheSize = 128,
            minTtlSec = 5,
            maxTtlSec = 60,
        ),
        preSetIpList = preset,
    )

    private fun resolver(config: SidebikeConfig, transport: FakeDnsTransport): DefaultDnsResolver =
        DefaultDnsResolver(config, NoopSidebikeLogger, transport) { 1_000_000L }

    @Test
    fun `resolves and caches answers`() = runBlocking {
        val transport = FakeDnsTransport().apply { respond("udp", listOf("1.2.3.4", "1.2.3.5"), ttl = 30) }
        val resolver = resolver(config(), transport)

        val first = resolver.resolve("example.com")
        assertEquals(listOf("1.2.3.4", "1.2.3.5"), first.ips)
        // UDP-only 的境外域名结果会被标记为 "(unverified)"（防污染透明度，0.1.1-alpha）
        assertTrue("provider 应为 udp/udp(unverified)，实际: ${first.provider}", first.provider.startsWith("udp"))
        assertFalse(first.fromCache)
        assertEquals(1, transport.calls.size)

        val second = resolver.resolve("example.com")
        assertTrue(second.fromCache)
        assertEquals(1, transport.calls.size)
        val stats = resolver.stats()
        assertEquals(2, stats.queries)
        assertEquals(1, stats.cacheHits)
        assertEquals(1, stats.cacheEntries)
    }

    @Test
    fun `hosts and preset ip take precedence without upstream query`() = runBlocking {
        val transport = FakeDnsTransport()
        val resolver = resolver(
            config(
                hosts = mapOf("hosts.example.com" to "9.9.9.9"),
                preset = mapOf("preset.example.com" to linkedMapOf("8.8.8.8" to true, "7.7.7.7" to false)),
            ),
            transport,
        )
        assertEquals(listOf("9.9.9.9"), resolver.resolve("hosts.example.com").ips)
        assertEquals(listOf("8.8.8.8"), resolver.resolve("preset.example.com").ips)
        assertEquals("preset", resolver.resolve("preset.example.com").provider)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `mapping selects provider`() = runBlocking {
        val transport = FakeDnsTransport().apply {
            respond("udp", listOf("1.1.1.1"))
            respond("doh", listOf("2.2.2.2"))
        }
        val resolver = resolver(
            config(
                providers = linkedMapOf(
                    "udp" to DnsProvider(server = "1.1.1.1"),
                    "doh" to DnsProvider(server = "https://doh.pub/dns-query", protocol = DnsProtocol.DOH, bootstrapIps = listOf("120.53.53.53")),
                ),
                mapping = mapOf("*.x.com" to "doh"),
                fallbacks = listOf("udp"),
            ),
            transport,
        )
        val answer = resolver.resolve("sub.x.com")
        assertEquals("doh", answer.provider)
        assertEquals(listOf("2.2.2.2"), answer.ips)
    }

    @Test
    fun `parallel race prefers fastest provider`() = runBlocking {
        val transport = FakeDnsTransport().apply {
            respond("slow", listOf("5.5.5.5"))
            delay("slow", 250)
            respond("fast", listOf("6.6.6.6"))
        }
        val resolver = resolver(
            config(
                providers = linkedMapOf(
                    "slow" to DnsProvider(server = "1.1.1.1"),
                    "fast" to DnsProvider(server = "1.1.1.2"),
                ),
                fallbacks = listOf("slow", "fast"),
                parallel = true,
            ),
            transport,
        )
        val answer = resolver.resolve("race.example.com")
        assertEquals(listOf("6.6.6.6"), answer.ips)
        assertTrue("provider 应为 fast/fast(unverified)，实际: ${answer.provider}", answer.provider.startsWith("fast"))
    }

    @Test
    fun `sequential fallback when primary fails`() = runBlocking {
        val transport = FakeDnsTransport().apply {
            onQuery("bad") { null }
            respond("good", listOf("3.3.3.3"))
        }
        val resolver = resolver(
            config(
                providers = linkedMapOf(
                    "bad" to DnsProvider(server = "1.1.1.1"),
                    "good" to DnsProvider(server = "1.1.1.2"),
                ),
                mapping = mapOf("fallback.example.com" to "bad"),
                fallbacks = listOf("good"),
                parallel = false,
            ),
            transport,
        )
        val answer = resolver.resolve("fallback.example.com")
        assertEquals(listOf("3.3.3.3"), answer.ips)
        assertTrue("provider 应为 good/good(unverified)，实际: ${answer.provider}", answer.provider.startsWith("good"))
    }

    @Test
    fun `rejects bogus answers and reports failure`() = runBlocking {
        val transport = FakeDnsTransport().apply { respond("udp", listOf("0.0.0.0", "127.0.0.1")) }
        val resolver = resolver(config(), transport)
        val answer = resolver.resolve("polluted.example.com")
        assertTrue(answer.isEmpty)
        assertTrue(resolver.stats().failures >= 1)
    }

    @Test
    fun `aaaa query falls back to a record`() = runBlocking {
        val transport = FakeDnsTransport().apply {
            onQuery("udp") { query ->
                val parsed = DnsCodec.parseOrThrow(query)
                if (parsed.questions[0].type == DnsType.AAAA) emptyResponseFor(query) else responseFor(query, listOf("4.4.4.4"))
            }
        }
        val resolver = resolver(
            config(familyMapping = mapOf("v6.example.com" to "6")),
            transport,
        )
        val answer = resolver.resolve("v6.example.com")
        assertEquals(listOf("4.4.4.4"), answer.ips)
    }

    @Test
    fun `unusable provider without bootstrap fails cleanly without calling transport`() = runBlocking {
        val transport = FakeDnsTransport()
        val resolver = resolver(
            config(
                providers = linkedMapOf(
                    "no-bootstrap" to DnsProvider(server = "https://doh.pub/dns-query", protocol = DnsProtocol.DOH),
                ),
                fallbacks = listOf("no-bootstrap"),
            ),
            transport,
        )
        val answer = resolver.resolve("nothing.example.com")
        assertTrue(answer.isEmpty)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `raw query returns valid reply without tc and with trimming`() = runBlocking {
        // 注意：解析器对境外域名的 UDP 应答会过滤私有/保留 IP（防投毒），此处必须用公网 IP
        val manyIps = (1..100).map { "1.1.1.$it" }
        val transport = FakeDnsTransport().apply { respond("udp", manyIps) }
        val resolver = resolver(config(), transport)

        val query = DnsCodec.buildQuery("big.example.com", DnsType.A, id = 77, udpPayloadSize = 512)
        val reply = resolver.resolveRawQuery(query)
        assertNotNull(reply)
        assertTrue("raw 应答必须 <= EDNS0 advertised 512: ${reply!!.size}", reply.size <= 512)
        val parsed = DnsCodec.parseOrThrow(reply)
        assertEquals(77, parsed.id)
        assertTrue(parsed.isResponse)
        assertFalse("禁止设置 TC", parsed.isTruncated)
        assertTrue(parsed.ipAnswers().isNotEmpty())
        assertEquals("big.example.com", parsed.questions[0].name)
    }

    @Test
    fun `raw query for https record returns empty noerror`() = runBlocking {
        val transport = FakeDnsTransport().apply { respond("udp", listOf("1.2.3.4")) }
        val resolver = resolver(config(), transport)
        val reply = resolver.resolveRawQuery(DnsCodec.buildQuery("ech.example.com", DnsType.HTTPS, id = 88))
        assertNotNull(reply)
        val parsed = DnsCodec.parseOrThrow(reply!!)
        assertEquals(0, parsed.rcode)
        assertTrue(parsed.ipAnswers().isEmpty())
    }

    @Test
    fun `raw query failure returns servfail wire reply`() = runBlocking {
        val transport = FakeDnsTransport().apply { onQuery("udp") { null } }
        val resolver = resolver(config(), transport)
        val reply = resolver.resolveRawQuery(DnsCodec.buildQuery("fail.example.com", DnsType.A, id = 99))
        assertNotNull(reply)
        assertEquals(2, DnsCodec.parseOrThrow(reply!!).rcode)
    }

    @Test
    fun `raw query for invalid input returns null`() = runBlocking {
        val resolver = resolver(config(), FakeDnsTransport())
        assertNull(resolver.resolveRawQuery(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `update config clears cache and applies preset`() = runBlocking {
        val transport = FakeDnsTransport().apply { respond("udp", listOf("1.2.3.4")) }
        val resolver = resolver(config(), transport)
        assertEquals(listOf("1.2.3.4"), resolver.resolve("update.example.com").ips)

        resolver.updateConfig(
            config(preset = mapOf("update.example.com" to linkedMapOf("9.9.9.9" to true))),
        )
        val answer = resolver.resolve("update.example.com")
        assertEquals(listOf("9.9.9.9"), answer.ips)
        assertEquals("preset", answer.provider)
        assertTrue(answer.isEmpty == false)
    }

    @Test
    fun `blank host returns empty answer without query`() = runBlocking {
        val transport = FakeDnsTransport()
        val resolver = resolver(config(), transport)
        val answer = resolver.resolve("   ")
        assertTrue(answer.isEmpty)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `ip literal host resolves without query`() = runBlocking {
        val transport = FakeDnsTransport()
        val resolver = resolver(config(), transport)
        val answer = resolver.resolve("203.0.113.9")
        assertEquals(listOf("203.0.113.9"), answer.ips)
        assertEquals("literal", answer.provider)
        assertEquals(0, transport.calls.size)
    }
}
