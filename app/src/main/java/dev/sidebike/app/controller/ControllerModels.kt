package dev.sidebike.app.controller

import android.content.Intent
import dev.sidebike.core.api.InterceptAction

/** [SidebikeController.prepareCaInstall] 的结果：包含可直接启动的系统安装 Intent。 */
data class CaInstallRequest(
    val intent: Intent,
    /** PEM 文本（用于导出/复制，与 Intent 中的 DER 对应）。 */
    val pem: String,
    /** 证书指纹（SHA-256，冒号分隔），用于 UI 展示与用户核对。 */
    val sha256Fingerprint: String,
    /** 证书 subject DN，例如 CN=Dev-Sidebike CA。 */
    val subject: String,
    /** 系统安装器未弹出时的备用入口（打开系统"加密与凭据"设置页）。 */
    val settingsFallbackIntent: Intent,
)

/** MITM 开关结果：effective=false 表示因 CA 未安装而未启用，message 用于 UI 提示。 */
data class MitmToggleResult(
    val effective: Boolean,
    val message: String? = null,
)

/** 规则导入摘要。 */
data class RuleImportSummary(
    val addedIntercepts: Int = 0,
    val addedDirectPassDomains: Int = 0,
    val addedDomesticDomains: Int = 0,
    val warnings: List<String> = emptyList(),
)

/** 远程规则源刷新摘要。 */
data class RuleSourceRefreshSummary(
    val updatedSources: List<String> = emptyList(),
    val failedSources: Map<String, String> = emptyMap(),
    val notModifiedSources: List<String> = emptyList(),
    val importedRules: Int = 0,
) {
    val hasChanges: Boolean get() = updatedSources.isNotEmpty()
}

/** 单条规则的稳定 key（domainPattern + pathRegex），用于"禁用内置规则"持久化。 */
fun ruleKey(domainPattern: String, pathRegex: String): String = "$domainPattern\t$pathRegex"

/** 单条规则的编辑草稿（UI → Controller）。 */
data class RuleDraft(
    val domainPattern: String,
    val pathRegex: String = ".*",
    val sni: String? = null,
    val proxy: String? = null,
    val desc: String? = null,
) {
    fun toAction(): InterceptAction = InterceptAction(
        sni = sni?.trim()?.takeIf { it.isNotEmpty() },
        proxy = proxy?.trim()?.takeIf { it.isNotEmpty() },
        desc = desc?.trim()?.takeIf { it.isNotEmpty() },
    )
}
