package dev.sidebike.app.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment

/**
 * Material Design 3 motion tokens — the single source of truth for every animation in the UI.
 *
 * Values are the official MD3 motion tokens (m3.material.io/styles/motion/easing-and-duration),
 * identical to `androidx.compose.material3.tokens.MotionTokens`:
 *
 * | token                         | cubic-bezier                | duration (ms) |
 * | ----------------------------- | --------------------------- | ------------- |
 * | EmphasizedDecelerate          | (0.05, 0.70, 0.10, 1.00)    | Short1..4 = 50, 100, 150, 200 |
 * | EmphasizedAccelerate          | (0.30, 0.00, 0.80, 0.15)    | Medium1..4 = 250, 300, 350, 400 |
 * | Standard                      | (0.20, 0.00, 0.00, 1.00)    | Long1..4 = 450, 500, 550, 600 |
 * | StandardDecelerate            | (0.00, 0.00, 0.00, 1.00)    | ExtraLong1..4 = 700, 800, 900, 1000 |
 * | StandardAccelerate            | (0.30, 0.00, 1.00, 1.00)    |               |
 *
 * MD3's "emphasized" easing is a two-part curve: incoming content uses
 * [EmphasizedDecelerate] and outgoing content uses [EmphasizedAccelerate]. No linear easing
 * exists anywhere in the app (enforced by `Md3ComplianceTest`).
 *
 * The event -> animation -> easing -> duration mapping is documented in
 * `docs/design/03-motion-spec.md`.
 */
object M3Motion {

    // ---------------------------------------------------------------------------------------
    // Easing tokens
    // ---------------------------------------------------------------------------------------

    /** 强调型减速曲线，用于进入/展开/尺寸增长的动画。 */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 强调型加速曲线，用于退出/收起/尺寸收缩的动画。 */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 标准曲线，用于状态之间的等权过渡（颜色、数值滚动等）。 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 标准减速曲线。 */
    val StandardDecelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** 标准加速曲线。 */
    val StandardAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    // ---------------------------------------------------------------------------------------
    // Duration tokens (milliseconds)
    // ---------------------------------------------------------------------------------------

    const val Short1 = 50
    const val Short2 = 100
    const val Short3 = 150
    const val Short4 = 200
    const val Medium1 = 250
    const val Medium2 = 300
    const val Medium3 = 350
    const val Medium4 = 400
    const val Long1 = 450
    const val Long2 = 500
    const val Long3 = 550
    const val Long4 = 600
    const val ExtraLong1 = 700
    const val ExtraLong2 = 800
    const val ExtraLong3 = 900
    const val ExtraLong4 = 1000

    // ---------------------------------------------------------------------------------------
    // Shared motion constants
    // ---------------------------------------------------------------------------------------

    /** MD3 fade-through: incoming content starts at 92% scale. */
    const val FadeThroughIncomingScale = 0.92f

    /** MD3 fade-through: outgoing content grows to 108% scale. */
    const val FadeThroughOutgoingScale = 1.08f

    /** MD3 fade-through: outgoing fade duration (Short2). */
    const val FadeThroughOutDuration = Short2

    /** MD3 fade-through: incoming fade + scale duration (Medium1). */
    const val FadeThroughInDuration = Medium1

    // ---------------------------------------------------------------------------------------
    // Token-backed animation specs (all tween, all non-linear)
    // ---------------------------------------------------------------------------------------

    /** 进入/展开：EmphasizedDecelerate + Medium1。 */
    fun <T> emphasizedDecelerateIn(durationMillis: Int = Medium1): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = EmphasizedDecelerate)

    /** 进入但更长的强调动画（大画布/整屏）：EmphasizedDecelerate + Medium2。 */
    fun <T> emphasizedDecelerateEnter(durationMillis: Int = Medium2): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = EmphasizedDecelerate)

    /** 退出/收起：EmphasizedAccelerate + Short2。 */
    fun <T> emphasizedAccelerateOut(durationMillis: Int = Short2): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = EmphasizedAccelerate)

    /** 数值滚动/颜色过渡：Standard + Medium2。 */
    fun <T> standardTransition(durationMillis: Int = Medium2): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = Standard)

    /** 长数值滚动（例如统计累计值）：StandardDecelerate + Long1。 */
    fun <T> standardDecelerateLong(durationMillis: Int = Long1): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = StandardDecelerate)

    /** 收起时较长的强调加速（整屏退出）：EmphasizedAccelerate + Short4。 */
    fun <T> emphasizedAccelerateExit(durationMillis: Int = Short4): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = EmphasizedAccelerate)
}

// -------------------------------------------------------------------------------------------
// Reusable MD3 transitions built from the tokens above.
// -------------------------------------------------------------------------------------------

/** MD3 fade-through incoming half: fade in + scale 92% -> 100%（EmphasizedDecelerate）。 */
fun m3FadeThroughIn(
    durationMillis: Int = M3Motion.FadeThroughInDuration,
): EnterTransition {
    val spec = tween<Float>(durationMillis = durationMillis, easing = M3Motion.EmphasizedDecelerate)
    return fadeIn(spec) + scaleIn(
        animationSpec = spec,
        initialScale = M3Motion.FadeThroughIncomingScale,
    )
}

/** MD3 fade-through outgoing half: fade out + scale 100% -> 108%（EmphasizedAccelerate）。 */
fun m3FadeThroughOut(
    durationMillis: Int = M3Motion.FadeThroughOutDuration,
): ExitTransition {
    val spec = tween<Float>(durationMillis = durationMillis, easing = M3Motion.EmphasizedAccelerate)
    return fadeOut(spec) + scaleOut(
        animationSpec = spec,
        targetScale = M3Motion.FadeThroughOutgoingScale,
    )
}

/** MD3 fade-through content transform (used by AnimatedContent for state / destination changes). */
fun m3FadeThrough(): ContentTransform = m3FadeThroughIn() togetherWith m3FadeThroughOut()

/**
 * MD3 fade-through with an animated size change between the two states (EmphasizedDecelerate,
 * Medium2). Used where the outgoing and incoming content have different intrinsic sizes
 * (e.g. the start/stop control morphing).
 */
fun m3FadeThroughResize(
    sizeDurationMillis: Int = M3Motion.Medium2,
): ContentTransform {
    val sizeTransform = SizeTransform(
        clip = false,
        sizeAnimationSpec = { _, _ ->
            tween<androidx.compose.ui.unit.IntSize>(
                durationMillis = sizeDurationMillis,
                easing = M3Motion.EmphasizedDecelerate,
            )
        },
    )
    return ContentTransform(
        targetContentEnter = m3FadeThroughIn(),
        initialContentExit = m3FadeThroughOut(),
        targetContentZIndex = 0f,
        sizeTransform = sizeTransform,
    )
}

/** 展开进入（错误横幅、列表详情）：fade in + expand（EmphasizedDecelerate / Medium2）。 */
fun m3ExpandIn(): EnterTransition = fadeIn(
    tween(M3Motion.Medium1, easing = M3Motion.EmphasizedDecelerate),
) + expandVertically(
    animationSpec = tween(M3Motion.Medium2, easing = M3Motion.EmphasizedDecelerate),
    expandFrom = Alignment.Top,
)

/** 收起退出：fade out + shrink（EmphasizedAccelerate / Short4）。 */
fun m3ShrinkOut(): ExitTransition = fadeOut(
    tween(M3Motion.Short2, easing = M3Motion.EmphasizedAccelerate),
) + shrinkVertically(
    animationSpec = tween(M3Motion.Short4, easing = M3Motion.EmphasizedAccelerate),
    shrinkTowards = Alignment.Top,
)

/** 列表/卡片进入：fade in + 轻微上滑（EmphasizedDecelerate / Medium2）。 */
fun m3EnterFromBottom(offsetFraction: Float = 0.08f): EnterTransition = fadeIn(
    tween(M3Motion.Medium2, easing = M3Motion.EmphasizedDecelerate),
) + slideInVertically(
    animationSpec = tween(M3Motion.Medium2, easing = M3Motion.EmphasizedDecelerate),
) { fullHeight -> (fullHeight * offsetFraction).toInt() }

/** 顶栏折叠/展开的 snap 规格：EmphasizedDecelerate + Medium1（fling 使用 MD3 物理衰减）。 */
val M3TopAppBarSnapSpec: AnimationSpec<Float> = tween(
    durationMillis = M3Motion.Medium1,
    easing = M3Motion.EmphasizedDecelerate,
)

/** 列表项淡入规格（LazyColumn `Modifier.animateItem`）。 */
val M3ListItemFadeInSpec: FiniteAnimationSpec<Float> = tween(
    durationMillis = M3Motion.Medium1,
    easing = M3Motion.EmphasizedDecelerate,
)

/** 列表项位置动画规格（LazyColumn `Modifier.animateItem`）。 */
val M3ListItemPlacementSpec: FiniteAnimationSpec<androidx.compose.ui.unit.IntOffset> = tween(
    durationMillis = M3Motion.Medium2,
    easing = M3Motion.Standard,
)

/** 列表项淡出规格。 */
val M3ListItemFadeOutSpec: FiniteAnimationSpec<Float> = tween(
    durationMillis = M3Motion.Short2,
    easing = M3Motion.EmphasizedAccelerate,
)

/** 列表项错峰入场延迟（每项递增 Short1，非线性的 EmphasizedDecelerate 入场）。 */
fun m3StaggerDelayMillis(index: Int, stepMillis: Int = M3Motion.Short1): Int = index * stepMillis
