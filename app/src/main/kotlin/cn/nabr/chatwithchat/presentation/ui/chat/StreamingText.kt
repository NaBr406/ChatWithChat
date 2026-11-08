package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.exp

internal const val STREAMING_TEXT_SMOOTHING_ENABLED = true
private const val STREAMING_TEXT_FRAME_MILLIS = 24L
internal const val STREAMING_TEXT_COMPLETION_FRAME_COUNT = 80
private const val STREAMING_TEXT_TAIL = "●"

internal data class SerializedStreamingText(
    val text: String,
    val thoughts: String,
    val isTextAnimating: Boolean,
    val isThoughtTextAnimating: Boolean
)

private enum class StreamingPresentationSide {
    THOUGHTS,
    TEXT
}

@Composable
internal fun rememberSmoothedStreamingText(
    targetText: String,
    isStreaming: Boolean,
    contentIdentity: Any
): String {
    var visibleText by remember(contentIdentity) {
        mutableStateOf(targetText)
    }
    var shouldAnimatePendingText by remember(contentIdentity) {
        mutableStateOf(false)
    }
    val latestTargetText by rememberUpdatedState(targetText)

    LaunchedEffect(contentIdentity, targetText, isStreaming) {
        if (isStreaming) {
            shouldAnimatePendingText = true
        }
        if (!isStreaming && !shouldAnimatePendingText) {
            visibleText = targetText
        } else if (visibleText.length > targetText.length || !targetText.startsWith(visibleText)) {
            visibleText = targetText.take(safeTextEnd(targetText, commonPrefixLength(visibleText, targetText)))
        }
    }

    LaunchedEffect(contentIdentity, isStreaming) {
        if (!isStreaming && !shouldAnimatePendingText) {
            visibleText = latestTargetText
            return@LaunchedEffect
        }

        var completionStartIndex = visibleText.length
        var completionFrame = 0
        while (isActive) {
            val target = latestTargetText
            when {
                target.isEmpty() -> visibleText = ""
                visibleText.length > target.length || !target.startsWith(visibleText) -> {
                    visibleText = target.take(safeTextEnd(target, commonPrefixLength(visibleText, target)))
                    completionStartIndex = visibleText.length
                    completionFrame = 0
                }
                visibleText.length < target.length -> {
                    if (!isStreaming) completionFrame++
                    visibleText = target.take(
                        if (isStreaming) {
                            nextStreamingTextEnd(
                                text = target,
                                currentIndex = visibleText.length
                            )
                        } else {
                            nextCompletedStreamingTextEnd(
                                text = target,
                                currentIndex = visibleText.length,
                                completionStartIndex = completionStartIndex,
                                completionFrame = completionFrame
                            )
                        }
                    )
                }
            }
            if (!isStreaming && visibleText == target) {
                shouldAnimatePendingText = false
                return@LaunchedEffect
            }
            delay(STREAMING_TEXT_FRAME_MILLIS)
        }
    }

    return visibleText
}

/**
 * Turns bursty arrivals into an even per-frame flow of characters.
 *
 * Providers deliver text in irregular bursts (3 characters in one publish, 200 in the next). Showing
 * each burst at once made the layout grow by a variable number of lines at irregular moments, which
 * no fade or scroll smoothing can hide. The pacer releases characters at the measured arrival rate,
 * keeps roughly [bufferMillis] of text in reserve to absorb the gaps between bursts, works a larger
 * backlog off over [catchUpMillis], and low-passes its own speed over [speedTimeConstantMillis] so a
 * burst landing does not visibly change the pace. No character waits much longer than
 * [maxLatencyMillis]. After the stream ends the rest drains within about [drainMillis].
 */
internal class StreamingTextPacer(
    private val bufferMillis: Float = 220f,
    private val catchUpMillis: Float = 900f,
    private val speedTimeConstantMillis: Float = 250f,
    private val maxLatencyMillis: Float = 700f,
    private val drainMillis: Float = 400f,
    private val rateTimeConstantMillis: Float = 1_200f,
    private val minCharsPerMillis: Float = 0.02f,
    private val minDrainCharsPerMillis: Float = 0.25f
) {
    private var observedTotal = -1
    private var arrivedSinceStart = 0
    private var millisSinceStart = 0f
    private var arrivalCharsPerMillis = 0f
    private var releaseCharsPerMillis = 0f
    private var credit = 0f

    /** Returns how many characters to reveal in a frame lasting [frameMillis]. */
    fun advance(frameMillis: Float, targetTotal: Int, backlog: Int, isStreaming: Boolean): Int {
        val dt = frameMillis.coerceIn(1f, 64f)
        if (observedTotal < 0 || targetTotal < observedTotal) observedTotal = targetTotal
        val arrived = targetTotal - observedTotal
        observedTotal = targetTotal
        if (arrived > 0 || arrivedSinceStart > 0) {
            arrivedSinceStart += arrived
            millisSinceStart += dt
            arrivalCharsPerMillis = if (millisSinceStart < rateTimeConstantMillis) {
                // Until one time constant has passed, the plain average is a better estimate than an
                // average that starts from zero.
                arrivedSinceStart / maxOf(millisSinceStart, WARM_UP_MIN_MILLIS)
            } else {
                arrivalCharsPerMillis + (arrived / dt - arrivalCharsPerMillis) * (1f - exp(-dt / rateTimeConstantMillis))
            }
        }
        if (backlog <= 0) {
            credit = 0f
            releaseCharsPerMillis = 0f
            return 0
        }
        val perMillis = if (isStreaming) {
            val reserve = arrivalCharsPerMillis * bufferMillis
            val wanted = (arrivalCharsPerMillis + (backlog - reserve) / catchUpMillis)
                .coerceAtLeast(maxOf(arrivalCharsPerMillis * 0.4f, minCharsPerMillis))
            releaseCharsPerMillis += (wanted - releaseCharsPerMillis) * (1f - exp(-dt / speedTimeConstantMillis))
            maxOf(releaseCharsPerMillis, backlog / maxLatencyMillis, minCharsPerMillis)
        } else {
            // The floor ends the drain in finite time instead of slowing down on the last few characters.
            maxOf(arrivalCharsPerMillis, backlog / drainMillis, minDrainCharsPerMillis)
                .also { releaseCharsPerMillis = it }
        }
        credit = (credit + perMillis * dt).coerceAtMost(backlog.toFloat())
        val release = credit.toInt()
        credit -= release
        return release
    }

    private companion object {
        const val WARM_UP_MIN_MILLIS = 150f
    }
}

/**
 * Reveals thinking and answer text through one serialized queue.
 *
 * A thinking backlog always wins; answer text stays staged until the thinking side catches up.
 * If thinking resumes while the answer is revealing, the answer pauses at its current visible
 * boundary and resumes only after the new thinking segment is complete.
 */
@Composable
internal fun rememberSerializedStreamingText(
    targetText: String,
    targetThoughts: String,
    isStreaming: Boolean,
    contentIdentity: Any,
    onVisibleContentChanged: () -> Unit = {}
): SerializedStreamingText {
    var visibleText by remember(contentIdentity) {
        mutableStateOf(targetText)
    }
    var visibleThoughts by remember(contentIdentity) {
        mutableStateOf(targetThoughts)
    }
    var activeSide by remember(contentIdentity) {
        mutableStateOf(
            if (targetThoughts.isNotBlank()) {
                StreamingPresentationSide.THOUGHTS
            } else {
                StreamingPresentationSide.TEXT
            }
        )
    }
    // Survives the restart when streaming ends, so the drain starts from the measured rate.
    val pacer = remember(contentIdentity) { StreamingTextPacer() }
    val latestTargetText by rememberUpdatedState(targetText)
    val latestTargetThoughts by rememberUpdatedState(targetThoughts)
    val latestOnVisibleContentChanged by rememberUpdatedState(onVisibleContentChanged)

    LaunchedEffect(contentIdentity, isStreaming) {
        // Compared by reference: the targets are remembered upstream, so a new instance means new text,
        // and a full comparison of a long answer on every frame is avoided. Null forces a first check.
        var observedText: String? = null
        var observedThoughts: String? = null
        var previousFrameNanos = withFrameNanos { it }

        while (isActive) {
            val frameNanos = withFrameNanos { it }
            val frameMillis = (frameNanos - previousFrameNanos) / 1_000_000f
            previousFrameNanos = frameNanos
            val previousVisibleTextLength = visibleText.length
            val previousVisibleThoughtsLength = visibleThoughts.length
            val textTarget = latestTargetText
            val thoughtsTarget = latestTargetThoughts

            val thoughtsChanged = thoughtsTarget !== observedThoughts
            val textChanged = textTarget !== observedText
            // A rollback or rewrite: keep only what is still a prefix of the new target.
            if (textChanged && (visibleText.length > textTarget.length || !textTarget.startsWith(visibleText))) {
                visibleText = textTarget.take(
                    safeTextEnd(textTarget, commonPrefixLength(visibleText, textTarget))
                )
            }
            if (
                thoughtsChanged &&
                (visibleThoughts.length > thoughtsTarget.length || !thoughtsTarget.startsWith(visibleThoughts))
            ) {
                visibleThoughts = thoughtsTarget.take(
                    safeTextEnd(thoughtsTarget, commonPrefixLength(visibleThoughts, thoughtsTarget))
                )
            }

            when {
                thoughtsChanged -> activeSide = StreamingPresentationSide.THOUGHTS
                textChanged && visibleThoughts.length >= thoughtsTarget.length -> {
                    activeSide = StreamingPresentationSide.TEXT
                }
            }
            observedText = textTarget
            observedThoughts = thoughtsTarget

            val thoughtBacklog = thoughtsTarget.length - visibleThoughts.length
            val textBacklog = textTarget.length - visibleText.length
            if (activeSide == StreamingPresentationSide.THOUGHTS && thoughtBacklog <= 0 && textBacklog > 0) {
                activeSide = StreamingPresentationSide.TEXT
            } else if (activeSide == StreamingPresentationSide.TEXT && textBacklog <= 0 && thoughtBacklog > 0) {
                activeSide = StreamingPresentationSide.THOUGHTS
            }
            val release = pacer.advance(
                frameMillis = frameMillis,
                targetTotal = thoughtsTarget.length + textTarget.length,
                backlog = thoughtBacklog + textBacklog,
                isStreaming = isStreaming
            )
            if (release > 0) {
                when (activeSide) {
                    StreamingPresentationSide.THOUGHTS -> visibleThoughts = thoughtsTarget.take(
                        safeTextEnd(thoughtsTarget, visibleThoughts.length + minOf(release, thoughtBacklog))
                    )
                    StreamingPresentationSide.TEXT -> visibleText = textTarget.take(
                        safeTextEnd(textTarget, visibleText.length + minOf(release, textBacklog))
                    )
                }
            }

            if (
                visibleText.length > previousVisibleTextLength ||
                visibleThoughts.length > previousVisibleThoughtsLength
            ) {
                latestOnVisibleContentChanged()
            }

            if (!isStreaming && visibleThoughts == thoughtsTarget && visibleText == textTarget) {
                return@LaunchedEffect
            }
        }
    }

    // Any unrevealed text counts, including answer text staged behind thinking: callers use these to
    // keep Markdown accumulation and fade-in in streaming mode until everything is on screen.
    return SerializedStreamingText(
        text = visibleText,
        thoughts = visibleThoughts,
        isTextAnimating = visibleText != targetText,
        isThoughtTextAnimating = visibleThoughts != targetThoughts
    )
}

internal fun appendStreamingTextTail(text: String, isStreaming: Boolean): String =
    if (isStreaming && text.isNotBlank()) text + STREAMING_TEXT_TAIL else text

internal fun nextStreamingTextEnd(
    text: String,
    currentIndex: Int
): Int {
    if (currentIndex >= text.length) return text.length

    val remaining = text.length - currentIndex
    val baseStep = when {
        remaining > 180 -> 12
        remaining > 72 -> 8
        remaining > 24 -> 5
        else -> 3
    }
    val hardEnd = safeTextEnd(text, (currentIndex + baseStep).coerceAtMost(text.length))
    val searchEnd = (currentIndex + baseStep + 4).coerceAtMost(text.length)
    val naturalEnd = (hardEnd..searchEnd).firstOrNull { index ->
        index > currentIndex && isStreamingBreakChar(text[index - 1])
    }
    return safeTextEnd(text, naturalEnd ?: hardEnd).coerceAtLeast((currentIndex + 1).coerceAtMost(text.length))
}

internal fun nextCompletedStreamingTextEnd(
    text: String,
    currentIndex: Int,
    completionStartIndex: Int,
    completionFrame: Int
): Int {
    if (currentIndex >= text.length) return text.length

    val stableEnd = nextStreamingTextEnd(text, currentIndex)
    if (completionFrame <= 1) return stableEnd
    if (completionFrame >= STREAMING_TEXT_COMPLETION_FRAME_COUNT) return text.length

    val safeStart = completionStartIndex.coerceIn(0, currentIndex)
    val progress = completionFrame.toDouble() / STREAMING_TEXT_COMPLETION_FRAME_COUNT
    val easedProgress = progress * progress
    val scheduledEnd = safeStart + kotlin.math.ceil((text.length - safeStart) * easedProgress).toInt()
    return safeTextEnd(
        text,
        maxOf(stableEnd, scheduledEnd).coerceAtMost(text.length)
    )
}

private fun commonPrefixLength(first: String, second: String): Int {
    val limit = minOf(first.length, second.length)
    var index = 0
    while (index < limit && first[index] == second[index]) {
        index++
    }
    return index
}

private fun safeTextEnd(text: String, end: Int): Int {
    if (end <= 0 || end >= text.length) return end.coerceIn(0, text.length)
    return if (Character.isHighSurrogate(text[end - 1])) {
        (end + 1).coerceAtMost(text.length)
    } else {
        end
    }
}

private fun isStreamingBreakChar(char: Char): Boolean =
    char.isWhitespace() || char in ",.;:!?，。！？；、)]}）】》"
