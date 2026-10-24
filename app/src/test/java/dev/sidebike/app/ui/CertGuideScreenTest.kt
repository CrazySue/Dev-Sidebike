package dev.sidebike.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.ui.preview.PreviewData
import dev.sidebike.app.ui.screens.CertGuideScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class CertGuideScreenTest : ComposeTestBase() {

    private var installCalls = 0
    private var exportCalls = 0
    private var refreshCalls = 0
    private var settingsCalls = 0

    private fun setCert(state: SidebikeUiState) {
        setScreen {
            CertGuideScreen(
                state = state,
                contentPadding = PaddingValues(0.dp),
                onInstallCa = { installCalls++ },
                onExportCa = { exportCalls++ },
                onRefreshCaInstalled = { refreshCalls++ },
                onOpenSettings = { settingsCalls++ },
            )
        }
    }

    private fun scrollToText(text: String) {
        composeRule.onNodeWithTag("cert-list").performScrollToNode(hasText(text))
    }

    @Test
    fun rendersHeroAndInstallSteps() {
        setCert(PreviewData.state(caInstalled = false))

        composeRule.onNodeWithText("CA 证书状态").assertExists()
        composeRule.onNodeWithText("未安装").assertExists()
        composeRule.onNodeWithText("安装 CA 证书").assertExists()
        composeRule.onNodeWithText("导出 CA 证书").assertExists()

        scrollToText("安装步骤")
        composeRule.onNodeWithText("安装步骤").assertExists()

        scrollToText("4. 返回验证")
        composeRule.onNodeWithText("4. 返回验证").assertExists()
    }

    @Test
    fun installButtonInvokesCallback() {
        setCert(PreviewData.state(caInstalled = false))

        composeRule.onNodeWithText("安装 CA 证书").performClick()
        assertEquals(1, installCalls)
    }

    @Test
    fun exportButtonInvokesCallback() {
        setCert(PreviewData.state(caInstalled = false))

        composeRule.onNodeWithText("导出 CA 证书").performClick()
        assertEquals(1, exportCalls)
    }

    @Test
    fun refreshButtonInvokesCallback() {
        setCert(PreviewData.state(caInstalled = false))

        composeRule.onNodeWithText("重新检测").performClick()
        assertEquals(1, refreshCalls)
    }

    @Test
    fun notGeneratedStateShowsHintAndSettingsShortcut() {
        setCert(PreviewData.emptyState)

        composeRule.onNodeWithText("尚未生成").assertExists()
        composeRule.onNodeWithText("尚未生成 CA", substring = true).assertExists()

        composeRule.onNodeWithText("打开设置").performClick()
        assertEquals(1, settingsCalls)
    }

    @Test
    fun installedStateShowsTrustedDescription() {
        setCert(PreviewData.state(caInstalled = true))

        composeRule.onNodeWithText("已安装").assertExists()
        composeRule.onNodeWithText("系统已信任", substring = true).assertExists()
    }

    @Test
    fun limitsSectionExplainsCapabilityBoundary() {
        setCert(PreviewData.state(caInstalled = false))

        scrollToText("能力边界")
        composeRule.onNodeWithText("能力边界").assertExists()
        composeRule.onNodeWithText("Android 7", substring = true).assertExists()
    }
}
