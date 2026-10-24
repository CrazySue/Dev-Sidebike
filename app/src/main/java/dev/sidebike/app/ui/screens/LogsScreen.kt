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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.sidebike.app.ui.components.UiFormat
import dev.sidebike.app.ui.motion.M3ListItemFadeInSpec
import dev.sidebike.app.ui.motion.M3ListItemFadeOutSpec
import dev.sidebike.app.ui.motion.M3ListItemPlacementSpec
import dev.sidebike.app.ui.motion.M3Motion
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel

/**
 * 日志页：级别筛选 chips、自动滚动、清空、可展开的日志行。
 *
 * 动效：级别切换 chips（MD3 FilterChip 内建状态动画）；列表项 `animateItem`；
 * 日志展开 `animateContentSize`（EmphasizedDecelerate）+ 箭头旋转 `animateFloatAsState`。
 * 自动滚动使用 Compose 滚动动画（物理曲线，非线性插值）。
 */
@Composable
fun LogsScreen(
    logs: List<LogEntry>,
    contentPadding: PaddingValues,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedLevelName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedLevel = selectedLevelName?.let { name ->
        LogLevel.entries.firstOrNull { it.name == name }
    }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }

    val filtered = remember(logs, selectedLevel) {
        if (selectedLevel == null) logs else logs.filter { it.level == selectedLevel }
    }
    val errorCount = remember(filtered) { filtered.count { it.level == LogLevel.ERROR } }
    val listState = rememberLazyListState()
    val headerItemCount = 1

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(headerItemCount + filtered.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag("logs-list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "logs-toolbar") {
            LogsToolbar(
                totalCount = filtered.size,
                errorCount = errorCount,
                selectedLevel = selectedLevel,
                autoScroll = autoScroll,
                onAutoScrollChange = { autoScroll = it },
                onSelectLevel = { level -> selectedLevelName = level?.name },
                onClearLogs = onClearLogs,
            )
        }
        if (filtered.isEmpty()) {
            item(key = "logs-empty") {
                if (logs.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.ReceiptLong,
                        title = stringResource(R.string.logs_empty_title),
                        description = stringResource(R.string.logs_empty_desc),
                    )
                } else {
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.logs_no_match_title),
                        description = stringResource(R.string.logs_no_match_desc),
                    )
                }
            }
        } else {
            itemsIndexed(
                items = filtered,
                key = { index, entry -> "${entry.timeMs}:$index" },
            ) { _, entry ->
                val itemKey = "${entry.timeMs}:${entry.message}"
                LogEntryCard(
                    entry = entry,
                    expanded = expandedKey == itemKey,
                    onToggle = { expandedKey = if (expandedKey == itemKey) null else itemKey },
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
private fun LogsToolbar(
    totalCount: Int,
    errorCount: Int,
    selectedLevel: LogLevel?,
    autoScroll: Boolean,
    onAutoScrollChange: (Boolean) -> Unit,
    onSelectLevel: (LogLevel?) -> Unit,
    onClearLogs: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.logs_count, totalCount, errorCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.logs_auto_scroll),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = autoScroll,
                onCheckedChange = onAutoScrollChange,
                modifier = Modifier.testTag("logs-auto-scroll"),
            )
            IconButton(
                onClick = onClearLogs,
                modifier = Modifier.testTag("logs-clear"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteSweep,
                    contentDescription = stringResource(R.string.logs_clear),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedLevel == null,
                onClick = { onSelectLevel(null) },
                label = { Text(stringResource(R.string.logs_filter_all)) },
                modifier = Modifier.testTag("logs-level-all"),
            )
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = selectedLevel == level,
                    onClick = { onSelectLevel(level) },
                    label = { Text(stringResource(levelLabelRes(level))) },
                    modifier = Modifier.testTag("logs-level-${level.name}"),
                )
            }
        }
    }
}

@Composable
private fun LogEntryCard(
    entry: LogEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = MaterialTheme.shapes.medium,
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogLevelBadge(level = entry.level)
                Text(
                    text = UiFormat.timeOfDay(entry.timeMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(
                        durationMillis = M3Motion.Medium2,
                        easing = M3Motion.EmphasizedDecelerate,
                    ),
                    label = "logExpandArrow",
                )
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                entry.throwable?.let { throwable ->
                    Text(
                        text = throwable.stackTraceToString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogLevelBadge(level: LogLevel) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = levelContainerColor(level),
        contentColor = levelContentColor(level),
    ) {
        Text(
            text = stringResource(levelLabelRes(level)),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun levelLabelRes(level: LogLevel): Int = when (level) {
    LogLevel.DEBUG -> R.string.logs_level_debug
    LogLevel.INFO -> R.string.logs_level_info
    LogLevel.WARN -> R.string.logs_level_warn
    LogLevel.ERROR -> R.string.logs_level_error
}

@Composable
private fun levelContainerColor(level: LogLevel): Color = when (level) {
    LogLevel.DEBUG -> MaterialTheme.colorScheme.surfaceContainerHighest
    LogLevel.INFO -> MaterialTheme.colorScheme.primaryContainer
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiaryContainer
    LogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun levelContentColor(level: LogLevel): Color = when (level) {
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
    LogLevel.WARN -> MaterialTheme.colorScheme.onTertiaryContainer
    LogLevel.ERROR -> MaterialTheme.colorScheme.onErrorContainer
}
