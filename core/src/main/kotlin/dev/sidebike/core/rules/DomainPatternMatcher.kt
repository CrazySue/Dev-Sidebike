package dev.sidebike.core.rules

import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.log.NoopSidebikeLogger

/**
 * 域名 pattern 匹配器，语义对齐上游 `mitmproxy/src/utils/util.match.js`（**必须逐条保持一致**）：
 *
 * - 编译规则（`domainMapRegexply`，上游 44-76 行）：
 *   - `.xxx.com` → 若同时存在 `*.xxx.com` 则忽略，否则视为 `*.xxx.com`；
 *   - 含 `*` 或以 `^` 开头 → 正则：`^` 开头原样；否则把 `.`→`\.`、`*`→`.*` 后 `^...$` 锚定；
 *   - 不含 `*`、不以 `^` 开头 → 精确匹配；
 *   - 形如 `*suffix`（仅一个前导 `*`）同时进入"前缀通配"快查表。
 * - 单值匹配（`matchHostname`）：精确 → `*.host` 前缀 → `*host` 前缀 → 正则按插入顺序首个命中；
 * - 多值匹配（`matchHostnameAll`）：正则（按插入顺序，全部 merge）→ `*host` → `*.host` → 精确（优先级递增）。
 *
 * 注意：`match` 的前缀查找顺序是 `*.host` 先于 `*host`（上游 97-106 行），
 * 而 `matchAll` 的合并顺序是 `*host` 先于 `*.host`（上游 185-203 行），两者不可互换。
 */
class DomainPatternMatcher<T : Any>(
    rawPatterns: Map<String, T?>,
    private val logger: SidebikeLogger = NoopSidebikeLogger,
) {

    data class Match<T : Any>(val pattern: String, val value: T, val priority: Int)

    private class RegexEntry<T : Any>(val pattern: String, val regex: Regex, val value: T)

    private val exact = LinkedHashMap<String, T>()
    private val prefixWildcard = LinkedHashMap<String, T>()
    private val regexEntries = ArrayList<RegexEntry<T>>()

    init {
        // 第一遍：`.xxx.com` 归一化为 `*.xxx.com`（若后者已存在则丢弃前者，对齐上游）
        val normalized = LinkedHashMap<String, T?>()
        for ((rawKey, value) in rawPatterns) {
            var key = rawKey
            if (key.startsWith(".")) {
                val starKey = "*$key"
                if (rawPatterns.containsKey(starKey)) continue
                key = starKey
            }
            normalized[key] = value
        }
        for ((pattern, value) in normalized) {
            if (value == null) continue
            val hasStar = pattern.contains('*')
            val isRegex = hasStar || pattern.startsWith("^")
            when {
                pattern == "true" || pattern == "*" || pattern == ".*" -> {
                    // 上游把 `true`/`*`/`.*` 视为 `^.*$`（isMatched/domainRegexply 的特例）
                    regexEntries.add(RegexEntry(pattern, Regex("^.*$"), value))
                }

                isRegex -> {
                    val compiled = runCatching { Regex(compilePattern(pattern)) }.getOrNull()
                    if (compiled == null) {
                        logger.warn(TAG, "非法域名正则，已忽略: pattern='$pattern'")
                        continue
                    }
                    regexEntries.add(RegexEntry(pattern, compiled, value))
                    if (hasStar && pattern.startsWith("*") && pattern.count { it == '*' } == 1) {
                        prefixWildcard[pattern] = value
                    }
                }

                else -> exact[pattern] = value
            }
        }
    }

    /** 单值匹配（上游 `matchHostname` 语义）。未命中返回 null。 */
    fun match(host: String): T? {
        val normalized = normalizeHost(host)
        exact[normalized]?.let { return it }
        prefixWildcard["*.$normalized"]?.let { return it }
        prefixWildcard["*$normalized"]?.let { return it }
        for (entry in regexEntries) {
            if (entry.regex.containsMatchIn(normalized)) return entry.value
        }
        return null
    }

    /**
     * 多值匹配（上游 `matchHostnameAll` 语义），返回按优先级升序的命中列表：
     * 正则（插入顺序）→ `*host` → `*.host` → 精确。
     */
    fun matchAll(host: String): List<Match<T>> {
        val normalized = normalizeHost(host)
        val result = ArrayList<Match<T>>(4)
        for (entry in regexEntries) {
            if (entry.regex.containsMatchIn(normalized)) {
                result.add(Match(entry.pattern, entry.value, PRIORITY_REGEX))
            }
        }
        prefixWildcard["*$normalized"]?.let { result.add(Match("*$normalized", it, PRIORITY_STAR_HOST)) }
        prefixWildcard["*.$normalized"]?.let { result.add(Match("*.$normalized", it, PRIORITY_STAR_DOT_HOST)) }
        exact[normalized]?.let { result.add(Match(normalized, it, PRIORITY_EXACT)) }
        return result
    }

    fun any(host: String): Boolean = matchAll(host).isNotEmpty()

    fun size(): Int = exact.size + prefixWildcard.size + regexEntries.size

    companion object {
        private const val TAG = "DomainMatcher"

        const val PRIORITY_REGEX = 1
        const val PRIORITY_STAR_HOST = 2
        const val PRIORITY_STAR_DOT_HOST = 3
        const val PRIORITY_EXACT = 4

        fun normalizeHost(host: String): String = host.trim().trimEnd('.').lowercase()

        /**
         * 与上游 `domainRegexply` 一致的编译：`^` 开头原样；否则 `.`→`\.`、`*`→`.*` 后锚定。
         * 其它字符原样保留（上游不转义其它元字符，历史规则依赖该行为，如 `(?<pre>.*)`）。
         */
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

        /** 构造只含单 pattern 的匹配器（`RuleEngine.domainMatches` 用）。 */
        fun single(pattern: String, logger: SidebikeLogger = NoopSidebikeLogger): DomainPatternMatcher<Boolean> =
            DomainPatternMatcher(mapOf(pattern to true), logger)
    }
}
