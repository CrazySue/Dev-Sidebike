package dev.sidebike.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.sidebike.app.ui.theme.SidebikeTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose + Robolectric 测试基类：
 * - 固定 411x891dp 视口，避免 LazyColumn 只组合首屏内容导致断言失败；
 * - 冻结动画时钟（autoAdvance = false）后再手动推进 1.5s，既让入场动画结束、又避免无限动画挂起 waitForIdle。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
abstract class ComposeTestBase {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    protected fun setScreen(content: @Composable () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SidebikeTheme(dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    content()
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_500)
    }

    /** 推进动画时钟（点击/展开后调用，让非线性动画走完）。 */
    protected fun settle(millis: Long = 1_500) {
        composeRule.mainClock.advanceTimeBy(millis)
    }
}
