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

    // ---------------------------------------------------------------- 日志导出（0.1.1-alpha）
    /** 可读日志文本（.log/.txt），包含诊断信息 + 控制台/引擎日志。 */
    suspend fun buildLogText(): String

    /** 调试包（.zip）：诊断信息、日志、配置、设备与环境信息，供用户发给开发者定位问题。 */
    suspend fun buildDebugBundle(): ByteArray

    // ---------------------------------------------------------------- 网络诊断（0.1.1-alpha）
    /** 运行一次网络诊断（DNS Provider 健康、DNS 解析、直连/TLS、系统代理路径），返回可读报告。 */
    suspend fun runNetworkDiagnostics(): String

    // ---------------------------------------------------------------- CA / MITM（0.1.1-alpha）
    /** 生成（如未生成）CA 并返回系统安装所需数据；不依赖 MITM 开关。 */
    suspend fun prepareCaInstall(): CaInstallRequest?

    /** MITM 开关：CA 未安装时拒绝启用（返回实际生效值 + 提示）。 */
    suspend fun setMitmEnabled(enabled: Boolean): MitmToggleResult

    // ---------------------------------------------------------------- 规则编辑 / 导入（0.1.1-alpha）
    /** 新增或覆盖一条拦截规则。 */
    suspend fun saveRule(draft: RuleDraft)

    /** 删除一条规则（内置规则会写入 disabledIntercepts，避免下次启动还原）。 */
    suspend fun deleteRule(domainPattern: String, pathRegex: String)

    /** 直连放行域名列表（写 whiteList[domain] = true）。 */
    suspend fun setDirectPassDomains(domains: List<String>)

    /** 国内域名直连列表（可编辑；传空列表表示"不启用国内直连"）。 */
    suspend fun setDomesticAllowList(domains: List<String>)

    /** 从文本导入规则（JSON / Shadowrocket .list / 域名列表）。 */
    suspend fun importRulesFromText(
        text: String,
        format: dev.sidebike.core.api.RuleSourceFormat,
        sourceName: String = "本地导入",
    ): RuleImportSummary

    /** 刷新全部远程规则源（force=true 忽略间隔/ETag）。 */
    suspend fun refreshRuleSources(force: Boolean = false): RuleSourceRefreshSummary

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
