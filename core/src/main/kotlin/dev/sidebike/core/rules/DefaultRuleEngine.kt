package dev.sidebike.core.rules

import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.RouteKind
import dev.sidebike.core.api.RoutePlan
import dev.sidebike.core.api.RuleEngine
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.log.NoopSidebikeLogger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 默认域名规则引擎（对齐上游 Dev-Sidecar 的 `whiteList / excludeIpList / intercepts / 国内域名放行`）。
 *
 * 使用方式（proxy 侧）：
 * ```kotlin
 * val engine = DefaultRuleEngine(config, logger)
 * val plan  = engine.plan(host, port, pathAndQuery)   // 每个 HTTP 请求调用
 * val mitm  = engine.requiresMitm(host)               // CONNECT 阶段、path 未知时调用
 * engine.updateConfig(newConfig)                      // 配置热更新（原子替换 + 清缓存）
 * ```
 *
 * ## `plan(host, port, null)` 的预判定语义（与 Lead/proxy-dev 约定）
 * CONNECT 阶段只有 host/port，没有 path。此时：
 * - 若该 host 命中任意 `intercepts` 规则（含所有路径专属规则）→ 返回 `RouteKind.DIRECT`，
 *   但带上合并后的 `action` 与 `reason = "path-rule-pending-mitm"`，供代理决定是否开启 MITM；
 * - 未命中 → `RouteKind.DIRECT, action = null, reason = "no-intercept"`。
 *
 * 真正的路由（MIRROR_PROXY/SYNTHETIC/REJECT）只在 path 已知的请求级 [plan] 中产生。
 * 代理侧也可直接调用（非 api 接口）[requiresMitm] 获得布尔判定，二者等价。
 *
 * ## 性能
 * - 域名 pattern 在 [updateConfig] 时一次性预编译（精确匹配 O(1)，前缀通配 O(1)，正则按插入顺序）；
 * - path 正则同样预编译；请求级 plan 结果使用 LRU 缓存（1024 条），配置更新时清空。
 * - 线程安全：状态用 `@Volatile` 原子替换；缓存用 synchronized 小临界区。
 */
class DefaultRuleEngine(
    initialConfig: SidebikeConfig,
    private val logger: SidebikeLogger = NoopSidebikeLogger,
) : RuleEngine {

    @Volatile
    private var compiled: Compiled = Compiled(initialConfig, logger)

    @Volatile
    private var generation: Int = 1

    private val planCache = object : LinkedHashMap<String, RoutePlan>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RoutePlan>?): Boolean =
            size > CACHE_SIZE
    }

    private val planLock = Any()

    // 单 pattern 匹配器缓存（domainMatches 用）
    private val singleMatchers = object : LinkedHashMap<String, DomainPatternMatcher<Boolean>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DomainPatternMatcher<Boolean>>?): Boolean =
            size > 256
    }

    private val singleLock = Any()

    override fun updateConfig(config: SidebikeConfig) {
        compiled = Compiled(config, logger)
        generation++
        synchronized(planLock) { planCache.clear() }
        logger.info(TAG, "规则引擎配置已更新: intercepts=${config.intercepts.size} 域, whitelist=${config.whiteList.size}")
    }

    fun configVersion(): Int = generation

    // ------------------------------------------------------------------ api.RuleEngine

    override fun match(host: String, pathAndQuery: String?): InterceptAction? =
        compiled.matchAction(host, pathAndQuery)

    override fun plan(host: String, port: Int, pathAndQuery: String?): RoutePlan {
        val normalizedHost = DomainPatternMatcher.normalizeHost(host)
        val key = "$normalizedHost|$port|${pathAndQuery ?: "\u0000"}"
        synchronized(planLock) { planCache[key]?.let { return it } }
        val plan = buildPlan(normalizedHost, port, pathAndQuery)
        synchronized(planLock) { planCache[key] = plan }
        return plan
    }

    override fun isWhitelisted(host: String): Boolean =
        compiled.whiteList.match(host) == true

    override fun isExcluded(host: String): Boolean =
        compiled.excludeIpList.match(host) == true

    override fun isDomestic(host: String): Boolean = compiled.isDomestic(host)

    override fun presetIps(host: String): List<String> = compiled.preset.ips(host)

    override fun domainMatches(pattern: String, host: String): Boolean {
        if (pattern == host) return true
        val matcher = synchronized(singleLock) {
            singleMatchers.getOrPut(pattern) { DomainPatternMatcher.single(pattern, logger) }
        }
        return matcher.match(host) == true
    }

    // ------------------------------------------------------------------ 扩展（非 api，供 proxy-dev 直接调用）

    /** CONNECT 阶段判断该 host 是否有任何拦截规则（= 是否需要 MITM）。 */
    fun requiresMitm(host: String): Boolean = compiled.requiresMitm(host)

    /** 该 host 是否有任何拦截规则（含 path 专属规则）。 */
    fun hasIntercept(host: String): Boolean = compiled.requiresMitm(host)

    // ------------------------------------------------------------------ 内部

    private fun buildPlan(host: String, port: Int, pathAndQuery: String?): RoutePlan {
        if (compiled.isExcluded(host)) {
            return RoutePlan(RouteKind.DIRECT, host, port, reason = REASON_EXCLUDED)
        }
        if (compiled.isWhitelisted(host)) {
            return RoutePlan(RouteKind.DIRECT, host, port, reason = REASON_WHITELIST)
        }

        val action = compiled.matchAction(host, pathAndQuery)
        if (pathAndQuery == null) {
            return if (compiled.requiresMitm(host)) {
                RoutePlan(
                    kind = RouteKind.DIRECT,
                    connectHost = host,
                    connectPort = port,
                    sni = action?.sni,
                    action = action ?: pendingMarker(),
                    reason = REASON_PATH_RULE_PENDING,
                )
            } else {
                RoutePlan(RouteKind.DIRECT, host, port, reason = REASON_NO_INTERCEPT)
            }
        }

        if (action == null) {
            return RoutePlan(
                RouteKind.DIRECT,
                host,
                port,
                reason = if (compiled.isDomestic(host)) REASON_DOMESTIC else REASON_NO_RULE,
            )
        }

        if (isAbortMarker(action.success)) {
            return RoutePlan(RouteKind.REJECT, host, port, sni = action.sni, action = action, reason = "abort")
        }
        if (action.success != null) {
            return RoutePlan(RouteKind.SYNTHETIC, host, port, sni = action.sni, action = action, reason = "success")
        }
        if (action.proxy != null) {
            val target = ProxyTargetParser.parse(action.proxy, port)
            return if (target == null) {
                logger.warn(TAG, "proxy 目标不可解析（含占位符/非法地址），回退 DIRECT: host=$host, proxy=${action.proxy}")
                RoutePlan(RouteKind.DIRECT, host, port, sni = action.sni, action = action, reason = "proxy-unresolvable")
            } else {
                RoutePlan(
                    kind = RouteKind.MIRROR_PROXY,
                    connectHost = target.host,
                    connectPort = target.port,
                    sni = action.sni,
                    action = action,
                    reason = "proxy",
                )
            }
        }
        if (action.status != null) {
            return RoutePlan(RouteKind.SYNTHETIC, host, port, sni = action.sni, action = action, reason = "status")
        }
        return RoutePlan(
            RouteKind.DIRECT,
            host,
            port,
            sni = action.sni,
            action = action,
            reason = if (action.sni != null) "sni" else "intercept",
        )
    }

    private fun pendingMarker(): InterceptAction = InterceptAction(desc = REASON_PATH_RULE_PENDING)

    private fun isAbortMarker(success: JsonElement?): Boolean {
        val obj = success as? JsonObject ?: return false
        val abort = obj["abort"] as? JsonPrimitive ?: return false
        return abort.booleanOrNull == true
    }

    private class Compiled(config: SidebikeConfig, logger: SidebikeLogger) {
        val version: Int = config.version

        private val domainRules: DomainPatternMatcher<List<CompiledPath>> =
            DomainPatternMatcher(
                config.intercepts.mapValues { (_, paths) -> compilePaths(paths, logger) },
                logger,
            )

        val whiteList: DomainPatternMatcher<Boolean> =
            DomainPatternMatcher(config.whiteList.filterValues { it != null }, logger)

        val excludeIpList: DomainPatternMatcher<Boolean> =
            DomainPatternMatcher(config.excludeIpList.filterValues { it != null }, logger)

        private val domestic: DomainPatternMatcher<Boolean> =
            DomainPatternMatcher(config.domesticAllowList.associateWith { true }, logger)

        val preset: PresetIpTable = PresetIpTable(config.preSetIpList)

        fun requiresMitm(host: String): Boolean = domainRules.any(DomainPatternMatcher.normalizeHost(host))

        fun isWhitelisted(host: String): Boolean = whiteList.match(host) == true

        fun isExcluded(host: String): Boolean = excludeIpList.match(host) == true

        fun matchAction(host: String, pathAndQuery: String?): InterceptAction? {
            val normalized = DomainPatternMatcher.normalizeHost(host)
            val domainMatches = domainRules.matchAll(normalized)
            if (domainMatches.isEmpty()) return null

            var merged: InterceptAction? = null
            if (pathAndQuery == null) {
                // 路径未知：合并所有路径规则，供 CONNECT 阶段预判
                for (domainMatch in domainMatches) {
                    for (pathRule in domainMatch.value) {
                        merged = mergeActions(merged, pathRule.action)
                    }
                }
                return merged
            }
            for (domainMatch in domainMatches) {
                for (pathRule in domainMatch.value) {
                    val regex = pathRule.regex
                    if (regex == null || regex.containsMatchIn(pathAndQuery)) {
                        merged = mergeActions(merged, pathRule.action)
                    }
                }
            }
            return merged
        }

        fun isDomestic(host: String): Boolean {
            if (domestic.match(host) == true) return true
            if (host == "cn" || host.endsWith(".cn")) return true
            if (host == "localhost" || host.endsWith(".localhost")) return true
            return isPrivateIpLiteral(host)
        }

        private fun compilePaths(
            paths: Map<String, InterceptAction>,
            logger: SidebikeLogger,
        ): List<CompiledPath> = paths.map { (pattern, action) ->
            CompiledPath(pattern, compilePathRegex(pattern, logger), action)
        }

        private fun compilePathRegex(pattern: String, logger: SidebikeLogger): Regex? {
            if (pattern == ".*" || pattern == "*" || pattern == "true") return null
            var regexPattern = pattern
            if (regexPattern.isNotEmpty() &&
                (regexPattern[0] == '*' || regexPattern[0] == '?' || regexPattern[0] == '+')
            ) {
                regexPattern = ".$regexPattern" // 对齐上游 isMatched：前导 *?+ 补一个点，使 `*.js` 也能当正则
            }
            return runCatching { Regex(regexPattern) }.getOrElse {
                logger.warn(TAG, "非法 path 正则，按不匹配处理: pattern='$pattern', error=${it.message}")
                null
            }
        }

        private data class CompiledPath(val pattern: String, val regex: Regex?, val action: InterceptAction)
    }

    companion object {
        private const val TAG = "RuleEngine"
        private const val CACHE_SIZE = 1024

        const val REASON_PATH_RULE_PENDING = "path-rule-pending-mitm"
        const val REASON_NO_INTERCEPT = "no-intercept"
        const val REASON_NO_RULE = "no-rule"
        const val REASON_DOMESTIC = "domestic"
        const val REASON_WHITELIST = "whitelist"
        const val REASON_EXCLUDED = "excluded"

        /** 合并两条动作：override 的非空字段覆盖 base（对应上游多个拦截器叠加的简化模型）。 */
        fun mergeActions(base: InterceptAction?, override: InterceptAction): InterceptAction {
            if (base == null) return override
            return base.copy(
                sni = override.sni ?: base.sni,
                proxy = override.proxy ?: base.proxy,
                success = override.success ?: base.success,
                script = override.script ?: base.script,
                tampermonkeyScript = override.tampermonkeyScript ?: base.tampermonkeyScript,
                requestReplace = override.requestReplace ?: base.requestReplace,
                responseReplace = override.responseReplace ?: base.responseReplace,
                cacheDays = override.cacheDays ?: base.cacheDays,
                status = override.status ?: base.status,
                desc = override.desc ?: base.desc,
                remark = override.remark ?: base.remark,
            )
        }

        internal fun isPrivateIpLiteral(host: String): Boolean {
            val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").find(host) ?: return false
            val (a, b) = ipv4.destructured
            val first = a.toIntOrNull() ?: return false
            val second = b.toIntOrNull() ?: return false
            return first == 10 || first == 127 || (first == 192 && second == 168) ||
                (first == 172 && second in 16..31) || first == 169 || first == 0
        }
    }
}
