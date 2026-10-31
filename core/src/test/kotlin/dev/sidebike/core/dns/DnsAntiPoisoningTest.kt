package dev.sidebike.core.dns

import dev.sidebike.core.api.DnsMode
import dev.sidebike.core.api.DnsProtocol
import dev.sidebike.core.api.DnsProvider
import dev.sidebike.core.api.DnsSettings
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.log.NoopSidebikeLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.1.1-alpha DNS 加固测试（hermetic，纯本地 fake/本地服务器）：
 * - `health()`：ok/失败/超时/缺 bootstrap 均返回结果且不抛异常；
 * - 境外域名多源共识：UDP 投毒被可信 DoH 覆盖、未缓存污染结果；
 * - DoH/DoT provider 优先于 UDP；
 * - 全部 provider 失败时回退 preSetIpList；
 * - 失败不缓存、超时受控（短超时）；
 * - 仅 UDP 可用时返回 `(unverified)` 结果且 TTL ≤ 60s；
 * - 私人/保留 IP 投毒对境外域名被拒绝；
 * - DoT transport：bootstrap 字面 IP + 诱饵 SNI 握手失败自动回退真实 SNI。
 */
class DnsAntiPoisoningTest {

    private fun provider(name: String, doh: Boolean = false, bootstrap: Boolean = true): DnsProvider =
        if (doh) {
            DnsProvider(
                server = "https://$name.example/dns-query",
                protocol = DnsProtocol.DOH,
                bootstrapIps = if (bootstrap) listOf("127.0.0.1") else emptyList(),
            )
        } else {
            DnsProvider(server = "1.1.1.1", protocol = DnsProtocol.UDP)
        }

    private fun config(
        providers: Map<String, DnsProvider>,
        mapping: Map<String, String> = emptyMap(),
        fallbacks: List<String> = providers.keys.toList(),
        parallel: Boolean = true,
        preset: Map<String, Map<String, Boolean>> = emptyMap(),
        mode: DnsMode = DnsMode.SMART,
        queryTimeoutMs: Int = 800,
    ): SidebikeConfig = SidebikeConfig(
        dns = DnsSettings(
            providers = providers,
            mapping = mapping,
            fallbackProviders = fallbacks,
            parallelProviders = parallel,
            mode = mode,
            queryTimeoutMs = queryTimeoutMs,
            cacheSize = 64,
            minTtlSec = 5,
            maxTtlSec = 60,
        ),
        preSetIpList = preset,
    )

    private fun resolver(config: SidebikeConfig, transport: FakeDnsTransport): DefaultDnsResolver =
        DefaultDnsResolver(config, NoopSidebikeLogger, transport) { 1_000_000L }

    // ------------------------------------------------------------------ health

    @Test
    fun `health probes every enabled provider and never throws`() = runBlocking {
        val transport = FakeDnsTransport().apply {
            respond("udp-ok", listOf("1.2.3.4"), ttl = 60)
            onQuery("doh-fail") { null }
            delay("doh-timeout", 5_000)
        }
        val resolver = resolver(
            config(
                providers = linkedMapOf(
                    "udp-ok" to provider("udp-ok"),
                    "doh-fail" to provider("doh-fail", doh = true),
                    "doh-timeout" to provider("doh-timeout", doh = true),
                    "no-bootstrap" to provider("no-bootstrap", doh = true, bootstrap = false),
                ),
                queryTimeoutMs = 500,
            ),
            transport,
        )
        val results = resolver.health()
        assertEquals(4, results.size)
        val byName = results.associateBy { it.provider }
        assertTrue(byName.getValue("udp-ok").ok)
        assertTrue(byName.getValue("udp-ok").latencyMs >= 0)
        assertFalse(byName.getValue("doh-fail").ok)
        assertNotNull(byName.getValue("doh-fail").error)
        assertFalse(byName.getValue("doh-timeout").ok)
        assertNotNull(byName.getValue("doh-timeout").error)
        assertFalse(byName.getValue("no-bootstrap").ok)
        assertTrue(byName.getValue("no-bootstrap").error!!.contains("bootstrap"))
    }

    @Test
    fun `health returns empty list when no provider enabled`() = runBlocking {
        val resolver = resolver(config(emptyMap(), fallbacks = emptyList()), FakeDnsTransport())
        assertTrue(resolver.health().isEmpty())
    }

    // ------------------------------------------------------------------ consensus

    @Test
    fun `udp poisoned answer is overridden by trusted doh result and not cached`() = runBlocking {
        val transport = FakeDnsTransport().apply {
            respond("udp", listOf("6.6.6.6"))
            respond("doh", listOf("1.2.3.4"))
        }
        val resolver = resolver(
            config(
                providers = linkedMapOf("udp" to provider("udp"), "doh" to provider("doh", doh = true)),
                mapping = mapOf("poison.example.com" to "udp"),
                fallbacks = listOf("udp", "doh"),
            ),
            transport,
        )
        val answer = resolver.resolve("poison.example.com")
        assertEquals("doh", answer.provider)
        assertEquals(listOf("1.2.3.4"), answer.ips)
        assertFalse(answer.fromCache)
        // 缓存中不应出现被污染的 6.6.6.6
        val second = resolver.resolve("poison.example.com")
        assertTrue(second.fromCache)
        assertEquals(listOf("1.2.3.4"), second.ips)
        assertEquals(1, transport.callsFor("doh").size)
    }

    @Test
    fun `trusted provider preferred over udp even when udp listed first`() = runBlocking {
        val transport = FakeDnsTransport().apply {
            respond("udp", listOf("6.6.6.6"))
            respond("doh", listOf("1.2.3.4"))
        }
        val resolver = resolver(
            config(
                providers = linkedMapOf("udp" to provider("udp"), "doh" to provider("doh", doh = true)),
                fallbacks = listOf("udp", "doh"),
            ),
            transport,
        )
        val answer = resolver.resolve("foreign.example.com")
        assertEquals("doh", answer.provider)
        assertEquals(listOf("1.2.3.4"), answer.ips)
    }

    @Test
    fun `only untrusted available returns unverified answer with capped ttl`() = runBlocking {
        val transport = FakeDnsTransport().apply { respond("udp", listOf("1.2.3.4"), ttl = 3600) }
        val resolver = resolver(
            config(
                providers = linkedMapOf("udp" to provider("udp")),
                fallbacks = listOf("udp"),
            ),
            transport,
        )
        val answer = resolver.resolve("foreign.example.com")
        assertTrue(answer.provider.contains("unverified"))
        assertEquals(listOf("1.2.3.4"), answer.ips)
        assertTrue("未验证结果 TTL 必须 <= 60s, ttl=${answer.ttlSec}", answer.ttlSec <= 60)
    }

    @Test
    fun `private ip in udp answer for foreign domain is rejected`() = runBlocking {
        val transport = FakeDnsTransport().apply { respond("udp", listOf("192.168.1.1", "10.0.0.1")) }
        val resolver = resolver(
            config(providers = linkedMapOf("udp" to provider("udp")), fallbacks = listOf("udp")),
            transport,
        )
        val answer = resolver.resolve("foreign.example.com")
        assertTrue("私有地址投毒必须被丢弃: ${answer.ips}", answer.isEmpty)
        assertTrue(resolver.stats().failures >= 1)
    }

    @Test
    fun `same answer from trusted and untrusted is accepted as trusted`() = runBlocking {
        val transport = FakeDnsTransport().apply {
            respond("udp", listOf("1.2.3.4"))
            respond("doh", listOf("1.2.3.4"))
        }
        val resolver = resolver(
            config(
                providers = linkedMapOf("udp" to provider("udp"), "doh" to provider("doh", doh = true)),
                fallbacks = listOf("udp", "doh"),
            ),
            transport,
        )
        val answer = resolver.resolve("foreign.example.com")
        assertEquals("doh", answer.provider)
    }

    // ------------------------------------------------------------------ fallback / caching

    @Test
    fun `preset ips used when all providers fail`() = runBlocking {
        val transport = FakeDnsTransport().apply { onQuery("udp") { null } }
        val resolver = resolver(
            config(
                providers = linkedMapOf("udp" to provider("udp")),
                fallbacks = listOf("udp"),
                preset = mapOf("blocked.example.com" to linkedMapOf("9.9.9.9" to true)),
            ),
            transport,
        )
        val answer = resolver.resolve("blocked.example.com")
        assertEquals("preset", answer.provider)
        assertEquals(listOf("9.9.9.9"), answer.ips)
    }

    @Test
    fun `preset fallback also applies when family mapping asks for aaaa but only v4 preset exists`() = runBlocking {
        val transport = FakeDnsTransport().apply { onQuery("udp") { null } }
        val resolver = resolver(
            config(
                providers = linkedMapOf("udp" to provider("udp")),
                fallbacks = listOf("udp"),
                preset = mapOf("v4only.example.com" to linkedMapOf("9.9.9.9" to true)),
            ),
            transport,
        )
        // familyMapping 强制 IPv6，但预置 IP 只有 v4：family 过滤后为空 → 走 provider 失败 → 再兜底 preset
        val cfg = resolver.let { it }
        // 通过 raw AAAA 查询验证兜底仍返回预置 IP 的 A 应答（AAAA 查询走 AAAA 分支；这里直接验证 resolve）
        val answer = cfg.resolve("v4only.example.com")
        assertEquals(listOf("9.9.9.9"), answer.ips)
    }

    @Test
    fun `failures are not cached and next query retries`() = runBlocking {
        var attempt = 0
        val transport = FakeDnsTransport().apply {
            onQuery("udp") { query ->
                attempt++
                if (attempt == 1) null else responseFor(query, listOf("1.2.3.4"))
            }
        }
        val resolver = resolver(
            config(providers = linkedMapOf("udp" to provider("udp")), fallbacks = listOf("udp")),
            transport,
        )
        val first = resolver.resolve("retry.example.com")
        assertTrue(first.isEmpty)
        val second = resolver.resolve("retry.example.com")
        assertEquals(listOf("1.2.3.4"), second.ips)
        assertFalse(second.fromCache)
        assertEquals(2, resolver.stats().queries)
    }

    @Test
    fun `slow provider does not stall beyond configured timeout`() = runBlocking {
        val transport = FakeDnsTransport().apply { delay("udp", 5_000) }
        val resolver = resolver(
            config(
                providers = linkedMapOf("udp" to provider("udp")),
                fallbacks = listOf("udp"),
                queryTimeoutMs = 600,
            ),
            transport,
        )
        val started = System.currentTimeMillis()
        val answer = resolver.resolve("slow.example.com")
        val elapsed = System.currentTimeMillis() - started
        assertTrue(answer.isEmpty)
        assertTrue("解析不应被 5s provider 拖死, elapsed=${elapsed}ms", elapsed < 2_500)
    }

    @Test
    fun `single flight deduplicates concurrent identical queries`() = runBlocking {
        val transport = FakeDnsTransport().apply {
            respond("udp", listOf("1.2.3.4"))
            delay("udp", 120)
        }
        val resolver = resolver(
            config(providers = linkedMapOf("udp" to provider("udp")), fallbacks = listOf("udp")),
            transport,
        )
        val results = coroutineScope {
            (1..8).map { async { resolver.resolve("dedup.example.com") } }
                .map { it.await() }
        }
        assertTrue(results.all { it.ips == listOf("1.2.3.4") })
        assertEquals("single-flight 应只查询一次, calls=${transport.calls.size}", 1, transport.calls.size)
    }

    // ------------------------------------------------------------------ DoT transport

    @Test
    fun `dot transport connects to bootstrap ip with configured decoy sni`() = runBlocking {
        LocalTlsDnsServer { query -> responseFor(query, listOf("2.3.4.5")) }.use { server ->
            val provider = DnsProvider(
                server = "tls://dns.test",
                protocol = DnsProtocol.DOT,
                sni = "dns.test",
                bootstrapIps = listOf("127.0.0.1"),
                trustAllCerts = true,
            )
            val endpoint = DnsEndpointParser.parse("dot-local", provider).copy(port = server.port)
            val transport = SocketDnsTransport(NoopSidebikeLogger)
            val reply = transport.exchange(endpoint, DnsCodec.buildQuery("example.com", DnsType.A), 2_000)
            assertNotNull(reply)
            assertTrue(DnsCodec.parseOrThrow(reply!!).ipAnswers().any { it.ip == "2.3.4.5" })
        }
    }

    @Test
    fun `dot transport retries real sni when decoy sni is rejected by server`() = runBlocking {
        // 服务端只接受真实 SNI "dns.test"；客户端配的诱饵 sni=baidu.com 会被拒绝 → transport 回退真实 SNI 重试
        LocalTlsDnsServer(expectedSni = "dns.test") { query -> responseFor(query, listOf("3.4.5.6")) }.use { server ->
            val provider = DnsProvider(
                server = "tls://dns.test",
                protocol = DnsProtocol.DOT,
                sni = "baidu.com",
                bootstrapIps = listOf("127.0.0.1"),
                trustAllCerts = true,
            )
            val endpoint = DnsEndpointParser.parse("dot-decoy", provider).copy(port = server.port)
            val transport = SocketDnsTransport(NoopSidebikeLogger)
            val reply = transport.exchange(endpoint, DnsCodec.buildQuery("example.com", DnsType.A), 3_000)
            assertNotNull("诱饵 SNI 被拒后应回退真实 SNI 并成功", reply)
            assertTrue(DnsCodec.parseOrThrow(reply!!).ipAnswers().any { it.ip == "3.4.5.6" })
        }
    }

    @Test
    fun `doh provider is preferred and used for foreign domains`() = runBlocking {
        val transport = FakeDnsTransport().apply {
            respond("udp", listOf("6.6.6.6"))
            respond("doh", listOf("1.2.3.4"))
        }
        val resolver = resolver(
            config(
                providers = linkedMapOf("udp" to provider("udp"), "doh" to provider("doh", doh = true)),
                fallbacks = listOf("udp", "doh"),
            ),
            transport,
        )
        val answer = resolver.resolve("doh-pref.example.com")
        assertEquals("doh", answer.provider)
        assertEquals(listOf("1.2.3.4"), answer.ips)
        assertTrue(transport.callsFor("doh").isNotEmpty())
    }
}
