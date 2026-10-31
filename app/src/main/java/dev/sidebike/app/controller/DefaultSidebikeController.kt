package dev.sidebike.app.controller

import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.sidebike.app.BuildConfig
import dev.sidebike.app.R
import dev.sidebike.app.data.CaInstaller
import dev.sidebike.app.data.ConnectivityProbes
import dev.sidebike.app.data.EngineBootstrap
import dev.sidebike.app.data.JavaConnectivityProbes
import dev.sidebike.app.data.LogExport
import dev.sidebike.app.data.NetworkDiagnosticsRunner
import dev.sidebike.app.data.RuleEditor
import dev.sidebike.app.data.SidebikeConfigStore
import dev.sidebike.app.data.SidebikeRuntime
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.vpn.DnsProbe
import dev.sidebike.app.vpn.VpnPhase
import dev.sidebike.app.vpn.VpnRuntimeStats
import dev.sidebike.app.vpn.VpnServiceBridge
import dev.sidebike.core.api.EngineStats
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.ProxyDefaults
import dev.sidebike.core.api.RuleSourceFormat
import dev.sidebike.core.api.RuleSourceManager
import dev.sidebike.core.api.RuleSourceManagerFactory
import dev.sidebike.core.api.RuleSourceRefreshResult
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
    caDetector: ((String, String?) -> CaInstaller.Detection)? = null,
    connectivityProbes: ConnectivityProbes? = null,
    ruleManagerProvider: (() -> RuleSourceManager?)? = null,
    caInstallBundleBuilder: ((String) -> CaInstaller.InstallBundle?)? = null,
    secureLockChecker: (() -> Boolean)? = null,
) : SidebikeController {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Injectable seams for hermetic JVM tests (defaults use real sockets / ConnectivityManager). */
    private val allocatePort: (Int) -> Int? = portAllocator ?: { desired -> findAvailablePort(desired) }
    private val probePort: (Int) -> Boolean = portProbe ?: { port -> isPortAccepting(port) }
    private val networkDetector: NetworkStateDetector =
        networkStateDetector ?: AndroidNetworkStateDetector(appContext)

    /** CA-installed detection seam (real impl enumerates AndroidCAStore + TLS self-test). */
    private val detectCaInstalled: (String, String?) -> CaInstaller.Detection =
        caDetector ?: { pem, keyPem -> CaInstaller.isCaInstalled(pem, keyPem) }

    /** Network probes seam for runNetworkDiagnostics (tests inject a fake to stay hermetic). */
    private val probes: ConnectivityProbes = connectivityProbes ?: JavaConnectivityProbes()

    /** Rule-source manager seam; defaults to the installed core factory (tolerant to absence). */
    private val provideRuleManager: () -> RuleSourceManager? =
        ruleManagerProvider ?: { resolveRuleSourceManager() }

    /** CA install-bundle seam (real impl uses KeyChain; tests inject a deterministic bundle). */
    private val buildCaInstallBundle: (String) -> CaInstaller.InstallBundle? =
        caInstallBundleBuilder ?: { pem -> CaInstaller.buildInstallBundle(appContext, pem) }

    /**
     * Secure-lock-screen probe. The Android CA installer / credential UI requires a device lock
     * (PIN/pattern/password); without one it can show a "black dialog with only a close button"
     * (user issue 3), so prepareCaInstall() refuses to launch and returns a clear message instead.
     */
    private val isSecureLockConfigured: () -> Boolean =
        secureLockChecker ?: { runCatching { isDeviceSecureReal() }.getOrDefault(false) }

    private val ruleAutoRefreshInFlight = AtomicBoolean(false)

    /** Guards duplicate "VPN 已运行" log/side effects when service callback and start flow race. */
    private val runningEntryLogged = AtomicBoolean(false)

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
            val caInstalled = refreshCaInstalledInternal(
                config = config,
                allowEngineCreation = config.mitm.enabled || config.proxy.mitmEnabled,
            )
            // 0.1.1-alpha migration: legacy 0.1.0 configs enabled MITM without a trusted CA, which
            // made every intercepted HTTPS request fail with CERTIFICATE_UNKNOWN (real-device report).
            migrateLegacyMitmIfUnsafe(config, caInstalled)
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
        // 0.1.1-alpha safety net: never persist an enabled MITM without a trusted CA, even when the
        // UI bypasses setMitmEnabled() (legacy config import / programmatic config update).
        val safeConfig = if (!_state.value.caInstalled && (config.mitm.enabled || config.proxy.mitmEnabled)) {
            log(LogLevel.WARN, "mitm", LEGACY_MITM_DOWNGRADE_MESSAGE)
            _state.update {
                it.copy(statusMessage = LEGACY_MITM_DOWNGRADE_MESSAGE, lastError = LEGACY_MITM_DOWNGRADE_MESSAGE)
            }
            config.copy(
                mitm = config.mitm.copy(enabled = false),
                proxy = config.proxy.copy(mitmEnabled = false),
            )
        } else {
            config
        }
        runCatching { configStore.save(safeConfig) }
            .onFailure { Log.e(TAG, "Failed to persist config", it) }
        _state.update { it.copy(config = safeConfig) }
        engine?.let { currentEngine ->
            runCatching { currentEngine.updateConfig(safeConfig) }
                .onFailure { Log.w(TAG, "engine.updateConfig failed", it) }
        }
        val addressesChanged = previous.dns.vpnDnsAddress != safeConfig.dns.vpnDnsAddress ||
            previous.dns.tunAddress != safeConfig.dns.tunAddress
        val proxyEndpointChanged = previous.proxy.port != safeConfig.proxy.port ||
            previous.proxy.enabled != safeConfig.proxy.enabled ||
            previous.proxy.setSystemProxy != safeConfig.proxy.setSystemProxy
        if (_state.value.status == RunStatus.RUNNING && (addressesChanged || proxyEndpointChanged)) {
            _state.update { it.copy(statusMessage = "配置已保存，需重启 VPN 生效") }
            log(LogLevel.WARN, "controller", "VPN 地址/代理端口变更：需重启 VPN 后生效")
        }
        refreshCaInstalledInternal(safeConfig, allowEngineCreation = safeConfig.mitm.enabled)
    }

    override suspend fun reloadConfig() {
        val config = configStore.load()
        _state.update { it.copy(config = config) }
        engine?.let { currentEngine ->
            runCatching { currentEngine.updateConfig(config) }
                .onFailure { Log.w(TAG, "engine.updateConfig failed", it) }
        }
        val caInstalled = refreshCaInstalledInternal(config, allowEngineCreation = config.mitm.enabled)
        migrateLegacyMitmIfUnsafe(config, caInstalled)
    }

    override fun clearLogs() {
        synchronized(logLock) { _logs.value = emptyList() }
        runCatching { engine?.logger()?.clear() }
    }

    override suspend fun caCertificatePem(): String? = caCertificatePemInternal()

    override fun refreshCaInstalled() {
        scope.launch {
            // Retry: after returning from the system installer the CA store may lag behind by a
            // moment; a single check produced the false negative "未安装该证书" (user issue 3).
            refreshCaInstalledInternal(
                config = configStore.load(),
                allowEngineCreation = true,
                attempts = CA_REFRESH_DETECTION_ATTEMPTS,
            )
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

    // ---------------------------------------------------------------- 0.1.1-alpha: log export

    override suspend fun buildLogText(): String = runCatching {
        val snapshot = _state.value
        val allLogs = collectAllLogs()
        val deviceInfo = LogExport.buildDeviceInfo(
            context = appContext,
            state = snapshot,
            config = snapshot.config,
            extraEnvironmentLines = environmentExtraLines(snapshot),
        )
        LogExport.buildLogText(deviceInfo, allLogs, exportDiagnostics())
    }.onFailure { Log.e(TAG, "buildLogText failed", it) }
        .getOrElse { error -> "Dev-Sidebike 日志生成失败: ${error.message ?: error.javaClass.simpleName}" }

    override suspend fun buildDebugBundle(): ByteArray = runCatching {
        val snapshot = _state.value
        val allLogs = collectAllLogs()
        val deviceInfo = LogExport.buildDeviceInfo(
            context = appContext,
            state = snapshot,
            config = snapshot.config,
            extraEnvironmentLines = environmentExtraLines(snapshot),
        )
        LogExport.buildDebugBundle(
            deviceInfo = deviceInfo,
            diagnostics = exportDiagnostics(),
            logs = allLogs,
            configJson = configStore.encode(snapshot.config),
            stateText = LogExport.buildStateText(snapshot, allLogs.size),
        )
    }.onFailure { Log.e(TAG, "buildDebugBundle failed", it) }
        .getOrElse { ByteArray(0) }

    // ---------------------------------------------------------------- 0.1.1-alpha: diagnostics

    override suspend fun runNetworkDiagnostics(): String {
        _state.update { it.copy(diagnosticsRunning = true) }
        log(LogLevel.INFO, "diagnostics", "开始网络诊断")
        return try {
            val snapshot = _state.value
            val engineForProbe = engine ?: SidebikeRuntime.engineOrNull()
            val runner = NetworkDiagnosticsRunner(
                config = snapshot.config,
                state = snapshot,
                engine = engineForProbe,
                probes = probes,
            )
            val report = withContext(Dispatchers.IO) { runner.run() }
            log(LogLevel.INFO, "diagnostics", "网络诊断完成: ${summarizeDiagnostics(report)}")
            report
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "runNetworkDiagnostics failed", error)
            val message = "网络诊断失败: ${error.message ?: error.javaClass.simpleName}"
            log(LogLevel.ERROR, "diagnostics", message, error)
            "$message\n请尝试「导出日志与诊断包」反馈给开发者。"
        } finally {
            _state.update { it.copy(diagnosticsRunning = false) }
        }
    }

    // ---------------------------------------------------------------- 0.1.1-alpha: CA / MITM

    override suspend fun prepareCaInstall(): CaInstallRequest? = withContext(Dispatchers.IO) {
        runCatching {
            // The system certificate installer needs a secure lock screen; without one the credential
            // UI shows a black dialog with only a close button (real-device "未安装该证书" report).
            if (!isSecureLockConfigured()) {
                val message = "请先在系统设置中设置锁屏密码/图案后再安装 CA 证书（系统证书安装器需要设备锁屏）"
                log(LogLevel.WARN, "ca", message)
                _state.update { it.copy(lastError = message, statusMessage = message) }
                return@runCatching null
            }
            val config = configStore.load()
            val activeEngine = ensureEngineForOperations(config)
            if (activeEngine == null) {
                log(LogLevel.ERROR, "ca", "CA 生成失败：引擎不可用")
                return@runCatching null
            }
            // Generate the CA regardless of the MITM toggle (bug: CA was missing when MITM was off,
            // so users could not install it before enabling MITM).
            val pem = runCatching { activeEngine.ensureCaCertificate() }
                .onFailure { Log.w(TAG, "ensureCaCertificate failed", it) }
                .getOrNull()
                ?: runCatching { activeEngine.caCertificatePem() }.getOrNull()
            if (pem.isNullOrBlank()) {
                log(LogLevel.ERROR, "ca", "引擎未能生成 CA 证书")
                return@runCatching null
            }
            val bundle = buildCaInstallBundle(pem)
            if (bundle == null) {
                log(LogLevel.ERROR, "ca", "无法构建系统证书安装 Intent")
                return@runCatching null
            }
            // Clear a stale lock-screen/install error once generation succeeds.
            _state.update {
                if (it.lastError == null) it else it.copy(lastError = null, statusMessage = "CA 证书已就绪，可开始安装")
            }
            log(LogLevel.INFO, "ca", "CA 已就绪: ${bundle.subject} SHA256=${bundle.sha256Fingerprint}")
            CaInstallRequest(
                intent = bundle.intent,
                pem = bundle.pem,
                sha256Fingerprint = bundle.sha256Fingerprint,
                subject = bundle.subject,
                settingsFallbackIntent = bundle.settingsFallbackIntent,
            )
        }.onFailure { Log.e(TAG, "prepareCaInstall failed", it) }.getOrNull()
    }

    override suspend fun setMitmEnabled(enabled: Boolean): MitmToggleResult {
        val config = configStore.load()
        if (!enabled) {
            val updated = config.copy(
                mitm = config.mitm.copy(enabled = false),
                proxy = config.proxy.copy(mitmEnabled = false),
            )
            updateConfig(updated)
            _state.update { it.copy(mitmActive = false) }
            log(LogLevel.INFO, "mitm", "MITM 已关闭（HTTP 层改写停用）")
            return MitmToggleResult(effective = false, message = "MITM 已关闭")
        }

        val activeEngine = ensureEngineForOperations(config)
        if (activeEngine == null) {
            log(LogLevel.ERROR, "mitm", "无法开启 MITM：引擎不可用")
            return MitmToggleResult(false, "引擎不可用，无法生成 CA 证书")
        }
        val pem = withContext(Dispatchers.IO) {
            runCatching { activeEngine.ensureCaCertificate() }
                .onFailure { Log.w(TAG, "ensureCaCertificate failed", it) }
                .getOrNull()
        }
        if (pem.isNullOrBlank()) {
            log(LogLevel.ERROR, "mitm", "无法开启 MITM：CA 生成失败")
            return MitmToggleResult(false, "无法生成 CA 证书，请稍后重试或导出日志反馈")
        }
        val keyPem = readCaKeyPem(config)
        val detection = withContext(Dispatchers.IO) { detectCaInstalled(pem, keyPem) }
        if (!detection.installed) {
            _state.update { it.copy(caInstalled = false, mitmActive = false) }
            log(LogLevel.WARN, "mitm", "CA 未安装，拒绝开启 MITM（检测=${detection.method}）")
            return MitmToggleResult(
                effective = false,
                message = "CA 证书尚未安装或未被系统信任，请先在「证书安装」页完成安装后再开启 MITM",
            )
        }

        val updated = config.copy(
            mitm = config.mitm.copy(enabled = true),
            proxy = config.proxy.copy(mitmEnabled = true),
        )
        // Mark CA as trusted *before* updateConfig so its safety net does not downgrade this
        // deliberate, CA-verified enable operation.
        _state.update { it.copy(caInstalled = true) }
        updateConfig(updated)
        log(LogLevel.INFO, "mitm", "MITM 已开启（CA 已安装，检测=${detection.method}）")
        return MitmToggleResult(effective = true)
    }

    // ---------------------------------------------------------------- 0.1.1-alpha: rule editing

    override suspend fun saveRule(draft: RuleDraft) {
        val config = configStore.load()
        val updated = runCatching { RuleEditor.applyRule(config, draft) }
            .onFailure { Log.w(TAG, "saveRule rejected", it) }
            .getOrNull() ?: return
        updateConfig(updated)
        log(LogLevel.INFO, "rules", "已保存规则 ${draft.domainPattern} ${draft.pathRegex}")
    }

    override suspend fun deleteRule(domainPattern: String, pathRegex: String) {
        val config = configStore.load()
        val updated = RuleEditor.deleteRule(config, domainPattern, pathRegex)
        updateConfig(updated)
        log(LogLevel.INFO, "rules", "已删除规则 $domainPattern $pathRegex")
    }

    override suspend fun setDirectPassDomains(domains: List<String>) {
        val config = configStore.load()
        val normalized = RuleEditor.normalizeDomains(domains)
        val previouslyManaged = configStore.loadManagedDirectPassDomains()
        val updated = RuleEditor.setDirectPassDomains(config, normalized, previouslyManaged)
        runCatching { configStore.saveManagedDirectPassDomains(normalized) }
            .onFailure { Log.w(TAG, "Failed to persist managed direct-pass domains", it) }
        updateConfig(updated)
        log(LogLevel.INFO, "rules", "直连放行域名已更新: ${normalized.size} 个")
    }

    override suspend fun setDomesticAllowList(domains: List<String>) {
        val config = configStore.load()
        val updated = RuleEditor.setDomesticAllowList(config, domains)
        updateConfig(updated)
        log(LogLevel.INFO, "rules", "国内直连列表已更新: ${updated.domesticAllowList.size} 条")
    }

    override suspend fun importRulesFromText(
        text: String,
        format: RuleSourceFormat,
        sourceName: String,
    ): RuleImportSummary {
        val manager = provideRuleManager() ?: return RuleImportSummary(
            warnings = listOf("规则解析器尚未安装（core/rules 模块更新中），请稍后重试"),
        )
        if (text.isBlank()) return RuleImportSummary(warnings = listOf("导入内容为空"))
        val imported = runCatching { manager.parse(text, format, sourceName) }
            .onFailure { Log.w(TAG, "rule parse failed", it) }
            .getOrElse { error ->
                log(LogLevel.ERROR, "rules", "规则解析失败: ${error.message}", error)
                return RuleImportSummary(warnings = listOf("规则解析失败: ${error.message ?: error.javaClass.simpleName}"))
            }
        val config = configStore.load()
        val outcome = RuleEditor.mergeImported(config, imported)
        updateConfig(outcome.config)
        log(
            LogLevel.INFO,
            "rules",
            "导入规则[$sourceName]: 拦截 +${outcome.addedIntercepts}, 直连 +${outcome.addedDirectPassDomains}, " +
                "国内 +${outcome.addedDomesticDomains}, 警告 ${imported.warnings.size}",
        )
        return RuleImportSummary(
            addedIntercepts = outcome.addedIntercepts,
            addedDirectPassDomains = outcome.addedDirectPassDomains,
            addedDomesticDomains = outcome.addedDomesticDomains,
            warnings = imported.warnings,
        )
    }

    override suspend fun refreshRuleSources(force: Boolean): RuleSourceRefreshSummary {
        val manager = provideRuleManager()
            ?: return RuleSourceRefreshSummary(failedSources = mapOf("manager" to "规则源管理器尚未安装"))
        _state.update { it.copy(ruleSourcesBusy = true) }
        return try {
            val config = configStore.load()
            val enabledSources = config.ruleSources.filter { it.enabled }
            if (enabledSources.isEmpty()) {
                return RuleSourceRefreshSummary()
            }
            val results: List<RuleSourceRefreshResult> = runCatching {
                manager.refreshAll(enabledSources, force)
            }.onFailure { Log.w(TAG, "refreshAll failed", it) }
                .getOrElse { error ->
                    enabledSources.map {
                        RuleSourceRefreshResult(
                            source = it,
                            error = error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
            val resultById = results.associateBy { it.source.id }
            val updated = mutableListOf<String>()
            val failed = linkedMapOf<String, String>()
            val notModified = mutableListOf<String>()
            var importedRules = 0
            var mergedConfig = config
            val newSources = config.ruleSources.map { source ->
                val result = resultById[source.id] ?: return@map source
                val resultError = result.error
                val resultImported = result.imported
                when {
                    resultError != null -> {
                        failed[source.name] = resultError
                        source.copy(lastError = resultError)
                    }
                    result.notModified -> {
                        notModified += source.name
                        source.copy(lastError = null)
                    }
                    resultImported != null -> {
                        val outcome = RuleEditor.mergeImported(mergedConfig, resultImported)
                        mergedConfig = outcome.config
                        importedRules += outcome.addedIntercepts +
                            outcome.addedDirectPassDomains + outcome.addedDomesticDomains
                        updated += source.name
                        source.copy(
                            lastUpdatedMs = System.currentTimeMillis(),
                            lastError = null,
                            lastImported = resultImported.ruleCount,
                            // Persist conditional-request validators so subsequent refreshes can get a 304
                            // (QA-011-03: the manager returned them but the controller dropped them).
                            etag = result.source.etag ?: source.etag,
                            lastModified = result.source.lastModified ?: source.lastModified,
                        )
                    }
                    else -> source
                }
            }
            mergedConfig = mergedConfig.copy(ruleSources = newSources)
            updateConfig(mergedConfig)
            log(
                LogLevel.INFO,
                "rules",
                "规则源刷新: 更新 ${updated.size}, 未变 ${notModified.size}, 失败 ${failed.size}, 新增规则 $importedRules",
            )
            RuleSourceRefreshSummary(
                updatedSources = updated,
                failedSources = failed,
                notModifiedSources = notModified,
                importedRules = importedRules,
            )
        } finally {
            _state.update { it.copy(ruleSourcesBusy = false) }
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

        // 0.1.1-alpha migration (defense in depth): re-check right before starting, because the CA may
        // have been uninstalled since app launch. Never start the VPN with MITM-on-but-untrusted-CA.
        val caInstalledAtStart = refreshCaInstalledInternal(
            config = config,
            allowEngineCreation = config.mitm.enabled || config.proxy.mitmEnabled,
        )
        if (migrateLegacyMitmIfUnsafe(config, caInstalledAtStart)) {
            config = configStore.load()
        }
        _state.update { it.copy(config = config) }

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
                transition(
                    from = setOf(RunStatus.STARTING),
                    to = RunStatus.RUNNING,
                    message = runningStatusMessage(),
                    resetUptime = true,
                )
                // The service-phase collector may win the transition race; the side effects must run
                // exactly once either way (log "VPN 已运行", stats polling, proxy verification, …).
                if (_state.value.status == RunStatus.RUNNING) onEnterRunning()
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
                transition(
                    from = setOf(RunStatus.STARTING),
                    to = RunStatus.RUNNING,
                    message = runningStatusMessage(),
                    resetUptime = true,
                )
                if (_state.value.status == RunStatus.RUNNING) onEnterRunning()
            }
            RunStatus.IDLE, RunStatus.ERROR -> {
                // Always-on VPN was (re)started by the system while the UI was not driving it.
                transition(
                    from = setOf(RunStatus.IDLE, RunStatus.ERROR),
                    to = RunStatus.RUNNING,
                    message = "运行中（always-on）",
                    resetUptime = true,
                )
                if (_state.value.status == RunStatus.RUNNING) onEnterRunning()
            }
            else -> Unit
        }
    }

    /**
     * Side effects of entering RUNNING, shared by the start flow and the service callback (they can
     * race; [runningEntryLogged] makes it exactly-once per RUNNING period).
     */
    private fun onEnterRunning() {
        if (!runningEntryLogged.compareAndSet(false, true)) return
        log(LogLevel.INFO, "controller", "VPN 已运行")
        startStatsPolling()
        verifySystemProxyAsync()
        // 0.1.1-alpha: refresh due remote rule sources in the background once the VPN is up (never
        // blocks startup and avoids racing the startup config writes).
        maybeAutoRefreshRuleSources(_state.value.config)
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
        // CA generation is independent of the MITM toggle (0.1.1-alpha): the install guide needs the
        // certificate before MITM can be enabled.
        return runCatching {
            availableEngine.ensureCaCertificate() ?: availableEngine.caCertificatePem()
        }.onFailure { Log.w(TAG, "caCertificatePem failed", it) }.getOrNull()
    }

    private suspend fun refreshCaInstalledInternal(
        config: SidebikeConfig,
        allowEngineCreation: Boolean,
        attempts: Int = 1,
    ): Boolean {
        val pem = runCatching {
            withContext(Dispatchers.IO) { caCertificatePemInternal(allowEngineCreation) }
        }.getOrNull()
        if (pem == null) {
            _state.update { it.copy(caInstalled = false) }
            return false
        }
        val keyPem = readCaKeyPem(config)
        val detection = withContext(Dispatchers.IO) {
            runCatching { detectCaInstalled(pem, keyPem) }
                .getOrDefault(CaInstaller.Detection(false, "error"))
        }
        var effective = detection
        if (!effective.installed && attempts > 1) {
            withContext(Dispatchers.IO) {
                repeat(attempts - 1) {
                    if (effective.installed) return@repeat
                    runCatching { Thread.sleep(CA_REFRESH_DETECTION_DELAY_MS) }
                    effective = runCatching { detectCaInstalled(pem, keyPem) }
                        .getOrDefault(CaInstaller.Detection(false, "error"))
                }
            }
        }
        _state.update { it.copy(caInstalled = effective.installed) }
        if (effective.installed) {
            log(LogLevel.DEBUG, "ca", "检测到 CA 已安装（${effective.method}）")
        }
        return effective.installed
    }

    /**
     * 0.1.1-alpha migration for 0.1.0-beta.1 configs: MITM was enabled without a trusted CA, which
     * made every intercepted HTTPS request fail with `CERTIFICATE_UNKNOWN` (real-device report).
     * When the CA is not trusted we force-disable MITM, persist the change, hot-update the engine and
     * surface a clear warning so the next launch is explicit instead of silently broken.
     *
     * @return true when a legacy unsafe MITM configuration was downgraded.
     */
    private fun migrateLegacyMitmIfUnsafe(config: SidebikeConfig, caInstalled: Boolean): Boolean {
        if (caInstalled) return false
        if (!config.mitm.enabled && !config.proxy.mitmEnabled) return false
        val migrated = config.copy(
            mitm = config.mitm.copy(enabled = false),
            proxy = config.proxy.copy(mitmEnabled = false),
        )
        runCatching { configStore.save(migrated) }
            .onFailure { Log.w(TAG, "Failed to persist legacy MITM migration", it) }
        engine?.let { currentEngine ->
            runCatching { currentEngine.updateConfig(migrated) }
                .onFailure { Log.w(TAG, "engine.updateConfig failed during MITM migration", it) }
        }
        _state.update {
            it.copy(
                config = migrated,
                mitmActive = false,
                caInstalled = false,
                statusMessage = LEGACY_MITM_DOWNGRADE_MESSAGE,
                lastError = LEGACY_MITM_DOWNGRADE_MESSAGE,
            )
        }
        log(LogLevel.WARN, "mitm", LEGACY_MITM_DOWNGRADE_MESSAGE)
        return true
    }

    // ---------------------------------------------------------------- 0.1.1-alpha helpers

    /** Merges engine logger history with the controller log ring (deduplicated, chronological). */
    private fun collectAllLogs(): List<LogEntry> {
        val engineHistory = runCatching { engine?.logger()?.history() }.getOrNull().orEmpty()
        return (engineHistory + logs.value)
            .sortedBy { it.timeMs }
            .distinctBy { "${it.timeMs}|${it.tag}|${it.message}" }
            .takeLast(MAX_LOG_ENTRIES * 2)
    }

    private fun environmentExtraLines(snapshot: SidebikeUiState): List<String> = buildList {
        add("CA 指纹: ${caFingerprintOrNull() ?: "不可用"}")
        add("规则源: ${snapshot.config.ruleSources.size} 个（启用 ${snapshot.config.ruleSources.count { it.enabled }}）")
        add("日志条数: 控制器=${logs.value.size}, 导出=${collectAllLogs().size}")
        add("诊断进行中: ${snapshot.diagnosticsRunning}, 规则源刷新中: ${snapshot.ruleSourcesBusy}")
        val tunStats = VpnRuntimeStats.snapshot()
        add(
            "tun 丢弃: IPv6=${tunStats.ipv6Dropped}, 其他=${tunStats.otherDropped}, " +
                "DNS 查询=${tunStats.dnsQueries}, 最近原因=${tunStats.lastIgnoredReason ?: "无"}",
        )
    }

    private fun caFingerprintOrNull(): String? = runCatching {
        val pem = engine?.caCertificatePem() ?: return null
        CaInstaller.parseCertificate(pem)?.let { CaInstaller.sha256Fingerprint(it) }
    }.getOrNull()

    private fun readCaKeyPem(config: SidebikeConfig): String? = runCatching {
        File(appContext.filesDir, config.mitm.caKeyFileName).takeIf { it.isFile }?.readText()
    }.getOrNull()

    /** Returns the process engine, creating (but not starting) it if a CA/rule operation needs it. */
    private fun ensureEngineForOperations(config: SidebikeConfig): SidebikeEngine? {
        engine?.let { return it }
        SidebikeRuntime.engineOrNull()?.let { existing ->
            attachEngine(existing)
            return existing
        }
        val created = runCatching { SidebikeRuntime.ensureEngine(appContext, config) }
            .onFailure { Log.w(TAG, "Engine unavailable for controller operation", it) }
            .getOrNull() ?: return null
        attachEngine(created)
        return created
    }

    private fun summarizeDiagnostics(report: String): String = report.lineSequence()
        .filter { line -> line.contains("✅") || line.contains("❌") }
        .take(6)
        .joinToString("；") { it.trim() }

    /** Resolves the installed rule-source manager; tries the bootstrap installer when missing. */
    private fun resolveRuleSourceManager(): RuleSourceManager? {
        runCatching { RuleSourceManagerFactory.create() }.getOrNull()?.let { return it }
        EngineBootstrap.ensureRuleSourceManagerInstalled()
        return runCatching { RuleSourceManagerFactory.create() }.getOrNull()
    }

    /**
     * 0.1.1-alpha: refresh enabled+autoUpdate remote rule sources whose interval elapsed, in the
     * background so VPN startup is never blocked by network I/O.
     */
    private fun maybeAutoRefreshRuleSources(config: SidebikeConfig) {
        val now = System.currentTimeMillis()
        val due = config.ruleSources.any { source ->
            source.enabled && source.autoUpdate && source.updateIntervalHours > 0 &&
                (source.lastUpdatedMs <= 0L ||
                    now - source.lastUpdatedMs >= source.updateIntervalHours * 3_600_000L)
        }
        if (!due) return
        if (!ruleAutoRefreshInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                log(LogLevel.INFO, "rules", "规则源已到更新时间，开始后台自动刷新")
                refreshRuleSources(force = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                log(LogLevel.WARN, "rules", "自动刷新规则源失败: ${error.message}", error)
            } finally {
                ruleAutoRefreshInFlight.set(false)
            }
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
            val current = _state.value
            if (current.status == RunStatus.RUNNING && to != RunStatus.RUNNING) {
                runningEntryLogged.set(false)
            }
            _state.value = current.transitioned(to, message, error)
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
        if (current.status == RunStatus.RUNNING && to != RunStatus.RUNNING) {
            runningEntryLogged.set(false)
        }
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
        // The system-proxy registration only means something while the VPN network is up.
        systemProxyRegistered = if (to == RunStatus.RUNNING) systemProxyRegistered else null,
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

    /** Real secure-lock-screen probe backed by [KeyguardManager] (CA installer prerequisite). */
    private fun isDeviceSecureReal(): Boolean = runCatching {
        appContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }.getOrDefault(false)

    /** Test/caller hook: releases resources and stops all coroutines. Idempotent. */
    internal fun close() {
        cancelStatsPolling()
        startJob?.cancel()
        startRequested.set(false)
        runningEntryLogged.set(false)
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

        /** CA-installed re-detection after returning from the system installer (user issue 3). */
        private const val CA_REFRESH_DETECTION_ATTEMPTS = 2
        private const val CA_REFRESH_DETECTION_DELAY_MS = 600L

        /**
         * Warning surfaced when a legacy config had MITM enabled without a trusted CA (0.1.0-beta.1
         * real-device bug: CERTIFICATE_UNKNOWN storms on googleapis/gstatic). Persisted + shown in UI.
         */
        private const val LEGACY_MITM_DOWNGRADE_MESSAGE =
            "检测到 MITM 已开启但 CA 证书未安装，已自动降级直连，避免境外 HTTPS 全部失败；请先安装证书再重新开启 MITM"

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
