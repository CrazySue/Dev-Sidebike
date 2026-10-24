package dev.sidebike.app.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import org.junit.Test

/**
 * 最小 Compose/Robolectric 基础设施冒烟测试。
 * 单独先跑它，用来把「测试框架挂起」和「某个屏幕/动画挂起」区分开。
 */
class ComposeInfraSmokeTest : ComposeTestBase() {

    @Test
    fun rendersSimpleText() {
        setScreen { Text("smoke-ok") }

        composeRule.onNodeWithText("smoke-ok").assertExists()
    }
}
