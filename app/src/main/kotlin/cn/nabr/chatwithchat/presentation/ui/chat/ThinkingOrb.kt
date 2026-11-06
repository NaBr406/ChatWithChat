package cn.nabr.chatwithchat.presentation.ui.chat

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The thinking indicator is a port of the `breathing` state ("a face-on ring slowly morphing", which the
 * library itself labels "Thinking…") from thinking-orbs, at its 20px preset.
 *
 *   thinking-orbs — https://github.com/Jakubantalik/thinking-orbs
 *   MIT License, Copyright (c) 2026 Jakub Antalik
 *
 * Only the geometry is ported (src/engine/ribbon.ts with the ring profile from src/engine/profiles.ts and
 * the 20px multipliers from src/presets.ts, resolved once into the constants below). The library's
 * grayscale ink is mapped onto the app's label colour: on a light page a dot of ink value `w` is the
 * label colour at alpha `1 - w`, and the library mirrors ink on dark pages, which is the same alpha on
 * a light label. So one formula covers both themes without hardcoded greys.
 */

/**
 * @param isAnimating true while the model is reasoning. Turning it off does not freeze the ring on the
 * spot: its clock decelerates to rest, so a finished thought settles instead of stopping mid-motion, and
 * turning it back on mid-settle accelerates from wherever it is.
 */
@Composable
internal fun ThinkingOrb(
    isAnimating: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    val context = LocalContext.current
    val motionEnabled = remember(context) { context.animatorsEnabled() }
    val orbTime = remember { mutableFloatStateOf(ORB_STATIC_FRAME_TIME) }
    val speed = remember { Animatable(if (isAnimating && motionEnabled) 1f else 0f) }
    val latestColor by rememberUpdatedState(color)

    LaunchedEffect(isAnimating, motionEnabled) {
        if (!motionEnabled) return@LaunchedEffect
        speed.animateTo(
            targetValue = if (isAnimating) 1f else 0f,
            animationSpec = if (isAnimating) {
                tween(durationMillis = ORB_SPIN_UP_MILLIS, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = ORB_SETTLE_MILLIS, easing = LinearOutSlowInEasing)
            }
        )
    }
    LaunchedEffect(isAnimating, motionEnabled) {
        if (!motionEnabled) return@LaunchedEffect
        var lastFrameNanos = -1L
        // Runs only while the clock moves; a settled orb costs no frames.
        while (isAnimating || speed.value > 0f) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos >= 0L) {
                    val deltaSeconds = (frameNanos - lastFrameNanos) / 1_000_000_000f
                    orbTime.floatValue += deltaSeconds * ORB_PRESET_SPEED * speed.value
                }
                lastFrameNanos = frameNanos
            }
        }
    }

    Canvas(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { }
    ) {
        val scale = this.size.minDimension / ORB_PRESET_SIZE
        val ink = latestColor
        // Reading the clock here keeps every frame a redraw, never a recomposition.
        forEachBreathingDot(orbTime.floatValue) { x, y, radius, white, alpha ->
            drawCircle(
                color = ink.copy(alpha = ink.alpha * alpha * (1f - white).coerceIn(0f, 1f)),
                radius = radius * scale,
                center = Offset(x * scale, y * scale)
            )
        }
    }
}

private fun Context.animatorsEnabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

private class OrbDot(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var radius: Float = 0f,
    var white: Float = 0f,
    var alpha: Float = 0f
)

private val dotPool = Array(ORB_LANES * ORB_SEGMENTS) { OrbDot() }
private val drawOrder = arrayOfNulls<OrbDot>(ORB_LANES * ORB_SEGMENTS)

/**
 * One frame of the face-on ring, z-sorted far to near, in the preset's 20-unit coordinate space.
 *
 * Mirrors `frameRibbon` with `faceOn = 1, spin = 0, ghostN = 0`: the camera yaw and band precession are
 * both frozen, so the band plane is fixed and only the radial undulation travels around it. Called on
 * the main thread from a draw pass, so the pooled scratch arrays are never shared across threads.
 */
private inline fun forEachBreathingDot(
    time: Float,
    draw: (x: Float, y: Float, radius: Float, white: Float, alpha: Float) -> Unit
) {
    val center = ORB_PRESET_SIZE / 2f
    val ringRadius = center * 0.78f
    val cameraTilt = 0.3f
    val sinTilt = sin(cameraTilt)
    val cosTilt = cos(cameraTilt)
    val radiusScale = (ORB_PRESET_SIZE / 300f).pow(ORB_RADIUS_SCALE_POW)

    // Face-on: the band tilt cancels the camera tilt, so the ring reads as a true circle.
    val bandTilt = -cameraTilt
    val ux = 1f
    val uy = 0f
    val uz = 0f
    val vx = 0f
    val vy = cos(bandTilt)
    val vz = sin(bandTilt)
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx

    val wobbleAmplitude = 0.23f * ORB_WOBBLE_MUL
    val baseRadius = ringRadius / (1f + 0.85f * wobbleAmplitude)
    val laneCentre = (ORB_LANES - 1) / 2f

    var count = 0
    for (lane in 0 until ORB_LANES) {
        val laneOffset = (lane - laneCentre) * 0.075f
        val edge = abs(lane - laneCentre) / max(1f, laneCentre)
        for (segment in 0 until ORB_SEGMENTS) {
            val angle = segment.toFloat() / ORB_SEGMENTS * TWO_PI
            val wobble = (
                0.16f * sin(angle * 3f - time * 1.7f + lane * 0.22f) +
                    0.07f * sin(angle * 5f + time * 1.1f)
                ) * ORB_WOBBLE_MUL
            val cosA = cos(angle)
            val sinA = sin(angle)
            val x = ux * cosA + vx * sinA + nx * laneOffset
            val y = uy * cosA + vy * sinA + ny * laneOffset
            val z = uz * cosA + vz * sinA + nz * laneOffset
            val length = sqrt(x * x + y * y + z * z)
            val radius = baseRadius * (1f + wobble)
            val px = x / length * radius
            val py = y / length * radius
            val pz = z / length * radius
            // makeProj with yaw 0: rotate about X by the camera tilt, then project orthographically.
            val projectedY = py * cosTilt - pz * sinTilt
            val depthZ = py * sinTilt + pz * cosTilt
            val depth = (depthZ / ringRadius + 1f) / 2f
            val alpha = 0.4f + 0.6f * depth
            if (alpha < ORB_MIN_ALPHA) continue
            val dot = dotPool[count]
            dot.x = center + px
            dot.y = center - projectedY
            dot.z = depthZ
            dot.radius = max(
                ORB_MIN_RADIUS,
                (ORB_RADIUS_BASE + ORB_RADIUS_DEPTH * depth) * (1f - 0.25f * edge) * radiusScale
            )
            dot.white = 0.52f - 0.44f * depth + 0.18f * edge
            dot.alpha = alpha
            drawOrder[count] = dot
            count++
        }
    }
    sortByDepth(count)
    for (index in 0 until count) {
        val dot = drawOrder[index] ?: continue
        draw(dot.x, dot.y, dot.radius, dot.white, dot.alpha)
    }
}

/** Insertion sort: 120 nearly-ordered dots per frame, no allocation. */
private fun sortByDepth(count: Int) {
    for (i in 1 until count) {
        val current = drawOrder[i] ?: continue
        var j = i - 1
        while (j >= 0 && (drawOrder[j]?.z ?: 0f) > current.z) {
            drawOrder[j + 1] = drawOrder[j]
            j--
        }
        drawOrder[j + 1] = current
    }
}

private const val TWO_PI = (2.0 * Math.PI).toFloat()

// Resolved `breathing` @ 20px: ring profile × preset {speed 3.78, count 0.028, size 1.622,
// bandMul 3.968, wobMul 0.565}. lanes = round(max(2, round(5·√0.028)) · 3.968) = 8,
// segs = round(88·√0.028) = 15.
private const val ORB_PRESET_SIZE = 20f
private const val ORB_PRESET_SPEED = 3.78f
private const val ORB_LANES = 8
private const val ORB_SEGMENTS = 15
private const val ORB_RADIUS_BASE = 1.1f * 1.622f
private const val ORB_RADIUS_DEPTH = 1.7f * 1.622f
private const val ORB_RADIUS_SCALE_POW = 0.6f
private const val ORB_MIN_RADIUS = 0.3f
private const val ORB_MIN_ALPHA = 0.02f
private const val ORB_WOBBLE_MUL = 0.565f

/** The library's reduced-motion frame, also where a never-animated orb rests. */
private const val ORB_STATIC_FRAME_TIME = 0.6f
private const val ORB_SPIN_UP_MILLIS = 260
private const val ORB_SETTLE_MILLIS = 900
