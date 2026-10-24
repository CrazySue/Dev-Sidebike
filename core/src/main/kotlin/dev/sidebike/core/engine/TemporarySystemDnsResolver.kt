package dev.sidebike.core.engine

import dev.sidebike.core.api.DnsAnswer
import dev.sidebike.core.api.DnsResolver
import dev.sidebike.core.api.DnsStats
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeLogger
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TEMPORARY stand-in for `dev.sidebike.core.dns.DefaultDnsResolver` while the backend-dns workstream
 * finishes the real anti-poisoning resolver (UDP/DoT/DoH providers + cache + speed-test selection).
 *
 * TODO(integration): delete this class and construct `DefaultDnsResolver(config, logger)` directly inside
 * [DefaultSidebikeEngine] as soon as `core/dns/DefaultDnsResolver.kt` lands (Lead-provided signature:
 * `DefaultDnsResolver(initialConfig, logger = NoopSidebikeLogger, transport = SocketDnsTransport(), clock = {...})`).
 * Until then the proxy uses the JVM/Android system resolver, which is NOT anti-poisoning: the tun DNS
 * interceptor receives `null` from [resolveRawQuery] (no wire-format responder), so VPN DNS fallback only
 * works once the real resolver replaces this class.
 *
 * Kept intentionally tiny - it is a bridge, not a duplicate implementation of the dns package.
 */
internal class TemporarySystemDnsResolver(
    initialConfig: SidebikeConfig,
    private val logger: SidebikeLogger,
) : DnsResolver {

    @Volatile
    private var config: SidebikeConfig = initialConfig

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private val queries = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val totalLatencyMs = AtomicLong(0)

    private data class CacheEntry(val answer: DnsAnswer, val expiresAtMs: Long)

    fun updateConfig(config: SidebikeConfig) {
        this.config = config
        cache.clear()
    }

    override suspend fun resolve(host: String): DnsAnswer = withContext(Dispatchers.IO) {
        val current = config
        val now = System.currentTimeMillis()
        cache[host]?.let { entry ->
            if (entry.expiresAtMs > now) {
                cacheHits.incrementAndGet()
                return@withContext entry.answer.copy(fromCache = true)
            }
            cache.remove(host)
        }
        queries.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        val answer = try {
            val addresses = InetAddress.getAllByName(host)
            val ips = addresses.mapNotNull { it.hostAddress }
            val ttl = current.dns.minTtlSec.coerceIn(1, current.dns.maxTtlSec)
            DnsAnswer(
                host = host,
                ips = ips,
                provider = "system",
                fromCache = false,
                ttlSec = ttl,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        } catch (e: UnknownHostException) {
            failures.incrementAndGet()
            DnsAnswer(host, emptyList(), "system", false, 0, System.currentTimeMillis() - startedAt)
        }
        totalLatencyMs.addAndGet(answer.elapsedMs)
        if (!answer.isEmpty) {
            cache[host] = CacheEntry(answer, now + answer.ttlSec * 1000L)
        }
        answer
    }

    override suspend fun resolveRawQuery(query: ByteArray): ByteArray? {
        // TODO(integration): implement by delegating to DefaultDnsResolver once it lands.
        logger.debug("engine.dns", "resolveRawQuery(${query.size}B) unavailable: temporary system resolver active")
        return null
    }

    override fun clearCache() {
        cache.clear()
    }

    override fun stats(): DnsStats {
        val count = queries.get()
        return DnsStats(
            queries = count,
            cacheHits = cacheHits.get(),
            failures = failures.get(),
            cacheEntries = cache.size,
            avgLatencyMs = if (count > 0) totalLatencyMs.get() / count else 0,
        )
    }
}
