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
    /** 系统是否已注册 VPN 下发的 HTTP 代理（API29+；null=未知/API<29 不支持）。 */
    val systemProxyRegistered: Boolean? = null,
    /** 是否检测到系统 Private DNS（DoT）开启：会绕过 VPN DNS 拦截，需提示用户关闭。 */
    val privateDnsActive: Boolean = false,
    /** 是否开启"始终开启 VPN + 阻止无 VPN 连接"（lockdown）：与 split-tunnel 冲突。 */
    val lockdownDetected: Boolean = false,
    /** 实际本地代理端口（31181 被占用时会回退到高位可用端口）。 */
    val effectiveProxyPort: Int = 31181,
) {
    val isBusy: Boolean get() = status == RunStatus.PREPARING || status == RunStatus.STARTING || status == RunStatus.STOPPING
}
