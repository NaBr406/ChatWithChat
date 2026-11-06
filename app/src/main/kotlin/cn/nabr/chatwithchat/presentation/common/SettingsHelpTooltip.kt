package cn.nabr.chatwithchat.presentation.common

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import cn.nabr.chatwithchat.R

/**
 * A "?" button that sits beside a form field's title and shows [helpText] in a bubble.
 *
 * The bubble opens above the icon, because the field it explains sits below its title and the bubble
 * must not cover what is being edited; it only drops below when there is no room above. It never
 * takes focus, so the keyboard and caret of the field being edited stay put, and any touch outside
 * it (including on the field itself) closes it. Text longer than the room available scrolls inside
 * the bubble instead of running off screen.
 */
@Composable
fun SettingsHelpTooltipIcon(
    helpText: String,
    modifier: Modifier = Modifier
) {
    val materialColors = settingsMaterialColors()
    val visibilityState = remember { MutableTransitionState(false) }
    var isMounted by remember { mutableStateOf(false) }
    var lastDismissedAt by remember { mutableLongStateOf(0L) }
    var anchorTopPx by remember { mutableFloatStateOf(0f) }
    var anchorBottomPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height

    fun dismiss() {
        if (!visibilityState.targetState) return
        visibilityState.targetState = false
        lastDismissedAt = SystemClock.uptimeMillis()
    }

    // An outside touch that lands on the icon itself dismisses first and then clicks; without this
    // guard the click would immediately reopen the bubble it just closed.
    val canReopen = { SystemClock.uptimeMillis() - lastDismissedAt > HELP_REOPEN_GUARD_MILLIS }

    BackHandler(enabled = visibilityState.targetState) { dismiss() }

    LaunchedEffect(visibilityState.isIdle, visibilityState.currentState, visibilityState.targetState) {
        if (visibilityState.isIdle && !visibilityState.currentState && !visibilityState.targetState) {
            isMounted = false
        }
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            anchorTopPx = bounds.top
            anchorBottomPx = bounds.bottom
        }
    ) {
        IconButton(
            modifier = Modifier.size(HELP_ICON_TOUCH_SIZE),
            onClick = {
                when {
                    visibilityState.targetState -> dismiss()
                    canReopen() -> {
                        isMounted = true
                        visibilityState.targetState = true
                    }
                }
            }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(R.string.field_help),
                tint = materialColors.secondaryLabel,
                modifier = Modifier.size(18.dp)
            )
        }

        if (isMounted) {
            val marginPx = with(density) { HELP_BUBBLE_WINDOW_MARGIN.roundToPx() }
            val gapPx = with(density) { HELP_BUBBLE_GAP.roundToPx() }
            val spaceAbovePx = anchorTopPx - gapPx - marginPx
            val spaceBelowPx = windowHeightPx - anchorBottomPx - gapPx - marginPx
            val placeAbove = spaceAbovePx >= with(density) { HELP_BUBBLE_MIN_COMFORTABLE_HEIGHT.toPx() } ||
                spaceAbovePx >= spaceBelowPx
            val availableHeight = with(density) {
                (if (placeAbove) spaceAbovePx else spaceBelowPx).coerceAtLeast(0f).toDp()
            }
            val positionProvider = remember(placeAbove, marginPx, gapPx) {
                HelpBubblePositionProvider(placeAbove, marginPx, gapPx)
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = ::dismiss,
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = true
                )
            ) {
                AnimatedVisibility(
                    visibleState = visibilityState,
                    enter = scaleIn(
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                        initialScale = 0.92f,
                        transformOrigin = TransformOrigin(0.5f, if (placeAbove) 1f else 0f)
                    ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                    exit = scaleOut(
                        animationSpec = tween(durationMillis = 120, easing = FastOutLinearInEasing),
                        targetScale = 0.96f,
                        transformOrigin = TransformOrigin(0.5f, if (placeAbove) 1f else 0f)
                    ) + fadeOut(animationSpec = tween(durationMillis = 90))
                ) {
                    HelpBubble(
                        text = helpText,
                        maxHeight = minOf(HELP_BUBBLE_MAX_HEIGHT, availableHeight)
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpBubble(text: String, maxHeight: Dp) {
    val materialColors = settingsMaterialColors()
    Surface(
        modifier = Modifier.widthIn(max = HELP_BUBBLE_MAX_WIDTH),
        shape = RoundedCornerShape(12.dp),
        // White on light, near-black on dark: the bubble is a card of the page itself, not a tinted
        // overlay, so it reads as plain explanatory text.
        color = materialColors.canvas,
        contentColor = materialColors.primaryLabel,
        border = BorderStroke(0.5.dp, materialColors.separatorStrong),
        shadowElevation = 6.dp,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = materialColors.primaryLabel
            )
        }
    }
}

/** Centres the bubble on the icon, clamps it inside the window, and keeps it on the chosen side. */
private class HelpBubblePositionProvider(
    private val placeAbove: Boolean,
    private val marginPx: Int,
    private val gapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        val x = anchorBounds.center.x - popupContentSize.width / 2
        val y = if (placeAbove) {
            anchorBounds.top - gapPx - popupContentSize.height
        } else {
            anchorBounds.bottom + gapPx
        }
        return IntOffset(x.coerceIn(marginPx, maxX), y.coerceIn(marginPx, maxY))
    }
}

private const val HELP_REOPEN_GUARD_MILLIS = 250L
private val HELP_ICON_TOUCH_SIZE = 32.dp
private val HELP_BUBBLE_MAX_WIDTH = 300.dp
private val HELP_BUBBLE_MAX_HEIGHT = 260.dp
private val HELP_BUBBLE_MIN_COMFORTABLE_HEIGHT = 120.dp
private val HELP_BUBBLE_WINDOW_MARGIN = 12.dp
private val HELP_BUBBLE_GAP = 4.dp
