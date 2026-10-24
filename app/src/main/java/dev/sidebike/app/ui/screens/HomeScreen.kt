@file:OptIn(ExperimentalMaterial3Api::class)

package dev.sidebike.app.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sidebike.app.R
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.ui.components.EmptyState
import dev.sidebike.app.ui.components.InfoBanner
import dev.sidebike.app.ui.components.RunControlButton
import dev.sidebike.app.ui.components.SectionHeader
import dev.sidebike.app.ui.components.StatTile
import dev.sidebike.app.ui.components.StatusPill
import dev.sidebike.app.ui.components.StatusVisuals
import dev.sidebike.app.ui.components.UiFormat
import dev.sidebike.app.ui.motion.M3Motion
import dev.sidebike.app.ui.motion.m3ExpandIn
import dev.sidebike.app.ui.motion.m3FadeThrough
import dev.sidebike.app.ui.motion.m3FadeThroughResize
import dev.sidebike.app.ui.motion.m3ShrinkOut
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.TrafficStats

/**
 * 首页：状态 Hero（RunStatus + 运行时长 + 错误）+ 启动/停止控件 + 实时统计 + 快捷操作 + 最近日志。
 *
 * 动效（详见 docs/design/03-motion-spec.md）：
 * - 状态切换：Hero 容器色/圆角 `animate*AsState`（Standard / EmphasizedDecelerate）+ 标题 `AnimatedContent` fade-through
 * - 启动/停止按钮：颜色 morph + 文案 fade-through + 进度指示器展开
 * - 统计数字：`AnimatedCounterText`（Long 滚动，EmphasizedDecelerate）
 * - 错误横幅 / 进度条：`AnimatedVisibility`（expand/shrink，Emphasized）
 */
@Composable
fun HomeScreen(
    state: SidebikeUiState,
    recentLogs: List<LogEntry>,
    contentPadding: PaddingValues,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenCertGuide: () -> Unit,
    onToggleMitm: (Boolean) -> Unit,
    onRefreshCa: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home-list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "status-hero") {
            StatusHeroCard(state = state, onRefreshCa = onRefreshCa, onOpenCertGuide = onOpenCertGuide)
        }
        item(key = "run-control") {
            RunControlButton(status = state.status, onStart = onStart, onStop = onStop)
        }
        item(key = "capability-note") {
            CapabilityNote()
        }
        if (hasEnvironmentWarnings(state)) {
            item(key = "environment-warnings") {
                HomeWarnings(state = state)
            }
        }
        item(key = "stats-header") {
            SectionHeader(
                title = stringResource(R.string.home_section_stats),
                trailing = {
                    Text(
                        text = uptimeLabel(state),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        item(key = "stats-grid") {
            StatsGrid(state.stats)
        }
        item(key = "actions-header") {
            SectionHeader(title = stringResource(R.string.home_section_actions))
        }
        item(key = "quick-actions") {
            QuickActionsCard(
                mitmEnabled = state.config.proxy.mitmEnabled || state.config.mitm.enabled,
                caInstalled = state.caInstalled,
                onToggleMitm = onToggleMitm,
                onOpenRules = onOpenRules,
                onOpenLogs = onOpenLogs,
                onOpenCertGuide = onOpenCertGuide,
            )
        }
        item(key = "recent-header") {
            SectionHeader(
                title = stringResource(R.string.home_section_recent_logs),
                trailing = {
                    TextButton(onClick = onOpenLogs) {
                        Text(text = stringResource(R.string.home_view_all))
                    }
                },
            )
        }
        item(key = "recent-logs") {
            RecentLogsCard(logs = recentLogs, onOpenLogs = onOpenLogs)
        }
    }
}

@Composable
private fun uptimeLabel(state: SidebikeUiState): String = if (state.uptimeMs > 0L) {
    stringResource(R.string.home_uptime, UiFormat.uptime(state.uptimeMs))
} else {
    stringResource(R.string.home_uptime_idle)
}

// ---------------------------------------------------------------------------------------------
// Status hero
// ---------------------------------------------------------------------------------------------

@Composable
private fun StatusHeroCard(
    state: SidebikeUiState,
    onRefreshCa: () -> Unit,
    onOpenCertGuide: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = StatusVisuals.containerColor(state.status),
        animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.Standard),
        label = "heroContainerColor",
    )
    val contentColor by animateColorAsState(
        targetValue = StatusVisuals.contentColor(state.status),
        animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.Standard),
        label = "heroContentColor",
    )
    val accentColor by animateColorAsState(
        targetValue = StatusVisuals.accentColor(state.status),
        animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.Standard),
        label = "heroAccentColor",
    )
    // MD3 shape token morph: idle/error = large(16dp)，running/busy = extraLarge(28dp)
    val cornerRadius by animateDpAsState(
        targetValue = if (state.status == RunStatus.RUNNING || state.isBusy) 28.dp else 16.dp,
        animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.EmphasizedDecelerate),
        label = "heroCornerRadius",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val pulsing = state.status == RunStatus.RUNNING || state.isBusy
                StatusPill(
                    label = stringResource(StatusVisuals.labelRes(state.status)),
                    containerColor = accentColor.copy(alpha = 0.16f),
                    contentColor = accentColor,
                    dotColor = accentColor,
                    dotAlpha = rememberPulseAlpha(pulsing),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = uptimeLabel(state),
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor.copy(alpha = 0.8f),
                )
                AnimatedVisibility(visible = state.isBusy, enter = m3ExpandIn(), exit = m3ShrinkOut()) {
                    ProgressSpinner(color = accentColor)
                }
            }

            AnimatedContent(
                targetState = state.status,
                transitionSpec = { m3FadeThrough() },
                label = "heroStatusContent",
            ) { status ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = StatusVisuals.icon(status),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = accentColor,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(StatusVisuals.labelRes(status)),
                            style = MaterialTheme.typography.headlineSmall,
                            color = contentColor,
                        )
                        Text(
                            text = state.statusMessage.ifBlank {
                                stringResource(StatusVisuals.descriptionRes(status))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    label = stringResource(
                        R.string.home_chip_vpn,
                        stringResource(if (state.vpnActive) R.string.state_connected else R.string.state_disconnected),
                    ),
                    containerColor = contentColor.copy(alpha = 0.10f),
                    contentColor = contentColor,
                    leadingDot = false,
                )
                StatusPill(
                    label = stringResource(
                        R.string.home_chip_proxy,
                        if (state.proxyRunning && state.effectiveProxyPort > 0) {
                            state.effectiveProxyPort
                        } else if (state.stats.proxyPort > 0) {
                            state.stats.proxyPort
                        } else {
                            state.config.proxy.port
                        },
                    ),
                    containerColor = contentColor.copy(alpha = 0.10f),
                    contentColor = contentColor,
                    leadingDot = false,
                )
                StatusPill(
                    label = stringResource(
                        R.string.home_chip_mitm,
                        stringResource(
                            if (state.config.proxy.mitmEnabled || state.config.mitm.enabled) {
                                R.string.state_on
                            } else {
                                R.string.state_off
                            },
                        ),
                    ),
                    containerColor = contentColor.copy(alpha = 0.10f),
                    contentColor = contentColor,
                    leadingDot = false,
                )
            }

            AnimatedVisibility(visible = state.isBusy, enter = m3ExpandIn(), exit = m3ShrinkOut()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = accentColor,
                    trackColor = contentColor.copy(alpha = 0.24f),
                )
            }

            AnimatedVisibility(
                visible = state.lastError != null,
                enter = m3ExpandIn(),
                exit = m3ShrinkOut(),
            ) {
                InfoBanner(
                    text = state.lastError.orEmpty(),
                    icon = Icons.Outlined.ErrorOutline,
                    containerColor = colorScheme.errorContainer,
                    contentColor = colorScheme.onErrorContainer,
                )
            }

            if (!state.caInstalled) {
                Text(
                    text = stringResource(
                        if (state.config.proxy.mitmEnabled || state.config.mitm.enabled) {
                            R.string.home_ca_not_installed
                        } else {
                            R.string.home_ca_not_generated
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenCertGuide),
                )
            } else {
                Text(
                    text = stringResource(R.string.home_ca_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                    modifier = Modifier.clickable(onClick = onRefreshCa),
                )
            }
        }
    }
}

@Composable
private fun ProgressSpinner(color: Color) {
    androidx.compose.material3.CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        color = color,
        strokeWidth = 2.dp,
    )
}

@Composable
private fun rememberPulseAlpha(active: Boolean): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = "statusPulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = M3Motion.ExtraLong1, easing = M3Motion.StandardDecelerate),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "statusPulseAlpha",
    )
    return alpha
}

// ---------------------------------------------------------------------------------------------
// Environment warnings (Private DNS / lockdown / system proxy / port fallback)
// ---------------------------------------------------------------------------------------------

/** 是否展示环境提示区域（无任何提示时不占用列表间距）。 */
fun hasEnvironmentWarnings(state: SidebikeUiState): Boolean =
    state.privateDnsActive ||
        state.lockdownDetected ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && state.systemProxyRegistered == false) ||
        (state.proxyRunning && state.effectiveProxyPort > 0 && state.effectiveProxyPort != state.config.proxy.port)

@Composable
private fun HomeWarnings(state: SidebikeUiState) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(visible = state.privateDnsActive, enter = m3ExpandIn(), exit = m3ShrinkOut()) {
            InfoBanner(
                text = stringResource(R.string.home_warn_private_dns),
                icon = Icons.Outlined.WarningAmber,
                containerColor = colorScheme.tertiaryContainer,
                contentColor = colorScheme.onTertiaryContainer,
            )
        }
        AnimatedVisibility(visible = state.lockdownDetected, enter = m3ExpandIn(), exit = m3ShrinkOut()) {
            InfoBanner(
                text = stringResource(R.string.home_warn_lockdown),
                icon = Icons.Outlined.WarningAmber,
                containerColor = colorScheme.errorContainer,
                contentColor = colorScheme.onErrorContainer,
            )
        }
        AnimatedVisibility(
            visible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && state.systemProxyRegistered == false,
            enter = m3ExpandIn(),
            exit = m3ShrinkOut(),
        ) {
            InfoBanner(
                text = stringResource(R.string.home_warn_system_proxy),
                icon = Icons.Outlined.WarningAmber,
                containerColor = colorScheme.tertiaryContainer,
                contentColor = colorScheme.onTertiaryContainer,
            )
        }
        AnimatedVisibility(
            visible = state.proxyRunning &&
                state.effectiveProxyPort > 0 &&
                state.effectiveProxyPort != state.config.proxy.port,
            enter = m3ExpandIn(),
            exit = m3ShrinkOut(),
        ) {
            InfoBanner(
                text = stringResource(
                    R.string.home_warn_port_fallback,
                    state.config.proxy.port,
                    state.effectiveProxyPort,
                ),
                icon = Icons.Outlined.WarningAmber,
                containerColor = colorScheme.tertiaryContainer,
                contentColor = colorScheme.onTertiaryContainer,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Capability note
// ---------------------------------------------------------------------------------------------

@Composable
private fun CapabilityNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.home_capability_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------------------------

@Composable
private fun StatsGrid(stats: TrafficStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = stringResource(R.string.stats_total_conn),
                value = stats.totalConnections,
                icon = Icons.Outlined.Link,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stats_active_conn),
                value = stats.activeConnections.toLong(),
                icon = Icons.Outlined.Bolt,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = stringResource(R.string.stats_bytes_up),
                value = stats.bytesUp,
                icon = Icons.Outlined.ArrowUpward,
                format = UiFormat::bytes,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stats_bytes_down),
                value = stats.bytesDown,
                icon = Icons.Outlined.ArrowDownward,
                format = UiFormat::bytes,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = stringResource(R.string.stats_dns_queries),
                value = stats.dnsQueries,
                icon = Icons.Outlined.Dns,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stats_avg_latency),
                value = stats.dnsAvgLatencyMs,
                icon = Icons.Outlined.Speed,
                format = UiFormat::latency,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Quick actions
// ---------------------------------------------------------------------------------------------

@Composable
private fun QuickActionsCard(
    mitmEnabled: Boolean,
    caInstalled: Boolean,
    onToggleMitm: (Boolean) -> Unit,
    onOpenRules: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenCertGuide: () -> Unit,
) {
    val listColors = ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        headlineColor = MaterialTheme.colorScheme.onSurface,
        supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        leadingIconColor = MaterialTheme.colorScheme.primary,
        trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_mitm)) },
                supportingContent = { Text(stringResource(R.string.home_mitm_desc)) },
                leadingContent = { Icon(Icons.Outlined.VerifiedUser, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = mitmEnabled,
                        onCheckedChange = onToggleMitm,
                        modifier = Modifier.testTag("home-mitm-switch"),
                    )
                },
                colors = listColors,
                modifier = Modifier.clickable { onToggleMitm(!mitmEnabled) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ActionListItem(
                icon = Icons.Outlined.Rule,
                headline = stringResource(R.string.home_action_rules),
                supporting = stringResource(R.string.nav_rules),
                colors = listColors,
                onClick = onOpenRules,
            )
            ActionListItem(
                icon = Icons.Outlined.Article,
                headline = stringResource(R.string.home_action_logs),
                supporting = stringResource(R.string.nav_logs),
                colors = listColors,
                onClick = onOpenLogs,
            )
            ActionListItem(
                icon = Icons.Outlined.VerifiedUser,
                headline = stringResource(R.string.home_action_cert),
                supporting = stringResource(
                    if (caInstalled) R.string.home_ca_installed else R.string.home_ca_not_installed,
                ),
                colors = listColors,
                onClick = onOpenCertGuide,
            )
        }
    }
}

@Composable
private fun ActionListItem(
    icon: ImageVector,
    headline: String,
    supporting: String,
    colors: androidx.compose.material3.ListItemColors,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        colors = colors,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// ---------------------------------------------------------------------------------------------
// Recent logs
// ---------------------------------------------------------------------------------------------

@Composable
private fun RecentLogsCard(logs: List<LogEntry>, onOpenLogs: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        AnimatedContent(
            targetState = logs.takeLast(3),
            transitionSpec = { m3FadeThroughResize() },
            label = "recentLogs",
        ) { recent ->
            if (recent.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.ReceiptLong,
                    title = stringResource(R.string.home_logs_empty_title),
                    description = stringResource(R.string.home_logs_empty_desc),
                    modifier = Modifier.clickable(onClick = onOpenLogs),
                )
            } else {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    recent.forEachIndexed { index, entry ->
                        RecentLogRow(entry = entry)
                        if (index != recent.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentLogRow(entry: LogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = levelColor(entry),
            modifier = Modifier.size(width = 4.dp, height = 28.dp),
        ) {}
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
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun levelColor(entry: LogEntry): Color = when (entry.level) {
    dev.sidebike.core.api.LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
    dev.sidebike.core.api.LogLevel.INFO -> MaterialTheme.colorScheme.primary
    dev.sidebike.core.api.LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    dev.sidebike.core.api.LogLevel.ERROR -> MaterialTheme.colorScheme.error
}
