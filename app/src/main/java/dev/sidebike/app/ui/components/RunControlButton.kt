package dev.sidebike.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sidebike.app.R
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.ui.motion.LocalInfiniteAnimationsEnabled
import dev.sidebike.app.ui.motion.M3Motion
import dev.sidebike.app.ui.motion.m3FadeThroughResize

/** 启动/停止控件所处的 4 个视觉相位。 */
enum class RunControlPhase { START, STARTING, STOP, STOPPING }

fun runControlPhaseOf(status: RunStatus): RunControlPhase = when (status) {
    RunStatus.IDLE, RunStatus.ERROR -> RunControlPhase.START
    RunStatus.PREPARING, RunStatus.STARTING -> RunControlPhase.STARTING
    RunStatus.RUNNING -> RunControlPhase.STOP
    RunStatus.STOPPING -> RunControlPhase.STOPPING
}

private fun phaseLabelRes(phase: RunControlPhase): Int = when (phase) {
    RunControlPhase.START -> R.string.home_start
    RunControlPhase.STARTING -> R.string.home_starting
    RunControlPhase.STOP -> R.string.home_stop
    RunControlPhase.STOPPING -> R.string.home_stopping
}

/**
 * 主操作按钮：启动/停止状态之间做 **颜色 + 文案 + 进度指示器** 的 MD3 形态变换（morph）。
 *
 * 动效：
 * - 容器色/内容色：`animateColorAsState`（Standard, Medium2）
 * - 文案 & 图标/进度：`AnimatedContent` fade-through（EmphasizedDecelerate/Accelerate）
 * - 尺寸变化：`animateContentSize`（EmphasizedDecelerate, Medium2）
 */
@Composable
fun RunControlButton(
    status: RunStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = runControlPhaseOf(status)
    val busy = phase == RunControlPhase.STARTING || phase == RunControlPhase.STOPPING
    val colorScheme = MaterialTheme.colorScheme

    val containerColor by animateColorAsState(
        targetValue = when (phase) {
            RunControlPhase.START -> colorScheme.primary
            RunControlPhase.STARTING, RunControlPhase.STOPPING -> colorScheme.secondaryContainer
            RunControlPhase.STOP -> colorScheme.secondaryContainer
        },
        animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.Standard),
        label = "runControlContainerColor",
    )
    val contentColor by animateColorAsState(
        targetValue = when (phase) {
            RunControlPhase.START -> colorScheme.onPrimary
            RunControlPhase.STARTING, RunControlPhase.STOPPING -> colorScheme.onSecondaryContainer
            RunControlPhase.STOP -> colorScheme.onSecondaryContainer
        },
        animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.Standard),
        label = "runControlContentColor",
    )

    Button(
        onClick = {
            when (phase) {
                RunControlPhase.START -> onStart()
                RunControlPhase.STOP -> onStop()
                RunControlPhase.STARTING, RunControlPhase.STOPPING -> Unit
            }
        },
        enabled = !busy,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier.animateContentSize(
                animationSpec = tween(durationMillis = M3Motion.Medium2, easing = M3Motion.EmphasizedDecelerate),
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = { m3FadeThroughResize() },
                contentAlignment = Alignment.Center,
                label = "runControlLabel",
            ) { targetPhase ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (targetPhase == RunControlPhase.STARTING || targetPhase == RunControlPhase.STOPPING) {
                        if (LocalInfiniteAnimationsEnabled.current) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = LocalContentColor.current,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            // 测试/静态渲染：用确定值进度圈替代无限旋转动画，避免 compose 无法 idle
                            CircularProgressIndicator(
                                progress = { 0.6f },
                                modifier = Modifier.size(18.dp),
                                color = LocalContentColor.current,
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = if (targetPhase == RunControlPhase.START) {
                                StatusVisuals.icon(RunStatus.IDLE)
                            } else {
                                StatusVisuals.icon(RunStatus.RUNNING)
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = stringResource(phaseLabelRes(targetPhase)),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
