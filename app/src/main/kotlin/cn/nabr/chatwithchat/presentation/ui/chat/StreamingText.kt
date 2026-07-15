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
private const val STREAMING_TEXT_TAIL = "●"

@Composable
internal fun rememberSmoothedStreamingText(
    targetText: String,
    isStreaming: Boolean,
    contentIdentity: Any
): String {
    var visibleText by remember(contentIdentity) {
        mutableStateOf(if (isStreaming) "" else targetText)
    }
    val latestTargetText by rememberUpdatedState(targetText)

    LaunchedEffect(contentIdentity, targetText, isStreaming) {
        if (!isStreaming) {
            visibleText = targetText
        } else if (visibleText.length > targetText.length || !targetText.startsWith(visibleText)) {
            visibleText = targetText.take(safeTextEnd(targetText, commonPrefixLength(visibleText, targetText)))
        }
    }

    LaunchedEffect(contentIdentity, isStreaming) {
        if (!isStreaming) {
            visibleText = latestTargetText
            return@LaunchedEffect
        }

        while (isActive) {
            val target = latestTargetText
            when {
                target.isEmpty() -> visibleText = ""
                visibleText.length > target.length || !target.startsWith(visibleText) -> {
                    visibleText = target.take(safeTextEnd(target, commonPrefixLength(visibleText, target)))
                }
                visibleText.length < target.length -> {
                    visibleText = target.take(nextStreamingTextEnd(target, visibleText.length))
                }
            }
            delay(STREAMING_TEXT_FRAME_MILLIS)
        }
    }

    return if (isStreaming) visibleText else targetText
}

internal fun appendStreamingTextTail(text: String, isStreaming: Boolean): String =
    if (isStreaming && text.isNotBlank()) text + STREAMING_TEXT_TAIL else text

private fun nextStreamingTextEnd(text: String, currentIndex: Int): Int {
    if (currentIndex >= text.length) return text.length

    val remaining = text.length - currentIndex
    val baseStep = when {
        remaining > 420 -> 42
        remaining > 180 -> 24
        remaining > 72 -> 12
        remaining > 24 -> 6
        else -> 3
    }
    val hardEnd = safeTextEnd(text, (currentIndex + baseStep).coerceAtMost(text.length))
    val searchEnd = (currentIndex + baseStep + 12).coerceAtMost(text.length)
    val naturalEnd = (hardEnd..searchEnd).firstOrNull { index ->
        index > currentIndex && isStreamingBreakChar(text[index - 1])
    }
    return safeTextEnd(text, naturalEnd ?: hardEnd).coerceAtLeast((currentIndex + 1).coerceAtMost(text.length))
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
