package dev.sidebike.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.ui.preview.PreviewData
import dev.sidebike.app.ui.screens.HomeScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenTest : ComposeTestBase() {

    private var startCalls = 0
    private var stopCalls = 0
    private var lastMitmToggle: Boolean? = null
    private var refreshCaCalls = 0

    private var notificationRequests = 0

    private fun setHome(
        state: SidebikeUiState,
        recentLogs: List<dev.sidebike.core.api.LogEntry> = emptyList(),
        notificationsDenied: Boolean = false,
    ) {
        setScreen {
            HomeScreen(
                state = state,
                recentLogs = recentLogs,
                contentPadding = PaddingValues(0.dp),
                onStart = { startCalls++ },
                onStop = { stopCalls++ },
                onOpenRules = {},
                onOpenLogs = {},
                onOpenCertGuide = {},
                onToggleMitm = { lastMitmToggle = it },
                onRefreshCa = { refreshCaCalls++ },
                notificationsDenied = notificationsDenied,
                onRequestNotificationPermission = { notificationRequests++ },
            )
        }
    }

    private fun scrollHomeTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        composeRule.onNodeWithTag("home-list").performScrollToNode(matcher)
    }

    @Test
    fun rendersIdleStateWithEmptyData() {
        setHome(PreviewData.emptyState)

        composeRule.onAllNodesWithText("未运行").onFirst().assertExists()
        // "尚未启动" 同时出现在 Hero headline 与运行时长占位（两处），取第一个断言即可
        composeRule.onAllNodesWithText("尚未启动").onFirst().assertExists()
        composeRule.onNodeWithText("启动服务").assertExists()
        composeRule.onNodeWithText("实时数据").assertExists()
        composeRule.onNodeWithText("连接总数").assertExists()
        composeRule.onNodeWithText("活跃连接").assertExists()
        composeRule.onNodeWithText("DNS 查询").assertExists()

        scrollHomeTo(hasText("暂无日志"))
        composeRule.onNodeWithText("暂无日志").assertExists()
        composeRule.onNodeWithText("最近日志").assertExists()

        scrollHomeTo(hasText("DNS 级防污染对全部应用生效", substring = true))
        composeRule.onNodeWithText("DNS 级防污染对全部应用生效", substring = true).assertExists()
    }

    @Test
    fun rendersRunningStateWithLiveStats() {
        setHome(PreviewData.state(), recentLogs = PreviewData.logs.takeLast(3))

        composeRule.onNodeWithText("停止服务").assertExists()
        composeRule.onAllNodesWithText("运行中").onFirst().assertExists()
        // Hero 信息层级：headline 带运行时长；subtitle 为真实能力行
        composeRule.onNodeWithText("已连接 · 1:02:04").assertExists()
        composeRule.onNodeWithText("DNS 防污染已生效 · 系统代理已注册 · MITM 已开启").assertExists()
        composeRule.onNodeWithText("VPN 已连接").assertExists()
        composeRule.onNodeWithText("代理 :31181").assertExists()
        composeRule.onAllNodesWithText("MITM 已开启").onFirst().assertExists()

        // 统计数字：动画结束后显示目标值
        composeRule.onNodeWithText("128").assertExists()
        composeRule.onNodeWithText("6").assertExists()
        composeRule.onNodeWithText("1.2 MB").assertExists()
        composeRule.onNodeWithText("9.4 MB").assertExists()
        composeRule.onNodeWithText("4,821").assertExists()
        composeRule.onNodeWithText("23 ms").assertExists()
    }

    @Test
    fun byteCountersFallBackToDashWhenTrafficUnknown() {
        val state = PreviewData.state().let {
            it.copy(stats = it.stats.copy(totalConnections = 130, bytesUp = 0, bytesDown = 0))
        }
        setHome(state)

        composeRule.onAllNodesWithText("—").onFirst().assertExists()
    }

    @Test
    fun rendersErrorStateWithBanner() {
        setHome(PreviewData.state(status = RunStatus.ERROR, lastError = "无法创建 tun 设备：权限被拒绝"))

        composeRule.onAllNodesWithText("启动失败").onFirst().assertExists()
        composeRule.onNodeWithText("无法创建 tun 设备：权限被拒绝").assertExists()
    }

    @Test
    fun busyStatusShowsProgressAndDisablesButton() {
        setHome(PreviewData.state(status = RunStatus.STARTING))

        composeRule.onNodeWithText("启动中…").assertIsNotEnabled()
    }

    @Test
    fun startButtonInvokesControllerStart() {
        setHome(PreviewData.emptyState)

        composeRule.onNodeWithText("启动服务").performClick()
        assertEquals(1, startCalls)
    }

    @Test
    fun stopButtonInvokesControllerStop() {
        setHome(PreviewData.state())

        composeRule.onNodeWithText("停止服务").performClick()
        assertEquals(1, stopCalls)
    }

    @Test
    fun mitmSwitchTogglesConfig() {
        val config = PreviewData.config.copy(
            proxy = PreviewData.config.proxy.copy(mitmEnabled = false),
            mitm = PreviewData.config.mitm.copy(enabled = false),
        )
        setHome(PreviewData.state(config = config))

        scrollHomeTo(hasTestTag("home-mitm-switch"))
        composeRule.onNodeWithTag("home-mitm-switch").performClick()
        assertEquals(true, lastMitmToggle)
    }

    @Test
    fun caCopyIsStateAwareWhenMitmEnabledButCaMissing() {
        setHome(PreviewData.state(caInstalled = false))

        // MITM 已开启 + CA 未安装 → 需要安装证书（不再出现「请先启动服务」这类矛盾文案）
        scrollHomeTo(hasText("需要安装 CA 证书"))
        composeRule.onAllNodesWithText("需要安装 CA 证书").onFirst().assertExists()
        composeRule.onNodeWithText("尚未生成，请先开启 MITM 并启动服务").assertDoesNotExist()
    }

    @Test
    fun caCopyIsStateAwareWhenMitmDisabled() {
        val config = PreviewData.config.copy(
            proxy = PreviewData.config.proxy.copy(mitmEnabled = false),
            mitm = PreviewData.config.mitm.copy(enabled = false),
        )
        setHome(PreviewData.state(config = config))

        scrollHomeTo(hasText("MITM 未启用（可选增强）"))
        composeRule.onAllNodesWithText("MITM 未启用（可选增强）").onFirst().assertExists()
        composeRule.onNodeWithText("需要安装 CA 证书").assertDoesNotExist()
    }

    @Test
    fun caCopyIsStateAwareWhenReady() {
        setHome(PreviewData.state(caInstalled = true))

        scrollHomeTo(hasText("CA 证书已就绪"))
        composeRule.onAllNodesWithText("CA 证书已就绪").onFirst().assertExists()
    }

    @Test
    fun notificationDeniedShowsBannerAndRetriesPermission() {
        setHome(PreviewData.state(), notificationsDenied = true)

        scrollHomeTo(hasText("通知权限未授予", substring = true))
        composeRule.onNodeWithText("通知权限未授予", substring = true).assertExists()
        composeRule.onNodeWithText("允许通知").performClick()
        assertEquals(1, notificationRequests)
    }

    @Test
    fun showsPrivateDnsWarning() {
        setHome(PreviewData.state(privateDnsActive = true))

        scrollHomeTo(hasText("Private DNS", substring = true))
        composeRule.onNodeWithText("Private DNS", substring = true).assertExists()
    }

    @Test
    fun showsLockdownWarning() {
        setHome(PreviewData.state(lockdownDetected = true))

        scrollHomeTo(hasText("始终开启 VPN", substring = true))
        composeRule.onNodeWithText("始终开启 VPN", substring = true).assertExists()
    }

    @Test
    fun showsSystemProxyNotRegisteredWarning() {
        setHome(PreviewData.state(systemProxyRegistered = false))

        scrollHomeTo(hasText("系统 HTTP 代理未生效", substring = true))
        composeRule.onNodeWithText("系统 HTTP 代理未生效", substring = true).assertExists()
    }

    @Test
    fun showsPortFallbackWarningAndEffectivePort() {
        setHome(PreviewData.state(effectiveProxyPort = 41234))

        scrollHomeTo(hasText("已回退到端口", substring = true))
        composeRule.onNodeWithText("已回退到端口 41234", substring = true).assertExists()
        composeRule.onNodeWithText("代理 :41234").assertExists()
    }

    @Test
    fun noWarningsWhenEnvironmentHealthy() {
        setHome(PreviewData.state())

        composeRule.onNodeWithText("环境提示").assertDoesNotExist()
        composeRule.onNodeWithText("Private DNS", substring = true).assertDoesNotExist()
    }
}
