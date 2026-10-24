@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.sidebike.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sidebike.app.R
import dev.sidebike.app.ui.components.EmptyState
import dev.sidebike.app.ui.components.SectionHeader
import dev.sidebike.app.ui.components.StatusPill
import dev.sidebike.app.ui.motion.M3ListItemFadeInSpec
import dev.sidebike.app.ui.motion.M3ListItemFadeOutSpec
import dev.sidebike.app.ui.motion.M3ListItemPlacementSpec
import dev.sidebike.app.ui.motion.M3Motion
import dev.sidebike.app.ui.motion.m3ExpandIn
import dev.sidebike.app.ui.motion.m3ShrinkOut
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.SidebikeConfig

/** 规则动作类型（用于列表摘要 chips，纯 Kotlin，可单测）。 */
enum class RuleActionKind { SNI, PROXY, SYNTHETIC, SCRIPT, STATUS, CACHE }

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
    }
    return kinds.toList()
}

/** 域名的"直连放行"状态：whiteList[domain] == true 表示不参与代理/MITM。 */
fun SidebikeConfig.isDomainIntercepted(domain: String): Boolean = whiteList[domain] != true

/** 切换某个域名的"直连放行"（写 whiteList，不修改只读的 intercepts 规则）。 */
fun SidebikeConfig.withDomainIntercepted(domain: String, intercepted: Boolean): SidebikeConfig =
    copy(whiteList = if (intercepted) whiteList - domain else whiteList + (domain to true))

/** 动作详情字段（纯 Kotlin 枚举，UI 层再映射到中文资源）。 */
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
    action.desc?.let { add(InterceptDetailLabel.DESC to it) }
    action.remark?.let { add(InterceptDetailLabel.REMARK to it) }
}

private fun detailLabelRes(label: InterceptDetailLabel): Int = when (label) {
    InterceptDetailLabel.SNI -> R.string.rules_detail_sni
    InterceptDetailLabel.PROXY -> R.string.rules_detail_proxy
    InterceptDetailLabel.SUCCESS -> R.string.rules_detail_success
    InterceptDetailLabel.SCRIPT -> R.string.rules_detail_script
    InterceptDetailLabel.STATUS -> R.string.rules_detail_status
    InterceptDetailLabel.CACHE_DAYS -> R.string.rules_detail_cache
    InterceptDetailLabel.REQUEST_REPLACE -> R.string.rules_detail_request
    InterceptDetailLabel.RESPONSE_REPLACE -> R.string.rules_detail_response
    InterceptDetailLabel.DESC -> R.string.rules_detail_desc
    InterceptDetailLabel.REMARK -> R.string.rules_detail_remark
}

/**
 * 规则页：搜索 + 功能开关（代理规则/MITM/国内直连）+ 域名列表（直连放行 Switch + 展开动作详情）。
 *
 * 动效：列表项 `Modifier.animateItem`（fade/placement，MD3 token）；
 * 展开详情 `animateContentSize`（EmphasizedDecelerate, Medium2）+ `AnimatedVisibility`（expand/shrink）。
 */
@Composable
fun RulesScreen(
    config: SidebikeConfig,
    contentPadding: PaddingValues,
    onConfigChange: (SidebikeConfig) -> Unit,
    modifier: Modifier = Modifier,
    onReloadConfig: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expandedDomain by rememberSaveable { mutableStateOf<String?>(null) }

    val domains = remember(config.intercepts) {
        config.intercepts.entries.sortedBy { it.key }
    }
    val filtered = remember(domains, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            domains
        } else {
            domains.filter { (domain, actions) ->
                domain.contains(keyword, ignoreCase = true) ||
                    actions.entries.any { (path, action) ->
                        path.contains(keyword, ignoreCase = true) ||
                            action.sni?.contains(keyword, true) == true ||
                            action.proxy?.contains(keyword, true) == true ||
                            action.desc?.contains(keyword, true) == true
                    }
            }
        }
    }
    val disabledCount = domains.count { !config.isDomainIntercepted(it.key) }
    val pathCount = domains.sumOf { it.value.size }
    val mitmEnabled = config.proxy.mitmEnabled || config.mitm.enabled

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("rules-list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rules-search"),
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                placeholder = { Text(stringResource(R.string.rules_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    AnimatedVisibility(visible = query.isNotEmpty(), enter = m3ExpandIn(), exit = m3ShrinkOut()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.rules_clear_search),
                            )
                        }
                    }
                },
            )
        }
        item(key = "features") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column {
                    FeatureRow(
                        title = stringResource(R.string.rules_feature_proxy),
                        description = stringResource(R.string.rules_feature_proxy_desc),
                        checked = config.proxy.enabled,
                        tag = "feature-switch:proxy",
                        onCheckedChange = { enabled ->
                            onConfigChange(config.copy(proxy = config.proxy.copy(enabled = enabled)))
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FeatureRow(
                        title = stringResource(R.string.rules_feature_domestic),
                        description = stringResource(R.string.rules_feature_domestic_desc),
                        checked = config.proxy.bypassDomestic,
                        tag = "feature-switch:domestic",
                        onCheckedChange = { enabled ->
                            onConfigChange(config.copy(proxy = config.proxy.copy(bypassDomestic = enabled)))
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FeatureRow(
                        title = stringResource(R.string.rules_feature_mitm),
                        description = stringResource(R.string.rules_feature_mitm_desc),
                        checked = mitmEnabled,
                        tag = "feature-switch:mitm",
                        onCheckedChange = { enabled ->
                            onConfigChange(
                                config.copy(
                                    proxy = config.proxy.copy(mitmEnabled = enabled),
                                    mitm = config.mitm.copy(enabled = enabled),
                                ),
                            )
                        },
                    )
                }
            }
        }
        item(key = "summary") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.rules_summary, domains.size, pathCount, disabledCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.rules_disabled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                if (domains.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Inbox,
                        title = stringResource(R.string.rules_empty_title),
                        description = stringResource(R.string.rules_empty_desc),
                        action = {
                            OutlinedButton(onClick = onReloadConfig) {
                                Text(stringResource(R.string.rules_reload_config))
                            }
                        },
                    )
                } else {
                    EmptyState(
                        icon = Icons.Outlined.Search,
                        title = stringResource(R.string.rules_no_result_title),
                        description = stringResource(R.string.rules_no_result_desc),
                    )
                }
            }
        } else {
            items(
                items = filtered,
                key = { entry -> entry.key },
            ) { (domain, actions) ->
                val intercepted = config.isDomainIntercepted(domain)
                DomainRuleCard(
                    domain = domain,
                    actions = actions,
                    intercepted = intercepted,
                    expanded = expandedDomain == domain,
                    onToggleExpanded = {
                        expandedDomain = if (expandedDomain == domain) null else domain
                    },
                    onToggleIntercepted = { nextIntercepted ->
                        onConfigChange(config.withDomainIntercepted(domain, nextIntercepted))
                    },
                    modifier = Modifier.animateItem(
                        fadeInSpec = M3ListItemFadeInSpec,
                        placementSpec = M3ListItemPlacementSpec,
                        fadeOutSpec = M3ListItemFadeOutSpec,
                    ),
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(
    title: String,
    description: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(tag),
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}

@Composable
private fun DomainRuleCard(
    domain: String,
    actions: Map<String, InterceptAction>,
    intercepted: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleIntercepted: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("rule-card:$domain"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = tween(
                        durationMillis = M3Motion.Medium2,
                        easing = M3Motion.EmphasizedDecelerate,
                    ),
                )
                .clickable(onClick = onToggleExpanded)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = domain,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (intercepted) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.rules_rule_count, actions.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.rules_direct_only_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = !intercepted,
                    onCheckedChange = { direct -> onToggleIntercepted(!direct) },
                    modifier = Modifier.testTag("rule-switch:$domain"),
                )
                val arrowRotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(
                        durationMillis = M3Motion.Medium2,
                        easing = M3Motion.EmphasizedDecelerate,
                    ),
                    label = "ruleExpandArrow",
                )
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(arrowRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val kinds = remember(actions) { ruleActionKinds(actions) }
            if (kinds.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.forEach { kind ->
                        StatusPill(
                            label = stringResource(ruleActionLabelRes(kind)),
                            containerColor = actionKindContainerColor(kind),
                            contentColor = actionKindContentColor(kind),
                            leadingDot = false,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded, enter = m3ExpandIn(), exit = m3ShrinkOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    actions.entries.forEach { (path, action) ->
                        ActionDetail(path = path, action = action)
                    }
                    if (!intercepted) {
                        Text(
                            text = stringResource(R.string.rules_disabled_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionDetail(path: String, action: InterceptAction) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.rules_path_label, path),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val details = remember(action) {
                interceptActionDetails(action)
            }
            details.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(detailLabelRes(label)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(88.dp),
                    )
                    Text(
                        text = value ?: stringResource(R.string.rules_detail_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun ruleActionLabelRes(kind: RuleActionKind): Int = when (kind) {
    RuleActionKind.SNI -> R.string.rules_action_sni
    RuleActionKind.PROXY -> R.string.rules_action_proxy
    RuleActionKind.SYNTHETIC -> R.string.rules_action_success
    RuleActionKind.SCRIPT -> R.string.rules_action_script
    RuleActionKind.STATUS -> R.string.rules_action_status_short
    RuleActionKind.CACHE -> R.string.rules_action_cache_short
}

@Composable
private fun actionKindContainerColor(kind: RuleActionKind): Color = when (kind) {
    RuleActionKind.SNI, RuleActionKind.PROXY -> MaterialTheme.colorScheme.secondaryContainer
    RuleActionKind.SYNTHETIC, RuleActionKind.SCRIPT -> MaterialTheme.colorScheme.tertiaryContainer
    RuleActionKind.STATUS, RuleActionKind.CACHE -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
private fun actionKindContentColor(kind: RuleActionKind): Color = when (kind) {
    RuleActionKind.SNI, RuleActionKind.PROXY -> MaterialTheme.colorScheme.onSecondaryContainer
    RuleActionKind.SYNTHETIC, RuleActionKind.SCRIPT -> MaterialTheme.colorScheme.onTertiaryContainer
    RuleActionKind.STATUS, RuleActionKind.CACHE -> MaterialTheme.colorScheme.onSurfaceVariant
}
