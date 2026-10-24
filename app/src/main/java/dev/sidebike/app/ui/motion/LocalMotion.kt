package dev.sidebike.app.ui.motion

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 是否允许**无限/持续**动画（呼吸点、不确定进度指示器）。
 *
 * 有限时长的状态动画（AnimatedContent / animate*AsState / animateItem）永远开启 —— 它们正是
 * MD3 motion spec 的要求。只有「无限循环」类动画在 Robolectric UI 测试中会阻止 compose 进入
 * idle，因此测试基类通过该 CompositionLocal 关闭它们；生产/Preview 默认 true。
 */
val LocalInfiniteAnimationsEnabled = staticCompositionLocalOf { true }
