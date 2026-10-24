package dev.sidebike.core.proxy

import dev.sidebike.core.api.DnsResolver
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.ProxyResult
import dev.sidebike.core.api.ProxyServer
import dev.sidebike.core.api.RouteKind
import dev.sidebike.core.api.RoutePlan
import dev.sidebike.core.api.RuleEngine
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.api.TrafficStats
import dev.sidebike.core.cert.CaStore
import dev.sidebike.core.mitm.MitmEngine
import dev.sidebike.core.mitm.ResponseCache
import dev.sidebike.core.util.ByteRelay
import dev.sidebike.core.util.Clock
import dev.sidebike.core.util.CountingInputStream
import dev.sidebike.core.util.CountingOutputStream
import dev.sidebike.core.util.HttpCodec
import dev.sidebike.core.util.HttpRequestHead
import dev.sidebike.core.util.MalformedHttpException
import dev.sidebike.core.util.NetUtils
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Local HTTP/HTTPS proxy on `127.0.0.1` - the Android equivalent of the single upstream port 31181
 * (`mitmproxy/src/lib/proxy/mitmproxy/index.js:80-152`, HTTP proxy + CONNECT share one port).
 *
 * Routing:
 *  - plain HTTP (absolute-form request target) -> [HttpForwarder] (request/response interceptor actions
 *    apply directly, no MITM needed since the traffic is already plaintext);
 *  - `CONNECT host:port` -> [RuleEngine.plan] decides:
 *      * `REJECT`   -> 403 + close (upstream `abort`);
 *      * `SYNTHETIC` with MITM off -> 403 (a synthetic body cannot be delivered without terminating TLS);
 *      * needs HTTP rewriting / SNI decoy / synthetic (and MITM enabled) -> [MitmEngine] terminates TLS and
 *        forwards the decrypted request;
 *      * otherwise -> transparent byte tunnel ([ByteRelay]) to the resolved upstream; a configured `sni`
 *        without MITM only logs a warning and falls back to a direct tunnel (task requirement).
 *
 * Robustness: one worker thread per connection (daemon, bounded by `proxy.maxConnections`), all sockets are
 * tracked and force-closed on [stop], `SO_TIMEOUT` on every blocking read, byte counters per connection.
 */
class HttpProxyServer(
    private val dataDir: File,
    initialConfig: SidebikeConfig,
    private val ruleEngine: RuleEngine,
    private val dnsResolver: DnsResolver,
    private val logger: SidebikeLogger,
    private val clock: Clock = Clock.SYSTEM,
    /**
     * CONNECT-phase MITM decision hook. `RuleEngine.plan(host, port, null)` cannot see the path, so a host
     * whose only rules are path-specific must still be inspected. The engine passes
     * `DefaultRuleEngine::requiresMitm`; when null we fall back to plan/action heuristics (enough for fake
     * rule engines in tests).
     */
    private val hostNeedsMitm: ((String) -> Boolean)? = null,
) : ProxyServer {

    @Volatile
    private var config: SidebikeConfig = initialConfig

    private val configProvider: () -> SidebikeConfig = { config }

    private val caStore = CaStore(
        dataDir = dataDir,
        clock = clock,
        logger = logger,
        certFileName = initialConfig.mitm.caCertFileName,
        keyFileName = initialConfig.mitm.caKeyFileName,
        maxLeafCache = initialConfig.mitm.maxLeafCache,
        leafValidDays = initialConfig.mitm.leafValidDays,
    )
    private val connector = DefaultUpstreamConnector(
        configProvider = configProvider,
        dnsResolver = dnsResolver,
        presetIpProvider = { host -> runCatching { ruleEngine.presetIps(host) }.getOrDefault(emptyList()) },
        logger = logger,
    )
    private val responseCache = ResponseCache(clock)
    private val forwarder = HttpForwarder(configProvider, ruleEngine, logger, clock, connector, responseCache)
    private val mitmEngine = MitmEngine(configProvider, caStore, forwarder, ruleEngine, logger, clock)

    private val lock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var listeningPort = 0

    @Volatile
    private var workers: ExecutorService? = null

    private val workerSeq = AtomicInteger(0)
    private val activeSockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    /** Hosts already warned about the MIRROR_PROXY-without-MITM downgrade (avoid log spam per connection). */
    private val warnedMirrorFallbackHosts: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // ---- statistics (exposed to the engine via trafficSnapshot()) ----
    private val totalConnections = AtomicLong(0)
    private val successfulConnections = AtomicLong(0)
    private val failedConnections = AtomicLong(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val activeConnections = AtomicInteger(0)

    @Volatile
    private var startedAtMs = 0L

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var resultListener: ((String, ProxyResult) -> Unit)? = null

    // --------------------------------------------------------------------------------------------
    // ProxyServer API
    // --------------------------------------------------------------------------------------------

    override fun start(port: Int) {
        synchronized(lock) {
            if (running) return
            val socket = ServerSocket()
            socket.reuseAddress = true
            try {
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            } catch (e: IOException) {
                NetUtils.closeQuietly(socket)
                throw IOException("cannot bind proxy port $port on 127.0.0.1: ${e.message}", e)
            }
            serverSocket = socket
            listeningPort = socket.localPort
            startedAtMs = clock.currentTimeMillis()
            running = true
            val executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "ds-proxy-worker-${workerSeq.incrementAndGet()}").apply { isDaemon = true }
            }
            workers = executor
            val acceptor = Thread({ acceptLoop(socket) }, "ds-proxy-accept")
            acceptor.isDaemon = true
            acceptThread = acceptor
            acceptor.start()
            logger.info(
                TAG,
                "proxy listening on 127.0.0.1:$listeningPort (mitm=${isMitmEnabled()}, maxConnections=${config.proxy.maxConnections})",
            )
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!running && serverSocket == null) return
            running = false
            val socket = serverSocket
            serverSocket = null
            NetUtils.closeQuietly(socket)
            acceptThread?.let { thread ->
                thread.interrupt()
                try {
                    thread.join(2000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            acceptThread = null
            // Closing every tracked socket unblocks workers stuck in reads (tunnels, TLS handshakes, ...).
            for (client in activeSockets.toList()) NetUtils.closeQuietly(client)
            activeSockets.clear()
            val executor = workers
            workers = null
            executor?.shutdownNow()
            if (executor != null) {
                try {
                    executor.awaitTermination(3, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            listeningPort = 0
            logger.info(TAG, "proxy stopped")
        }
    }

    override val isRunning: Boolean get() = running

    override val port: Int
        get() = if (listeningPort > 0) listeningPort else config.proxy.port

    override fun setResultListener(listener: (host: String, result: ProxyResult) -> Unit) {
        resultListener = listener
    }

    override fun updateConfig(config: SidebikeConfig) {
        this.config = config
    }

    // --------------------------------------------------------------------------------------------
    // Internal surface for DefaultSidebikeEngine
    // --------------------------------------------------------------------------------------------

    internal fun isMitmEnabled(): Boolean = config.proxy.mitmEnabled || config.mitm.enabled

    internal fun caPemIfExists(): String? = mitmEngine.caPemIfExists()

    internal fun ensureCaPem(): String? = try {
        mitmEngine.ensureCaPem()
    } catch (e: Exception) {
        logger.error(TAG, "failed to generate CA: ${e.message}", e)
        null
    }

    internal fun lastErrorOrNull(): String? = lastError

    internal fun trafficSnapshot(): TrafficStats {
        val uptime = if (running && startedAtMs > 0) clock.currentTimeMillis() - startedAtMs else 0L
        return TrafficStats(
            totalConnections = totalConnections.get(),
            activeConnections = activeConnections.get(),
            successfulConnections = successfulConnections.get(),
            failedConnections = failedConnections.get(),
            bytesUp = bytesUp.get(),
            bytesDown = bytesDown.get(),
            uptimeMs = uptime,
            proxyPort = port,
        )
    }

    // --------------------------------------------------------------------------------------------
    // accept loop / connection dispatch
    // --------------------------------------------------------------------------------------------

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (running) logger.warn(TAG, "accept failed: ${e.message}")
                break
            }
            if (!running) {
                NetUtils.closeQuietly(socket)
                break
            }
            dispatch(socket)
        }
    }

    private fun dispatch(socket: Socket) {
        val maxConnections = config.proxy.maxConnections.coerceAtLeast(1)
        if (activeConnections.get() >= maxConnections) {
            writeBareResponse(socket, 503, "Too Many Connections", "DevSidebike: connection limit reached ($maxConnections)")
            NetUtils.closeQuietly(socket)
            failedConnections.incrementAndGet()
            lastError = "connection limit reached"
            return
        }
        activeConnections.incrementAndGet()
        totalConnections.incrementAndGet()
        activeSockets.add(socket)
        val executor = workers
        if (executor == null) {
            activeSockets.remove(socket)
            activeConnections.decrementAndGet()
            NetUtils.closeQuietly(socket)
            return
        }
        try {
            executor.execute {
                try {
                    handleConnection(socket)
                } catch (t: Throwable) {
                    logger.error(TAG, "unhandled proxy worker error: ${t.message}", t)
                } finally {
                    activeSockets.remove(socket)
                    activeConnections.decrementAndGet()
                    NetUtils.closeQuietly(socket)
                }
            }
        } catch (_: RejectedExecutionException) {
            activeSockets.remove(socket)
            activeConnections.decrementAndGet()
            NetUtils.closeQuietly(socket)
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.tcpNoDelay = true
        val currentConfig = config
        socket.soTimeout = currentConfig.proxy.readTimeoutMs
        val countingIn = CountingInputStream(socket.getInputStream())
        val countingOut = CountingOutputStream(socket.getOutputStream())
        var hostForReport = ""
        var result = ProxyResult(success = false, error = "connection closed without a request")
        try {
            val head = HttpCodec.readRequestHead(countingIn)
            if (head == null) {
                record(hostForReport, result)
                return
            }
            hostForReport = reportHost(head)
            result = if (head.isConnect) {
                handleConnect(socket, head)
            } else {
                handlePlainHttp(head, countingIn, countingOut)
            }
        } catch (e: MalformedHttpException) {
            result = ProxyResult(false, countingIn.count, countingOut.count, "bad request: ${e.message}")
            writeBareResponseSafe(countingOut, 400, "Bad Request", "DevSidebike: ${e.message}")
        } catch (e: SocketTimeoutException) {
            result = ProxyResult(false, countingIn.count, countingOut.count, "read timeout: ${e.message}")
            writeBareResponseSafe(countingOut, 408, "Request Timeout", "DevSidebike: client read timeout")
        } catch (e: IOException) {
            result = ProxyResult(false, countingIn.count, countingOut.count, "${e.javaClass.simpleName}: ${e.message}")
            logger.debug(TAG, "connection I/O error ($hostForReport): ${e.message}")
        } finally {
            record(hostForReport, result)
        }
    }

    // --------------------------------------------------------------------------------------------
    // plain HTTP forwarding
    // --------------------------------------------------------------------------------------------

    private fun handlePlainHttp(
        head: HttpRequestHead,
        countingIn: CountingInputStream,
        countingOut: CountingOutputStream,
    ): ProxyResult {
        val uri = NetUtils.parseAbsoluteUri(head.target)
            ?: throw MalformedHttpException("plain HTTP proxy requests must use an absolute-form target (got '${head.target.take(120)}')")
        if (uri.scheme != "http") {
            HttpResponses.writeError(
                countingOut, head, 400,
                "absolute https URI is not supported; use CONNECT", "https://${uri.host}:${uri.port}",
            )
            return ProxyResult(false, countingIn.count, countingOut.count, "https absolute-form is not supported")
        }
        val plan = try {
            ruleEngine.plan(uri.host, uri.port, uri.pathAndQuery)
        } catch (e: Exception) {
            logger.error(TAG, "rule engine failed for ${uri.host}${uri.pathAndQuery}: ${e.message}", e)
            HttpResponses.writeError(
                countingOut, head, 500,
                "规则引擎异常: ${e.message}", "http://${uri.host}:${uri.port}${uri.pathAndQuery}",
            )
            return ProxyResult(false, countingIn.count, countingOut.count, "rule engine error: ${e.message}")
        }
        val outcome = forwarder.forward(head, "http", uri.host, uri.port, plan, countingIn, countingOut)
        return ProxyResult(
            success = outcome.error == null,
            bytesUp = countingIn.count,
            bytesDown = countingOut.count,
            error = outcome.error,
        )
    }

    // --------------------------------------------------------------------------------------------
    // CONNECT handling
    // --------------------------------------------------------------------------------------------

    private fun handleConnect(client: Socket, head: HttpRequestHead): ProxyResult {
        val authority = NetUtils.parseAuthority(head.target, 443)
            ?: throw MalformedHttpException("bad CONNECT authority: '${head.target.take(120)}'")
        val host = authority.host
        val port = authority.port
        val plan = try {
            ruleEngine.plan(host, port, null)
        } catch (e: Exception) {
            logger.error(TAG, "rule engine failed for CONNECT $host:$port: ${e.message}", e)
            writeBareResponse(client, 500, "Internal Server Error", "DevSidebike: rule engine error")
            return ProxyResult(false, 0, 0, "rule engine error: ${e.message}")
        }
        // CONNECT carries no path, so a host may still have path-only intercepts; ask the rule engine for the
        // host-level action as a fallback (documented contract expectation for RuleEngine.plan(host, port, null)).
        val domainAction: InterceptAction? = plan.action ?: runCatching { ruleEngine.match(host, null) }.getOrNull()
        val whitelisted = runCatching { ruleEngine.isWhitelisted(host) }.getOrDefault(false)
        val mitmEnabled = isMitmEnabled()

        if (plan.kind == RouteKind.REJECT) {
            val reason = plan.reason.ifBlank { "该域名被拦截规则禁止访问 (abort)" }
            writeBareResponse(client, 403, "Forbidden", "DevSidebike: $reason")
            return ProxyResult(false, 0, 0, "rejected: $reason")
        }
        if (plan.kind == RouteKind.SYNTHETIC && !mitmEnabled) {
            val msg = "该域名命中 success 合成响应规则，但 MITM 未启用，无法在 CONNECT 隧道内合成响应"
            writeBareResponse(client, 403, "Forbidden", "DevSidebike: $msg")
            return ProxyResult(false, 0, 0, "synthetic response requires MITM: $msg")
        }
        if (mitmEnabled && !whitelisted && requiresMitm(host, plan, domainAction)) {
            val result = mitmEngine.handleConnect(client, host, port)
            return ProxyResult(result.success, result.bytesUp, result.bytesDown, result.error)
        }
        if (domainAction?.sni != null && !mitmEnabled) {
            logger.warn(
                TAG,
                "domain $host has a sni rewrite rule but MITM is disabled; falling back to a direct tunnel (SNI decoy cannot be applied)",
            )
        }
        // MIRROR_PROXY needs TLS termination to be safe for CONNECT: tunnelling to the mirror host makes the
        // client validate the MIRROR's certificate for the ORIGINAL hostname → certificate mismatch. When we
        // could not MITM (MITM disabled or whitelisted) degrade to DIRECT so HTTPS keeps working, and say so.
        if (plan.kind == RouteKind.MIRROR_PROXY) {
            if (warnedMirrorFallbackHosts.add(host)) {
                logger.warn(
                    TAG,
                    "domain $host has a mirror/proxy rule (${plan.connectHost}:${plan.connectPort}) but MITM is " +
                        "disabled; falling back to a direct tunnel to keep TLS certificate valid",
                )
            }
            return tunnel(client, RoutePlan(RouteKind.DIRECT, host, port, reason = "mirror-fallback-direct"))
        }
        return tunnel(client, plan)
    }

    private fun requiresMitm(host: String, plan: RoutePlan, action: InterceptAction?): Boolean {
        if (plan.kind == RouteKind.SYNTHETIC) return true
        if (plan.sni != null) return true
        // Explicit host-level decision from the concrete rule engine (sees path-only rules too).
        if (hostNeedsMitm?.invoke(host) == true) return true
        // DefaultRuleEngine signals "host has (path-only) intercept rules" with this reason.
        if (plan.reason == "path-rule-pending-mitm") return true
        if (action == null) return false
        return action.sni != null ||
            action.success != null ||
            action.requestReplace != null ||
            action.responseReplace != null ||
            action.script != null ||
            action.tampermonkeyScript != null ||
            action.cacheDays != null ||
            action.status != null
    }

    /** Transparent byte tunnel: used for DIRECT/MIRROR_PROXY CONNECTs that need no HTTP rewriting. */
    private fun tunnel(client: Socket, plan: RoutePlan): ProxyResult {
        val upstream = try {
            connector.connect(plan, tls = false, sni = null)
        } catch (e: SocketTimeoutException) {
            writeBareResponse(client, 408, "Proxy connect timeout", "DevSidebike: 连接上游超时 ${plan.connectHost}:${plan.connectPort}")
            return ProxyResult(false, 0, 0, "proxy connect timeout: ${plan.connectHost}:${plan.connectPort}")
        } catch (e: IOException) {
            writeBareResponse(
                client,
                400,
                "Proxy connect error",
                "DevSidebike: 连接上游失败 ${plan.connectHost}:${plan.connectPort} (${e.message})",
            )
            return ProxyResult(false, 0, 0, "proxy connect error: ${e.message}")
        }
        try {
            val clientOut = client.getOutputStream()
            clientOut.write(
                "HTTP/1.1 200 Connection Established\r\nProxy-agent: dev-sidecar\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1),
            )
            clientOut.flush()
            val idleTimeout = config.proxy.idleTimeoutMs
            // Live byte accounting: tunnels can stay open for a long time (keep-alive HTTPS), so counters are
            // updated chunk-by-chunk instead of only when the tunnel closes (QA-009: traffic showed "0 B").
            val relay = ByteRelay.relay(
                client,
                upstream.socket,
                idleTimeout,
                onError = { message -> logger.debug(TAG, "tunnel ${plan.connectHost}:${plan.connectPort}: $message") },
                onBytes = { upDelta, downDelta ->
                    if (upDelta > 0) bytesUp.addAndGet(upDelta)
                    if (downDelta > 0) bytesDown.addAndGet(downDelta)
                },
            )
            if (relay.error != null) logger.debug(TAG, "tunnel closed with error: ${relay.error}")
            // Bytes were already added incrementally above (avoid double counting in record()).
            return ProxyResult(relay.error == null, 0, 0, relay.error)
        } catch (e: IOException) {
            return ProxyResult(false, 0, 0, "tunnel I/O error: ${e.message}")
        } finally {
            upstream.close()
        }
    }

    // --------------------------------------------------------------------------------------------
    // helpers
    // --------------------------------------------------------------------------------------------

    private fun reportHost(head: HttpRequestHead): String {
        if (head.isConnect) return NetUtils.parseAuthority(head.target, 443)?.host ?: head.target
        val abs = NetUtils.parseAbsoluteUri(head.target)
        if (abs != null) return abs.host
        return head.headers.get("Host") ?: ""
    }

    private fun record(host: String, result: ProxyResult) {
        if (result.success) successfulConnections.incrementAndGet() else {
            failedConnections.incrementAndGet()
            result.error?.let { lastError = it }
        }
        bytesUp.addAndGet(result.bytesUp)
        bytesDown.addAndGet(result.bytesDown)
        resultListener?.let { listener ->
            try {
                listener(host, result)
            } catch (t: Throwable) {
                logger.warn(TAG, "result listener threw: ${t.message}", t)
            }
        }
    }

    private fun writeBareResponse(socket: Socket, status: Int, reason: String, body: String) {
        try {
            writeBareResponseSafe(socket.getOutputStream(), status, reason, body)
        } catch (_: IOException) {
            // peer already gone
        }
    }

    private fun writeBareResponseSafe(out: OutputStream, status: Int, reason: String, body: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        } catch (_: IOException) {
            // peer already gone
        }
    }

    companion object {
        private const val TAG = "proxy"
    }
}
