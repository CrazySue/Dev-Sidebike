package dev.sidebike.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pre-establish DNS readiness probe: query wire format and response validation.
 * The controller/service integration (probe failure => VPN never established) is covered in
 * `DefaultSidebikeControllerTest.dnsProbeFailureAbortsBeforeVpnIsEstablished`.
 */
class DnsProbeTest {

    @Test
    fun probeQueryIsAWellFormedSingleQuestionQuery() {
        val query = DnsProbe.buildQuery("www.baidu.com")
        val info = DnsWire.inspect(query)!!
        assertFalse("probe query must be a query, not a response", info.isResponse)
        assertEquals(1, info.questionCount)
        assertEquals("www.baidu.com", info.questions[0].name)
        assertEquals(DnsWire.QTYPE_A, info.questions[0].qtype)
        assertEquals(1, info.questions[0].qclass)
        assertTrue("probe query must request recursion", info.flags and 0x0100 != 0)
    }

    @Test
    fun usableResponseValidation() {
        assertFalse(DnsProbe.isUsableResponse(null))
        assertFalse("short buffer", DnsProbe.isUsableResponse(ByteArray(11)))
        assertFalse("QR not set", DnsProbe.isUsableResponse(response(flags = 0x0100, answers = 1)))
        assertFalse("no answers", DnsProbe.isUsableResponse(response(flags = 0x8180, answers = 0)))
        assertFalse("truncated answer", DnsProbe.isUsableResponse(response(flags = 0x8380, answers = 1)))
        assertFalse("SERVFAIL", DnsProbe.isUsableResponse(response(flags = 0x8182, answers = 1)))
        assertFalse("NXDOMAIN", DnsProbe.isUsableResponse(response(flags = 0x8183, answers = 0)))
        assertTrue("normal answer", DnsProbe.isUsableResponse(response(flags = 0x8180, answers = 1)))
        assertTrue("normal answer with 3 records", DnsProbe.isUsableResponse(response(flags = 0x8180, answers = 3)))
    }

    private fun response(flags: Int, answers: Int): ByteArray = ByteArray(DnsWire.HEADER_SIZE).apply {
        DnsWire.writeU16(this, 0, 0x51DB)
        DnsWire.writeU16(this, 2, flags)
        DnsWire.writeU16(this, 4, 1)
        DnsWire.writeU16(this, 6, answers)
        DnsWire.writeU16(this, 8, 0)
        DnsWire.writeU16(this, 10, 0)
    }
}
