package cn.nabr.chatwithchat.presentation.ui.chat.richcontent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.richcontent.RichContentAccumulator
import cn.nabr.chatwithchat.data.richcontent.RichContentParser
import cn.nabr.chatwithchat.data.richcontent.RichContentProjection
import cn.nabr.chatwithchat.data.richcontent.RichDocument
import cn.nabr.chatwithchat.data.richcontent.RichNode
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionEvent
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionIdentity
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionSessionState
import cn.nabr.chatwithchat.presentation.common.isSettingsDarkTheme
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.ui.chat.ChatMarkdown
import cn.nabr.chatwithchat.presentation.ui.chat.LocalStreamingReveal
import cn.nabr.chatwithchat.presentation.ui.chat.LocalStreamingSourceOffset
import cn.nabr.chatwithchat.presentation.ui.chat.RichInteractionHostBinding
import cn.nabr.chatwithchat.presentation.ui.chat.StreamingRevealSession
import cn.nabr.chatwithchat.presentation.ui.chat.rememberStreamingRevealDrawing

private data class RichUiEventContext(
    val state: RichInteractionSessionState?,
    val dispatch: (RichInteractionEvent) -> Unit
)

private val LocalRichUiEvents = staticCompositionLocalOf { RichUiEventContext(null, {}) }

@Composable
fun RichChatContent(
    content: String,
    modifier: Modifier = Modifier,
    contentIdentity: Any = content,
    isStreaming: Boolean = false,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    interactionBinding: RichInteractionHostBinding? = null,
    onInteractionEvent: (RichInteractionIdentity, RichInteractionEvent) -> Unit = { _, _ -> }
) {
    val components = LocalRichUiComponentRegistry.current
    val accumulator = remember(contentIdentity, components) { RichContentAccumulator(RichContentParser(components.catalog)) }
    val document = remember(content, contentIdentity, isStreaming, accumulator) {
        accumulator.update(
            fullText = content,
            isStreaming = isStreaming,
            generation = contentIdentity.hashCode().toLong()
        ).document
    }
    val state = interactionBinding?.session?.collectAsStateWithLifecycle()?.value
    val matches = interactionBinding != null &&
        state != null &&
        !isStreaming &&
        interactionBinding.document.source == content &&
        state.identity == interactionBinding.identity &&
        state.documentGeneration == interactionBinding.document.generation
    CompositionLocalProvider(
        LocalRichUiEvents provides RichUiEventContext(if (matches) state else null) { event ->
            if (matches) onInteractionEvent(interactionBinding.identity, event)
        }
    ) {
        RichChatContent(document = document, modifier = modifier, contentColor = contentColor)
    }
}

@Composable
fun RichChatContent(
    document: RichDocument,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onBackground
) {
    val inheritedSession = LocalStreamingReveal.current
    val ownSession = remember(document.generation) { StreamingRevealSession() }
    if (inheritedSession == null) ownSession.updateSource(document.source, document.isStreaming)
    val session = inheritedSession ?: ownSession
    CompositionLocalProvider(LocalStreamingReveal provides session) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            document.nodes.filterNot(::isBlankRichMarkdown).forEach { node ->
                key(node.range.start, node::class.simpleName) {
                    RichNodeContent(node = node, contentColor = contentColor)
                }
            }
        }
    }
}

@Composable
private fun RichNodeContent(node: RichNode, contentColor: Color) {
    CompositionLocalProvider(LocalStreamingSourceOffset provides node.range.start) {
        when (node) {
            is RichNode.Markdown -> if (node.text.isNotBlank()) {
                ChatMarkdown(
                    content = node.text,
                    contentIdentity = "markdown:${node.range.start}",
                    contentColor = contentColor
                )
            }
            is RichNode.Invalid -> FallbackText(node.raw, contentColor)
            is RichNode.Incomplete -> FallbackText(node.raw, contentColor)
            is RichNode.Component -> ComponentContent(node, contentColor)
        }
    }
}

@Composable
private fun ComponentContent(node: RichNode.Component, contentColor: Color) {
    val children = node.children
    val definition = LocalRichUiComponentRegistry.current.definition(node.name)
    if (definition != null) {
        val events = LocalRichUiEvents.current
        definition.Content(
            RichUiComponentScope(node, events.state, contentColor, events.dispatch) {
                ChildColumn(children, contentColor)
            }
        )
        return
    }
    when (node.name) {
        "ui-card" -> SectionContent(node.attributes["title"], contentColor) {
            ChildColumn(children, contentColor)
        }
        "ui-row" -> GroupedRowContent(children, contentColor)
        "ui-col" -> ChildColumn(children, contentColor)
        "ui-metric" -> MetricContent(node, contentColor)
        "ui-callout" -> CalloutContent(node, contentColor)
        "ui-steps" -> StepsContent(children, contentColor)
        "ui-step" -> StepContent(1, node, contentColor)
        else -> FallbackText(RichContentProjection.toMarkdown(node), contentColor)
    }
}

@Composable
private fun SectionContent(title: String?, contentColor: Color, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        title?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = contentColor)
        }
        content()
    }
}

@Composable
private fun MetricContent(node: RichNode.Component, contentColor: Color, isGrouped: Boolean = false) {
    val colors = settingsMaterialColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = contentColor
    ) {
        val accent = richToneColor(node.attributes["tone"] ?: "neutral")
        Column(
            modifier = if (isGrouped) Modifier else Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(node.attributes["label"].orEmpty(), style = MaterialTheme.typography.labelMedium, color = colors.secondaryLabel)
            Text(node.attributes["value"].orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = contentColor)
            if (node.attributes["delta"] != null || node.attributes["trend"] != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    node.attributes["delta"]?.let {
                        Text(
                            text = it,
                            modifier = if (node.attributes["trend"] != null) Modifier.weight(1f, fill = false) else Modifier,
                            style = MaterialTheme.typography.bodySmall,
                            color = accent
                        )
                    }
                    node.attributes["trend"]?.let { trend ->
                        val (direction, descriptionResource) = when (trend) {
                            "up" -> "↑" to R.string.rich_content_trend_up
                            "down" -> "↓" to R.string.rich_content_trend_down
                            else -> "→" to R.string.rich_content_trend_neutral
                        }
                        val description = stringResource(descriptionResource)
                        Text(
                            text = direction,
                            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondaryLabel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalloutContent(node: RichNode.Component, contentColor: Color) {
    val accent = richToneColor(node.attributes["tone"] ?: "info")
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = if (isRtl) size.width - 1.dp.toPx() else 1.dp.toPx()
                drawLine(accent.copy(alpha = 0.65f), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            }
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        node.attributes["title"]?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = accent)
        }
        ChildColumn(node.children, contentColor)
    }
}

@Composable
private fun StepContent(
    index: Int,
    node: RichNode.Component,
    contentColor: Color,
    hasNext: Boolean = false
) {
    val colors = settingsMaterialColors()
    val connectorColor = colors.separator
    val badgeSize = (if (index >= 100) 28.dp else 20.dp) * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = badgeSize)
            .drawBehind {
                val markerSize = badgeSize.toPx()
                val x = if (isRtl) size.width - markerSize / 2 else markerSize / 2
                if (hasNext) {
                    drawLine(
                        color = connectorColor,
                        start = Offset(x, markerSize),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
    ) {
        Box(
            modifier = Modifier.size(badgeSize),
            contentAlignment = Alignment.Center
        ) {
            Text("$index", style = MaterialTheme.typography.labelSmall, color = colors.secondaryLabel)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, bottom = if (hasNext) 8.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            node.attributes["title"]?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = contentColor)
            }
            ChildColumn(node.children, contentColor)
        }
    }
}

@Composable
private fun ChildColumn(children: List<RichNode>, contentColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        children.filterNot(::isBlankRichMarkdown).forEach { child ->
            key(child.range.start, child::class.simpleName) {
                RichNodeContent(child, contentColor)
            }
        }
    }
}

@Composable
private fun richToneColor(tone: String?): Color {
    val isDarkTheme = isSettingsDarkTheme()
    val colors = settingsMaterialColors()
    return when (tone) {
        "success" -> if (isDarkTheme) Color(0xFFA8BDB0) else Color(0xFF476854)
        "warning" -> if (isDarkTheme) Color(0xFFC9B797) else Color(0xFF78613C)
        "error" -> lerp(colors.secondaryLabel, MaterialTheme.colorScheme.error, 0.65f)
        "info" -> if (isDarkTheme) Color(0xFF84B6D9) else Color(0xFF356F9E)
        else -> colors.secondaryLabel
    }
}

@Composable
private fun GroupedRowContent(children: List<RichNode>, contentColor: Color) {
    val bands = remember(children) { richRowBands(children) }
    if (bands.isEmpty()) return
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val colors = settingsMaterialColors()
    val dividerColor = colors.separator
    val isMetricsOnly = bands.flatten().all { it is RichNode.Component && it.name == "ui-metric" }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = contentColor
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val availableWidth = maxWidth
            Column {
                bands.forEachIndexed { bandIndex, band ->
                    if (bandIndex > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = dividerColor)
                    val isCellBand = band.first().isRichRowCell()
                    val minimumCellWidth = if (band.all { it is RichNode.Component && it.name == "ui-metric" }) 132.dp else 220.dp
                    val columns = if (isCellBand) {
                        (availableWidth / (minimumCellWidth * fontScale)).toInt().coerceIn(1, band.size.coerceAtMost(3))
                    } else {
                        1
                    }
                    band.chunked(columns).forEachIndexed { lineIndex, line ->
                        if (lineIndex > 0) HorizontalDivider(modifier = Modifier.padding(vertical = if (isMetricsOnly) 0.dp else 8.dp), color = dividerColor)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    for (divider in 1 until line.size) {
                                        val x = size.width * divider / line.size
                                        drawLine(dividerColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                                    }
                                }
                        ) {
                            line.forEach { child ->
                                key(child.range.start, child::class.simpleName) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = if (isMetricsOnly || line.size > 1) 10.dp else 0.dp, vertical = if (isMetricsOnly) 10.dp else 4.dp)
                                    ) {
                                        if (child is RichNode.Component && child.name == "ui-metric") {
                                            MetricContent(child, contentColor, isGrouped = true)
                                        } else {
                                            RichNodeContent(child, contentColor)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepsContent(children: List<RichNode>, contentColor: Color) {
    val visibleChildren = remember(children) { children.filterNot(::isBlankRichMarkdown) }
    Column(modifier = Modifier.fillMaxWidth()) {
        var stepNumber = 0
        visibleChildren.forEachIndexed { index, child ->
            if (child.isRichStep()) stepNumber++
            val currentStepNumber = stepNumber
            key(child.range.start, child::class.simpleName) {
                if (child is RichNode.Component && child.name == "ui-step") {
                    StepContent(
                        index = currentStepNumber,
                        node = child,
                        contentColor = contentColor,
                        hasNext = visibleChildren.getOrNull(index + 1).isRichStep()
                    )
                } else {
                    Box(modifier = Modifier.padding(vertical = 8.dp)) {
                        RichNodeContent(child, contentColor)
                    }
                }
            }
        }
    }
}

// Prose and fallback nodes occupy their own band, preserving their source position.
// Only adjacent cells explicitly grouped by ui-row can share a comparison line.
internal fun richRowBands(children: List<RichNode>): List<List<RichNode>> {
    val bands = mutableListOf<MutableList<RichNode>>()
    children.filterNot(::isBlankRichMarkdown).forEach { child ->
        if (child.isRichRowCell() && bands.lastOrNull()?.lastOrNull()?.isRichRowCell() == true) {
            bands.last().add(child)
        } else {
            bands.add(mutableListOf(child))
        }
    }
    return bands
}

private fun isBlankRichMarkdown(node: RichNode): Boolean = node is RichNode.Markdown && node.text.isBlank()

private fun RichNode.isRichRowCell(): Boolean = this is RichNode.Component && (name == "ui-col" || name == "ui-metric")

private fun RichNode?.isRichStep(): Boolean = this is RichNode.Component && name == "ui-step"

@Composable
private fun FallbackText(raw: String, contentColor: Color) {
    val offset = LocalStreamingSourceOffset.current
    val drawing = rememberStreamingRevealDrawing(raw, "fallback:$offset", offset)
    Text(
        text = raw,
        onTextLayout = drawing.onTextLayout,
        modifier = Modifier.fillMaxWidth().then(drawing.modifier),
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor
    )
}
