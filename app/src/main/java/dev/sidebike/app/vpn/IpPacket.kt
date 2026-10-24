package dev.sidebike.app.vpn

/**
 * Minimal IPv4 + UDP wire-format codec used by the tun DNS interceptor.
 *
 * The VPN only routes `config.dns.vpnDnsAddress/32`, so the tun interface receives exactly one
 * category of traffic: IPv4/UDP datagrams addressed to the in-VPN DNS resolver. This codec
 * implements just enough of RFC 791 / RFC 768 to:
 *
 *  - parse those inbound datagrams (defensively — anything malformed is rejected, never thrown), and
 *  - build the matching IPv4/UDP reply with a correct IPv4 header checksum and a correct UDP
 *    checksum over the IPv4 pseudo-header (RFC 768 §3.1).
 *
 * Everything here is pure Kotlin/JVM (no Android APIs, no external network) so it can be unit
 * tested with plain JUnit. All parsing functions are total: they return `null` for truncated or
 * malformed input instead of throwing.
 */
internal object IpPacket {

    const val IPV4_VERSION: Int = 4
    const val PROTOCOL_UDP: Int = 17

    /** Smallest possible IPv4 header (IHL = 5). */
    const val IPV4_HEADER_MIN_SIZE: Int = 20

    /** Largest possible IPv4 header (IHL = 15, i.e. with options). */
    const val IPV4_HEADER_MAX_SIZE: Int = 60

    const val UDP_HEADER_SIZE: Int = 8

    /** Offset of the IPv4 header checksum field. */
    private const val IP_CHECKSUM_OFFSET = 10

    /** Offset of the UDP checksum field, relative to the start of the UDP header. */
    private const val UDP_CHECKSUM_OFFSET = 6

    private const val FLAG_DF = 0x4000

    /**
     * Parsed view of an IPv4 datagram.
     *
     * [sourceAddress]/[destinationAddress] are freshly copied 4-byte arrays; [payload] is a freshly
     * copied array containing exactly `totalLength - headerLength` bytes (no Ethernet framing, no
     * trailing bytes), so callers never alias the tun read buffer.
     */
    data class Ipv4Packet(
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val protocol: Int,
        val ttl: Int,
        val headerLength: Int,
        val totalLength: Int,
        val payload: ByteArray,
    )

    /** Parsed view of a UDP datagram (header + [payload]); [payload] is a fresh copy. */
    data class UdpDatagram(
        val sourcePort: Int,
        val destinationPort: Int,
        val lengthField: Int,
        val checksum: Int,
        val payload: ByteArray,
    )

    /**
     * Parses an IPv4 datagram from [buffer].
     *
     * @param offset first byte of the IPv4 header inside [buffer].
     * @param length number of meaningful bytes available starting at [offset].
     * @return parsed packet, or `null` when the packet is truncated, not IPv4, uses an invalid
     *   IHL/total-length combination, or is a non-first/fragmented datagram (DNS queries are never
     *   fragmented in practice and reassembly is explicitly out of scope for the interceptor).
     */
    fun parseIpv4(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Ipv4Packet? {
        if (offset < 0 || length < IPV4_HEADER_MIN_SIZE || offset + length > buffer.size) return null

        val versionAndIhl = buffer[offset].toInt() and 0xFF
        if ((versionAndIhl ushr 4) != IPV4_VERSION) return null

        val headerLength = (versionAndIhl and 0x0F) * 4
        if (headerLength < IPV4_HEADER_MIN_SIZE || headerLength > IPV4_HEADER_MAX_SIZE) return null
        if (headerLength > length) return null

        val totalLength = readU16(buffer, offset + 2)
        if (totalLength < headerLength || totalLength > length) return null

        // Reject fragmented datagrams: reassembly is out of scope and a partial DNS query can never
        // be answered correctly.
        val flagsAndFragmentOffset = readU16(buffer, offset + 6)
        val moreFragments = flagsAndFragmentOffset and 0x2000 != 0
        val fragmentOffset = flagsAndFragmentOffset and 0x1FFF
        if (fragmentOffset != 0 || moreFragments) return null

        val protocol = buffer[offset + 9].toInt() and 0xFF
        val ttl = buffer[offset + 8].toInt() and 0xFF
        val source = buffer.copyOfRange(offset + 12, offset + 16)
        val destination = buffer.copyOfRange(offset + 16, offset + 20)
        val payload = buffer.copyOfRange(offset + headerLength, offset + totalLength)

        return Ipv4Packet(
            sourceAddress = source,
            destinationAddress = destination,
            protocol = protocol,
            ttl = ttl,
            headerLength = headerLength,
            totalLength = totalLength,
            payload = payload,
        )
    }

    /**
     * Parses a UDP datagram (the payload of an IPv4 packet with protocol 17).
     *
     * Returns `null` for truncated headers, a `length` field smaller than the 8-byte header or
     * larger than the available bytes.
     */
    fun parseUdp(datagram: ByteArray): UdpDatagram? {
        if (datagram.size < UDP_HEADER_SIZE) return null
        val sourcePort = readU16(datagram, 0)
        val destinationPort = readU16(datagram, 2)
        val lengthField = readU16(datagram, 4)
        if (lengthField < UDP_HEADER_SIZE || lengthField > datagram.size) return null
        val checksum = readU16(datagram, UDP_CHECKSUM_OFFSET)
        val payload = datagram.copyOfRange(UDP_HEADER_SIZE, lengthField)
        return UdpDatagram(sourcePort, destinationPort, lengthField, checksum, payload)
    }

    /**
     * Builds a complete IPv4 packet carrying one UDP datagram (no IP options, DF set, TTL [ttl]).
     *
     * Both checksums are computed here: the IPv4 header checksum and the UDP checksum over the
     * IPv4 pseudo-header. Per RFC 768 a computed UDP checksum of `0x0000` is transmitted as
     * `0xFFFF` ("all ones") so it is not confused with "checksum not computed".
     *
     * @throws IllegalArgumentException if the resulting packet would exceed the IPv4 maximum size.
     */
    fun buildUdpIpv4(
        sourceAddress: ByteArray,
        sourcePort: Int,
        destinationAddress: ByteArray,
        destinationPort: Int,
        payload: ByteArray,
        ttl: Int = 64,
        identification: Int = nextIdentification(),
    ): ByteArray {
        require(sourceAddress.size == 4) { "IPv4 source address must be 4 bytes" }
        require(destinationAddress.size == 4) { "IPv4 destination address must be 4 bytes" }
        require(sourcePort in 0..0xFFFF) { "source port out of range: $sourcePort" }
        require(destinationPort in 0..0xFFFF) { "destination port out of range: $destinationPort" }
        require(ttl in 1..255) { "ttl out of range: $ttl" }

        val totalLength = IPV4_HEADER_MIN_SIZE + UDP_HEADER_SIZE + payload.size
        require(totalLength <= 0xFFFF) { "IPv4 datagram too large: $totalLength" }

        val packet = ByteArray(totalLength)

        // --- IPv4 header (20 bytes, no options) ---
        packet[0] = ((IPV4_VERSION shl 4) or 5).toByte() // version + IHL
        packet[1] = 0 // DSCP/ECN
        writeU16(packet, 2, totalLength)
        writeU16(packet, 4, identification and 0xFFFF)
        writeU16(packet, 6, FLAG_DF)
        packet[8] = ttl.toByte()
        packet[9] = PROTOCOL_UDP.toByte()
        // packet[10..11] = header checksum, filled below
        System.arraycopy(sourceAddress, 0, packet, 12, 4)
        System.arraycopy(destinationAddress, 0, packet, 16, 4)
        writeU16(packet, IP_CHECKSUM_OFFSET, checksum(packet, 0, IPV4_HEADER_MIN_SIZE))

        // --- UDP header (8 bytes) + payload ---
        val udpOffset = IPV4_HEADER_MIN_SIZE
        writeU16(packet, udpOffset, sourcePort)
        writeU16(packet, udpOffset + 2, destinationPort)
        writeU16(packet, udpOffset + 4, UDP_HEADER_SIZE + payload.size)
        // packet[udpOffset+6..7] = checksum, filled below
        System.arraycopy(payload, 0, packet, udpOffset + UDP_HEADER_SIZE, payload.size)

        val udpChecksum = udpChecksum(
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            udpPacket = packet,
            udpOffset = udpOffset,
            udpLength = UDP_HEADER_SIZE + payload.size,
        )
        writeU16(packet, udpOffset + UDP_CHECKSUM_OFFSET, udpChecksum)
        return packet
    }

    /**
     * Computes the UDP checksum for the datagram stored at [udpOffset] in [udpPacket]
     * (header + payload, with the checksum field treated as zero), including the IPv4
     * pseudo-header. Returns `0xFFFF` if the computed value is zero (RFC 768).
     */
    fun udpChecksum(
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        udpPacket: ByteArray,
        udpOffset: Int,
        udpLength: Int,
    ): Int {
        require(sourceAddress.size == 4 && destinationAddress.size == 4)
        require(udpOffset >= 0 && udpLength >= UDP_HEADER_SIZE && udpOffset + udpLength <= udpPacket.size)

        var sum = 0L
        // Pseudo-header: src(4) + dst(4) + zero(1) + protocol(1) + UDP length(2).
        sum += wordsSum(sourceAddress, 0, 4)
        sum += wordsSum(destinationAddress, 0, 4)
        sum += PROTOCOL_UDP.toLong()
        sum += udpLength.toLong()
        // UDP header + payload. The checksum field bytes are 0 in a freshly built packet; to stay
        // honest for arbitrary callers, mask them out explicitly.
        var index = udpOffset
        val end = udpOffset + udpLength
        while (index < end) {
            val hi = if (index == udpOffset + UDP_CHECKSUM_OFFSET || index == udpOffset + UDP_CHECKSUM_OFFSET + 1) 0
            else udpPacket[index].toInt() and 0xFF
            val loIndex = index + 1
            val lo = if (loIndex >= end || loIndex == udpOffset + UDP_CHECKSUM_OFFSET || loIndex == udpOffset + UDP_CHECKSUM_OFFSET + 1) 0
            else udpPacket[loIndex].toInt() and 0xFF
            sum += ((hi shl 8) or lo).toLong()
            index += 2
        }
        val folded = fold(sum)
        val result = folded.inv() and 0xFFFF
        return if (result == 0) 0xFFFF else result
    }

    /**
     * Internet checksum (RFC 1071) over `buffer[offset until offset+length]`.
     * Returns the 16-bit one's-complement checksum; feeding a complete header (checksum included)
     * back into this function yields `0x0000` for a valid header.
     */
    fun checksum(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        return fold(wordsSum(buffer, offset, length)).inv() and 0xFFFF
    }

    /** Returns true if the IPv4 header checksum field of a parsed header is valid. */
    fun isValidIpv4HeaderChecksum(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Boolean {
        if (length < IPV4_HEADER_MIN_SIZE) return false
        val ihl = (buffer[offset].toInt() and 0x0F) * 4
        if (ihl < IPV4_HEADER_MIN_SIZE || ihl > length) return false
        return checksum(buffer, offset, ihl) == 0
    }

    private fun wordsSum(buffer: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += (((buffer[index].toInt() and 0xFF) shl 8) or (buffer[index + 1].toInt() and 0xFF)).toLong()
            index += 2
        }
        if (index < end) {
            // Odd trailing byte is padded with a zero low byte.
            sum += ((buffer[index].toInt() and 0xFF) shl 8).toLong()
        }
        return sum
    }

    /** Folds a 32-bit (or larger) one's-complement sum down to 16 bits and folds the carry. */
    private fun fold(sum: Long): Int {
        var folded = sum
        while ((folded shr 16) != 0L) {
            folded = (folded and 0xFFFF) + (folded shr 16)
        }
        return folded.toInt() and 0xFFFF
    }

    private fun readU16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    /** IPv4 identification counter: unique enough for locally generated DNS replies. */
    private val identificationCounter = java.util.concurrent.atomic.AtomicInteger(1)

    private fun nextIdentification(): Int = identificationCounter.getAndIncrement() and 0xFFFF
}
