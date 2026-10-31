package dev.sidebike.app.ui

import android.content.Intent
import dev.sidebike.app.controller.CaInstallRequest
import dev.sidebike.app.controller.MitmToggleResult
import dev.sidebike.app.controller.RuleDraft
import dev.sidebike.app.controller.RuleImportSummary
import dev.sidebike.app.controller.RuleSourceRefreshSummary
import dev.sidebike.core.api.RuleSourceFormat
import dev.sidebike.core.api.SidebikeConfig

/** CA 安装启动结果（UI 用来展示准确文案，而不是笼统的"未安装"）。 */
enum class CaInstallLaunchStatus { LAUNCHED, FALLBACK_LAUNCHED, LOCK_SCREEN_REQUIRED, NO_CA, FAILED }

data class CaInstallUiResult(
    val status: CaInstallLaunchStatus,
    val fingerprint: String? = null,
    val subject: String? = null,
    val message: String? = null,
)

/**
 * UI -> Controller 的集中动作入口（由 [SidebikeApp] 用 ViewModel + Android 系统能力构造）。
 * 屏幕组件只依赖该对象，便于 Robolectric 测试注入 fake 动作、也避免几十个回调参数散落。
 */
class SidebikeActions(
    val requestStart: () -> Unit = {},
    val stop: () -> Unit = {},
    val updateConfig: (SidebikeConfig) -> Unit = {},
    val reloadConfig: () -> Unit = {},
    val clearLogs: () -> Unit = {},
    /** 重新检测 CA 是否已安装（从系统安装器/设置返回后）。 */
    val refreshCaInstalled: () -> Unit = {},
    /** MITM 开关：CA 未安装时 Controller 会拒绝并返回 message（UI 需提示）。 */
    val setMitmEnabled: suspend (Boolean) -> MitmToggleResult = { MitmToggleResult(it) },
    /** 生成 CA 并启动系统安装器（失败时回退系统证书设置页）。 */
    val installCa: suspend () -> CaInstallUiResult = { CaInstallUiResult(CaInstallLaunchStatus.NO_CA) },
    /** 导出 CA PEM 到用户选择的文件（SAF）。 */
    val exportCaToFile: () -> Unit = {},
    /** 打开系统"加密与凭据"设置页（安装器未弹出时的兜底）。 */
    val openCaSettings: () -> Unit = {},
    /** 打开系统安全设置（引导用户先设置锁屏密码/图案，系统证书安装器要求设备已设锁屏）。 */
    val openLockScreenSettings: () -> Unit = {},
    /** 导出日志 .log 文件（SAF）。 */
    val exportLogFile: () -> Unit = {},
    /** 导出诊断包 .zip（SAF）。 */
    val exportDebugBundle: () -> Unit = {},
    /** 复制日志文本到剪贴板（无 SAF 时的兜底）。 */
    val copyLogText: () -> Unit = {},
    /** 复制任意文本到剪贴板（CA 指纹、诊断报告等）。 */
    val copyText: (String) -> Unit = {},
    /** 保存任意文本到用户选择的文件（诊断报告等）。 */
    val saveTextFile: (suggestedName: String, content: String) -> Unit = { _, _ -> },
    val runNetworkDiagnostics: suspend () -> String = { "" },
    val saveRule: suspend (RuleDraft) -> Unit = {},
    val deleteRule: suspend (domainPattern: String, pathRegex: String) -> Unit = { _, _ -> },
    val setDirectPassDomains: suspend (List<String>) -> Unit = {},
    val setDomesticAllowList: suspend (List<String>) -> Unit = {},
    val importRulesFromText: suspend (text: String, format: RuleSourceFormat, sourceName: String) -> RuleImportSummary =
        { _, _, _ -> RuleImportSummary() },
    val refreshRuleSources: suspend (force: Boolean) -> RuleSourceRefreshSummary = { RuleSourceRefreshSummary() },
    /** 启动任意系统 Intent（返回是否成功，供证书安装 fallback 判断）。 */
    val launchIntent: (Intent) -> Boolean = { false },
    /** 构造 CA 安装请求（不启动 Activity，仅取 PEM/指纹用于展示）。 */
    val prepareCaInstall: suspend () -> CaInstallRequest? = { null },
)
