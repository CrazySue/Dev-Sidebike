package dev.sidebike.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.sidebike.app.R
import dev.sidebike.app.state.RunStatus

/**
 * [RunStatus] -> UI 的单一映射表。状态机每增加一个状态，这里必须显式补齐，
 * 由 `StatusMappingTest` 保证 6 个状态都有中文文案、图标与 MD3 color role。
 */
object StatusVisuals {

    fun labelRes(status: RunStatus): Int = when (status) {
        RunStatus.IDLE -> R.string.status_idle
        RunStatus.PREPARING -> R.string.status_preparing
        RunStatus.STARTING -> R.string.status_starting
        RunStatus.RUNNING -> R.string.status_running
        RunStatus.STOPPING -> R.string.status_stopping
        RunStatus.ERROR -> R.string.status_error
    }

    fun descriptionRes(status: RunStatus): Int = when (status) {
        RunStatus.IDLE -> R.string.status_desc_idle
        RunStatus.PREPARING -> R.string.status_desc_preparing
        RunStatus.STARTING -> R.string.status_desc_starting
        RunStatus.RUNNING -> R.string.status_desc_running
        RunStatus.STOPPING -> R.string.status_desc_stopping
        RunStatus.ERROR -> R.string.status_desc_error
    }

    fun icon(status: RunStatus): ImageVector = when (status) {
        RunStatus.IDLE -> Icons.Outlined.PowerSettingsNew
        RunStatus.PREPARING -> Icons.Outlined.HourglassTop
        RunStatus.STARTING -> Icons.Outlined.Sync
        RunStatus.RUNNING -> Icons.Outlined.CheckCircle
        RunStatus.STOPPING -> Icons.Outlined.StopCircle
        RunStatus.ERROR -> Icons.Outlined.ErrorOutline
    }

    /** 状态卡片容器色：使用 MD3 color roles，深/浅色与动态取色都自动适配。 */
    @Composable
    fun containerColor(status: RunStatus): Color = when (status) {
        RunStatus.IDLE -> MaterialTheme.colorScheme.surfaceContainerHigh
        RunStatus.PREPARING, RunStatus.STARTING -> MaterialTheme.colorScheme.secondaryContainer
        RunStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        RunStatus.STOPPING -> MaterialTheme.colorScheme.secondaryContainer
        RunStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

    @Composable
    fun contentColor(status: RunStatus): Color = when (status) {
        RunStatus.IDLE -> MaterialTheme.colorScheme.onSurface
        RunStatus.PREPARING, RunStatus.STARTING, RunStatus.STOPPING -> MaterialTheme.colorScheme.onSecondaryContainer
        RunStatus.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
        RunStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    /** 强调色（图标/脉冲点）：仅用于强调，实际容器色仍由 [containerColor] 决定。 */
    @Composable
    fun accentColor(status: RunStatus): Color = when (status) {
        RunStatus.RUNNING -> MaterialTheme.colorScheme.primary
        RunStatus.ERROR -> MaterialTheme.colorScheme.error
        RunStatus.PREPARING, RunStatus.STARTING, RunStatus.STOPPING -> MaterialTheme.colorScheme.secondary
        RunStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** 状态是否处于进行中（按钮禁用、显示进度）。 */
fun RunStatus.isBusy(): Boolean =
    this == RunStatus.PREPARING || this == RunStatus.STARTING || this == RunStatus.STOPPING
