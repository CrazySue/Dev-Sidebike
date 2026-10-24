package dev.sidebike.core.stats

import dev.sidebike.core.api.DnsStats
import dev.sidebike.core.api.EngineStats
import dev.sidebike.core.api.TrafficStats
import java.util.concurrent.atomic.AtomicLong

/**
 * 流量/连接/DNS 统计聚合器（线程安全）。
 *
 * 由 `engine/DefaultSidebikeEngine` 与 `proxy/HttpProxyServer` 在请求生命周期内调用；
 * `:core` 内部只做计数，不做持久化（UI 通过 `EngineStats` 快照轮询）。
 *
 * 约定：
 * - 所有计数单调递增；`activeConnections` 为瞬时值（由 proxy 事件维护）；
 * - `uptimeMs` 由本类从创建时刻起算；
 * - DNS 统计可直接用 `DnsResolver.stats()` 覆盖（本类也接受 [mergeDnsStats] 便于聚合）。
 */
class TrafficStatsAggregator(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val startedAtMs: Long = clock()

    private val totalConnections = AtomicLong()
    private val activeConnections = AtomicLong()
    private val successfulConnections = AtomicLong()
    private val failedConnections = AtomicLong()
    private val bytesUp = AtomicLong()
    private val bytesDown = AtomicLong()

    fun onConnectionOpened() {
        totalConnections.incrementAndGet()
        activeConnections.incrementAndGet()
    }

    fun onConnectionClosed(success: Boolean) {
        activeConnections.updateAndGet { if (it > 0) it - 1 else 0 }
        if (success) successfulConnections.incrementAndGet() else failedConnections.incrementAndGet()
    }

    /** [up]/[down] 为本次请求新增字节数（不是累计值）。 */
    fun addBytes(up: Long, down: Long) {
        if (up > 0) bytesUp.addAndGet(up)
        if (down > 0) bytesDown.addAndGet(down)
    }

    fun activeConnections(): Int = activeConnections.get().toInt()

    fun totalConnections(): Long = totalConnections.get()

    fun uptimeMs(): Long = (clock() - startedAtMs).coerceAtLeast(0)

    /**
     * 生成流量快照。
     *
     * @param proxyPort 代理监听端口（未启动传 0）
     * @param dns 可选 DNS 统计（传 null 时 DNS 字段为 0）
     */
    fun traffic(proxyPort: Int = 0, dns: DnsStats? = null): TrafficStats = TrafficStats(
        totalConnections = totalConnections.get(),
        activeConnections = activeConnections.get().toInt(),
        successfulConnections = successfulConnections.get(),
        failedConnections = failedConnections.get(),
        bytesUp = bytesUp.get(),
        bytesDown = bytesDown.get(),
        dnsQueries = dns?.queries ?: 0L,
        dnsCacheHits = dns?.cacheHits ?: 0L,
        dnsFailures = dns?.failures ?: 0L,
        uptimeMs = uptimeMs(),
        proxyPort = proxyPort,
        dnsAvgLatencyMs = dns?.avgLatencyMs ?: 0L,
    )

    fun engineStats(
        traffic: TrafficStats,
        dnsCacheEntries: Int = 0,
        caReady: Boolean = false,
        proxyRunning: Boolean = false,
        dnsRunning: Boolean = false,
        lastError: String? = null,
    ): EngineStats = EngineStats(
        traffic = traffic,
        dnsCacheEntries = dnsCacheEntries,
        caReady = caReady,
        proxyRunning = proxyRunning,
        dnsRunning = dnsRunning,
        lastError = lastError,
    )

    fun reset() {
        totalConnections.set(0)
        activeConnections.set(0)
        successfulConnections.set(0)
        failedConnections.set(0)
        bytesUp.set(0)
        bytesDown.set(0)
    }
}
