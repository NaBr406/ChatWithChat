package cn.nabr.chatwithchat.presentation.ui.chat.richcontent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.data.richcontent.RichContentAccumulator
import cn.nabr.chatwithchat.data.richcontent.RichContentProjection
import cn.nabr.chatwithchat.data.richcontent.RichDocument
import cn.nabr.chatwithchat.data.richcontent.RichNode
import cn.nabr.chatwithchat.presentation.ui.chat.ChatMarkdown

private val RichUiBackground = Color(0x0A000000)

@Composable
fun RichChatContent(
    content: String,
    modifier: Modifier = Modifier,
    contentIdentity: Any = content,
    isStreaming: Boolean = false,
    contentColor: Color = MaterialTheme.colorScheme.onBackground
) {
    val accumulator = remember(contentIdentity) { RichContentAccumulator() }
    val document = remember(content, contentIdentity, isStreaming) {
        accumulator.update(
            fullText = content,
            isStreaming = isStreaming,
            generation = contentIdentity.hashCode().toLong()
        ).document
    }
    RichChatContent(document = document, modifier = modifier, contentColor = contentColor)
}

@Composable
fun RichChatContent(
    document: RichDocument,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onBackground
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        document.nodes.forEach { node ->
            key(node.range.start, node::class.simpleName) {
                RichNodeContent(node = node, contentColor = contentColor)
            }
        }
    }
}

@Composable
private fun RichNodeContent(node: RichNode, contentColor: Color) {
    when (node) {
        is RichNode.Markdown -> if (node.text.isNotEmpty()) {
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

@Composable
private fun ComponentContent(node: RichNode.Component, contentColor: Color) {
    val children = node.children
    when (node.name) {
        "ui-card" -> FramedSurface(node.attributes["title"], contentColor) {
            ChildColumn(children, contentColor)
        }
        "ui-row" -> {
            val rowItems = children.filterIsInstance<RichNode.Component>().filter { it.name == "ui-col" || it.name == "ui-metric" }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val useRow = maxWidth >= 600.dp && rowItems.size <= 3
                if (useRow) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { child ->
                            key(child.range.start, child::class.simpleName) {
                                Column(modifier = Modifier.weight(1f)) {
                                    RichNodeContent(child, contentColor)
                                }
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { child ->
                            key(child.range.start, child::class.simpleName) {
                                RichNodeContent(child, contentColor)
                            }
                        }
                    }
                }
            }
        }
        "ui-col" -> ChildColumn(children, contentColor)
        "ui-metric" -> MetricContent(node, contentColor)
        "ui-callout" -> CalloutContent(node, contentColor)
        "ui-steps" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var stepNumber = 0
            children.forEach { child ->
                if (child is RichNode.Component && child.name == "ui-step") {
                    stepNumber++
                    StepContent(stepNumber, child, contentColor)
                } else {
                    RichNodeContent(child, contentColor)
                }
            }
        }
        "ui-step" -> StepContent(1, node, contentColor)
        else -> FallbackText(RichContentProjection.toMarkdown(node), contentColor)
    }
}

@Composable
private fun FramedSurface(title: String?, contentColor: Color, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = contentColor)
            }
            content()
        }
    }
}

@Composable
private fun MetricContent(node: RichNode.Component, contentColor: Color) {
    Surface(
        modifier = Modifier.widthIn(min = 120.dp),
        shape = MaterialTheme.shapes.small,
        color = RichUiBackground,
        contentColor = contentColor
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(node.attributes["label"].orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(node.attributes["value"].orEmpty(), style = MaterialTheme.typography.titleLarge, color = contentColor)
            node.attributes["delta"]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = contentColor) }
            node.attributes["trend"]?.let { Text("Trend: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun CalloutContent(node: RichNode.Component, contentColor: Color) {
    val tone = node.attributes["tone"]
    val accent = when (tone) {
        "success" -> Color(0xFF2E7D32)
        "warning" -> Color(0xFF8D5E00)
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = accent.copy(alpha = 0.08f),
        contentColor = contentColor,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.32f))
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.attributes["title"]?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = accent)
            }
            ChildColumn(node.children, contentColor)
        }
    }
}

@Composable
private fun StepContent(index: Int, node: RichNode.Component, contentColor: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$index.", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            node.attributes["title"]?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = contentColor)
            }
            ChildColumn(node.children, contentColor)
        }
    }
}

@Composable
private fun ChildColumn(children: List<RichNode>, contentColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        children.forEach { child ->
            key(child.range.start, child::class.simpleName) {
                RichNodeContent(child, contentColor)
            }
        }
    }
}

@Composable
private fun FallbackText(raw: String, contentColor: Color) {
    Text(
        text = raw,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor
    )
}
