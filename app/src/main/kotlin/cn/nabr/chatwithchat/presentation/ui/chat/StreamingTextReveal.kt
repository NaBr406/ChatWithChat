package cn.nabr.chatwithchat.presentation.ui.chat

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import java.util.regex.Pattern
import kotlin.math.ceil
import kotlin.math.floor

internal const val STREAMING_REVEAL_DURATION_MILLIS = 200L

internal data class StreamingRevealProfile(val durationMillis: Long = STREAMING_REVEAL_DURATION_MILLIS, val hiddenAlpha: Float = 1f)

internal data class StreamingRevealRange(
    val start: Int,
    val end: Int,
    val startedAt: Long,
    val profile: StreamingRevealProfile = StreamingRevealProfile()
)

/** Uses appended source Unicode code points, not provider tokens; the UI has no tokenizer. */
internal class StreamingRevealRate {
    private var previousTime: Long? = null
    private var unitsPerSecond: Float? = null
    var profile = StreamingRevealProfile()
        private set

    fun reset(now: Long) {
        previousTime = now
        unitsPerSecond = null
        profile = StreamingRevealProfile()
    }

    fun append(text: String, start: Int, now: Long) {
        val elapsed = previousTime?.let { now - it }
        previousTime = now
        if (elapsed == null || elapsed <= 0L) return
        // An idle interval begins a fresh estimate instead of keeping the old fast-stream setting.
        if (elapsed > 500L) {
            reset(now)
            return
        }
        val sample = text.codePointCount(start, text.length) * 1_000f / elapsed
        val previous = unitsPerSecond
        val rate = if (previous == null || sample >= previous) sample else previous * 0.65f + sample * 0.35f
        unitsPerSecond = rate
        val speed = ((rate - 80f) / 160f).coerceIn(0f, 1f)
        profile = StreamingRevealProfile(
            durationMillis = (200f - speed * 100f).toLong(),
            hiddenAlpha = 1f - speed * 0.45f
        )
    }
}

private val graphemePattern = Pattern.compile("\\X")

/**
 * Grapheme clusters are short, so segmenting from a little before [appendedAt] finds the same boundaries
 * there as segmenting from the start. Scanning the whole leaf ran a `\X` match per character of every
 * growing leaf (up to the 4 000-character thinking tail) twice per publish on the main thread.
 */
private const val GRAPHEME_SCAN_LOOKBEHIND = 32

private fun graphemeMatcherNear(text: String, appendedAt: Int): java.util.regex.Matcher =
    graphemePattern.matcher(text).region(graphemeScanStart(text, appendedAt), text.length)

/** A position shortly before [appendedAt] that is certainly a cluster boundary, or 0 if none is found. */
private fun graphemeScanStart(text: String, appendedAt: Int): Int {
    var index = (appendedAt - GRAPHEME_SCAN_LOOKBEHIND).coerceAtMost(text.length - 1)
    if (index <= 0) return 0
    val limit = (index - GRAPHEME_SCAN_LOOKBEHIND).coerceAtLeast(0)
    while (index > limit) {
        if (alwaysStartsGraphemeCluster(text[index])) return index
        index--
    }
    return 0
}

/**
 * ASCII (except LF, which joins a preceding CR) and CJK ideographs, kana, CJK and fullwidth punctuation
 * never extend the cluster before them. Hangul is left out: leading jamo join a following syllable.
 */
private fun alwaysStartsGraphemeCluster(char: Char): Boolean =
    (char < '\u0080' && char != '\n') || char in '\u3000'..'\u9FFF' || char in '\uFF00'..'\uFFEF'

internal fun streamingRevealStart(text: String, appendedAt: Int): Int {
    val matcher = graphemeMatcherNear(text, appendedAt)
    while (matcher.find()) {
        if (matcher.start() >= appendedAt) return matcher.start()
        if (matcher.end() > appendedAt) return matcher.end()
    }
    return text.length
}

private fun stableGraphemeEnd(text: String, appendedAt: Int): Int {
    val matcher = graphemeMatcherNear(text, appendedAt)
    while (matcher.find()) {
        if (matcher.start() < appendedAt && matcher.end() > appendedAt) return matcher.start()
    }
    return appendedAt
}

/** Tracks displayed characters, independently of Markdown syntax and Compose node lifetimes. */
internal class StreamingRevealSession {
    private data class Leaf(val text: String, val ranges: List<StreamingRevealRange>, val sourceEnd: Int)

    private var source: String? = null
    private var initialLength = 0
    private val leaves = mutableMapOf<Any, Leaf>()
    private val rate = StreamingRevealRate()
    var appendStart: Int = 0
        private set
    var isStreaming: Boolean = false
        private set

    fun updateSource(text: String, streaming: Boolean, now: Long = System.nanoTime() / 1_000_000L) {
        val previous = source
        if (previous == null || !text.startsWith(previous)) {
            leaves.clear()
            initialLength = text.length
            appendStart = text.length
            rate.reset(now)
        } else if (text != previous) {
            appendStart = previous.length
            if (streaming) rate.append(text, previous.length, now)
        }
        source = text
        isStreaming = streaming
    }

    fun observe(key: Any, text: String, sourceStart: Int, now: Long, newLeafBaseline: Int? = null, windowPrefixLength: Int = 0): List<StreamingRevealRange> {
        val previous = leaves[key]
        val ranges = previous?.ranges.orEmpty().filter { now - it.startedAt < it.profile.durationMillis }
        val nextRanges = when {
            previous == null -> {
                val baseline = newLeafBaseline ?: initialLength
                val raw = source.orEmpty()
                val start = when {
                    sourceStart >= baseline -> windowPrefixLength
                    // Plain pending text promoted to Markdown can retain only its genuinely new suffix.
                    // Transformed Markdown establishes a baseline, because raw offsets are not display offsets.
                    sourceStart >= 0 && raw.startsWith(text.drop(windowPrefixLength), sourceStart) -> streamingRevealStart(text, (baseline - sourceStart + windowPrefixLength).coerceAtMost(text.length))
                    else -> text.length
                }
                if (isStreaming && start < text.length) listOf(StreamingRevealRange(start, text.length, now, rate.profile)) else emptyList()
            }
            text == previous.text && (windowPrefixLength == 0 || sourceStart + text.length - windowPrefixLength == previous.sourceEnd) -> ranges
            // A semantic rewrite (for example closing Markdown syntax) establishes a fresh baseline.
            // Never replay old characters just because their formatting changed.
            !text.startsWith(previous.text) || (windowPrefixLength > 0 && text == previous.text) -> {
                if (isStreaming && windowPrefixLength > 0 && source.orEmpty().startsWith(text.drop(windowPrefixLength), sourceStart)) {
                    val start = streamingRevealStart(text, (previous.sourceEnd - sourceStart + windowPrefixLength).coerceIn(windowPrefixLength, text.length))
                    if (start < text.length) listOf(StreamingRevealRange(start, text.length, now, rate.profile)) else emptyList()
                } else {
                    emptyList()
                }
            }
            isStreaming -> {
                val start = streamingRevealStart(text, previous.text.length)
                val stableEnd = stableGraphemeEnd(text, previous.text.length)
                ranges.mapNotNull { range ->
                    val end = range.end.coerceAtMost(stableEnd)
                    if (range.start < end) range.copy(end = end) else null
                } + if (start < text.length) listOf(StreamingRevealRange(start, text.length, now, rate.profile)) else emptyList()
            }
            else -> ranges
        }
        leaves[key] = Leaf(text, nextRanges, sourceStart + text.length - windowPrefixLength)
        return nextRanges
    }
}

internal val LocalStreamingReveal = staticCompositionLocalOf<StreamingRevealSession?> { null }
internal val LocalStreamingSourceOffset = staticCompositionLocalOf { 0 }
internal val LocalStreamingRevealNewLeafBaseline = staticCompositionLocalOf<Int?> { null }

@Composable
internal fun StreamingRevealContent(content: String, identity: Any, isStreaming: Boolean, children: @Composable () -> Unit) {
    val session = remember(identity) { StreamingRevealSession() }
    session.updateSource(content, isStreaming, SystemClock.uptimeMillis())
    CompositionLocalProvider(LocalStreamingReveal provides session, content = children)
}

/** The clock is read only by draw: animation frames cannot reparse text or change its layout. */
internal class StreamingRevealDrawing(
    val modifier: Modifier,
    val onTextLayout: (TextLayoutResult) -> Unit
)

@Composable
internal fun rememberStreamingRevealDrawing(
    text: String,
    key: Any,
    sourceStart: Int,
    layoutText: String = text,
    windowPrefixLength: Int = 0
): StreamingRevealDrawing {
    val session = LocalStreamingReveal.current
    val leafKey = LocalStreamingSourceOffset.current to key
    val ranges = session?.observe(leafKey, text, sourceStart, SystemClock.uptimeMillis(), LocalStreamingRevealNewLeafBaseline.current, windowPrefixLength).orEmpty()
    val layout = remember(session, leafKey) { mutableStateOf<TextLayoutResult?>(null) }
    val clock = remember(session, leafKey) { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(ranges) {
        if (ranges.isNotEmpty()) {
            do {
                withFrameNanos { clock.longValue = SystemClock.uptimeMillis() }
            } while (ValueAnimator.areAnimatorsEnabled() && ranges.any { clock.longValue - it.startedAt < it.profile.durationMillis })
        }
    }
    val modifier = if (ranges.isEmpty()) {
        Modifier
    } else {
        Modifier.drawWithCache {
            val result = layout.value
            // Glyph paths depend on layout, not animation time. Cache them instead of asking the
            // text engine to rebuild selection geometry on every display frame.
            val paths = if (result != null && result.layoutInput.text.text == layoutText) {
                ranges.filter { it.end <= text.length }.map { it to result.getPathForRange(it.start, it.end) }
            } else {
                emptyList()
            }
            val paint = Paint()
            // The offscreen layer used to cover the whole leaf: in a long paragraph or the thinking tail
            // that is most of the screen, allocated and composited by the GPU on every frame of every
            // fade, for a line or two of fading glyphs. Cover only those glyphs, on whole pixels so the
            // seam with the directly drawn rest of the text is invisible.
            val bounds = paths
                .map { (_, path) -> path.getBounds() }
                .reduceOrNull { union, next ->
                    Rect(
                        minOf(union.left, next.left),
                        minOf(union.top, next.top),
                        maxOf(union.right, next.right),
                        maxOf(union.bottom, next.bottom)
                    )
                }
                ?.let { union ->
                    Rect(
                        floor(union.left - 1f).coerceAtLeast(0f),
                        floor(union.top).coerceAtLeast(0f),
                        ceil(union.right + 1f).coerceAtMost(size.width),
                        ceil(union.bottom).coerceAtMost(size.height)
                    )
                }
                ?.takeIf { !it.isEmpty }
            onDrawWithContent {
                val now = clock.longValue
                val active = paths.any { (range, _) -> now - range.startedAt < range.profile.durationMillis }
                if (bounds != null && active && ValueAnimator.areAnimatorsEnabled()) {
                    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom, ClipOp.Difference) {
                        this@onDrawWithContent.drawContent()
                    }
                    // The temporary layer contains only the fading glyphs of this leaf, never its
                    // background or controls.
                    drawContext.canvas.save()
                    drawContext.canvas.clipRect(bounds)
                    drawContext.canvas.saveLayer(bounds, paint)
                    try {
                        drawContent()
                        paths.forEach { (range, path) ->
                            val hiddenAlpha = (1f - (now - range.startedAt).toFloat() / range.profile.durationMillis).coerceIn(0f, 1f) * range.profile.hiddenAlpha
                            if (hiddenAlpha > 0f) {
                                drawPath(path, Color.Black, alpha = hiddenAlpha, blendMode = BlendMode.DstOut)
                            }
                        }
                    } finally {
                        drawContext.canvas.restore()
                        drawContext.canvas.restore()
                    }
                } else {
                    drawContent()
                }
            }
        }
    }
    return StreamingRevealDrawing(modifier) { layout.value = it }
}
