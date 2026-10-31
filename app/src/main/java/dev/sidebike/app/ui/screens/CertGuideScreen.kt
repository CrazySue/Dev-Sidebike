@file:OptIn(ExperimentalMaterial3Api::class)

package dev.sidebike.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sidebike.app.R
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.ui.CaInstallLaunchStatus
import dev.sidebike.app.ui.CaInstallUiResult
import dev.sidebike.app.ui.components.InfoBanner
import dev.sidebike.app.ui.components.SectionHeader
import dev.sidebike.app.ui.motion.LocalInfiniteAnimationsEnabled
import dev.sidebike.app.ui.motion.M3Motion
import dev.sidebike.app.ui.motion.m3ExpandIn
import dev.sidebike.app.ui.motion.m3FadeThrough
import dev.sidebike.app.ui.motion.m3ShrinkOut
import kotlinx.coroutines.launch

/** CA 证书在 UI 中的可见状态（0.1.1-alpha：新增「需重新检测」，避免取消安装后误报"未安装"）。 */
enum class CaStatus { INSTALLED, NOT_INSTALLED, NEEDS_RECHECK }

/**
 * 计算 CA 展示状态：
 * - 已安装 -> INSTALLED
 * - 曾经尝试安装但当前仍检测不到 -> NEEDS_RECHECK（提示"可能已取消/需重新检测"）
 * - 从未尝试 -> NOT_INSTALLED
 */
fun caStatusOf(caInstalled: Boolean, installAttempted: Boolean): CaStatus = when {
    caInstalled -> CaStatus.INSTALLED
    installAttempted -> CaStatus.NEEDS_RECHECK
    else -> CaStatus.NOT_INSTALLED
}

/**
 * CA 证书页（0.1.1-alpha item 3）：
 * - 标准 MD3 AlertDialog 说明 + 确认（默认 scrim，不再是"只有关闭按钮的黑遮罩"）；
 * - 流程：生成并安装 -> 启动系统安装 Intent；失败回退系统证书设置页；
 * - 展示 SHA-256 指纹、证书主体与三态文案；支持 SAF 导出 PEM 到文件；
 * - 手动安装步骤（设置 → 安全 → 加密与凭据 → 安装证书 → CA 证书）。
 */
@Composable
fun CertGuideScreen(
    state: SidebikeUiState,
    contentPadding: PaddingValues,
    onPrepareInstall: suspend () -> CaInstallUiResult,
    onLoadCaInfo: suspend () -> CaInstallUiResult,
    onExportCaToFile: () -> Unit,
    onRefreshCaInstalled: () -> Unit,
    onOpenCaSettings: () -> Unit,
    onOpenLockScreenSettings: () -> Unit = {},
    onCopyFingerprint: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var installAttempted by rememberSaveable { mutableStateOf(false) }
    var lockScreenRequired by rememberSaveable { mutableStateOf(false) }
    var showInstallDialog by rememberSaveable { mutableStateOf(false) }
    var fingerprint by rememberSaveable { mutableStateOf<String?>(null) }
    var subject by rememberSaveable { mutableStateOf<String?>(null) }
    var loadingInfo by remember { mutableStateOf(false) }
    val status = caStatusOf(
        caInstalled = state.caInstalled,
        installAttempted = installAttempted,
    )
    val mitmConfigured = state.config.proxy.mitmEnabled || state.config.mitm.enabled
    val mitmAutoDisabled = mitmConfigured && !state.caInstalled && !state.mitmActive

    // 页面进入时生成/读取 CA 信息（指纹、subject），用于展示与用户核对。
    LaunchedEffect(Unit) {
        if (fingerprint == null) {
            loadingInfo = true
            val info = onLoadCaInfo()
            fingerprint = info.fingerprint
            subject = info.subject
            loadingInfo = false
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("cert-list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "cert-hero") {
            CertHeroCard(
                status = status,
                loadingInfo = loadingInfo,
                onInstallClick = { showInstallDialog = true },
                onExportClick = onExportCaToFile,
                onRefreshClick = onRefreshCaInstalled,
                onOpenCaSettings = onOpenCaSettings,
            )
        }
        item(key = "cert-lock-screen-hint") {
            AnimatedVisibility(
                visible = lockScreenRequired,
                enter = m3ExpandIn(),
                exit = m3ShrinkOut(),
            ) {
                InfoBanner(
                    text = stringResource(R.string.cert_lock_screen_required_hint),
                    icon = Icons.Outlined.Info,
                    action = {
                        TextButton(onClick = onOpenLockScreenSettings) {
                            Text(stringResource(R.string.cert_go_set_lock_screen))
                        }
                    },
                )
            }
        }
        item(key = "cert-mitm-auto-disabled") {
            AnimatedVisibility(
                visible = mitmAutoDisabled,
                enter = m3ExpandIn(),
                exit = m3ShrinkOut(),
            ) {
                InfoBanner(
                    text = stringResource(R.string.cert_mitm_auto_disabled),
                    icon = Icons.Outlined.WarningAmber,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        item(key = "cert-cancelled-hint") {
            AnimatedVisibility(
                visible = status == CaStatus.NEEDS_RECHECK,
                enter = m3ExpandIn(),
                exit = m3ShrinkOut(),
            ) {
                InfoBanner(
                    text = stringResource(R.string.cert_cancelled_hint),
                    icon = Icons.Outlined.Info,
                    action = {
                        TextButton(onClick = onOpenCaSettings) {
                            Text(stringResource(R.string.cert_open_ca_settings))
                        }
                    },
                )
            }
        }
        item(key = "cert-fingerprint") {
            FingerprintCard(
                fingerprint = fingerprint,
                subject = subject,
                onCopyFingerprint = onCopyFingerprint,
            )
        }
        item(key = "cert-steps-header") {
            SectionHeader(title = stringResource(R.string.cert_steps_title))
        }
        item(key = "cert-step-1") { StepCard(1, stringResource(R.string.cert_step1_title), stringResource(R.string.cert_step1_desc)) }
        item(key = "cert-step-2") { StepCard(2, stringResource(R.string.cert_step2_title), stringResource(R.string.cert_step2_desc)) }
        item(key = "cert-step-3") { StepCard(3, stringResource(R.string.cert_step3_title), stringResource(R.string.cert_step3_desc)) }
        item(key = "cert-step-4") { StepCard(4, stringResource(R.string.cert_step4_title), stringResource(R.string.cert_step4_desc)) }
        item(key = "cert-manual") { ManualInstallCard(onOpenCaSettings = onOpenCaSettings) }
        item(key = "cert-limits") { LimitsCard() }
    }

    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.cert_install_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.cert_install_dialog_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.cert_lockscreen_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (fingerprint != null) {
                        Text(
                            text = stringResource(R.string.cert_fingerprint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = fingerprint.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showInstallDialog = false
                        scope.launch {
                            val result = onPrepareInstall()
                            fingerprint = result.fingerprint ?: fingerprint
                            subject = result.subject ?: subject
                            when (result.status) {
                                CaInstallLaunchStatus.LAUNCHED,
                                CaInstallLaunchStatus.FALLBACK_LAUNCHED,
                                -> {
                                    installAttempted = true
                                    lockScreenRequired = false
                                }

                                CaInstallLaunchStatus.LOCK_SCREEN_REQUIRED -> {
                                    lockScreenRequired = true
                                }

                                CaInstallLaunchStatus.NO_CA,
                                CaInstallLaunchStatus.FAILED,
                                -> Unit
                            }
                            result.message?.let(onShowMessage)
                        }
                    },
                    modifier = Modifier.testTag("cert-install-confirm"),
                ) {
                    Text(stringResource(R.string.cert_prepare_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            modifier = Modifier.testTag("cert-install-dialog"),
        )
    }
}

@Composable
private fun CertHeroCard(
    status: CaStatus,
    loadingInfo: Boolean,
    onInstallClick: () -> Unit,
    onExportClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onOpenCaSettings: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = heroContainerColor(status),
        animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.Standard),
        label = "certHeroContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = heroContentColor(status),
        animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.Standard),
        label = "certHeroContent",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = accentColor(status),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.cert_hero_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                    AnimatedContent(
                        targetState = status,
                        transitionSpec = { m3FadeThrough() },
                        label = "certStatusText",
                    ) { targetStatus ->
                        Text(
                            text = stringResource(statusLabelRes(targetStatus)),
                            style = MaterialTheme.typography.headlineSmall,
                            color = contentColor,
                        )
                    }
                }
                if (loadingInfo) {
                    if (LocalInfiniteAnimationsEnabled.current) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = accentColor(status),
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { 0.6f },
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = accentColor(status),
                        )
                    }
                }
            }
            Text(
                text = stringResource(statusDescriptionRes(status)),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.85f),
            )
            Button(
                onClick = onInstallClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cert-install-button"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = stringResource(R.string.cert_prepare_install))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onExportClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cert-export-file-button"),
                ) {
                    Text(stringResource(R.string.cert_export_file))
                }
                TextButton(
                    onClick = onRefreshClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cert-refresh-button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(stringResource(R.string.settings_refresh_ca))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenCaSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(stringResource(R.string.cert_open_ca_settings))
                }
            }
        }
    }
}

@Composable
private fun FingerprintCard(
    fingerprint: String?,
    subject: String?,
    onCopyFingerprint: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cert-fingerprint-card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.cert_fingerprint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (fingerprint != null) {
                    IconButton(
                        onClick = { onCopyFingerprint(fingerprint) },
                        modifier = Modifier.testTag("cert-copy-fingerprint"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.cert_copy_fingerprint),
                        )
                    }
                }
            }
            Text(
                text = fingerprint ?: stringResource(R.string.cert_generating),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subject != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text = stringResource(R.string.cert_subject),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = subject,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun StepCard(index: Int, title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ManualInstallCard(onOpenCaSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = stringResource(R.string.cert_manual_steps_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.cert_manual_path),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenCaSettings) {
                Text(stringResource(R.string.cert_open_ca_settings))
            }
        }
    }
}

@Composable
private fun LimitsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = stringResource(R.string.cert_limits_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.cert_limits_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun statusLabelRes(status: CaStatus): Int = when (status) {
    CaStatus.INSTALLED -> R.string.cert_status_installed
    CaStatus.NOT_INSTALLED -> R.string.cert_status_not_installed
    CaStatus.NEEDS_RECHECK -> R.string.cert_status_needs_recheck
}

private fun statusDescriptionRes(status: CaStatus): Int = when (status) {
    CaStatus.INSTALLED -> R.string.cert_hero_desc_installed
    CaStatus.NOT_INSTALLED -> R.string.cert_hero_desc_not_installed
    CaStatus.NEEDS_RECHECK -> R.string.cert_hero_desc_needs_recheck
}

@Composable
private fun heroContainerColor(status: CaStatus): Color = when (status) {
    CaStatus.INSTALLED -> MaterialTheme.colorScheme.primaryContainer
    CaStatus.NOT_INSTALLED, CaStatus.NEEDS_RECHECK -> MaterialTheme.colorScheme.tertiaryContainer
}

@Composable
private fun heroContentColor(status: CaStatus): Color = when (status) {
    CaStatus.INSTALLED -> MaterialTheme.colorScheme.onPrimaryContainer
    CaStatus.NOT_INSTALLED, CaStatus.NEEDS_RECHECK -> MaterialTheme.colorScheme.onTertiaryContainer
}

@Composable
private fun accentColor(status: CaStatus): Color = when (status) {
    CaStatus.INSTALLED -> MaterialTheme.colorScheme.primary
    CaStatus.NOT_INSTALLED, CaStatus.NEEDS_RECHECK -> MaterialTheme.colorScheme.tertiary
}

/**
 * 兼容旧调用方（QA 截图测试）：旧版 onInstallCa/onExportCa/onOpenSettings 回调签名。
 * 新代码请使用 [CertGuideScreen] 的 actions 版参数（0.1.1-alpha）。
 */
@Composable
fun CertGuideScreen(
    state: SidebikeUiState,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onInstallCa: () -> Unit,
    onExportCa: () -> Unit,
    onRefreshCaInstalled: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CertGuideScreen(
        state = state,
        contentPadding = contentPadding,
        onPrepareInstall = {
            onInstallCa()
            CaInstallUiResult(CaInstallLaunchStatus.NO_CA)
        },
        onLoadCaInfo = { CaInstallUiResult(CaInstallLaunchStatus.NO_CA) },
        onExportCaToFile = onExportCa,
        onRefreshCaInstalled = onRefreshCaInstalled,
        onOpenCaSettings = onOpenSettings,
        onOpenLockScreenSettings = onOpenSettings,
        onCopyFingerprint = {},
        onShowMessage = {},
        modifier = modifier,
    )
}