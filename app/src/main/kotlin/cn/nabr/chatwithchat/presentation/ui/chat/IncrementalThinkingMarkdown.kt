package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val THINKING_PENDING_PREVIEW_LENGTH = 4_000
private const val THINKING_PENDING_PREVIEW_PREFIX = "…\n"

internal data class ThinkingMarkdownBlock(
    val id: Int,
    val content: String
)

internal data class ThinkingMarkdownSnapshot(
    val blocks: List<ThinkingMarkdownBlock>,
    val pendingText: String
)

internal class ThinkingMarkdownAccumulator {
    private data class FenceState(
        val marker: Char,
        val length: Int
    )

    private data class FenceLine(
        val marker: Char,
        val length: Int,
        val remainder: String
    )

    private var observedText = ""
    private var scanIndex = 0
    private var blockStartIndex = 0
    private var nextBlockId = 0
    private var committedBlocks: List<ThinkingMarkdownBlock> = emptyList()
    private var openFence: FenceState? = null
    private var blankBoundaryIndex: Int? = null

    fun update(text: String, forceFlush: Boolean): ThinkingMarkdownSnapshot {
        if (shouldReset(text)) reset()

        observedText = text
        processCompleteLines(text)
        if (forceFlush) {
            blankBoundaryIndex = null
            commitPending(text, text.length)
        }
        return snapshot(text)
    }

    private fun shouldReset(text: String): Boolean = when {
        text.length < observedText.length -> true
        text.length == observedText.length -> text != observedText
        else -> !text.startsWith(observedText)
    }

    private fun reset() {
        observedText = ""
        scanIndex = 0
        blockStartIndex = 0
        nextBlockId = 0
        committedBlocks = emptyList()
        openFence = null
        blankBoundaryIndex = null
    }

    private fun processCompleteLines(text: String) {
        var newlineIndex = text.indexOf('\n', scanIndex)
        while (newlineIndex >= 0) {
            val lineEnd = newlineIndex + 1
            val line = text.substring(scanIndex, newlineIndex).trimEnd('\r')
            processLine(
                text = text,
                line = line,
                lineStart = scanIndex,
                lineEnd = lineEnd
            )
            scanIndex = lineEnd
            newlineIndex = text.indexOf('\n', scanIndex)
        }
    }

    private fun processLine(
        text: String,
        line: String,
        lineStart: Int,
        lineEnd: Int
    ) {
        val fenceLine = parseFenceLine(line)
        val activeFence = openFence
        if (activeFence != null) {
            if (
                fenceLine != null &&
                fenceLine.marker == activeFence.marker &&
                fenceLine.length >= activeFence.length &&
                fenceLine.remainder.isBlank()
            ) {
                openFence = null
                blankBoundaryIndex = null
                commitPending(text, lineEnd)
            }
            return
        }

        if (fenceLine != null) {
            resolveBlankBoundary(
                text = text,
                nextLine = line
            )?.let { boundaryIndex ->
                commitPending(text, boundaryIndex)
                blockStartIndex = lineStart
            }
            openFence = FenceState(
                marker = fenceLine.marker,
                length = fenceLine.length
            )
            return
        }

        if (line.isBlank()) {
            if (blankBoundaryIndex == null) {
                blankBoundaryIndex = lineStart
            } else {
                val boundaryIndex = blankBoundaryIndex ?: return
                commitPending(text, boundaryIndex)
                blockStartIndex = lineEnd
                blankBoundaryIndex = null
            }
            return
        }

        val boundaryIndex = blankBoundaryIndex ?: return
        if (continuesAfterBlank(text, line)) {
            blankBoundaryIndex = null
        } else {
            commitPending(text, boundaryIndex)
            blockStartIndex = lineStart
            blankBoundaryIndex = null
        }
    }

    private fun resolveBlankBoundary(
        text: String,
        nextLine: String
    ): Int? {
        val boundaryIndex = blankBoundaryIndex ?: return null
        if (continuesAfterBlank(text, nextLine)) {
            blankBoundaryIndex = null
            return null
        }
        blankBoundaryIndex = null
        return boundaryIndex
    }

    private fun continuesAfterBlank(text: String, nextLine: String): Boolean {
        val firstLineEnd = text.indexOf('\n', blockStartIndex).let { index ->
            if (index == -1) text.length else index
        }
        if (firstLineEnd <= blockStartIndex) return false

        val rawPreviousLine = text.substring(blockStartIndex, firstLineEnd)
        val previousLine = rawPreviousLine.trimStart()
        val nextLineTrimmed = nextLine.trimStart()
        val nextLineIndent = nextLine.length - nextLineTrimmed.length

        if (previousLine.startsWith(">")) {
            return nextLineTrimmed.startsWith(">")
        }
        if (isIndentedCode(rawPreviousLine)) {
            return nextLineIndent >= 4
        }
        if (isListItem(previousLine)) {
            return isListItem(nextLineTrimmed) ||
                nextLineIndent >= 2 ||
                nextLineTrimmed.startsWith(">")
        }
        return false
    }

    private fun isListItem(line: String): Boolean {
        if (
            line.startsWith("- ") ||
            line.startsWith("* ") ||
            line.startsWith("+ ")
        ) {
            return true
        }

        var markerEnd = 0
        while (markerEnd < line.length && line[markerEnd].isDigit()) {
            markerEnd++
        }
        return markerEnd in 1..9 &&
            markerEnd + 1 < line.length &&
            (line[markerEnd] == '.' || line[markerEnd] == ')') &&
            line[markerEnd + 1].isWhitespace()
    }

    private fun isIndentedCode(line: String): Boolean = line.startsWith("    ")

    private fun parseFenceLine(line: String): FenceLine? {
        var leadingSpaces = 0
        while (leadingSpaces < line.length && line[leadingSpaces] == ' ' && leadingSpaces < 4) {
            leadingSpaces++
        }
        if (leadingSpaces > 3 || leadingSpaces >= line.length) return null

        val marker = line[leadingSpaces]
        if (marker != '`' && marker != '~') return null

        var markerEnd = leadingSpaces
        while (markerEnd < line.length && line[markerEnd] == marker) {
            markerEnd++
        }
        val markerLength = markerEnd - leadingSpaces
        if (markerLength < 3) return null

        return FenceLine(
            marker = marker,
            length = markerLength,
            remainder = line.substring(markerEnd)
        )
    }

    private fun commitPending(text: String, endExclusive: Int) {
        val safeEnd = endExclusive.coerceIn(blockStartIndex.coerceAtMost(text.length), text.length)
        var start = blockStartIndex.coerceIn(0, safeEnd)
        var end = safeEnd
        while (start < end && (text[start] == '\n' || text[start] == '\r')) {
            start++
        }
        while (end > start && (text[end - 1] == '\n' || text[end - 1] == '\r')) {
            end--
        }

        if (start < end && text.substring(start, end).isNotBlank()) {
            committedBlocks = committedBlocks + ThinkingMarkdownBlock(
                id = nextBlockId++,
                content = text.substring(start, end)
            )
        }
        blockStartIndex = safeEnd
    }

    private fun snapshot(text: String): ThinkingMarkdownSnapshot {
        val safeStart = blockStartIndex.coerceIn(0, text.length)
        return ThinkingMarkdownSnapshot(
            blocks = committedBlocks,
            pendingText = text.substring(safeStart).trimStart('\n', '\r')
        )
    }
}

internal fun pendingThinkingPreview(
    text: String,
    maxCharacters: Int = THINKING_PENDING_PREVIEW_LENGTH
): String {
    if (text.length <= maxCharacters) return text

    val requestedStart = text.length - maxCharacters
    val safeStart = if (
        requestedStart > 0 &&
        requestedStart < text.length &&
        Character.isLowSurrogate(text[requestedStart])
    ) {
        requestedStart + 1
    } else {
        requestedStart
    }
    return THINKING_PENDING_PREVIEW_PREFIX + text.substring(safeStart)
}

@Composable
internal fun IncrementalThinkingMarkdown(
    thoughts: String,
    isStreaming: Boolean,
    isPendingAnimating: Boolean,
    contentIdentity: Any,
    useMathJax: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val accumulator = remember(contentIdentity) { ThinkingMarkdownAccumulator() }
    val snapshot = remember(contentIdentity, thoughts, isStreaming) {
        accumulator.update(
            text = thoughts,
            forceFlush = !isStreaming
        )
    }

    Column(modifier = modifier) {
        snapshot.blocks.forEach { block ->
            key(contentIdentity, block.id) {
                ChatMarkdown(
                    content = block.content,
                    contentIdentity = "$contentIdentity:block:${block.id}",
                    renderMath = true,
                    useMathJax = useMathJax,
                    contentColor = contentColor,
                    style = ChatMarkdownStyle.THINKING,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (snapshot.pendingText.isNotBlank()) {
            val preview = pendingThinkingPreview(snapshot.pendingText)
            Text(
                text = if (isPendingAnimating && preview.isNotBlank()) {
                    preview + "●"
                } else {
                    preview
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.sp
                ),
                color = contentColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}
