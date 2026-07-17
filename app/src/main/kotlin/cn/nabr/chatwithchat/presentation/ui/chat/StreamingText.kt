package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val STREAMING_TEXT_FRAME_MILLIS = 24L
internal const val STREAMING_TEXT_COMPLETION_FRAME_COUNT = 80
private const val STREAMING_TEXT_TAIL = "●"

@Composable
internal fun rememberSmoothedStreamingText(
    targetText: String,
    isStreaming: Boolean,
    animateInitialText: Boolean = isStreaming,
    contentIdentity: Any
): String {
    val shouldAnimateInitialText = isStreaming || animateInitialText
    var visibleText by remember(contentIdentity) {
        mutableStateOf(if (shouldAnimateInitialText) "" else targetText)
    }
    var shouldAnimatePendingText by remember(contentIdentity) {
        mutableStateOf(shouldAnimateInitialText)
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
