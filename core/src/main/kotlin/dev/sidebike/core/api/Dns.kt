package dev.sidebike.core.api

/** DNS 解析结果。 */
data class DnsAnswer(
    val host: String,
    /** 有效 IP 列表（已去重、按优选顺序）。 */
    val ips: List<String>,
    val provider: String,
    val fromCache: Boolean,
    val ttlSec: Int,
    val elapsedMs: Long,
) {
    val isEmpty: Boolean get() = ips.isEmpty()
}

data class DnsStats(
    val queries: Long = 0,
    val cacheHits: Long = 0,
    val failures: Long = 0,
    val cacheEntries: Int = 0,
    val avgLatencyMs: Long = 0,
)

/**
 * DNS 解析器。VPN 的 tun DNS 拦截与 HTTP 代理内部解析共用。
 */
interface DnsResolver {
    /** 解析域名（默认 A 记录）。 */
    suspend fun resolve(host: String): DnsAnswer

    /** 直接解析 wire-format DNS query（tun 拦截路径），返回 wire-format 响应；失败返回 null。 */
    suspend fun resolveRawQuery(query: ByteArray): ByteArray?

    /** 清空缓存（配置变更/网络切换时调用）。 */
    fun clearCache()

    fun stats(): DnsStats

    /** 预解析（启动预热，可空实现）。 */
    suspend fun prewarm(hosts: List<String>) = Unit

    /**
     * Provider 健康检查（真实发起轻量查询，供"网络诊断"与设置页状态展示）。
     * 实现必须容错：单个 provider 失败不影响其他结果，任何情况下不抛出异常。
     */
    suspend fun health(): List<DnsProviderHealth> = emptyList()
}
