package dev.sidebike.app.controller

import android.content.Context
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.SidebikeConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * App 内唯一的状态入口。实现见 `DefaultSidebikeController`（android-service workstream）。
 * UI 只通过本接口读取状态/触发动作；不得直接触碰 VpnService/Engine。
 */
interface SidebikeController {
    val state: StateFlow<SidebikeUiState>
    val logs: StateFlow<List<LogEntry>>

    /** 用户请求启动（调用前 UI 必须已完成 VpnService.prepare 授权）。幂等。 */
    fun start()

    /** 用户请求停止。幂等。 */
    fun stop()

    /** 中止启动流程（如用户拒绝 VPN 授权）。 */
    fun onStartAborted(reason: String)

    /** 保存配置并热更新引擎。 */
    suspend fun updateConfig(config: SidebikeConfig)

    suspend fun reloadConfig()

    fun clearLogs()

    /** CA 证书 PEM（供安装引导页导出/安装）；未启用 MITM 或未生成时为 null。 */
    suspend fun caCertificatePem(): String?

    /** 重新检测 CA 是否已安装（用户从系统设置返回后调用）。 */
    fun refreshCaInstalled()

    /** 导出诊断信息（版本、设备、配置、最近日志）为纯文本。 */
    suspend fun exportDiagnostics(): String

    companion object {
        fun get(context: Context): SidebikeController = SidebikeControllerHolder.get(context)
    }
}

/** 由 DefaultSidebikeController 注册的真实实现；未注册时返回占位实现以免 UI 崩溃。 */
object SidebikeControllerHolder {
    @Volatile
    private var factory: ((Context) -> SidebikeController)? = null

    fun install(factory: (Context) -> SidebikeController) {
        this.factory = factory
    }

    fun get(context: Context): SidebikeController {
        val appContext = context.applicationContext
        return factory?.invoke(appContext) ?: PlaceholderSidebikeController()
    }
}
