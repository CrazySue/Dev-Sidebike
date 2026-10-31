package dev.sidebike.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sidebike.app.R
import dev.sidebike.app.controller.RuleDraft
import dev.sidebike.app.controller.RuleSourceRefreshSummary
import dev.sidebike.app.ui.components.AppTextField
import dev.sidebike.app.ui.rules.RuleActionType
import dev.sidebike.app.ui.rules.isPlausibleDomainPattern
import dev.sidebike.app.ui.rules.isPlausiblePathRegex
import dev.sidebike.core.api.RuleSource
import dev.sidebike.core.api.RuleSourceFormat

/** 通用确认对话框（MD3 AlertDialog，默认 scrim）。 */
@Composable
fun ConfirmDialog(
    title: String,
    description: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm-positive"),
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("confirm-negative")) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = modifier,
    )
}

/** 添加/编辑单条拦截规则。 */
@Composable
fun RuleEditorDialog(
    initial: RuleDraft? = null,
    initialAction: RuleActionType = RuleActionType.SNI,
    onDismiss: () -> Unit,
    onSave: (RuleDraft, RuleActionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = initial != null
    var domain by remember { mutableStateOf(initial?.domainPattern.orEmpty()) }
    var path by remember { mutableStateOf(initial?.pathRegex ?: ".*") }
    var actionType by remember { mutableStateOf(initialAction) }
    var sni by remember { mutableStateOf(initial?.sni.orEmpty()) }
    var proxy by remember { mutableStateOf(initial?.proxy.orEmpty()) }
    var desc by remember { mutableStateOf(initial?.desc.orEmpty()) }

    val domainValid = isPlausibleDomainPattern(domain)
    val pathValid = isPlausiblePathRegex(path)
    val actionValid = when (actionType) {
        RuleActionType.SNI -> sni.isNotBlank()
        RuleActionType.PROXY -> proxy.isNotBlank()
        RuleActionType.ABORT -> true
    }
    val valid = domainValid && pathValid && actionValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (editing) R.string.rules_edit_rule else R.string.rules_add_rule))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (editing) {
                    Text(
                        text = "${initial?.domainPattern.orEmpty()}  ·  ${initial?.pathRegex.orEmpty()}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    AppTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = stringResource(R.string.rule_domain_label),
                        placeholder = stringResource(R.string.rule_domain_hint),
                        isError = domain.isNotEmpty() && !domainValid,
                        supportingText = if (domain.isNotEmpty() && !domainValid) {
                            stringResource(R.string.rule_invalid_domain)
                        } else {
                            null
                        },
                        testTag = "rule-domain-input",
                    )
                    AppTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = stringResource(R.string.rule_path_label_input),
                        placeholder = stringResource(R.string.rule_path_hint),
                        isError = !pathValid,
                        testTag = "rule-path-input",
                    )
                }
                Text(
                    text = stringResource(R.string.rule_action_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    RuleActionType.entries.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = actionType == type,
                            onClick = { actionType = type },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = RuleActionType.entries.size,
                            ),
                            modifier = Modifier.testTag("rule-action-${type.name}"),
                        ) {
                            Text(stringResource(ruleActionLabelRes(type)))
                        }
                    }
                }
                when (actionType) {
                    RuleActionType.SNI -> AppTextField(
                        value = sni,
                        onValueChange = { sni = it },
                        label = stringResource(R.string.rule_action_sni),
                        placeholder = stringResource(R.string.rule_action_sni_hint),
                        isError = sni.isBlank(),
                        testTag = "rule-sni-input",
                    )

                    RuleActionType.PROXY -> AppTextField(
                        value = proxy,
                        onValueChange = { proxy = it },
                        label = stringResource(R.string.rule_action_proxy),
                        placeholder = stringResource(R.string.rule_action_proxy_hint),
                        isError = proxy.isBlank(),
                        testTag = "rule-proxy-input",
                    )

                    RuleActionType.ABORT -> Text(
                        text = stringResource(R.string.source_skip_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = stringResource(R.string.rule_desc_label),
                    placeholder = stringResource(R.string.rule_desc_hint),
                    testTag = "rule-desc-input",
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        RuleDraft(
                            domainPattern = domain.trim(),
                            pathRegex = path.trim().ifEmpty { ".*" },
                            sni = sni.trim().ifEmpty { null },
                            proxy = proxy.trim().ifEmpty { null },
                            desc = desc.trim().ifEmpty { null },
                        ),
                        actionType,
                    )
                },
                modifier = Modifier.testTag("rule-save"),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = modifier.testTag("rule-editor-dialog"),
    )
}

private fun ruleActionLabelRes(type: RuleActionType): Int = when (type) {
    RuleActionType.SNI -> R.string.rule_action_sni
    RuleActionType.PROXY -> R.string.rule_action_proxy
    RuleActionType.ABORT -> R.string.rule_action_abort
}

/** 单输入框对话框（添加直连域名/国内域名等）。 */
@Composable
fun SingleInputDialog(
    title: String,
    label: String,
    placeholder: String? = null,
    initialValue: String = "",
    supportingText: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            AppTextField(
                value = value,
                onValueChange = { value = it },
                label = label,
                placeholder = placeholder,
                supportingText = supportingText,
                singleLine = true,
                testTag = "single-input",
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
                modifier = Modifier.testTag("single-input-save"),
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = modifier,
    )
}

/** 规则源编辑对话框（添加/编辑共用）。 */
@Composable
fun RuleSourceEditorDialog(
    initial: RuleSource? = null,
    onDismiss: () -> Unit,
    onSave: (RuleSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = initial != null
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var format by remember { mutableStateOf(initial?.format ?: RuleSourceFormat.AUTO) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var autoUpdate by remember { mutableStateOf(initial?.autoUpdate ?: true) }
    var interval by remember { mutableStateOf((initial?.updateIntervalHours ?: 24).toString()) }

    val urlValid = url.trim().startsWith("http://") || url.trim().startsWith("https://")
    val nameValid = name.isNotBlank()
    val intervalValue = interval.toIntOrNull() ?: 0
    val valid = nameValid && urlValid && intervalValue >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (editing) R.string.source_edit_title else R.string.source_add_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.source_name_label),
                    isError = name.isNotEmpty() && !nameValid,
                    testTag = "source-name-input",
                )
                AppTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.source_url_label),
                    placeholder = "https://raw.githubusercontent.com/...",
                    isError = url.isNotEmpty() && !urlValid,
                    supportingText = if (url.isNotEmpty() && !urlValid) {
                        stringResource(R.string.source_invalid_url)
                    } else {
                        null
                    },
                    testTag = "source-url-input",
                )
                Text(
                    text = stringResource(R.string.source_format_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    RuleSourceFormat.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = format == item,
                            onClick = { format = item },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = RuleSourceFormat.entries.size,
                            ),
                            modifier = Modifier.testTag("source-format-${item.name}"),
                        ) {
                            Text(stringResource(dev.sidebike.app.ui.rules.sourceFormatLabelRes(item)))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.source_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        modifier = Modifier.testTag("source-enabled"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.source_auto_update),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = autoUpdate,
                        onCheckedChange = { autoUpdate = it },
                        modifier = Modifier.testTag("source-auto-update"),
                    )
                }
                AppTextField(
                    value = interval,
                    onValueChange = { interval = it.filter { c -> c.isDigit() }.take(3) },
                    label = stringResource(R.string.source_interval_label),
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    testTag = "source-interval-input",
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        RuleSource(
                            id = initial?.id ?: "custom-${System.currentTimeMillis()}",
                            name = name.trim(),
                            url = url.trim(),
                            format = format,
                            enabled = enabled,
                            autoUpdate = autoUpdate,
                            updateIntervalHours = intervalValue,
                            lastUpdatedMs = initial?.lastUpdatedMs ?: 0L,
                            lastError = initial?.lastError,
                            etag = initial?.etag,
                            lastModified = initial?.lastModified,
                            lastImported = initial?.lastImported ?: 0,
                        ),
                    )
                },
                modifier = Modifier.testTag("source-save"),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = modifier.testTag("source-editor-dialog"),
    )
}

/** 从文本/URL 导入规则。 */
@Composable
fun RuleImportDialog(
    onDismiss: () -> Unit,
    onImport: (text: String, format: RuleSourceFormat, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(RuleSourceFormat.AUTO) }
    val isUrl = text.trim().startsWith("http://") || text.trim().startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_import_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.source_import_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = stringResource(R.string.source_import_text_label),
                    singleLine = false,
                    minLines = 4,
                    maxLines = 8,
                    testTag = "import-text-input",
                )
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.source_import_name_label),
                    singleLine = true,
                    testTag = "import-name-input",
                )
                if (!isUrl) {
                    Text(
                        text = stringResource(R.string.source_format_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        RuleSourceFormat.entries.forEachIndexed { index, item ->
                            SegmentedButton(
                                selected = format == item,
                                onClick = { format = item },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = RuleSourceFormat.entries.size,
                                ),
                                modifier = Modifier.testTag("import-format-${item.name}"),
                            ) {
                                Text(stringResource(dev.sidebike.app.ui.rules.sourceFormatLabelRes(item)))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onImport(text.trim(), format, name.trim()) },
                modifier = Modifier.testTag("import-save"),
            ) {
                Text(stringResource(R.string.action_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = modifier.testTag("rule-import-dialog"),
    )
}

/** 规则源刷新结果 -> 提示文案（UI 层组装，便于测试）。 */
fun refreshSummaryText(summary: RuleSourceRefreshSummary): String {
    val parts = buildList {
        if (summary.updatedSources.isNotEmpty()) add("更新 ${summary.updatedSources.size} 个源")
        if (summary.notModifiedSources.isNotEmpty()) add("未变化 ${summary.notModifiedSources.size} 个")
        if (summary.failedSources.isNotEmpty()) add("失败 ${summary.failedSources.size} 个")
        if (summary.importedRules > 0) add("导入 ${summary.importedRules} 条")
    }
    return parts.joinToString(" · ").ifEmpty { "无变化" }
}
