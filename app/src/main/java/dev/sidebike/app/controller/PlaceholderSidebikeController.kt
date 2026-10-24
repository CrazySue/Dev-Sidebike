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
}
