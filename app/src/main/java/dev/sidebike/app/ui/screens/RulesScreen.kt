@file:OptIn(ExperimentalMaterial3Api::class)

package dev.sidebike.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sidebike.app.R
import dev.sidebike.app.controller.RuleDraft
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.ui.SidebikeActions
import dev.sidebike.app.ui.components.AppTextField
import dev.sidebike.app.ui.motion.M3Motion
import dev.sidebike.app.ui.motion.m3FadeThrough
import dev.sidebike.app.ui.rules.RuleActionType
import dev.sidebike.app.ui.rules.RuleSourcePresets
import dev.sidebike.app.ui.rules.directPassDomains
import dev.sidebike.app.ui.rules.domesticDomains
import dev.sidebike.app.ui.rules.withRule
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.RuleSource
import dev.sidebike.core.api.RuleSourceFormat
import kotlinx.coroutines.launch

/** 规则页 4 个 tab 的下标（与 PrimaryTabRow 顺序一致）。 */
object RulesTab {
    const val INTERCEPTS = 0
    const val DIRECT_PASS = 1
    const val DOMESTIC = 2
    const val SOURCES = 3
}

/** 规则编辑对话框的输入状态。 */
private data class RuleEditorState(
    val draft: RuleDraft,
    val actionType: RuleActionType,
    val editing: Boolean,
)

/** 待删除的单条规则。 */
private data class RuleDeleteTarget(val domain: String, val path: String)

/**
 * 规则页（0.1.1-alpha item 4/5/6/7）：
 * - 4 个 tab：拦截规则 / 直连放行 / 国内域名 / 规则源，共享顶部搜索与计数；
 * - 拦截规则：展开/收起 + 编辑/删除 + 添加规则（SNI/镜像/abort），卡片 ripple 由 Card 裁剪；
 * - 直连放行：添加/移除（controller.setDirectPassDomains）；
 * - 国内域名：搜索/添加/移除/恢复默认（controller.setDomesticAllowList / reloadConfig）；
 * - 规则源：推荐预设（Johnshall，CC BY-SA 4.0）/自定义/文本导入/立即刷新/编辑/删除/启停。
 */
@Composable
fun RulesScreen(
    state: SidebikeUiState,
    contentPadding: PaddingValues,
    actions: SidebikeActions,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int? = null,
    onTabConsumed: () -> Unit = {},
) {
    val config = state.config
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf((initialTab ?: RulesTab.INTERCEPTS).coerceIn(0, 3)) }
    var expandedDomain by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(initialTab) {
        val requested = initialTab ?: return@LaunchedEffect
        selectedTab = requested.coerceIn(0, 3)
        onTabConsumed()
    }

    // 对话框状态
    var ruleEditor by remember { mutableStateOf<RuleEditorState?>(null) }
    var ruleDeleteTarget by remember { mutableStateOf<RuleDeleteTarget?>(null) }
    var addDirectPass by remember { mutableStateOf(false) }
    var addDomestic by remember { mutableStateOf(false) }
    var resetDomestic by remember { mutableStateOf(false) }
    var sourceEditor by remember { mutableStateOf<SourceEditorState?>(null) }
    var deleteSourceTarget by remember { mutableStateOf<RuleSource?>(null) }
    var showImport by remember { mutableStateOf(false) }

    val interceptDomains = remember(config.intercepts) { config.intercepts.entries.sortedBy { it.key } }
    val directDomains = remember(config.whiteList) { directPassDomains(config) }
    val domestic = remember(config.domesticAllowList) { domesticDomains(config) }
    val sources = config.ruleSources
    val busy = state.ruleSourcesBusy

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            AppTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.rules_search_hint),
                singleLine = true,
                testTag = "rules-search",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.rules_tab_summary,
                        interceptDomains.sumOf { it.value.size },
                        directDomains.size,
                        domestic.size,
                        sources.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PrimaryTabRow(selectedTabIndex = selectedTab) {
            val tabs = listOf(
                RulesTab.INTERCEPTS to stringResource(R.string.rules_tab_intercepts),
                RulesTab.DIRECT_PASS to stringResource(R.string.rules_tab_direct),
                RulesTab.DOMESTIC to stringResource(R.string.rules_tab_domestic),
                RulesTab.SOURCES to stringResource(R.string.rules_tab_sources),
            )
            tabs.forEach { (index, label) ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(label, maxLines = 1) },
                    modifier = Modifier.testTag("rules-tab:$index"),
                )
            }
        }

        AnimatedContent(
            targetState = selectedTab,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            transitionSpec = { m3FadeThrough() },
            label = "rulesTabContent",
        ) { tab ->
            when (tab) {
                RulesTab.INTERCEPTS -> InterceptsTabContent(
                    config = config,
                    query = query,
                    expandedDomain = expandedDomain,
                    contentPadding = contentPadding,
                    onToggleExpanded = { domain ->
                        expandedDomain = if (expandedDomain == domain) null else domain
                    },
                    onToggleDirectPass = { domain, directPass ->
                        scope.launch {
                            actions.setDirectPassDomains(
                                if (directPass) {
                                    (directPassDomains(config) + domain).distinct()
                                } else {
                                    directPassDomains(config) - domain
                                },
                            )
                            onShowMessage(context.getString(R.string.direct_updated))
                        }
                    },
                    onAddRule = {
                        ruleEditor = RuleEditorState(
                            draft = RuleDraft(domainPattern = "", pathRegex = ".*"),
                            actionType = RuleActionType.SNI,
                            editing = false,
                        )
                    },
                    onEditRule = { domain, path, action ->
                        ruleEditor = RuleEditorState(
                            draft = RuleDraft(
                                domainPattern = domain,
                                pathRegex = path,
                                sni = action.sni,
                                proxy = action.proxy,
                                desc = action.desc,
                            ),
                            actionType = actionTypeOf(action),
                            editing = true,
                        )
                    },
                    onDeleteRule = { domain, path ->
                        ruleDeleteTarget = RuleDeleteTarget(domain, path)
                    },
                )

                RulesTab.DIRECT_PASS -> DirectPassTabContent(
                    domains = directDomains,
                    query = query,
                    contentPadding = contentPadding,
                    onAdd = { addDirectPass = true },
                    onRemove = { domain ->
                        scope.launch {
                            actions.setDirectPassDomains(directPassDomains(config) - domain)
                            onShowMessage(context.getString(R.string.direct_updated))
                        }
                    },
                )

                RulesTab.DOMESTIC -> DomesticTabContent(
                    domains = domestic,
                    query = query,
                    contentPadding = contentPadding,
                    onAdd = { addDomestic = true },
                    onRemove = { domain ->
                        scope.launch {
                            actions.setDomesticAllowList(domesticDomains(config) - domain)
                        }
                    },
                    onReset = { resetDomestic = true },
                )

                RulesTab.SOURCES -> SourcesTabContent(
                    sources = sources,
                    busy = busy,
                    query = query,
                    contentPadding = contentPadding,
                    onAddPreset = {
                        val existingIds = sources.map { it.id }.toSet()
                        val merged = RuleSourcePresets.mergeInto(sources)
                        val added = merged.count { it.id !in existingIds }
                        scope.launch {
                            actions.updateConfig(config.copy(ruleSources = merged))
                            onShowMessage(context.getString(R.string.domestic_updated))
                        }
                        if (added == 0) {
                            onShowMessage(context.getString(R.string.source_preset_exists))
                        } else {
                            onShowMessage(context.getString(R.string.source_preset_added, added))
                        }
                    },
                    onAddCustom = {
                        sourceEditor = SourceEditorState(source = null, editing = false)
                    },
                    onImport = { showImport = true },
                    onRefreshAll = {
                        scope.launch {
                            val summary = actions.refreshRuleSources(true)
                            onShowMessage(refreshSummaryText(summary))
                        }
                    },
                    onToggleEnabled = { source, enabled ->
                        scope.launch {
                            actions.updateConfig(
                                config.copy(
                                    ruleSources = sources.map { if (it.id == source.id) it.copy(enabled = enabled) else it },
                                ),
                            )
                        }
                    },
                    onEdit = { source -> sourceEditor = SourceEditorState(source, editing = true) },
                    onDelete = { source -> deleteSourceTarget = source },
                    onRefreshOne = {
                        scope.launch {
                            val summary = actions.refreshRuleSources(true)
                            onShowMessage(refreshSummaryText(summary))
                        }
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------ dialogs
    ruleEditor?.let { editor ->
        RuleEditorDialog(
            initial = if (editor.editing) editor.draft else null,
            initialAction = editor.actionType,
            onDismiss = { ruleEditor = null },
            onSave = { draft, actionType ->
                ruleEditor = null
                scope.launch {
                    if (actionType == RuleActionType.ABORT) {
                        // 契约 RuleDraft 不含 abort：通过配置合并写入 InterceptAction(abort=true)
                        actions.updateConfig(config.withRule(draft, actionType))
                    } else {
                        actions.saveRule(draft)
                    }
                    onShowMessage(context.getString(R.string.rule_saved))
                }
            },
        )
    }

    ruleDeleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.rules_delete_rule_title),
            description = stringResource(
                R.string.rules_delete_rule_desc,
                target.domain,
                target.path,
            ),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                ruleDeleteTarget = null
                scope.launch {
                    actions.deleteRule(target.domain, target.path)
                    onShowMessage(context.getString(R.string.rule_deleted))
                }
            },
            onDismiss = { ruleDeleteTarget = null },
            modifier = Modifier.testTag("rule-delete-dialog"),
        )
    }

    if (addDirectPass) {
        SingleInputDialog(
            title = stringResource(R.string.direct_pass_add),
            label = stringResource(R.string.rule_domain_label),
            placeholder = stringResource(R.string.direct_pass_domain_hint),
            onDismiss = { addDirectPass = false },
            onConfirm = { value ->
                addDirectPass = false
                if (value.isNotBlank()) {
                    scope.launch {
                        actions.setDirectPassDomains((directPassDomains(config) + value).distinct())
                        onShowMessage(context.getString(R.string.direct_updated))
                    }
                }
            },
            modifier = Modifier.testTag("direct-add-dialog"),
        )
    }

    if (addDomestic) {
        SingleInputDialog(
            title = stringResource(R.string.domestic_add),
            label = stringResource(R.string.rule_domain_label),
            placeholder = stringResource(R.string.direct_pass_domain_hint),
            onDismiss = { addDomestic = false },
            onConfirm = { value ->
                addDomestic = false
                if (value.isNotBlank()) {
                    scope.launch {
                        actions.setDomesticAllowList((domesticDomains(config) + value).distinct())
                        onShowMessage(context.getString(R.string.domestic_updated))
                    }
                }
            },
            modifier = Modifier.testTag("domestic-add-dialog"),
        )
    }

    if (resetDomestic) {
        ConfirmDialog(
            title = stringResource(R.string.domestic_reset_title),
            description = stringResource(R.string.domestic_reset_desc),
            confirmText = stringResource(R.string.action_reset),
            onConfirm = {
                resetDomestic = false
                scope.launch { actions.reloadConfig() }
                onShowMessage(context.getString(R.string.domestic_reset_done))
            },
            onDismiss = { resetDomestic = false },
        )
    }

    sourceEditor?.let { editor ->
        RuleSourceEditorDialog(
            initial = editor.source,
            onDismiss = { sourceEditor = null },
            onSave = { source ->
                sourceEditor = null
                val nextSources = if (editor.editing && editor.source != null) {
                    sources.map { if (it.id == source.id) source else it }
                } else {
                    sources + source
                }
                scope.launch {
                    actions.updateConfig(config.copy(ruleSources = nextSources))
                    onShowMessage(context.getString(R.string.source_saved))
                }
            },
        )
    }

    deleteSourceTarget?.let { source ->
        ConfirmDialog(
            title = stringResource(R.string.source_delete_title),
            description = stringResource(R.string.source_delete_desc, source.name),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                deleteSourceTarget = null
                scope.launch {
                    actions.updateConfig(config.copy(ruleSources = sources.filterNot { it.id == source.id }))
                    onShowMessage(context.getString(R.string.source_deleted))
                }
            },
            onDismiss = { deleteSourceTarget = null },
        )
    }

    if (showImport) {
        RuleImportDialog(
            onDismiss = { showImport = false },
            onImport = { text, format, name ->
                showImport = false
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    // URL：先创建规则源，再触发刷新（控制器会拉取并合并）
                    val newSource = RuleSource(
                        id = "custom-${System.currentTimeMillis()}",
                        name = name.ifBlank { text.substringAfterLast('/') },
                        url = text,
                        format = RuleSourceFormat.AUTO,
                    )
                    scope.launch {
                        actions.updateConfig(config.copy(ruleSources = sources + newSource))
                        val summary = actions.refreshRuleSources(true)
                        onShowMessage(refreshSummaryText(summary))
                    }
                } else {
                    scope.launch {
                        val summary = actions.importRulesFromText(text, format, name.ifBlank { "本地导入" })
                        onShowMessage(
                            "导入完成：拦截 ${summary.addedIntercepts} · 直连 ${summary.addedDirectPassDomains} · 国内 ${summary.addedDomesticDomains}",
                        )
                    }
                }
            },
        )
    }
}

private data class SourceEditorState(val source: RuleSource?, val editing: Boolean)

/**
 * 兼容旧调用方（QA 截图测试）：仅按 config 渲染规则页。
 * 新代码请使用 [RulesScreen] 的 state + actions 重载（0.1.1-alpha）。
 */
@Composable
fun RulesScreen(
    config: dev.sidebike.core.api.SidebikeConfig,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onConfigChange: (dev.sidebike.core.api.SidebikeConfig) -> Unit,
    modifier: Modifier = Modifier,
    onReloadConfig: () -> Unit = {},
) {
    RulesScreen(
        state = SidebikeUiState(config = config),
        contentPadding = contentPadding,
        actions = SidebikeActions(
            updateConfig = onConfigChange,
            reloadConfig = onReloadConfig,
        ),
        onShowMessage = {},
        modifier = modifier,
    )
}
