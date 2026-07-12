package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.chungjungsoo.gptmobile.R
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CLIPBOARD_LABEL_CODE = "code"
private const val DISPLAY_MATH_PLACEHOLDER_PREFIX = "CHAT_MATH_DISPLAY_"
private const val DISPLAY_MATH_PLACEHOLDER_SUFFIX = "_TOKEN"
private const val DISPLAY_MATH_PLACEHOLDER_TEST_NONCE = "test"
private const val TABLE_MAX_VISIBLE_TEXT_LENGTH = 34

@Composable
fun ChatMarkdown(
    content: String,
    contentIdentity: Any = content,
    renderMath: Boolean = true,
    useMathJax: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = isSystemInDarkTheme()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val parsed = remember(content, renderMath) {
        if (renderMath) {
            parseChatMarkdown(content)
        } else {
            ParsedChatMarkdown(
                blocks = listOf(ChatMarkdownBlock.Markdown(content)),
                inlineMath = emptyList()
            )
        }
    }
    val displayMathNonce = remember(contentIdentity) { stableDisplayMathNonce(contentIdentity) }
    val highlightsBuilder = remember(isDarkTheme) {
        Highlights.Builder().theme(SyntaxThemes.atom(isDarkTheme))
    }
    val combinedMarkdown = remember(parsed.blocks, displayMathNonce) {
        buildCombinedMarkdown(parsed.blocks, displayMathNonce)
    }
    val inlineMathByPlaceholder = remember(parsed.inlineMath) {
        parsed.inlineMath.associateBy { it.placeholder }
    }
    val displayMathByPlaceholder = remember(parsed.blocks, displayMathNonce) {
        parsed.blocks
            .filterIsInstance<ChatMarkdownBlock.DisplayMath>()
            .mapIndexed { index, block ->
                createDisplayMathPlaceholder(index, displayMathNonce) to block
            }
            .toMap()
    }
    val annotator = remember(inlineMathByPlaceholder) {
        markdownAnnotator { source, child ->
            val text = source.substring(child.startOffset, child.endOffset)
            if (!containsInlineMathPlaceholder(text)) {
                false
            } else {
                appendTextWithInlineMath(this, text, inlineMathByPlaceholder)
                true
            }
        }
    }
    val inlineMathContent = remember(parsed.inlineMath, useMathJax) {
        parsed.inlineMath.associate { token ->
            token.placeholder to InlineTextContent(
                placeholder = Placeholder(
                    width = inlineMathWidth(token.tex),
                    height = inlineMathHeight(token.tex),
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                InlineMathView(
                    tex = token.tex,
                    useMathJax = useMathJax
                )
            }
        }
    }
    val copyCodeToClipboard: (String) -> Unit = remember(clipboard, scope) {
        { code ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(CLIPBOARD_LABEL_CODE, code)))
            }
        }
    }
    val components = remember(highlightsBuilder, copyCodeToClipboard, displayMathByPlaceholder, inlineMathByPlaceholder, inlineMathContent, annotator, useMathJax) {
        markdownComponents(
            codeBlock = {
                MarkdownCodeBlock(it.content, it.node, it.typography.code) { code, language, style ->
                    CodeBlockWithCopy(
                        code = code,
                        language = language,
                        onCopyCode = copyCodeToClipboard
                    ) {
                        HighlightedCodeContent(
                            code = code,
                            language = language,
                            style = style,
                            highlightsBuilder = highlightsBuilder
                        )
                    }
                }
            },
            codeFence = {
                MarkdownCodeFence(it.content, it.node, it.typography.code) { code, language, style ->
                    CodeBlockWithCopy(
                        code = code,
                        language = language,
                        onCopyCode = copyCodeToClipboard
                    ) {
                        HighlightedCodeContent(
                            code = code,
                            language = language,
                            style = style,
                            highlightsBuilder = highlightsBuilder
                        )
                    }
                }
            },
            paragraph = { model ->
                val paragraphText = extractNodeText(model.content, model.node).trim()
                val displayMathBlocks = resolveDisplayMathParagraph(
                    paragraphText = paragraphText,
                    displayMathByPlaceholder = displayMathByPlaceholder
                )
                if (displayMathBlocks != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        displayMathBlocks.forEach { displayMath ->
                            DisplayMathView(
                                tex = displayMath.tex,
                                useMathJax = useMathJax,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                            )
                        }
                    }
                } else {
                    DefaultParagraph(model.content, model.node, model.typography.paragraph, annotator)
                }
            },
            table = { model ->
                ChatMarkdownTable(
                    rawTable = extractNodeText(model.content, model.node),
                    style = model.typography.paragraph,
                    inlineContent = inlineMathContent,
                    inlineMathByPlaceholder = inlineMathByPlaceholder
                )
            }
        )
    }
    key(contentIdentity) {
        val markdownState = rememberMarkdownState(
            content = combinedMarkdown,
            retainState = true
        )
        val animations = markdownAnimations(animateTextSize = { this })

        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Markdown(
                markdownState = markdownState,
                colors = markdownColor(
                    text = contentColor,
                    codeBackground = contentColor.copy(alpha = 0.14f),
                    inlineCodeBackground = contentColor.copy(alpha = 0.16f),
                    dividerColor = contentColor.copy(alpha = 0.22f)
                ),
                inlineContent = markdownInlineContent(inlineMathContent),
                annotator = annotator,
                components = components,
                typography = chatMarkdownTypography(),
                animations = animations,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun CodeBlockWithCopy(
    code: String,
    language: String?,
    onCopyCode: (String) -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    modifier = Modifier.padding(end = 12.dp),
                    text = language?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.code),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    modifier = Modifier.heightIn(min = 32.dp),
                    onClick = { onCopyCode(code) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.copy_code),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun HighlightedCodeContent(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder
) {
    val highlightedCode by produceState(
        initialValue = AnnotatedString(code),
        key1 = code,
        key2 = language,
        key3 = highlightsBuilder
    ) {
        value = withContext(Dispatchers.Default) {
            buildHighlightedAnnotatedString(code, language, highlightsBuilder)
        }
    }

    Text(
        text = highlightedCode,
        style = style,
        softWrap = false,
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

private fun buildHighlightedAnnotatedString(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder
): AnnotatedString {
    val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
    val codeHighlights = highlightsBuilder
        .code(code)
        .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
        .build()
        .getHighlights()

    return AnnotatedString.Builder(code).apply {
        codeHighlights.forEach { highlight ->
            val spanStyle = when (highlight) {
                is ColorHighlight -> SpanStyle(color = androidx.compose.ui.graphics.Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(
                style = spanStyle,
                start = highlight.location.start,
                end = highlight.location.end
            )
        }
    }.toAnnotatedString()
}

private fun appendTextWithInlineMath(
    builder: androidx.compose.ui.text.AnnotatedString.Builder,
    text: String,
    inlineMathByPlaceholder: Map<String, InlineMathToken>
) {
    var cursor = 0
    while (cursor < text.length) {
        val nextToken = inlineMathByPlaceholder.keys
            .mapNotNull { placeholder ->
                val start = text.indexOf(placeholder, cursor)
                if (start == -1) null else placeholder to start
            }
            .minByOrNull { it.second }

        if (nextToken == null) {
            builder.append(text.substring(cursor))
            return
        }

        val (placeholder, start) = nextToken
        if (start > cursor) {
            builder.append(text.substring(cursor, start))
        }
        builder.appendInlineContent(placeholder, "[math]")
        cursor = start + placeholder.length
    }
}

private fun inlineMathWidth(tex: String) = tex.estimateInlineMathWidthEm().em

private fun inlineMathHeight(tex: String) = when {
    tex.containsDisplaySizedMath() -> 2.4.em
    tex.containsSuperscriptOrSubscriptMath() -> 1.55.em
    else -> 1.28.em
}

private fun String.estimateInlineMathWidthEm(): Float {
    val normalized = trim()
    if (normalized.isEmpty()) return 0.6f

    val compact = normalized.replace("""\s+""".toRegex(), "")
    estimateSimpleInlineMathWidthEm(compact)?.let { return it }

    val visibleLength = normalized.visibleMathLength().coerceAtLeast(1)
    val perCharacterWidth = when {
        visibleLength <= 3 -> 0.48f
        visibleLength <= 10 -> 0.54f
        else -> 0.58f
    }
    val padding = when {
        visibleLength <= 3 -> 0.2f
        visibleLength <= 10 -> 0.45f
        else -> 0.8f
    }
    val tallOperatorBonus = if (containsDisplaySizedMath()) 0.85f else 0f

    return (visibleLength * perCharacterWidth + padding + tallOperatorBonus).coerceIn(0.72f, 42f)
}

private fun estimateSimpleInlineMathWidthEm(compactTex: String): Float? {
    if (compactTex.length == 1 && compactTex[0].isLetterOrDigit()) {
        return 0.78f
    }

    val scriptMarkerIndex = compactTex.indexOfFirst { it == '_' || it == '^' }
    if (scriptMarkerIndex <= 0) return null

    val base = compactTex.substring(0, scriptMarkerIndex)
    if (!base.isSimpleMathAtom()) return null

    val scriptText = compactTex.substring(scriptMarkerIndex + 1)
        .removeSurrounding("{", "}")
    if (scriptText.isBlank()) return null

    val baseWidth = if (base.startsWith("\\")) 1.05f else 0.78f
    val scriptWidth = scriptText.visibleMathLength().coerceAtLeast(1) * 0.34f
    return (baseWidth + scriptWidth + 0.22f).coerceAtMost(2.4f)
}

private fun String.isSimpleMathAtom(): Boolean = matches("""[A-Za-z0-9]""".toRegex()) ||
    matches("""\\[a-zA-Z]+""".toRegex())

private fun String.visibleMathLength(): Int = replace("""\\[a-zA-Z]+""".toRegex(), "m")
    .replace("""\\[,;:!]""".toRegex(), "")
    .replace("""[{}_^]""".toRegex(), "")
    .replace("""\s+""".toRegex(), "")
    .length

private fun resolveDisplayMathParagraph(
    paragraphText: String,
    displayMathByPlaceholder: Map<String, ChatMarkdownBlock.DisplayMath>
): List<ChatMarkdownBlock.DisplayMath>? {
    if (paragraphText.isEmpty()) return null

    val placeholders = displayMathByPlaceholder.keys
        .filter(paragraphText::contains)
    if (placeholders.isEmpty()) return null

    val nonPlaceholderText = placeholders.fold(paragraphText) { current, placeholder ->
        current.replace(placeholder, " ")
    }
    if (nonPlaceholderText.isNotBlank()) return null

    return placeholders.mapNotNull(displayMathByPlaceholder::get)
}

private fun String.containsDisplaySizedMath(): Boolean = listOf(
    "\\frac",
    "\\dfrac",
    "\\tfrac",
    "\\sum",
    "\\prod",
    "\\int",
    "\\oint",
    "\\lim",
    "\\begin",
    "\\left",
    "\\right",
    "\\over"
).any(::contains)

private fun String.containsSuperscriptOrSubscriptMath(): Boolean = contains('^') || contains('_')

internal fun buildCombinedMarkdown(
    blocks: List<ChatMarkdownBlock>,
    nonce: String = DISPLAY_MATH_PLACEHOLDER_TEST_NONCE
): String = buildString {
    var displayMathIndex = 0
    blocks.forEach { block ->
        when (block) {
            is ChatMarkdownBlock.Markdown -> append(block.content)

            is ChatMarkdownBlock.DisplayMath -> appendDisplayMathPlaceholder(
                createDisplayMathPlaceholder(displayMathIndex++, nonce)
            )
        }
    }
}

private fun StringBuilder.appendDisplayMathPlaceholder(placeholder: String) {
    val linePrefix = currentLinePrefix().orEmpty()
    if (!isEmpty() && !endsWithBlankLine()) {
        if (last() != '\n') {
            appendLine()
        }
        appendLine()
    }
    if (linePrefix.isNotEmpty()) {
        append(linePrefix)
    }
    append(placeholder)
    appendLine()
    appendLine()
}

private fun StringBuilder.endsWithBlankLine(): Boolean = length >= 2 && this[length - 1] == '\n' && this[length - 2] == '\n'

private fun StringBuilder.currentLinePrefix(): String? {
    val lineStart = lastIndexOf('\n').let { if (it == -1) 0 else it + 1 }
    if (lineStart == length) return ""

    val currentLine = substring(lineStart, length)
    return currentLine.takeIf { line ->
        line.all { character ->
            character == ' ' || character == '\t' || character == '>'
        }
    }
}

private fun createDisplayMathPlaceholder(
    index: Int,
    nonce: String
): String = "\uE000$DISPLAY_MATH_PLACEHOLDER_PREFIX${nonce}_$index$DISPLAY_MATH_PLACEHOLDER_SUFFIX\uE001"

private fun stableDisplayMathNonce(contentIdentity: Any): String {
    val identity = contentIdentity.toString()
    return "${identity.length}_${identity.hashCode()}"
}

private data class ParsedMarkdownTable(
    val header: List<String>,
    val rows: List<List<String>>
)

@Composable
private fun ChatMarkdownTable(
    rawTable: String,
    style: TextStyle,
    inlineContent: Map<String, InlineTextContent>,
    inlineMathByPlaceholder: Map<String, InlineMathToken>
) {
    val table = remember(rawTable) { parseMarkdownTable(rawTable) }
    if (table == null) {
        Text(
            text = rawTable,
            style = style,
            color = LocalContentColor.current
        )
        return
    }

    val columnWidths = remember(table) { table.columnWidths() }
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
    val headerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val rowColor = MaterialTheme.colorScheme.surface
    val alternateRowColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.54f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
        ) {
            ChatMarkdownTableRow(
                cells = table.header,
                columnWidths = columnWidths,
                style = style,
                inlineContent = inlineContent,
                inlineMathByPlaceholder = inlineMathByPlaceholder,
                backgroundColor = headerColor,
                isHeader = true
            )
            table.rows.forEachIndexed { index, row ->
                ChatMarkdownTableRow(
                    cells = row,
                    columnWidths = columnWidths,
                    style = style,
                    inlineContent = inlineContent,
                    inlineMathByPlaceholder = inlineMathByPlaceholder,
                    backgroundColor = if (index % 2 == 0) rowColor else alternateRowColor,
                    isHeader = false
                )
            }
        }
    }
}

@Composable
private fun ChatMarkdownTableRow(
    cells: List<String>,
    columnWidths: List<Dp>,
    style: TextStyle,
    inlineContent: Map<String, InlineTextContent>,
    inlineMathByPlaceholder: Map<String, InlineMathToken>,
    backgroundColor: Color,
    isHeader: Boolean
) {
    Row {
        columnWidths.forEachIndexed { index, width ->
            val cellText = cells.getOrElse(index) { "" }
            val annotatedCell = remember(cellText, inlineMathByPlaceholder) {
                buildInlineMathAnnotatedString(cellText, inlineMathByPlaceholder)
            }
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                BasicText(
                    text = annotatedCell,
                    modifier = Modifier
                        .width(width)
                        .defaultMinSize(minHeight = 44.dp)
                        .background(backgroundColor)
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = style.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isHeader) FontWeight.SemiBold else style.fontWeight
                    ),
                    inlineContent = inlineContent
                )
            }
        }
    }
}

private fun buildInlineMathAnnotatedString(
    text: String,
    inlineMathByPlaceholder: Map<String, InlineMathToken>
): AnnotatedString = AnnotatedString.Builder().apply {
    appendTextWithInlineMath(this, text, inlineMathByPlaceholder)
}.toAnnotatedString()

private fun parseMarkdownTable(rawTable: String): ParsedMarkdownTable? {
    val lines = rawTable
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    if (lines.size < 2) return null

    val header = splitMarkdownTableRow(lines[0])
    val delimiter = splitMarkdownTableRow(lines[1])
    if (header.isEmpty() || delimiter.size < header.size || !delimiter.take(header.size).all(::isMarkdownTableDelimiterCell)) {
        return null
    }

    val rows = lines.drop(2)
        .map { splitMarkdownTableRow(it).normalizeTableCellCount(header.size) }
    return ParsedMarkdownTable(header = header, rows = rows)
}

private fun splitMarkdownTableRow(line: String): List<String> {
    val trimmedLine = line.trim()
        .removePrefix("|")
        .removeSuffix("|")
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var isEscaped = false

    trimmedLine.forEach { character ->
        when {
            isEscaped -> {
                cell.append(character)
                isEscaped = false
            }

            character == '\\' -> isEscaped = true
            character == '|' -> {
                cells += cell.toString().trim()
                cell.clear()
            }

            else -> cell.append(character)
        }
    }
    if (isEscaped) {
        cell.append('\\')
    }
    cells += cell.toString().trim()
    return cells
}

private fun isMarkdownTableDelimiterCell(cell: String): Boolean {
    val normalized = cell.trim()
    return normalized.length >= 3 &&
        normalized.all { it == '-' || it == ':' } &&
        normalized.count { it == '-' } >= 3
}

private fun List<String>.normalizeTableCellCount(columnCount: Int): List<String> = when {
    size == columnCount -> this
    size < columnCount -> this + List(columnCount - size) { "" }
    else -> take(columnCount - 1) + drop(columnCount - 1).joinToString(" | ")
}

private fun ParsedMarkdownTable.columnWidths(): List<Dp> {
    val allRows = listOf(header) + rows
    return header.indices.map { columnIndex ->
        val maxLength = allRows.maxOfOrNull { row ->
            row.getOrNull(columnIndex)
                ?.visibleTableTextLength()
                ?: 0
        } ?: 0
        (maxLength.coerceIn(10, TABLE_MAX_VISIBLE_TEXT_LENGTH) * 8).dp
    }
}

private fun String.visibleTableTextLength(): Int = replace("""\s+""".toRegex(), " ")
    .replace("""CHAT_MATH_INLINE_\d+_TOKEN""".toRegex(), "formula")
    .length

@Composable
private fun chatMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.headlineMedium,
    h2 = MaterialTheme.typography.headlineSmall,
    h3 = MaterialTheme.typography.titleLarge,
    h4 = MaterialTheme.typography.titleMedium,
    h5 = MaterialTheme.typography.titleSmall,
    h6 = MaterialTheme.typography.labelLarge,
    text = MaterialTheme.typography.bodyMedium,
    paragraph = MaterialTheme.typography.bodyMedium,
    ordered = MaterialTheme.typography.bodyMedium,
    bullet = MaterialTheme.typography.bodyMedium,
    list = MaterialTheme.typography.bodyMedium
)

@Composable
private fun DefaultParagraph(
    content: String,
    node: org.intellij.markdown.ast.ASTNode,
    style: TextStyle,
    annotator: MarkdownAnnotator
) {
    MarkdownParagraph(
        content,
        node,
        Modifier,
        style,
        annotatorSettings(
            LocalMarkdownTypography.current.textLink,
            LocalMarkdownTypography.current.inlineCode.toSpanStyle(),
            annotator,
            LocalReferenceLinkHandler.current,
            LocalUriHandler.current,
            null
        )
    )
}

private fun extractNodeText(
    content: String,
    node: org.intellij.markdown.ast.ASTNode
): String = content.substring(node.startOffset, node.endOffset)
