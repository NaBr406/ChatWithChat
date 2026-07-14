package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.AppleBlue
import dev.chungjungsoo.gptmobile.presentation.common.AppleGreen
import dev.chungjungsoo.gptmobile.presentation.common.settingsMaterialColors
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes

private const val CLIPBOARD_LABEL_CODE = "code"
private const val DISPLAY_MATH_PLACEHOLDER_PREFIX = "CHAT_MATH_DISPLAY_"
private const val DISPLAY_MATH_PLACEHOLDER_SUFFIX = "_TOKEN"
private const val DISPLAY_MATH_PLACEHOLDER_TEST_NONCE = "test"

@Composable
fun ChatMarkdown(
    content: String,
    contentIdentity: Any = content,
    renderMath: Boolean = true,
    useMathJax: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val materialColors = settingsMaterialColors()
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
    val components = remember(highlightsBuilder, copyCodeToClipboard, displayMathByPlaceholder, inlineMathByPlaceholder, inlineMathContent, annotator, useMathJax, materialColors, contentColor) {
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
            heading1 = { model -> ChatMarkdownHeading(model, model.typography.h1, topPadding = 10.dp, bottomPadding = 4.dp) },
            heading2 = { model -> ChatMarkdownHeading(model, model.typography.h2, topPadding = 8.dp, bottomPadding = 4.dp) },
            heading3 = { model -> ChatMarkdownHeading(model, model.typography.h3, topPadding = 6.dp, bottomPadding = 3.dp) },
            heading4 = { model -> ChatMarkdownHeading(model, model.typography.h4, topPadding = 4.dp, bottomPadding = 2.dp) },
            heading5 = { model -> ChatMarkdownHeading(model, model.typography.h5, topPadding = 4.dp, bottomPadding = 2.dp) },
            heading6 = { model -> ChatMarkdownHeading(model, model.typography.h6, topPadding = 4.dp, bottomPadding = 2.dp) },
            setextHeading1 = { model ->
                ChatMarkdownHeading(
                    model = model,
                    style = model.typography.h1,
                    topPadding = 10.dp,
                    bottomPadding = 4.dp,
                    contentType = MarkdownTokenTypes.SETEXT_CONTENT
                )
            },
            setextHeading2 = { model ->
                ChatMarkdownHeading(
                    model = model,
                    style = model.typography.h2,
                    topPadding = 8.dp,
                    bottomPadding = 4.dp,
                    contentType = MarkdownTokenTypes.SETEXT_CONTENT
                )
            },
            blockQuote = { model -> ChatMarkdownBlockQuote(model, contentColor) },
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
            },
            horizontalRule = {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    thickness = 0.5.dp,
                    color = materialColors.separatorStrong
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
                    codeBackground = materialColors.field,
                    inlineCodeBackground = contentColor.copy(alpha = 0.1f),
                    dividerColor = materialColors.separatorStrong,
                    tableBackground = materialColors.field
                ),
                inlineContent = markdownInlineContent(inlineMathContent),
                annotator = annotator,
                components = components,
                typography = chatMarkdownTypography(),
                padding = chatMarkdownPadding(),
                dimens = chatMarkdownDimens(),
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
    val materialColors = settingsMaterialColors()
    var isCopied by remember(code) { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(1_500)
            isCopied = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = materialColors.field,
        contentColor = materialColors.primaryLabel,
        tonalElevation = 0.dp,
        border = BorderStroke(
            width = 0.5.dp,
            color = materialColors.separatorStrong
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(materialColors.grouped)
                    .defaultMinSize(minHeight = 44.dp)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    text = language?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.code),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        letterSpacing = 0.sp
                    ),
                    color = materialColors.secondaryLabel,
                    maxLines = 1
                )
                IconButton(
                    modifier = Modifier.size(44.dp),
                    onClick = {
                        onCopyCode(code)
                        isCopied = true
                    }
                ) {
                    Icon(
                        imageVector = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(
                            if (isCopied) R.string.code_copied else R.string.copy_code
                        ),
                        tint = if (isCopied) AppleGreen else AppleBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = materialColors.separatorStrong
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
    val materialColors = settingsMaterialColors()
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
        style = style.copy(
            color = materialColors.primaryLabel,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            letterSpacing = 0.sp
        ),
        softWrap = false,
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
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

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val columnWidths = remember(table, style, density, textMeasurer) {
        table.columnWidths(
            textMeasurer = textMeasurer,
            style = style,
            density = density
        )
    }
    val materialColors = settingsMaterialColors()
    val tableShape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        Surface(
            shape = tableShape,
            color = materialColors.field,
            contentColor = materialColors.primaryLabel,
            border = BorderStroke(0.5.dp, materialColors.separatorStrong),
            tonalElevation = 0.dp
        ) {
            Column {
                ChatMarkdownTableRow(
                    cells = table.header,
                    columnWidths = columnWidths,
                    style = style,
                    inlineContent = inlineContent,
                    inlineMathByPlaceholder = inlineMathByPlaceholder,
                    backgroundColor = materialColors.grouped,
                    contentColor = materialColors.primaryLabel,
                    isHeader = true
                )
                if (table.rows.isNotEmpty()) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = materialColors.separatorStrong
                    )
                }
                table.rows.forEachIndexed { index, row ->
                    ChatMarkdownTableRow(
                        cells = row,
                        columnWidths = columnWidths,
                        style = style,
                        inlineContent = inlineContent,
                        inlineMathByPlaceholder = inlineMathByPlaceholder,
                        backgroundColor = materialColors.field,
                        contentColor = materialColors.primaryLabel,
                        isHeader = false
                    )
                    if (index < table.rows.lastIndex) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = materialColors.separator
                        )
                    }
                }
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
    contentColor: Color,
    isHeader: Boolean
) {
    Row(modifier = Modifier.background(backgroundColor)) {
        columnWidths.forEachIndexed { index, width ->
            val cellText = cells.getOrElse(index) { "" }
            val annotatedCell = remember(cellText, inlineMathByPlaceholder) {
                buildInlineMathAnnotatedString(cellText, inlineMathByPlaceholder)
            }
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                BasicText(
                    text = annotatedCell,
                    modifier = Modifier
                        .width(width)
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    style = style.copy(
                        color = contentColor,
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

private fun ParsedMarkdownTable.columnWidths(
    textMeasurer: TextMeasurer,
    style: TextStyle,
    density: Density
): List<Dp> {
    val allRows = listOf(header) + rows
    return header.indices.map { columnIndex ->
        val measuredWidth = allRows.maxOfOrNull { row ->
            val cell = row.getOrNull(columnIndex)?.visibleTableText().orEmpty()
            textMeasurer.measure(
                text = cell,
                style = style,
                maxLines = 1
            ).size.width
        } ?: 0
        (with(density) { measuredWidth.toDp() } + 24.dp).coerceIn(112.dp, 248.dp)
    }
}

private fun String.visibleTableText(): String = replace("""\s+""".toRegex(), " ")
    .replace("""CHAT_MATH_INLINE_\d+_TOKEN""".toRegex(), "formula")

@Composable
private fun ChatMarkdownHeading(
    model: MarkdownComponentModel,
    style: TextStyle,
    topPadding: Dp,
    bottomPadding: Dp,
    contentType: IElementType? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = bottomPadding)
    ) {
        if (contentType == null) {
            MarkdownHeader(model.content, model.node, style)
        } else {
            MarkdownHeader(model.content, model.node, style, contentType)
        }
    }
}

@Composable
private fun ChatMarkdownBlockQuote(
    model: MarkdownComponentModel,
    contentColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = contentColor.copy(alpha = 0.08f),
        contentColor = contentColor,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = AppleBlue,
                        size = Size(width = 3.dp.toPx(), height = size.height)
                    )
                }
        ) {
            MarkdownBlockQuote(model.content, model.node, model.typography.quote)
        }
    }
}

@Composable
private fun chatMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.headlineSmall.copy(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    h2 = MaterialTheme.typography.titleLarge.copy(
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    h3 = MaterialTheme.typography.titleMedium.copy(
        fontSize = 19.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    h4 = chatMarkdownSmallHeadingStyle(),
    h5 = chatMarkdownSmallHeadingStyle(),
    h6 = chatMarkdownSmallHeadingStyle(),
    text = chatMarkdownBodyStyle(),
    code = chatMarkdownCodeStyle(),
    inlineCode = chatMarkdownCodeStyle().copy(lineHeight = 20.sp),
    quote = chatMarkdownBodyStyle(),
    paragraph = chatMarkdownBodyStyle(),
    ordered = chatMarkdownBodyStyle(),
    bullet = chatMarkdownBodyStyle(),
    list = chatMarkdownBodyStyle(),
    table = chatMarkdownBodyStyle()
)

@Composable
private fun chatMarkdownBodyStyle() = MaterialTheme.typography.bodyLarge.copy(
    fontSize = 16.sp,
    lineHeight = 25.sp,
    letterSpacing = 0.sp
)

@Composable
private fun chatMarkdownSmallHeadingStyle() = MaterialTheme.typography.titleMedium.copy(
    fontSize = 17.sp,
    lineHeight = 23.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.sp
)

@Composable
private fun chatMarkdownCodeStyle() = MaterialTheme.typography.bodyMedium.copy(
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
    lineHeight = 21.sp,
    letterSpacing = 0.sp
)

@Composable
private fun chatMarkdownPadding() = markdownPadding(
    block = 6.dp,
    list = 4.dp,
    listItemTop = 3.dp,
    listItemBottom = 3.dp,
    listIndent = 16.dp,
    codeBlock = PaddingValues(top = 6.dp, bottom = 10.dp)
)

@Composable
private fun chatMarkdownDimens() = markdownDimens(
    dividerThickness = 0.5.dp,
    codeBackgroundCornerSize = 4.dp,
    blockQuoteThickness = 0.dp
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
