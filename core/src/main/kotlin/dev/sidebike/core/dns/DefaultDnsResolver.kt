package dev.sidebike.core.dns

import dev.sidebike.core.api.DnsAnswer
import dev.sidebike.core.api.DnsMode
import dev.sidebike.core.api.DnsProtocol
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
 * 功能对齐上游 `mitmproxy/src/lib/dns` 目录 + `proxy/mitmproxy/dnsLookup.js` 的核心语义，并按 Android/VPN 约束收敛：
 * - provider：UDP / TCP / DoT / DoH（bootstrap IP 自举，**禁止系统 DNS**）；
 * - 选择：`hosts` → `preSetIpList` → `dns.mapping` → `familyMapping` → `fallbackProviders`；
 * - 防污染：多 provider 并行 race（`parallelProviders=true` 时取前 2 个候选），拒绝 0.0.0.0/127.0.0.1/链路本地/
 *   组播/广播等非法应答；只缓存通过校验的结果；按 provider 失败计数做回退排序；
 * - 缓存：TTL 感知 LRU（见 [DnsCache]）；
 * - tun 路径：[resolveRawQuery] 解析 wire-format 查询并合成应答（**不设置 TC 位**，按客户端 EDNS0 裁剪 answer）；
 *   HTTPS/SVCB 等非 A/AAAA 类型返回空 NOERROR（Android 无 ECH，降级到 A/AAAA）。
 *
 * 线程模型：所有网络 IO 在传输层切到 `Dispatchers.IO`；同一 key 的并发解析用 [Mutex] 单飞（single-flight）。
 */
class DefaultDnsResolver(
    initialConfig: SidebikeConfig,
    private val logger: SidebikeLogger = NoopSidebikeLogger,
    private val transport: DnsTransport = SocketDnsTransport(logger),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DnsResolver {

    @Volatile
    private var state: State = State(initialConfig, logger, transport, clock)

    /** 配置热更新：重建 provider/规则/缓存（缓存清空，避免跨配置脏读）。 */
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

    /** 当前可用 provider 的端点（诊断用）。 */
    fun endpoints(): Map<String, DnsEndpoint> = state.endpoints

    // ------------------------------------------------------------------ api.DnsResolver

    override suspend fun resolve(host: String): DnsAnswer = state.resolveHost(host)

    override suspend fun resolveRawQuery(query: ByteArray): ByteArray? = state.resolveRaw(query)

    override fun clearCache() = state.cache.clear()

    override fun stats(): DnsStats = state.stats()

    override suspend fun prewarm(hosts: List<String>) {
        for (host in hosts) {
            runCatching { resolve(host) }
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private class State(
        config: SidebikeConfig,
        private val logger: SidebikeLogger,
        private val transport: DnsTransport,
        private val clock: () -> Long,
    ) {
        private val dns: DnsSettings = config.dns
        private val mode: DnsMode = dns.mode
        private val blockIpv6: Boolean = dns.blockIpv6
        private val queryTimeoutMs: Int = dns.queryTimeoutMs.coerceIn(500, 30_000)
        private val queryTimeoutSec: Long = queryTimeoutMs.toLong()
        private val minTtlSec: Int = dns.minTtlSec.coerceAtLeast(1)
        private val maxTtlSec: Int = dns.maxTtlSec.coerceAtLeast(minTtlSec)
        private val parallel: Boolean = dns.parallelProviders
        private val fallbackNames: List<String> = dns.fallbackProviders

        val endpoints: Map<String, DnsEndpoint> = LinkedHashMap<String, DnsEndpoint>().apply {
            for ((name, provider) in dns.providers) {
                if (!provider.enabled) continue
                val endpoint = DnsEndpointParser.parse(name, provider)
                put(name, endpoint)
            }
        }

        init {
            val unusable = endpoints.values.filterNot { it.usable }.map { it.name }
            if (unusable.isNotEmpty()) {
                logger.warn(TAG, "以下 DNS provider 缺少 bootstrapIps（或 server 非字面 IP），运行期不可用: $unusable")
            }
        }

        private val mappingMatcher = DomainPatternMatcher(dns.mapping, logger)
        private val familyMatcher = DomainPatternMatcher(dns.familyMapping, logger)
        private val hosts: HostsOverrides = HostsOverrides.fromConfig(config)
        private val preset = PresetIpTable(config.preSetIpList)

        val cache = DnsCache(dns.cacheSize)

        private val statsQueries = AtomicLong()
        private val statsCacheHits = AtomicLong()
        private val statsFailures = AtomicLong()
        private val latencySumMs = AtomicLong()
        private val latencyCount = AtomicLong()

        private val providerFailures = ConcurrentHashMap<String, AtomicInteger>()
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
            val cached = cache.get(cacheKey(normalized, family), clock())
            if (cached != null) {
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
                            DnsCodec.buildReply(
                                message,
                                answer.ips.map { IpAnswer(it, answer.ttlSec, 4) },
                                0,
                                payload,
                            )
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
                                DnsCodec.buildReply(
                                    message,
                                    answer.ips.map { IpAnswer(it, answer.ttlSec, 6) },
                                    0,
                                    payload,
                                )
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
            return queryFresh(normalized, family, clock(), countQuery = false)
        }

        private suspend fun queryFresh(
            host: String,
            family: Int,
            started: Long,
            countQuery: Boolean = true,
        ): DnsAnswer {
            val candidateNames = candidates(host)
            if (candidateNames.isEmpty()) {
                statsFailures.incrementAndGet()
                recordLatency(clock() - started)
                return DnsAnswer(host, emptyList(), "none", false, 0, clock() - started)
            }
            val candidates = candidateNames.mapNotNull { endpoints[it] }.filter { it.usable }
            if (candidates.isEmpty()) {
                statsFailures.incrementAndGet()
                logger.warn(TAG, "域名 $host 的所有候选 provider 均不可用（缺 bootstrapIps）: $candidateNames")
                return DnsAnswer(host, emptyList(), "unusable", false, 0, clock() - started)
            }

            var attempt = performQuery(host, family, candidates)
            if (attempt == null && family == 6) {
                // AAAA 无结果时回退 A（Android 网络常见：有 IPv4 无 IPv6）
                logger.debug(TAG, "域名 $host 无 AAAA 结果，回退 A")
                attempt = performQuery(host, 4, candidates)
            }
            if (attempt == null) {
                statsFailures.incrementAndGet()
                recordLatency(clock() - started)
                return DnsAnswer(host, emptyList(), candidates.first().name, false, 0, clock() - started)
            }

            val ttlSec = attempt.ttlSec.coerceIn(minTtlSec, maxTtlSec)
            val cacheFamily = attempt.ips.firstOrNull()?.family ?: family
            cache.put(
                cacheKey(host, cacheFamily),
                DnsCache.Entry(attempt.ips, attempt.provider, ttlSec, clock() + ttlSec * 1000L),
            )
            recordLatency(clock() - started)
            return DnsAnswer(host, attempt.ips.map { it.ip }, attempt.provider, false, ttlSec, clock() - started)
        }

        private suspend fun performQuery(host: String, family: Int, candidates: List<DnsEndpoint>): Attempt? {
            val type = if (family == 6) DnsType.AAAA else DnsType.A
            val query = DnsCodec.buildQuery(
                host = host,
                type = type,
                id = idCounter.incrementAndGet() and 0xFFFF,
                edns0 = true,
                udpPayloadSize = 1232,
            )
            val raceCandidates = if (parallel && candidates.size > 1) candidates.take(2) else candidates.take(1)
            if (raceCandidates.size == 1) {
                val first = attempt(raceCandidates[0], query, family)
                if (first != null) return first
                return sequential(candidates.drop(1), query, family)
            }
            // 并行 race：取最快且通过校验的应答
            return coroutineScope {
                val deferreds = raceCandidates.map { endpoint -> async { attempt(endpoint, query, family) } }.toMutableList()
                try {
                    while (deferreds.isNotEmpty()) {
                        val result = select<Attempt?> { deferreds.forEach { deferred -> deferred.onAwait { it } } }
                        deferreds.removeAll { it.isCompleted }
                        if (result != null) return@coroutineScope result
                    }
                    null
                } finally {
                    deferreds.forEach { it.cancel() }
                }
            } ?: sequential(candidates.drop(raceCandidates.size), query, family)
        }

        private suspend fun sequential(
            candidates: List<DnsEndpoint>,
            query: ByteArray,
            family: Int,
        ): Attempt? {
            for (endpoint in candidates) {
                val result = attempt(endpoint, query, family)
                if (result != null) return result
            }
            return null
        }

        private suspend fun attempt(endpoint: DnsEndpoint, query: ByteArray, family: Int): Attempt? {
            val started = clock()
            val response = withTimeoutOrNull(queryTimeoutMs + 200L) {
                transport.exchange(endpoint, query, queryTimeoutMs)
            } ?: run {
                recordProviderFailure(endpoint.name)
                return null
            }
            val message = DnsCodec.parse(response) ?: run {
                recordProviderFailure(endpoint.name)
                return null
            }
            val ips = message.ipAnswers()
                .filter { it.family == family }
                .filter { isValidIp(it.ip) }
            if (ips.isEmpty()) {
                recordProviderFailure(endpoint.name, soft = true)
                return null
            }
            resetProviderFailure(endpoint.name)
            val ttl = ips.map { it.ttlSec }.filter { it > 0 }.minOrNull() ?: minTtlSec
            return Attempt(endpoint.name, ips, ttl, clock() - started)
        }

        // -------------------------------------------------------------- helpers

        private fun candidates(host: String): List<String> {
            val names = LinkedHashSet<String>()
            mappingMatcher.match(host)?.let { names.add(it) }
            if (mode == DnsMode.SINGLE) {
                if (names.isEmpty()) endpoints.keys.firstOrNull { endpoints[it]?.usable == true }?.let { names.add(it) }
                return names.toList()
            }
            if (mode == DnsMode.DOH_ONLY) {
                val doh = endpoints.filterValues { it.protocol == DnsProtocol.DOH && it.usable }.keys
                if (names.none { endpoints[it]?.protocol == DnsProtocol.DOH }) {
                    names.clear()
                    names.addAll(doh.take(1))
                }
                return names.toList()
            }
            // 主候选缺失时：取第一个可用 fallback
            if (names.none { endpoints[it]?.usable == true }) {
                fallbackNames.firstOrNull { endpoints[it]?.usable == true }?.let { names.add(it) }
            }
            // 并行/回退候选：parallel 时补足最多 2 个候选；串行时按失败次数升序追加全部回退候选
            if (parallel) {
                val sortedFallbacks = fallbackNames.asSequence()
                    .filter { endpoints[it]?.usable == true }
                    .sortedBy { providerFailureCount(it) }
                for (name in sortedFallbacks) {
                    if (names.size >= 2) break
                    names.add(name)
                }
            } else {
                val sortedFallbacks = fallbackNames.asSequence()
                    .filter { endpoints[it]?.usable == true }
                    .sortedBy { providerFailureCount(it) }
                for (name in sortedFallbacks) {
                    names.add(name)
                }
            }
            if (names.isEmpty()) {
                endpoints.entries.firstOrNull { it.value.usable }?.let { names.add(it.key) }
            }
            return names.filter { endpoints[it] != null }.toList()
        }

        private fun familyFor(host: String): Int {
            val family = familyMatcher.match(host)
            return if (family == "6") 6 else 4
        }

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

        private fun isIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { it.toIntOrNull()?.let { p -> p in 0..255 } == true }
        }

        private fun recordProviderFailure(name: String, soft: Boolean = false) {
            providerFailures.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
            logger.debug(TAG, "provider '$name' 查询失败${if (soft) "（无有效应答）" else ""}")
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

        private data class Attempt(
            val provider: String,
            val ips: List<IpAnswer>,
            val ttlSec: Int,
            val elapsedMs: Long,
        )
    }

    private companion object {
        const val TAG = "DnsResolver"
    }
}
