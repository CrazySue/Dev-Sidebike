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

    @Test
    fun extractIpv4AnswersReadsARecordsInOrder() {
        val query = DnsProbe.buildQuery("github.com")
        val response = buildAResponse(query, listOf("20.205.243.166", "20.205.243.165"))
        assertEquals(
            listOf("20.205.243.166", "20.205.243.165"),
            DnsProbe.extractIpv4Answers(response),
        )
        assertEquals(emptyList<String>(), DnsProbe.extractIpv4Answers(null))
        assertEquals(emptyList<String>(), DnsProbe.extractIpv4Answers(ByteArray(3)))
        // A query (not a response) yields no answer addresses.
        assertEquals(emptyList<String>(), DnsProbe.extractIpv4Answers(query))
    }

    private fun buildAResponse(query: ByteArray, ips: List<String>): ByteArray {
        val info = DnsWire.inspect(query)!!
        val questionEnd = info.questionSectionEnd
        val output = java.io.ByteArrayOutputStream()
        val header = ByteArray(DnsWire.HEADER_SIZE)
        DnsWire.writeU16(header, 0, DnsWire.u16(query, 0))
        DnsWire.writeU16(header, 2, 0x8180)
        DnsWire.writeU16(header, 4, 1)
        DnsWire.writeU16(header, 6, ips.size)
        output.write(header)
        output.write(query, DnsWire.HEADER_SIZE, questionEnd - DnsWire.HEADER_SIZE)
        for (ip in ips) {
            output.write(0xC0)
            output.write(0x0C)
            writeU16(output, DnsWire.QTYPE_A)
            writeU16(output, 1) // IN
            writeU16(output, 0) // TTL hi
            writeU16(output, 60) // TTL lo
            writeU16(output, 4) // RDLENGTH
            for (part in ip.split('.')) output.write(part.toInt())
        }
        return output.toByteArray()
    }

    private fun writeU16(output: java.io.ByteArrayOutputStream, value: Int) {
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
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
