package dev.sidebike.app.controller

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sidebike.app.data.SidebikeConfigStore
import dev.sidebike.app.data.SidebikeRuntime
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.vpn.VpnPhase
import dev.sidebike.app.vpn.VpnServiceBridge
import dev.sidebike.core.api.EngineStats
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeEngine
import dev.sidebike.core.api.SidebikeEngineFactory
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.api.TrafficStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * State-machine tests for [DefaultSidebikeController] with a fake engine injected through
 * [SidebikeEngineFactory.install] and a fake [VpnGateway] — no real VpnService, no real sockets
 * (port allocation/probing are injected as well), so the tests are hermetic on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultSidebikeControllerTest {

    private lateinit var context: Context
    private lateinit var configStore: SidebikeConfigStore
    private lateinit var gateway: FakeVpnGateway
    private lateinit var controller: DefaultSidebikeController
    private var consentRequired = false
    private var portAllocator: (Int) -> Int? = { desired -> desired }
    private var portProbe: (Int) -> Boolean = { true }
    private lateinit var networkDetector: FakeNetworkDetector
    private var createdEngine: FakeEngine? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        configStore = SidebikeConfigStore(context)
        configStore.resetToDefaults()
        VpnServiceBridge.reset()
        SidebikeRuntime.resetForTest()
        createdEngine = null
        SidebikeEngineFactory.install { _, initialConfig, _ ->
            FakeEngine(initialConfig).also { createdEngine = it }
        }
        gateway = FakeVpnGateway()
        networkDetector = FakeNetworkDetector()
        consentRequired = false
        portAllocator = { desired -> desired }
        portProbe = { true }
        controller = createController()
    }

    @After
    fun tearDown() {
        DefaultSidebikeController.resetForTest()
        controller.close()
        SidebikeRuntime.resetForTest()
        configStore.resetToDefaults()
        VpnServiceBridge.reset()
    }

    // ---------------------------------------------------------------- initial state / config

    @Test
    fun initialStateIsIdleAndLoadsPersistedConfig() {
        configStore.save(SidebikeConfig(proxy = SidebikeConfig().proxy.copy(port = 33000)))
        controller.close()
        controller = createController()

        val state = awaitState { it.config.proxy.port == 33000 }
        assertEquals(RunStatus.IDLE, state.status)
        assertFalse(state.vpnActive)
        assertEquals(33000, state.effectiveProxyPort)
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun startStartsEngineAndReachesRunning() {
        controller.start()

        val running = awaitState { it.status == RunStatus.RUNNING }
        assertTrue(running.vpnActive)
        assertNotNull("running state must advertise the effective proxy port", running.effectiveProxyPort)
        assertEquals(1, gateway.starts.size)
        assertNotNull(createdEngine)
        assertEquals(1, createdEngine!!.startCalls)
        assertTrue("engine must stay running", createdEngine!!.isRunning)
    }

    @Test
    fun repeatedStartWhileRunningIsIgnored() {
        controller.start()
        awaitState { it.status == RunStatus.RUNNING }
        controller.start()
        Thread.sleep(300)
        assertEquals(1, gateway.starts.size)
        assertEquals(1, createdEngine!!.startCalls)
        assertEquals(RunStatus.RUNNING, controller.state.value.status)
    }

    @Test
    fun stopAfterRunningReturnsToIdleAndReleasesEngine() {
        controller.start()
        awaitState { it.status == RunStatus.RUNNING }
        controller.stop()

        val idle = awaitState { it.status == RunStatus.IDLE }
        assertFalse(idle.vpnActive)
        assertTrue("VPN service stop must be requested", gateway.stops >= 1)
        assertTrue("engine must be stopped", createdEngine!!.stopCalls >= 1)
        assertFalse(createdEngine!!.isRunning)
    }

    // ---------------------------------------------------------------- failure paths

    @Test
    fun engineStartFailureLandsInErrorWithoutStartingVpn() {
        SidebikeEngineFactory.install { _, initialConfig, _ ->
            FakeEngine(initialConfig).also {
                it.failStart = true
                createdEngine = it
            }
        }
        controller.close()
        controller = createController()

        controller.start()
        val state = awaitState { it.status == RunStatus.ERROR }

        assertFalse(state.vpnActive)
        assertTrue(
            "error must mention the engine failure, was: ${state.lastError}",
            state.lastError?.contains("引擎") == true || state.statusMessage.contains("引擎"),
        )
        assertEquals("VPN must not be started when the engine fails", 0, gateway.starts.size)
        assertTrue("failed engine must be released", createdEngine!!.stopCalls >= 1)
    }

    @Test
    fun serviceFailureDuringStartReportsErrorAndAllowsRetry() {
        gateway.startMode = FakeVpnGateway.StartMode.FAIL
        controller.start()
        val failed = awaitState { it.status == RunStatus.ERROR }
        assertNotNull(failed.lastError)
        assertEquals(1, gateway.starts.size)

        // No zombie lock: a retry after an error must be able to start again.
        gateway.startMode = FakeVpnGateway.StartMode.RUNNING
        VpnServiceBridge.reset()
        controller.start()
        awaitState { it.status == RunStatus.RUNNING }
        assertEquals(2, gateway.starts.size)
    }

    @Test
    fun missingConsentKeepsIdleAndDoesNotStart() {
        consentRequired = true
        controller.close()
        controller = createController()
        controller.start()

        val state = runBlocking {
            withTimeout(FAIL_TIMEOUT_MS) {
                controller.state.first { it.status == RunStatus.IDLE && it.statusMessage.isNotEmpty() }
            }
        }
        assertEquals(RunStatus.IDLE, state.status)
        assertTrue(state.statusMessage.contains("授权"))
        assertEquals(0, gateway.starts.size)
        assertEquals(0, createdEngine?.startCalls ?: 0)
    }

    @Test
    fun revokedVpnReportsErrorAndStopsEngine() {
        controller.start()
        awaitState { it.status == RunStatus.RUNNING }
        VpnServiceBridge.publish(VpnPhase.Revoked)

        val state = awaitState { it.status == RunStatus.ERROR }
        assertTrue(
            "revoked state must explain the revocation, was: ${state.lastError}",
            state.lastError?.contains("撤销") == true,
        )
        assertFalse(state.vpnActive)
        assertTrue(createdEngine!!.stopCalls >= 1)
    }

    @Test
    fun proxyWatchdogStopsVpnAndReportsError() {
        controller.start()
        awaitState { it.status == RunStatus.RUNNING }
        createdEngine!!.proxyRunning = false

        val state = awaitState(timeoutMs = 10_000) { it.status == RunStatus.ERROR }
        assertTrue(
            "watchdog must explain the proxy failure, was: ${state.lastError}",
            state.lastError?.contains("代理") == true,
        )
        assertTrue(gateway.stops >= 1)
        assertTrue(createdEngine!!.stopCalls >= 1)
    }

    // ---------------------------------------------------------------- port fallback / config

    @Test
    fun busyDefaultPortFallsBackAndPersistsEffectivePort() {
        portAllocator = { desired -> desired + 5 }
        controller.close()
        controller = createController()
        controller.start()

        val running = awaitState { it.status == RunStatus.RUNNING }
        assertEquals(31186, running.effectiveProxyPort)
        assertEquals(31186, running.config.proxy.port)
        assertEquals(31186, configStore.load().proxy.port)
    }

    @Test
    fun updateConfigPersistsAndHotUpdatesEngine() {
        controller.start()
        awaitState { it.status == RunStatus.RUNNING }
        val custom = SidebikeConfig(proxy = SidebikeConfig().proxy.copy(port = 32323))

        runBlocking { controller.updateConfig(custom) }

        assertEquals(32323, configStore.load().proxy.port)
        assertEquals(32323, controller.state.value.config.proxy.port)
        assertTrue(createdEngine!!.updateConfigCalls >= 1)
        assertEquals(32323, createdEngine!!.currentConfig().proxy.port)
    }

    // ---------------------------------------------------------------- CA / diagnostics / logs

    @Test
    fun caCertificatePemDelegatesToEngine() {
        val pem = "-----BEGIN CERTIFICATE-----\nTEST\n-----END CERTIFICATE-----"
        SidebikeEngineFactory.install { _, initialConfig, _ ->
            FakeEngine(initialConfig).also { it.caPem = pem; createdEngine = it }
        }
        controller.close()
        controller = createController()
        val result = runBlocking { controller.caCertificatePem() }
        assertEquals(pem, result)
    }

    @Test
    fun exportDiagnosticsContainsConfigAndStatus() {
        val report = runBlocking { controller.exportDiagnostics() }
        assertTrue(report.contains("Dev-Sidebike 诊断信息"))
        assertTrue(report.contains("=== 配置 (JSON) ==="))
        assertTrue(report.contains("状态: IDLE"))
    }

    @Test
    fun engineAndControllerLogsAreExposedToUi() {
        controller.start()
        awaitState { it.status == RunStatus.RUNNING }
        val logs = runBlocking {
            withTimeout(FAIL_TIMEOUT_MS) {
                controller.logs.first { entries ->
                    entries.any { it.message.contains("VPN 已运行") } &&
                        entries.any { it.tag == "controller" }
                }
            }
        }
        assertTrue(logs.isNotEmpty())
    }

    // ---------------------------------------------------------------- helpers

    private fun createController(): DefaultSidebikeController = DefaultSidebikeController(
        context = context,
        gateway = gateway,
        consentChecker = VpnConsentChecker { consentRequired },
        configStore = configStore,
        portAllocator = { desired -> portAllocator(desired) },
        portProbe = { port -> portProbe(port) },
        networkStateDetector = networkDetector,
    )

    private fun awaitState(
        timeoutMs: Long = 8_000,
        predicate: (SidebikeUiState) -> Boolean,
    ): SidebikeUiState = runBlocking {
        withTimeout(timeoutMs) { controller.state.first(predicate) }
    }

    // ---------------------------------------------------------------- fakes

    private class FakeVpnGateway : VpnGateway {
        enum class StartMode { RUNNING, FAIL, SILENT }

        var startMode: StartMode = StartMode.RUNNING
        val starts = CopyOnWriteArrayList<Long>()
        @Volatile
        var stops: Int = 0

        override fun start(context: Context) {
            starts += System.currentTimeMillis()
            when (startMode) {
                StartMode.RUNNING -> VpnServiceBridge.publish(VpnPhase.Running)
                StartMode.FAIL -> VpnServiceBridge.publish(VpnPhase.Failed("tun 建立失败"))
                StartMode.SILENT -> Unit
            }
        }

        override fun stop(context: Context) {
            stops += 1
            VpnServiceBridge.publish(VpnPhase.Stopped)
        }
    }

    private class FakeNetworkDetector : NetworkStateDetector {
        @Volatile
        var privateDnsActive: Boolean = false

        @Volatile
        var lockdownEnabled: Boolean = false

        @Volatile
        var vpnHttpProxyRegistered: Boolean = true

        override fun isPrivateDnsActive(): Boolean = privateDnsActive
        override fun isLockdownEnabled(): Boolean = lockdownEnabled
        override fun isVpnHttpProxyRegistered(): Boolean = vpnHttpProxyRegistered
    }

    private class FakeEngine(initialConfig: SidebikeConfig) : SidebikeEngine {
        @Volatile
        var running: Boolean = false

        @Volatile
        var failStart: Boolean = false

        @Volatile
        var proxyRunning: Boolean = true

        @Volatile
        var caPem: String? = null

        @Volatile
        var startCalls: Int = 0

        @Volatile
        var stopCalls: Int = 0

        @Volatile
        var updateConfigCalls: Int = 0

        private var currentConfig: SidebikeConfig = initialConfig
        private val logger = FakeLogger()
        private val lock = Any()

        override fun start() {
            synchronized(lock) {
                startCalls += 1
                if (failStart) throw IllegalStateException("模拟引擎启动失败")
                running = true
            }
        }

        override fun stop() {
            synchronized(lock) {
                stopCalls += 1
                running = false
            }
        }

        override val isRunning: Boolean get() = running

        override fun updateConfig(config: SidebikeConfig) {
            synchronized(lock) {
                updateConfigCalls += 1
                currentConfig = config
            }
        }

        override fun currentConfig(): SidebikeConfig = currentConfig

        override suspend fun resolveRawDnsQuery(query: ByteArray): ByteArray? = null

        override fun stats(): EngineStats = EngineStats(
            traffic = TrafficStats(dnsQueries = 7),
            proxyRunning = proxyRunning && running,
            caReady = false,
            dnsRunning = running,
        )

        override fun logger(): SidebikeLogger = logger

        override fun caCertificatePem(): String? = caPem

        override fun ensureCaCertificate(): String? = caPem

        override fun close() = Unit
    }

    private class FakeLogger : SidebikeLogger {
        private val entries = CopyOnWriteArrayList<LogEntry>()
        private val subscribers = CopyOnWriteArrayList<(LogEntry) -> Unit>()

        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            val entry = LogEntry(System.currentTimeMillis(), level, tag, message, throwable)
            entries += entry
            subscribers.forEach { it(entry) }
        }

        override fun history(): List<LogEntry> = entries.toList()

        override fun clear() {
            entries.clear()
        }

        override fun subscribe(listener: (LogEntry) -> Unit): AutoCloseable {
            subscribers += listener
            return AutoCloseable { subscribers.remove(listener) }
        }
    }

    private companion object {
        private const val FAIL_TIMEOUT_MS = 10_000L
    }
}
