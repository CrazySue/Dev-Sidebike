package dev.sidebike.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode


import androidx.compose.ui.unit.dp
import dev.sidebike.app.ui.preview.PreviewData
import dev.sidebike.app.ui.screens.SettingsScreen
import dev.sidebike.core.api.DnsMode
import dev.sidebike.core.api.SidebikeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenTest : ComposeTestBase() {

    private var lastConfig: SidebikeConfig? = null
    private var openCertCalls = 0
    private var refreshCalls = 0
    private var exportCalls = 0
    private var diagnosticsCalls = 0
    private var savedTextFile: Pair<String, String>? = null
    private var copiedText: String? = null
    private var openRulesCalls = 0
    private var openLogsCalls = 0
    private val messages = mutableListOf<String>()
    private var mitmToggleResult: dev.sidebike.app.controller.MitmToggleResult? = null

    private fun setSettings(
        config: SidebikeConfig = PreviewData.config,
        caInstalled: Boolean = false,
        diagnosticsRunning: Boolean = false,
    ) {
        setScreen {
            SettingsScreen(
                config = config,
                caInstalled = caInstalled,
                contentPadding = PaddingValues(0.dp),
                onConfigChange = { lastConfig = it },
                onOpenCertGuide = { openCertCalls++ },
                onRefreshCaInstalled = { refreshCalls++ },
                onExportCa = { exportCalls++ },
                diagnosticsRunning = diagnosticsRunning,
                onRunNetworkDiagnostics = {
                    diagnosticsCalls++
                    "Provider health: aliyun OK\nresolve github.com -> 1.2.3.4"
                },
                onSaveTextFile = { name, content -> savedTextFile = name to content },
                onCopyText = { copiedText = it },
                onOpenRules = { openRulesCalls++ },
                onOpenLogs = { openLogsCalls++ },
                onShowMessage = { messages += it },
                onSetMitmEnabled = {
                    mitmToggleResult ?: dev.sidebike.app.controller.MitmToggleResult(effective = it)
                },
            )
        }
    }

    private fun scrollToTag(tag: String) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(tag))
    }

    private fun scrollToText(text: String) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText(text))
    }

    @Test
    fun rendersProxyAndDnsSections() {
        setSettings()

        composeRule.onNodeWithText("代理服务").assertExists()
        composeRule.onNodeWithText("代理端口").assertExists()
        composeRule.onNodeWithText("31181").assertExists()
        composeRule.onNodeWithText("系统 HTTP 代理").assertExists()
        composeRule.onNodeWithText("DNS 解析").assertExists()
        composeRule.onNodeWithText("接管 DNS 查询").assertExists()
    }

    @Test
    fun themeModeSelectionPersists() {
        setSettings()

        scrollToTag("theme-light")
        composeRule.onNodeWithTag("theme-light").performClick()
        settle()

        assertEquals("light", lastConfig?.app?.themeMode)
    }

    @Test
    fun dynamicColorTogglePersists() {
        setSettings()

        scrollToTag("switch:dynamic-color")
        composeRule.onNodeWithTag("switch:dynamic-color").performClick()
        settle()

        assertEquals(false, lastConfig?.app?.dynamicColor)
    }

    @Test
    fun mitmToggleWritesBothContractFlags() {
        setSettings()

        scrollToTag("switch:mitm")
        composeRule.onNodeWithTag("switch:mitm").performClick()
        settle()

        val config = lastConfig
        assertTrue(config != null)
        assertEquals(false, config!!.proxy.mitmEnabled)
        assertEquals(false, config.mitm.enabled)
    }

    @Test
    fun autoStartTogglePersists() {
        setSettings()

        scrollToTag("switch:auto-start")
        composeRule.onNodeWithTag("switch:auto-start").performClick()
        settle()

        assertEquals(true, lastConfig?.app?.autoStartOnBoot)
    }

    @Test
    fun proxyPortDialogSaves() {
        setSettings()

        composeRule.onNodeWithTag("settings-proxy-port").performClick()
        settle()
        composeRule.onNodeWithText("修改代理端口").assertExists()
        composeRule.onNodeWithTag("port-input").assertExists()

        // 端口输入/校验逻辑由 PortValidationTest 覆盖；这里验证 Dialog -> onConfigChange 的接线。
        composeRule.onNodeWithTag("port-save").performClick()
        settle()

        assertEquals(31181, lastConfig?.proxy?.port)
    }

    @Test
    fun dnsModeSelectorPersists() {
        setSettings()

        scrollToTag("dns-mode-DOH_ONLY")
        composeRule.onNodeWithTag("dns-mode-DOH_ONLY").performClick()
        settle()

        assertEquals(DnsMode.DOH_ONLY, lastConfig?.dns?.mode)
    }

    @Test
    fun dnsProviderSwitchPersists() {
        setSettings()

        scrollToTag("provider-switch:aliyun")
        composeRule.onNodeWithTag("provider-switch:aliyun").performClick()
        settle()

        assertEquals(false, lastConfig?.dns?.providers?.get("aliyun")?.enabled)
    }

    @Test
    fun caGuideShortcutOpensGuide() {
        setSettings()

        scrollToText("证书指南")
        composeRule.onNodeWithText("证书指南").performClick()

        assertEquals(1, openCertCalls)
    }

    @Test
    fun caStatusReflectsInstalledFlag() {
        setSettings(caInstalled = true)

        scrollToText("已安装并信任")
        composeRule.onNodeWithText("已安装并信任").assertExists()
    }

    @Test
    fun caStatusSaysMitmDisabledWhenMitmOff() {
        val config = PreviewData.config.copy(
            proxy = PreviewData.config.proxy.copy(mitmEnabled = false),
            mitm = PreviewData.config.mitm.copy(enabled = false),
        )
        setSettings(config = config)

        scrollToText("MITM 未启用，无需安装证书")
        composeRule.onNodeWithText("MITM 未启用，无需安装证书").assertExists()
    }

    @Test
    fun aboutDialogShowsUpstreamLicense() {
        setSettings()

        scrollToTag("settings-about")
        composeRule.onNodeWithTag("settings-about").performClick()
        settle()

        composeRule.onAllNodesWithText("MPL-2.0", substring = true).onFirst().assertExists()
        composeRule.onAllNodesWithText("Dev-Sidecar", substring = true).onFirst().assertExists()
    }

    @Test
    fun licenseDialogShowsMplAttribution() {
        setSettings()

        scrollToTag("settings-licenses")
        composeRule.onNodeWithTag("settings-licenses").performClick()
        settle()

        composeRule.onAllNodesWithText("Material Design 3", substring = true).onFirst().assertExists()
    }

    @Test
    fun exportCaAndRefreshInvokeCallbacks() {
        setSettings()

        scrollToTag("switch:mitm")
        scrollToText("导出 CA 证书")
        composeRule.onNodeWithText("导出 CA 证书").performClick()
        assertEquals(1, exportCalls)

        composeRule.onNodeWithText("重新检测").performClick()
        assertEquals(1, refreshCalls)
    }

    // ---------------------------------------------------------------- 0.1.1-alpha additions

    @Test
    fun timeoutPresetPersistsProxyTimeouts() {
        setSettings()

        scrollToTag("timeout-fast")
        composeRule.onNodeWithTag("timeout-fast").performClick()
        settle()

        val proxy = lastConfig?.proxy
        assertEquals(6_000, proxy?.connectTimeoutMs)
        assertEquals(15_000, proxy?.readTimeoutMs)
        assertEquals(30_000, proxy?.idleTimeoutMs)
        assertTrue(messages.any { it.contains("超时预设") })
    }

    @Test
    fun hostsAddDialogPersistsHostsEntry() {
        setSettings()

        scrollToTag("settings-host-add")
        composeRule.onNodeWithTag("settings-host-add").performClick()
        settle()
        composeRule.onNodeWithTag("host-add-dialog").assertExists()
        setText("host-domain-input", "ads.example.com")
        setText("host-ip-input", "127.0.0.1")
        settle()
        composeRule.onNodeWithTag("host-add-confirm").performClick()
        settle()

        assertEquals("127.0.0.1", lastConfig?.dns?.hosts?.get("ads.example.com"))
    }

    @Test
    fun hostsRemoveIconUpdatesConfig() {
        val config = PreviewData.config.copy(
            dns = PreviewData.config.dns.copy(hosts = mapOf("ads.example.com" to "127.0.0.1")),
        )
        setSettings(config = config)

        scrollToTag("host-remove:ads.example.com")
        composeRule.onNodeWithTag("host-remove:ads.example.com").performClick()
        settle()

        assertEquals(null, lastConfig?.dns?.hosts?.get("ads.example.com"))
    }

    @Test
    fun runDiagnosticsShowsResultDialogAndSavesFile() {
        setSettings()

        scrollToTag("settings-run-diagnostics")
        composeRule.onNodeWithTag("settings-run-diagnostics").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { diagnosticsCalls == 1 }
        settle()

        composeRule.onNodeWithTag("diagnostics-dialog").assertExists()
        composeRule.onNodeWithText("Provider health", substring = true).assertExists()
        composeRule.onNodeWithTag("diagnostics-save").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { savedTextFile != null }
        assertTrue(savedTextFile!!.second.contains("Provider health"))
    }

    @Test
    fun diagnosticsCopyInvokesClipboardCallback() {
        setSettings()

        scrollToTag("settings-run-diagnostics")
        composeRule.onNodeWithTag("settings-run-diagnostics").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { diagnosticsCalls == 1 }
        settle()
        composeRule.onAllNodesWithText("复制").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { copiedText != null }
        assertTrue(copiedText!!.contains("Provider health"))
    }

    @Test
    fun dnsDetailsDialogShowsCounts() {
        setSettings()

        scrollToTag("settings-dns-details")
        composeRule.onNodeWithTag("settings-dns-details").performClick()
        settle()

        composeRule.onNodeWithTag("dns-details-dialog").assertExists()
        composeRule.onAllNodesWithText("Provider：", substring = true).onFirst().assertExists()
    }

    @Test
    fun mitmToggleRejectedWhenCaMissingShowsMessage() {
        mitmToggleResult = dev.sidebike.app.controller.MitmToggleResult(
            effective = false,
            message = "需要先安装 CA 证书",
        )
        setSettings(
            config = PreviewData.config.copy(
                proxy = PreviewData.config.proxy.copy(mitmEnabled = false),
                mitm = PreviewData.config.mitm.copy(enabled = false),
            ),
        )

        scrollToTag("switch:mitm")
        composeRule.onNodeWithTag("switch:mitm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { messages.isNotEmpty() }
        assertTrue(messages.any { it.contains("需要先安装 CA 证书") })
        assertEquals(null, lastConfig)
    }

    @Test
    fun settingsLinksOpenRulesAndLogs() {
        setSettings()

        scrollToTag("settings-open-rules")
        composeRule.onNodeWithTag("settings-open-rules").performClick()
        assertEquals(1, openRulesCalls)

        scrollToTag("settings-open-logs")
        composeRule.onNodeWithTag("settings-open-logs").performClick()
        assertEquals(1, openLogsCalls)
    }

    @Test
    fun aboutDialogMentionsRuleSourceLicense() {
        setSettings()

        scrollToTag("settings-about")
        composeRule.onNodeWithTag("settings-about").performClick()
        settle()

        composeRule.onNodeWithText("CC BY-SA 4.0", substring = true).assertExists()
    }
}
