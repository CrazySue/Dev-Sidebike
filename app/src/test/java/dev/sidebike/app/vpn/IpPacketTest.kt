package dev.sidebike.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure-Kotlin IPv4/UDP codec used by the tun DNS interceptor.
 *
 * Covers: build/parse roundtrip, header checksums (independent verification), odd-length payloads,
 * truncation/malformed input, fragmentation rejection and argument validation.
 */
class IpPacketTest {

    private val src = byteArrayOf(10, 111, 0, 2)
    private val dst = byteArrayOf(10, 0, 0, 7)

    @Test
    fun buildThenParseRoundtrip() {
        val payload = "hello-tun".toByteArray()
        val packet = IpPacket.buildUdpIpv4(src, 53, dst, 40123, payload, ttl = 64, identification = 0x1234)

        val ip = IpPacket.parseIpv4(packet)!!
        assertEquals(IpPacket.IPV4_HEADER_MIN_SIZE, ip.headerLength)
        assertEquals(IpPacket.IPV4_HEADER_MIN_SIZE + IpPacket.UDP_HEADER_SIZE + payload.size, ip.totalLength)
        assertEquals(IpPacket.PROTOCOL_UDP, ip.protocol)
        assertEquals(64, ip.ttl)
        assertArrayEquals(src, ip.sourceAddress)
        assertArrayEquals(dst, ip.destinationAddress)

        val udp = IpPacket.parseUdp(ip.payload)!!
        assertEquals(53, udp.sourcePort)
        assertEquals(40123, udp.destinationPort)
        assertEquals(IpPacket.UDP_HEADER_SIZE + payload.size, udp.lengthField)
        assertArrayEquals(payload, udp.payload)
    }

    @Test
    fun ipv4HeaderChecksumIsValid() {
        val packet = IpPacket.buildUdpIpv4(src, 53, dst, 5353, ByteArray(24) { it.toByte() }, identification = 7)
        assertTrue(IpPacket.isValidIpv4HeaderChecksum(packet))
        // Checksumming a valid header (checksum field included) must yield zero.
        assertEquals(0, IpPacket.checksum(packet, 0, IpPacket.IPV4_HEADER_MIN_SIZE))
    }

    @Test
    fun corruptedIpv4HeaderChecksumIsDetected() {
        val packet = IpPacket.buildUdpIpv4(src, 53, dst, 5353, ByteArray(8), identification = 9)
        packet[8] = (packet[8] + 1).toByte() // flip a TTL bit without re-computing the checksum
        assertFalse(IpPacket.isValidIpv4HeaderChecksum(packet))
    }

    @Test
    fun udpChecksumIsValidIncludingPseudoHeader() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7) // odd length exercises the zero-pad path
        val packet = IpPacket.buildUdpIpv4(src, 53, dst, 5353, payload, identification = 11)

        val ip = IpPacket.parseIpv4(packet)!!
        val udp = IpPacket.parseUdp(ip.payload)!!
        assertNotEquals(0, udp.checksum)
        // Sum over pseudo-header + UDP datagram (checksum included) must be all-ones.
        assertEquals(0xFFFF, onesComplementSumWithPseudoHeader(ip.sourceAddress, ip.destinationAddress, ip.payload))
    }

    @Test
    fun udpChecksumChangesWhenPayloadChanges() {
        val first = IpPacket.buildUdpIpv4(src, 53, dst, 5353, byteArrayOf(1, 2, 3), identification = 1)
        val second = IpPacket.buildUdpIpv4(src, 53, dst, 5353, byteArrayOf(1, 2, 4), identification = 1)
        assertNotEquals(udpChecksumField(first), udpChecksumField(second))
    }

    @Test
    fun emptyPayloadIsSupported() {
        val packet = IpPacket.buildUdpIpv4(src, 53, dst, 5353, ByteArray(0), identification = 3)
        val ip = IpPacket.parseIpv4(packet)!!
        val udp = IpPacket.parseUdp(ip.payload)!!
        assertEquals(0, udp.payload.size)
        assertEquals(0xFFFF, onesComplementSumWithPseudoHeader(ip.sourceAddress, ip.destinationAddress, ip.payload))
    }

    @Test
    fun rejectsTruncatedIpv4Packets() {
        assertNull(IpPacket.parseIpv4(ByteArray(19)))
        val packet = IpPacket.buildUdpIpv4(src, 53, dst, 5353, ByteArray(10), identification = 1)
        assertNull(IpPacket.parseIpv4(packet, 0, 19))
        assertNull(IpPacket.parseIpv4(packet, 0, packet.size - 1))
    }

    @Test
    fun rejectsNonIpv4Version() {
        val packet = IpPacket.buildUdpIpv4(src, 53, dst, 5353, ByteArray(4), identification = 1)
        packet[0] = 0x65 // version 6, IHL 5
        assertNull(IpPacket.parseIpv4(packet))
    }

    @Test
    fun rejectsInvalidIhl() {
        val packet = IpPacket.buildUdpIpv4(src, 53, dst, 5353, ByteArray(4), identification = 1)
        packet[0] = 0x44 // version 4, IHL 4 (16 bytes, too short)
        assertNull(IpPacket.parseIpv4(packet))
    }

    @Test
    fun rejectsTotalLengthSmallerThanHeader() {
        val packet = IpPacket.buildUdpIpv4(src, 53, dst, 5353, ByteArray(4), identification = 1)
        packet[2] = 0 // total length = 8 < header length
        packet[3] = 8
        assertNull(IpPacket.parseIpv4(packet))
    }

    @Test
    fun rejectsFragmentedPackets() {
        val packet = IpPacket.buildUdpIpv4(src, 53, dst, 5353, ByteArray(4), identification = 1)
        // Non-first fragment: fragment offset = 1.
        packet[6] = 0
        packet[7] = 1
        assertNull(IpPacket.parseIpv4(packet))

        // First fragment with MF set: reassembly would be required.
        val moreFragments = IpPacket.buildUdpIpv4(src, 53, dst, 5353, ByteArray(4), identification = 1)
        moreFragments[6] = 0x20.toByte()
        moreFragments[7] = 0
        assertNull(IpPacket.parseIpv4(moreFragments))
    }

    @Test
    fun parseIpv4HonoursOffsetAndLength() {
        val packet = IpPacket.buildUdpIpv4(src, 53, dst, 5353, byteArrayOf(9, 9), identification = 5)
        val padded = ByteArray(packet.size + 8) { 0x7F }
        System.arraycopy(packet, 0, padded, 4, packet.size)
        val ip = IpPacket.parseIpv4(padded, offset = 4, length = packet.size)!!
        assertArrayEquals(src, ip.sourceAddress)
        assertArrayEquals(byteArrayOf(9, 9), IpPacket.parseUdp(ip.payload)!!.payload)
    }

    @Test
    fun rejectsTruncatedUdpDatagrams() {
        assertNull(IpPacket.parseUdp(ByteArray(7)))
        val malformedLength = ByteArray(8).also { it[4] = 0; it[5] = 4 }
        assertNull(IpPacket.parseUdp(malformedLength))
        val tooLong = ByteArray(8).also { it[4] = 0; it[5] = 9 }
        assertNull(IpPacket.parseUdp(tooLong))
    }

    @Test
    fun rejectsWrongAddressSizes() {
        try {
            IpPacket.buildUdpIpv4(ByteArray(3), 53, dst, 53, ByteArray(0))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            IpPacket.buildUdpIpv4(src, 53, ByteArray(16), 53, ByteArray(0))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    private fun udpChecksumField(packet: ByteArray): Int {
        val offset = IpPacket.IPV4_HEADER_MIN_SIZE + 6
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }

    /** Independent reference implementation: ones-complement sum over pseudo-header + UDP. */
    private fun onesComplementSumWithPseudoHeader(
        source: ByteArray,
        destination: ByteArray,
        udp: ByteArray,
    ): Int {
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
}
