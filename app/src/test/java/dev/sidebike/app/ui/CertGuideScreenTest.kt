package dev.sidebike.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.ui.preview.PreviewData
import dev.sidebike.app.ui.screens.CertGuideScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.1-alpha CA 页：三态文案、指纹、MD3 安装确认 Dialog、SAF 导出与刷新回调。 */
class CertGuideScreenTest : ComposeTestBase() {

    private var prepareCalls = 0
    private var exportCalls = 0
    private var refreshCalls = 0
    private var openSettingsCalls = 0
    private var copiedFingerprint: String? = null
    private val messages = mutableListOf<String>()

    private fun setCert(
        state: dev.sidebike.app.state.SidebikeUiState = PreviewData.state(caInstalled = false),
        fingerprint: String? = "AB:CD:EF:01",
        installStatus: CaInstallLaunchStatus = CaInstallLaunchStatus.LAUNCHED,
    ) {
        setScreen {
            CertGuideScreen(
                state = state,
                contentPadding = PaddingValues(0.dp),
                onPrepareInstall = {
                    prepareCalls++
                    CaInstallUiResult(
                        status = installStatus,
                        fingerprint = fingerprint,
                        subject = "CN=Dev-Sidebike CA",
                        message = if (installStatus == CaInstallLaunchStatus.FAILED) "安装失败" else "已打开安装器",
                    )
                },
                onLoadCaInfo = {
                    CaInstallUiResult(
                        status = CaInstallLaunchStatus.LAUNCHED,
                        fingerprint = fingerprint,
                        subject = "CN=Dev-Sidebike CA",
                    )
                },
                onExportCaToFile = { exportCalls++ },
                onRefreshCaInstalled = { refreshCalls++ },
                onOpenCaSettings = { openSettingsCalls++ },
                onOpenLockScreenSettings = { openSettingsCalls++ },
                onCopyFingerprint = { copiedFingerprint = it },
                onShowMessage = { messages += it },
            )
        }
    }

    @Test
    fun rendersHeroStepsFingerprintAndManualInstall() {
        setCert()

        composeRule.onNodeWithText("CA 证书状态").assertExists()
        composeRule.onNodeWithText("未安装").assertExists()
        composeRule.onNodeWithText("生成并安装证书").assertExists()
        composeRule.onNodeWithText("导出证书到文件（.crt）").assertExists()
        composeRule.onNodeWithText("安装步骤").assertExists()
        composeRule.onNodeWithTag("cert-list").performScrollToNode(hasText("手动安装", substring = true))
        composeRule.onNodeWithText("手动安装", substring = true).assertExists()
        composeRule.onAllNodesWithText("AB:CD:EF:01").onFirst().assertExists()
        composeRule.onAllNodesWithText("CN=Dev-Sidebike CA").onFirst().assertExists()
    }

    @Test
    fun installButtonOpensMd3ConfirmDialog() {
        setCert()

        composeRule.onNodeWithTag("cert-install-button").performClick()
        settle()
        composeRule.onNodeWithTag("cert-install-dialog").assertExists()
        composeRule.onNodeWithText("安装 CA 证书").assertExists()
        composeRule.onNodeWithText("需要锁屏密码", substring = true).assertExists()
    }

    @Test
    fun confirmInstallCallsControllerAndShowsRecheckStatus() {
        setCert()

        composeRule.onNodeWithTag("cert-install-button").performClick()
        settle()
        composeRule.onNodeWithTag("cert-install-confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { prepareCalls == 1 }
        settle()

        composeRule.onNodeWithText("需重新检测").assertExists()
        composeRule.onAllNodesWithText("未检测到证书", substring = true).onFirst().assertExists()
        assertTrue(messages.any { it.contains("已打开安装器") })
    }

    @Test
    fun lockScreenRequiredShowsGuidance() {
        setCert(installStatus = CaInstallLaunchStatus.LOCK_SCREEN_REQUIRED)

        composeRule.onNodeWithTag("cert-install-button").performClick()
        settle()
        composeRule.onNodeWithTag("cert-install-confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { prepareCalls == 1 }
        settle()

        composeRule.onAllNodesWithText("设置锁屏", substring = true).onFirst().assertExists()
        composeRule.onAllNodesWithText("去设置锁屏").onFirst().performClick()
        assertTrue(openSettingsCalls >= 1)
    }

    @Test
    fun exportAndRefreshInvokeCallbacks() {
        setCert()

        composeRule.onNodeWithTag("cert-export-file-button").performClick()
        assertEquals(1, exportCalls)

        composeRule.onNodeWithTag("cert-refresh-button").performClick()
        assertEquals(1, refreshCalls)
    }

    @Test
    fun copyFingerprintInvokesCallback() {
        setCert()

        composeRule.onNodeWithTag("cert-copy-fingerprint").performClick()
        assertEquals("AB:CD:EF:01", copiedFingerprint)
    }

    @Test
    fun installedStateShowsTrustedCopy() {
        setCert(state = PreviewData.state(caInstalled = true))

        composeRule.onNodeWithText("已安装").assertExists()
        composeRule.onNodeWithText("系统已信任", substring = true).assertExists()
    }

    @Test
    fun failedInstallShowsMessage() {
        setCert(installStatus = CaInstallLaunchStatus.FAILED)

        composeRule.onNodeWithTag("cert-install-button").performClick()
        settle()
        composeRule.onNodeWithTag("cert-install-confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { prepareCalls == 1 }
        composeRule.waitUntil(timeoutMillis = 5_000) { messages.any { it.contains("安装失败") } }
    }

    @Test
    fun notGeneratedStateStillRenders() {
        setCert(state = PreviewData.emptyState)

        composeRule.onNodeWithText("CA 证书状态").assertExists()
        composeRule.onNodeWithTag("cert-list").assertExists()
    }

    @Test
    fun openSettingsFallbackInvokesCallback() {
        setCert()

        composeRule.onAllNodesWithText("打开证书设置").onFirst().performClick()
        assertEquals(1, openSettingsCalls)
    }

    @Test
    fun runningStateRendersWithoutUptimeDependency() {
        setCert(state = PreviewData.state(status = RunStatus.RUNNING, caInstalled = false))

        composeRule.onNodeWithText("CA 证书状态").assertExists()
    }
}
