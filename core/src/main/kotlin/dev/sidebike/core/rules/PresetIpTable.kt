package dev.sidebike.core.rules

/**
 * 上游 `preSetIpList` 的只读索引：域名 pattern → (IP → 是否启用)。
 *
 * 语义对齐上游 `dns/base.js:36-47 mapToList` + `matchHostnameAll`：
 * - 同一域名命中多条 pattern 时按优先级合并，高优先级覆盖同 IP 的启用状态；
 * - 只有真值（`true`）的 IP 参与解析；`false` 表示"保留在配置中但不使用"；
 * - 返回顺序：按配置插入顺序（高优先级 pattern 先出现），同 IP 去重。
 */
class PresetIpTable(
    entries: Map<String, Map<String, Boolean>>,
) {
    private val matcher = DomainPatternMatcher(entries)

    fun ips(host: String): List<String> {
        if (matcher.size() == 0) return emptyList()
        val merged = LinkedHashMap<String, Boolean>()
        for (match in matcher.matchAll(host)) {
            for ((ip, enabled) in match.value) {
                merged[ip] = enabled
            }
        }
        return merged.filterValues { it }.keys.toList()
    }

    fun has(host: String): Boolean = ips(host).isNotEmpty()

    fun isEmpty(): Boolean = matcher.size() == 0
}
