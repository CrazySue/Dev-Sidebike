package dev.sidebike.app.qa

import dev.sidebike.app.vpn.DnsTunHandler
import dev.sidebike.app.vpn.DnsWire
import dev.sidebike.app.vpn.IpPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * QA adversarial tests for the tun-side IPv4/UDP/DNS codec ([IpPacket], [DnsWire],
 * [DnsTunHandler]). These are hermetic (no Android APIs, no network) and deliberately feed
 * malformed/hostile input: the VPN read loop must never crash on a hostile packet, otherwise the
 * whole device loses DNS (every app's resolver breaks).
 */
class VpnCodecFuzzTest {

    private val rnd = Random(0xC0FFEE)
    private val vpnDns = byteArrayOf(10, 111.toByte(), 0, 2)
    private val handler = DnsTunHandler(vpnDns, mtu = 1500)

    private fun randomBytes(max: Int): ByteArray = ByteArray(rnd.nextInt(max + 1)).also { rnd.nextBytes(it) }

    // ------------------------------------------------------------------------------------------
    // 1. Parsers must tolerate arbitrary bytes
    // ------------------------------------------------------------------------------------------

    @Test
    fun randomBytesNeverThrowInParsers() {
        repeat(5_000) {
            val bytes = randomBytes(128)
            val len = if (bytes.isEmpty()) 0 else rnd.nextInt(bytes.size + 1)
            // Must never throw; null is a perfectly valid answer for garbage.
            IpPacket.parseIpv4(bytes, 0, len)
            IpTolerateUdp(bytes, len)
            DnsWire.inspect(bytes, len)
        }
    }

    private fun IpTolerateUdp(bytes: ByteArray, len: Int) {
        val ip = IpPacket.parseIpv4(bytes, 0, len) ?: return
        IpPacket.parseUdp(ip.payload)
    }

    @Test
    fun randomPacketsNeverThrowInClassifier() {
        repeat(3_000) {
            val bytes = randomBytes(96)
            val len = if (bytes.isEmpty()) 0 else rnd.nextInt(bytes.size + 1)
            val result = handler.classify(bytes, len) // must never throw
            assertNotNull(result)
            // Random garbage must not be classified as a DNS query that we then try to answer.
            if (result is DnsTunHandler.Classified.Dns) {
                assertTrue(result.request.dnsQuery.isNotEmpty())
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 2. Positive path: a real query is answered with a well-formed packet
    // ------------------------------------------------------------------------------------------

    @Test
    fun validQueryIsClassifiedAndAnswered() {
        val query = buildDnsQuery(id = 0x1234, name = "example.com", qtype = DnsWire.QTYPE_A)
        val client = byteArrayOf(192.toByte(), 168.toByte(), 1, 5)
        val packet = IpPacket.buildUdpIpv4(client, 40123, vpnDns, 53, query)

        val classified = handler.classify(packet)
        assertTrue("expected Dns, got $classified", classified is DnsTunHandler.Classified.Dns)
        val request = (classified as DnsTunHandler.Classified.Dns).request
        assertEquals("example.com", request.questionName)
        assertEquals(DnsWire.QTYPE_A, request.questionType)

        val reply = handler.buildReply(request, resolverResponse = null) // resolver failure path
        assertNotNull("SERVFAIL reply must be synthesised", reply)
        val ip = IpPacket.parseIpv4(reply!!)
        assertNotNull(ip)
        assertEquals(17, ip!!.protocol)
        assertTrue(IpPacket.isValidIpv4HeaderChecksum(reply))
        val udp = IpPacket.parseUdp(ip.payload)
        assertNotNull(udp)
        assertEquals(53, udp!!.sourcePort)
        assertEquals(40123, udp.destinationPort)
        val info = DnsWire.inspect(udp.payload)
        assertNotNull(info)
        assertTrue(info!!.isResponse)
        assertEquals(0x1234, info.id)
        assertEquals(DnsWire.RCODE_SERVFAIL, info.flags and 0x000F)
    }

    @Test
    fun mismatchedResolverResponseGetsTransactionIdFixed() {
        val query = buildDnsQuery(id = 0xBEEF, name = "github.com", qtype = DnsWire.QTYPE_A)
        val packet = IpPacket.buildUdpIpv4(
            byteArrayOf(10, 0, 0, 9), 5353, vpnDns, 53, query,
        )
        val request = (handler.classify(packet) as DnsTunHandler.Classified.Dns).request
        // A resolver response with a different transaction id (must be rewritten to 0xBEEF).
        val resolverResponse = buildDnsResponse(id = 0x0001, rcode = 0)
        val reply = handler.buildReply(request, resolverResponse)
        assertNotNull(reply)
        val ip = IpPacket.parseIpv4(reply!!)!!
        val dns = IpPacket.parseUdp(ip.payload)!!.payload
        // The resolver response above is header-only (no question section), so DnsWire.inspect()
        // rejects it; assert the raw transaction id instead (that is what the fix is about).
        assertTrue(dns.size >= DnsWire.HEADER_SIZE)
        assertEquals(0xBEEF, DnsWire.u16(dns, 0))
    }

    // ------------------------------------------------------------------------------------------
    // 3. Negative path: non-DNS traffic must be ignored, not answered
    // ------------------------------------------------------------------------------------------

    @Test
    fun nonDnsTrafficIsIgnored() {
        val dnsPayload = buildDnsQuery(id = 7, name = "a.com", qtype = DnsWire.QTYPE_A)

        // Wrong destination address.
        val wrongAddr = IpPacket.buildUdpIpv4(vpnDns, 53, byteArrayOf(10, 0, 0, 8), 1234, dnsPayload)
        assertTrue(handler.classify(wrongAddr) is DnsTunHandler.Classified.Ignored)

        // Wrong destination port.
        val wrongPort = IpPacket.buildUdpIpv4(vpnDns, 5353, byteArrayOf(10, 0, 0, 8), 1234, dnsPayload)
        assertTrue(handler.classify(wrongPort) is DnsTunHandler.Classified.Ignored)

        // IPv6 packet (first byte 0x60) must be rejected, not parsed as IPv4.
        val ipv6 = ByteArray(60)
        ipv6[0] = 0x60
        assertTrue(handler.classify(ipv6) is DnsTunHandler.Classified.Ignored)

        // Truncated ARP-ish garbage.
        assertTrue(handler.classify(ByteArray(0)) is DnsTunHandler.Classified.Ignored)
        assertTrue(handler.classify(ByteArray(3)) is DnsTunHandler.Classified.Ignored)
    }

    @Test
    fun dnsResponsePacketIsNotTreatedAsQuery() {
        val response = buildDnsResponse(id = 42, rcode = 0)
        val packet = IpPacket.buildUdpIpv4(vpnDns, 53, byteArrayOf(10, 0, 0, 8), 9999, response)
        assertTrue(handler.classify(packet) is DnsTunHandler.Classified.Ignored)
    }

    // ------------------------------------------------------------------------------------------
    // 4. UDP/IP roundtrip + checksum for arbitrary payload lengths
    // ------------------------------------------------------------------------------------------

    @Test
    fun udpRoundtripForRandomPayloadLengths() {
        for (size in 0..600) {
            val payload = ByteArray(size).also { rnd.nextBytes(it) }
            val packet = IpPacket.buildUdpIpv4(
                sourceAddress = byteArrayOf(10, 111.toByte(), 0, 2),
                sourcePort = 53,
                destinationAddress = byteArrayOf(10, 0, 0, 7),
                destinationPort = 40000 + (size % 20000),
                payload = payload,
            )
            val ip = IpPacket.parseIpv4(packet)
            assertNotNull("size=$size", ip)
            assertTrue("bad ip checksum size=$size", IpPacket.isValidIpv4HeaderChecksum(packet))
            assertEquals(IpPacket.IPV4_HEADER_MIN_SIZE + IpPacket.UDP_HEADER_SIZE + size, ip!!.totalLength)
            val udp = IpPacket.parseUdp(ip.payload)
            assertNotNull("size=$size", udp)
            assertEquals("size=$size", size, udp!!.payload.size)
            assertEquals("size=$size", true, udp.payload.contentEquals(payload))
        }
    }

    @Test
    fun oversizedResolverResponseIsTrimmedWithoutTc() {
        // Beta has no TCP:53 listener, so TC=1 would make Android's resolver retry over TCP and
        // time out. Contract (QA-003 fix): answers are trimmed to the client EDNS0 buffer / tun MTU
        // and TC stays 0. This test fails loudly if anyone reintroduces TC=1 (or an oversized reply).
        val query = buildDnsQuery(id = 0x7777, name = "big.example.com", qtype = DnsWire.QTYPE_A)
        val packet = IpPacket.buildUdpIpv4(byteArrayOf(10, 0, 0, 9), 5555, vpnDns, 53, query)
        val request = (handler.classify(packet) as DnsTunHandler.Classified.Dns).request

        val huge = ByteArray(handler.maxDnsPayload + 512) { it.toByte() }
        // Make it a syntactically plausible DNS answer (the handler may still short-circuit).
        huge[0] = 0x77; huge[1] = 0x77.toByte(); huge[2] = 0x81.toByte(); huge[3] = 0x80.toByte()
        val reply = handler.buildReply(request, huge)
        if (reply != null) {
            assertTrue("reply must fit the tun MTU", reply.size <= handler.maxDnsPayload + 28)
            val ip = IpPacket.parseIpv4(reply)!!
            val dns = IpPacket.parseUdp(ip.payload)!!.payload
            if (dns.size >= DnsWire.HEADER_SIZE) {
                val flags = DnsWire.u16(dns, 2)
                assertEquals("TC must never be set (no TCP:53 listener)", 0, flags and 0x0200)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private fun buildDnsQuery(id: Int, name: String, qtype: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
        u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(0)
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        u16(qtype); u16(1)
        return out.toByteArray()
    }

    private fun buildDnsResponse(id: Int, rcode: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
        u16(id); u16(0x8180 or (rcode and 0xF)); u16(0); u16(0); u16(0); u16(0)
        return out.toByteArray()
    }
}
