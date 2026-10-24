package dev.sidebike.app.controller

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.sidebike.app.BuildConfig
import dev.sidebike.app.R
import dev.sidebike.app.data.CaInstaller
import dev.sidebike.app.data.SidebikeConfigStore
import dev.sidebike.app.data.SidebikeRuntime
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.vpn.DnsProbe
import dev.sidebike.app.vpn.VpnPhase
import dev.sidebike.app.vpn.VpnServiceBridge
import dev.sidebike.core.api.EngineStats
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.ProxyDefaults
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeEngine
import dev.sidebike.core.api.TrafficStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [SidebikeController]: owns the process [SidebikeEngine] lifecycle, persists config, drives
 * [dev.sidebike.app.vpn.SidebikeVpnService] and exposes a single [SidebikeUiState] to the Compose UI.
 *
 * State machine (see `docs/architecture/01-architecture.md` §3.1):
 * ```
 * IDLE -> PREPARING -> STARTING -> RUNNING -> STOPPING -> IDLE
 *            |             |          |
 *            +-------------+----------+--> ERROR (engine/service failure, auto cleanup)
 * ```
 *
 * Startup order is fail-safe and deliberately strict:
 *  1. load + validate config, pick a free local proxy port (31181 is default; fall back within
 *     31181..31200 and persist the choice so the UI can show `effectiveProxyPort`),
 *  2. detect Private DNS / lockdown before bringing the VPN up (both are surfaced as warnings),
 *  3. start the engine (DNS resolver + HTTP proxy) and verify the proxy TCP port is accepting,
 *  4. only then ask the service to build the tun / register the system proxy,
 *  5. after the service reports running, verify the VPN network actually carries the HTTP proxy
 *     (`systemProxyRegistered`) and start the stats/health polling.
 *
 * Failure policy (no zombie states, no crash loops):
 *  - Missing VPN consent returns to IDLE with a message instead of starting.
 *  - Engine start failure / non-running engine / dead local proxy port releases the engine and lands
 *    in ERROR before the VPN service is ever started.
 *  - Service failures (tun build error, revoked VPN, dead engine) arrive via [VpnServiceBridge] and
 *    tear the engine down; the runtime watchdog additionally stops the VPN if the proxy dies.
 *  - `stop()` cancels an in-flight start, waits for the service to stop (with a timeout) and always
 *    releases the engine, so an interrupted start cannot leak a listening proxy port.
 *
 * Threading: all work happens on [dispatcher] (IO by default); state transitions are serialized on a
 * lock. External entry points (`start`, `stop`, `onStartAborted`) are non-blocking and idempotent as
 * required by the [SidebikeController] contract. The controller is a process-wide singleton.
 */
class DefaultSidebikeController internal constructor(
    context: Context,
    private val gateway: VpnGateway = AndroidVpnGateway,
    private val consentChecker: VpnConsentChecker = AndroidVpnConsentChecker,
    private val configStore: SidebikeConfigStore = SidebikeConfigStore(context),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    portAllocator: ((Int) -> Int?)? = null,
    portProbe: ((Int) -> Boolean)? = null,
    networkStateDetector: NetworkStateDetector? = null,
) : SidebikeController {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Injectable seams for hermetic JVM tests (defaults use real sockets / ConnectivityManager). */
    private val allocatePort: (Int) -> Int? = portAllocator ?: { desired -> findAvailablePort(desired) }
    private val probePort: (Int) -> Boolean = portProbe ?: { port -> isPortAccepting(port) }
    private val networkDetector: NetworkStateDetector =
        networkStateDetector ?: AndroidNetworkStateDetector(appContext)

    private val _state = MutableStateFlow(
        SidebikeUiState(effectiveProxyPort = ProxyDefaults.DEFAULT_PORT),
    )
    override val state: StateFlow<SidebikeUiState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    override val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val transitionLock = Any()
    private val logLock = Any()

    private val startRequested = AtomicBoolean(false)

    @Volatile
    private var engine: SidebikeEngine? = null

    @Volatile
    private var engineLogSubscription: AutoCloseable? = null

    @Volatile
    private var statsJob: Job? = null

    @Volatile
    private var startJob: Job? = null

    @Volatile
    private var runningSinceMs: Long = 0L

    /** Consecutive stats polls that reported a dead proxy while RUNNING (watchdog). */
    @Volatile
    private var proxyWatchdogFailures: Int = 0

    @Volatile
    private var statsTick: Long = 0L

    /** Warnings collected during PREPARING (Private DNS / lockdown / port fallback) shown while RUNNING. */
    private val startupWarnings = mutableListOf<String>()

    init {
        scope.launch {
            val config = runCatching { configStore.load() }
                .onFailure { Log.w(TAG, "Failed to load persisted config", it) }
                .getOrDefault(SidebikeConfig())
            _state.update { it.copy(config = config, effectiveProxyPort = config.proxy.port) }
            log(LogLevel.INFO, "controller", "配置已加载（${config.dns.providers.size} 个 DNS provider）")
            refreshCaInstalledInternal(config, allowEngineCreation = config.mitm.enabled)
        }
        scope.launch {
            VpnServiceBridge.phase.collect { phase -> onServicePhase(phase) }
        }
    }

    // ---------------------------------------------------------------- SidebikeController

    override fun start() {
        if (!startRequested.compareAndSet(false, true)) {
            log(LogLevel.DEBUG, "controller", "启动请求已在进行中，忽略重复调用")
            return
        }
        startJob = scope.launch {
            try {
                runStart()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Unexpected start failure", error)
                failStart("启动异常: ${error.message ?: error.javaClass.simpleName}", error)
            } finally {
                startRequested.set(false)
                startJob = null
            }
        }
    }

    override fun stop() {
        val current = _state.value.status
        if (current == RunStatus.STOPPING) return
        if (current == RunStatus.IDLE) {
            // Nothing active; still release a stray engine (e.g. after a failed start).
            scope.launch { cleanupEngine() }
            return
        }
        forceTransition(RunStatus.STOPPING, "正在停止…")
        cancelStatsPolling()
        val pendingStart = startJob
        scope.launch {
            // Let an in-flight start unwind before tearing down, otherwise it could re-create the
            // engine after cleanup and leave a zombie proxy listener behind.
            if (pendingStart != null) {
                pendingStart.cancel()
                pendingStart.join()
            }
            runCatching { gateway.stop(appContext) }
                .onFailure { Log.w(TAG, "Failed to stop VPN service", it) }
            withTimeoutOrNull(STOP_TIMEOUT_MS) {
                VpnServiceBridge.phase.first {
                    it is VpnPhase.Stopped || it is VpnPhase.Failed || it is VpnPhase.Revoked
                }
            }
            cleanupEngine()
            forceTransition(RunStatus.IDLE, "已停止")
            log(LogLevel.INFO, "controller", "已停止")
        }
    }

    override fun onStartAborted(reason: String) {
        log(LogLevel.WARN, "controller", "启动已中止: $reason")
        val pendingStart = startJob
        scope.launch {
            pendingStart?.cancel()
            pendingStart?.join()
            cancelStatsPolling()
            runCatching { gateway.stop(appContext) }
            cleanupEngine()
            forceTransition(RunStatus.IDLE, reason)
        }
    }

    override suspend fun updateConfig(config: SidebikeConfig) {
        val previous = _state.value.config
        runCatching { configStore.save(config) }
            .onFailure { Log.e(TAG, "Failed to persist config", it) }
        _state.update { it.copy(config = config) }
        engine?.let { currentEngine ->
            runCatching { currentEngine.updateConfig(config) }
                .onFailure { Log.w(TAG, "engine.updateConfig failed", it) }
        }
        val addressesChanged = previous.dns.vpnDnsAddress != config.dns.vpnDnsAddress ||
            previous.dns.tunAddress != config.dns.tunAddress
        val proxyEndpointChanged = previous.proxy.port != config.proxy.port ||
            previous.proxy.enabled != config.proxy.enabled ||
            previous.proxy.setSystemProxy != config.proxy.setSystemProxy
        if (_state.value.status == RunStatus.RUNNING && (addressesChanged || proxyEndpointChanged)) {
            _state.update { it.copy(statusMessage = "配置已保存，需重启 VPN 生效") }
            log(LogLevel.WARN, "controller", "VPN 地址/代理端口变更：需重启 VPN 后生效")
        }
        refreshCaInstalledInternal(config, allowEngineCreation = config.mitm.enabled)
    }

    override suspend fun reloadConfig() {
        val config = configStore.load()
        _state.update { it.copy(config = config) }
        engine?.let { currentEngine ->
            runCatching { currentEngine.updateConfig(config) }
                .onFailure { Log.w(TAG, "engine.updateConfig failed", it) }
        }
        refreshCaInstalledInternal(config, allowEngineCreation = config.mitm.enabled)
    }

    override fun clearLogs() {
        synchronized(logLock) { _logs.value = emptyList() }
        runCatching { engine?.logger()?.clear() }
    }

    override suspend fun caCertificatePem(): String? = caCertificatePemInternal()

    override fun refreshCaInstalled() {
        scope.launch {
            refreshCaInstalledInternal(_state.value.config, allowEngineCreation = _state.value.config.mitm.enabled)
        }
    }

    override suspend fun exportDiagnostics(): String = buildString {
        appendLine("Dev-Sidebike 诊断信息")
        appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("应用: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine(
            "系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}",
        )
        val snapshot = _state.value
        appendLine("状态: ${snapshot.status} - ${snapshot.statusMessage}")
        appendLine(
            "vpnActive=${snapshot.vpnActive} proxyRunning=${snapshot.proxyRunning} " +
                "mitmActive=${snapshot.mitmActive} caInstalled=${snapshot.caInstalled}",
        )
        appendLine(
            "effectiveProxyPort=${snapshot.effectiveProxyPort} " +
                "systemProxyRegistered=${snapshot.systemProxyRegistered} " +
                "privateDnsActive=${snapshot.privateDnsActive} lockdownDetected=${snapshot.lockdownDetected}",
        )
        appendLine("运行时长: ${snapshot.uptimeMs} ms")
        appendLine()
        appendLine("=== 配置 (JSON) ===")
        appendLine(configStore.encode(snapshot.config))
        appendLine()
        appendLine("=== 统计 ===")
        appendLine(snapshot.stats.toString())
        appendLine()
        appendLine("=== 最近日志 (${logs.value.size}) ===")
        logs.value.forEach { entry ->
            appendLine(
                "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timeMs))} " +
                    "[${entry.level}] ${entry.tag}: ${entry.message}",
            )
            entry.throwable?.let { appendLine("  ! ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- start flow

    private suspend fun runStart() {
        if (!transition(setOf(RunStatus.IDLE, RunStatus.ERROR), RunStatus.PREPARING, "正在准备…")) {
            log(LogLevel.DEBUG, "controller", "当前状态 ${_state.value.status}，忽略启动")
            return
        }
        log(LogLevel.INFO, "controller", "用户请求启动 VPN")
        synchronized(transitionLock) { startupWarnings.clear() }

        var config = configStore.load()
        _state.update { it.copy(config = config) }

        val consentNeeded = runCatching { consentChecker.requiresUserConsent(appContext) }
            .onFailure { Log.w(TAG, "VpnService.prepare failed", it) }
            .getOrDefault(true)
        if (consentNeeded) {
            log(LogLevel.WARN, "controller", "缺少 VPN 授权，等待用户在界面上确认")
            transition(setOf(RunStatus.PREPARING), RunStatus.IDLE, "等待 VPN 授权")
            return
        }

        // --- 1. preflight: proxy port + Private DNS / lockdown detection -----------------------
        val preflight = try {
            withContext(Dispatchers.IO) { runPreflight(config) }
        } catch (error: Throwable) {
            Log.e(TAG, "Network preflight failed", error)
            log(LogLevel.ERROR, "controller", "启动前检查失败: ${error.message}", error)
            transition(
                from = setOf(RunStatus.PREPARING),
                to = RunStatus.ERROR,
                message = "启动前检查失败",
                error = error.message ?: error.javaClass.simpleName,
            )
            return
        }
        if (preflight.config != config) {
            config = preflight.config
            runCatching { configStore.save(config) }
                .onFailure { Log.w(TAG, "Failed to persist adjusted proxy port", it) }
        }
        synchronized(transitionLock) {
            startupWarnings.clear()
            startupWarnings += preflight.warnings
        }
        preflight.warnings.forEach { log(LogLevel.WARN, "controller", it) }
        _state.update {
            it.copy(
                config = config,
                effectiveProxyPort = preflight.effectiveProxyPort,
                privateDnsActive = preflight.privateDnsActive,
                lockdownDetected = preflight.lockdownDetected,
                systemProxyRegistered = null,
                statusMessage = "正在准备…",
            )
        }

        // --- 2. start the engine (DNS + HTTP proxy) -------------------------------------------
        val startedEngine = try {
            SidebikeRuntime.startEngine(appContext, config)
        } catch (error: Throwable) {
            Log.e(TAG, "Engine failed to start", error)
            log(LogLevel.ERROR, "engine", "引擎/代理启动失败: ${error.message}", error)
            transition(
                from = setOf(RunStatus.PREPARING),
                to = RunStatus.ERROR,
                message = "引擎启动失败",
                error = error.message ?: error.javaClass.simpleName,
            )
            SidebikeRuntime.releaseEngine()
            return
        }
        attachEngine(startedEngine)
        if (!startedEngine.isRunning) {
            failStart("引擎未进入运行状态（代理端口可能被占用）", null)
            return
        }

        // --- 3. verify the local proxy actually accepts connections ---------------------------
        val proxyPort = preflight.effectiveProxyPort
        if (config.proxy.enabled && proxyPort > 0) {
            val ready = withContext(Dispatchers.IO) { waitForPort(proxyPort, PROXY_READY_TIMEOUT_MS) }
            if (!ready) {
                failStart("本地代理端口 127.0.0.1:$proxyPort 未就绪", null)
                return
            }
        }
        log(LogLevel.INFO, "controller", "引擎已启动（代理端口 ${if (proxyPort > 0) proxyPort else "未启用"}）")

        // --- 4. DNS readiness probe: never establish the VPN behind a broken resolver -----------
        // Otherwise every app's DNS is routed into the tun but nothing can be resolved (device-wide
        // DNS black hole). Fail before the VPN exists so the network stays untouched.
        val dnsReady = try {
            DnsProbe.probe(startedEngine, DNS_PROBE_TIMEOUT_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "DNS readiness probe failed with error", error)
            false
        }
        if (!dnsReady) {
            failStart(appContext.getString(R.string.vpn_error_dns_unavailable), null)
            return
        }
        log(LogLevel.INFO, "controller", "DNS 就绪探测通过")

        // --- 5. start the VPN service --------------------------------------------------------
        if (!transition(setOf(RunStatus.PREPARING), RunStatus.STARTING, "正在建立 VPN…")) return

        VpnServiceBridge.reset()
        val gatewayStarted = runCatching { gateway.start(appContext) }
            .onFailure { Log.e(TAG, "Failed to start VpnService", it) }
            .isSuccess
        if (!gatewayStarted) {
            failStart("VPN 服务启动失败", null)
            return
        }

        val phase = withTimeoutOrNull(START_TIMEOUT_MS) {
            VpnServiceBridge.phase.first { it !is VpnPhase.Idle && it !is VpnPhase.Preparing }
        }
        when {
            phase is VpnPhase.Running -> {
                if (transition(
                        from = setOf(RunStatus.STARTING),
                        to = RunStatus.RUNNING,
                        message = runningStatusMessage(),
                        resetUptime = true,
                    )
                ) {
                    log(LogLevel.INFO, "controller", "VPN 已运行")
                    startStatsPolling()
                    verifySystemProxyAsync()
                }
            }
            phase == null -> failStart("VPN 启动超时（${START_TIMEOUT_MS / 1000}s 内未就绪）", null)
            phase is VpnPhase.Failed -> failStart("VPN 服务错误: ${phase.message}", null)
            phase is VpnPhase.Revoked -> failStart("VPN 已被系统撤销", null)
            else -> failStart("VPN 未能建立连接", null)
        }
    }

    /** Result of the pre-start network inspection; [config] may carry a fallback proxy port. */
    private data class PreflightResult(
        val config: SidebikeConfig,
        val effectiveProxyPort: Int,
        val privateDnsActive: Boolean,
        val lockdownDetected: Boolean,
        val warnings: List<String>,
    )

    /**
     * Runs on IO: picks a free proxy port (if the proxy is enabled) and inspects the system network
     * for Private DNS / lockdown. Never blocks the caller's thread.
     */
    private fun runPreflight(config: SidebikeConfig): PreflightResult {
        val warnings = mutableListOf<String>()
        var effectiveConfig = config
        var effectiveProxyPort = 0

        if (config.proxy.enabled) {
            val desired = config.proxy.port.coerceIn(1, 65535)
            val selected = allocatePort(desired)
                ?: throw IllegalStateException("代理端口 $desired..${desired + PORT_FALLBACK_RANGE} 均被占用")
            effectiveProxyPort = selected
            if (selected != desired) {
                effectiveConfig = config.copy(proxy = config.proxy.copy(port = selected))
                warnings += appContext.getString(R.string.vpn_error_proxy_port_fallback, selected)
            }
        }

        val privateDnsActive = runCatching { networkDetector.isPrivateDnsActive() }.getOrDefault(false)
        if (privateDnsActive) {
            warnings += appContext.getString(R.string.vpn_warning_private_dns)
        }
        val lockdownDetected = runCatching { networkDetector.isLockdownEnabled() }.getOrDefault(false)
        if (lockdownDetected) {
            warnings += appContext.getString(R.string.vpn_warning_lockdown)
        }
        return PreflightResult(
            config = effectiveConfig,
            effectiveProxyPort = effectiveProxyPort,
            privateDnsActive = privateDnsActive,
            lockdownDetected = lockdownDetected,
            warnings = warnings,
        )
    }

    /** Single failure path for the start flow: report ERROR, release engine, stop the service. */
    private fun failStart(message: String, cause: Throwable?) {
        log(LogLevel.ERROR, "controller", message, cause)
        transition(
            from = setOf(RunStatus.PREPARING, RunStatus.STARTING),
            to = RunStatus.ERROR,
            message = "启动失败",
            error = message,
        )
        cancelStatsPolling()
        runCatching { gateway.stop(appContext) }
        cleanupEngine()
    }

    // ---------------------------------------------------------------- service phase handling

    private fun onServicePhase(phase: VpnPhase) {
        when (phase) {
            is VpnPhase.Running -> onServiceRunning()
            is VpnPhase.Failed -> {
                log(LogLevel.ERROR, "vpn", "VPN 服务错误: ${phase.message}")
                if (transition(
                        from = setOf(RunStatus.PREPARING, RunStatus.STARTING, RunStatus.RUNNING),
                        to = RunStatus.ERROR,
                        message = "VPN 服务错误",
                        error = phase.message,
                    )
                ) {
                    cancelStatsPolling()
                    cleanupEngine()
                }
            }
            is VpnPhase.Revoked -> {
                log(LogLevel.WARN, "vpn", "VPN 已被系统或用户撤销")
                if (transition(
                        from = setOf(RunStatus.PREPARING, RunStatus.STARTING, RunStatus.RUNNING, RunStatus.STOPPING),
                        to = RunStatus.ERROR,
                        message = "VPN 已被撤销",
                        error = "VPN 已被系统或用户撤销（可在系统设置中重新启用）",
                    )
                ) {
                    cancelStatsPolling()
                    cleanupEngine()
                }
            }
            is VpnPhase.Stopped -> {
                val from = _state.value.status
                if (from == RunStatus.STOPPING) {
                    // stop() handles the final IDLE transition after awaiting the service.
                    return
                }
                if (transition(
                        from = setOf(RunStatus.RUNNING, RunStatus.STARTING),
                        to = if (from == RunStatus.STARTING) RunStatus.ERROR else RunStatus.IDLE,
                        message = if (from == RunStatus.STARTING) "启动被中断" else "已停止",
                        error = if (from == RunStatus.STARTING) "VPN 服务在启动过程中停止" else null,
                    )
                ) {
                    cancelStatsPolling()
                    cleanupEngine()
                }
            }
            is VpnPhase.Idle, is VpnPhase.Preparing -> Unit
        }
    }

    private fun onServiceRunning() {
        val runtimeEngine = SidebikeRuntime.engineOrNull()
        if (runtimeEngine != null && engine !== runtimeEngine) {
            log(LogLevel.INFO, "vpn", "接管已运行的引擎（always-on VPN 重启）")
            attachEngine(runtimeEngine)
        }
        when (_state.value.status) {
            RunStatus.STARTING -> {
                if (transition(
                        from = setOf(RunStatus.STARTING),
                        to = RunStatus.RUNNING,
                        message = runningStatusMessage(),
                        resetUptime = true,
                    )
                ) {
                    startStatsPolling()
                    verifySystemProxyAsync()
                }
            }
            RunStatus.IDLE, RunStatus.ERROR -> {
                // Always-on VPN was (re)started by the system while the UI was not driving it.
                if (transition(
                        from = setOf(RunStatus.IDLE, RunStatus.ERROR),
                        to = RunStatus.RUNNING,
                        message = "运行中（always-on）",
                        resetUptime = true,
                    )
                ) {
                    startStatsPolling()
                    verifySystemProxyAsync()
                }
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- stats + watchdog

    private fun startStatsPolling() {
        if (statsJob?.isActive == true) return
        proxyWatchdogFailures = 0
        statsJob = scope.launch {
            while (isActive) {
                val currentEngine = engine
                if (currentEngine != null) {
                    val stats = runCatching { currentEngine.stats() }.getOrNull()
                    if (stats != null) {
                        applyStats(stats)
                        updateSystemProxyRegistrationPeriodically()
                        if (detectProxyWatchdogTrip(stats)) return@launch
                    }
                }
                delay(STATS_POLL_MS)
            }
        }
    }

    /**
     * Runtime watchdog: the engine claims to be running but its proxy stopped accepting work.
     * Two consecutive polls are required to avoid stopping on a transient GC pause / stats race.
     */
    private fun detectProxyWatchdogTrip(stats: EngineStats): Boolean {
        val snapshot = _state.value
        val proxyExpected = snapshot.status == RunStatus.RUNNING &&
            snapshot.config.proxy.enabled &&
            snapshot.effectiveProxyPort > 0
        val healthy = !proxyExpected || stats.proxyRunning
        if (healthy) {
            proxyWatchdogFailures = 0
            return false
        }
        proxyWatchdogFailures++
        if (proxyWatchdogFailures < PROXY_WATCHDOG_THRESHOLD) return false
        val message = "本地代理已停止响应，已自动关闭 VPN 以恢复网络"
        scope.launch { failRuntime(message) }
        return true
    }

    /** Stops the VPN + engine and reports ERROR (runtime watchdog / unexpected termination). */
    private fun failRuntime(message: String) {
        log(LogLevel.ERROR, "controller", message)
        forceTransition(RunStatus.ERROR, "运行错误", message)
        cancelStatsPolling()
        runCatching { gateway.stop(appContext) }
        cleanupEngine()
    }

    private fun cancelStatsPolling() {
        statsJob?.cancel()
        statsJob = null
        proxyWatchdogFailures = 0
    }

    private fun applyStats(stats: EngineStats) {
        val now = System.currentTimeMillis()
        _state.update { current ->
            val running = current.status == RunStatus.RUNNING
            current.copy(
                stats = stats.traffic,
                proxyRunning = stats.proxyRunning,
                mitmActive = current.config.mitm.enabled && stats.caReady,
                uptimeMs = if (running && runningSinceMs > 0) now - runningSinceMs else current.uptimeMs,
            )
        }
    }

    // ---------------------------------------------------------------- system proxy verification

    /**
     * Verifies (API 29+, with a few retries because ConnectivityManager takes a moment to expose the
     * VPN network) that the HTTP proxy we handed to `Builder.setHttpProxy` is actually registered on
     * the VPN network, and surfaces the result as `state.systemProxyRegistered`.
     */
    private fun verifySystemProxyAsync() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _state.update { it.copy(systemProxyRegistered = null) }
            return
        }
        if (!systemProxyEnabled(_state.value.config)) {
            _state.update { it.copy(systemProxyRegistered = null) }
            return
        }
        scope.launch {
            var registered = false
            repeat(SYSTEM_PROXY_VERIFY_ATTEMPTS) {
                if (!registered) {
                    delay(SYSTEM_PROXY_VERIFY_DELAY_MS)
                    registered = runCatching { networkDetector.isVpnHttpProxyRegistered() }.getOrDefault(false)
                }
            }
            _state.update { it.copy(systemProxyRegistered = registered) }
            if (!registered) {
                log(LogLevel.WARN, "controller", appContext.getString(R.string.vpn_warning_system_proxy_unavailable))
            }
        }
    }

    private fun updateSystemProxyRegistrationPeriodically() {
        statsTick++
        if (statsTick % SYSTEM_PROXY_RECHECK_TICKS != 0L) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!systemProxyEnabled(_state.value.config)) return
        val registered = runCatching { networkDetector.isVpnHttpProxyRegistered() }.getOrDefault(false)
        _state.update { it.copy(systemProxyRegistered = registered) }
    }

    private fun systemProxyEnabled(config: SidebikeConfig): Boolean =
        config.proxy.enabled && config.proxy.setSystemProxy

    // ---------------------------------------------------------------- engine & logs

    private fun attachEngine(newEngine: SidebikeEngine) {
        if (engine === newEngine) return
        detachEngineLogs()
        engine = newEngine
        val logger = runCatching { newEngine.logger() }.getOrNull() ?: return
        runCatching { logger.history() }
            .getOrDefault(emptyList())
            .takeLast(MAX_LOG_ENTRIES)
            .forEach { appendLog(it) }
        engineLogSubscription = runCatching { logger.subscribe { entry -> appendLog(entry) } }
            .onFailure { Log.w(TAG, "Failed to subscribe to engine logger", it) }
            .getOrNull()
    }

    private fun detachEngineLogs() {
        runCatching { engineLogSubscription?.close() }
        engineLogSubscription = null
    }

    private fun cleanupEngine() {
        detachEngineLogs()
        engine = null
        if (SidebikeRuntime.releaseEngine()) {
            log(LogLevel.DEBUG, "controller", "引擎已释放")
        }
    }

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            timeMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable,
        )
        appendLog(entry)
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, "[$tag] $message", throwable)
            LogLevel.WARN -> Log.w(TAG, "[$tag] $message", throwable)
            else -> Unit
        }
    }

    private fun appendLog(entry: LogEntry) {
        synchronized(logLock) {
            val current = _logs.value
            _logs.value = if (current.size >= MAX_LOG_ENTRIES) {
                buildList(current.size) {
                    addAll(current.subList(current.size - MAX_LOG_ENTRIES + 1, current.size))
                    add(entry)
                }
            } else {
                current + entry
            }
        }
    }

    // ---------------------------------------------------------------- CA

    private suspend fun caCertificatePemInternal(allowEngineCreation: Boolean = true): String? {
        val currentEngine = engine ?: SidebikeRuntime.engineOrNull()
        val availableEngine: SidebikeEngine = if (currentEngine != null) {
            currentEngine
        } else if (allowEngineCreation) {
            runCatching { SidebikeRuntime.ensureEngine(appContext, _state.value.config) }
                .onFailure { Log.w(TAG, "Engine unavailable for CA export", it) }
                .getOrNull() ?: return null
        } else {
            return null
        }
        if (engine == null) attachEngine(availableEngine)
        val config = _state.value.config
        return runCatching {
            availableEngine.caCertificatePem()
                ?: if (config.mitm.enabled) availableEngine.ensureCaCertificate() else null
        }.onFailure { Log.w(TAG, "caCertificatePem failed", it) }.getOrNull()
    }

    private suspend fun refreshCaInstalledInternal(config: SidebikeConfig, allowEngineCreation: Boolean) {
        val pem = runCatching {
            withContext(Dispatchers.IO) { caCertificatePemInternal(allowEngineCreation) }
        }.getOrNull()
        if (pem == null) {
            _state.update { it.copy(caInstalled = false) }
            return
        }
        val keyPem = runCatching {
            withContext(Dispatchers.IO) {
                File(appContext.filesDir, config.mitm.caKeyFileName)
                    .takeIf { it.isFile }
                    ?.readText()
            }
        }.getOrNull()
        val detection = withContext(Dispatchers.IO) {
            runCatching { CaInstaller.isCaInstalled(pem, keyPem) }
                .getOrDefault(CaInstaller.Detection(false, "error"))
        }
        _state.update { it.copy(caInstalled = detection.installed) }
        if (detection.installed) {
            log(LogLevel.DEBUG, "ca", "检测到 CA 已安装（${detection.method}）")
        }
    }

    // ---------------------------------------------------------------- state helpers

    private fun runningStatusMessage(): String = buildString {
        append("运行中")
        val notes = mutableListOf<String>()
        val snapshot = _state.value
        if (snapshot.privateDnsActive) notes += "私人 DNS 已开启"
        if (snapshot.lockdownDetected) notes += "Lockdown 已开启"
        if (!systemProxyEnabled(snapshot.config)) notes += "仅 DNS 模式"
        if (notes.isNotEmpty()) append("（").append(notes.joinToString("，")).append("）")
    }

    private fun forceTransition(to: RunStatus, message: String, error: String? = null): Boolean {
        synchronized(transitionLock) {
            _state.value = _state.value.transitioned(to, message, error)
            return true
        }
    }

    private fun transition(
        from: Set<RunStatus>,
        to: RunStatus,
        message: String,
        error: String? = null,
        resetUptime: Boolean = false,
    ): Boolean = synchronized(transitionLock) {
        val current = _state.value
        if (current.status !in from) return false
        if (resetUptime) runningSinceMs = System.currentTimeMillis()
        _state.value = current.transitioned(to, message, error)
        true
    }

    private fun SidebikeUiState.transitioned(to: RunStatus, message: String, error: String?): SidebikeUiState = copy(
        status = to,
        statusMessage = message,
        lastError = when {
            to == RunStatus.ERROR -> error ?: lastError ?: message
            to == RunStatus.IDLE || to == RunStatus.PREPARING -> null
            else -> lastError
        },
        vpnActive = to == RunStatus.RUNNING,
        uptimeMs = if (to == RunStatus.RUNNING) uptimeMs else 0L,
        stats = if (to == RunStatus.RUNNING) stats else TrafficStats(),
        proxyRunning = if (to == RunStatus.RUNNING) proxyRunning else false,
        systemProxyRegistered = if (to == RunStatus.PREPARING) null else systemProxyRegistered,
    )

    // ---------------------------------------------------------------- network helpers (real impl)

    /** Binds a loopback ServerSocket to probe availability; returns the first free port or null. */
    private fun findAvailablePort(desired: Int): Int? {
        val last = (desired + PORT_FALLBACK_RANGE).coerceAtMost(65535)
        for (port in desired..last) {
            if (isPortFree(port)) return port
        }
        return null
    }

    private fun isPortFree(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port))
            true
        }
    }.getOrDefault(false)

    private fun isPortAccepting(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), PORT_PROBE_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probePort(port)) return true
            Thread.sleep(PORT_PROBE_INTERVAL_MS)
        }
        return probePort(port)
    }

    /** Test/caller hook: releases resources and stops all coroutines. Idempotent. */
    internal fun close() {
        cancelStatsPolling()
        startJob?.cancel()
        startRequested.set(false)
        runCatching { gateway.stop(appContext) }
        cleanupEngine()
        scope.cancel()
    }

    companion object {
        private const val TAG = "SidebikeController"

        /** UI log ring size (matches `AppSettings.logMaxEntries` default). */
        private const val MAX_LOG_ENTRIES = 500

        private const val STATS_POLL_MS = 1_000L
        private const val START_TIMEOUT_MS = 15_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val PORT_FALLBACK_RANGE = 20
        private const val PORT_PROBE_TIMEOUT_MS = 500
        private const val PORT_PROBE_INTERVAL_MS = 100L
        private const val PROXY_READY_TIMEOUT_MS = 3_000L
        private const val DNS_PROBE_TIMEOUT_MS = 3_000L
        private const val PROXY_WATCHDOG_THRESHOLD = 2
        private const val SYSTEM_PROXY_VERIFY_ATTEMPTS = 4
        private const val SYSTEM_PROXY_VERIFY_DELAY_MS = 400L
        private const val SYSTEM_PROXY_RECHECK_TICKS = 5L
        private const val LOOPBACK = "127.0.0.1"

        @Volatile
        private var instance: DefaultSidebikeController? = null

        /** Process-wide singleton used by the UI, the VPN service and the boot receiver. */
        fun get(context: Context): DefaultSidebikeController {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: DefaultSidebikeController(context.applicationContext).also { instance = it }
            }
        }

        /** Test hook: drops the singleton (after closing it) so tests start from a clean slate. */
        internal fun resetForTest() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}

/**
 * Seam over `ConnectivityManager` for the network-environment checks (Private DNS, VPN lockdown,
 * registered HTTP proxy). Fakeable so controller JVM tests stay hermetic.
 */
internal interface NetworkStateDetector {
    /** True when the active network uses Private DNS (DoT); it bypasses our in-VPN DNS. */
    fun isPrivateDnsActive(): Boolean

    /** True when Android's "Block connections without VPN" lockdown is enabled. */
    fun isLockdownEnabled(): Boolean

    /** True when some VPN network currently exposes an HTTP proxy via LinkProperties (API 29+). */
    fun isVpnHttpProxyRegistered(): Boolean
}

/** Production detector backed by the real [ConnectivityManager]. */
internal class AndroidNetworkStateDetector(context: Context) : NetworkStateDetector {

    private val appContext: Context = context.applicationContext
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    override fun isPrivateDnsActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            val manager = connectivityManager ?: return false
            val network = manager.activeNetwork ?: return false
            manager.getLinkProperties(network)?.isPrivateDnsActive == true
        }.getOrDefault(false)
    }

    override fun isLockdownEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        // `ConnectivityManager.isLockdownEnabled()` is a @SystemApi (not part of the public SDK), so
        // read the secure setting the system UI writes instead. Both keys exist since Android 7 and
        // are readable without a permission.
        return runCatching {
            Settings.Secure.getString(
                appContext.contentResolver,
                SETTING_ALWAYS_ON_VPN_LOCKDOWN,
            ) == "1"
        }.getOrDefault(false)
    }

    override fun isVpnHttpProxyRegistered(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val manager = connectivityManager ?: return false
            manager.allNetworks.any { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@any false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    manager.getLinkProperties(network)?.httpProxy != null
            }
        }.getOrDefault(false)
    }

    private companion object {
        /** Secure setting written by the system UI when "Block connections without VPN" is on. */
        private const val SETTING_ALWAYS_ON_VPN_LOCKDOWN = "always_on_vpn_lockdown"
    }
}
