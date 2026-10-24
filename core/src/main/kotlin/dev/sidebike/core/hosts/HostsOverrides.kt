package dev.sidebike.core.hosts

import dev.sidebike.core.api.SidebikeConfig

/**
 * hosts 覆盖表（对齐上游 `/etc/hosts` 语义 + 配置内 `dns.hosts`）。
 *
 * 优先级（从高到低）：
 * 1. 用户配置 `dns.hosts`（支持通配符 pattern，如 `*.example.com`）；
 * 2. 系统/内置 hosts 文件（若提供）；
 * 3. DNS provider 解析。
 *
 * 仅接受 IPv4/IPv6 字面量，非法值丢弃（避免把 hosts 值当域名二次递归解析）。
 */
class HostsOverrides internal constructor(
    private val table: Map<String, List<String>>,
) {

    /** 命中返回覆盖 IP（非空），否则 null。 */
    fun resolve(host: String): List<String>? {
        if (table.isEmpty()) return null
        direct(host)?.let { return it }
        wildcard(host)?.let { return it }
        return null
    }

    fun isEmpty(): Boolean = table.isEmpty()

    fun entries(): Map<String, List<String>> = table

    private val exact: Map<String, List<String>> = table.filterKeys { !it.contains('*') && !it.startsWith("^") }

    private val wildcardEntries: List<Pair<Regex, List<String>>> = table.entries
        .asSequence()
        .filter { key -> key.key.contains('*') || key.key.startsWith("^") }
        .mapNotNull { (pattern, ips) ->
            runCatching { Regex(compilePattern(pattern)) to ips }.getOrNull()
        }
        .toList()

    private fun direct(host: String): List<String>? = exact[host.lowercase()]?.takeIf { it.isNotEmpty() }

    private fun wildcard(host: String): List<String>? {
        val normalized = host.lowercase()
        for ((regex, ips) in wildcardEntries) {
            if (regex.matches(normalized) && ips.isNotEmpty()) return ips
        }
        return null
    }

    companion object {

        /** 从配置构建（`dns.hosts` + 调用方提供的额外 hosts 文本，如系统 hosts）。 */
        fun fromConfig(config: SidebikeConfig, extraHostsText: String? = null): HostsOverrides {
            val table = LinkedHashMap<String, List<String>>()
            if (extraHostsText != null) {
                for ((host, ips) in parseHostsFile(extraHostsText)) table[host] = ips
            }
            for ((pattern, value) in config.dns.hosts) {
                val ips = parseIpList(value) ?: continue
                table[pattern.lowercase()] = ips
            }
            return HostsOverrides(table)
        }

        /** 解析 hosts 文件文本：`ip host [host2...]`，# 注释；非法行忽略。 */
        fun parseHostsFile(text: String): Map<String, List<String>> {
            val result = LinkedHashMap<String, MutableList<String>>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.size < 2) continue
                val ip = parts[0]
                if (!isIpLiteral(ip)) continue
                for (i in 1 until parts.size) {
                    val host = parts[i].lowercase()
                    if (!isValidHost(host)) continue
                    result.getOrPut(host) { mutableListOf() }.add(ip)
                }
            }
            return result
        }

        /** 解析配置值：支持 "1.2.3.4" / "1.2.3.4,5.6.7.8" / 空格分隔。 */
        fun parseIpList(value: String): List<String>? {
            val ips = value.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            val valid = ips.filter { isIpLiteral(it) }
            return valid.ifEmpty { null }
        }

        fun isIpLiteral(value: String): Boolean {
            val v = value.trim()
            if (v.isEmpty() || v.length > 45) return false
            var seenColon = false
            for (ch in v) {
                when {
                    ch.isDigit() || ch == '.' -> Unit
                    ch == ':' -> seenColon = true
                    ch.isLetterOrDigit() && (ch == 'a' || ch == 'b' || ch == 'c' || ch == 'd' || ch == 'e' ||
                        ch == 'f' || ch == 'A' || ch == 'B' || ch == 'C' || ch == 'D' || ch == 'E' || ch == 'F') -> Unit
                    else -> return false
                }
            }
            return v.count { it == '.' } == 3 || seenColon
        }

        private fun isValidHost(host: String): Boolean =
            host.isNotEmpty() && host.length <= 253 && !host.contains(' ') &&
                host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

        /** 与上游 `util.match.js` 相同的 domain pattern 编译：`^` 开头原样正则，否则 `.`→`\.`、`*`→`.*` 后锚定。 */
        fun compilePattern(pattern: String): String {
            if (pattern.startsWith("^")) return pattern
            val builder = StringBuilder("^")
            for (ch in pattern) {
                when (ch) {
                    '.' -> builder.append("\\.")
                    '*' -> builder.append(".*")
                    else -> builder.append(ch)
                }
            }
            return builder.append('$').toString()
        }
    }
}
