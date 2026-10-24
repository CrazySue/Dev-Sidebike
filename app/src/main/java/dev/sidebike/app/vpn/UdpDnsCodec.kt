package dev.sidebike.app.vpn

/**
 * DNS-over-UDP interception codec for [SidebikeVpnService].
 *
 * The VPN routes only `vpnDnsAddress/32`, therefore every packet reaching the tun reader is an
 * IPv4/UDP datagram destined to the in-VPN resolver (port 53). This file provides:
 *
 *  - [DnsWire]: a defensive, dependency-free DNS message reader/builder. It understands enough of
 *    RFC 1035 / RFC 6891 to read the header + question section of a client query (including EDNS0
 *    queries, whose OPT pseudo-record lives in the additional section and is simply left opaque),
 *    to produce SERVFAIL/FORMERR and TC-truncated replies, and to repair the transaction ID if the
 *    resolver returns a response with a different one.
 *  - [DnsTunHandler]: classifies inbound tun packets (cheap, runs on the tun reader thread) and
 *    turns the resolver's wire response into a correct IPv4/UDP reply packet (runs on a worker).
 *
 * Multi-question queries are explicitly rejected with FORMERR instead of being forwarded: DNS
 * clients never batch questions in practice (RFC 1035 discouraged it, RFC 9619 now forbids it) and
 * the engine contract only promises single-question handling. Crucially the parser must not crash
 * on them — that is covered by unit tests.
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
    private const val EDNS0_UDP_SIZE = 1232

    /** Parsed DNS question (name is decoded to a printable dotted string; used for logging only). */
    data class Question(val name: String, val qtype: Int, val qclass: Int)

    /**
     * Parsed DNS message header + question section.
     *
     * [questionSectionEnd] is the offset just past the last question (i.e. where the answer section
     * starts), computed relative to a query that uses no name compression in its question names.
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

        val transactionId: Int get() = id
    }

    /**
     * Parses a DNS message enough to inspect a client query.
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

    /** Reads a (possibly compression-pointer-based) DNS name. Returns the name and the offset just past it. */
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
        /** Wire-format DNS query bytes exactly as received (without IP/UDP headers). */
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
     */
    fun classify(packet: ByteArray, length: Int = packet.size): Classified {
        val ip = IpPacket.parseIpv4(packet, 0, length)
            ?: return Classified.Ignored("not-ipv4-or-malformed")
        if (ip.protocol != IpPacket.PROTOCOL_UDP) return Classified.Ignored("not-udp")
        if (!ip.destinationAddress.contentEquals(vpnDnsAddress)) return Classified.Ignored("not-vpn-dns-address")

        val udp = IpPacket.parseUdp(ip.payload) ?: return Classified.Ignored("malformed-udp")
        if (udp.destinationPort != DNS_PORT) return Classified.Ignored("not-dns-port")

        val info = DnsWire.inspect(udp.payload) ?: return Classified.Ignored("malformed-dns")
        if (info.isResponse) return Classified.Ignored("dns-response-not-query")

        val request = DnsRequest(
            clientAddress = ip.sourceAddress,
            clientPort = udp.sourcePort,
            serverAddress = ip.destinationAddress,
            serverPort = udp.destinationPort,
            dnsQuery = udp.payload,
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

        val boundedReply = if (dnsReply.size > maxDnsPayload) {
            // Cannot fragment locally; tell the client the answer is truncated (TC=1) so it can
            // fail over / retry over TCP instead of hanging on a silently dropped response.
            DnsWire.buildReply(request.dnsQuery, DnsWire.RCODE_NO_ERROR, truncated = true) ?: return null
        } else {
            dnsReply
        }
        if (boundedReply.size < DnsWire.HEADER_SIZE) return null

        return IpPacket.buildUdpIpv4(
            sourceAddress = request.serverAddress,
            sourcePort = request.serverPort,
            destinationAddress = request.clientAddress,
            destinationPort = request.clientPort,
            payload = boundedReply,
        )
    }

    companion object {
        const val DNS_PORT: Int = 53
        const val DEFAULT_MTU: Int = 1500
    }
}
