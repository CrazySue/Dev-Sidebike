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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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

    private fun setSettings(
        config: SidebikeConfig = PreviewData.config,
        caInstalled: Boolean = false,
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
}
