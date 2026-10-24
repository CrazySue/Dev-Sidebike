package dev.sidebike.core.dns

import java.net.InetAddress

/** DNS 记录类型（仅列出用到的）。 */
object DnsType {
    const val A = 1
    const val NS = 2
    const val CNAME = 5
    const val SOA = 6
    const val AAAA = 28
    const val OPT = 41
    const val SVCB = 64
    const val HTTPS = 65
    const val ANY = 255
}

data class DnsQuestion(val name: String, val type: Int, val clazz: Int)

/** 解析后的 DNS 资源记录（rdata 不展开，按 [type] 取用）。 */
data class DnsRecord(
    val name: String,
    val type: Int,
    val clazz: Int,
    val ttl: Long,
    val rdataOffset: Int,
    val rdataLength: Int,
)

data class IpAnswer(val ip: String, val ttlSec: Int, val family: Int)

/** EDNS0 OPT 伪记录参数（RFC 6891）。 */
data class Edns0(
    val udpPayloadSize: Int,
    val dnssecOk: Boolean,
    val version: Int,
    val extendedRcode: Int,
)

/**
 * DNS 报文（wire format）解析结果。
 *
 * 注意：上游 `mitmproxy/src/lib/dns` 目录用第三方 dns 库解析，Android 端为纯 Kotlin 自实现，
 * 只需覆盖 resolver 实际用到的部分：A/AAAA/OPT/CNAME（CNAME 作为记录跳过），以及问题/记录计数。
 */
class DnsMessage(
    val id: Int,
    val flags: Int,
    val questions: List<DnsQuestion>,
    val answers: List<DnsRecord>,
    val authorities: List<DnsRecord>,
    val additionals: List<DnsRecord>,
    /** 原始字节（buildReply 需要回显 question 段）。 */
    val raw: ByteArray,
    /** question 段结束偏移（= answers 段起点）。 */
    val questionEndOffset: Int,
) {
    val rcode: Int get() = flags and 0x000F
    val isResponse: Boolean get() = (flags and 0x8000) != 0
    val isTruncated: Boolean get() = (flags and 0x0200) != 0
    val isRecursionDesired: Boolean get() = (flags and 0x0100) != 0

    /** 可选 EDNS0（查询的 additional 段第一条 OPT）。 */
    val edns0: Edns0? by lazy {
        additionals.firstOrNull { it.type == DnsType.OPT }?.let { record ->
            // OPT: CLASS = UDP payload size; TTL = ext-rcode(8) | version(8) | DO(1) | Z(15)
            val ttl = record.ttl and 0xFFFFFFFFL
            Edns0(
                udpPayloadSize = record.clazz,
                dnssecOk = (ttl shr 15) and 1L == 1L,
                version = ((ttl shr 16) and 0xFF).toInt(),
                extendedRcode = ((ttl shr 24) and 0xFF).toInt(),
            )
        }
    }

    /** 从 A/AAAA 记录提取 IP（顺序即应答顺序）。 */
    fun ipAnswers(): List<IpAnswer> {
        val result = ArrayList<IpAnswer>(answers.size)
        for (record in answers) {
            when (record.type) {
                DnsType.A -> if (record.rdataLength == 4) {
                    val bytes = raw.copyOfRange(record.rdataOffset, record.rdataOffset + 4)
                    result.add(IpAnswer(formatIp(bytes), record.ttl.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), 4))
                }

                DnsType.AAAA -> if (record.rdataLength == 16) {
                    val bytes = raw.copyOfRange(record.rdataOffset, record.rdataOffset + 16)
                    result.add(IpAnswer(formatIp(bytes), record.ttl.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), 6))
                }
            }
        }
        return result
    }

    private fun formatIp(bytes: ByteArray): String = try {
        InetAddress.getByAddress(bytes).hostAddress ?: ""
    } catch (_: Exception) {
        ""
    }
}

/**
 * DNS wire-format 编解码。
 *
 * 能力边界（与 QA 要求一致，见 `docs/feasibility/01b-config-porting.md`）：
 * - 解析支持名字压缩指针（answers 常见压缩），带循环保护；
 * - 构造查询：A/AAAA + 可选 EDNS0；
 * - 构造应答（tun 路径）：回显 question、A/AAAA answer、EDNS0 OPT；**绝不设置 TC 位**，
 *   而是按客户端 EDNS0 UDP payload（无 EDNS0 时 512）裁剪 answer 数量，保证报文结构合法。
 */
object DnsCodec {

    private const val MAX_NAME_JUMPS = 32
    private const val HEADER_LEN = 12

    class MalformedDnsException(message: String) : IllegalArgumentException(message)

    /** 解析失败返回 null（代理路径/坏包不应抛异常）。 */
    fun parse(data: ByteArray): DnsMessage? = try {
        parseOrThrow(data)
    } catch (_: Exception) {
        null
    }

    fun parseOrThrow(data: ByteArray): DnsMessage {
        if (data.size < HEADER_LEN) throw MalformedDnsException("DNS 报文过短: ${data.size}")
        val id = u16(data, 0)
        val flags = u16(data, 2)
        val qdCount = u16(data, 4)
        val anCount = u16(data, 6)
        val nsCount = u16(data, 8)
        val arCount = u16(data, 10)

        var offset = HEADER_LEN
        val questions = ArrayList<DnsQuestion>(qdCount)
        repeat(qdCount) {
            val (name, next) = readName(data, offset)
            offset = next
            if (offset + 4 > data.size) throw MalformedDnsException("question 越界")
            val type = u16(data, offset)
            val clazz = u16(data, offset + 2)
            offset += 4
            questions.add(DnsQuestion(name, type, clazz))
        }
        val questionEnd = offset

        fun readRecords(count: Int): List<DnsRecord> {
            val records = ArrayList<DnsRecord>(count)
            repeat(count) {
                val (name, next) = readName(data, offset)
                offset = next
                if (offset + 10 > data.size) throw MalformedDnsException("resource record 越界")
                val type = u16(data, offset)
                val clazz = u16(data, offset + 2)
                val ttl = u32(data, offset + 4)
                val rdLength = u16(data, offset + 8)
                offset += 10
                if (offset + rdLength > data.size) throw MalformedDnsException("rdata 越界")
                records.add(DnsRecord(name, type, clazz, ttl, offset, rdLength))
                offset += rdLength
            }
            return records
        }

        val answers = readRecords(anCount)
        val authorities = readRecords(nsCount)
        val additionals = readRecords(arCount)
        return DnsMessage(id, flags, questions, answers, authorities, additionals, data, questionEnd)
    }

    /**
     * 构造查询。`id` 由调用方给定（0 也合法，UDP 会校验 id 响应）。
     * [dnssecOk] 仅对支持 EDNS0 的 provider 有意义（如 Cloudflare/quad9）。
     */
    fun buildQuery(
        host: String,
        type: Int,
        id: Int = 0,
        recursionDesired: Boolean = true,
        edns0: Boolean = true,
        udpPayloadSize: Int = 1232,
        dnssecOk: Boolean = false,
    ): ByteArray {
        val name = encodeName(host)
        val questionLen = name.size + 4
        val optLen = if (edns0) 11 else 0
        val out = ByteArray(HEADER_LEN + questionLen + optLen)
        putU16(out, 0, id)
        var flags = 0
        if (recursionDesired) flags = flags or 0x0100
        putU16(out, 2, flags)
        putU16(out, 4, 1) // qdcount
        putU16(out, 6, 0)
        putU16(out, 8, 0)
        putU16(out, 10, if (edns0) 1 else 0) // arcount
        var offset = HEADER_LEN
        name.copyInto(out, offset)
        offset += name.size
        putU16(out, offset, type)
        putU16(out, offset + 2, 1) // class IN
        offset += 4
        if (edns0) {
            out[offset] = 0 // root name
            putU16(out, offset + 1, DnsType.OPT)
            putU16(out, offset + 3, udpPayloadSize.coerceIn(512, 4096))
            val ttl = if (dnssecOk) 0x00008000L else 0L
            putU32(out, offset + 5, ttl)
            putU16(out, offset + 9, 0) // rdlength
        }
        return out
    }

    /**
     * 构造应答（tun 路径专用）。
     *
     * @param maxUdpPayload 客户端可接收的 UDP payload（来自 EDNS0；无 EDNS0 时传 512）。
     *   本方法**不设置 TC 位**：answer 放不下时按顺序裁剪，直到整包 ≤ maxUdpPayload；
     *   一条都放不下时返回空 answer 的 NOERROR 应答（调用方/客户端会自然回退 A/AAAA 其它路径）。
     */
    fun buildReply(
        query: DnsMessage,
        answers: List<IpAnswer>,
        rcode: Int = 0,
        maxUdpPayload: Int = 512,
        includeEdns0: Boolean = true,
    ): ByteArray {
        val limit = maxUdpPayload.coerceIn(512, 4096)
        val questionBytes = query.raw.copyOfRange(HEADER_LEN, query.questionEndOffset)
        val optLen = if (includeEdns0 && query.edns0 != null) 11 else 0
        val ednsUdpSize = query.edns0?.udpPayloadSize?.coerceIn(512, 4096) ?: limit
        val dnssecOk = query.edns0?.dnssecOk ?: false

        fun answerBytes(answer: IpAnswer): ByteArray {
            val out = ByteArray(12 + answerBytesLength(answer))
            putU16(out, 0, 0xC00C) // name: pointer to question name at offset 12
            putU16(out, 2, if (answer.family == 6) DnsType.AAAA else DnsType.A)
            putU16(out, 4, 1) // class IN
            putU32(out, 6, answer.ttlSec.coerceAtLeast(0).toLong() and 0xFFFFFFFFL)
            val rdata = ipToBytes(answer.ip) ?: return ByteArray(0)
            putU16(out, 10, rdata.size)
            rdata.copyInto(out, 12)
            return out
        }

        val answerChunks = ArrayList<ByteArray>(answers.size)
        var total = HEADER_LEN + questionBytes.size + optLen
        for (answer in answers) {
            val chunk = answerBytes(answer)
            if (chunk.isEmpty()) continue
            if (total + chunk.size > limit) break // 裁剪：不放 TC 位
            answerChunks.add(chunk)
            total += chunk.size
        }

        val out = ByteArray(HEADER_LEN + questionBytes.size + answerChunks.sumOf { it.size } + optLen)
        putU16(out, 0, query.id)
        var flags = 0x8000 or 0x0080 // QR=1, RA=1
        flags = flags or (query.flags and 0x7800) // 回显 opcode
        if (query.isRecursionDesired) flags = flags or 0x0100
        flags = flags or (rcode and 0xF)
        putU16(out, 2, flags)
        putU16(out, 4, query.questions.size.coerceAtMost(1))
        putU16(out, 6, answerChunks.size)
        putU16(out, 8, 0)
        putU16(out, 10, if (optLen > 0) 1 else 0)
        var offset = HEADER_LEN
        questionBytes.copyInto(out, offset)
        offset += questionBytes.size
        for (chunk in answerChunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        if (optLen > 0) {
            out[offset] = 0
            putU16(out, offset + 1, DnsType.OPT)
            putU16(out, offset + 3, ednsUdpSize)
            val ttl = (if (dnssecOk) 0x00008000L else 0L)
            putU32(out, offset + 5, ttl)
            putU16(out, offset + 9, 0)
        }
        return out
    }

    private fun answerBytesLength(answer: IpAnswer): Int = 12 + if (answer.family == 6) 16 else 4

    // ------------------------------------------------------------------ names

    private fun readName(data: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>(4)
        var offset = start
        var next = -1
        var jumps = 0
        while (true) {
            if (offset >= data.size) throw MalformedDnsException("name 越界")
            val length = data[offset].toInt() and 0xFF
            when {
                length == 0 -> {
                    offset += 1
                    if (next < 0) next = offset
                    val name = labels.joinToString(".").lowercase()
                    return name to next
                }

                length and 0xC0 == 0xC0 -> {
                    if (offset + 1 >= data.size) throw MalformedDnsException("压缩指针越界")
                    val pointer = ((length and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    if (next < 0) next = offset + 2
                    if (++jumps > MAX_NAME_JUMPS) throw MalformedDnsException("压缩指针循环")
                    offset = pointer
                }

                length and 0xC0 != 0 -> throw MalformedDnsException("非法 label 长度: $length")

                else -> {
                    if (offset + 1 + length > data.size) throw MalformedDnsException("label 越界")
                    labels.add(String(data, offset + 1, length, Charsets.US_ASCII))
                    offset += 1 + length
                }
            }
        }
    }

    private fun encodeName(host: String): ByteArray {
        val normalized = host.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty()) return byteArrayOf(0)
        val labels = normalized.split('.')
        var size = 1
        for (label in labels) size += 1 + label.length
        val out = ByteArray(size)
        var offset = 0
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.size > 63) throw MalformedDnsException("label 过长: $label")
            out[offset] = bytes.size.toByte()
            bytes.copyInto(out, offset + 1)
            offset += 1 + bytes.size
        }
        out[offset] = 0
        return out
    }

    // ------------------------------------------------------------------ ip

    private fun ipToBytes(ip: String): ByteArray? = try {
        InetAddress.getByName(ip).address
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ primitives

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun putU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putU32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}
