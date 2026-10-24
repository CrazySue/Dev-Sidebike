package dev.sidebike.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.sidebike.app.ui.motion.LocalInfiniteAnimationsEnabled
import dev.sidebike.app.ui.theme.SidebikeTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose + Robolectric 测试基类。
 *
 * 稳定性策略（由 04:09 的挂起事故驱动）：
 * - 固定 411x891dp 视口，避免 LazyColumn 只组合首屏内容导致断言缺失；
 * - 通过 [LocalInfiniteAnimationsEnabled] = false 关闭**无限动画**（状态呼吸点、不确定进度圈）：
 *   这些动画会让 Compose 永远不进入 idle，导致 Robolectric 测试挂起；
 *   有限时长动画（MD3 motion token 的 AnimatedContent/animate*AsState/animateItem）保持开启并被验证。
 * - 时钟保持 autoAdvance = true，让 `waitForIdle()` 自动推进有限动画到稳定帧。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
abstract class ComposeTestBase {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    protected fun setScreen(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalInfiniteAnimationsEnabled provides false) {
                SidebikeTheme(dynamicColor = false) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        content()
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** 推进有限动画并等待稳定（点击/展开后调用）。 */
    protected fun settle(millis: Long = 1_500) {
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }
}
