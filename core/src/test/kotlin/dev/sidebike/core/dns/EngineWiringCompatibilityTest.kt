package dev.sidebike.core.dns

import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.log.NoopSidebikeLogger
import dev.sidebike.core.log.RingBufferSidebikeLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎接线兼容性测试：用**默认配置**（api DnsSettings.defaultProviders）驱动 raw tun DNS 路径，
 * 覆盖 `DefaultSidebikeEngine.selfCheckDns` / `resolveRawDnsQuery` 对 resolver 的真实调用形态。
 * 这是 T6 与 proxy-dev 引擎集成的最低保障（hermetic：注入 fake transport）。
 */
class EngineWiringCompatibilityTest {

    @Test
    fun `default config raw a query returns noerror answer over fake transport`() = runBlocking {
        val transport = FakeDnsTransport().apply { respond("aliyun", listOf("1.2.3.4"), ttl = 60) }
        // doh.pub 等 provider 也可能被并行选中；为所有 provider 都配置应答
        for (name in listOf("doh.pub", "cf-DoT", "cf-DoH", "quad9-ip", "rubyfish")) {
            transport.respond(name, listOf("1.2.3.4"), ttl = 60)
        }
        val resolver = DefaultDnsResolver(SidebikeConfig(), NoopSidebikeLogger, transport)
        val query = DnsCodec.buildQuery("example.com", DnsType.A, id = 0x5A17)
        val reply = resolver.resolveRawQuery(query)
        assertNotNull("raw reply expected", reply)
        val parsed = DnsCodec.parse(reply!!)
        assertNotNull(parsed)
        assertTrue(parsed!!.isResponse)
        assertEquals(0, parsed.rcode)
        assertFalse(parsed.isTruncated)
        assertTrue("expected answer from fake transport", parsed.ipAnswers().any { it.ip == "1.2.3.4" })
        assertTrue("raw queries must be counted", resolver.stats().queries > 0)
    }

    @Test
    fun `engine style wiring with ring buffer logger and default config`() = runBlocking {
        val transport = FakeDnsTransport().apply { respond("aliyun", listOf("140.82.121.3")) }
        val resolver = DefaultDnsResolver(SidebikeConfig(), RingBufferSidebikeLogger(50), transport)
        val reply = resolver.resolveRawQuery(DnsCodec.buildQuery("github.com", DnsType.A, id = 0x1234, edns0 = false))
        assertNotNull(reply)
        val parsed = DnsCodec.parse(reply!!)!!
        assertEquals(0, parsed.rcode)
        assertTrue(parsed.ipAnswers().any { it.ip == "140.82.121.3" })
    }

    @Test
    fun `self check style query with no edns0 still works`() = runBlocking {
        val transport = FakeDnsTransport().apply { respond("aliyun", listOf("9.9.9.9")) }
        val resolver = DefaultDnsResolver(SidebikeConfig(), NoopSidebikeLogger, transport)
        val reply = resolver.resolveRawQuery(DnsCodec.buildQuery("example.com", DnsType.A, id = 0x5A17, edns0 = false))
        assertNotNull(reply)
        assertTrue(reply!!.size >= 12)
        val parsed = DnsCodec.parse(reply)!!
        assertTrue(parsed.isResponse && parsed.rcode == 0 && parsed.answers.isNotEmpty())
    }
}
