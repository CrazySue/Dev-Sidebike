package dev.sidebike.app.vpn

import dev.sidebike.core.api.SidebikeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DNS readiness probe.
 *
 * Establishing the VPN without a working DNS resolver would turn the device into a DNS black hole
 * (every app's DNS query is routed into the tun, so nobody could resolve anything). The controller
 * and the VPN service therefore resolve a known host through the engine *before* creating the tun;
 * if that fails the VPN is never established and the network stays untouched.
 *
 * The probe uses the same wire API as the tun path ([SidebikeEngine.resolveRawDnsQuery]) so it
 * exercises exactly the code that will answer real queries (provider selection, doh/dot bootstrap,
 * cache). Two well-known hosts are tried within the overall timeout budget.
 */
internal object DnsProbe {

    /** Hosts probed in order; the first usable answer wins. */
    val PROBE_HOSTS: List<String> = listOf("www.baidu.com", "example.com")

    /** Overall budget for the readiness probe (the tun establishment waits for it). */
    const val DEFAULT_TIMEOUT_MS: Long = 3_000L

    private const val PER_HOST_TIMEOUT_MS = 1_600L
    private const val PROBE_QUERY_ID = 0x51DB
    private const val FLAG_QR = 0x8000
    private const val FLAG_TC = 0x0200

    /** Builds a minimal wire-format A query for [host] (RD set, one question). */
    fun buildQuery(
        host: String,
        transactionId: Int = PROBE_QUERY_ID,
        qtype: Int = DnsWire.QTYPE_A,
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream(64)
        fun writeU16(value: Int) {
            output.write((value ushr 8) and 0xFF)
            output.write(value and 0xFF)
        }
        writeU16(transactionId)
        writeU16(0x0100) // RD
        writeU16(1) // QDCOUNT
        writeU16(0) // ANCOUNT
        writeU16(0) // NSCOUNT
        writeU16(0) // ARCOUNT
        for (label in host.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "invalid DNS label in probe host: $host" }
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        writeU16(qtype)
        writeU16(1) // QCLASS IN
        return output.toByteArray()
    }

    /**
     * True when [response] is a usable, non-truncated DNS answer with at least one record.
     * NXDOMAIN/SERVFAIL responses mean "the resolver is reachable but did not answer this name",
     * which is not good enough for a readiness probe of a well-known host.
     */
    fun isUsableResponse(response: ByteArray?): Boolean {
        if (response == null || response.size < DnsWire.HEADER_SIZE) return false
        val flags = DnsWire.u16(response, 2)
        if (flags and FLAG_QR == 0) return false
        if (flags and FLAG_TC != 0) return false
        if ((flags and 0x0F) != DnsWire.RCODE_NO_ERROR) return false
        return DnsWire.u16(response, 6) > 0 // ANCOUNT
    }

    /**
     * Runs the readiness probe against [engine]. Never throws: resolver errors/timeouts are reported
     * as `false` (except coroutine cancellation, which is rethrown so service/controller teardown
     * stays cooperative).
     */
    suspend fun probe(engine: SidebikeEngine, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(500L)
        for (host in PROBE_HOSTS) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val response = try {
                withTimeoutOrNull(minOf(remaining, PER_HOST_TIMEOUT_MS)) {
                    engine.resolveRawDnsQuery(buildQuery(host))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (isUsableResponse(response)) return true
        }
        return false
    }
}
