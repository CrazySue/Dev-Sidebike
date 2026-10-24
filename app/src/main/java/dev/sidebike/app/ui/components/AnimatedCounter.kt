package dev.sidebike.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.sidebike.app.ui.motion.M3Motion

private val LongToVector: TwoWayConverter<Long, AnimationVector1D> = TwoWayConverter(
    convertToVector = { AnimationVector1D(it.toFloat()) },
    convertFromVector = { it.value.toLong() },
)

/**
 * Long 版本的 [androidx.compose.animation.core.animateIntAsState]：
 * Compose 没有内置 Long 版，这里用 TwoWayConverter 实现，动画曲线仍走 MD3 motion token。
 */
@Composable
fun animateLongAsState(
    targetValue: Long,
    animationSpec: AnimationSpec<Long> = tween(
        durationMillis = M3Motion.Medium2,
        easing = M3Motion.EmphasizedDecelerate,
    ),
    label: String = "animatedLong",
): State<Long> = animateValueAsState(
    targetValue = targetValue,
    typeConverter = LongToVector,
    animationSpec = animationSpec,
    label = label,
)

/**
 * 可动画的数值文本：统计数字变化时使用 MD3 非线性曲线滚动，避免数字“跳变”。
 */
@Composable
fun AnimatedCounterText(
    value: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineSmall,
    color: Color = Color.Unspecified,
    format: (Long) -> String = { it.toString() },
) {
    val animated by animateLongAsState(value)
    Text(
        text = format(animated),
        modifier = modifier,
        color = color,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
