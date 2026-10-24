package dev.sidebike.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.sidebike.app.BuildConfig
import dev.sidebike.app.MainActivity
import dev.sidebike.app.R
import dev.sidebike.app.data.SidebikeConfigStore
import dev.sidebike.app.data.SidebikeRuntime
import dev.sidebike.core.api.EngineStats
import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeEngine
import dev.sidebike.core.api.SidebikeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The single Android [VpnService] of Dev-Sidebike.
 *
 * ## What it does
 * - Runs as a foreground service (type `specialUse`, declared in the manifest) with a status
 *   notification and a "stop" action.
 * - Builds a tun interface that only captures DNS traffic: `addAddress(tunAddress/32)`,
 *   `addDnsServer(vpnDnsAddress)`, `addRoute(vpnDnsAddress/32)`, `setMtu(1500)`. Non-DNS traffic is
 *   never routed into the tun, so it keeps using the underlying network untouched. The interface is
 *   flagged non-metered because, from the system's point of view, only DNS is offloaded.
 * - On API 29+ it also calls `Builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", port))`
 *   so apps that honour the system proxy use the local HTTP proxy. On API 26-28 this API does not
 *   exist and the service logs a warning and runs in DNS-only mode.
 * - Reads the tun on a dedicated blocking thread, classifies IPv4/UDP DNS queries addressed to
 *   `vpnDnsAddress:53` with [DnsTunHandler], resolves them through
 *   [SidebikeEngine.resolveRawDnsQuery] on a bounded worker pool (per-query timeout, drop-oldest
 *   queue) and writes a correctly checksummed IPv4/UDP reply back into the tun.
 *
 * ## Fail-safe behaviour
 * - If the engine is missing/not running, the local proxy port is not accepting, or the tun cannot
 *   be established, the service tears down the tun + foreground state and publishes
 *   [VpnPhase.Failed] (the controller reports ERROR).
 * - A tun read failure or a dead engine/proxy while running triggers the same teardown; there is no
 *   "running" zombie state.
 * - The service never loops on crashes: a consecutive-failure counter stops automatic retries after
 *   [MAX_ALWAYS_ON_FAILURES] attempts (always-on VPN may otherwise restart it indefinitely).
 * - `onRevoke()` tears everything down and reports [VpnPhase.Revoked].
 * - Android restarting an always-on VPN delivers a `null` intent: the service treats it as a start
 *   and bootstraps the engine itself via [SidebikeRuntime].
 *
 * The service and the controller live in the same process; they communicate through
 * [VpnServiceBridge] (phase) and [SidebikeRuntime] (engine instance).
 */
class SidebikeVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunScope: CoroutineScope? = null

    private val startInFlight = AtomicBoolean(false)
    private val tearingDown = AtomicBoolean(false)
    private val consecutiveFailures = AtomicInteger(0)
    private val dnsQueryCounter = AtomicLong(0)
    private val tunWriteLock = Any()

    @Volatile
    private var running: Boolean = false

    /** True once [startForeground] succeeded; guards against duplicate foreground transitions. */
    @Volatile
    private var foregroundActive: Boolean = false

    private var config: SidebikeConfig = SidebikeConfig()

    @Volatile
    private var engine: SidebikeEngine? = null

    private var handler: DnsTunHandler? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunInput: InputStream? = null
    private var tunOutput: OutputStream? = null
    private var tunReaderThread: Thread? = null
    private var dnsQueue: Channel<DnsTunHandler.DnsRequest>? = null
    private var notificationUpdateJob: Job? = null
    private var healthMonitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            log(LogLevel.INFO, "收到停止通知动作")
            teardown(publish = VpnPhase.Stopped, reason = "notification-action")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DEBUG_FAULT) {
            return handleDebugFault(intent, startId)
        }
        if (intent?.action != null && intent.action != ACTION_START) {
            log(LogLevel.WARN, "忽略未知 action: ${intent.action}")
            ensureForeground(startId)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // null intent == Android restarting an always-on VPN; ACTION_START is the controller.
        // A service started with startForegroundService() must call startForeground() within ~5s,
        // regardless of whether the subsequent startup succeeds or fails.
        if (!ensureForeground(startId)) return START_NOT_STICKY

        if (running || startInFlight.get()) {
            log(LogLevel.DEBUG, "VPN 已在运行/启动中，忽略重复启动")
            return stickyOrNot()
        }
        if (consecutiveFailures.get() >= MAX_ALWAYS_ON_FAILURES) {
            log(LogLevel.ERROR, "连续启动失败 ${consecutiveFailures.get()} 次，停止自动重试以避免崩溃循环")
            publish(VpnPhase.Failed("VPN 连续启动失败，请打开应用手动重试"))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        publish(VpnPhase.Preparing)
        val configStore = SidebikeConfigStore(this)
        if (!startInFlight.compareAndSet(false, true)) return stickyOrNot()
        serviceScope.launch {
            try {
                establishVpn(configStore)
            } finally {
                startInFlight.set(false)
            }
        }
        return stickyOrNot()
    }

    /**
     * Ensures the foreground notification exists (idempotent). Returns false when the platform
     * rejects the foreground transition — in that case the caller must stop the service.
     */
    private fun ensureForeground(startId: Int): Boolean {
        if (foregroundActive) return true
        val ok = startForegroundWithNotification(buildNotification(getString(R.string.vpn_notification_starting), null))
        if (!ok) {
            publish(VpnPhase.Failed("无法启动前台服务"))
            stopSelf(startId)
            return false
        }
        foregroundActive = true
        return true
    }

    /**
     * Debug-only fault injection for QA (never compiled into release behaviour: the whole branch is
     * gated on [BuildConfig.DEBUG]). Triggered from adb, e.g.:
     * ```
     * adb shell am start-foreground-service -n dev.sidebike.app.debug/dev.sidebike.app.vpn.SidebikeVpnService \
     *     -a dev.sidebike.app.action.VPN_DEBUG_FAULT --es fault kill-engine
     * ```
     * Supported faults: `kill-engine`, `kill-proxy`, `tun-failure`, `revoke`.
     */
    private fun handleDebugFault(intent: Intent, startId: Int): Int {
        if (!BuildConfig.DEBUG) {
            log(LogLevel.WARN, "故障注入仅 debug 构建可用")
            return START_NOT_STICKY
        }
        if (!ensureForeground(startId)) return START_NOT_STICKY
        val fault = intent.getStringExtra(EXTRA_FAULT) ?: "unknown"
        log(LogLevel.WARN, "QA 故障注入: $fault")
        when (fault) {
            FAULT_KILL_ENGINE -> {
                engine?.stop()
                SidebikeRuntime.engineOrNull()?.stop()
                failRuntime("QA 注入故障：引擎已停止")
            }
            FAULT_KILL_PROXY -> {
                SidebikeRuntime.engineOrNull()?.stop()
                failRuntime("QA 注入故障：本地代理已停止")
            }
            FAULT_TUN_FAILURE -> failRuntime("QA 注入故障：tun 读取失败")
            FAULT_REVOKE -> onRevoke()
            else -> log(LogLevel.WARN, "未知故障类型: $fault")
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- startup

    private suspend fun establishVpn(configStore: SidebikeConfigStore) {
        try {
            val loadedConfig = configStore.load()
            config = loadedConfig

            // The controller normally starts the engine before us; if Android restarted the
            // always-on VPN in a fresh process we bootstrap it ourselves.
            val activeEngine = SidebikeRuntime.engineOrNull() ?: run {
                val created = SidebikeRuntime.startEngine(applicationContext, loadedConfig)
                SidebikeRuntime.claimServiceOwnership()
                log(LogLevel.INFO, "always-on 模式下由服务启动引擎")
                created
            }
            engine = activeEngine
            if (!activeEngine.isRunning) {
                throw IllegalStateException("引擎未进入运行状态")
            }

            // DNS readiness probe: if the engine cannot resolve, establishing the tun would route
            // every app's DNS into a black hole. Abort before touching the network.
            var dnsReady = DnsProbe.probe(activeEngine, DNS_PROBE_TIMEOUT_MS)
            if (!dnsReady && SidebikeRuntime.isServiceOwned()) {
                // Android restarts always-on VPN very early after boot; the network may not be up yet.
                // Give it one short retry before declaring failure (otherwise always-on stays broken
                // until the user opens the app).
                log(LogLevel.WARN, "DNS 尚未就绪（可能是开机 early-boot），${DNS_PROBE_BOOT_RETRY_DELAY_MS}ms 后重试")
                delay(DNS_PROBE_BOOT_RETRY_DELAY_MS)
                dnsReady = DnsProbe.probe(activeEngine, DNS_PROBE_TIMEOUT_MS)
            }
            if (!dnsReady) {
                throw IllegalStateException(getString(R.string.vpn_error_dns_unavailable))
            }

            val addresses = resolveAddresses(loadedConfig)
            val tunFd = buildTunInterface(loadedConfig, addresses)
            val dnsHandler = DnsTunHandler(addresses.vpnDnsBytes, mtu = MTU)
            handler = dnsHandler

            tunInterface = tunFd
            tunInput = FileInputStream(tunFd.fileDescriptor)
            tunOutput = FileOutputStream(tunFd.fileDescriptor)

            startDnsPipeline(dnsHandler, tunInput!!, tunOutput!!)
            running = true
            consecutiveFailures.set(0)
            publish(VpnPhase.Running)
            log(
                LogLevel.INFO,
                "VPN 已建立 tun=${addresses.tunAddress}/32 vpnDns=${addresses.vpnDnsAddress} " +
                    "mode=${if (systemProxyEnabled(loadedConfig)) "DNS+HTTP proxy" else "DNS-only"}",
            )
            updateNotification(getString(R.string.vpn_notification_running), null)
            startNotificationUpdates()
            startEngineHealthMonitor()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            consecutiveFailures.incrementAndGet()
            log(LogLevel.ERROR, "VPN 启动失败: ${error.message}", error)
            teardown(
                publish = VpnPhase.Failed("VPN 启动失败: ${error.message ?: error.javaClass.simpleName}"),
                reason = "establish-failed",
            )
        }
    }

    /** Resolves and validates the tun/DNS addresses from config, falling back to safe defaults. */
    private fun resolveAddresses(loadedConfig: SidebikeConfig): TunAddresses {
        val tunAddress = loadedConfig.dns.tunAddress.takeIf { parseIpv4(it) != null }
            ?: DEFAULT_TUN_ADDRESS.also { log(LogLevel.WARN, "config.dns.tunAddress 非法，回退到 $it") }
        val vpnDnsAddress = loadedConfig.dns.vpnDnsAddress.takeIf { parseIpv4(it) != null }
            ?: DEFAULT_VPN_DNS_ADDRESS.also { log(LogLevel.WARN, "config.dns.vpnDnsAddress 非法，回退到 $it") }
        if (tunAddress == vpnDnsAddress) {
            throw IllegalStateException("tunAddress 与 vpnDnsAddress 不能相同（$tunAddress）")
        }
        return TunAddresses(
            tunAddress = tunAddress,
            vpnDnsAddress = vpnDnsAddress,
            vpnDnsBytes = requireNotNull(parseIpv4(vpnDnsAddress)),
        )
    }

    private fun buildTunInterface(loadedConfig: SidebikeConfig, addresses: TunAddresses): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(MTU)
            // A DNS-only split tunnel must not be advertised as metered: that would make the system
            // treat the underlying connectivity as metered and can break large transfers/updates.
            .setMetered(false)
            .addAddress(addresses.tunAddress, 32)
            .addDnsServer(addresses.vpnDnsAddress)
            .addRoute(addresses.vpnDnsAddress, 32)
            .setBlocking(true)
            .setConfigureIntent(configureIntent())

        if (systemProxyEnabled(loadedConfig)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val proxyPort = loadedConfig.proxy.port.coerceIn(1, 65535)
                // The proxy must be listening before we hand the port to the system, otherwise every
                // app would get connection-refused until the engine recovers.
                if (!isLocalPortAccepting(proxyPort)) {
                    throw IllegalStateException("本地代理 127.0.0.1:$proxyPort 未就绪")
                }
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, proxyPort))
                log(LogLevel.INFO, "系统代理已设置 $LOOPBACK:$proxyPort")
            } else {
                // API 26-28: VpnService.Builder.setHttpProxy does not exist.
                log(
                    LogLevel.WARN,
                    "Android ${Build.VERSION.SDK_INT} 不支持 setHttpProxy（需要 API 29+），降级为仅 DNS 模式",
                )
            }
        } else {
            log(LogLevel.INFO, "系统代理未启用（DNS-only 模式）")
        }

        return builder.establish()
            ?: throw IllegalStateException(getString(R.string.vpn_error_no_tun))
    }

    /** Quick TCP reachability probe of the local proxy port (no traffic, no engine dependency). */
    private fun isLocalPortAccepting(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), PROXY_PROBE_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    private fun systemProxyEnabled(checkConfig: SidebikeConfig): Boolean =
        checkConfig.proxy.enabled && checkConfig.proxy.setSystemProxy

    // ---------------------------------------------------------------- tun DNS pipeline

    private fun startDnsPipeline(dnsHandler: DnsTunHandler, input: InputStream, output: OutputStream) {
        // Bounded queue with drop-oldest semantics: under a DNS flood the newest query wins and stale
        // pending queries are discarded instead of growing memory or blocking the tun reader.
        val queue = Channel<DnsTunHandler.DnsRequest>(
            capacity = QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        dnsQueue = queue
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        tunScope = scope
        repeat(WORKER_COUNT) {
            scope.launch {
                for (request in queue) {
                    resolveAndReply(dnsHandler, request, output)
                }
            }
        }
        tunReaderThread = Thread(
            { tunReadLoop(dnsHandler, input, queue) },
            "sidebike-tun-reader",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun tunReadLoop(
        dnsHandler: DnsTunHandler,
        input: InputStream,
        queue: Channel<DnsTunHandler.DnsRequest>,
    ) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var ignoredLogBudget = 8
        try {
            while (!tearingDown.get() && !Thread.currentThread().isInterrupted) {
                val read = input.read(buffer) // blocking read; configured via Builder.setBlocking(true)
                if (read <= 0) continue
                when (val classified = dnsHandler.classify(buffer, read)) {
                    is DnsTunHandler.Classified.Dns -> {
                        val sendResult = queue.trySend(classified.request)
                        if (sendResult.isFailure && !tearingDown.get()) {
                            log(LogLevel.WARN, "DNS 工作队列已关闭，丢弃查询")
                        }
                    }
                    is DnsTunHandler.Classified.Ignored -> {
                        if (ignoredLogBudget > 0) {
                            log(LogLevel.DEBUG, "忽略 tun 数据包: ${classified.reason}")
                            ignoredLogBudget--
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (!tearingDown.get()) {
                log(LogLevel.ERROR, "tun 读取线程异常", error)
                failRuntime("tun 读取失败: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private suspend fun resolveAndReply(
        dnsHandler: DnsTunHandler,
        request: DnsTunHandler.DnsRequest,
        output: OutputStream,
    ) {
        val response: ByteArray? = if (request.forcedRcode != null) {
            null
        } else {
            val activeEngine = engine
            if (activeEngine == null) {
                log(LogLevel.WARN, "引擎不可用，丢弃 DNS 查询: ${request.questionName}")
                null
            } else {
                val timeoutMs = config.dns.queryTimeoutMs
                    .coerceIn(MIN_QUERY_TIMEOUT_MS, MAX_QUERY_TIMEOUT_MS)
                    .toLong()
                try {
                    withTimeoutOrNull(timeoutMs) { activeEngine.resolveRawDnsQuery(request.dnsQuery) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    log(LogLevel.WARN, "DNS 解析异常 ${request.questionName}: ${error.message}")
                    null
                }
            }
        }

        val reply: ByteArray = dnsHandler.buildReply(request, response) ?: run {
            log(LogLevel.DEBUG, "无法为查询生成回复: ${request.questionName}")
            return
        }
        writeToTun(output, reply)
        dnsQueryCounter.incrementAndGet()
    }

    private fun writeToTun(output: OutputStream, packet: ByteArray) {
        synchronized(tunWriteLock) {
            if (tearingDown.get()) return
            runCatching {
                output.write(packet)
                output.flush()
            }.onFailure { error ->
                if (!tearingDown.get()) log(LogLevel.WARN, "写回 tun 失败: ${error.message}")
            }
        }
    }

    // ---------------------------------------------------------------- monitoring

    private fun startEngineHealthMonitor() {
        healthMonitorJob = serviceScope.launch {
            var consecutiveHealthFailures = 0
            while (isActive && running) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                if (!running) return@launch
                val failure = detectRuntimeFailure()
                consecutiveHealthFailures = if (failure == null) 0 else consecutiveHealthFailures + 1
                if (failure != null && consecutiveHealthFailures >= HEALTH_FAILURE_THRESHOLD) {
                    failRuntime(failure)
                    return@launch
                }
            }
        }
    }

    /** Returns a human-readable reason when the engine/proxy died, null when everything is healthy. */
    private fun detectRuntimeFailure(): String? {
        val activeEngine = engine ?: return "引擎实例丢失"
        if (!runCatching { activeEngine.isRunning }.getOrDefault(false)) {
            return getString(R.string.vpn_error_engine_stopped)
        }
        if (systemProxyEnabled(config)) {
            val proxyPort = config.proxy.port.coerceIn(1, 65535)
            if (!isLocalPortAccepting(proxyPort)) {
                return "本地代理端口 $LOOPBACK:$proxyPort 无响应"
            }
        }
        return null
    }

    private fun failRuntime(message: String) {
        if (tearingDown.get()) return
        log(LogLevel.ERROR, message)
        serviceScope.launch { teardown(publish = VpnPhase.Failed(message), reason = "runtime-failure") }
    }

    // ---------------------------------------------------------------- notifications

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.vpn_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, stats: EngineStats?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SidebikeVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detail = buildString {
            append(text)
            if (systemProxyEnabled(config)) {
                append(" · ").append(getString(R.string.vpn_notification_proxy_port, config.proxy.port))
            }
            if (stats != null) {
                append(" · ").append(getString(R.string.vpn_notification_dns_queries, stats.traffic.dnsQueries))
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.vpn_notification_stop_action), stopIntent)
            .build()
    }

    /** Returns true when [startForeground] succeeded. */
    private fun startForegroundWithNotification(notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // `specialUse` is the FGS type declared in the manifest; it is accepted even when the
            // system has not yet registered us as a VPN (first start after install), where
            // `systemExempted` may be rejected with ForegroundServiceTypeNotAllowedException.
            val typed = runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            }
            if (typed.isSuccess) return true
            Log.w(TAG, "Typed startForeground(specialUse) rejected, stepping down to untyped", typed.exceptionOrNull())
        }
        return runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { Log.e(TAG, "startForeground failed", it) }
            .isSuccess
    }

    private fun updateNotification(text: String, stats: EngineStats?) {
        if (tearingDown.get() || !foregroundActive) return
        val notification = buildNotification(text, stats)
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startNotificationUpdates() {
        notificationUpdateJob = serviceScope.launch {
            while (isActive && running) {
                delay(NOTIFICATION_UPDATE_INTERVAL_MS)
                if (!running) return@launch
                val stats = runCatching { engine?.stats() }.getOrNull()
                updateNotification(getString(R.string.vpn_notification_running), stats)
            }
        }
    }

    private fun configureIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        2,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ---------------------------------------------------------------- teardown

    /**
     * Idempotent teardown: closes the tun and all worker coroutines, releases the service-owned
     * engine, removes the foreground notification, stops the service and publishes [publish].
     */
    private fun teardown(publish: VpnPhase?, reason: String? = null) {
        if (!tearingDown.compareAndSet(false, true)) return
        running = false
        if (reason != null) log(LogLevel.DEBUG, "teardown: $reason")

        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
        healthMonitorJob?.cancel()
        healthMonitorJob = null

        tunReaderThread?.interrupt()
        tunReaderThread = null
        dnsQueue?.close()
        dnsQueue = null
        tunScope?.cancel()
        tunScope = null
        handler = null

        runCatching { tunInput?.close() }
        runCatching { tunOutput?.close() }
        runCatching { tunInterface?.close() }
        tunInput = null
        tunOutput = null
        tunInterface = null
        engine = null

        // Only release the engine when this service created it; otherwise the controller owns it
        // and reacts to the phase we publish below.
        runCatching { SidebikeRuntime.releaseEngineIfServiceOwned() }

        // Terminal phase is always published (the controller may be waiting for it). Teardown is
        // re-entrancy guarded above, so onDestroy cannot publish a second, conflicting phase.
        if (publish != null) VpnServiceBridge.publish(publish)

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foregroundActive = false
        stopSelf()
    }

    private fun publish(phase: VpnPhase) {
        VpnServiceBridge.publish(phase)
    }

    override fun onRevoke() {
        log(LogLevel.WARN, "VPN 被系统撤销（onRevoke）")
        teardown(publish = VpnPhase.Revoked, reason = "onRevoke")
        runCatching { super.onRevoke() }
    }

    override fun onDestroy() {
        teardown(publish = VpnPhase.Stopped, reason = "onDestroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun stickyOrNot(): Int = if (isAlwaysOnVpn()) START_STICKY else START_NOT_STICKY

    private fun isAlwaysOnVpn(): Boolean = runCatching {
        Settings.Secure.getString(contentResolver, SETTING_ALWAYS_ON_VPN_APP) == packageName
    }.getOrDefault(false)

    private fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        val logger: SidebikeLogger? = runCatching { engine?.logger() }.getOrNull()
        if (logger != null) {
            runCatching { logger.log(level, LOG_TAG, message, throwable) }
        }
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, message, throwable)
            LogLevel.INFO -> Log.i(TAG, message, throwable)
            LogLevel.WARN -> Log.w(TAG, message, throwable)
            LogLevel.ERROR -> Log.e(TAG, message, throwable)
        }
    }

    /** Strict numeric IPv4 parser (never performs DNS lookups for hostnames). */
    private fun parseIpv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (index in 0 until 4) {
            val value = parts[index].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    private data class TunAddresses(
        val tunAddress: String,
        val vpnDnsAddress: String,
        val vpnDnsBytes: ByteArray,
    )

    companion object {
        private const val TAG = "SidebikeVpnService"
        private const val LOG_TAG = "vpn"
        private const val LOOPBACK = "127.0.0.1"

        /** Starts (or re-uses) the foreground VPN service. Called by the controller. */
        const val ACTION_START = "dev.sidebike.app.action.VPN_START"

        /** Stops the VPN (notification action / QuickSettings tile). */
        const val ACTION_STOP = "dev.sidebike.app.action.VPN_STOP"

        /** Debug-only QA fault injection action (honoured only in [BuildConfig.DEBUG] builds). */
        const val ACTION_DEBUG_FAULT = "dev.sidebike.app.action.VPN_DEBUG_FAULT"

        /** Extra for [ACTION_DEBUG_FAULT]: `kill-engine` | `kill-proxy` | `tun-failure` | `revoke`. */
        const val EXTRA_FAULT = "fault"

        const val FAULT_KILL_ENGINE = "kill-engine"
        const val FAULT_KILL_PROXY = "kill-proxy"
        const val FAULT_TUN_FAILURE = "tun-failure"
        const val FAULT_REVOKE = "revoke"

        private const val DNS_PROBE_TIMEOUT_MS = 3_000L
        private const val DNS_PROBE_BOOT_RETRY_DELAY_MS = 2_000L

        private const val CHANNEL_ID = "sidebike_vpn_status"
        private const val NOTIFICATION_ID = 0x5DB1
        private const val SESSION_NAME = "Dev-Sidebike"
        private const val MTU = 1500
        private const val READ_BUFFER_SIZE = 32 * 1024

        private const val DEFAULT_TUN_ADDRESS = "10.111.0.1"
        private const val DEFAULT_VPN_DNS_ADDRESS = "10.111.0.2"

        private const val WORKER_COUNT = 4
        private const val QUEUE_CAPACITY = 64
        private const val MIN_QUERY_TIMEOUT_MS = 300
        private const val MAX_QUERY_TIMEOUT_MS = 20_000
        private const val HEALTH_CHECK_INTERVAL_MS = 2_000L
        private const val HEALTH_FAILURE_THRESHOLD = 2
        private const val PROXY_PROBE_TIMEOUT_MS = 800
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 2_000L
        private const val MAX_ALWAYS_ON_FAILURES = 3

        private const val SETTING_ALWAYS_ON_VPN_APP = "always_on_vpn_app"

        /**
         * Starts the VPN service. Must only be called after `VpnService.prepare()` consent has been
         * granted; the controller checks that first.
         */
        fun start(context: Context) {
            val intent = Intent(context, SidebikeVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Requests a clean stop. Uses `stopService` (not `startService`) so it never accidentally
         * starts the service — important while a start is in flight or when the service is already
         * gone.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, SidebikeVpnService::class.java))
        }
    }
}
