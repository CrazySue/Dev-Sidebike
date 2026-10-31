package dev.sidebike.app.ui

import dev.sidebike.app.controller.CaInstallRequest
import dev.sidebike.app.controller.MitmToggleResult
import dev.sidebike.app.controller.RuleDraft
import dev.sidebike.app.controller.RuleImportSummary
import dev.sidebike.app.controller.RuleSourceRefreshSummary
import dev.sidebike.app.controller.SidebikeController
import dev.sidebike.app.controller.SidebikeControllerHolder
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.ui.preview.PreviewData
import dev.sidebike.core.api.ImportedRuleSet
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.RuleSourceFormat
import dev.sidebike.core.api.SidebikeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 测试用 SidebikeController：记录所有 UI 触发的动作，可注入任意 state/logs。
 * 通过 [SidebikeControllerHolder.install] 注入，不修改真实 controller 代码。
 */
class FakeSidebikeController(
    initialState: SidebikeUiState = PreviewData.state(),
    initialLogs: List<LogEntry> = PreviewData.logs,
) : SidebikeController {

    private val stateFlow = MutableStateFlow(initialState)
    private val logsFlow = MutableStateFlow(initialLogs)

    override val state: StateFlow<SidebikeUiState> = stateFlow.asStateFlow()
    override val logs: StateFlow<List<LogEntry>> = logsFlow.asStateFlow()

    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var abortedReason: String? = null
        private set
    var updateConfigCalls: Int = 0
        private set
    var lastConfig: SidebikeConfig? = null
        private set
    var reloadConfigCalls: Int = 0
        private set
    var clearLogsCalls: Int = 0
        private set
    var caPemRequests: Int = 0
        private set
    var refreshCaCalls: Int = 0
        private set
    var exportCalls: Int = 0
        private set
    var buildLogTextCalls: Int = 0
        private set
    var buildDebugBundleCalls: Int = 0
        private set
    var diagnosticsCalls: Int = 0
        private set
    var prepareCaInstallCalls: Int = 0
        private set
    var setMitmEnabledCalls: Int = 0
        private set
    var saveRuleCalls: Int = 0
        private set
    var deleteRuleCalls: Int = 0
        private set
    var setDirectPassDomainsCalls: Int = 0
        private set
    var setDomesticAllowListCalls: Int = 0
        private set
    var importRulesCalls: Int = 0
        private set
    var refreshRuleSourcesCalls: Int = 0
        private set

    /** 注入值。 */
    var caPem: String? = null
    var caInstallRequest: CaInstallRequest? = null
    var mitmToggleResult: MitmToggleResult? = null
    var diagnosticsReport: String = "DNS Provider 健康：aliyun OK；解析 github.com -> 1.2.3.4"
    var logText: String = "Dev-Sidebike 日志\nline-1\nline-2"
    var debugBundle: ByteArray = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    var importSummary: RuleImportSummary = RuleImportSummary(addedIntercepts = 3)
    var refreshSummary: RuleSourceRefreshSummary = RuleSourceRefreshSummary(importedRules = 5)

    var lastRuleDraft: RuleDraft? = null
    var lastDeletedRule: Pair<String, String>? = null
    var lastDirectPassDomains: List<String>? = null
    var lastDomesticAllowList: List<String>? = null
    var lastImportedText: String? = null
    var lastImportFormat: RuleSourceFormat? = null

    fun setState(newState: SidebikeUiState) {
        stateFlow.value = newState
    }

    fun setLogs(newLogs: List<LogEntry>) {
        logsFlow.value = newLogs
    }

    /** 直接改配置（模拟 controller 持久化后的 state 回推）。 */
    fun setConfig(config: SidebikeConfig) {
        stateFlow.value = stateFlow.value.copy(config = config)
    }

    override fun start() {
        startCalls++
    }

    override fun stop() {
        stopCalls++
    }

    override fun onStartAborted(reason: String) {
        abortedReason = reason
    }

    override suspend fun updateConfig(config: SidebikeConfig) {
        updateConfigCalls++
        lastConfig = config
        stateFlow.value = stateFlow.value.copy(config = config)
    }

    override suspend fun reloadConfig() {
        reloadConfigCalls++
    }

    override fun clearLogs() {
        clearLogsCalls++
        logsFlow.value = emptyList()
    }

    override suspend fun caCertificatePem(): String? {
        caPemRequests++
        return caPem
    }

    override fun refreshCaInstalled() {
        refreshCaCalls++
    }

    override suspend fun exportDiagnostics(): String {
        exportCalls++
        return "Dev-Sidebike 诊断信息\nversion=test\ndevice=robolectric"
    }

    override suspend fun buildLogText(): String {
        buildLogTextCalls++
        return logText
    }

    override suspend fun buildDebugBundle(): ByteArray {
        buildDebugBundleCalls++
        return debugBundle
    }

    override suspend fun runNetworkDiagnostics(): String {
        diagnosticsCalls++
        return diagnosticsReport
    }

    override suspend fun prepareCaInstall(): CaInstallRequest? {
        prepareCaInstallCalls++
        return caInstallRequest
    }

    override suspend fun setMitmEnabled(enabled: Boolean): MitmToggleResult {
        setMitmEnabledCalls++
        return mitmToggleResult ?: MitmToggleResult(effective = enabled)
    }

    override suspend fun saveRule(draft: RuleDraft) {
        saveRuleCalls++
        lastRuleDraft = draft
    }

    override suspend fun deleteRule(domainPattern: String, pathRegex: String) {
        deleteRuleCalls++
        lastDeletedRule = domainPattern to pathRegex
    }

    override suspend fun setDirectPassDomains(domains: List<String>) {
        setDirectPassDomainsCalls++
        lastDirectPassDomains = domains
    }

    override suspend fun setDomesticAllowList(domains: List<String>) {
        setDomesticAllowListCalls++
        lastDomesticAllowList = domains
    }

    override suspend fun importRulesFromText(
        text: String,
        format: RuleSourceFormat,
        sourceName: String,
    ): RuleImportSummary {
        importRulesCalls++
        lastImportedText = text
        lastImportFormat = format
        return importSummary
    }

    override suspend fun refreshRuleSources(force: Boolean): RuleSourceRefreshSummary {
        refreshRuleSourcesCalls++
        return refreshSummary
    }

    /** 便于测试：直接返回一个 RuleImportSummary 的构造器引用，保持 imported 语义可见。 */
    fun importedRuleSet(): ImportedRuleSet = ImportedRuleSet.EMPTY
}

/** 安装 fake 控制器。 */
fun installFakeController(fake: FakeSidebikeController) {
    SidebikeControllerHolder.install { fake }
}
