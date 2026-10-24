@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.sidebike.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sidebike.app.R
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.ui.components.InfoBanner
import dev.sidebike.app.ui.components.SectionHeader
import dev.sidebike.app.ui.motion.M3Motion
import dev.sidebike.app.ui.motion.m3ExpandIn
import dev.sidebike.app.ui.motion.m3FadeThrough
import dev.sidebike.app.ui.motion.m3ShrinkOut
import dev.sidebike.app.ui.motion.m3StaggerDelayMillis

/** CA 证书在 UI 中的三种可见状态。 */
enum class CaStatus { INSTALLED, NOT_INSTALLED, NOT_GENERATED }

fun caStatusOf(caInstalled: Boolean, mitmEnabled: Boolean): CaStatus = when {
    caInstalled -> CaStatus.INSTALLED
    mitmEnabled -> CaStatus.NOT_INSTALLED
    else -> CaStatus.NOT_GENERATED
}

/**
 * CA 证书安装引导页：状态 Hero + 安装/导出/重新检测 + 四步安装指引 + 能力边界说明。
 *
 * 动效：Hero 容器色 `animateColorAsState`（Standard）；状态文案 `AnimatedContent` fade-through；
 * 步骤卡片错峰入场（stagger = Short1 递增 + EmphasizedDecelerate）；提示横幅 `AnimatedVisibility`。
 */
@Composable
fun CertGuideScreen(
    state: SidebikeUiState,
    contentPadding: PaddingValues,
    onInstallCa: () -> Unit,
    onExportCa: () -> Unit,
    onRefreshCaInstalled: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mitmEnabled = state.config.proxy.mitmEnabled || state.config.mitm.enabled
    val status = caStatusOf(caInstalled = state.caInstalled, mitmEnabled = mitmEnabled)
    val steps = listOf(
        stringResource(R.string.cert_step1_title) to stringResource(R.string.cert_step1_desc),
        stringResource(R.string.cert_step2_title) to stringResource(R.string.cert_step2_desc),
        stringResource(R.string.cert_step3_title) to stringResource(R.string.cert_step3_desc),
        stringResource(R.string.cert_step4_title) to stringResource(R.string.cert_step4_desc),
    )

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
                onInstallCa = onInstallCa,
                onExportCa = onExportCa,
                onRefreshCaInstalled = onRefreshCaInstalled,
            )
        }
        item(key = "cert-hint") {
            AnimatedVisibility(visible = !mitmEnabled, enter = m3ExpandIn(), exit = m3ShrinkOut()) {
                InfoBanner(
                    text = stringResource(R.string.cert_generated_hint),
                    icon = Icons.Outlined.Info,
                    action = {
                        TextButton(onClick = onOpenSettings) {
                            Text(stringResource(R.string.cert_open_settings))
                        }
                    },
                )
            }
        }
        item(key = "cert-steps-header") {
            SectionHeader(title = stringResource(R.string.cert_steps_title))
        }
        itemsIndexed(items = steps, key = { index, _ -> "cert-step-$index" }) { index, step ->
            StaggeredStep(index = index, title = step.first, description = step.second)
        }
        item(key = "cert-limits") {
            LimitsCard()
        }
    }
}

@Composable
private fun CertHeroCard(
    status: CaStatus,
    onInstallCa: () -> Unit,
    onExportCa: () -> Unit,
    onRefreshCaInstalled: () -> Unit,
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
            }
            Text(
                text = stringResource(statusDescriptionRes(status)),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.85f),
            )
            Button(
                onClick = onInstallCa,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = stringResource(R.string.cert_install))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onExportCa,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cert_export))
                }
                TextButton(
                    onClick = onRefreshCaInstalled,
                    modifier = Modifier.weight(1f),
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
        }
    }
}

@Composable
private fun StaggeredStep(index: Int, title: String, description: String) {
    val visibleState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    val delayMillis = m3StaggerDelayMillis(index)
    val enter = fadeIn(
        animationSpec = tween(
            durationMillis = M3Motion.Medium2,
            delayMillis = delayMillis,
            easing = M3Motion.EmphasizedDecelerate,
        ),
    ) + slideInVertically(
        animationSpec = tween(
            durationMillis = M3Motion.Medium2,
            delayMillis = delayMillis,
            easing = M3Motion.EmphasizedDecelerate,
        ),
    ) { height -> height / 8 }
    AnimatedVisibility(visibleState = visibleState, enter = enter) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
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
                            text = (index + 1).toString(),
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
}

@Composable
private fun LimitsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
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
    CaStatus.NOT_GENERATED -> R.string.cert_status_not_generated
}

private fun statusDescriptionRes(status: CaStatus): Int = when (status) {
    CaStatus.INSTALLED -> R.string.cert_hero_desc_installed
    CaStatus.NOT_INSTALLED -> R.string.cert_hero_desc_not_installed
    CaStatus.NOT_GENERATED -> R.string.cert_hero_desc_not_generated
}

@Composable
private fun heroContainerColor(status: CaStatus): Color = when (status) {
    CaStatus.INSTALLED -> MaterialTheme.colorScheme.primaryContainer
    CaStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.tertiaryContainer
    CaStatus.NOT_GENERATED -> MaterialTheme.colorScheme.surfaceContainerHigh
}

@Composable
private fun heroContentColor(status: CaStatus): Color = when (status) {
    CaStatus.INSTALLED -> MaterialTheme.colorScheme.onPrimaryContainer
    CaStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.onTertiaryContainer
    CaStatus.NOT_GENERATED -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun accentColor(status: CaStatus): Color = when (status) {
    CaStatus.INSTALLED -> MaterialTheme.colorScheme.primary
    CaStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.tertiary
    CaStatus.NOT_GENERATED -> MaterialTheme.colorScheme.onSurfaceVariant
}
