package dev.sidebike.core.dns

import dev.sidebike.core.api.DnsAnswer
import dev.sidebike.core.api.DnsMode
import dev.sidebike.core.api.DnsProtocol
import dev.sidebike.core.api.DnsProviderHealth
import dev.sidebike.core.api.DnsResolver
import dev.sidebike.core.api.DnsSettings
import dev.sidebike.core.api.DnsStats
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.hosts.HostsOverrides
import dev.sidebike.core.log.NoopSidebikeLogger
import dev.sidebike.core.rules.DomainPatternMatcher
import dev.sidebike.core.rules.PresetIpTable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 默认 DNS 解析器（纯 Kotlin/JVM，Android 可用）。
 *
 * 功能对齐上游 `mitmproxy/src/lib/dns` + `proxy/mitmproxy/dnsLookup.js`，并按 Android/VPN 与"真实 GFW 网络"
 * 场景加固：
 *
 * 1. **Provider 选择**：`hosts` → `preSetIpList` → `dns.mapping` → `familyMapping` → fallback 链；
 *    境外域名优先 DoH/DoT（可信 provider），UDP/TCP 只作为回退/对照；
 * 2. **防污染（多源共识）**：境外域名至少有 1 个可信 provider 时，并行查询"可信主源 + 对照源"：
 *    - 可信结果与对照源一致 → 采用；
 *    - 不一致 → 记 warning 并**采用可信结果**（丢弃疑似被污染的一方，不缓存被污染结果）；
 *    - 可信源全部失败、只有 UDP/TCP 有结果 → 作为"未验证"结果返回，TTL 上限 [UNVERIFIED_TTL_SEC]，
 *      并在日志中明确标注（真实 GFW 网络下 DoT/DoH 可能整体不通，此时不能用"无解析"惩罚用户）；
 *    - 境外域名的 UDP/TCP 应答若包含私有/保留 IP（典型投毒特征）→ 拒绝该 IP；
 * 3. **避免串行 5s 卡死**：单 provider 硬超时 `min(queryTimeoutMs, 2500ms)`，整次解析总预算 `queryTimeoutMs`，
 *    并行 race 取最快有效结果，失败后按失败计数升序快速回退；
 * 4. **preSetIpList 兜底**：所有 provider 都失败时再次使用预置 IP（含 family 不匹配时的回退）；
 * 5. **缓存**：TTL 感知 LRU（[DnsCache]）；失败与被拒结果**不缓存**；未验证结果用短 TTL；
 * 6. **健康检查**：[health] 对每个启用 provider 发一次 `example.com A` 轻量查询，返回 ok/latency/error，
 *    任何情况不抛异常（供 `SidebikeEngine.dnsHealth()` 委托）；
 * 7. **tun 路径** [resolveRawQuery]：合成应答**不设置 TC 位**，按客户端 EDNS0 裁剪 answer；HTTPS/SVCB 等
 *    非 A/AAAA 类型返回空 NOERROR（Android 无 ECH，降级到 A/AAAA）。
 *
 * 线程模型：网络 IO 在传输层切 `Dispatchers.IO`；同 key 并发解析用 [Mutex] 单飞（single-flight）。
 */
class DefaultDnsResolver(
    initialConfig: SidebikeConfig,
    private val logger: SidebikeLogger = NoopSidebikeLogger,
    private val transport: DnsTransport = SocketDnsTransport(logger),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DnsResolver {

    @Volatile
    private var state: State = State(initialConfig, logger, transport, clock)

    /** 配置热更新：重建 provider/规则/缓存（旧缓存清空，避免跨配置脏读）。 */
    fun updateConfig(config: SidebikeConfig) {
        val old = state
        state = State(config, logger, transport, clock)
        logger.info(
            TAG,
            "DNS 配置已更新: providers=${state.endpoints.size}, mapping=${config.dns.mapping.size}, " +
                "fallback=${config.dns.fallbackProviders}, parallel=${config.dns.parallelProviders}",
        )
        old.cache.clear()
    }

    /** 当前 provider 端点（含不可用项，诊断用）。 */
    fun endpoints(): Map<String, DnsEndpoint> = state.endpoints

    // ------------------------------------------------------------------ api.DnsResolver

    override suspend fun resolve(host: String): DnsAnswer = state.resolveHost(host)

    override suspend fun resolveRawQuery(query: ByteArray): ByteArray? = state.resolveRaw(query)

    override fun clearCache() = state.cache.clear()

    override fun stats(): DnsStats = state.stats()

    override suspend fun health(): List<DnsProviderHealth> = state.health()

    override suspend fun prewarm(hosts: List<String>) {
        for (host in hosts) {
            runCatching { resolve(host) }
        }
    }

    // ------------------------------------------------------------------ implementation

    private class State(
        config: SidebikeConfig,
        private val logger: SidebikeLogger,
        private val transport: DnsTransport,
        private val clock: () -> Long,
    ) {
        private val dns: DnsSettings = config.dns
        private val mode: DnsMode = dns.mode
        private val blockIpv6: Boolean = dns.blockIpv6
        private val queryTimeoutMs: Int = dns.queryTimeoutMs.coerceIn(500, 20_000)
        private val attemptTimeoutMs: Int = minOf(queryTimeoutMs, ATTEMPT_TIMEOUT_MS)
        private val minTtlSec: Int = dns.minTtlSec.coerceAtLeast(1)
        private val maxTtlSec: Int = dns.maxTtlSec.coerceAtLeast(minTtlSec)
        private val parallel: Boolean = dns.parallelProviders
        private val fallbackNames: List<String> = dns.fallbackProviders

        /** 配置启用的全部 provider（按配置顺序；含缺少 bootstrapIps 的不可用项，供 health 报错）。 */
        val endpoints: Map<String, DnsEndpoint> = LinkedHashMap<String, DnsEndpoint>().apply {
            for ((name, provider) in dns.providers) {
                if (!provider.enabled) continue
                put(name, DnsEndpointParser.parse(name, provider))
            }
        }

        private val usableEndpoints: List<DnsEndpoint> = endpoints.values.filter { it.usable }

        init {
            val unusable = endpoints.values.filterNot { it.usable }.map { it.name }
            if (unusable.isNotEmpty()) {
                logger.warn(TAG, "以下 DNS provider 缺少 bootstrapIps（或 server 非字面 IP），运行期不可用: $unusable")
            }
        }

        private val mappingMatcher = DomainPatternMatcher(dns.mapping, logger)
        private val familyMatcher = DomainPatternMatcher(dns.familyMapping, logger)
        private val domesticMatcher = DomainPatternMatcher(config.domesticAllowList.associateWith { true }, logger)
        private val hosts: HostsOverrides = HostsOverrides.fromConfig(config)
        private val preset = PresetIpTable(config.preSetIpList)

        val cache = DnsCache(dns.cacheSize)

        private val statsQueries = AtomicLong()
        private val statsCacheHits = AtomicLong()
        private val statsFailures = AtomicLong()
        private val latencySumMs = AtomicLong()
        private val latencyCount = AtomicLong()

        private val providerFailures = ConcurrentHashMap<String, AtomicInteger>()
        private val lastErrors = ConcurrentHashMap<String, String>()
        private val singleFlights = ConcurrentHashMap<String, Mutex>()
        private val idCounter = AtomicInteger((clock() and 0xFFFF).toInt())

        // -------------------------------------------------------------- public entry

        suspend fun resolveHost(host: String): DnsAnswer {
            val normalized = DomainPatternMatcher.normalizeHost(host)
            val started = clock()
            statsQueries.incrementAndGet()
            if (normalized.isEmpty()) {
                return DnsAnswer(host, emptyList(), "invalid", false, 0, 0)
            }
            if (IpLiterals.isLiteral(normalized)) {
                return DnsAnswer(normalized, listOf(normalized), "literal", false, minTtlSec, clock() - started)
            }
            hosts.resolve(normalized)?.let { ips ->
                return DnsAnswer(normalized, ips, "hosts", false, maxTtlSec, clock() - started)
            }
            val family = if (blockIpv6) 4 else familyFor(normalized)
            preset.ips(normalized).let { presetIps ->
                if (presetIps.isNotEmpty()) {
                    val familyFiltered = filterByFamily(presetIps, family).ifEmpty { presetIps }
                    return DnsAnswer(normalized, familyFiltered, "preset", false, minTtlSec, clock() - started)
                }
            }
            cache.get(cacheKey(normalized, family), clock())?.let { cached ->
                statsCacheHits.incrementAndGet()
                return DnsAnswer(normalized, cached.ips.map { it.ip }, cached.provider, true, cached.ttlSec, clock() - started)
            }
            return singleFlight(cacheKey(normalized, family)) {
                cache.get(cacheKey(normalized, family), clock())?.let { entry ->
                    statsCacheHits.incrementAndGet()
                    DnsAnswer(normalized, entry.ips.map { it.ip }, entry.provider, true, entry.ttlSec, clock() - started)
                } ?: queryFresh(normalized, family, started)
            }
        }

        suspend fun resolveRaw(query: ByteArray): ByteArray? {
            val message = DnsCodec.parse(query) ?: return null
            if (message.isResponse || message.questions.isEmpty()) return null
            val question = message.questions[0]
            if (question.clazz != 1) return null // 仅 IN
            statsQueries.incrementAndGet()
            val payload = message.edns0?.udpPayloadSize?.coerceIn(512, 4096) ?: 512
            return try {
                when (question.type) {
                    DnsType.A -> {
                        val answer = resolveForRaw(question.name, 4)
                        if (answer.ips.isEmpty()) {
                            DnsCodec.buildReply(message, emptyList(), 2, payload) // SERVFAIL
                        } else {
                            DnsCodec.buildReply(message, answer.ips.map { IpAnswer(it, answer.ttlSec, 4) }, 0, payload)
                        }
                    }

                    DnsType.AAAA -> {
                        if (blockIpv6) {
                            DnsCodec.buildReply(message, emptyList(), 0, payload)
                        } else {
                            val answer = resolveForRaw(question.name, 6)
                            if (answer.ips.isEmpty()) {
                                DnsCodec.buildReply(message, emptyList(), 2, payload) // SERVFAIL，客户端会回退 A
                            } else {
                                DnsCodec.buildReply(message, answer.ips.map { IpAnswer(it, answer.ttlSec, 6) }, 0, payload)
                            }
                        }
                    }

                    // HTTPS(65)/SVCB(64)/ANY/其它：Android 不支持 ECH，返回空 NOERROR 让客户端回退 A/AAAA
                    else -> DnsCodec.buildReply(message, emptyList(), 0, payload)
                }
            } catch (e: Exception) {
                logger.warn(TAG, "raw DNS 查询处理失败: ${question.name}, ${e.message}")
                runCatching { DnsCodec.buildReply(message, emptyList(), 2, payload) }.getOrNull()
            }
        }

        fun stats(): DnsStats {
            val count = latencyCount.get()
            return DnsStats(
                queries = statsQueries.get(),
                cacheHits = statsCacheHits.get(),
                failures = statsFailures.get(),
                cacheEntries = cache.size(),
                avgLatencyMs = if (count > 0) latencySumMs.get() / count else 0,
            )
        }

        /**
         * Provider 健康检查：真实发起一次 `example.com A` 轻量查询（最多 [HEALTH_TIMEOUT_MS]）。
         * 单个 provider 失败不影响其他结果；任何情况不抛异常。
         */
        suspend fun health(): List<DnsProviderHealth> = coroutineScope {
            endpoints.values.map { endpoint -> async { probe(endpoint) } }.awaitAll()
        }

        private suspend fun probe(endpoint: DnsEndpoint): DnsProviderHealth {
            if (!endpoint.usable) {
                return DnsProviderHealth(
                    provider = endpoint.name,
                    ok = false,
                    latencyMs = 0,
                    error = "缺少 bootstrapIps（server 非字面 IP）",
                )
            }
            val timeoutMs = minOf(queryTimeoutMs, HEALTH_TIMEOUT_MS)
            val query = DnsCodec.buildQuery(
                host = HEALTH_PROBE_HOST,
                type = DnsType.A,
                id = idCounter.incrementAndGet() and 0xFFFF,
                edns0 = true,
                udpPayloadSize = 1232,
            )
            val started = clock()
            return try {
                val response = withTimeoutOrNull(timeoutMs.toLong() + 200L) {
                    transport.exchange(endpoint, query, timeoutMs)
                }
                val cost = clock() - started
                if (response == null) {
                    val message = "超时或无响应（${cost}ms > ${timeoutMs}ms 上限）"
                    lastErrors[endpoint.name] = message
                    recordProviderFailure(endpoint.name)
                    DnsProviderHealth(endpoint.name, false, cost, message)
                } else {
                    val parsed = DnsCodec.parse(response)
                    val valid = parsed != null && parsed.rcode == 0 &&
                        parsed.ipAnswers().any { isValidIp(it.ip) }
                    if (valid) {
                        resetProviderFailure(endpoint.name)
                        lastErrors.remove(endpoint.name)
                        DnsProviderHealth(endpoint.name, true, cost)
                    } else {
                        val message = "响应无效: rcode=${parsed?.rcode}, answers=${parsed?.ipAnswers()?.size ?: 0}, " +
                            "tc=${parsed?.isTruncated}"
                        lastErrors[endpoint.name] = message
                        recordProviderFailure(endpoint.name)
                        DnsProviderHealth(endpoint.name, false, cost, message)
                    }
                }
            } catch (e: Exception) {
                val message = "${e.javaClass.simpleName}: ${e.message}"
                lastErrors[endpoint.name] = message
                DnsProviderHealth(endpoint.name, false, clock() - started, message)
            }
        }

        // -------------------------------------------------------------- query pipeline

        private suspend fun resolveForRaw(host: String, family: Int): DnsAnswer {
            val normalized = DomainPatternMatcher.normalizeHost(host)
            if (IpLiterals.isLiteral(normalized)) {
                return DnsAnswer(normalized, listOf(normalized), "literal", false, minTtlSec, 0)
            }
            hosts.resolve(normalized)?.let { ips ->
                val filtered = filterByFamily(ips, family)
                if (filtered.isNotEmpty()) return DnsAnswer(normalized, filtered, "hosts", false, maxTtlSec, 0)
            }
            preset.ips(normalized).let { presetIps ->
                val filtered = filterByFamily(presetIps, family)
                if (filtered.isNotEmpty()) return DnsAnswer(normalized, filtered, "preset", false, minTtlSec, 0)
            }
            cache.get(cacheKey(normalized, family), clock())?.let { entry ->
                statsCacheHits.incrementAndGet()
                return DnsAnswer(normalized, entry.ips.map { it.ip }, entry.provider, true, entry.ttlSec, 0)
            }
            return queryFresh(normalized, family, clock())
        }

        private suspend fun queryFresh(host: String, family: Int, started: Long): DnsAnswer {
            val plan = buildPlan(host)
            if (plan.all.isEmpty()) {
                statsFailures.incrementAndGet()
                recordLatency(clock() - started)
                logger.warn(TAG, "域名 $host 无可用 DNS provider（检查 dns.providers/fallbackProviders 配置）")
                return DnsAnswer(host, emptyList(), "none", false, 0, clock() - started)
            }

            var attempt: Attempt? = null
            val deadline = started + queryTimeoutMs
            if (!isDomesticHost(host) && plan.trusted.isNotEmpty()) {
                attempt = consensus(host, family, plan, deadline)
            } else {
                attempt = raceOrSequential(plan.all, host, family, deadline)
            }
            if (attempt == null && family == 6) {
                logger.debug(TAG, "域名 $host 无 AAAA 结果，回退 A")
                attempt = if (!isDomesticHost(host) && plan.trusted.isNotEmpty()) {
                    consensus(host, 4, plan, deadline)
                } else {
                    raceOrSequential(plan.all, host, 4, deadline)
                }
            }
            if (attempt == null) {
                // 所有 provider 失败：preSetIpList 兜底（即使 family 不匹配也优于无解析）
                val presetIps = preset.ips(host)
                if (presetIps.isNotEmpty()) {
                    logger.warn(TAG, "域名 $host 全部 DNS provider 失败，回退 preSetIpList: $presetIps")
                    recordLatency(clock() - started)
                    return DnsAnswer(host, presetIps, "preset-fallback", false, minTtlSec, clock() - started)
                }
                statsFailures.incrementAndGet()
                recordLatency(clock() - started)
                val detail = lastErrors.entries.joinToString("; ") { "${it.key}=${it.value}" }
                logger.warn(TAG, "域名 $host 解析失败（已尝试 ${plan.all.map { it.name }}, 失败计数详情: $detail）")
                return DnsAnswer(host, emptyList(), plan.all.first().name, false, 0, clock() - started)
            }

            var ttlSec = attempt.ttlSec.coerceIn(minTtlSec, maxTtlSec)
            if (attempt.unverified) ttlSec = minOf(ttlSec, UNVERIFIED_TTL_SEC)
            val providerLabel = if (attempt.unverified) "${attempt.provider}(unverified)" else attempt.provider
            val cacheFamily = attempt.ips.firstOrNull()?.family ?: family
            cache.put(
                cacheKey(host, cacheFamily),
                DnsCache.Entry(attempt.ips, providerLabel, ttlSec, clock() + ttlSec * 1000L),
            )
            recordLatency(clock() - started)
            return DnsAnswer(host, attempt.ips.map { it.ip }, providerLabel, false, ttlSec, clock() - started)
        }

        /** 境外域名：可信 provider 与对照源并行查询并做一致性判定。 */
        private suspend fun consensus(host: String, family: Int, plan: QueryPlan, deadline: Long): Attempt? {
            val trustedEndpoints = plan.trusted
            if (trustedEndpoints.isEmpty()) return raceOrSequential(plan.all, host, family, deadline)
            val primary = trustedEndpoints.first()
            val competitors = ArrayList<DnsEndpoint>(2)
            competitors.add(primary)
            plan.mappingEndpoint?.takeIf { !isTrustedEndpoint(it) }?.let { competitors.add(it) }
            if (competitors.size < 2) {
                plan.untrusted.firstOrNull()?.let { competitors.add(it) }
            }
            if (competitors.size < 2) {
                trustedEndpoints.getOrNull(1)?.let { competitors.add(it) }
            }
            val query = buildQuery(host, family)
            val attempts = coroutineScope {
                competitors.map { endpoint ->
                    async { attempt(endpoint, query, family, host, attemptTimeoutMs) }
                }.awaitAll().filterNotNull()
            }
            val trustedAttempt = attempts.firstOrNull { isTrustedEndpointName(it.provider) }
            val untrustedAttempts = attempts.filterNot { isTrustedEndpointName(it.provider) }

            if (trustedAttempt != null) {
                for (other in untrustedAttempts) {
                    val trustedIps = trustedAttempt.ips.map { it.ip }.toSet()
                    val otherIps = other.ips.map { it.ip }.toSet()
                    if (trustedIps.intersect(otherIps).isEmpty()) {
                        logger.warn(
                            TAG,
                            "防污染: 域名 $host 的 provider '${other.provider}' 与可信 provider '${trustedAttempt.provider}' " +
                                "结果不一致，采用可信结果 $trustedIps（丢弃 ${otherIps}）",
                        )
                    }
                }
                return trustedAttempt
            }

            // 可信源失败：快速尝试其余可信 provider（顺序、短超时）
            val remainingTrusted = trustedEndpoints.filter { candidate -> attempts.none { it.provider == candidate.name } }
            sequential(remainingTrusted, host, family, deadline)?.let { return it }

            // 只有 UDP/TCP 结果：返回"未验证"结果（真实 GFW 网络下 DoT/DoH 可能整体不通）
            if (untrustedAttempts.isNotEmpty()) {
                val untrusted = untrustedAttempts.first()
                logger.warn(
                    TAG,
                    "防污染: 域名 $host 无可信 DNS 结果，仅使用未验证的 ${untrusted.provider} 结果 " +
                        "${untrusted.ips.map { it.ip }}（TTL 上限 ${UNVERIFIED_TTL_SEC}s）",
                )
                return untrusted.copy(unverified = true)
            }
            return null
        }

        private fun buildQuery(host: String, family: Int): ByteArray = DnsCodec.buildQuery(
            host = host,
            type = if (family == 6) DnsType.AAAA else DnsType.A,
            id = idCounter.incrementAndGet() and 0xFFFF,
            edns0 = true,
            udpPayloadSize = 1232,
        )

        private suspend fun raceOrSequential(
            candidates: List<DnsEndpoint>,
            host: String,
            family: Int,
            deadline: Long,
        ): Attempt? {
            if (candidates.isEmpty()) return null
            val query = buildQuery(host, family)
            if (parallel && candidates.size > 1) {
                val race = candidates.take(2)
                // 取"最先返回的有效结果"（不是列表第一个），避免慢 provider 因 awaitAll 顺序获胜
                val firstValid = coroutineScope {
                    val deferreds = race.map { endpoint ->
                        async { attempt(endpoint, query, family, host, attemptTimeoutMs) }
                    }.toMutableList()
                    try {
                        var found: Attempt? = null
                        while (deferreds.isNotEmpty()) {
                            val result = select<Attempt?> { deferreds.forEach { deferred -> deferred.onAwait { it } } }
                            deferreds.removeAll { it.isCompleted }
                            if (result != null) {
                                found = result
                                break
                            }
                        }
                        found
                    } finally {
                        deferreds.forEach { it.cancel() }
                    }
                }
                if (firstValid != null) return firstValid
                return sequential(candidates.drop(2), host, family, deadline)
            }
            return sequential(candidates, host, family, deadline)
        }

        private suspend fun sequential(
            candidates: List<DnsEndpoint>,
            host: String,
            family: Int,
            deadline: Long,
        ): Attempt? {
            for (endpoint in candidates) {
                val remaining = deadline - clock()
                if (remaining <= MIN_ATTEMPT_BUDGET_MS) break
                val timeout = minOf(attemptTimeoutMs.toLong(), remaining).toInt()
                attempt(endpoint, buildQuery(host, family), family, host, timeout)?.let { return it }
            }
            return null
        }

        private suspend fun attempt(
            endpoint: DnsEndpoint,
            query: ByteArray,
            family: Int,
            host: String,
            timeoutMs: Int,
        ): Attempt? {
            if (!endpoint.usable) return null
            val started = clock()
            val response = withTimeoutOrNull(timeoutMs.toLong() + 150L) {
                transport.exchange(endpoint, query, timeoutMs)
            } ?: run {
                val message = "超时/无响应（${clock() - started}ms）"
                lastErrors[endpoint.name] = message
                recordProviderFailure(endpoint.name)
                return null
            }
            val parsed = DnsCodec.parse(response) ?: run {
                lastErrors[endpoint.name] = "响应非法（无法解析）"
                recordProviderFailure(endpoint.name)
                return null
            }
            if (parsed.rcode != 0) {
                lastErrors[endpoint.name] = "rcode=${parsed.rcode}"
                recordProviderFailure(endpoint.name, soft = true)
                return null
            }
            var ips = parsed.ipAnswers().filter { it.family == family && isValidIp(it.ip) }
            // 境外域名的 UDP/TCP 应答若出现私有/保留 IP（典型投毒特征）→ 拒绝
            if (!isDomesticHost(host) && !isTrustedEndpoint(endpoint)) {
                val filtered = ips.filterNot { isSuspiciousPrivateIp(it.ip) }
                if (filtered.size < ips.size) {
                    logger.warn(
                        TAG,
                        "防污染: 域名 $host 的 UDP/TCP provider '${endpoint.name}' 返回可疑私有/保留 IP，已丢弃: " +
                            ips.filter { isSuspiciousPrivateIp(it.ip) }.map { it.ip },
                    )
                }
                ips = filtered
            }
            if (ips.isEmpty()) {
                lastErrors[endpoint.name] = "无有效 A/AAAA 记录"
                recordProviderFailure(endpoint.name, soft = true)
                return null
            }
            resetProviderFailure(endpoint.name)
            lastErrors.remove(endpoint.name)
            val ttl = ips.map { it.ttlSec }.filter { it > 0 }.minOrNull() ?: minTtlSec
            return Attempt(
                provider = endpoint.name,
                ips = ips,
                ttlSec = ttl,
                elapsedMs = clock() - started,
                trusted = isTrustedEndpoint(endpoint),
                // 境外域名的 UDP/TCP 结果无法交叉验证：标记 unverified（TTL 截断、UI/日志可见）
                unverified = !isTrustedEndpoint(endpoint) && !isDomesticHost(host),
            )
        }

        // -------------------------------------------------------------- candidate planning

        private fun buildPlan(host: String): QueryPlan {
            if (mode == DnsMode.SINGLE) {
                val mapped = mappingMatcher.match(host)?.let { endpoints[it] }?.takeIf { it.usable }
                val single = mapped ?: usableEndpoints.minByOrNull { providerFailureCount(it.name) }
                return planOf(listOfNotNull(single), mapped)
            }
            if (mode == DnsMode.DOH_ONLY) {
                val trusted = trustedEndpointsOrdered()
                val mapped = mappingMatcher.match(host)?.let { endpoints[it] }?.takeIf { it.usable && isTrustedEndpoint(it) }
                val ordered = LinkedHashMap<String, DnsEndpoint>()
                mapped?.let { ordered[it.name] = it }
                trusted.forEach { candidate -> ordered.putIfAbsent(candidate.name, candidate) }
                return planOf(ordered.values.toList(), mapped)
            }

            val ordered = LinkedHashMap<String, DnsEndpoint>()
            val mapped = mappingMatcher.match(host)?.let { endpoints[it] }?.takeIf { it.usable }
            if (isDomesticHost(host)) {
                // 国内：优先映射/低延迟 UDP，其次其它；不需要共识
                mapped?.let { ordered[it.name] = it }
                fallbackNames.forEach { name -> endpoints[name]?.takeIf { it.usable }?.let { ordered.putIfAbsent(it.name, it) } }
                usableEndpoints.sortedBy { providerFailureCount(it.name) }.forEach { ordered.putIfAbsent(it.name, it) }
            } else {
                // 境外：可信 DoH/DoT 优先（映射若为可信则第一），其次映射（若为 UDP 作对照），再其它
                if (mapped != null && isTrustedEndpoint(mapped)) ordered[mapped.name] = mapped
                trustedEndpointsOrdered().forEach { ordered.putIfAbsent(it.name, it) }
                mapped?.let { ordered.putIfAbsent(it.name, it) }
                fallbackNames.forEach { name -> endpoints[name]?.takeIf { it.usable }?.let { ordered.putIfAbsent(it.name, it) } }
                usableEndpoints.filterNot { isTrustedEndpoint(it) }
                    .sortedBy { providerFailureCount(it.name) }
                    .forEach { ordered.putIfAbsent(it.name, it) }
            }
            return planOf(ordered.values.toList(), mapped)
        }

        private fun planOf(all: List<DnsEndpoint>, mappingEndpoint: DnsEndpoint?): QueryPlan = QueryPlan(
            all = all,
            trusted = all.filter { isTrustedEndpoint(it) },
            untrusted = all.filterNot { isTrustedEndpoint(it) },
            mappingEndpoint = mappingEndpoint,
        )

        private fun trustedEndpointsOrdered(): List<DnsEndpoint> =
            usableEndpoints.filter { isTrustedEndpoint(it) }
                .sortedWith(compareBy({ providerFailureCount(it.name) }, { usableEndpoints.indexOf(it) }))

        private fun isTrustedEndpoint(endpoint: DnsEndpoint): Boolean =
            endpoint.protocol == DnsProtocol.DOH || endpoint.protocol == DnsProtocol.DOT

        private fun isTrustedEndpointName(name: String): Boolean {
            val base = name.substringBefore("(")
            return endpoints[base]?.let { isTrustedEndpoint(it) } ?: false
        }

        // -------------------------------------------------------------- helpers

        private fun isDomesticHost(host: String): Boolean {
            if (domesticMatcher.match(host) == true) return true
            if (host == "cn" || host.endsWith(".cn")) return true
            if (host == "localhost" || host.endsWith(".localhost")) return true
            return isPrivateIpLiteral(host)
        }

        private fun familyFor(host: String): Int = if (familyMatcher.match(host) == "6") 6 else 4

        private fun filterByFamily(ips: List<String>, family: Int): List<String> =
            ips.filter { ip -> if (family == 6) ip.contains(':') else !ip.contains(':') }

        private fun cacheKey(host: String, family: Int): String = "$host|$family"

        private fun isValidIp(ip: String): Boolean {
            if (ip.isBlank()) return false
            val v = ip.trim()
            if (v == "0.0.0.0" || v == "::" || v == "255.255.255.255") return false
            if (v.startsWith("127.")) return false
            if (v == "::1") return false
            if (v.startsWith("169.254.")) return false // link-local
            if (v.startsWith("fe80:", ignoreCase = true)) return false
            if (isIpv4(v)) {
                val first = v.substringBefore('.').toIntOrNull() ?: return false
                if (first in 224..239) return false // multicast
            }
            return true
        }

        /** 典型投毒/劫持特征：境外域名的 UDP 应答返回私有、保留、链路本地等地址。 */
        private fun isSuspiciousPrivateIp(ip: String): Boolean {
            if (!isIpv4(ip)) {
                return ip == "::1" || ip.startsWith("fe80:", ignoreCase = true) ||
                    ip.startsWith("fc", ignoreCase = true) || ip.startsWith("fd", ignoreCase = true)
            }
            val parts = ip.split('.')
            val a = parts[0].toIntOrNull() ?: return true
            val b = parts[1].toIntOrNull() ?: return true
            return a == 0 || a == 10 || a == 127 || a == 169 ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }

        private fun isPrivateIpLiteral(host: String): Boolean {
            if (!isIpv4(host)) return false
            val parts = host.split('.')
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts.getOrNull(1)?.toIntOrNull() ?: return false
            return a == 10 || a == 127 || a == 169 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }

        private fun isIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { it.toIntOrNull()?.let { p -> p in 0..255 } == true }
        }

        private fun recordProviderFailure(name: String, soft: Boolean = false) {
            providerFailures.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
            if (!soft) logger.debug(TAG, "provider '$name' 查询失败")
        }

        private fun resetProviderFailure(name: String) {
            providerFailures.computeIfAbsent(name) { AtomicInteger() }.set(0)
        }

        private fun providerFailureCount(name: String): Int = providerFailures[name]?.get() ?: 0

        private fun recordLatency(ms: Long) {
            latencySumMs.addAndGet(ms.coerceAtLeast(0))
            latencyCount.incrementAndGet()
        }

        private suspend fun <T> singleFlight(key: String, block: suspend () -> T): T {
            if (singleFlights.size > dns.cacheSize * 2) {
                singleFlights.clear()
            }
            val mutex = singleFlights.computeIfAbsent(key) { Mutex() }
            return mutex.withLock { block() }
        }

        private data class QueryPlan(
            val all: List<DnsEndpoint>,
            val trusted: List<DnsEndpoint>,
            val untrusted: List<DnsEndpoint>,
            val mappingEndpoint: DnsEndpoint?,
        )

        private data class Attempt(
            val provider: String,
            val ips: List<IpAnswer>,
            val ttlSec: Int,
            val elapsedMs: Long,
            val trusted: Boolean,
            val unverified: Boolean = false,
        )
    }

    private companion object {
        const val TAG = "DnsResolver"

        /** 单 provider 硬超时上限（避免串行 5s 卡死；用户配置更小时以配置为准）。 */
        const val ATTEMPT_TIMEOUT_MS = 2_500

        /** health() 每个 provider 的单次探测超时。 */
        const val HEALTH_TIMEOUT_MS = 2_000

        /** health() 探测域名（轻量、全球可解析）。 */
        const val HEALTH_PROBE_HOST = "example.com"

        /** 未验证（仅 UDP/TCP）结果的缓存 TTL 上限。 */
        const val UNVERIFIED_TTL_SEC = 60

        /** 顺序回退时剩余预算不足该值则不再尝试。 */
        const val MIN_ATTEMPT_BUDGET_MS = 200
    }
}
