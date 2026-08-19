package cn.nabr.chatwithchat.presentation.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/**
 * NavHost 的页面转场。
 *
 * 预见式返回的「跟手」不需要我们实现：navigation-compose 内部已经用 PredictiveBackHandler +
 * SeekableTransitionState 把手势进度映射成 pop 转场的播放进度。但它自己不带任何空间位移，默认转场
 * 只有 fadeIn/fadeOut(700ms) 且 pop 方向直接复用 push 方向，被手势 seek 出来就只是一团渐隐渐现。
 * 这个文件补的就是「转场本身」。
 *
 * 因为转场会被按任意进度采样，写法上有两条硬约束：
 * 1. 只用平移/缩放/整体透明度，且同一转场内各子动画同时长——否则中途会出现某个子动画已经跑完、
 *    页面提前消失的散架效果；
 * 2. pop 方向的 easing 不能前段太陡。手势进度是**直接拿来采样这条曲线**的，用 emphasized 这类
 *    前段极陡的曲线会变成「手指拖一点、页面窜很远」。同理 pop 方向不要用 spring：它的时长是估算值，
 *    曲线还带过冲，seek 时会出现手指往回拖、页面反而继续往前的失控感。
 *
 * 三类跳转语义不同，不能一刀切全用滑动：
 * - 设置/关于/记忆等下钻、Setup 向导步进 → 层级关系，shared-axis-X 视差滑动
 * - CHAT_LIST ↔ CHAT_ROOM、CHAT_ROOM ↔ CHAT_ROOM → 同一个 ChatShellScreen 只换 slot，属于同级
 *   切换，横滑会把正在关闭的抽屉一起滑走 → fade through
 * - ATTACHMENT_PREVIEW → 缩略图放大成全屏，横滑语义不对 → scale + fade
 */

/**
 * 转场时长。
 *
 * 注意这几个值**管不到手势拖动过程**：seek 期间页面位置只由手势进度采样曲线得出，与时长无关。它们
 * 决定的是松手后自动播完剩余进度的快慢、以及拖一半放手时回弹的快慢。pop 方向给得比 push 略长一点，
 * 是因为松手落位这一下需要显得从容，而 push 是纯自动播放，太长会拖沓。
 */
private const val PUSH_DURATION_MS = 400
private const val POP_DURATION_MS = 420
private const val FADE_THROUGH_DURATION_MS = 280
private const val PREVIEW_DURATION_MS = 360

/** 退到后面的页面只平移这么多，形成纵深视差。 */
private const val PARALLAX_FRACTION = 0.3f

/** 退到后面时压暗到的透明度。别压到 0：seek 到中途会看见它凭空消失。 */
private const val PARALLAX_DIM_ALPHA = 0.65f

/** 预览页放大前/缩小后的比例。 */
private const val PREVIEW_SCALE = 0.92f

/** 预览页盖上来时，底下页面轻微后退的比例与透明度。 */
private const val PREVIEW_UNDERLAY_SCALE = 0.97f
private const val PREVIEW_UNDERLAY_ALPHA = 0.7f

/** M3 emphasized decelerate。只用在没有手势参与的 push 方向。 */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * pop 方向的曲线。选对称曲线是为了让手势位移和页面位移大致成正比。
 * 若真机上仍觉得「拖一点就窜」，换成 LinearEasing——牺牲松手后自动播放的观感，换 1:1 跟手。
 */
private val PopEasing = FastOutSlowInEasing

private val NavBackStackEntry.routePattern: String?
    get() = destination.route

/** CHAT_LIST 和 CHAT_ROOM 是同一个 ChatShellScreen 的两种 slot，互跳属于同级切换。 */
private fun isChatShell(route: String?): Boolean =
    route == Route.CHAT_LIST || route == Route.CHAT_ROOM

private fun parallaxOffset(fullWidth: Int): Int = -(fullWidth * PARALLAX_FRACTION).toInt()

internal val NavEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    when {
        targetState.routePattern == Route.ATTACHMENT_PREVIEW ->
            scaleIn(tween(PREVIEW_DURATION_MS, easing = EmphasizedDecelerate), initialScale = PREVIEW_SCALE) +
                fadeIn(tween(PREVIEW_DURATION_MS, easing = EmphasizedDecelerate))

        isChatShell(initialState.routePattern) && isChatShell(targetState.routePattern) ->
            fadeIn(tween(FADE_THROUGH_DURATION_MS, delayMillis = FADE_THROUGH_DURATION_MS / 2))

        else -> slideInHorizontally(tween(PUSH_DURATION_MS, easing = EmphasizedDecelerate)) { fullWidth ->
            fullWidth
        }
    }
}

internal val NavExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    when {
        // 预览页不透明地盖上来，底下页面只需轻微后退。但必须留住动画时长：换成 ExitTransition.None
        // 会让它立刻出组，预览放大的过程中露出空白画布。
        targetState.routePattern == Route.ATTACHMENT_PREVIEW ->
            scaleOut(tween(PREVIEW_DURATION_MS, easing = EmphasizedDecelerate), targetScale = PREVIEW_UNDERLAY_SCALE) +
                fadeOut(tween(PREVIEW_DURATION_MS, easing = EmphasizedDecelerate), targetAlpha = PREVIEW_UNDERLAY_ALPHA)

        isChatShell(initialState.routePattern) && isChatShell(targetState.routePattern) ->
            fadeOut(tween(FADE_THROUGH_DURATION_MS / 2))

        else -> slideOutHorizontally(
            tween(PUSH_DURATION_MS, easing = EmphasizedDecelerate),
            targetOffsetX = ::parallaxOffset
        ) + fadeOut(tween(PUSH_DURATION_MS, easing = EmphasizedDecelerate), targetAlpha = PARALLAX_DIM_ALPHA)
    }
}

internal val NavPopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    when {
        initialState.routePattern == Route.ATTACHMENT_PREVIEW ->
            scaleIn(tween(PREVIEW_DURATION_MS, easing = PopEasing), initialScale = PREVIEW_UNDERLAY_SCALE) +
                fadeIn(tween(PREVIEW_DURATION_MS, easing = PopEasing), initialAlpha = PREVIEW_UNDERLAY_ALPHA)

        // 这里刻意不用带 delay 的 fade through：被手势 seek 时拖到一半会露出空白画布。
        isChatShell(initialState.routePattern) && isChatShell(targetState.routePattern) ->
            fadeIn(tween(FADE_THROUGH_DURATION_MS, easing = PopEasing))

        else -> slideInHorizontally(
            tween(POP_DURATION_MS, easing = PopEasing),
            initialOffsetX = ::parallaxOffset
        ) + fadeIn(tween(POP_DURATION_MS, easing = PopEasing), initialAlpha = PARALLAX_DIM_ALPHA)
    }
}

internal val NavPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    when {
        initialState.routePattern == Route.ATTACHMENT_PREVIEW ->
            scaleOut(tween(PREVIEW_DURATION_MS, easing = PopEasing), targetScale = PREVIEW_SCALE) +
                fadeOut(tween(PREVIEW_DURATION_MS, easing = PopEasing))

        isChatShell(initialState.routePattern) && isChatShell(targetState.routePattern) ->
            fadeOut(tween(FADE_THROUGH_DURATION_MS, easing = PopEasing))

        // 整幅滑出屏幕，不叠 fade：页面已经走出可视区，再淡出只会在中途变成一层鬼影。
        else -> slideOutHorizontally(tween(POP_DURATION_MS, easing = PopEasing)) { fullWidth -> fullWidth }
    }
}
