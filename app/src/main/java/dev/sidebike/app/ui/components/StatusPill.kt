package dev.sidebike.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * MD3 状态徽标（composition，而非自造新组件）：
 * 由 MD3 `Surface` 的色 role + `MaterialTheme.shapes.small` + `Text` 组合而成，
 * 用于把 [dev.sidebike.app.state.RunStatus] 映射成稳定的视觉语言。
 */
@Composable
fun StatusPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    leadingDot: Boolean = true,
    dotColor: Color = contentColor,
    dotAlpha: Float = 1f,
) {
    Row(
        modifier = modifier
            .background(color = containerColor, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leadingDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color = dotColor.copy(alpha = dotAlpha), shape = CircleShape),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}
