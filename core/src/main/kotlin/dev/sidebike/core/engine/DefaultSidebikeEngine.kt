package dev.sidebike.core.engine

import dev.sidebike.core.api.DnsResolver
import dev.sidebike.core.api.DnsProviderHealth
import dev.sidebike.core.api.EngineStats
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeEngine
import dev.sidebike.core.api.SidebikeEngineFactory
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.dns.DefaultDnsResolver
import dev.sidebike.core.dns.DnsCodec
import dev.sidebike.core.dns.DnsType
import dev.sidebike.core.log.RingBufferSidebikeLogger
import dev.sidebike.core.proxy.HttpProxyServer
import dev.sidebike.core.rules.DefaultRuleEngine
import dev.sidebike.core.util.Clock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Default [SidebikeEngine] implementation - the only facade the Android app is supposed to use.
 *
 * Wiring (all direct constructors, no reflection):
 *  - config: [SidebikeConfig] (updates are applied atomically to the rule engine, DNS resolver and proxy),
 *  - rules: `DefaultRuleEngine` (backend-dns workstream, task T6),
 *  - DNS: `DefaultDnsResolver` (T6) - anti-poisoning UDP/DoT/DoH providers, LRU cache and raw-DNS responder,
 *  - proxy: [HttpProxyServer] (HTTP + CONNECT + MITM),
 *  - logging: the injected logger or a `RingBufferSidebikeLogger` sized from `app.logMaxEntries`,
 *  - statistics: proxy counters + DNS stats aggregated into [EngineStats] on demand.
 *
 * Lifecycle: [start]/[stop] are idempotent and safe to call repeatedly; [close] stops the engine.
 *
 * Registration: the companion object registers the factory eagerly when the class is initialized, and
 * [install] can be called explicitly by the app bootstrap (`DevSidebikeApplication.onCreate`) so that
 * [SidebikeEngineFactory.create] never throws "not installed". Both are idempotent.
 */
class DefaultSidebikeEngine(
    private val dataDir: File,
    initialConfig: SidebikeConfig,
    logger: SidebikeLogger? = null,
    private val clock: Clock = Clock.SYSTEM,
    /** Test seam: replace the DNS resolver; production wiring uses the temporary system bridge. */
    /** Test seam: replace the DNS resolver; production wiring is [DefaultDnsResolver]. */
    dnsResolverFactory: ((SidebikeConfig, SidebikeLogger) -> DnsResolver)? = null,
) : SidebikeEngine {

    private val log: SidebikeLogger = logger ?: RingBufferSidebikeLogger(initialConfig.app.logMaxEntries)

    @Volatile
    private var config: SidebikeConfig = initialConfig

    private val configLock = Any()
    private val lifecycleLock = Any()

    private val ruleEngine = DefaultRuleEngine(initialConfig, log)

    private val dnsResolver: DnsResolver = dnsResolverFactory?.invoke(initialConfig, log)
        ?: DefaultDnsResolver(initialConfig, log)

    private val proxy = HttpProxyServer(
        dataDir = dataDir,
        initialConfig = initialConfig,
        ruleEngine = ruleEngine,
        dnsResolver = dnsResolver,
        logger = log,
        clock = clock,
        hostNeedsMitm = { host -> ruleEngine.requiresMitm(host) },
    )

    @Volatile
    private var running = false

    @Volatile
    private var startedAtMs = 0L

    @Volatile
    private var lastError: String? = null

    private val startCount = AtomicLong(0)

    // ------------------------------------------------------------------------------------------------
    // SidebikeEngine
    // ------------------------------------------------------------------------------------------------

    override fun start() {
        synchronized(lifecycleLock) {
            if (running) {
                log.debug(TAG, "start() ignored: already running")
                return
            }
            log.info(TAG, "starting engine (dataDir=${dataDir.absolutePath})")
            try {
                ruleEngine.updateConfig(config)
                proxy.updateConfig(config)
                (dnsResolver as? DefaultDnsResolver)?.updateConfig(config)
                proxy.start(config.proxy.port)
                startedAtMs = clock.currentTimeMillis()
                startCount.incrementAndGet()
                running = true
                lastError = null
                log.info(
                    TAG,
                    "engine started: proxy=127.0.0.1:${proxy.port}, mitm=${isMitmEnabled()}, " +
                        "dns=${config.dns.enabled}, interceptDomains=${config.intercepts.size}",
                )
            } catch (e: Exception) {
                lastError = "engine start failed: ${e.message}"
                log.error(TAG, "engine start failed: ${e.message}", e)
                runCatching { proxy.stop() }
                running = false
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            if (!running && !proxy.isRunning) {
                log.debug(TAG, "stop() ignored: not running")
                return
            }
            log.info(TAG, "stopping engine")
            runCatching { proxy.stop() }
                .onFailure { log.warn(TAG, "proxy stop failed: ${it.message}", it) }
            running = false
            startedAtMs = 0L
            log.info(TAG, "engine stopped")
        }
    }

    override val isRunning: Boolean get() = running && proxy.isRunning

    override fun updateConfig(config: SidebikeConfig) {
        synchronized(configLock) {
            this.config = config
        }
        log.info(
            TAG,
            "config updated: proxy.port=${config.proxy.port}, mitm=${config.proxy.mitmEnabled || config.mitm.enabled}, " +
                "intercepts=${config.intercepts.size}, dnsProviders=${config.dns.providers.size}",
        )
        runCatching { ruleEngine.updateConfig(config) }
            .onFailure { log.error(TAG, "rule engine update failed: ${it.message}", it) }
        runCatching { proxy.updateConfig(config) }
            .onFailure { log.error(TAG, "proxy config update failed: ${it.message}", it) }
        (dnsResolver as? DefaultDnsResolver)?.updateConfig(config)
    }

    override fun currentConfig(): SidebikeConfig = config

    /**
     * DNS Provider 健康检查（0.1.1 契约）：委托给 [DnsResolver.health]（backend-dns 实现真实轻量查询；
     * 未实现时接口默认返回空列表）。容错：任何异常都返回空列表，绝不抛出。
     */
    override suspend fun dnsHealth(): List<DnsProviderHealth> = try {
        dnsResolver.health()
    } catch (e: Exception) {
        log.warn(TAG, "dnsHealth failed: ${e.message}", e)
        emptyList()
    }

    override suspend fun resolveRawDnsQuery(query: ByteArray): ByteArray? = try {
        dnsResolver.resolveRawQuery(query)
    } catch (e: Exception) {
        log.warn(TAG, "resolveRawDnsQuery failed: ${e.message}", e)
        null
    }

    override fun stats(): EngineStats {
        val dnsStats = runCatching { dnsResolver.stats() }.getOrNull()
        val base = proxy.trafficSnapshot()
        val uptime = if (running && startedAtMs > 0) clock.currentTimeMillis() - startedAtMs else 0L
        val traffic = base.copy(
            dnsQueries = dnsStats?.queries ?: 0L,
            dnsCacheHits = dnsStats?.cacheHits ?: 0L,
            dnsFailures = dnsStats?.failures ?: 0L,
            dnsAvgLatencyMs = dnsStats?.avgLatencyMs ?: 0L,
            uptimeMs = uptime,
            proxyPort = if (running) proxy.port else 0,
        )
        return EngineStats(
            traffic = traffic,
            dnsCacheEntries = dnsStats?.cacheEntries ?: 0,
            caReady = caCertificatePem() != null,
            proxyRunning = proxy.isRunning,
            dnsRunning = running && config.dns.enabled,
            lastError = proxy.lastErrorOrNull() ?: lastError,
        )
    }

    override fun logger(): SidebikeLogger = log

    /**
     * MITM readiness health (0.1.1 item 2, concrete extension - not part of the frozen interface):
     * true only when MITM is enabled in the current config AND the local CA is usable, i.e. exactly the
     * preflight the proxy performs before terminating TLS. When false the proxy degrades rule-matched hosts
     * to direct tunnels; the UI can surface this as a warning.
     */
    fun mitmReady(): Boolean = try {
        proxy.mitmReady()
    } catch (e: Exception) {
        log.warn(TAG, "mitmReady check failed: ${e.message}", e)
        false
    }

    /** True when a CA certificate has been generated and is readable (independent of the MITM toggle). */
    fun caGenerated(): Boolean = proxy.caPemIfExists() != null

    override fun caCertificatePem(): String? {
        if (!isMitmEnabled()) return null
        return proxy.caPemIfExists()
    }

    override fun ensureCaCertificate(): String? = try {
        proxy.ensureCaPem()
    } catch (e: Exception) {
        log.error(TAG, "ensureCaCertificate failed: ${e.message}", e)
        null
    }

    override fun close() {
        stop()
    }

    // ------------------------------------------------------------------------------------------------
    // helpers / test seams
    // ------------------------------------------------------------------------------------------------

    private fun isMitmEnabled(): Boolean = config.proxy.mitmEnabled || config.mitm.enabled

    /**
     * DNS readiness self-check used by the app's startup fail-safe (not part of the frozen api):
     * sends a synthetic A query for [host] through the exact VPN DNS path ([resolveRawDnsQuery]) and reports
     * whether a valid, non-error reply with at least one answer byte came back within [timeoutMs].
     *
     * If this returns false the app must NOT enable the VPN's DNS route (otherwise every DNS lookup on the
     * device would black-hole); it should surface the error and keep the previous network configuration.
     *
     * Hermetic in tests (inject a fake `DnsTransport` through the engine's resolver factory); in production
     * it performs one real DNS query against the configured providers.
     */
    suspend fun selfCheckDns(host: String = "example.com", timeoutMs: Long = 3000): Boolean {
        return try {
            val query = DnsCodec.buildQuery(host, DnsType.A, id = 0x5A17)
            val reply = kotlinx.coroutines.withTimeoutOrNull(timeoutMs.coerceAtLeast(100)) {
                resolveRawDnsQuery(query)
            }
            if (reply == null || reply.size < 12) {
                log.warn(TAG, "DNS self-check failed for $host: no raw reply within ${timeoutMs}ms")
                return false
            }
            val parsed = DnsCodec.parse(reply)
            val ok = parsed != null && parsed.isResponse && parsed.rcode == 0 && parsed.answers.isNotEmpty()
            if (!ok) {
                log.warn(TAG, "DNS self-check failed for $host: rcode=${parsed?.rcode}, answers=${parsed?.answers?.size}")
            } else {
                log.info(TAG, "DNS self-check ok for $host: ${parsed?.ipAnswers()?.take(2)?.joinToString { it.ip } ?: ""}")
            }
            ok
        } catch (e: Exception) {
            log.warn(TAG, "DNS self-check error for $host: ${e.message}", e)
            false
        }
    }

    /** Exposed for hermetic engine tests: number of successful start() transitions. */
    internal fun startCount(): Long = startCount.get()

    companion object {
        private const val TAG = "engine"

        init {
            registerFactory()
        }

        /**
         * Registers the factory in [SidebikeEngineFactory]. Idempotent; safe to call from
         * `Application.onCreate` before the first `SidebikeEngineFactory.create(...)`.
         */
        fun install() {
            registerFactory()
        }

        private fun registerFactory() {
            SidebikeEngineFactory.install { dataDir, config, logger ->
                DefaultSidebikeEngine(dataDir, config, logger)
            }
        }
    }
}
