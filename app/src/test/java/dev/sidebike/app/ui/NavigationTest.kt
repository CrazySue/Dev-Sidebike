package dev.sidebike.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.ui.motion.LocalInfiniteAnimationsEnabled
import dev.sidebike.app.ui.preview.PreviewData
import dev.sidebike.app.ui.theme.SidebikeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 根导航 + controller 交互的集成测试（Robolectric + Compose）：
 * - 5 个 NavigationBar destination 均可切换（用 `nav:<route>` testTag 精确定位，避免与快捷操作文案歧义）
 * - 启动/停止/配置更新/导出/CA 请求都会转发给 fake controller
 * - 空数据状态下不崩溃
 */
class NavigationTest : ComposeTestBase() {

    private lateinit var fake: FakeSidebikeController
    private lateinit var viewModel: SidebikeViewModel

    @Before
    fun setUp() {
        // 默认 IDLE + 有配置/日志：首页按钮为「启动服务」，规则/日志页有真实数据
        fake = FakeSidebikeController(initialState = PreviewData.state(status = RunStatus.IDLE))
        installFakeController(fake)
        viewModel = SidebikeViewModel(ApplicationProvider.getApplicationContext())
    }

    private fun setApp() {
        composeRule.setContent {
            CompositionLocalProvider(LocalInfiniteAnimationsEnabled provides false) {
                SidebikeTheme(dynamicColor = false) {
                    SidebikeApp(viewModel = viewModel)
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun clickNav(route: String) {
        composeRule.onNodeWithTag("nav:$route").performClick()
        composeRule.waitForIdle()
    }

    private fun assertTextExists(text: String, substring: Boolean = false) {
        composeRule.onNodeWithText(text, substring = substring).assertExists()
    }

    @Test
    fun navigatesAcrossAllFiveDestinations() {
        setApp()

        assertTextExists("启动服务")

        clickNav("rules")
        assertTextExists("共 4 个域名", substring = true)

        clickNav("logs")
        assertTextExists("共 5 条 · 错误 1 条")

        clickNav("settings")
        assertTextExists("代理端口")

        clickNav("cert")
        assertTextExists("安装步骤")

        clickNav("home")
        assertTextExists("启动服务")
    }

    @Test
    fun startAndStopForwardToController() {
        setApp()

        composeRule.onNodeWithText("启动服务").performClick()
        assertEquals(1, fake.startCalls)

        fake.setState(PreviewData.state(status = RunStatus.RUNNING))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("停止服务").performClick()
        assertEquals(1, fake.stopCalls)
    }

    @Test
    fun emptyStateRendersAcrossDestinations() {
        fake.setState(PreviewData.emptyState)
        fake.setLogs(emptyList())
        setApp()

        assertTextExists("启动服务")

        clickNav("rules")
        assertTextExists("暂无拦截规则")

        clickNav("logs")
        assertTextExists("暂无日志")

        clickNav("cert")
        assertTextExists("尚未生成")
    }

    @Test
    fun logsExportFabInvokesDiagnosticsExport() {
        setApp()

        clickNav("logs")
        composeRule.onNodeWithTag("logs-export-fab").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { fake.exportCalls == 1 }
        assertEquals(1, fake.exportCalls)
    }

    @Test
    fun certGuideInstallRequestsCaPemFromController() {
        setApp()

        clickNav("cert")
        composeRule.onNodeWithText("安装 CA 证书").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { fake.caPemRequests == 1 }
        assertEquals(1, fake.caPemRequests)
    }

    @Test
    fun rulesToggleForwardsConfigUpdateToController() {
        setApp()

        clickNav("rules")
        composeRule.onNodeWithTag("rules-list").performScrollToNode(hasTestTag("feature-switch:mitm"))
        composeRule.onNodeWithTag("feature-switch:mitm").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { fake.updateConfigCalls == 1 }
        val config = fake.lastConfig
        assertTrue(config != null)
        assertEquals(false, config!!.mitm.enabled)
    }

    @Test
    fun settingsThemeChangeForwardsConfigUpdate() {
        setApp()

        clickNav("settings")
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("theme-dark"))
        composeRule.onNodeWithTag("theme-dark").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { fake.updateConfigCalls == 1 }
        assertEquals("dark", fake.lastConfig?.app?.themeMode)
    }

    @Test
    fun busyStateDoesNotCrashShell() {
        fake.setState(PreviewData.state(status = RunStatus.STARTING))
        setApp()

        composeRule.onNodeWithText("启动中…").assertExists()
    }
}
