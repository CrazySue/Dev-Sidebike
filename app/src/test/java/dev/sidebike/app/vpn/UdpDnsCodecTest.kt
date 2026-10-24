package dev.sidebike.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the tun DNS codec: query classification, response packet construction
 * (addresses/ports/checksums), DNS transaction-ID repair, SERVFAIL/FORMERR synthesis, EDNS0
 * queries, multi-question queries and malformed/truncated input.
 */
class UdpDnsCodecTest {

    private val vpnDns = byteArrayOf(10, 111, 0, 2)
    private val client = byteArrayOf(192.toByte(), 168.toByte(), 1, 77)
    private val handler = DnsTunHandler(vpnDnsAddress = vpnDns)

    // ---------------------------------------------------------------- classification

    @Test
    fun classifyValidDnsQuery() {
        val query = dnsQuery(id = 0xBEEF, name = "example.com", qtype = DnsWire.QTYPE_A)
        val packet = ipv4UdpPacket(query, dstAddress = vpnDns, srcPort = 41234, dstPort = 53)

        val classified = handler.classify(packet) as DnsTunHandler.Classified.Dns
        val request = classified.request
        assertEquals("example.com", request.questionName)
        assertEquals(DnsWire.QTYPE_A, request.questionType)
        assertEquals(41234, request.clientPort)
        assertArrayEquals(client, request.clientAddress)
        assertArrayEquals(vpnDns, request.serverAddress)
        assertEquals(53, request.serverPort)
        assertArrayEquals(query, request.dnsQuery)
        assertNull(request.forcedRcode)
    }

    @Test
    fun classifyEdns0Query() {
        val query = dnsQuery(id = 1, name = "github.com", edns = true)
        val packet = ipv4UdpPacket(query, dstAddress = vpnDns, dstPort = 53)
        val info = DnsWire.inspect(query)!!
        assertTrue("EDNS0 queries are forwarded as opaque wire data", info.hasEdns0)

        val classified = handler.classify(packet) as DnsTunHandler.Classified.Dns
        assertNull(classified.request.forcedRcode)
    }

    @Test
    fun classifyIgnoresOtherDestinationsAndPorts() {
        val query = dnsQuery(id = 2, name = "example.com")
        assertIgnored(ipv4UdpPacket(query, dstAddress = byteArrayOf(10, 111, 0, 9), dstPort = 53))
        assertIgnored(ipv4UdpPacket(query, dstAddress = vpnDns, dstPort = 80))
        assertIgnored(ipv4UdpPacket(query, dstAddress = vpnDns, dstPort = 443))
        // Source port is irrelevant; destination must be 53.
        val sourcePort53 = ipv4UdpPacket(query, dstAddress = vpnDns, srcPort = 53, dstPort = 53)
        assertNotNull(handler.classify(sourcePort53) as? DnsTunHandler.Classified.Dns)
    }

    @Test
    fun classifyIgnoresNonUdpAndNonIpv4() {
        assertIgnored(ByteArray(0))
        assertIgnored(ByteArray(3))
        assertIgnored(ByteArray(40) { 0x60.toByte() }) // version 6 nibble

        val tcpLike = ipv4UdpPacket(dnsQuery(id = 3, name = "example.com"), dstAddress = vpnDns).also {
            it[9] = 6 // protocol = TCP
        }
        assertIgnored(tcpLike)
    }

    @Test
    fun classifyIgnoresMalformedDns() {
        // Header only, QDCOUNT = 0.
        assertIgnored(ipv4UdpPacket(ByteArray(12), dstAddress = vpnDns, dstPort = 53))
        // Truncated question.
        val query = dnsQuery(id = 4, name = "example.com")
        assertIgnored(ipv4UdpPacket(query.copyOfRange(0, 15), dstAddress = vpnDns, dstPort = 53))
        assertIgnored(ipv4UdpPacket(ByteArray(11), dstAddress = vpnDns, dstPort = 53))
    }

    @Test
    fun classifyIgnoresDnsResponses() {
        val query = dnsQuery(id = 5, name = "example.com")
        query[2] = (query[2].toInt() or 0x80).toByte() // set QR bit
        assertIgnored(ipv4UdpPacket(query, dstAddress = vpnDns, dstPort = 53))
    }

    @Test
    fun classifyMultiQuestionQueryYieldsFormerrInsteadOfCrash() {
        val multi = dnsQueryWithTwoQuestions(id = 6)
        val classified = handler.classify(ipv4UdpPacket(multi, dstAddress = vpnDns, dstPort = 53))
            as DnsTunHandler.Classified.Dns
        assertEquals(DnsWire.RCODE_FORMERR, classified.request.forcedRcode)
    }

    // ---------------------------------------------------------------- reply building

    @Test
    fun buildReplyProducesCorrectIpAndUdpPacket() {
        val query = dnsQuery(id = 0xCAFE, name = "example.com")
        val request = (handler.classify(ipv4UdpPacket(query, dstAddress = vpnDns, dstPort = 53))
            as DnsTunHandler.Classified.Dns).request
        val dnsResponse = dnsResponseFor(query, answerCount = 1)

        val packet = handler.buildReply(request, dnsResponse)!!
        val ip = IpPacket.parseIpv4(packet)!!
        // Reply source = the in-VPN DNS address; reply destination = the client.
        assertArrayEquals(vpnDns, ip.sourceAddress)
        assertArrayEquals(client, ip.destinationAddress)
        assertTrue(IpPacket.isValidIpv4HeaderChecksum(packet))
        val udp = IpPacket.parseUdp(ip.payload)!!
        assertEquals(53, udp.sourcePort)
        assertEquals(41234, udp.destinationPort)
        assertArrayEquals(dnsResponse, udp.payload)
        assertEquals(0xFFFF, onesComplementSumWithPseudoHeader(ip.sourceAddress, ip.destinationAddress, ip.payload))
    }

    @Test
    fun buildReplyRepairsMismatchedTransactionId() {
        val query = dnsQuery(id = 0x1234, name = "example.com")
        val request = (handler.classify(ipv4UdpPacket(query, dstAddress = vpnDns))
            as DnsTunHandler.Classified.Dns).request
        val wrongIdResponse = dnsResponseFor(query, answerCount = 0).also { it[0] = 0; it[1] = 0 }

        val packet = handler.buildReply(request, wrongIdResponse)!!
        val dns = IpPacket.parseUdp(IpPacket.parseIpv4(packet)!!.payload)!!.payload
        assertEquals(0x1234, DnsWire.u16(dns, 0))
        assertEquals(0x1234, DnsWire.u16(dns, 0))
    }

    @Test
    fun buildReplySynthesisesServfailWhenResolverFails() {
        val query = dnsQuery(id = 0x4321, name = "example.com")
        val request = (handler.classify(ipv4UdpPacket(query, dstAddress = vpnDns))
            as DnsTunHandler.Classified.Dns).request

        val packet = handler.buildReply(request, null)!!
        val dns = IpPacket.parseUdp(IpPacket.parseIpv4(packet)!!.payload)!!.payload
        assertEquals(0x4321, DnsWire.u16(dns, 0))
        assertEquals(DnsWire.RCODE_SERVFAIL, DnsWire.u16(dns, 2) and 0x0F)
        assertTrue("QR must be set on a reply", DnsWire.u16(dns, 2) and 0x8000 != 0)
        assertTrue(IpPacket.isValidIpv4HeaderChecksum(packet))
    }

    @Test
    fun buildReplyFormerrForMultiQuestion() {
        val multi = dnsQueryWithTwoQuestions(id = 7)
        val request = (handler.classify(ipv4UdpPacket(multi, dstAddress = vpnDns))
            as DnsTunHandler.Classified.Dns).request
        val dns = IpPacket.parseUdp(IpPacket.parseIpv4(handler.buildReply(request, null)!!)!!.payload)!!.payload
        assertEquals(DnsWire.RCODE_FORMERR, DnsWire.u16(dns, 2) and 0x0F)
    }

    @Test
    fun buildReplyToEdns0QueryContainsOptRecord() {
        val query = dnsQuery(id = 8, name = "example.com", edns = true)
        val request = (handler.classify(ipv4UdpPacket(query, dstAddress = vpnDns))
            as DnsTunHandler.Classified.Dns).request
        val dns = IpPacket.parseUdp(IpPacket.parseIpv4(handler.buildReply(request, null)!!)!!.payload)!!.payload
        assertEquals(1, DnsWire.u16(dns, 10)) // ARCOUNT
        // OPT record must follow the question section; type = 41.
        val info = DnsWire.inspect(query)!!
        assertEquals(41, DnsWire.u16(dns, info.questionSectionEnd + 1))
    }

    @Test
    fun oversizedResolverResponseIsTruncatedNotDropped() {
        val query = dnsQuery(id = 9, name = "large.example.com")
        val request = (handler.classify(ipv4UdpPacket(query, dstAddress = vpnDns))
            as DnsTunHandler.Classified.Dns).request
        val oversized = ByteArray(handler.maxDnsPayload + 200)
        System.arraycopy(query, 0, oversized, 0, 2) // same transaction id

        val packet = handler.buildReply(request, oversized)!!
        val dns = IpPacket.parseUdp(IpPacket.parseIpv4(packet)!!.payload)!!.payload
        assertTrue("reply must fit the MTU", dns.size <= handler.maxDnsPayload)
        assertTrue("TC bit must be set", DnsWire.u16(dns, 2) and 0x0200 != 0)
        assertEquals(9, DnsWire.u16(dns, 0))
    }

    @Test
    fun shortResolverResponseFallsBackToServfail() {
        val query = dnsQuery(id = 10, name = "example.com")
        val request = (handler.classify(ipv4UdpPacket(query, dstAddress = vpnDns))
            as DnsTunHandler.Classified.Dns).request
        val dns = IpPacket.parseUdp(IpPacket.parseIpv4(handler.buildReply(request, ByteArray(4))!!)!!.payload)!!.payload
        assertEquals(DnsWire.RCODE_SERVFAIL, DnsWire.u16(dns, 2) and 0x0F)
    }

    @Test
    fun dnsWireRejectsCompressionLoop() {
        val loop = ByteArray(12 + 2 + 4)
        // QDCOUNT = 1, question name = pointer to itself at offset 12 (0xC00C).
        loop[4] = 0
        loop[5] = 1
        loop[12] = 0xC0.toByte()
        loop[13] = 0x0C
        assertNull(DnsWire.inspect(loop))
    }

    // ---------------------------------------------------------------- helpers

    private fun assertIgnored(packet: ByteArray) {
        val classified = handler.classify(packet)
        assertTrue(
            "expected Ignored but was $classified",
            classified is DnsTunHandler.Classified.Ignored,
        )
    }

    private fun onesComplementSumWithPseudoHeader(source: ByteArray, destination: ByteArray, udp: ByteArray): Int {
        var sum = 0L
        fun addWords(bytes: ByteArray) {
            var i = 0
            while (i + 1 < bytes.size) {
                sum += (((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)).toLong()
                i += 2
            }
            if (i < bytes.size) sum += ((bytes[i].toInt() and 0xFF) shl 8).toLong()
        }
        addWords(source)
        addWords(destination)
        sum += IpPacket.PROTOCOL_UDP.toLong()
        sum += udp.size.toLong()
        addWords(udp)
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt() and 0xFFFF
    }

    /** Builds an IPv4/UDP packet for a DNS query; usable in tests as the tun input. */
    private fun ipv4UdpPacket(
        payload: ByteArray,
        dstAddress: ByteArray,
        srcAddress: ByteArray = client,
        srcPort: Int = 41234,
        dstPort: Int = 53,
    ): ByteArray = IpPacket.buildUdpIpv4(
        sourceAddress = srcAddress,
        sourcePort = srcPort,
        destinationAddress = dstAddress,
        destinationPort = dstPort,
        payload = payload,
        identification = 0x42,
    )

    private fun dnsQueryWithTwoQuestions(id: Int): ByteArray {
        val header = dnsHeader(id = id, qdCount = 2)
        val first = encodeQuestion("a.example.com", DnsWire.QTYPE_A)
        val second = encodeQuestion("b.example.com", DnsWire.QTYPE_A)
        return header + first + second
    }

    private fun dnsResponseFor(query: ByteArray, answerCount: Int): ByteArray {
        val info = DnsWire.inspect(query)!!
        val response = ByteArray(query.size + answerCount * 16)
        System.arraycopy(query, 0, response, 0, query.size)
        DnsWire.writeU16(response, 2, 0x8180) // QR=1, RD=1, RA=1, RCODE=0
        DnsWire.writeU16(response, 6, answerCount)
        // A minimal A-record answer body: name pointer, type A, class IN, ttl, rdlength, 4-byte RDATA.
        if (answerCount > 0) {
            var offset = query.size
            response[offset++] = 0xC0.toByte()
            response[offset++] = 0x0C
            DnsWire.writeU16(response, offset, DnsWire.QTYPE_A); offset += 2
            DnsWire.writeU16(response, offset, 1); offset += 2 // class IN
            DnsWire.writeU16(response, offset, 0); offset += 2 // TTL high 16 bits
            DnsWire.writeU16(response, offset, 60); offset += 2 // TTL low 16 bits
            DnsWire.writeU16(response, offset, 4); offset += 2 // RDLENGTH
            response[offset++] = 93
            response[offset++] = 184.toByte()
            response[offset++] = 216.toByte()
            response[offset] = 34
        }
        return if (answerCount == 0) response.copyOf(info.questionSectionEnd) else response
    }
}

/** Builds a DNS query wire message (header + one question + optional EDNS0 OPT record). */
internal fun dnsQuery(
    id: Int,
    name: String,
    qtype: Int = DnsWire.QTYPE_A,
    edns: Boolean = false,
): ByteArray {
    val question = encodeQuestion(name, qtype)
    val header = dnsHeader(id = id, qdCount = 1, arCount = if (edns) 1 else 0)
    return if (edns) header + question + emptyOptRecord() else header + question
}

internal fun dnsHeader(id: Int, qdCount: Int, arCount: Int = 0): ByteArray {
    val header = ByteArray(DnsWire.HEADER_SIZE)
    DnsWire.writeU16(header, 0, id)
    DnsWire.writeU16(header, 2, 0x0100) // RD
    DnsWire.writeU16(header, 4, qdCount)
    DnsWire.writeU16(header, 6, 0)
    DnsWire.writeU16(header, 8, 0)
    DnsWire.writeU16(header, 10, arCount)
    return header
}

internal fun encodeQuestion(name: String, qtype: Int): ByteArray {
    val out = ArrayList<Byte>()
    for (label in name.split('.')) {
        out.add(label.length.toByte())
        for (char in label) out.add(char.code.toByte())
    }
    out.add(0)
    val result = ByteArray(out.size + 4)
    out.forEachIndexed { index, byte -> result[index] = byte }
    val offset = out.size
    DnsWire.writeU16(result, offset, qtype)
    DnsWire.writeU16(result, offset + 2, 1) // class IN
    return result
}

internal fun emptyOptRecord(): ByteArray = byteArrayOf(
    0x00, // root name
    0x00, 0x29, // type OPT
    0x04, 0xD0.toByte(), // UDP payload size 1232
    0x00, 0x00, 0x00, 0x00, // ttl
    0x00, 0x00, // rdlength
)
