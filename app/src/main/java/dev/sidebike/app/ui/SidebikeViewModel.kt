package dev.sidebike.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.sidebike.app.controller.CaInstallRequest
import dev.sidebike.app.controller.MitmToggleResult
import dev.sidebike.app.controller.RuleDraft
import dev.sidebike.app.controller.RuleImportSummary
import dev.sidebike.app.controller.RuleSourceRefreshSummary
import dev.sidebike.app.controller.SidebikeController
import dev.sidebike.app.controller.SidebikeControllerHolder
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.RuleSourceFormat
import dev.sidebike.core.api.SidebikeConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * UI 状态持有者：只做「转发」——把 [SidebikeController] 的 StateFlow 暴露给 Compose，
 * 并把用户动作转发回 Controller。UI 不持有任何业务状态，也不直接接触 VpnService/Engine。
 *
 * 通过 [SidebikeControllerHolder] 取实现：真实运行时由 `DefaultSidebikeController` 注册；
 * 测试 / Preview 用 fake 控制器通过 `SidebikeControllerHolder.install` 注入（不改 controller 代码）。
 */
class SidebikeViewModel(application: Application) : AndroidViewModel(application) {

    private val controller: SidebikeController = SidebikeControllerHolder.get(application)

    val state: StateFlow<SidebikeUiState> = controller.state
    val logs: StateFlow<List<LogEntry>> = controller.logs

    fun start() = controller.start()

    fun stop() = controller.stop()

    fun onStartAborted(reason: String) = controller.onStartAborted(reason)

    suspend fun updateConfig(config: SidebikeConfig) = controller.updateConfig(config)

    suspend fun reloadConfig() = controller.reloadConfig()

    fun clearLogs() = controller.clearLogs()

    suspend fun caCertificatePem(): String? = controller.caCertificatePem()

    fun refreshCaInstalled() = controller.refreshCaInstalled()

    suspend fun exportDiagnostics(): String = controller.exportDiagnostics()

    // ---------------------------------------------------------------- 0.1.1-alpha
    suspend fun buildLogText(): String = controller.buildLogText()

    suspend fun buildDebugBundle(): ByteArray = controller.buildDebugBundle()

    suspend fun runNetworkDiagnostics(): String = controller.runNetworkDiagnostics()

    suspend fun prepareCaInstall(): CaInstallRequest? = controller.prepareCaInstall()

    suspend fun setMitmEnabled(enabled: Boolean): MitmToggleResult = controller.setMitmEnabled(enabled)

    suspend fun saveRule(draft: RuleDraft) = controller.saveRule(draft)

    suspend fun deleteRule(domainPattern: String, pathRegex: String) =
        controller.deleteRule(domainPattern, pathRegex)

    suspend fun setDirectPassDomains(domains: List<String>) = controller.setDirectPassDomains(domains)

    suspend fun setDomesticAllowList(domains: List<String>) = controller.setDomesticAllowList(domains)

    suspend fun importRulesFromText(
        text: String,
        format: RuleSourceFormat,
        sourceName: String = "本地导入",
    ): RuleImportSummary = controller.importRulesFromText(text, format, sourceName)

    suspend fun refreshRuleSources(force: Boolean = false): RuleSourceRefreshSummary =
        controller.refreshRuleSources(force)
}
