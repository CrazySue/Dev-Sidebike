package dev.sidebike.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsNodeInteraction
import dev.sidebike.app.ui.motion.LocalInfiniteAnimationsEnabled
import dev.sidebike.app.ui.theme.SidebikeTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose + Robolectric 测试基类。
 *
 * 稳定性策略（由 04:09 的测试挂起事故驱动）：
 * - 固定 411x891dp 视口，避免 LazyColumn 只组合首屏内容导致断言缺失；
 * - 通过 [LocalInfiniteAnimationsEnabled] = false 关闭**无限动画**（状态呼吸点、不确定进度圈）：
 *   这些动画会让 Compose 永远不进入 idle，导致 Robolectric 测试挂起；
 *   有限时长动画（MD3 motion token 的 AnimatedContent/animate*AsState/animateItem）保持开启并被验证。
 * - 时钟保持 autoAdvance = true，让 `waitForIdle()` 自动推进有限动画到稳定帧。
 * - 支持同一个测试内多次调用 [setScreen]（截图类测试会连续渲染多个屏幕/状态）：
 *   `setContent` 只调用一次，后续调用通过 state 切换内容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
abstract class ComposeTestBase {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val screenContent = mutableStateOf<(@Composable () -> Unit)?>(null)
    private var contentInstalled = false

    protected fun setScreen(content: @Composable () -> Unit) {
        if (!contentInstalled) {
            contentInstalled = true
            composeRule.setContent {
                CompositionLocalProvider(LocalInfiniteAnimationsEnabled provides false) {
                    SidebikeTheme(dynamicColor = false) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            screenContent.value?.invoke()
                        }
                    }
                }
            }
        }
        screenContent.value = content
        composeRule.waitForIdle()
    }

    /**
     * 测试专用文本输入：直接触发 semantics SetText（测试模式下 AppTextField 用静态语义节点渲染，
     * 避免 OutlinedTextField 光标/IME 无限动画挂起）。调用后需 settle() 让 recomposition 生效。
     */
    protected fun setText(testTag: String, text: String) {
        composeRule.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.SetText) { setTextAction ->
            setTextAction(AnnotatedString(text))
        }
    }

    /** 推进有限动画并等待稳定（点击/展开后调用）。 */
    protected fun settle(millis: Long = 1_500) {
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }
}
