@file:OptIn(ExperimentalMaterial3Api::class)

package dev.sidebike.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sidebike.app.BuildConfig
import dev.sidebike.app.R
import dev.sidebike.app.ui.components.SectionHeader
import dev.sidebike.app.ui.components.StatusPill
import dev.sidebike.app.ui.components.mitmCaSettingsLabelRes
import dev.sidebike.app.ui.components.mitmCaStatusOf
import dev.sidebike.app.ui.motion.LocalInfiniteAnimationsEnabled
import dev.sidebike.app.ui.motion.M3Motion
import dev.sidebike.app.ui.theme.ThemeMode
import dev.sidebike.core.api.DnsMode
import dev.sidebike.core.api.DnsProvider
import dev.sidebike.core.api.SidebikeConfig

/**
 * 设置页：代理端口 / DNS 模式与 Provider / MITM 与 CA / 通用 / 外观（主题+动态取色）/ 关于与许可。
 *
 * 动效：Switch / SegmentedButton / AlertDialog 使用 MD3 组件内建动画；
 * 开关行背景色使用 `animateColorAsState`（Standard）；端口编辑使用 MD3 AlertDialog（内建进入/退出动画）。
 */
@Composable
fun SettingsScreen(
    config: SidebikeConfig,
    caInstalled: Boolean,
    contentPadding: PaddingValues,
    onConfigChange: (SidebikeConfig) -> Unit,
    onOpenCertGuide: () -> Unit,
    onRefreshCaInstalled: () -> Unit,
    onExportCa: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPortDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showLicensesDialog by rememberSaveable { mutableStateOf(false) }
    val mitmEnabled = config.proxy.mitmEnabled || config.mitm.enabled

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings-list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "proxy-header") { SectionHeader(title = stringResource(R.string.settings_section_proxy)) }
        item(key = "proxy-card") {
            SettingsCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_proxy_port)) },
                    supportingContent = { Text(stringResource(R.string.settings_proxy_port_desc)) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = config.proxy.port.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = settingsItemColors(),
                    modifier = Modifier
                        .clickable { showPortDialog = true }
                        .testTag("settings-proxy-port"),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_system_proxy),
                    description = stringResource(R.string.settings_system_proxy_desc),
                    checked = config.proxy.setSystemProxy,
                    tag = "switch:system-proxy",
                    onCheckedChange = { enabled ->
                        onConfigChange(config.copy(proxy = config.proxy.copy(setSystemProxy = enabled)))
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_bypass_domestic),
                    description = stringResource(R.string.settings_bypass_domestic_desc),
                    checked = config.proxy.bypassDomestic,
                    tag = "switch:bypass-domestic",
                    onCheckedChange = { enabled ->
                        onConfigChange(config.copy(proxy = config.proxy.copy(bypassDomestic = enabled)))
                    },
                )
            }
        }

        item(key = "dns-header") { SectionHeader(title = stringResource(R.string.settings_section_dns)) }
        item(key = "dns-card") {
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_dns_enabled),
                    description = stringResource(R.string.settings_dns_enabled_desc),
                    checked = config.dns.enabled,
                    tag = "switch:dns-enabled",
                    onCheckedChange = { enabled ->
                        onConfigChange(config.copy(dns = config.dns.copy(enabled = enabled)))
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_dns_mode),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        DnsMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = config.dns.mode == mode,
                                onClick = {
                                    onConfigChange(config.copy(dns = config.dns.copy(mode = mode)))
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = DnsMode.entries.size,
                                ),
                                modifier = Modifier.testTag("dns-mode-${mode.name}"),
                            ) {
                                Text(stringResource(dnsModeLabelRes(mode)))
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_dns_vpn_address)) },
                    trailingContent = {
                        Text(
                            text = config.dns.vpnDnsAddress,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = settingsItemColors(),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_dns_providers),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    config.dns.providers.forEach { (name, provider) ->
                        DnsProviderRow(
                            name = name,
                            provider = provider,
                            onEnabledChange = { enabled ->
                                val providers = config.dns.providers.toMutableMap()
                                providers[name] = provider.copy(enabled = enabled)
                                onConfigChange(config.copy(dns = config.dns.copy(providers = providers)))
                            },
                        )
                    }
                }
            }
        }

        item(key = "mitm-header") { SectionHeader(title = stringResource(R.string.settings_section_mitm)) }
        item(key = "mitm-card") {
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_mitm),
                    description = stringResource(R.string.settings_mitm_desc),
                    checked = mitmEnabled,
                    tag = "switch:mitm",
                    onCheckedChange = { enabled ->
                        onConfigChange(
                            config.copy(
                                proxy = config.proxy.copy(mitmEnabled = enabled),
                                mitm = config.mitm.copy(enabled = enabled),
                            ),
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_ca_status)) },
                    supportingContent = {
                        Text(
                            text = stringResource(
                                mitmCaSettingsLabelRes(
                                    mitmCaStatusOf(mitmEnabled = mitmEnabled, caInstalled = caInstalled),
                                ),
                            ),
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = onOpenCertGuide) {
                            Text(stringResource(R.string.settings_open_cert))
                        }
                    },
                    colors = settingsItemColors(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onRefreshCaInstalled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.settings_refresh_ca))
                    }
                    Button(
                        onClick = onExportCa,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.cert_export))
                    }
                }
            }
        }

        item(key = "general-header") { SectionHeader(title = stringResource(R.string.settings_section_general)) }
        item(key = "general-card") {
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_auto_start),
                    description = stringResource(R.string.settings_auto_start_desc),
                    checked = config.app.autoStartOnBoot,
                    tag = "switch:auto-start",
                    onCheckedChange = { enabled ->
                        onConfigChange(config.copy(app = config.app.copy(autoStartOnBoot = enabled)))
                    },
                )
            }
        }

        item(key = "appearance-header") {
            SectionHeader(title = stringResource(R.string.settings_section_appearance))
        }
        item(key = "appearance-card") {
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ThemeMode.options.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = (config.app.themeMode.ifBlank { ThemeMode.SYSTEM }) == mode,
                                onClick = {
                                    onConfigChange(config.copy(app = config.app.copy(themeMode = mode)))
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemeMode.options.size,
                                ),
                                modifier = Modifier.testTag("theme-$mode"),
                            ) {
                                Text(stringResource(themeModeLabelRes(mode)))
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    description = stringResource(R.string.settings_dynamic_color_desc),
                    checked = config.app.dynamicColor,
                    tag = "switch:dynamic-color",
                    onCheckedChange = { enabled ->
                        onConfigChange(config.copy(app = config.app.copy(dynamicColor = enabled)))
                    },
                )
            }
        }

        item(key = "about-header") { SectionHeader(title = stringResource(R.string.settings_section_about)) }
        item(key = "about-card") {
            SettingsCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_about)) },
                    supportingContent = {
                        Text(stringResource(R.string.settings_about_desc, BuildConfig.VERSION_NAME))
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = settingsItemColors(),
                    modifier = Modifier
                        .clickable { showAboutDialog = true }
                        .testTag("settings-about"),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_licenses)) },
                    supportingContent = { Text(stringResource(R.string.settings_licenses_desc)) },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = settingsItemColors(),
                    modifier = Modifier
                        .clickable { showLicensesDialog = true }
                        .testTag("settings-licenses"),
                )
                Text(
                    text = stringResource(R.string.settings_version_footer, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
        }
    }

    if (showPortDialog) {
        PortDialog(
            currentPort = config.proxy.port,
            onDismiss = { showPortDialog = false },
            onConfirm = { port ->
                onConfigChange(config.copy(proxy = config.proxy.copy(port = port)))
                showPortDialog = false
            },
        )
    }
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
    if (showLicensesDialog) {
        LicensesDialog(onDismiss = { showLicensesDialog = false })
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        content = content,
    )
}

@Composable
private fun settingsItemColors() = ListItemDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    headlineColor = MaterialTheme.colorScheme.onSurface,
    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.Standard),
        label = "settingsSwitchRowColor",
    )
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
            containerColor = containerColor,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}

@Composable
private fun DnsProviderRow(
    name: String,
    provider: DnsProvider,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StatusPill(
                    label = provider.protocol.name,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    leadingDot = false,
                )
            }
            Text(
                text = buildString {
                    append(provider.server)
                    provider.sni?.let { append(" · SNI ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = provider.enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.testTag("provider-switch:$name"),
        )
    }
}

/** 代理端口合法性（纯函数，供 UI 与单测共用）。 */
fun isValidProxyPort(port: Int?): Boolean = port != null && port in 1024..65535

/** 输入过滤：只保留数字并限制 5 位（纯函数，便于单测）。 */
fun normalizeProxyPortInput(input: String): String = input.filter { it.isDigit() }.take(5)

@Composable
private fun PortDialog(
    currentPort: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var portText by remember { mutableStateOf(currentPort.toString()) }
    val port = portText.toIntOrNull()
    val valid = isValidProxyPort(port)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.port_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.port_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (LocalInfiniteAnimationsEnabled.current) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { input -> portText = normalizeProxyPortInput(input) },
                        singleLine = true,
                        isError = portText.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = {
                            if (portText.isNotEmpty() && !valid) {
                                Text(stringResource(R.string.port_invalid))
                            }
                        },
                        modifier = Modifier.testTag("port-input"),
                    )
                } else {
                    // 测试/静态渲染（LocalInfiniteAnimationsEnabled=false）：TextInput 的 IME/光标
                    // 无限动画会让 Robolectric 永远无法 idle，改用只读文本渲染端口值。
                    Text(
                        text = portText,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("port-input"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { port?.let(onConfirm) },
                enabled = valid,
                modifier = Modifier.testTag("port-save"),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(text = stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.about_upstream_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(text = stringResource(R.string.about_upstream_body), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.about_tech_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(text = stringResource(R.string.about_tech_body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_licenses)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_license_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.about_license_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.about_upstream_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.about_upstream_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

private fun dnsModeLabelRes(mode: DnsMode): Int = when (mode) {
    DnsMode.SMART -> R.string.dns_mode_smart
    DnsMode.DOH_ONLY -> R.string.dns_mode_doh
    DnsMode.SINGLE -> R.string.dns_mode_single
}

private fun themeModeLabelRes(mode: String): Int = when (mode) {
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    else -> R.string.theme_system
}
