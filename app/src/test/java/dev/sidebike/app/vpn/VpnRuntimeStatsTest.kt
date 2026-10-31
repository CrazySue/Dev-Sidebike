package dev.sidebike.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [VpnRuntimeStats]: the tun pipeline counters surfaced in diagnostics/export, including
 * the IPv6 drops observed on the real device (`忽略 tun 数据包: not-ipv4(version=6)`).
 */
class VpnRuntimeStatsTest {

    @Test
    fun ipv6AndOtherDropsAreCountedSeparately() {
        VpnRuntimeStats.reset()
        try {
            VpnRuntimeStats.recordIgnored(VpnRuntimeStats.IPV6_REASON_PREFIX)
            VpnRuntimeStats.recordIgnored(VpnRuntimeStats.IPV6_REASON_PREFIX)
            VpnRuntimeStats.recordIgnored("not-udp(proto=6)")
            VpnRuntimeStats.recordDnsQuery()
            VpnRuntimeStats.recordDnsQuery()
            VpnRuntimeStats.recordDnsQuery()

            val snapshot = VpnRuntimeStats.snapshot()
            assertEquals(2, snapshot.ipv6Dropped)
            assertEquals(1, snapshot.otherDropped)
            assertEquals(3, snapshot.dnsQueries)
            assertEquals("not-udp(proto=6)", snapshot.lastIgnoredReason)
        } finally {
            VpnRuntimeStats.reset()
        }
    }

    @Test
    fun resetClearsCountersAndLastReason() {
        VpnRuntimeStats.recordIgnored(VpnRuntimeStats.IPV6_REASON_PREFIX)
        VpnRuntimeStats.recordDnsQuery()
        VpnRuntimeStats.reset()

        val snapshot = VpnRuntimeStats.snapshot()
        assertEquals(0, snapshot.ipv6Dropped)
        assertEquals(0, snapshot.otherDropped)
        assertEquals(0, snapshot.dnsQueries)
        assertNull(snapshot.lastIgnoredReason)
    }

    @Test
    fun classifierReportsIpv6VersionReasonMatchingStatsPrefix() {
        val handler = DnsTunHandler(vpnDnsAddress = byteArrayOf(10, 111, 0, 2))
        // IPv6 packet (version nibble 6) — intentionally not served by this beta.
        val ipv6Packet = ByteArray(40) { 0x60.toByte() }
        val classified = handler.classify(ipv6Packet)
        assertTrue(classified is DnsTunHandler.Classified.Ignored)
        val reason = (classified as DnsTunHandler.Classified.Ignored).reason
        assertEquals(VpnRuntimeStats.IPV6_REASON_PREFIX, reason)
        assertTrue(reason.startsWith(VpnRuntimeStats.IPV6_REASON_PREFIX))
    }
}
