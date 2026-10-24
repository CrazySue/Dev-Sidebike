package dev.sidebike.core.api

/** 代理链路的路由方式。 */
enum class RouteKind {
    /** 直连目标（可能带 SNI 伪装）。 */
    DIRECT,

    /** 改写连接到镜像主机（intercepts.proxy 动作）。 */
    MIRROR_PROXY,

    /** 直接拒绝（对齐上游 abort 语义）。 */
    REJECT,

    /** 本地合成响应（intercepts.success 动作）。 */
    SYNTHETIC,
}

/**
 * 一次请求的路由决策。connectHost/connectPort 为 **上游连接目标**，
 * 与客户端请求的 host 可能不同（镜像/改写场景）。
 */
data class RoutePlan(
    val kind: RouteKind,
    val connectHost: String,
    val connectPort: Int,
    /** 建立上游 TLS 时使用的 SNI；null 表示使用 connectHost。 */
    val sni: String? = null,
    val action: InterceptAction? = null,
    val reason: String = "",
)

/**
 * 域名规则引擎（对齐上游 whiteList / excludeIpList / intercepts / 国内域名放行）。
 * 线程安全，可被代理的多个连接并发调用。配置更新通过 [updateConfig] 原子替换。
 */
interface RuleEngine {
    fun updateConfig(config: SidebikeConfig)

    /** 命中拦截动作（domain pattern + path regex）；无命中返回 null。 */
    fun match(host: String, pathAndQuery: String? = null): InterceptAction?

    fun plan(host: String, port: Int, pathAndQuery: String? = null): RoutePlan

    /** 白名单（如 *.icloud.com、局域网）→ 直连且不走代理。 */
    fun isWhitelisted(host: String): Boolean

    /** excludeIpList / 排除域名 → 不接管。 */
    fun isExcluded(host: String): Boolean

    /** 国内域名（domesticAllowList 或内置 cn 判定）→ 直连。 */
    fun isDomestic(host: String): Boolean

    /** 上游 preSetIpList：返回该域名的候选 IP（按 true 优先排序）。 */
    fun presetIps(host: String): List<String>

    /** 通配符/正则匹配（* → 任意子域；^...$ → 正则）。 */
    fun domainMatches(pattern: String, host: String): Boolean
}

/** 配置仓库（分层合并 + 持久化）。 */
interface ConfigRepository {
    fun load(): SidebikeConfig
    fun save(config: SidebikeConfig)
    /** 导入上游 Dev-Sidecar `running.json`/`config.json`（尽力兼容，未知字段忽略）。 */
    fun importUpstream(json: String): SidebikeConfig
    /** 导出当前配置 JSON。 */
    fun export(): String
    fun defaults(): SidebikeConfig
}
