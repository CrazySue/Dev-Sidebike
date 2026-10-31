package dev.sidebike.app.vpn

/**
 * DNS-over-UDP interception codec for [SidebikeVpnService].
 *
 * The VPN routes only `vpnDnsAddress/32`, therefore every packet reaching the tun reader is an
 * IPv4/UDP datagram destined to the in-VPN resolver (port 53). This file provides:
 *
 *  - [DnsWire]: a defensive, dependency-free DNS message reader/builder. It understands enough of
 *    RFC 1035 / RFC 6891 to read the header + question section of a client query (including EDNS0
 *    queries, whose OPT pseudo-record lives in the additional section), to clamp the advertised
 *    EDNS0 UDP payload size, to trim oversize responses to the tun MTU while keeping TC=0, to
 *    produce SERVFAIL/FORMERR replies, and to repair the transaction ID if the resolver returns a
 *    response with a different one.
 *  - [DnsTunHandler]: classifies inbound tun packets (cheap, runs on the tun reader thread) and
 *    turns the resolver's wire response into a correct IPv4/UDP reply packet (runs on a worker).
 *
 * ## Truncation policy (beta has no TCP:53 listener)
 * The tun only carries IPv4/UDP. If a DNS response does not fit into one MTU there is no TCP
 * fallback the client could use, so setting `TC=1` would make Android's resolver retry over TCP and
 * hang. Instead:
 *  1. the EDNS0 UDP payload size in queries is clamped to [MTU_SAFE_EDNS_UDP_PAYLOAD] before the
 *     resolver sees it (so a compliant resolver already avoids huge answers), and
 *  2. if the answer is still too large, [DnsWire.trimResponseToFit] keeps the question plus as many
 *     whole answer records as fit and drops the rest (TC stays 0; the client gets a valid partial
 *     answer instead of a hang).
 *
 * Multi-question queries are explicitly rejected with FORMERR instead of being forwarded: DNS
 * clients never batch questions in practice (RFC 9619 now forbids it) and the engine contract only
 * promises single-question handling. Crucially the parser must not crash on them — covered by tests.
 *
 * Everything here is pure Kotlin/JVM and hermetic: no Android APIs, no external network.
 */
internal object DnsWire {

    /** Fixed DNS header size (RFC 1035 §4.1.1). */
    const val HEADER_SIZE: Int = 12

    const val QTYPE_A: Int = 1
    const val QTYPE_AAAA: Int = 28
    const val QTYPE_OPT: Int = 41

    const val RCODE_NO_ERROR: Int = 0
    const val RCODE_FORMERR: Int = 1
    const val RCODE_SERVFAIL: Int = 2

    /**
     * Upper bound advertised to upstream resolvers through the EDNS0 OPT record. 1200 bytes fits
     * every practical tun MTU (1500 - IPv4/UDP headers) without fragmentation.
     */
    const val MTU_SAFE_EDNS_UDP_PAYLOAD: Int = 1200

    private const val FLAG_QR = 0x8000
    private const val FLAG_RD = 0x0100
    private const val FLAG_RA = 0x0080
    private const val FLAG_TC = 0x0200

    /**
     * Upper bound on accepted question count. Larger values are treated as malformed instead of
     * being parsed (DoS hardening); real queries carry exactly one question.
     */
    private const val MAX_QUESTIONS = 64

    /** EDNS0 UDP payload size we advertise in generated responses (common safe default). */
    private const val EDNS0_UDP_SIZE = MTU_SAFE_EDNS_UDP_PAYLOAD

    /** Parsed DNS question (name is decoded to a printable dotted string; used for logging only). */
    data class Question(val name: String, val qtype: Int, val qclass: Int)

    /**
     * Parsed DNS message header + question section.
     *
     * [questionSectionEnd] is the offset just past the last question (i.e. where the answer section
     * starts), computed relative to a message whose question names are uncompressed.
     */
    data class MessageInfo(
        val id: Int,
        val flags: Int,
        val questionCount: Int,
        val answerCount: Int,
        val authorityCount: Int,
        val additionalCount: Int,
        val questions: List<Question>,
        val questionSectionEnd: Int,
        val isResponse: Boolean,
        val hasEdns0: Boolean,
    ) {
        val isStandardQuery: Boolean
            get() = !isResponse && questionCount == 1 &&
                (flags and 0x7800) == 0 // opcode QUERY
    }

    /**
     * Parses a DNS message enough to inspect a query/response.
     *
     * Never throws: returns `null` when the buffer is truncated, the header/question sections are
     * malformed, or the question count is implausible. Name-compression pointers are followed with
     * a hop limit so a malicious packet cannot loop.
     */
    fun inspect(payload: ByteArray, length: Int = payload.size): MessageInfo? {
        if (length < HEADER_SIZE || length > payload.size) return null
        val id = u16(payload, 0)
        val flags = u16(payload, 2)
        val questionCount = u16(payload, 4)
        val answerCount = u16(payload, 6)
        val authorityCount = u16(payload, 8)
        val additionalCount = u16(payload, 10)
        if (questionCount == 0 || questionCount > MAX_QUESTIONS) return null

        var offset = HEADER_SIZE
        val questions = ArrayList<Question>(questionCount)
        repeat(questionCount) {
            val name = readName(payload, offset, length) ?: return null
            offset = name.endOffset
            if (offset + 4 > length) return null
            questions += Question(
                name = name.name,
                qtype = u16(payload, offset),
                qclass = u16(payload, offset + 2),
            )
            offset += 4
        }
        return MessageInfo(
            id = id,
            flags = flags,
            questionCount = questionCount,
            answerCount = answerCount,
            authorityCount = authorityCount,
            additionalCount = additionalCount,
            questions = questions,
            questionSectionEnd = offset,
            isResponse = flags and FLAG_QR != 0,
            hasEdns0 = additionalCount > 0,
        )
    }

    /**
     * Clamps the EDNS0 OPT record's advertised UDP payload size in a query to [maxUdpPayload]
     * (never below 512, per RFC 6891). Returns the original array when the query has no OPT record
     * or no clamping is needed; otherwise returns a patched copy.
     *
     * This is the first line of defense against oversized answers: the engine's resolver sees the
     * clamped value and asks upstream servers for smaller responses.
     */
    fun clampEdnsUdpPayload(query: ByteArray, maxUdpPayload: Int = MTU_SAFE_EDNS_UDP_PAYLOAD): ByteArray {
        if (maxUdpPayload <= 512) return query
        val info = inspect(query) ?: return query
        if (!info.hasEdns0) return query

        var offset = info.questionSectionEnd
        val limit = query.size
        // Skip answers + authority, then walk the additional section looking for OPT (type 41).
        repeat(info.answerCount + info.authorityCount) {
            offset = skipRecord(query, offset, limit) ?: return query
        }
        repeat(info.additionalCount) {
            val nameResult = readName(query, offset, limit) ?: return query
            val typeOffset = nameResult.endOffset
            val end = recordEnd(query, typeOffset, limit) ?: return query
            if (u16(query, typeOffset) == QTYPE_OPT) {
                val advertised = u16(query, typeOffset + 2) // CLASS field of OPT == UDP payload size
                val clamped = advertised.coerceIn(512, maxUdpPayload)
                if (clamped == advertised) return query
                val patched = query.copyOf()
                writeU16(patched, typeOffset + 2, clamped)
                return patched
            }
            offset = end
        }
        return query
    }

    /**
     * Trims an oversize DNS [response] so that it fits [maxPayload] bytes without ever setting TC.
     *
     * Keeps the original header flags (TC cleared), the question section and as many complete answer
     * records as fit, drops authority records, and re-adds a minimal empty EDNS0 OPT record when the
     * client query had one (RFC 6891 §6.1.2). Compression pointers inside the kept prefix remain
     * valid because they only point backwards. Returns `null` when even the question section cannot
     * be preserved.
     */
    fun trimResponseToFit(query: ByteArray, response: ByteArray, maxPayload: Int): ByteArray? {
        val info = inspect(response) ?: return null
        val questionEnd = info.questionSectionEnd
        val queryInfo = inspect(query)
        val needsOpt = queryInfo?.hasEdns0 == true
        val optSize = if (needsOpt) EMPTY_OPT_RECORD.size else 0
        if (questionEnd + optSize > maxPayload) return null

        var offset = questionEnd
        val answerEnds = ArrayList<Int>(info.answerCount)
        repeat(info.answerCount) {
            val end = skipRecord(response, offset, response.size) ?: return@repeat
            answerEnds += end
            offset = end
        }

        var keptAnswers = 0
        for ((index, end) in answerEnds.withIndex()) {
            if (end + optSize > maxPayload) break
            keptAnswers = index + 1
        }
        val cut = if (keptAnswers > 0) answerEnds[keptAnswers - 1] else questionEnd
        if (cut + optSize > maxPayload) return null

        val trimmed = ByteArray(cut + optSize)
        System.arraycopy(response, 0, trimmed, 0, cut)
        writeU16(trimmed, 4, info.questionCount)
        writeU16(trimmed, 6, keptAnswers)
        writeU16(trimmed, 8, 0)
        writeU16(trimmed, 10, if (needsOpt) 1 else 0)
        // Preserve QR/opcode/RD/RA/RCODE, force TC=0: the client must not retry over TCP.
        writeU16(trimmed, 2, u16(trimmed, 2) and FLAG_TC.inv())
        if (needsOpt) System.arraycopy(EMPTY_OPT_RECORD, 0, trimmed, cut, optSize)
        return trimmed
    }

    /** Returns a copy of [message] with TC cleared when needed (defensive: beta has no TCP path). */
    fun clearTruncationFlag(message: ByteArray): ByteArray {
        if (message.size < HEADER_SIZE) return message
        if (u16(message, 2) and FLAG_TC == 0) return message
        val copy = message.copyOf()
        writeU16(copy, 2, u16(copy, 2) and FLAG_TC.inv())
        return copy
    }

    /**
     * Extracts the IPv4 addresses (A records) from a DNS wire response, in record order.
     * Used by the network diagnostics report; returns an empty list for malformed/non-answer input.
     */
    fun extractIpv4Answers(response: ByteArray?, length: Int = response?.size ?: 0): List<String> {
        if (response == null || length < HEADER_SIZE) return emptyList()
        val info = inspect(response, length) ?: return emptyList()
        if (!info.isResponse) return emptyList()
        val addresses = ArrayList<String>(info.answerCount)
        var offset = info.questionSectionEnd
        repeat(info.answerCount) {
            val name = readName(response, offset, length) ?: return emptyList()
            val typeOffset = name.endOffset
            if (typeOffset + 10 > length) return emptyList()
            val type = u16(response, typeOffset)
            val rdLength = u16(response, typeOffset + 8)
            val rdataStart = typeOffset + 10
            if (rdataStart + rdLength > length) return emptyList()
            if (type == QTYPE_A && rdLength == 4) {
                addresses += buildString {
                    append(response[rdataStart].toInt() and 0xFF).append('.')
                    append(response[rdataStart + 1].toInt() and 0xFF).append('.')
                    append(response[rdataStart + 2].toInt() and 0xFF).append('.')
                    append(response[rdataStart + 3].toInt() and 0xFF)
                }
            }
            offset = rdataStart + rdLength
        }
        return addresses
    }

    /**
     * Builds a minimal DNS error/truncated reply for [query]:
     * header (with QR/RA set, RD echoed, [rcode] in the low four bits), the query's question
     * section only, and — when the query carried an additional section (i.e. EDNS0) — an empty OPT
     * record as required by RFC 6891 §6.1.2.
     *
     * Returns `null` when [query] is not a well-formed single-question query.
     */
    fun buildReply(query: ByteArray, rcode: Int, truncated: Boolean = false): ByteArray? {
        val info = inspect(query) ?: return null
        if (info.isResponse) return null
        val questionLength = info.questionSectionEnd - HEADER_SIZE
        if (questionLength < 0) return null
        val optRecord = if (info.hasEdns0) EMPTY_OPT_RECORD else null

        val reply = ByteArray(HEADER_SIZE + questionLength + (optRecord?.size ?: 0))
        System.arraycopy(query, 0, reply, 0, info.questionSectionEnd)

        val queryFlags = u16(query, 2)
        var replyFlags = FLAG_QR or (queryFlags and FLAG_RD) or FLAG_RA or (rcode and 0x0F)
        if (truncated) replyFlags = replyFlags or FLAG_TC
        writeU16(reply, 2, replyFlags)
        writeU16(reply, 4, info.questionCount) // QDCOUNT
        writeU16(reply, 6, 0) // ANCOUNT
        writeU16(reply, 8, 0) // NSCOUNT
        writeU16(reply, 10, if (optRecord != null) 1 else 0) // ARCOUNT
        if (optRecord != null) {
            System.arraycopy(optRecord, 0, reply, HEADER_SIZE + questionLength, optRecord.size)
        }
        return reply
    }

    /**
     * Returns [response] with its transaction ID forced to [expectedId] when the resolver reused a
     * different one (some resolvers answer with ID 0). The input array is returned unchanged when
     * no repair is needed, otherwise a patched copy is returned.
     *
     * Returns `null` when [response] is too short to be a DNS message.
     */
    fun ensureTransactionId(response: ByteArray, expectedId: Int): ByteArray? {
        if (response.size < HEADER_SIZE) return null
        val actual = u16(response, 0)
        if (actual == (expectedId and 0xFFFF)) return response
        val patched = response.copyOf()
        writeU16(patched, 0, expectedId and 0xFFFF)
        return patched
    }

    /** Reads a (possibly compression-pointer-based) DNS name. Returns the name and offset past it. */
    private data class NameResult(val name: String, val endOffset: Int)

    private fun readName(buffer: ByteArray, start: Int, limit: Int): NameResult? {
        val name = StringBuilder()
        var offset = start
        var endOffset = -1
        var jumps = 0
        while (true) {
            if (offset < 0 || offset >= limit) return null
            val labelLength = buffer[offset].toInt() and 0xFF
            when {
                labelLength == 0 -> {
                    if (endOffset < 0) endOffset = offset + 1
                    return NameResult(name.toString(), endOffset)
                }
                labelLength and 0xC0 == 0xC0 -> {
                    // Compression pointer: 14-bit offset into the message.
                    if (offset + 1 >= limit) return null
                    if (jumps++ > 16) return null
                    val pointer = ((labelLength and 0x3F) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
                    if (endOffset < 0) endOffset = offset + 2
                    offset = pointer
                }
                labelLength and 0xC0 != 0 -> return null // reserved label type
                else -> {
                    if (labelLength > 63 || offset + 1 + labelLength > limit) return null
                    if (name.length + labelLength + 1 > 255) return null
                    if (name.isNotEmpty()) name.append('.')
                    for (i in 0 until labelLength) {
                        val value = buffer[offset + 1 + i].toInt() and 0xFF
                        // Printable ASCII only; control bytes would poison the log line.
                        name.append(if (value in 0x21..0x7E) value.toChar() else '?')
                    }
                    offset += 1 + labelLength
                }
            }
        }
    }

    /** Skips a complete resource record starting at [start]; returns the offset just past it. */
    private fun skipRecord(buffer: ByteArray, start: Int, limit: Int): Int? {
        val name = readName(buffer, start, limit) ?: return null
        return recordEnd(buffer, name.endOffset, limit)
    }

    /** Given the offset of a record's TYPE field, returns the offset just past its RDATA. */
    private fun recordEnd(buffer: ByteArray, typeOffset: Int, limit: Int): Int? {
        if (typeOffset + 10 > limit) return null
        val rdLength = u16(buffer, typeOffset + 8)
        val end = typeOffset + 10 + rdLength
        return if (end <= limit) end else null
    }

    /** Empty EDNS0 OPT pseudo-record: root name, type 41, class = UDP payload size, TTL 0, RDLEN 0. */
    private val EMPTY_OPT_RECORD = byteArrayOf(
        0x00, // NAME (root)
        0x00, 0x29, // TYPE = OPT (41)
        ((EDNS0_UDP_SIZE ushr 8) and 0xFF).toByte(), (EDNS0_UDP_SIZE and 0xFF).toByte(), // CLASS = UDP payload size
        0x00, 0x00, 0x00, 0x00, // TTL (extended RCODE/version/flags)
        0x00, 0x00, // RDLENGTH
    )

    internal fun u16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    internal fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }
}

/**
 * Stateless classifier/builder for the tun DNS path.
 *
 * [classify] runs on the single tun reader thread and only does cheap parsing; the actual resolver
 * call happens off-thread in the service. [buildReply] then wraps the resolver's wire response into
 * an IPv4/UDP packet addressed back to the client.
 *
 * @param vpnDnsAddress the IPv4 address advertised to apps via `Builder.addDnsServer` (4 bytes).
 * @param mtu tun interface MTU used to cap reply payload size (default 1500 as configured).
 */
internal class DnsTunHandler(
    private val vpnDnsAddress: ByteArray,
    private val mtu: Int = DEFAULT_MTU,
) {

    init {
        require(vpnDnsAddress.size == 4) { "vpnDnsAddress must be 4 bytes" }
        require(mtu > IpPacket.IPV4_HEADER_MIN_SIZE + IpPacket.UDP_HEADER_SIZE) { "MTU too small: $mtu" }
    }

    /** A DNS query extracted from the tun that still needs an answer. */
    data class DnsRequest(
        /** Original client IPv4 address (source of the query, destination of the reply). */
        val clientAddress: ByteArray,
        val clientPort: Int,
        /** Address the query was sent to; the reply appears to come from it. */
        val serverAddress: ByteArray,
        val serverPort: Int,
        /** Wire-format DNS query bytes as they should be handed to the resolver (EDNS0 clamped). */
        val dnsQuery: ByteArray,
        val questionName: String,
        val questionType: Int,
        /** When non-null the reply must be a synthetic error with this RCODE (no resolver call). */
        val forcedRcode: Int? = null,
    )

    sealed interface Classified {
        data class Dns(val request: DnsRequest) : Classified
        /** Packet is irrelevant to DNS interception and must be dropped. */
        data class Ignored(val reason: String) : Classified
    }

    /** Largest DNS payload that fits the configured MTU without IP fragmentation. */
    val maxDnsPayload: Int = mtu - IpPacket.IPV4_HEADER_MIN_SIZE - IpPacket.UDP_HEADER_SIZE

    /**
     * Classifies one inbound tun packet. Returns [Classified.Ignored] for anything that is not a
     * well-formed IPv4/UDP DNS query addressed to [vpnDnsAddress]:53, and never throws.
     *
     * The EDNS0 advertised UDP payload size of the query is clamped to
     * [DnsWire.MTU_SAFE_EDNS_UDP_PAYLOAD] so the resolver asks upstream servers for answers that fit
     * the tun MTU.
     */
    fun classify(packet: ByteArray, length: Int = packet.size): Classified {
        if (length <= 0) return Classified.Ignored("empty-packet")
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != IpPacket.IPV4_VERSION) return Classified.Ignored("not-ipv4(version=$version)")
        val ip = IpPacket.parseIpv4(packet, 0, length)
            ?: return Classified.Ignored("malformed-ipv4")
        if (ip.protocol != IpPacket.PROTOCOL_UDP) return Classified.Ignored("not-udp(proto=${ip.protocol})")
        if (!ip.destinationAddress.contentEquals(vpnDnsAddress)) return Classified.Ignored("not-vpn-dns-address")

        val udp = IpPacket.parseUdp(ip.payload) ?: return Classified.Ignored("malformed-udp")
        if (udp.destinationPort != DNS_PORT) return Classified.Ignored("not-dns-port(${udp.destinationPort})")

        val info = DnsWire.inspect(udp.payload) ?: return Classified.Ignored("malformed-dns")
        if (info.isResponse) return Classified.Ignored("dns-response-not-query")

        val engineQuery = DnsWire.clampEdnsUdpPayload(udp.payload)
        val request = DnsRequest(
            clientAddress = ip.sourceAddress,
            clientPort = udp.sourcePort,
            serverAddress = ip.destinationAddress,
            serverPort = udp.destinationPort,
            dnsQuery = engineQuery,
            questionName = info.questions.firstOrNull()?.name ?: "",
            questionType = info.questions.firstOrNull()?.qtype ?: 0,
            forcedRcode = when {
                // Multi-question queries are not forwarded; answer FORMERR so the client fails fast.
                !info.isStandardQuery && info.questionCount != 1 -> DnsWire.RCODE_FORMERR
                else -> null
            },
        )
        return Classified.Dns(request)
    }

    /**
     * Builds the IPv4/UDP reply for [request].
     *
     * Never produces a `TC=1` reply: the tun has no TCP:53 path, so an oversize answer is trimmed to
     * whole answer records that fit the MTU (see [DnsWire.trimResponseToFit]).
     *
     * @param resolverResponse wire-format DNS answer from the engine, or `null` on failure/timeout.
     * @return a complete IPv4 packet ready to be written back to the tun; `null` when no reply can
     *   be produced (e.g. the malformed query cannot even be echoed) and the packet must be dropped.
     */
    fun buildReply(request: DnsRequest, resolverResponse: ByteArray?): ByteArray? {
        val dnsReply = when {
            request.forcedRcode != null ->
                DnsWire.buildReply(request.dnsQuery, request.forcedRcode) ?: return null
            resolverResponse == null ->
                DnsWire.buildReply(request.dnsQuery, DnsWire.RCODE_SERVFAIL) ?: return null
            else ->
                DnsWire.ensureTransactionId(resolverResponse, DnsWire.u16(request.dnsQuery, 0))
                    ?: DnsWire.buildReply(request.dnsQuery, DnsWire.RCODE_SERVFAIL)
                    ?: return null
        }

        val fittedReply = if (dnsReply.size <= maxDnsPayload) {
            dnsReply
        } else {
            // Oversize: trim whole answer records to fit the MTU. TC must stay 0 (no TCP path).
            DnsWire.trimResponseToFit(request.dnsQuery, dnsReply, maxDnsPayload)
                ?: DnsWire.buildReply(request.dnsQuery, DnsWire.RCODE_SERVFAIL)
                ?: return null
        }
        val finalReply = DnsWire.clearTruncationFlag(fittedReply)
        if (finalReply.size < DnsWire.HEADER_SIZE || finalReply.size > maxDnsPayload) return null

        return IpPacket.buildUdpIpv4(
            sourceAddress = request.serverAddress,
            sourcePort = request.serverPort,
            destinationAddress = request.clientAddress,
            destinationPort = request.clientPort,
            payload = finalReply,
        )
    }

    companion object {
        const val DNS_PORT: Int = 53
        const val DEFAULT_MTU: Int = 1500
    }
}
