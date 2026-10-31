@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.sidebike.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.sidebike.app.ui.components.StatusPill
import dev.sidebike.app.ui.components.UiFormat
import dev.sidebike.app.ui.motion.LocalInfiniteAnimationsEnabled
import dev.sidebike.app.ui.motion.M3ListItemFadeInSpec
import dev.sidebike.app.ui.motion.M3ListItemFadeOutSpec
import dev.sidebike.app.ui.motion.M3Motion
import dev.sidebike.app.ui.motion.M3RuleExpandSpec
import dev.sidebike.app.ui.motion.M3RulePlacementSpec
import dev.sidebike.app.ui.rules.RuleActionType
import dev.sidebike.app.ui.rules.RuleSourcePresets
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.RuleSource
import dev.sidebike.core.api.RuleSourceFormat
import dev.sidebike.core.api.SidebikeConfig

// ---------------------------------------------------------------------------------------------
// 拦截规则 tab
// ---------------------------------------------------------------------------------------------

@Composable
fun InterceptsTabContent(
    config: SidebikeConfig,
    query: String,
    expandedDomain: String?,
    contentPadding: PaddingValues,
    onToggleExpanded: (String) -> Unit,
    onToggleDirectPass: (domain: String, directPass: Boolean) -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (domain: String, path: String, action: InterceptAction) -> Unit,
    onDeleteRule: (domain: String, path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val domains = remember(config.intercepts) { config.intercepts.entries.sortedBy { it.key } }
    val entries = remember(domains) { domains.map { it.key to it.value } }
    val filtered = remember(entries, query) { dev.sidebike.app.ui.rules.filterInterceptEntries(entries, query) }
    val pathCount = domains.sumOf { it.value.size }
    val directCount = domains.count { config.whiteList[it.key] == true }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("rules-list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "intercepts-actions") {
            ListHeaderRow(
                summary = stringResource(R.string.rules_summary, domains.size, pathCount, directCount),
                actionLabel = stringResource(R.string.rules_add_rule),
                actionTag = "rules-add-rule",
                onAction = onAddRule,
            )
        }
        item(key = "intercepts-note") {
            Text(
                text = stringResource(R.string.rules_disabled_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (filtered.isEmpty()) {
            item(key = "intercepts-empty") {
                if (domains.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Inbox,
                        title = stringResource(R.string.rules_empty_title),
                        description = stringResource(R.string.rules_empty_desc),
                    )
                } else {
                    EmptyState(
                        icon = Icons.Outlined.Inbox,
                        title = stringResource(R.string.rules_no_result_title),
                        description = stringResource(R.string.rules_no_result_desc),
                    )
                }
            }
        } else {
            items(items = filtered, key = { it.first }) { (domain, actions) ->
                DomainRuleCard(
                    domain = domain,
                    actions = actions,
                    intercepted = config.whiteList[domain] != true,
                    expanded = expandedDomain == domain,
                    onToggleExpanded = { onToggleExpanded(domain) },
                    onToggleIntercepted = { nextIntercepted -> onToggleDirectPass(domain, !nextIntercepted) },
                    onEditRule = onEditRule,
                    onDeleteRule = onDeleteRule,
                    modifier = Modifier.animateItem(
                        fadeInSpec = M3ListItemFadeInSpec,
                        placementSpec = M3RulePlacementSpec,
                        fadeOutSpec = M3ListItemFadeOutSpec,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ListHeaderRow(
    summary: String,
    actionLabel: String,
    actionTag: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            onClick = onAction,
            modifier = Modifier.testTag(actionTag),
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(text = actionLabel)
        }
    }
}

@Composable
private fun DomainRuleCard(
    domain: String,
    actions: Map<String, InterceptAction>,
    intercepted: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleIntercepted: (Boolean) -> Unit,
    onEditRule: (domain: String, path: String, action: InterceptAction) -> Unit,
    onDeleteRule: (domain: String, path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.large
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.EmphasizedDecelerate),
        label = "ruleExpandArrow",
    )
    // Card(onClick) 的 ripple 由 Card 在 shape 内裁剪绘制（覆盖整卡、无球形/越界 ripple）。
    Card(
        onClick = onToggleExpanded,
        modifier = modifier
            .fillMaxWidth()
            .testTag("rule-card:$domain"),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.EmphasizedDecelerate)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                ) {
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
                        style = MaterialTheme.typography.bodySmall,
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
                IconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.testTag("rule-expand:$domain"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) {
                            stringResource(R.string.rules_collapse)
                        } else {
                            stringResource(R.string.rules_expand)
                        },
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(arrowRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (expanded) 12.dp else 8.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val kinds = remember(actions) { ruleActionKinds(actions) }
                if (kinds.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                // 单一高度动画：只保留 animateContentSize（与兄弟卡片位移同 token），
                // 不再叠加 AnimatedVisibility expand/shrink，避免双重高度动画导致抖动。
                if (expanded) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    actions.forEach { (path, action) ->
                        ActionDetailRow(
                            domain = domain,
                            path = path,
                            action = action,
                            onEdit = { onEditRule(domain, path, action) },
                            onDelete = { onDeleteRule(domain, path) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionDetailRow(
    domain: String,
    path: String,
    action: InterceptAction,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        dev.sidebike.app.ui.screens.ActionDetail(
            path = path,
            action = action,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onEdit,
            modifier = Modifier.testTag("rule-edit:$domain:$path"),
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.action_edit),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.testTag("rule-delete:$domain:$path"),
        ) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 直连放行 tab
// ---------------------------------------------------------------------------------------------

@Composable
fun DirectPassTabContent(
    domains: List<String>,
    query: String,
    contentPadding: PaddingValues,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = remember(domains, query) { dev.sidebike.app.ui.rules.filterDomainsByQuery(domains, query) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("direct-pass-list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "direct-actions") {
            ListHeaderRow(
                summary = stringResource(R.string.domestic_count, domains.size),
                actionLabel = stringResource(R.string.direct_pass_add),
                actionTag = "direct-pass-add",
                onAction = onAdd,
            )
        }
        item(key = "direct-desc") {
            Text(
                text = stringResource(R.string.direct_pass_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (filtered.isEmpty()) {
            item(key = "direct-empty") {
                EmptyState(
                    icon = Icons.Outlined.Inbox,
                    title = stringResource(R.string.direct_pass_empty_title),
                    description = stringResource(R.string.direct_pass_empty_desc),
                    action = {
                        OutlinedButton(onClick = onAdd) {
                            Text(stringResource(R.string.direct_pass_add))
                        }
                    },
                )
            }
        } else {
            items(items = filtered, key = { it }) { domain ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("direct-item:$domain"),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onRemove(domain) },
                            modifier = Modifier.testTag("direct-remove:$domain"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 国内域名 tab
// ---------------------------------------------------------------------------------------------

@Composable
fun DomesticTabContent(
    domains: List<String>,
    query: String,
    contentPadding: PaddingValues,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = remember(domains, query) { dev.sidebike.app.ui.rules.filterDomainsByQuery(domains, query) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("domestic-list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "domestic-actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListHeaderRow(
                    summary = stringResource(R.string.domestic_count, domains.size),
                    actionLabel = stringResource(R.string.domestic_add),
                    actionTag = "domestic-add",
                    onAction = onAdd,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onReset,
                        modifier = Modifier.testTag("domestic-reset"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(R.string.action_reset))
                    }
                }
                Text(
                    text = stringResource(R.string.domestic_desc, domains.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (filtered.isEmpty()) {
            item(key = "domestic-empty") {
                EmptyState(
                    icon = Icons.Outlined.Inbox,
                    title = stringResource(R.string.domestic_empty_title),
                    description = stringResource(R.string.domestic_empty_desc),
                )
            }
        } else {
            items(items = filtered, key = { it }) { domain ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("domestic-item:$domain"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = domain,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onRemove(domain) },
                        modifier = Modifier.testTag("domestic-remove:$domain"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 规则源 tab
// ---------------------------------------------------------------------------------------------

@Composable
fun SourcesTabContent(
    sources: List<RuleSource>,
    busy: Boolean,
    query: String,
    contentPadding: PaddingValues,
    onAddPreset: () -> Unit,
    onAddCustom: () -> Unit,
    onImport: () -> Unit,
    onRefreshAll: () -> Unit,
    onToggleEnabled: (RuleSource, Boolean) -> Unit,
    onEdit: (RuleSource) -> Unit,
    onDelete: (RuleSource) -> Unit,
    onRefreshOne: (RuleSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = remember(sources, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            sources
        } else {
            sources.filter {
                it.name.contains(keyword, true) || it.url.contains(keyword, true)
            }
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("sources-list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "sources-actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.sources_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onRefreshAll,
                        enabled = !busy,
                        modifier = Modifier.testTag("sources-refresh-all"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(R.string.source_refresh_all))
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = onAddPreset,
                        modifier = Modifier.testTag("sources-add-preset"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(R.string.source_add_preset))
                    }
                    OutlinedButton(
                        onClick = onAddCustom,
                        modifier = Modifier.testTag("sources-add-custom"),
                    ) {
                        Text(stringResource(R.string.source_add_custom))
                    }
                    OutlinedButton(
                        onClick = onImport,
                        modifier = Modifier.testTag("sources-import"),
                    ) {
                        Text(stringResource(R.string.source_import))
                    }
                }
                Text(
                    text = stringResource(R.string.sources_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.source_skip_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.source_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) {
                    if (LocalInfiniteAnimationsEnabled.current) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { 0.5f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = stringResource(R.string.source_busy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item(key = "sources-empty") {
                EmptyState(
                    icon = Icons.Outlined.Inbox,
                    title = stringResource(R.string.source_empty_title),
                    description = stringResource(R.string.source_empty_desc),
                    action = {
                        OutlinedButton(onClick = onAddPreset) {
                            Text(stringResource(R.string.source_add_preset))
                        }
                    },
                )
            }
        } else {
            items(items = filtered, key = { it.id }) { source ->
                RuleSourceCard(
                    source = source,
                    busy = busy,
                    onToggleEnabled = { onToggleEnabled(source, it) },
                    onEdit = { onEdit(source) },
                    onDelete = { onDelete(source) },
                    onRefresh = { onRefreshOne(source) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = M3ListItemFadeInSpec,
                        placementSpec = M3RulePlacementSpec,
                        fadeOutSpec = M3ListItemFadeOutSpec,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RuleSourceCard(
    source: RuleSource,
    busy: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("source-card:${source.id}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = source.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("source-switch:${source.id}"),
                )
            }
            Text(
                text = source.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    label = stringResource(dev.sidebike.app.ui.rules.sourceFormatLabelRes(source.format)),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    leadingDot = false,
                )
                StatusPill(
                    label = if (source.autoUpdate) {
                        if (source.updateIntervalHours > 0) {
                            stringResource(R.string.source_interval_hours, source.updateIntervalHours)
                        } else {
                            stringResource(R.string.source_manual_interval)
                        }
                    } else {
                        stringResource(R.string.source_manual_interval)
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingDot = false,
                )
            }
            Text(
                text = if (source.lastUpdatedMs > 0) {
                    stringResource(R.string.source_last_updated, UiFormat.dateTime(source.lastUpdatedMs))
                } else {
                    stringResource(R.string.source_never_updated)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (source.lastImported > 0) {
                Text(
                    text = stringResource(R.string.source_last_imported, source.lastImported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            source.lastError?.let { error ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.source_last_error, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    modifier = Modifier.testTag("source-refresh:${source.id}"),
                ) {
                    Text(stringResource(R.string.source_refresh_one))
                }
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("source-edit:${source.id}"),
                ) {
                    Text(stringResource(R.string.action_edit))
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("source-delete:${source.id}"),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 共用：动作详情（保留原 RulesScreen 的公开语义，供测试/其他 tab 使用）
// ---------------------------------------------------------------------------------------------

@Composable
fun ActionDetail(
    path: String,
    action: InterceptAction,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
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
            val details = remember(action) { dev.sidebike.app.ui.screens.interceptActionDetails(action) }
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

internal fun ruleActionLabelRes(kind: RuleActionKind): Int = when (kind) {
    RuleActionKind.SNI -> R.string.rules_action_sni
    RuleActionKind.PROXY -> R.string.rules_action_proxy
    RuleActionKind.SYNTHETIC -> R.string.rules_action_success
    RuleActionKind.SCRIPT -> R.string.rules_action_script
    RuleActionKind.STATUS -> R.string.rules_action_status_short
    RuleActionKind.CACHE -> R.string.rules_action_cache_short
    RuleActionKind.ABORT -> R.string.rule_action_abort
}

@Composable
internal fun actionKindContainerColor(kind: RuleActionKind): Color = when (kind) {
    RuleActionKind.SNI, RuleActionKind.PROXY -> MaterialTheme.colorScheme.secondaryContainer
    RuleActionKind.SYNTHETIC, RuleActionKind.SCRIPT -> MaterialTheme.colorScheme.tertiaryContainer
    RuleActionKind.STATUS, RuleActionKind.CACHE -> MaterialTheme.colorScheme.surfaceContainerHighest
    RuleActionKind.ABORT -> MaterialTheme.colorScheme.errorContainer
}

@Composable
internal fun actionKindContentColor(kind: RuleActionKind): Color = when (kind) {
    RuleActionKind.SNI, RuleActionKind.PROXY -> MaterialTheme.colorScheme.onSecondaryContainer
    RuleActionKind.SYNTHETIC, RuleActionKind.SCRIPT -> MaterialTheme.colorScheme.onTertiaryContainer
    RuleActionKind.STATUS, RuleActionKind.CACHE -> MaterialTheme.colorScheme.onSurfaceVariant
    RuleActionKind.ABORT -> MaterialTheme.colorScheme.onErrorContainer
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
    InterceptDetailLabel.ABORT -> R.string.rule_action_abort
}