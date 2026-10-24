package dev.sidebike.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.sidebike.app.controller.SidebikeController
import dev.sidebike.app.controller.SidebikeControllerHolder
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.core.api.LogEntry
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
}
