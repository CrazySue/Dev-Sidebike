package dev.sidebike.app.ui.screens

import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.SidebikeConfig

/**
 * 规则页的展示模型（纯 Kotlin，可被 JVM 单测直接覆盖，不依赖 Compose）。
 */

/** 规则动作类型（用于列表摘要 chips）。 */
enum class RuleActionKind { SNI, PROXY, SYNTHETIC, SCRIPT, STATUS, CACHE, ABORT }

/** 汇总一组路径规则里出现的动作类型（去重、稳定顺序）。 */
fun ruleActionKinds(actions: Map<String, InterceptAction>): List<RuleActionKind> {
    val kinds = mutableSetOf<RuleActionKind>()
    actions.values.forEach { action ->
        action.sni?.let { kinds += RuleActionKind.SNI }
        action.proxy?.let { kinds += RuleActionKind.PROXY }
        action.success?.let { kinds += RuleActionKind.SYNTHETIC }
        action.script?.let { kinds += RuleActionKind.SCRIPT }
        action.status?.let { kinds += RuleActionKind.STATUS }
        action.cacheDays?.let { kinds += RuleActionKind.CACHE }
        if (action.abort == true) kinds += RuleActionKind.ABORT
    }
    return kinds.toList()
}

/** 域名的"直连放行"状态：whiteList[domain] == true 表示不参与代理/MITM。 */
fun SidebikeConfig.isDomainIntercepted(domain: String): Boolean = whiteList[domain] != true

/** 切换某个域名的"直连放行"（历史纯函数；新 UI 走 controller.setDirectPassDomains）。 */
fun SidebikeConfig.withDomainIntercepted(domain: String, intercepted: Boolean): SidebikeConfig =
    copy(whiteList = if (intercepted) whiteList - domain else whiteList + (domain to true))

/** 动作详情字段（UI 层映射到中文资源）。 */
enum class InterceptDetailLabel {
    SNI,
    PROXY,
    SUCCESS,
    SCRIPT,
    STATUS,
    CACHE_DAYS,
    REQUEST_REPLACE,
    RESPONSE_REPLACE,
    DESC,
    REMARK,
    ABORT,
}

/** 显示用：动作详情行（字段 -> 值）；空值不展示。 */
fun interceptActionDetails(action: InterceptAction): List<Pair<InterceptDetailLabel, String?>> = buildList {
    action.sni?.let { add(InterceptDetailLabel.SNI to it) }
    action.proxy?.let { add(InterceptDetailLabel.PROXY to it) }
    action.success?.let { add(InterceptDetailLabel.SUCCESS to "true") }
    action.script?.let { add(InterceptDetailLabel.SCRIPT to it.take(80)) }
    action.status?.let { add(InterceptDetailLabel.STATUS to it.toString()) }
    action.cacheDays?.let { add(InterceptDetailLabel.CACHE_DAYS to it.toString()) }
    action.requestReplace?.let { add(InterceptDetailLabel.REQUEST_REPLACE to null) }
    action.responseReplace?.let { add(InterceptDetailLabel.RESPONSE_REPLACE to null) }
    if (action.abort == true) add(InterceptDetailLabel.ABORT to null)
    action.desc?.let { add(InterceptDetailLabel.DESC to it) }
    action.remark?.let { add(InterceptDetailLabel.REMARK to it) }
}

/** 单条规则草稿 -> 动作类型（编辑时回填 UI）。 */
fun actionTypeOf(action: InterceptAction): dev.sidebike.app.ui.rules.RuleActionType = when {
    action.abort == true -> dev.sidebike.app.ui.rules.RuleActionType.ABORT
    action.sni != null -> dev.sidebike.app.ui.rules.RuleActionType.SNI
    else -> dev.sidebike.app.ui.rules.RuleActionType.PROXY
}
