package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

internal const val THINKING_PENDING_PREVIEW_LENGTH = 4_000
private const val THINKING_PENDING_PREVIEW_PREFIX = "…\n"
private const val NON_PARAGRAPH_LINE_STARTS = "#>-*+=_`~<$\\"
private val DISPLAY_MATH_OPENERS = listOf("$$", "\\[", "\\begin{")

internal data class ThinkingMarkdownBlock(
    val id: Int,
    val content: String,
    val sourceStart: Int = 0
)

internal data class ThinkingMarkdownSnapshot(
    val blocks: List<ThinkingMarkdownBlock>,
    val pendingText: String,
    val pendingStart: Int = 0
)

/**
 * Thinking often arrives as one long run of lines with no blank line between them. Without a soft
 * boundary that whole run stays in the pending tail: its `Text` is laid out again on every publish, the
 * preview window starts sliding past [THINKING_PENDING_PREVIEW_LENGTH], and the end of the stream commits
 * it as one huge Markdown block in a single frame.
 */
internal const val THINKING_SOFT_BREAK_COMMIT_LENGTH = 1_200

/**
 * @param softBreakCommitLength once the pending block reaches this many characters, commit it at the
 * next line break between two plain paragraph lines. 0 commits only at blank lines and fences.
 */
internal class ThinkingMarkdownAccumulator(
    private val softBreakCommitLength: Int = 0
) {
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
    private var newlineSearchIndex = 0
    private var blockStartIndex = 0
    private var nextBlockId = 0
    private val committedBlocks = mutableListOf<ThinkingMarkdownBlock>()
    private var snapshotBlocks: List<ThinkingMarkdownBlock> = emptyList()
    private var openFence: FenceState? = null
    private var blankBoundaryIndex: Int? = null
    private var previousLine: String? = null

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
        newlineSearchIndex = 0
        blockStartIndex = 0
        nextBlockId = 0
        committedBlocks.clear()
        snapshotBlocks = emptyList()
        openFence = null
        blankBoundaryIndex = null
        previousLine = null
    }

    private fun processCompleteLines(text: String) {
        // An unfinished line can span thousands of chunks. Search only the newly appended suffix.
        var newlineIndex = text.indexOf('\n', newlineSearchIndex)
        while (newlineIndex >= 0) {
            val lineEnd = newlineIndex + 1
            val line = text.substring(scanIndex, newlineIndex).trimEnd('\r')
            processLine(
                text = text,
                line = line,
                lineStart = scanIndex,
                lineEnd = lineEnd
            )
            previousLine = line
            scanIndex = lineEnd
            newlineIndex = text.indexOf('\n', scanIndex)
        }
        newlineSearchIndex = text.length
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

        val boundaryIndex = blankBoundaryIndex
        if (boundaryIndex == null) {
            if (shouldSoftCommitBefore(text, line, lineStart)) commitPending(text, lineStart)
            return
        }
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

    /**
     * Splits only between two plain paragraph lines of a block that began as a plain paragraph, so
     * a list, quote, table, heading or display formula is never cut in half.
     */
    private fun shouldSoftCommitBefore(text: String, line: String, lineStart: Int): Boolean {
        if (softBreakCommitLength <= 0 || lineStart - blockStartIndex < softBreakCommitLength) return false
        if (!isPlainParagraphLine(line) || previousLine?.let(::isPlainParagraphLine) != true) return false
        var firstLineStart = blockStartIndex
        while (firstLineStart < lineStart && (text[firstLineStart] == '\n' || text[firstLineStart] == '\r')) {
            firstLineStart++
        }
        val firstLineEnd = text.indexOf('\n', firstLineStart).let { if (it == -1 || it > lineStart) lineStart else it }
        if (!isPlainParagraphLine(text.substring(firstLineStart, firstLineEnd).trimEnd('\r'))) return false
        return DISPLAY_MATH_OPENERS.none { opener ->
            text.indexOf(opener, blockStartIndex).let { index -> index in blockStartIndex until lineStart }
        }
    }

    private fun isPlainParagraphLine(line: String): Boolean {
        if (line.isBlank() || line[0].isWhitespace()) return false
        if (line[0] in NON_PARAGRAPH_LINE_STARTS || '|' in line) return false
        return !isListItem(line)
    }

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
            committedBlocks += ThinkingMarkdownBlock(
                id = nextBlockId++,
                content = text.substring(start, end),
                sourceStart = start
            )
        }
        blockStartIndex = safeEnd
    }

    private fun snapshot(text: String): ThinkingMarkdownSnapshot {
        val safeStart = blockStartIndex.coerceIn(0, text.length)
        val pending = text.substring(safeStart).trimStart('\n', '\r')
        if (snapshotBlocks.size != committedBlocks.size) snapshotBlocks = committedBlocks.toList()
        return ThinkingMarkdownSnapshot(
            blocks = snapshotBlocks,
            pendingText = pending,
            pendingStart = text.length - pending.length
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

/**
 * Renders stable Markdown blocks once and keeps the unfinished tail cheap while text streams.
 * The same accumulator is used for thoughts and answers so fences, lists, and blank-line
 * boundaries are handled consistently.
 */
@Composable
internal fun IncrementalChatMarkdown(
    content: String,
    isStreaming: Boolean,
    isPendingAnimating: Boolean,
    contentIdentity: Any,
    useMathJax: Boolean,
    contentColor: Color,
    style: ChatMarkdownStyle,
    modifier: Modifier = Modifier,
    pendingAsMarkdown: Boolean = false
) {
    val input by rememberUpdatedState(content to isStreaming)
    var snapshot by remember(contentIdentity) {
        mutableStateOf(ThinkingMarkdownSnapshot(emptyList(), ""))
    }
    LaunchedEffect(contentIdentity) {
        // One worker owns the accumulator. Finish each parse, then consume the newest full snapshot;
        // cancelling a parse on every token can starve rendering during a continuous stream.
        val accumulator = ThinkingMarkdownAccumulator(
            softBreakCommitLength = if (style == ChatMarkdownStyle.THINKING) THINKING_SOFT_BREAK_COMMIT_LENGTH else 0
        )
        snapshotFlow { input }.conflate().collect { (text, streaming) ->
            snapshot = withContext(Dispatchers.Default) {
                accumulator.update(text, forceFlush = !streaming)
            }
        }
    }

    Column(modifier = modifier) {
        CommittedMarkdownBlocks(snapshot.blocks, contentIdentity, useMathJax, contentColor, style)

        if (pendingAsMarkdown && snapshot.pendingText.isNotBlank()) {
            // Answers render the unfinished block as Markdown too, so syntax never flashes raw. Only this
            // tail is reparsed per token; committed blocks above keep their parse and layout. The source
            // offset matches the block it will be committed as, so reveal progress carries over.
            key(contentIdentity, "pending", snapshot.pendingStart) {
                CompositionLocalProvider(LocalStreamingSourceOffset provides snapshot.pendingStart) {
                    ChatMarkdown(
                        // A link still arriving would otherwise flash its raw `[label](https://…` syntax
                        // before it closes and turns into a link or citation chip.
                        content = if (isStreaming) snapshot.pendingText.withoutTrailingPartialLink() else snapshot.pendingText,
                        contentIdentity = "$contentIdentity:pending:${snapshot.pendingStart}",
                        renderMath = true,
                        useMathJax = useMathJax,
                        contentColor = contentColor,
                        style = style,
                        modifier = Modifier.fillMaxWidth().graphicsLayer()
                    )
                }
            }
        } else if (snapshot.pendingText.isNotBlank()) {
            val preview = pendingThinkingPreview(snapshot.pendingText)
            val displayed = if (isPendingAnimating && preview.isNotBlank()) preview + "●" else preview
            val prefixLength = if (preview == snapshot.pendingText) 0 else THINKING_PENDING_PREVIEW_PREFIX.length
            val previewStart = snapshot.pendingStart + snapshot.pendingText.length - preview.length + prefixLength
            val drawing = rememberStreamingRevealDrawing(
                text = preview,
                key = "pending:${snapshot.pendingStart}",
                sourceStart = previewStart,
                layoutText = displayed,
                windowPrefixLength = prefixLength
            )
            Text(
                text = displayed,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = if (style == ChatMarkdownStyle.THINKING) 14.sp else 16.sp,
                    lineHeight = if (style == ChatMarkdownStyle.THINKING) 22.sp else 24.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.sp
                ),
                color = contentColor,
                // Own layer: redrawing the tail on every revealed frame must not re-record the blocks above it.
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).graphicsLayer().then(drawing.modifier),
                onTextLayout = drawing.onTextLayout
            )
        }
    }
}

/**
 * Its own restartable function so the loop is skipped while only the tail changes: the accumulator
 * hands out the same list instance until a block is committed. Inline in the caller, the loop ran over
 * every committed block of a long answer on every revealed frame.
 */
@Composable
private fun CommittedMarkdownBlocks(
    blocks: List<ThinkingMarkdownBlock>,
    contentIdentity: Any,
    useMathJax: Boolean,
    contentColor: Color,
    style: ChatMarkdownStyle
) {
    blocks.forEach { block ->
        key(contentIdentity, block.id) {
            CommittedMarkdownBlock(block, contentIdentity, useMathJax, contentColor, style)
        }
    }
}

@Composable
private fun CommittedMarkdownBlock(
    block: ThinkingMarkdownBlock,
    contentIdentity: Any,
    useMathJax: Boolean,
    contentColor: Color,
    style: ChatMarkdownStyle
) {
    val reveal = LocalStreamingReveal.current
    // A committed block never grows. Changing this static local on every append invalidates its
    // entire Markdown subtree, including paragraphs that have long since scrolled off screen.
    val baseline = remember(block, reveal) { reveal?.appendStart }
    // `ChatMarkdown` takes its identity as `Any`, compared by reference; a fresh string per call would
    // stop it from ever skipping.
    val blockIdentity = remember(contentIdentity, block.id) { "$contentIdentity:block:${block.id}" }
    CompositionLocalProvider(
        LocalStreamingSourceOffset provides block.sourceStart,
        LocalStreamingRevealNewLeafBaseline provides baseline
    ) {
        ChatMarkdown(
            content = block.content,
            contentIdentity = blockIdentity,
            renderMath = true,
            useMathJax = useMathJax,
            contentColor = contentColor,
            style = style,
            // Each committed block keeps its own display list, so neither a redrawn tail nor a scroll
            // frame re-records the Markdown of every finished paragraph.
            modifier = Modifier.fillMaxWidth().graphicsLayer()
        )
    }
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
    IncrementalChatMarkdown(
        content = thoughts,
        isStreaming = isStreaming,
        isPendingAnimating = isPendingAnimating,
        contentIdentity = contentIdentity,
        useMathJax = useMathJax,
        contentColor = contentColor,
        style = ChatMarkdownStyle.THINKING,
        modifier = modifier
    )
}
