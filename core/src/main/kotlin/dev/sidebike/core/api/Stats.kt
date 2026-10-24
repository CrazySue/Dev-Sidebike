package dev.sidebike.core.api

/** 代理/DNS 运行统计快照。所有计数单调递增（除 activeConnections/uptimeMs）。 */
data class TrafficStats(
    val totalConnections: Long = 0,
    val activeConnections: Int = 0,
    val successfulConnections: Long = 0,
    val failedConnections: Long = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val dnsQueries: Long = 0,
    val dnsCacheHits: Long = 0,
    val dnsFailures: Long = 0,
    val uptimeMs: Long = 0,
    val proxyPort: Int = 0,
    val dnsAvgLatencyMs: Long = 0,
)

data class EngineStats(
    val traffic: TrafficStats = TrafficStats(),
    val dnsCacheEntries: Int = 0,
    val caReady: Boolean = false,
    val proxyRunning: Boolean = false,
    val dnsRunning: Boolean = false,
    val lastError: String? = null,
)
