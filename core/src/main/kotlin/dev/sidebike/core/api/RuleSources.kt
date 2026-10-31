package dev.sidebike.core.api

/**
 * 一次规则源解析/导入的结果。合并策略由调用方（App Controller）决定并且必须保持幂等：
 *  - [intercepts]：按 `domainPattern -> pathRegex -> action` 覆盖合并到 `SidebikeConfig.intercepts`
 *  - [directPassDomains]：写入 `whiteList[domain] = true`（直连放行）
 *  - [domesticDomains]：追加到 `domesticAllowList`（去重）
 *  - [preSetIpList]：按域名合并预置 IP
 */
data class ImportedRuleSet(
    val intercepts: Map<String, Map<String, InterceptAction>> = emptyMap(),
    val directPassDomains: List<String> = emptyList(),
    val domesticDomains: List<String> = emptyList(),
    val preSetIpList: Map<String, Map<String, Boolean>> = emptyMap(),
    val warnings: List<String> = emptyList(),
) {
    val ruleCount: Int
        get() = intercepts.values.sumOf { it.size } + directPassDomains.size + domesticDomains.size

    companion object {
        val EMPTY = ImportedRuleSet()
    }
}

/** 单个规则源刷新结果（无网络访问失败仍返回，由调用方展示 lastError）。 */
data class RuleSourceRefreshResult(
    val source: RuleSource,
    /** 未修改（304）或禁用时为 null。 */
    val imported: ImportedRuleSet? = null,
    val notModified: Boolean = false,
    val error: String? = null,
)

/**
 * 远程规则源管理器（纯 Kotlin/JVM，Android 兼容）：
 * 拉取 → 解析 → 返回 [ImportedRuleSet]，不自行持久化配置（由 App Controller 合并保存）。
 *
 * 实现要求：
 *  - 仅支持 http/https；https 证书校验使用系统默认信任库（不使用明文/不安全信任）；
 *  - 支持 ETag / Last-Modified 条件请求；超时与响应体大小上限可配置；
 *  - 解析容错：坏行跳过并记录 warning，绝不抛出未捕获异常；
 *  - 格式自动识别规则见 [RuleSourceFormat]。
 */
interface RuleSourceManager {
    suspend fun refresh(source: RuleSource, force: Boolean = false): RuleSourceRefreshResult

    suspend fun refreshAll(sources: List<RuleSource>, force: Boolean = false): List<RuleSourceRefreshResult>

    /** 解析内存中的文本（用于"从剪贴板/文件导入"与单元测试）；[source.url] 可为空。 */
    fun parse(
        content: String,
        format: RuleSourceFormat = RuleSourceFormat.AUTO,
        name: String = "",
        url: String = "",
    ): ImportedRuleSet
}

/**
 * RuleSourceManager 工厂：实现类（core/rules/DefaultRuleSourceManager）通过 [install] 注册；
 * App 启动时由 Controller 调用 `install()` 后再 [create]（与 SidebikeEngineFactory 相同模式）。
 */
object RuleSourceManagerFactory {
    @Volatile
    private var creator: (() -> RuleSourceManager)? = null

    fun install(factory: () -> RuleSourceManager) {
        creator = factory
    }

    fun create(): RuleSourceManager =
        creator?.invoke() ?: error("RuleSourceManager 未安装实现（core/rules/DefaultRuleSourceManager 未注册）")
}
