package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.AppSourceNavigationTarget
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.database.entity.SafeMessageSourceTarget
import cn.nabr.chatwithchat.data.database.entity.safeDedupeKey
import cn.nabr.chatwithchat.data.database.entity.safeHttpUrlOrNull
import cn.nabr.chatwithchat.data.database.entity.safeNavigationTarget
import cn.nabr.chatwithchat.presentation.common.isSettingsDarkTheme
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import java.net.URI
import java.util.Locale
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/*
 * Inline citations.
 *
 * A citation is never inferred from prose. The only sources are the message's structured
 * [MessageSourceMetadata] (written by the search/fetch tools from what they actually returned), and a
 * link in the answer becomes a citation chip only when its URL resolves to one of those sources. Links
 * to anything else stay ordinary links, and plain text is never touched.
 *
 * The chip replaces the link in the rendered text only. Copy and "select text" work from the stored
 * Markdown, so they keep the original `[label](url)`, and history renders the same way because the
 * sources are persisted with the message.
 */

/** A message's citable sources, de-duplicated in display order. Equality is by the sources themselves. */
@Immutable
internal data class CitationSources(val items: List<MessageSourceMetadata>) {
    private val indexByUrl: Map<String, Int> by lazy {
        buildMap {
            items.forEachIndexed { index, source ->
                val url = (source.safeNavigationTarget() as? SafeMessageSourceTarget.PublicUrl)?.url
                    ?: return@forEachIndexed
                citationUrlKey(url)?.let { key -> putIfAbsent(key, index) }
            }
        }
    }

    val isEmpty: Boolean get() = items.isEmpty()

    fun indexOfUrl(url: String): Int? = citationUrlKey(url)?.let(indexByUrl::get)

    companion object {
        fun of(sources: List<MessageSourceMetadata>): CitationSources = CitationSources(
            sources
                .mapNotNull { source -> source.safeDedupeKey()?.let { key -> key to source } }
                .distinctBy { (key, _) -> key }
                .map { (_, source) -> source }
        )
    }
}

/** What the Markdown renderer needs to turn a matching link into a chip, and what a tap opens. */
@Immutable
internal class ChatCitationContext(
    val sources: CitationSources,
    val onOpenSources: (List<Int>) -> Unit
)

internal val LocalChatCitations = compositionLocalOf<ChatCitationContext?> { null }

/**
 * Comparison key for "is this the same page". Lenient only where two spellings cannot mean different
 * pages: scheme, `www.`, default ports, a trailing slash, the fragment and `utm_*`-style tracking
 * parameters. Path and query are compared decoded, so `%E4%B8%AD` and `中` match.
 */
internal fun citationUrlKey(rawUrl: String): String? {
    val safeUrl = rawUrl.trim().removeSurrounding("<", ">").safeHttpUrlOrNull() ?: return null
    val uri = runCatching { URI(safeUrl) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return null
    val port = uri.port.takeUnless { port -> port == -1 || port == 80 || port == 443 }
    val path = uri.path.orEmpty().trimEnd('/')
    val query = uri.query
        ?.split('&')
        ?.filter { parameter -> parameter.isNotBlank() && !parameter.isTrackingParameter() }
        ?.joinToString("&")
        ?.takeIf { it.isNotEmpty() }
    return buildString {
        append(host)
        port?.let { append(':').append(it) }
        append(path)
        query?.let { append('?').append(it) }
    }
}

private fun String.isTrackingParameter(): Boolean {
    val name = substringBefore('=').lowercase(Locale.US)
    return name.startsWith("utm_") || name in TRACKING_PARAMETERS
}

private val TRACKING_PARAMETERS = setOf("fbclid", "gclid", "spm", "from", "ref_src")

/**
 * Short site name for a chip: `news.bbc.co.uk` → `bbc`, `zh.wikipedia.org` → `wikipedia`.
 * Falls back to the source title, then to the host itself.
 */
internal fun citationSiteName(source: MessageSourceMetadata): String {
    val host = source.url.safeHttpUrlOrNull()
        ?.let { url -> runCatching { URI(url).host }.getOrNull() }
        ?.lowercase(Locale.US)
        ?.removePrefix("www.")
    if (host.isNullOrBlank()) return source.title.ifBlank { "?" }
    if (host.all { it.isDigit() || it == '.' || it == ':' }) return host
    val labels = host.split('.').filter { it.isNotEmpty() }.toMutableList()
    if (labels.size >= 2) labels.removeAt(labels.lastIndex)
    if (labels.size >= 2 && labels.last() in SECOND_LEVEL_SUFFIXES) labels.removeAt(labels.lastIndex)
    return labels.lastOrNull() ?: host
}

private val SECOND_LEVEL_SUFFIXES = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

// ---------------------------------------------------------------------------------------------------
// Detection: the same rules run twice. The renderer's annotator walks the parsed Markdown, and a cheap
// pre-scan over the raw text finds the multi-source groups up front, because inline content (whose
// widths must be measured before layout) has to be registered before the text is built. If the two
// ever disagree, the annotator falls back to one chip per source, which always exists.
// ---------------------------------------------------------------------------------------------------

/**
 * Link text short enough to be a source marker — `[1]`, `[Reuters]`, `[来源]` — rather than words of
 * the sentence. A descriptive link keeps its text and stays a normal link.
 */
internal fun isCitationLabelLike(linkText: String): Boolean {
    val text = linkText.trim()
    val length = text.codePointCount(0, text.length)
    return length in 1..MAX_CITATION_LABEL_CODE_POINTS && text.none { it in SENTENCE_BREAKS }
}

private const val MAX_CITATION_LABEL_CODE_POINTS = 24
private const val SENTENCE_BREAKS = "。！？；!?;\n"

/** Full-width punctuation a bare-URL tokenizer may swallow; it belongs to the sentence, not the URL. */
private const val TRAILING_SENTENCE_PUNCTUATION = "。，、；：！？）】》」』”’"

internal fun citationGroupId(members: List<Int>): String = CITATION_ID_PREFIX + members.joinToString(",")

private const val CITATION_ID_PREFIX = "chat-cite:"

private data class CitationHit(val sourceIndex: Int, val trailing: String)

private fun ASTNode.textIn(content: String): String = content.substring(startOffset, endOffset)

private fun citationHitOf(content: String, node: ASTNode, sources: CitationSources): CitationHit? = when (node.type) {
    MarkdownElementTypes.INLINE_LINK -> {
        val destination = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.textIn(content)
        val linkText = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
            ?.textIn(content)
            ?.removePrefix("[")
            ?.removeSuffix("]")
        if (destination == null || linkText == null || !isCitationLabelLike(linkText)) {
            null
        } else {
            sources.indexOfUrl(destination)?.let { index -> CitationHit(index, trailing = "") }
        }
    }
    MarkdownElementTypes.AUTOLINK -> sources.indexOfUrl(node.textIn(content))?.let { index -> CitationHit(index, "") }
    GFMTokenTypes.GFM_AUTOLINK -> if (node.parent?.type == MarkdownElementTypes.LINK_TEXT) {
        null
    } else {
        bareUrlHit(node.textIn(content), sources)
    }
    else -> null
}

private fun bareUrlHit(rawUrl: String, sources: CitationSources): CitationHit? {
    val url = rawUrl.trimEnd { it in TRAILING_SENTENCE_PUNCTUATION }
    return sources.indexOfUrl(url)?.let { index -> CitationHit(index, trailing = rawUrl.substring(url.length)) }
}

/**
 * Annotator hook: renders a run of adjacent citation links as one chip. Returns true when it consumed
 * [node] — the first link of a run (which draws the whole run), a later link of a run, or the spaces
 * between two links of a run.
 */
internal fun AnnotatedString.Builder.appendCitationIfMatched(
    content: String,
    node: ASTNode,
    sources: CitationSources,
    registeredIds: Set<String>
): Boolean {
    if (node.type !in CITATION_NODE_TYPES) return false
    val siblings = node.parent?.children ?: return false
    val position = siblings.indexOfFirst { it === node }
    if (position < 0) return false

    fun hitAt(index: Int): CitationHit? = siblings.getOrNull(index)?.let { citationHitOf(content, it, sources) }
    fun previousNonSpace(index: Int): Int {
        var cursor = index - 1
        while (cursor >= 0 && siblings[cursor].type == MarkdownTokenTypes.WHITE_SPACE) cursor--
        return cursor
    }
    fun nextNonSpace(index: Int): Int {
        var cursor = index + 1
        while (cursor < siblings.size && siblings[cursor].type == MarkdownTokenTypes.WHITE_SPACE) cursor++
        return cursor
    }

    if (node.type == MarkdownTokenTypes.WHITE_SPACE) {
        // Spaces inside a run would show up as gaps between chips that are drawn as one. Most spaces
        // sit between words, so rule those out by the next character before searching siblings.
        val nextCharacter = content.getOrNull(node.endOffset) ?: return false
        if (nextCharacter !in RUN_CONTINUATION_STARTS) return false
        val before = hitAt(previousNonSpace(position)) ?: return false
        return before.trailing.isEmpty() && hitAt(nextNonSpace(position)) != null
    }

    val first = citationHitOf(content, node, sources) ?: return false
    val previous = hitAt(previousNonSpace(position))
    if (previous != null && previous.trailing.isEmpty()) return true

    val members = mutableListOf(first.sourceIndex)
    var trailing = first.trailing
    var cursor = position
    while (trailing.isEmpty()) {
        val next = nextNonSpace(cursor)
        val hit = hitAt(next) ?: break
        if (hit.sourceIndex !in members) members += hit.sourceIndex
        trailing = hit.trailing
        cursor = next
    }

    val groupId = citationGroupId(members)
    if (groupId in registeredIds) {
        appendInlineContent(groupId, citationAlternateText(sources, members))
    } else {
        members.forEach { member ->
            appendInlineContent(citationGroupId(listOf(member)), citationAlternateText(sources, listOf(member)))
        }
    }
    append(trailing)
    return true
}

/** The text a chip stands in for in the laid-out string, which is what a selection across it copies. */
private fun citationAlternateText(sources: CitationSources, members: List<Int>): String =
    members.mapNotNull { sources.items.getOrNull(it)?.let(::citationSiteName) }.joinToString(prefix = "[", postfix = "]")

private val CITATION_NODE_TYPES = setOf(
    MarkdownTokenTypes.WHITE_SPACE,
    MarkdownElementTypes.INLINE_LINK,
    MarkdownElementTypes.AUTOLINK,
    GFMTokenTypes.GFM_AUTOLINK
)

/** First characters of anything that can continue a run: `[label](url)`, `<url>` or a bare `http…`. */
private val RUN_CONTINUATION_STARTS = setOf('[', '<', 'h')

private val INLINE_LINK_PATTERN = Regex(
    """\[([^\[\]\n]{1,60})\]\(\s*<?(https?://[^\s)>]+)>?(?:\s+"[^"\n]*")?\s*\)|<(https?://[^\s>]+)>|(https?://[^\s<>()\[\]]+)"""
)

/** Multi-source runs in [markdown], as their group ids. Mirrors [appendCitationIfMatched]. */
internal fun scanCitationGroupIds(markdown: String, sources: CitationSources): Set<String> {
    if (sources.isEmpty || !markdown.contains("http")) return emptySet()
    val groups = mutableSetOf<String>()
    var members = mutableListOf<Int>()
    var runEnd = -1
    var runOpen = false

    fun closeRun() {
        if (members.size > 1) groups += citationGroupId(members)
        members = mutableListOf()
        runOpen = false
    }

    INLINE_LINK_PATTERN.findAll(markdown).forEach { match ->
        val inlineText = match.groups[1]?.value
        val hit = when {
            inlineText != null -> if (isCitationLabelLike(inlineText)) {
                match.groups[2]?.value?.let(sources::indexOfUrl)?.let { CitationHit(it, "") }
            } else {
                null
            }
            match.groups[3] != null -> sources.indexOfUrl(match.groups[3]!!.value)?.let { CitationHit(it, "") }
            else -> bareUrlHit(match.groups[4]!!.value, sources)
        }
        if (hit == null) {
            closeRun()
            return@forEach
        }
        val adjacent = runOpen && markdown.substring(runEnd, match.range.first).all { it == ' ' || it == '\t' }
        if (!adjacent) closeRun()
        if (hit.sourceIndex !in members) members += hit.sourceIndex
        runOpen = hit.trailing.isEmpty()
        runEnd = match.range.last + 1
        if (!runOpen) closeRun()
    }
    closeRun()
    return groups
}

/** Hides a link still being streamed (`[Reuters](https://reu`) so its raw syntax never flashes. */
internal fun String.withoutTrailingPartialLink(): String {
    val match = TRAILING_PARTIAL_LINK.find(this) ?: return this
    return substring(0, match.range.first)
}

private val TRAILING_PARTIAL_LINK = Regex("""\[[^\[\]\n]{1,60}\](?:\([^)\s]*)?$""")

// ---------------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------------

/**
 * One measured chip per source plus one per multi-source run in [groupIds], keyed for
 * [appendCitationIfMatched]. Widths are measured here because a placeholder's size is fixed before the
 * text is laid out.
 */
@Composable
internal fun rememberCitationInlineContent(
    context: ChatCitationContext?,
    groupIds: Set<String>
): Map<String, InlineTextContent> {
    if (context == null || context.sources.isEmpty) return emptyMap()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = citationChipTextStyle()
    val latestOnOpen by rememberUpdatedState(context.onOpenSources)
    val sources = context.sources
    return remember(sources, groupIds, labelStyle, density) {
        val singleIds = sources.items.indices.map { index -> listOf(index) }
        val groups = groupIds.mapNotNull { id ->
            id.removePrefix(CITATION_ID_PREFIX)
                .split(',')
                .mapNotNull(String::toIntOrNull)
                .filter { it in sources.items.indices }
                .takeIf { it.size > 1 }
        }
        (singleIds + groups).associate { members ->
            val label = citationChipLabel(sources, members)
            val labelWidth = textMeasurer.measure(label, labelStyle, maxLines = 1).size.width
            val width = with(density) {
                (labelWidth + (CITATION_CHIP_HORIZONTAL_PADDING * 2 + CITATION_CHIP_MARGIN * 2).roundToPx()).toSp()
            }
            citationGroupId(members) to InlineTextContent(
                placeholder = Placeholder(
                    width = width,
                    height = CITATION_CHIP_HEIGHT_EM.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                CitationChip(label = label, onClick = { latestOnOpen(members) })
            }
        }
    }
}

private fun citationChipLabel(sources: CitationSources, members: List<Int>): String {
    val first = sources.items.getOrNull(members.first())?.let(::citationSiteName).orEmpty()
    return if (members.size > 1) "$first +${members.size - 1}" else first
}

@Composable
private fun citationChipTextStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.sp
)

@Composable
private fun CitationChip(label: String, onClick: () -> Unit) {
    val materialColors = settingsMaterialColors()
    val sourceLabel = stringResource(R.string.citation_sheet_title)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CITATION_CHIP_MARGIN, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "$sourceLabel: $label"
                    role = Role.Button
                },
            shape = CircleShape,
            color = citationChipFill(),
            contentColor = materialColors.secondaryLabel
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    style = citationChipTextStyle(),
                    color = materialColors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
private fun citationChipFill() = settingsMaterialColors().primaryLabel.copy(
    alpha = if (isSettingsDarkTheme()) 0.14f else 0.07f
)

/**
 * The sources row under an answer: a capsule with the first sites' initials and a count. It opens the
 * same sheet a citation chip does, listing every source the answer had, cited inline or not.
 */
@Composable
internal fun SourceMetadataBlock(
    sources: List<MessageSourceMetadata>,
    modifier: Modifier = Modifier
) {
    val citationSources = remember(sources) { CitationSources.of(sources) }
    if (citationSources.isEmpty) return
    val materialColors = settingsMaterialColors()
    var isSheetOpen by remember(citationSources) { mutableStateOf(false) }

    Row(modifier = modifier.fillMaxWidth()) {
        Surface(
            onClick = { isSheetOpen = true },
            shape = CircleShape,
            color = citationChipFill(),
            contentColor = materialColors.secondaryLabel
        ) {
            Row(
                modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val previews = citationSources.items.take(SOURCE_PILL_PREVIEW_COUNT)
                Box {
                    previews.forEachIndexed { index, source ->
                        SourceSiteBadge(
                            source = source,
                            size = 20,
                            modifier = Modifier
                                .offset(x = (index * SOURCE_PILL_BADGE_STEP).dp)
                                .border(1.5.dp, materialColors.canvas, CircleShape)
                        )
                    }
                    // Reserve the width the overlapping badges occupy.
                    Box(modifier = Modifier.size(width = (20 + (previews.size - 1) * SOURCE_PILL_BADGE_STEP).dp, height = 20.dp))
                }
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(R.string.sources_count, citationSources.items.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = materialColors.secondaryLabel
                )
            }
        }
    }

    if (isSheetOpen) {
        CitationSourcesSheet(
            title = stringResource(R.string.sources_title),
            sources = citationSources.items,
            onDismiss = { isSheetOpen = false }
        )
    }
}

private const val SOURCE_PILL_PREVIEW_COUNT = 3
private const val SOURCE_PILL_BADGE_STEP = 13

@Composable
internal fun CitationSourcesSheet(
    title: String,
    sources: List<MessageSourceMetadata>,
    onDismiss: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    ChatGlassSelectionSheet(onDismissRequest = onDismiss) {
        Text(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = materialColors.primaryLabel
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = CITATION_SHEET_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sources.forEach { source -> CitationSourceCard(source) }
        }
    }
}

@Composable
private fun CitationSourceCard(source: MessageSourceMetadata) {
    val materialColors = settingsMaterialColors()
    val uriHandler = LocalUriHandler.current
    val target = source.safeNavigationTarget() ?: return
    val publicUrl = (target as? SafeMessageSourceTarget.PublicUrl)?.url
    val siteLabel = when (target) {
        is SafeMessageSourceTarget.PublicUrl -> citationSiteName(source)
        is SafeMessageSourceTarget.LocalApp -> when (target.navigationTarget) {
            AppSourceNavigationTarget.CHAT_ROOM -> stringResource(R.string.source_local_chat)
            AppSourceNavigationTarget.MEMORY -> stringResource(R.string.source_local_memory)
        }
    }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SourceSiteBadge(source = source, size = 28)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = siteLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = materialColors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = source.title.ifBlank { publicUrl ?: (target as SafeMessageSourceTarget.LocalApp).entityId },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = materialColors.primaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                source.snippet.takeIf { it.isNotBlank() }?.let { snippet ->
                    Text(
                        text = snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = materialColors.secondaryLabel,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (publicUrl != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = stringResource(R.string.open_link),
                    tint = materialColors.tertiaryLabel,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(18.dp)
                )
            }
        }
    }
    val shape = RoundedCornerShape(14.dp)
    val border = BorderStroke(0.5.dp, materialColors.separator)
    if (publicUrl != null) {
        Surface(
            onClick = { runCatching { uriHandler.openUri(publicUrl) } },
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = materialColors.grouped,
            border = border,
            content = content
        )
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = materialColors.grouped,
            border = border,
            content = content
        )
    }
}

/** A site's initial in a circle. No favicons: they would mean fetching from every cited site. */
@Composable
private fun SourceSiteBadge(
    source: MessageSourceMetadata,
    size: Int,
    modifier: Modifier = Modifier
) {
    val materialColors = settingsMaterialColors()
    val initial = citationSiteName(source).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "·"
    Box(
        modifier = modifier
            .size(size.dp)
            .background(materialColors.controlFill, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = (size * 0.45f).sp,
                lineHeight = (size * 0.5f).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp
            ),
            color = materialColors.secondaryLabel
        )
    }
}

private val CITATION_CHIP_HORIZONTAL_PADDING = 7.dp
private val CITATION_CHIP_MARGIN = 2.dp
private const val CITATION_CHIP_HEIGHT_EM = 1.25f
private val CITATION_SHEET_MAX_HEIGHT = 520.dp
