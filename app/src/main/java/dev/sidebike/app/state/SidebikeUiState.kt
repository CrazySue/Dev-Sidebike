package dev.sidebike.app.state

import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.TrafficStats

/** VPN/引擎生命周期状态。UI 必须为每个状态提供独立的视觉反馈。 */
enum class RunStatus {
    IDLE,
    PREPARING,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

data class SidebikeUiState(
    val status: RunStatus = RunStatus.IDLE,
    val statusMessage: String = "",
    val vpnActive: Boolean = false,
    val proxyRunning: Boolean = false,
    val mitmActive: Boolean = false,
    val caInstalled: Boolean = false,
    val config: SidebikeConfig = SidebikeConfig(),
    val stats: TrafficStats = TrafficStats(),
    val recentLogs: List<LogEntry> = emptyList(),
    val lastError: String? = null,
    val uptimeMs: Long = 0,
) {
    val isBusy: Boolean get() = status == RunStatus.PREPARING || status == RunStatus.STARTING || status == RunStatus.STOPPING
}
