package dev.sidebike.core.rules

import java.net.URI

/**
 * `intercepts.*.proxy` 目标解析（镜像/反代改写），对齐上游
 * `mitmproxy/src/lib/interceptor/impl/req/proxy.js:39-89 buildTargetUrl` 的简化版。
 *
 * 支持形态：
 * - 裸主机：`fonts.googleapis.cn` → `https://fonts.googleapis.cn` + 原始 path（客户端端口=443 时）；
 * - 主机:端口：`example.com:8443`；
 * - 带路径前缀：`ajax.proxy.ustclug.org/base`（最终 path = pathPrefix + 原始 path）；
 * - 绝对 URL：`https://www.recaptcha.net`。
 *
 * 不支持（返回 null，由 RuleEngine 回退 DIRECT 并告警）：
 * - 含 `${...}` 占位符（依赖上游路径/主机正则捕获组；api.InterceptAction 未建模 captures）；
 * - `replace` 正则替换（同上）；
 * - proxy.backup 备用列表（MVP 未建模）。
 */
data class ProxyTarget(
    val host: String,
    val port: Int,
    /** 目标路径前缀（无尾斜杠；空串表示无前缀）。 */
    val pathPrefix: String,
) {
    /** 把客户端原始 path（含 query）拼接到镜像目标路径。 */
    fun buildPath(originalPathAndQuery: String): String {
        val path = when {
            originalPathAndQuery.isEmpty() -> "/"
            originalPathAndQuery.startsWith("/") -> originalPathAndQuery
            else -> "/$originalPathAndQuery"
        }
        return if (pathPrefix.isEmpty()) path else "$pathPrefix$path"
    }
}

object ProxyTargetParser {

    fun parse(rawProxy: String?, clientPort: Int): ProxyTarget? {
        val raw = rawProxy?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.contains("\${")) return null // 占位符目标：MVP 不支持

        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return runCatching {
                val uri = URI(raw)
                val host = uri.host ?: return null
                val port = when {
                    uri.port > 0 -> uri.port
                    uri.scheme.equals("https", ignoreCase = true) -> 443
                    else -> 80
                }
                ProxyTarget(
                    host = host.lowercase(),
                    port = port,
                    pathPrefix = (uri.rawPath ?: "").trimEnd('/'),
                )
            }.getOrNull()
        }

        val slash = raw.indexOf('/')
        val hostPart = if (slash >= 0) raw.substring(0, slash) else raw
        val pathPrefix = (if (slash >= 0) raw.substring(slash) else "").trimEnd('/')
        if (hostPart.isEmpty()) return null

        var host = hostPart
        var port: Int? = null
        val colon = hostPart.lastIndexOf(':')
        if (colon > 0 && hostPart.indexOf(':') == colon) { // 简单 host:port（不支持 IPv6 字面量代理目标）
            val maybePort = hostPart.substring(colon + 1).toIntOrNull()
            if (maybePort != null && maybePort in 1..65535) {
                port = maybePort
                host = hostPart.substring(0, colon)
            }
        }
        if (host.isEmpty()) return null
        return ProxyTarget(
            host = host.lowercase(),
            port = port ?: defaultPortFor(clientPort),
            pathPrefix = pathPrefix,
        )
    }

    /** 无显式端口时：客户端目标是 80 → 80，其它（443/8443 等 TLS 端口）→ 443（与上游 rOptions.protocol 对齐）。 */
    private fun defaultPortFor(clientPort: Int): Int = if (clientPort == 80) 80 else 443
}
