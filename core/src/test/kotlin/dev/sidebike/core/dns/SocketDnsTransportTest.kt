package dev.sidebike.core.dns

import dev.sidebike.core.api.DnsProvider
import dev.sidebike.core.api.DnsProtocol
import dev.sidebike.core.log.NoopSidebikeLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketDnsTransportTest {

    private val transport = SocketDnsTransport(NoopSidebikeLogger)
    private val query = DnsCodec.buildQuery("example.com", DnsType.A, id = 4242)

    private fun endpoint(provider: DnsProvider): DnsEndpoint = DnsEndpointParser.parse("test", provider)

    @Test
    fun `udp provider exchanges dns messages`() = runBlocking {
        LocalUdpDnsServer { request -> responseFor(request, listOf("1.2.3.4")) }.use { server ->
            val parsed = endpoint(DnsProvider(server = "127.0.0.1:${server.port}", protocol = DnsProtocol.UDP))
            assertTrue(parsed.usable)
            val response = transport.exchange(parsed, query, 1_000)
            assertNotNull(response)
            val answer = DnsCodec.parseOrThrow(response!!)
            assertTrue(answer.ipAnswers().any { it.ip == "1.2.3.4" })
        }
    }

    @Test
    fun `tcp provider exchanges dns messages`() = runBlocking {
        LocalTcpDnsServer { request -> responseFor(request, listOf("2.3.4.5")) }.use { server ->
            val parsed = endpoint(DnsProvider(server = "127.0.0.1:${server.port}", protocol = DnsProtocol.TCP))
            val response = transport.exchange(parsed, query, 1_000)
            assertNotNull(response)
            assertTrue(DnsCodec.parseOrThrow(response!!).ipAnswers().any { it.ip == "2.3.4.5" })
        }
    }

    @Test
    fun `doh provider over plain http exchanges dns messages`() = runBlocking {
        LocalHttpDohServer { request -> responseFor(request, listOf("3.4.5.6")) }.use { server ->
            val parsed = endpoint(
                DnsProvider(
                    server = "http://127.0.0.1:${server.port}/dns-query",
                    protocol = DnsProtocol.DOH,
                ),
            )
            val response = transport.exchange(parsed, query, 1_500)
            assertNotNull(response)
            assertTrue(DnsCodec.parseOrThrow(response!!).ipAnswers().any { it.ip == "3.4.5.6" })
            assertTrue(server.lastRequestLine.startsWith("POST /dns-query"))
        }
    }

    @Test
    fun `provider hostname without bootstrap ips fails without network access`() = runBlocking {
        val parsed = endpoint(DnsProvider(server = "https://doh.pub/dns-query", protocol = DnsProtocol.DOH))
        assertTrue(!parsed.usable)
        assertNull(transport.exchange(parsed, query, 500))
    }

    @Test
    fun `udp timeout returns null`() = runBlocking {
        SilentUdpServer().use { server ->
            val parsed = endpoint(DnsProvider(server = "127.0.0.1:${server.port}", protocol = DnsProtocol.UDP))
            val start = System.currentTimeMillis()
            val response = transport.exchange(parsed, query, 300)
            val elapsed = System.currentTimeMillis() - start
            assertNull(response)
            assertTrue("超时耗时应受控: ${elapsed}ms", elapsed < 3_000)
        }
    }

    @Test
    fun `endpoint parser infers protocols and ports`() {
        val udp = DnsEndpointParser.parse("aliyun", DnsProvider(server = "223.5.5.5"))
        assertTrue(udp.protocol == DnsProtocol.UDP)
        assertTrue(udp.port == 53)
        assertTrue(udp.usable)

        val dot = DnsEndpointParser.parse(
            "cf-dot",
            DnsProvider(server = "tls://1.1.1.1", protocol = DnsProtocol.DOT, sni = "baidu.com"),
        )
        assertTrue(dot.protocol == DnsProtocol.DOT)
        assertTrue(dot.port == 853)
        assertTrue(dot.secure)
        assertTrue(dot.tlsServerName == "baidu.com")
        assertTrue(dot.bootstrapIps == listOf("1.1.1.1"))

        val doh = DnsEndpointParser.parse(
            "cf-doh",
            DnsProvider(
                server = "https://cloudflare-dns.com/dns-query",
                protocol = DnsProtocol.DOH,
                bootstrapIps = listOf("1.1.1.1"),
            ),
        )
        assertTrue(doh.port == 443)
        assertTrue(doh.path == "/dns-query")
        assertTrue(doh.secure)
        assertTrue(doh.tlsServerName == "cloudflare-dns.com")
    }

    @Test
    fun `ip literal helper`() {
        assertTrue(IpLiterals.isLiteral("1.2.3.4"))
        assertTrue(IpLiterals.isLiteral("2001:db8::1"))
        assertTrue(!IpLiterals.isLiteral("doh.pub"))
        assertTrue(!IpLiterals.isLiteral("1.2.3"))
        assertTrue(!IpLiterals.isLiteral("::gg"))
    }
}
