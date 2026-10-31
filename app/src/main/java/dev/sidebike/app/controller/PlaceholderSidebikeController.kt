package dev.sidebike.app.controller

import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.TrafficStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fallback used only before the real controller is installed (e.g. Compose previews). */
internal class PlaceholderSidebikeController : SidebikeController {
    private val placeholderState = SidebikeUiState(
        status = RunStatus.IDLE,
        statusMessage = "controller 尚未初始化",
        stats = TrafficStats(),
    )
    private val stateFlow = MutableStateFlow(placeholderState)
    private val logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())

    override val state: StateFlow<SidebikeUiState> = stateFlow.asStateFlow()
    override val logs: StateFlow<List<LogEntry>> = logsFlow.asStateFlow()

    override fun start() = Unit
    override fun stop() = Unit
    override fun onStartAborted(reason: String) = Unit
    override suspend fun updateConfig(config: SidebikeConfig) = Unit
    override suspend fun reloadConfig() = Unit
    override fun clearLogs() {
        logsFlow.value = emptyList()
    }

    override suspend fun caCertificatePem(): String? = null
    override fun refreshCaInstalled() = Unit
    override suspend fun exportDiagnostics(): String = "Dev-Sidebike placeholder controller (not installed)"

    override suspend fun buildLogText(): String = "Dev-Sidebike placeholder controller (not installed)"
    override suspend fun buildDebugBundle(): ByteArray = ByteArray(0)
    override suspend fun runNetworkDiagnostics(): String = "controller 尚未初始化，无法进行网络诊断"
    override suspend fun prepareCaInstall(): CaInstallRequest? = null
    override suspend fun setMitmEnabled(enabled: Boolean): MitmToggleResult =
        MitmToggleResult(effective = false, message = "controller 尚未初始化")

    override suspend fun saveRule(draft: RuleDraft) = Unit
    override suspend fun deleteRule(domainPattern: String, pathRegex: String) = Unit
    override suspend fun setDirectPassDomains(domains: List<String>) = Unit
    override suspend fun setDomesticAllowList(domains: List<String>) = Unit
    override suspend fun importRulesFromText(
        text: String,
        format: dev.sidebike.core.api.RuleSourceFormat,
        sourceName: String,
    ): RuleImportSummary = RuleImportSummary(warnings = listOf("controller 尚未初始化"))

    override suspend fun refreshRuleSources(force: Boolean): RuleSourceRefreshSummary =
        RuleSourceRefreshSummary(failedSources = mapOf("controller" to "尚未初始化"))
}
