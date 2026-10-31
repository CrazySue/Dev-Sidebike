package dev.sidebike.app.controller

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.sidebike.app.data.CaInstaller
import dev.sidebike.app.data.ConnectivityProbes
import dev.sidebike.app.data.ProbeOutcome
import dev.sidebike.app.data.SidebikeConfigStore
import dev.sidebike.app.data.SidebikeRuntime
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.vpn.VpnPhase
import dev.sidebike.app.vpn.VpnServiceBridge
import dev.sidebike.core.api.DnsProviderHealth
import dev.sidebike.core.api.EngineStats
import dev.sidebike.core.api.ImportedRuleSet
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.RuleSource
import dev.sidebike.core.api.RuleSourceFormat
import dev.sidebike.core.api.RuleSourceManager
import dev.sidebike.core.api.RuleSourceRefreshResult
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeEngine
import dev.sidebike.core.api.SidebikeEngineFactory
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.api.TrafficStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

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
    private var caDetection: CaInstaller.Detection = CaInstaller.Detection(false, "test-not-installed")
    private var probes: FakeConnectivityProbes = FakeConnectivityProbes()
    private var ruleManager: FakeRuleSourceManager? = null
    private var secureLockConfigured = true

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
        caDetection = CaInstaller.Detection(false, "test-not-installed")
        probes = FakeConnectivityProbes()
        ruleManager = null
        secureLockConfigured = true
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

        // No zombie lock: a retry after an error must be able to start again. `start()` clears its
        // re-entrancy guard in the coroutine that reports ERROR, so poll briefly until it is released.
        gateway.startMode = FakeVpnGateway.StartMode.RUNNING
        VpnServiceBridge.reset()
        val deadline = System.currentTimeMillis() + FAIL_TIMEOUT_MS
        while (gateway.starts.size < 2 && System.currentTimeMillis() < deadline) {
            controller.start()
            Thread.sleep(100)
        }
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
    fun dnsProbeFailureAbortsBeforeVpnIsEstablished() {
        // Engine starts and its proxy port probes fine, but DNS resolution is broken: the controller
        // must NOT establish the VPN (otherwise every app would lose DNS = device-wide black hole).
        SidebikeEngineFactory.install { _, initialConfig, _ ->
            FakeEngine(initialConfig).also {
                it.dnsProbeHealthy = false
                createdEngine = it
            }
        }
        controller.close()
        controller = createController()

        controller.start()
        val state = awaitState { it.status == RunStatus.ERROR }

        assertFalse(state.vpnActive)
        assertTrue(
            "error must explain the DNS abort, was: ${state.lastError}",
            state.lastError?.contains("DNS") == true,
        )
        assertEquals("VPN must never be started when DNS is unusable", 0, gateway.starts.size)
        assertTrue("failed engine must be released", createdEngine!!.stopCalls >= 1)
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

        val state = awaitState(timeoutMs = 20_000) { it.status == RunStatus.ERROR }
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
                    entries.any { it.tag == "controller" && it.message.contains("已运行") }
                }
            }
        }
        assertTrue(logs.any { it.tag == "controller" })
        assertTrue(logs.any { it.message.contains("已运行") })
    }

    // ---------------------------------------------------------------- 0.1.1-alpha: log export

    @Test
    fun buildLogTextContainsEnvironmentDiagnosticsAndLogs() {
        val logs = runBlocking {
            withTimeout(FAIL_TIMEOUT_MS) { controller.logs.first { it.isNotEmpty() } }
        }
        assertTrue(logs.isNotEmpty())
        val text = runBlocking { controller.buildLogText() }
        assertTrue(text.contains("Dev-Sidebike 环境信息"))
        assertTrue(text.contains("=== 诊断信息 ==="))
        assertTrue(text.contains("=== 运行日志"))
        assertTrue(text.contains("controller"))
    }

    @Test
    fun buildDebugBundleIsZipWithRequiredEntries() {
        val bytes = runBlocking { controller.buildDebugBundle() }
        assertTrue(bytes.isNotEmpty())
        val entries = linkedSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertTrue(entries.containsAll(setOf("device-info.txt", "diagnostics.txt", "logs.txt", "config.json", "state.txt")))
    }

    // ---------------------------------------------------------------- 0.1.1-alpha: diagnostics

    @Test
    fun runNetworkDiagnosticsReturnsReportAndResetsBusyFlag() {
        val report = runBlocking { controller.runNetworkDiagnostics() }
        assertTrue(report.contains("=== Dev-Sidebike 网络诊断 ==="))
        assertTrue(report.contains("[1/5]"))
        assertTrue(report.contains("=== 结论 ==="))
        assertFalse("diagnosticsRunning must be reset", controller.state.value.diagnosticsRunning)
    }

    // ---------------------------------------------------------------- 0.1.1-alpha: CA / MITM

    @Test
    fun prepareCaInstallGeneratesCaEvenWhenMitmDisabled() {
        val pem = generateCaPem()
        SidebikeEngineFactory.install { _, initialConfig, _ ->
            FakeEngine(initialConfig).also { it.caPem = pem; createdEngine = it }
        }
        controller.close()
        controller = createController()
        configStore.save(SidebikeConfig())
        assertFalse("precondition: MITM disabled", configStore.load().mitm.enabled)

        val request = runBlocking { controller.prepareCaInstall() }
        assertNotNull("CA must be generated regardless of the MITM toggle", request)
        assertEquals(pem, request!!.pem)
        assertEquals(32, request.sha256Fingerprint.split(':').size)
        assertTrue(request.subject.contains("CN="))
        assertNotNull(request.intent)
        assertNotNull(request.settingsFallbackIntent)
    }

    @Test
    fun setMitmEnabledRejectsWhenCaNotInstalled() {
        val pem = generateCaPem()
        SidebikeEngineFactory.install { _, initialConfig, _ ->
            FakeEngine(initialConfig).also { it.caPem = pem; createdEngine = it }
        }
        caDetection = CaInstaller.Detection(false, "android-ca-store-miss")
        controller.close()
        controller = createController()

        val result = runBlocking { controller.setMitmEnabled(true) }

        assertFalse("MITM must not be enabled without a trusted CA", result.effective)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("CA"))
        assertFalse(configStore.load().mitm.enabled)
        assertFalse(controller.state.value.mitmActive)
    }

    @Test
    fun setMitmEnabledEnablesAndPersistsWhenCaDetected() {
        val pem = generateCaPem()
        SidebikeEngineFactory.install { _, initialConfig, _ ->
            FakeEngine(initialConfig).also { it.caPem = pem; createdEngine = it }
        }
        caDetection = CaInstaller.Detection(true, "android-ca-store")
        controller.close()
        controller = createController()

        val result = runBlocking { controller.setMitmEnabled(true) }

        assertTrue(result.effective)
        val persisted = configStore.load()
        assertTrue(persisted.mitm.enabled)
        assertTrue(persisted.proxy.mitmEnabled)
        assertTrue(controller.state.value.config.mitm.enabled)
        assertTrue("engine must be hot-updated", createdEngine!!.updateConfigCalls >= 1)
    }

    @Test
    fun setMitmDisabledPersistsImmediately() {
        configStore.save(
            SidebikeConfig(
                mitm = SidebikeConfig().mitm.copy(enabled = true),
                proxy = SidebikeConfig().proxy.copy(mitmEnabled = true),
            ),
        )
        controller.close()
        controller = createController()

        val result = runBlocking { controller.setMitmEnabled(false) }
        assertFalse(result.effective)
        assertFalse(configStore.load().mitm.enabled)
        assertFalse(configStore.load().proxy.mitmEnabled)
    }

    // ---------------------------------------------------------------- 0.1.1-alpha: legacy MITM migration

    @Test
    fun legacyMitmConfigWithoutCaIsAutoDowngradedAtStartup() {
        // Real-device bug (0.1.0-beta.1): config had MITM enabled but CA was never trusted, so every
        // intercepted HTTPS request failed with CERTIFICATE_UNKNOWN. Loading such a config must
        // auto-downgrade + persist the safer value and surface a UI-visible warning.
        val pem = generateCaPem()
        SidebikeEngineFactory.install { _, initialConfig, _ ->
            FakeEngine(initialConfig).also { it.caPem = pem; createdEngine = it }
        }
        caDetection = CaInstaller.Detection(false, "android-ca-store-miss")
        configStore.save(
            SidebikeConfig(
                mitm = SidebikeConfig().mitm.copy(enabled = true),
                proxy = SidebikeConfig().proxy.copy(mitmEnabled = true),
            ),
        )
        controller.close()
        controller = createController()

        val migratedState = awaitState { !it.config.mitm.enabled && !it.config.proxy.mitmEnabled }
        assertFalse("persisted config must be migrated too", configStore.load().mitm.enabled)
        assertFalse(configStore.load().proxy.mitmEnabled)
        assertFalse(migratedState.mitmActive)
        val hint = migratedState.lastError ?: migratedState.statusMessage
        assertTrue("UI-visible warning expected, was: $hint", hint.contains("MITM") && hint.contains("CA"))
        // Engine (created for CA detection) must be hot-updated to the downgraded config.
        assertNotNull(createdEngine)
        assertFalse(createdEngine!!.currentConfig().mitm.enabled)
    }

    @Test
    fun legacyMitmConfigWithInstalledCaIsKeptEnabled() {
        val pem = generateCaPem()
        SidebikeEngineFactory.install { _, initialConfig, _ ->
            FakeEngine(initialConfig).also { it.caPem = pem; createdEngine = it }
        }
        caDetection = CaInstaller.Detection(true, "android-ca-store")
        configStore.save(
            SidebikeConfig(
                mitm = SidebikeConfig().mitm.copy(enabled = true),
                proxy = SidebikeConfig().proxy.copy(mitmEnabled = true),
            ),
        )
        controller.close()
        controller = createController()

        val state = awaitState { it.caInstalled }
        assertTrue("trusted CA must NOT be downgraded", state.config.mitm.enabled)
        assertTrue(configStore.load().mitm.enabled)
    }

    @Test
    fun updateConfigDowngradesMitmWhenCaMissing() {
        controller.close()
        controller = createController()
        assertFalse("precondition: no trusted CA", controller.state.value.caInstalled)

        runBlocking {
            controller.updateConfig(
                SidebikeConfig(
                    mitm = SidebikeConfig().mitm.copy(enabled = true),
                    proxy = SidebikeConfig().proxy.copy(mitmEnabled = true),
                ),
            )
        }

        assertFalse("updateConfig must not persist MITM without CA", configStore.load().mitm.enabled)
        assertFalse(configStore.load().proxy.mitmEnabled)
        assertFalse(controller.state.value.config.mitm.enabled)
    }

    // ---------------------------------------------------------------- 0.1.1-alpha: CA lock-screen

    @Test
    fun prepareCaInstallRefusesWithoutSecureLockScreen() {
        val pem = generateCaPem()
        SidebikeEngineFactory.install { _, initialConfig, _ ->
            FakeEngine(initialConfig).also { it.caPem = pem; createdEngine = it }
        }
        secureLockConfigured = false
        controller.close()
        controller = createController()

        val request = runBlocking { controller.prepareCaInstall() }

        assertNull("must not launch the installer without a secure lock screen", request)
        val hint = controller.state.value.lastError ?: controller.state.value.statusMessage
        assertTrue("UI must be told to set a lock screen, was: $hint", hint.contains("锁屏"))
    }

    @Test
    fun prepareCaInstallWorksWithSecureLockScreen() {
        val pem = generateCaPem()
        SidebikeEngineFactory.install { _, initialConfig, _ ->
            FakeEngine(initialConfig).also { it.caPem = pem; createdEngine = it }
        }
        secureLockConfigured = true
        controller.close()
        controller = createController()

        assertNotNull(runBlocking { controller.prepareCaInstall() })
    }

    // ---------------------------------------------------------------- 0.1.1-alpha: rule editing

    @Test
    fun saveRulePersistsAndUpdatesState() {
        runBlocking { controller.saveRule(RuleDraft(domainPattern = "API.example.com", sni = "baidu.com")) }
        val config = configStore.load()
        assertTrue(config.intercepts.containsKey("api.example.com"))
        assertEquals("baidu.com", config.intercepts["api.example.com"]?.get(".*")?.sni)
        assertTrue(controller.state.value.config.intercepts.containsKey("api.example.com"))
    }

    @Test
    fun deleteRuleRemovesUserRuleAndDisablesBuiltInRule() {
        runBlocking { controller.saveRule(RuleDraft(domainPattern = "user.example.com")) }
        runBlocking { controller.deleteRule("user.example.com", ".*") }
        assertFalse(configStore.load().intercepts.containsKey("user.example.com"))

        val builtInDomain = dev.sidebike.core.config.BundledConfig.load().intercepts.keys.first()
        val builtInPath = dev.sidebike.core.config.BundledConfig.load().intercepts.getValue(builtInDomain).keys.first()
        runBlocking { controller.deleteRule(builtInDomain, builtInPath) }
        val after = configStore.load()
        assertFalse(after.intercepts[builtInDomain]?.containsKey(builtInPath) == true)
        assertTrue(
            "deleting a bundled rule must persist a disable marker",
            after.disabledIntercepts.contains(ruleKey(builtInDomain, builtInPath)),
        )
    }

    @Test
    fun setDirectPassDomainsTracksManagedEntriesAcrossEdits() {
        runBlocking { controller.setDirectPassDomains(listOf("a.example.com", "A.example.com")) }
        assertEquals(listOf("a.example.com"), configStore.loadManagedDirectPassDomains())
        assertEquals(true, configStore.load().whiteList["a.example.com"])

        runBlocking { controller.setDirectPassDomains(listOf("b.example.com")) }
        val config = configStore.load()
        assertEquals(true, config.whiteList["b.example.com"])
        assertFalse("old managed entry must be removed", config.whiteList.containsKey("a.example.com"))
        assertEquals(listOf("b.example.com"), configStore.loadManagedDirectPassDomains())
    }

    @Test
    fun setDomesticAllowListOverwritesPersistedList() {
        runBlocking { controller.setDomesticAllowList(listOf("A.cn", "a.cn", "b.cn")) }
        assertEquals(listOf("a.cn", "b.cn"), configStore.load().domesticAllowList)
        assertEquals(listOf("a.cn", "b.cn"), controller.state.value.config.domesticAllowList)
    }

    @Test
    fun importRulesFromTextMergesIdempotently() {
        val manager = FakeRuleSourceManager().apply {
            parseResult = ImportedRuleSet(
                intercepts = mapOf(
                    "imported-task8.example.com" to mapOf(".*" to InterceptAction(sni = "baidu.com")),
                ),
                directPassDomains = listOf("direct-task8.example.com"),
                domesticDomains = listOf("task8-domestic.example.cn"),
            )
        }
        ruleManager = manager

        val first = runBlocking { controller.importRulesFromText("content", RuleSourceFormat.AUTO, "测试源") }
        assertEquals(1, first.addedIntercepts)
        assertEquals(1, first.addedDirectPassDomains)
        assertEquals(1, first.addedDomesticDomains)
        val config = configStore.load()
        assertEquals("baidu.com", config.intercepts["imported-task8.example.com"]?.get(".*")?.sni)
        assertEquals(true, config.whiteList["direct-task8.example.com"])
        assertTrue(config.domesticAllowList.contains("task8-domestic.example.cn"))

        val second = runBlocking { controller.importRulesFromText("content", RuleSourceFormat.AUTO, "测试源") }
        assertEquals(0, second.addedIntercepts)
        assertEquals(0, second.addedDirectPassDomains)
        assertEquals(0, second.addedDomesticDomains)
        assertEquals(2, manager.parseCalls.size)
    }

    @Test
    fun refreshRuleSourcesUpdatesSummaryMetadataAndBusyFlag() {
        configStore.save(
            SidebikeConfig(
                ruleSources = listOf(
                    RuleSource(id = "s1", name = "来源1", url = "https://example.com/a.json"),
                    RuleSource(id = "s2", name = "来源2", url = "https://example.com/b.json"),
                    RuleSource(id = "s3", name = "来源3", url = "https://example.com/c.json"),
                ),
            ),
        )
        val manager = FakeRuleSourceManager().apply {
            refreshHandler = { sources, _ ->
                sources.map { source ->
                    when (source.id) {
                        "s1" -> RuleSourceRefreshResult(
                            source = source,
                            imported = ImportedRuleSet(
                                intercepts = mapOf("imported.example.com" to mapOf(".*" to InterceptAction(proxy = "x"))),
                            ),
                        )
                        "s2" -> RuleSourceRefreshResult(source = source, notModified = true)
                        else -> RuleSourceRefreshResult(source = source, error = "HTTP 500")
                    }
                }
            }
        }
        ruleManager = manager
        controller.close()
        controller = createController()

        val summary = runBlocking { controller.refreshRuleSources(force = true) }

        assertEquals(listOf("来源1"), summary.updatedSources)
        assertEquals(listOf("来源2"), summary.notModifiedSources)
        assertEquals("HTTP 500", summary.failedSources["来源3"])
        assertEquals(1, summary.importedRules)
        assertTrue(summary.hasChanges)
        assertFalse("ruleSourcesBusy must be reset", controller.state.value.ruleSourcesBusy)

        val sources = configStore.load().ruleSources.associateBy { it.id }
        assertTrue("updated source metadata must persist", sources.getValue("s1").lastUpdatedMs > 0)
        assertEquals(1, sources.getValue("s1").lastImported)
        assertNull(sources.getValue("s2").lastError)
        assertEquals("HTTP 500", sources.getValue("s3").lastError)
        assertEquals(true, configStore.load().intercepts.containsKey("imported.example.com"))
    }

    @Test
    fun refreshRuleSourcesWithoutManagerReturnsFailureSummary() {
        ruleManager = null
        val summary = runBlocking { controller.refreshRuleSources(force = true) }
        assertTrue(summary.failedSources.isNotEmpty())
    }

    @Test
    fun startAutoRefreshesDueRuleSources() {
        configStore.save(
            SidebikeConfig(
                ruleSources = listOf(
                    RuleSource(
                        id = "due",
                        name = "到期源",
                        url = "https://example.com/due.json",
                        autoUpdate = true,
                        updateIntervalHours = 1,
                        lastUpdatedMs = 1,
                    ),
                ),
            ),
        )
        val manager = FakeRuleSourceManager().apply {
            refreshHandler = { sources, _ ->
                sources.map { source ->
                    RuleSourceRefreshResult(
                        source = source,
                        imported = ImportedRuleSet(directPassDomains = listOf("auto-refreshed.example.com")),
                    )
                }
            }
        }
        ruleManager = manager
        controller.close()
        controller = createController()

        controller.start()
        awaitState { it.status == RunStatus.RUNNING }

        runBlocking {
            withTimeout(FAIL_TIMEOUT_MS) {
                while (manager.refreshAllCalls.isEmpty()) {
                    kotlinx.coroutines.delay(50)
                }
            }
        }
        assertTrue("due rule source must be refreshed in the background", manager.refreshAllCalls.isNotEmpty())
        // The merge/persist happens after the manager returns; poll briefly for the persisted result.
        runBlocking {
            withTimeout(FAIL_TIMEOUT_MS) {
                while (configStore.load().whiteList["auto-refreshed.example.com"] != true) {
                    kotlinx.coroutines.delay(50)
                }
            }
        }
        assertTrue("source metadata must be updated", configStore.load().ruleSources[0].lastUpdatedMs > 1)
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
        caDetector = { _, _ -> caDetection },
        connectivityProbes = probes,
        ruleManagerProvider = { ruleManager },
        caInstallBundleBuilder = { pem -> buildFakeInstallBundle(pem) },
        secureLockChecker = { secureLockConfigured },
    )

    /** Deterministic install bundle for controller tests (real KeyChain path is covered separately). */
    private fun buildFakeInstallBundle(pem: String): CaInstaller.InstallBundle? {
        val cert = CaInstaller.parseCertificate(pem) ?: return null
        return CaInstaller.InstallBundle(
            intent = Intent(Intent.ACTION_VIEW),
            settingsFallbackIntent = Intent(Settings.ACTION_SETTINGS),
            pem = pem,
            sha256Fingerprint = CaInstaller.sha256Fingerprint(cert),
            subject = CaInstaller.subjectDn(cert),
        )
    }

    /** Generates a real self-signed CA certificate (PEM) for CA-flow tests. */
    private fun generateCaPem(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=Dev-Sidebike Controller Test CA, O=Dev-Sidebike")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 60_000),
            Date(now + 24L * 3600 * 1000),
            name,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.digitalSignature))
        val certificate: X509Certificate = JcaX509CertificateConverter()
            .getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)))
        val base64 = java.util.Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(certificate.encoded)
        return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n"
    }

    private fun awaitState(
        timeoutMs: Long = 20_000,
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

    private class FakeConnectivityProbes : ConnectivityProbes {
        override suspend fun dnsHealth(engine: SidebikeEngine): List<DnsProviderHealth> =
            listOf(DnsProviderHealth(provider = "aliyun", ok = true, latencyMs = 8))

        override suspend fun resolveIps(engine: SidebikeEngine, host: String, timeoutMs: Long): List<String> =
            listOf("20.205.243.166")

        override fun directTls(host: String, port: Int, timeoutMs: Int): ProbeOutcome =
            ProbeOutcome(ok = true, latencyMs = 100, detail = "TLS TLSv1.3")

        override fun proxyConnect(proxyPort: Int, host: String, port: Int, timeoutMs: Int): ProbeOutcome =
            ProbeOutcome(ok = true, latencyMs = 10, detail = "HTTP/1.1 200 Connection established")

        override fun proxyHttpGet(proxyPort: Int, url: String, timeoutMs: Int): ProbeOutcome =
            ProbeOutcome(ok = true, latencyMs = 10, detail = "HTTP/1.1 200 OK")
    }

    private class FakeRuleSourceManager : RuleSourceManager {
        @Volatile
        var parseResult: ImportedRuleSet = ImportedRuleSet.EMPTY

        @Volatile
        var refreshHandler: (List<RuleSource>, Boolean) -> List<RuleSourceRefreshResult> =
            { sources, _ -> sources.map { RuleSourceRefreshResult(source = it, notModified = true) } }

        val refreshAllCalls = CopyOnWriteArrayList<Pair<List<RuleSource>, Boolean>>()
        val parseCalls = CopyOnWriteArrayList<String>()

        override suspend fun refresh(source: RuleSource, force: Boolean): RuleSourceRefreshResult =
            refreshHandler(listOf(source), force).firstOrNull()
                ?: RuleSourceRefreshResult(source = source, error = "no result")

        override suspend fun refreshAll(
            sources: List<RuleSource>,
            force: Boolean,
        ): List<RuleSourceRefreshResult> {
            refreshAllCalls += sources to force
            return refreshHandler(sources, force)
        }

        override fun parse(
            content: String,
            format: RuleSourceFormat,
            name: String,
            url: String,
        ): ImportedRuleSet {
            parseCalls += content
            return parseResult
        }
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
        var dnsProbeHealthy: Boolean = true

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

        override suspend fun resolveRawDnsQuery(query: ByteArray): ByteArray? {
            // Minimal usable DNS response: QR=1, RA=1, RCODE=0, ANCOUNT=1 (enough for DnsProbe).
            if (!dnsProbeHealthy) return null
            val response = ByteArray(12)
            if (query.size >= 2) {
                response[0] = query[0]
                response[1] = query[1]
            }
            response[2] = 0x81.toByte() // QR=1, RD=1
            response[3] = 0x80.toByte() // RA=1, RCODE=0
            response[5] = 1 // QDCOUNT
            response[7] = 1 // ANCOUNT
            return response
        }

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
        // Generous timeout: these are Robolectric tests running on shared CI/agent machines where
        // CPU contention can delay coroutine dispatch by several seconds (observed flakes at 10s).
        private const val FAIL_TIMEOUT_MS = 30_000L
    }
}
