package dev.sidebike.app.ui

import dev.sidebike.app.controller.SidebikeController
import dev.sidebike.app.controller.SidebikeControllerHolder
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.ui.preview.PreviewData
import dev.sidebike.core.api.LogEntry
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

    /** 测试可注入的 CA PEM；null 表示尚未生成。 */
    var caPem: String? = null

    fun setState(newState: SidebikeUiState) {
        stateFlow.value = newState
    }

    fun setLogs(newLogs: List<LogEntry>) {
        logsFlow.value = newLogs
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
}

/** 安装 fake 控制器并返回一个新的 ViewModel（Robolectric 环境）。 */
fun installFakeController(fake: FakeSidebikeController) {
    SidebikeControllerHolder.install { fake }
}
