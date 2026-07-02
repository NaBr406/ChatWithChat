package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTokenUsage
import dev.chungjungsoo.gptmobile.data.token.TokenUsageRecord
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage
import dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote

enum class RoundStatus {
    Completed,
    Partial,
    Failed,
    Generating
}

data class RoundNavigationItem(
    val turnIndex: Int,
    val displayNumber: Int,
    val questionPreview: String,
    val status: RoundStatus,
    val hasSuccessfulAnswer: Boolean,
    val totalTokens: Int,
    val isEstimated: Boolean,
    val platformUsages: List<PlatformTokenUsageLine>
)

data class PlatformTokenUsageLine(
    val platformName: String,
    val model: String,
    val totalTokens: Int,
    val toolTokens: Int,
    val isEstimated: Boolean
)

@Composable
fun CompletedRoundButton(
    completedRoundCount: Int,
    onClick: () -> Unit
) {
    Text(
        text = "${completedRoundCount}轮",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(end = 2.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        maxLines = 1
    )
}

@Composable
fun RoundNavigatorOverlay(
    items: List<RoundNavigationItem>,
    maxHeight: Dp,
    onDismiss: () -> Unit,
    onTurnClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.16f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss
            )
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.94f)
                .heightIn(max = maxHeight)
                .shadow(14.dp, RoundedCornerShape(8.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {}
                ),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f))
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "回答轮次",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${items.count { it.hasSuccessfulAnswer }}轮",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (items.isEmpty()) {
                    Text(
                        text = "暂无轮次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    items.forEach { item ->
                        RoundNavigatorItem(
                            item = item,
                            onClick = { onTurnClick(item.turnIndex) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundNavigatorItem(
    item: RoundNavigationItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "#${item.displayNumber}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = item.questionPreview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            StatusLabel(item.status)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "合并 ${formatTokenCount(item.totalTokens)}${if (item.isEstimated) " 估算" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item.platformUsages.forEach { usage ->
            Text(
                text = buildString {
                    append(usage.platformName)
                    usage.model.takeIf { it.isNotBlank() }?.let { model ->
                        append(" / ")
                        append(model)
                    }
                    append(" · ")
                    append(formatTokenCount(usage.totalTokens))
                    if (usage.toolTokens > 0) {
                        append(" · 工具 ")
                        append(formatTokenCount(usage.toolTokens))
                    }
                    if (usage.isEstimated) append(" 估算")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatusLabel(status: RoundStatus) {
    val color = when (status) {
        RoundStatus.Completed -> MaterialTheme.colorScheme.secondary
        RoundStatus.Partial -> MaterialTheme.colorScheme.tertiary
        RoundStatus.Failed -> MaterialTheme.colorScheme.error
        RoundStatus.Generating -> MaterialTheme.colorScheme.primary
    }
    Text(
        text = status.label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1
    )
}

fun buildRoundNavigationItems(
    groupedMessages: ChatViewModel.GroupedMessages,
    loadingStates: List<ChatViewModel.LoadingState>,
    enabledPlatformsInChat: List<String>,
    enabledPlatformLookup: Map<String, PlatformV2>
): List<RoundNavigationItem> = groupedMessages.userMessages.mapIndexed { index, userMessage ->
    val assistantRow = groupedMessages.assistantMessages.getOrNull(index).orEmpty()
    val usages = assistantRow.mapNotNull { it.effectiveTokenUsage() }
    val successCount = assistantRow.count { it.hasSuccessfulAssistantAnswer() }
    val failedCount = assistantRow.count { it.hasAssistantFailure() }
    val expectedCount = enabledPlatformsInChat.size.coerceAtLeast(assistantRow.size).coerceAtLeast(1)
    val isGenerating = index == groupedMessages.userMessages.lastIndex &&
        loadingStates.any { it == ChatViewModel.LoadingState.Loading }
    val status = when {
        isGenerating -> RoundStatus.Generating
        successCount > 0 && (failedCount > 0 || successCount < expectedCount) -> RoundStatus.Partial
        successCount > 0 -> RoundStatus.Completed
        else -> RoundStatus.Failed
    }

    RoundNavigationItem(
        turnIndex = index,
        displayNumber = index + 1,
        questionPreview = userMessage.content
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "空提问" },
        status = status,
        hasSuccessfulAnswer = successCount > 0,
        totalTokens = usages.roundTotalTokens(),
        isEstimated = usages.any { it.isEstimated },
        platformUsages = assistantRow.mapIndexedNotNull { platformIndex, assistantMessage ->
            val usage = assistantMessage.effectiveTokenUsage() ?: return@mapIndexedNotNull null
            val platformUid = enabledPlatformsInChat.getOrNull(platformIndex) ?: assistantMessage.platformType.orEmpty()
            val platform = enabledPlatformLookup[platformUid]
            PlatformTokenUsageLine(
                platformName = platform?.name ?: platformUid.ifBlank { "未知平台" },
                model = usage.model.ifBlank { platform?.model.orEmpty() },
                totalTokens = usage.roundTotalTokens(),
                toolTokens = usage.toolTotalTokens,
                isEstimated = usage.isEstimated
            )
        }
    )
}

private val RoundStatus.label: String
    get() = when (this) {
        RoundStatus.Completed -> "已完成"
        RoundStatus.Partial -> "部分完成"
        RoundStatus.Failed -> "失败"
        RoundStatus.Generating -> "生成中"
    }

private fun MessageV2.hasSuccessfulAssistantAnswer(): Boolean {
    val content = effectiveContent()
    if (isAssistantErrorMessage(content)) return false
    return stripAssistantErrorNote(content).trim().isNotBlank()
}

private fun MessageV2.hasAssistantFailure(): Boolean {
    val content = effectiveContent()
    return isAssistantErrorMessage(content) || stripAssistantErrorNote(content) != content
}

private fun TokenUsageRecord.roundTotalTokens(): Int = if (toolTotalTokens > 0) toolTotalTokens else totalTokens
