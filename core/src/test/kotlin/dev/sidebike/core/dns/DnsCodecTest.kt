package dev.sidebike.core.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCodecTest {

    @Test
    fun `build query and parse roundtrip with edns0`() {
        val query = DnsCodec.buildQuery("github.com", DnsType.A, id = 0x1234, dnssecOk = true)
        val parsed = DnsCodec.parseOrThrow(query)
        assertEquals(0x1234, parsed.id)
        assertFalse(parsed.isResponse)
        assertTrue(parsed.isRecursionDesired)
        assertEquals(1, parsed.questions.size)
        assertEquals("github.com", parsed.questions[0].name)
        assertEquals(DnsType.A, parsed.questions[0].type)
        assertEquals(1, parsed.questions[0].clazz)
        assertNotNull(parsed.edns0)
        assertEquals(1232, parsed.edns0!!.udpPayloadSize)
        assertTrue(parsed.edns0!!.dnssecOk)
    }

    @Test
    fun `parses compressed answer names`() {
        // header(id=1, flags=0x8180, qd=1, an=1) + question(github.com A IN) + answer(ptr 0xC00C A IN ttl=60 rdlen=4 1.2.3.4)
        val bytes = byteArrayOf(
            0x00, 0x01, // id=1
            0x81.toByte(), 0x80.toByte(), // response, RD, RA
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x06, 'g'.code.toByte(), 'i'.code.toByte(), 't'.code.toByte(), 'h'.code.toByte(), 'u'.code.toByte(), 'b'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00,
            0x00, 0x01, 0x00, 0x01,
            0xC0.toByte(), 0x0C,
            0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C,
            0x00, 0x04, 1, 2, 3, 4,
        )
        val parsed = DnsCodec.parseOrThrow(bytes)
        val answers = parsed.ipAnswers()
        assertEquals(1, answers.size)
        assertEquals("1.2.3.4", answers[0].ip)
        assertEquals(60, answers[0].ttlSec)
        assertEquals(4, answers[0].family)
    }

    @Test
    fun `malformed packets return null`() {
        assertNull(DnsCodec.parse(byteArrayOf()))
        assertNull(DnsCodec.parse(byteArrayOf(1, 2, 3)))
        val truncated = DnsCodec.buildQuery("x.com", DnsType.A).copyOf(15)
        assertNull(DnsCodec.parse(truncated))
    }

    @Test
    fun `reply echoes question and answers`() {
        val query = DnsCodec.buildQuery("example.com", DnsType.A, id = 7)
        val parsedQuery = DnsCodec.parseOrThrow(query)
        val reply = DnsCodec.buildReply(
            parsedQuery,
            listOf(IpAnswer("1.2.3.4", 60, 4), IpAnswer("5.6.7.8", 60, 4)),
            rcode = 0,
            maxUdpPayload = 512,
        )
        val parsedReply = DnsCodec.parseOrThrow(reply)
        assertTrue(parsedReply.isResponse)
        assertEquals(7, parsedReply.id)
        assertEquals("example.com", parsedReply.questions[0].name)
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), parsedReply.ipAnswers().map { it.ip })
        assertFalse(parsedReply.isTruncated)
        assertEquals(0, parsedReply.rcode)
        // EDNS0 被回显
        assertNotNull(parsedReply.edns0)
    }

    @Test
    fun `reply trims answers to edns payload size and never sets tc`() {
        val query = DnsCodec.buildQuery("big.example.com", DnsType.A, id = 9, udpPayloadSize = 512)
        val parsedQuery = DnsCodec.parseOrThrow(query)
        val ips = (1..100).map { "10.0.0.$it" }
        val reply = DnsCodec.buildReply(parsedQuery, ips.map { IpAnswer(it, 60, 4) }, maxUdpPayload = 512)
        assertTrue("应答长度必须 <= 512: ${reply.size}", reply.size <= 512)
        val parsedReply = DnsCodec.parseOrThrow(reply)
        assertFalse("raw responder 禁止设置 TC 位", parsedReply.isTruncated)
        assertTrue(parsedReply.ipAnswers().isNotEmpty())
        assertTrue(parsedReply.ipAnswers().size < 100)
    }

    @Test
    fun `reply without edns0 uses 512 byte limit`() {
        val query = DnsCodec.buildQuery("noedns.example.com", DnsType.A, id = 11, edns0 = false)
        val parsedQuery = DnsCodec.parseOrThrow(query)
        assertNull(parsedQuery.edns0)
        val ips = (1..60).map { "10.0.1.$it" }
        val reply = DnsCodec.buildReply(parsedQuery, ips.map { IpAnswer(it, 60, 4) }, maxUdpPayload = 512)
        assertTrue(reply.size <= 512)
        assertFalse(DnsCodec.parseOrThrow(reply).isTruncated)
        assertNull(DnsCodec.parseOrThrow(reply).edns0)
    }

    @Test
    fun `aaaa reply uses 16 byte rdata`() {
        val query = DnsCodec.buildQuery("v6.example.com", DnsType.AAAA, id = 13)
        val parsedQuery = DnsCodec.parseOrThrow(query)
        val reply = DnsCodec.buildReply(parsedQuery, listOf(IpAnswer("2001:db8::1", 30, 6)), maxUdpPayload = 1232)
        val answer = DnsCodec.parseOrThrow(reply).ipAnswers().single()
        assertEquals(6, answer.family)
        assertTrue(answer.ip.contains(':'))
        assertEquals(30, answer.ttlSec)
    }

    @Test
    fun `servfail reply has rcode and no answers`() {
        val query = DnsCodec.buildQuery("fail.example.com", DnsType.A, id = 21)
        val parsed = DnsCodec.parseOrThrow(query)
        val reply = DnsCodec.parseOrThrow(DnsCodec.buildReply(parsed, emptyList(), rcode = 2, maxUdpPayload = 512))
        assertEquals(2, reply.rcode)
        assertTrue(reply.ipAnswers().isEmpty())
    }
}
