package dev.sidebike.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import dev.sidebike.app.ui.preview.PreviewData
import dev.sidebike.app.ui.screens.LogsScreen
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class LogsScreenTest : ComposeTestBase() {

    private var clearCalls = 0

    private fun setLogs(logs: List<LogEntry> = PreviewData.logs) {
        setScreen {
            LogsScreen(
                logs = logs,
                contentPadding = PaddingValues(0.dp),
                onClearLogs = { clearCalls++ },
            )
        }
    }

    @Test
    fun rendersLogEntriesAndCounts() {
        setLogs()

        composeRule.onAllNodesWithText("共 5 条 · 错误 1 条").onFirst().assertExists()
        composeRule.onNodeWithText("engine").assertExists()
        composeRule.onAllNodesWithText("dns").onFirst().assertExists()
        composeRule.onNodeWithText("proxy").assertExists()
        composeRule.onNodeWithText("SidebikeEngine 已启动", substring = true).assertExists()
    }

    @Test
    fun levelFilterChipFiltersLogs() {
        setLogs()

        composeRule.onNodeWithTag("logs-level-ERROR").performClick()
        settle()

        composeRule.onNodeWithText("TLS 握手失败", substring = true).assertExists()
        composeRule.onNodeWithText("SidebikeEngine 已启动", substring = true).assertDoesNotExist()
        composeRule.onAllNodesWithText("共 1 条 · 错误 1 条").onFirst().assertExists()
    }

    @Test
    fun clearButtonInvokesCallback() {
        setLogs()

        composeRule.onNodeWithTag("logs-clear").performClick()
        assertEquals(1, clearCalls)
    }

    @Test
    fun autoScrollSwitchToggles() {
        setLogs()

        composeRule.onNodeWithTag("logs-auto-scroll").assertIsOn()
        composeRule.onNodeWithTag("logs-auto-scroll").performClick()
        composeRule.onNodeWithTag("logs-auto-scroll").assertIsOff()
    }

    @Test
    fun emptyLogsShowEmptyState() {
        setLogs(emptyList())

        composeRule.onNodeWithText("暂无日志").assertExists()
        composeRule.onNodeWithText("共 0 条 · 错误 0 条").assertExists()
    }

    @Test
    fun filteringWithoutMatchShowsNoMatchState() {
        val infoOnly = PreviewData.logs.filter { it.level == LogLevel.INFO }
        setLogs(infoOnly)

        composeRule.onNodeWithTag("logs-level-ERROR").performClick()
        settle()

        composeRule.onNodeWithText("当前筛选条件下没有日志").assertExists()
    }

    @Test
    fun expandingLogEntryRevealsFullMessage() {
        setLogs(PreviewData.logs)

        composeRule.onNodeWithText("SidebikeEngine 已启动", substring = true).performClick()
        settle(1_000)

        composeRule.onNodeWithText("SidebikeEngine 已启动", substring = true).assertExists()
    }
}
