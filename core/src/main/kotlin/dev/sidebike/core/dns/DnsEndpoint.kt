package dev.sidebike.core.dns

import dev.sidebike.core.api.DnsProtocol
import dev.sidebike.core.api.DnsProvider

/**
 * DNS provider 的运行时端点（从 `DnsProvider.server` 解析）。
 *
 * 关键约束（QA/Lead 要求）：
 * - **绝不用系统 DNS 解析 provider 主机名**（否则在 VPN 内自举环/再污染）；
 * - DoH/DoT 连接只使用 [bootstrapIps] 中的字面 IP，URL/TLS 主机名仅用于 SNI / Host / 证书校验；
 * - 无可用 bootstrap IP 的 provider 视为不可用，调用方必须干净失败并记录日志。
 */
data class DnsEndpoint(
    val name: String,
    val protocol: DnsProtocol,
    /** URL/TLS 主机名（SNI/Host/证书校验用；可能是域名，也可能是字面 IP）。 */
    val host: String,
    val port: Int,
    /** DoH 路径（如 `/dns-query`）；其它协议为空串。 */
    val path: String,
    /** DoH 是否走 TLS（server 以 `https://` 开头）。 */
    val secure: Boolean,
    /** 连接时使用的 SNI；为 null 表示不发 SNI（字面 IP 且未配置伪装 SNI）。 */
    val tlsServerName: String?,
    val trustAllCerts: Boolean,
    /** 连接用字面 IP 列表（非空才可用）。 */
    val bootstrapIps: List<String>,
    val forSni: Boolean,
) {
    val isIpHost: Boolean get() = IpLiterals.isLiteral(host)

    /** 是否具备连接条件（有字面 IP 可连）。 */
    val usable: Boolean get() = bootstrapIps.isNotEmpty() || isIpHost

    /** 实际连接 IP 列表：字面 IP host 优先，其次配置的 bootstrapIps。 */
    fun connectIps(): List<String> =
        if (isIpHost) listOf(host) + bootstrapIps else bootstrapIps
}

object DnsEndpointParser {

    fun parse(name: String, provider: DnsProvider): DnsEndpoint {
        var raw = provider.server.trim()
        var scheme: String? = null
        val schemeIdx = raw.indexOf("://")
        if (schemeIdx >= 0) {
            scheme = raw.substring(0, schemeIdx).lowercase()
            raw = raw.substring(schemeIdx + 3)
        }
        var hostPort = raw
        var path = ""
        val slash = raw.indexOf('/')
        if (slash >= 0) {
            hostPort = raw.substring(0, slash)
            path = raw.substring(slash)
        }

        var host: String
        var port: Int? = null
        if (hostPort.startsWith("[")) { // IPv6 字面量
            val end = hostPort.indexOf(']')
            if (end < 0) {
                host = hostPort
            } else {
                host = hostPort.substring(1, end)
                if (end + 1 < hostPort.length && hostPort[end + 1] == ':') {
                    port = hostPort.substring(end + 2).toIntOrNull()
                }
            }
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon > 0 && hostPort.indexOf(':') == colon) {
                host = hostPort.substring(0, colon)
                port = hostPort.substring(colon + 1).toIntOrNull()
            } else {
                host = hostPort
            }
        }
        host = host.trim()
        if (host.isEmpty()) {
            return DnsEndpoint(name, provider.protocol, "", 0, path, false, null, provider.trustAllCerts, emptyList(), provider.forSni)
        }

        val protocol = when (provider.protocol) {
            DnsProtocol.AUTO -> inferProtocol(scheme)
            else -> provider.protocol
        }
        val secure = protocol == DnsProtocol.DOH && (scheme == null || scheme == "https")
        val effectivePort = port ?: when (protocol) {
            DnsProtocol.DOT -> 853
            DnsProtocol.DOH -> if (secure) 443 else 80
            else -> 53
        }
        if (protocol == DnsProtocol.DOH && path.isEmpty()) path = "/dns-query"

        val tlsServerName = provider.sni?.takeIf { it.isNotBlank() }
            ?: if (IpLiterals.isLiteral(host)) null else host
        val bootstrap = provider.bootstrapIps.filter { IpLiterals.isLiteral(it) }
            .ifEmpty { if (IpLiterals.isLiteral(host)) listOf(host) else emptyList() }

        return DnsEndpoint(
            name = name,
            protocol = protocol,
            host = host.lowercase(),
            port = effectivePort,
            path = path,
            secure = secure,
            tlsServerName = tlsServerName,
            trustAllCerts = provider.trustAllCerts,
            bootstrapIps = bootstrap,
            forSni = provider.forSni,
        )
    }

    private fun inferProtocol(scheme: String?): DnsProtocol = when (scheme) {
        "https", "http" -> DnsProtocol.DOH
        "tls", "dot" -> DnsProtocol.DOT
        "tcp" -> DnsProtocol.TCP
        else -> DnsProtocol.UDP
    }
}

/** IP 字面量判定（不触发任何解析）。 */
object IpLiterals {
    fun isLiteral(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.length > 45) return false
        // IPv4: 4 段数字
        if (v.count { it == '.' } == 3 && v.all { it.isDigit() || it == '.' }) {
            val parts = v.split('.')
            return parts.size == 4 && parts.all { it.toIntOrNull()?.let { p -> p in 0..255 } == true }
        }
        if (!v.contains(':')) return false
        var groups = 0
        var hasDoubleColon = false
        var i = 0
        while (i < v.length) {
            val ch = v[i]
            when {
                ch == ':' -> {
                    if (i + 1 < v.length && v[i + 1] == ':') {
                        if (hasDoubleColon) return false
                        hasDoubleColon = true
                        i++
                    }
                    i++
                }

                ch.isLetterOrDigit() -> {
                    var count = 0
                    while (i < v.length && v[i].isLetterOrDigit()) {
                        if (!v[i].isDigit() && v[i].lowercaseChar() !in 'a'..'f') return false
                        count++
                        i++
                    }
                    if (count > 4) return false
                    groups++
                }

                ch == '.' -> { // IPv4-mapped 尾部
                    return v.substring(i).count { it == '.' } == 3
                }

                else -> return false
            }
        }
        return hasDoubleColon || groups == 8
    }
}
