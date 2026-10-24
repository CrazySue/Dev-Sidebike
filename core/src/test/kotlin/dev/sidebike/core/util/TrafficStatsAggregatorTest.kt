package dev.sidebike.core.util

import dev.sidebike.core.api.DnsStats
import dev.sidebike.core.stats.TrafficStatsAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class TrafficStatsAggregatorTest {

    @Test
    fun `aggregates connections bytes and dns stats`() {
        val clock = AtomicLong(0)
        val aggregator = TrafficStatsAggregator { clock.get() }
        aggregator.onConnectionOpened()
        aggregator.onConnectionOpened()
        assertEquals(2, aggregator.activeConnections())
        aggregator.addBytes(up = 100, down = 200)
        aggregator.onConnectionClosed(success = true)
        aggregator.onConnectionClosed(success = false)
        clock.set(5_000)

        val traffic = aggregator.traffic(
            proxyPort = 31181,
            dns = DnsStats(queries = 10, cacheHits = 4, failures = 1, cacheEntries = 3, avgLatencyMs = 12),
        )
        assertEquals(2, traffic.totalConnections)
        assertEquals(0, traffic.activeConnections)
        assertEquals(1, traffic.successfulConnections)
        assertEquals(1, traffic.failedConnections)
        assertEquals(100, traffic.bytesUp)
        assertEquals(200, traffic.bytesDown)
        assertEquals(10, traffic.dnsQueries)
        assertEquals(4, traffic.dnsCacheHits)
        assertEquals(1, traffic.dnsFailures)
        assertEquals(31181, traffic.proxyPort)
        assertEquals(5_000, traffic.uptimeMs)
        assertEquals(12, traffic.dnsAvgLatencyMs)
    }

    @Test
    fun `active connections never go negative`() {
        val aggregator = TrafficStatsAggregator { 0L }
        aggregator.onConnectionClosed(success = false)
        assertEquals(0, aggregator.activeConnections())
    }

    @Test
    fun `reset clears counters`() {
        val aggregator = TrafficStatsAggregator { 0L }
        aggregator.onConnectionOpened()
        aggregator.addBytes(1, 2)
        aggregator.reset()
        val traffic = aggregator.traffic()
        assertEquals(0, traffic.totalConnections)
        assertEquals(0, traffic.bytesUp)
        assertEquals(0, traffic.bytesDown)
        assertTrue(traffic.dnsQueries == 0L)
    }

    @Test
    fun `engine stats snapshot carries flags`() {
        val aggregator = TrafficStatsAggregator { 0L }
        val stats = aggregator.engineStats(
            traffic = aggregator.traffic(),
            dnsCacheEntries = 7,
            caReady = true,
            proxyRunning = true,
            dnsRunning = false,
            lastError = null,
        )
        assertEquals(7, stats.dnsCacheEntries)
        assertTrue(stats.caReady)
        assertTrue(stats.proxyRunning)
        assertTrue(!stats.dnsRunning)
    }
}
