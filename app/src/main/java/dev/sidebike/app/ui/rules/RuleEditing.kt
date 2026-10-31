package dev.sidebike.app.ui.rules

import dev.sidebike.app.controller.RuleDraft
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.SidebikeConfig

/**
 * 规则/域名编辑的纯函数（无 Compose 依赖，Robolectric 外也可单测）。
 * 契约兼容：`intercepts` 为 domain -> path -> action；直连放行 = `whiteList[domain] = true`；
 * 国内域名 = `domesticAllowList`；禁用内置规则 = `disabledIntercepts`（"domain\tpath"）。
 */

/** 编辑对话框里的动作类型（契约 RuleDraft 只有 sni/proxy/desc，abort 走配置合并路径）。 */
enum class RuleActionType { SNI, PROXY, ABORT }

/** 域名 pattern 的轻量校验（不做 DNS 语义校验，避免误伤正则/通配符）。 */
fun isPlausibleDomainPattern(pattern: String): Boolean {
    val value = pattern.trim()
    if (value.isEmpty() || value.length > 253) return false
    if (value.any { it.isWhitespace() }) return false
    return value.contains('.') || value.startsWith("*") || value.startsWith("^")
}

/** 路径正则校验：空 = 使用默认 .*。 */
fun isPlausiblePathRegex(regex: String): Boolean {
    val value = regex.trim()
    if (value.isEmpty()) return true
    return runCatching { Regex(value) }.isSuccess
}

fun normalizeRuleDraft(draft: RuleDraft): RuleDraft = draft.copy(
    domainPattern = draft.domainPattern.trim(),
    pathRegex = draft.pathRegex.trim().ifEmpty { ".*" },
    sni = draft.sni?.trim()?.takeIf { it.isNotEmpty() },
    proxy = draft.proxy?.trim()?.takeIf { it.isNotEmpty() },
    desc = draft.desc?.trim()?.takeIf { it.isNotEmpty() },
)

/** 直连放行域名（whiteList == true），排序稳定便于列表与 diff。 */
fun directPassDomains(config: SidebikeConfig): List<String> =
    config.whiteList.filterValues { it == true }.keys.sorted()

/** 国内域名（去重 + 排序，列表可能由规则源导入 1000+ 条）。 */
fun domesticDomains(config: SidebikeConfig): List<String> =
    config.domesticAllowList.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().toList()

/** 通用域名搜索（大小写不敏感；空关键字返回全部）。 */
fun filterDomainsByQuery(domains: List<String>, query: String): List<String> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return domains
    return domains.filter { it.contains(keyword, ignoreCase = true) }
}

/** 规则搜索：域名 / 路径 / 动作备注。 */
fun filterInterceptEntries(
    entries: List<Pair<String, Map<String, InterceptAction>>>,
    query: String,
): List<Pair<String, Map<String, InterceptAction>>> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return entries
    return entries.filter { (domain, actions) ->
        domain.contains(keyword, ignoreCase = true) ||
            actions.entries.any { (path, action) ->
                path.contains(keyword, ignoreCase = true) ||
                    action.sni?.contains(keyword, true) == true ||
                    action.proxy?.contains(keyword, true) == true ||
                    action.desc?.contains(keyword, true) == true
            }
    }
}

/**
 * 把编辑草稿写入配置（ABORT 动作使用；SNI/PROXY 走 controller.saveRule）。
 * 同时移除 disabledIntercepts 中对应的禁用标记（编辑即恢复该内置规则）。
 */
fun SidebikeConfig.withRule(draft: RuleDraft, actionType: RuleActionType): SidebikeConfig {
    val normalized = normalizeRuleDraft(draft)
    val action = when (actionType) {
        RuleActionType.ABORT -> InterceptAction(abort = true, desc = normalized.desc)
        RuleActionType.SNI -> InterceptAction(sni = normalized.sni, desc = normalized.desc)
        RuleActionType.PROXY -> InterceptAction(proxy = normalized.proxy, desc = normalized.desc)
    }
    val existingPaths = intercepts[normalized.domainPattern].orEmpty()
    val updatedDomain = existingPaths + (normalized.pathRegex to action)
    val nextIntercepts = intercepts + (normalized.domainPattern to updatedDomain)
    val key = "${normalized.domainPattern}\t${normalized.pathRegex}"
    return copy(
        intercepts = nextIntercepts,
        disabledIntercepts = disabledIntercepts.filterNot { it == key },
    )
}

/** 从配置中删除一条规则（用于 UI 侧预检/离线场景；实际删除优先走 controller.deleteRule）。 */
fun SidebikeConfig.withoutRule(domainPattern: String, pathRegex: String): SidebikeConfig {
    val paths = intercepts[domainPattern] ?: return this
    val nextPaths = paths - pathRegex
    val nextIntercepts = if (nextPaths.isEmpty()) intercepts - domainPattern else intercepts + (domainPattern to nextPaths)
    val key = "$domainPattern\t$pathRegex"
    return copy(
        intercepts = nextIntercepts,
        disabledIntercepts = (disabledIntercepts + key).distinct(),
    )
}

/** 设置直连放行列表：保留其它 whiteList=false 项，仅重写 true 项。 */
fun SidebikeConfig.withDirectPassDomains(domains: List<String>): SidebikeConfig {
    val normalized = domains.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val kept = whiteList.filterValues { it != true }
    return copy(whiteList = kept + normalized.associateWith { true })
}

/** 设置国内域名列表（空 = 不启用国内直连）。 */
fun SidebikeConfig.withDomesticDomains(domains: List<String>): SidebikeConfig =
    copy(domesticAllowList = domains.map { it.trim() }.filter { it.isNotEmpty() }.distinct())

/** 规则源格式的中文标签资源 id（供 UI 与测试共用）。 */
fun sourceFormatLabelRes(format: dev.sidebike.core.api.RuleSourceFormat): Int = when (format) {
    dev.sidebike.core.api.RuleSourceFormat.AUTO -> dev.sidebike.app.R.string.source_format_auto
    dev.sidebike.core.api.RuleSourceFormat.SIDEBIKE_JSON -> dev.sidebike.app.R.string.source_format_json
    dev.sidebike.core.api.RuleSourceFormat.SHADOWROCKET_LIST -> dev.sidebike.app.R.string.source_format_shadowrocket
    dev.sidebike.core.api.RuleSourceFormat.DOMAIN_LIST -> dev.sidebike.app.R.string.source_format_domain_list
}
