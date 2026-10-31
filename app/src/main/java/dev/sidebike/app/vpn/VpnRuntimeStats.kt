package dev.sidebike.app.vpn

import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime counters from the tun DNS pipeline, exposed to logs/diagnostics/export.
 *
 * The VPN only routes the in-VPN DNS address; any other tunnel traffic is dropped by the classifier.
 * Real-device diagnostics (Xiaomi/Android 16) showed repeating
 * `忽略 tun 数据包: not-ipv4(version=6)` lines — Android still hands some IPv6 packets to the tun
 * which this beta intentionally does not serve. Counting them (instead of only logging) makes the
 * behaviour visible in the exported diagnostics and lets support tell a harmless drop apart from a
 * real DNS failure.
 *
 * Thread-safe: written by the tun reader thread, read by UI/diagnostics.
 */
internal object VpnRuntimeStats {

    data class Snapshot(
        val ipv6Dropped: Long,
        val otherDropped: Long,
        val dnsQueries: Long,
        val lastIgnoredReason: String?,
    )

    private val ipv6Dropped = AtomicLong(0)
    private val otherDropped = AtomicLong(0)
    private val dnsQueries = AtomicLong(0)

    @Volatile
    private var lastIgnoredReasonInternal: String? = null

    /** Records one ignored tun packet; IPv6 drops are tracked separately from other drops. */
    fun recordIgnored(reason: String) {
        if (reason.startsWith(IPV6_REASON_PREFIX)) {
            ipv6Dropped.incrementAndGet()
        } else {
            otherDropped.incrementAndGet()
        }
        lastIgnoredReasonInternal = reason
    }

    fun recordDnsQuery() {
        dnsQueries.incrementAndGet()
    }

    fun snapshot(): Snapshot = Snapshot(
        ipv6Dropped = ipv6Dropped.get(),
        otherDropped = otherDropped.get(),
        dnsQueries = dnsQueries.get(),
        lastIgnoredReason = lastIgnoredReasonInternal,
    )

    /** Resets all counters (called when a new VPN session starts). */
    fun reset() {
        ipv6Dropped.set(0)
        otherDropped.set(0)
        dnsQueries.set(0)
        lastIgnoredReasonInternal = null
    }

    /** Reason prefix produced by [DnsTunHandler.classify] for IPv6 tun packets. */
    const val IPV6_REASON_PREFIX: String = "not-ipv4(version=6)"
}
