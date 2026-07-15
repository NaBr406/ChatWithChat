package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.data.database.entity.effectiveTokenUsage
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import cn.nabr.chatwithchat.presentation.common.AppleBlue
import cn.nabr.chatwithchat.presentation.common.AppleGreen
import cn.nabr.chatwithchat.presentation.common.AppleOrange
import cn.nabr.chatwithchat.presentation.common.AppleRed
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.util.isAssistantErrorMessage
import cn.nabr.chatwithchat.util.stripAssistantErrorNote

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
    roundCount: Int,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    val containerColor = animateColorAsState(
        targetValue = if (isExpanded) AppleBlue.copy(alpha = 0.12f) else Color.Transparent,
        label = "roundCountContainerColor"
    ).value
    val contentColor = animateColorAsState(
        targetValue = if (isExpanded) AppleBlue else materialColors.secondaryLabel,
        label = "roundCountContentColor"
    ).value
    val description = stringResource(R.string.round_navigator_button_description, roundCount)

    Surface(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
            .semantics { this.contentDescription = description },
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.round_count, roundCount),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
fun RoundNavigatorOverlay(
    items: List<RoundNavigationItem>,
    currentTurnIndex: Int?,
    maxHeight: Dp,
    onDismiss: () -> Unit,
    onTurnClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val materialColors = settingsMaterialColors()
    val initialVisibleItemIndex = items.indexOfFirst { item -> item.turnIndex == currentTurnIndex }
        .takeIf { index -> index >= 0 }
        ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialVisibleItemIndex)
    val completedCount = items.completedRoundCount()
    val maximumRoundDigits = items.maxOfOrNull { item -> item.displayNumber.toString().length } ?: 1
    val roundNumberWidth = (16.dp + 9.dp * (maximumRoundDigits * LocalDensity.current.fontScale))
        .coerceAtLeast(32.dp)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { }
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss
                )
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.94f)
                .heightIn(max = maxHeight)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
                },
            shape = RoundedCornerShape(14.dp),
            color = materialColors.grouped,
            contentColor = materialColors.primaryLabel,
            border = BorderStroke(0.5.dp, materialColors.separatorStrong),
            tonalElevation = 0.dp,
            shadowElevation = 14.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, top = 6.dp, end = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            modifier = Modifier.semantics { heading() },
                            text = stringResource(R.string.round_navigator_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = materialColors.primaryLabel
                        )
                        Text(
                            text = stringResource(R.string.round_navigator_summary, items.size, completedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = materialColors.secondaryLabel
                        )
                    }
                    IconButton(
                        modifier = Modifier.size(44.dp),
                        onClick = onDismiss
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = materialColors.secondaryLabel
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = materialColors.separatorStrong
                )

                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.round_navigator_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = materialColors.secondaryLabel
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(weight = 1f, fill = false),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        itemsIndexed(
                            items = items,
                            key = { _, item -> item.turnIndex }
                        ) { index, item ->
                            RoundNavigatorItem(
                                item = item,
                                isCurrent = item.turnIndex == currentTurnIndex,
                                roundNumberWidth = roundNumberWidth,
                                onClick = { onTurnClick(item.turnIndex) }
                            )
                            if (index < items.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 26.dp + roundNumberWidth),
                                    thickness = 0.5.dp,
                                    color = materialColors.separator
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundNavigatorItem(
    item: RoundNavigationItem,
    isCurrent: Boolean,
    roundNumberWidth: Dp,
    onClick: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    val statusColor = item.status.color
    val statusLabel = item.status.localizedLabel()
    val onClickLabel = stringResource(R.string.round_navigator_jump_to, item.displayNumber)
    val questionPreview = item.questionPreview.ifBlank { stringResource(R.string.round_question_empty) }
    val tokenLabel = stringResource(R.string.round_token_count, formatTokenCount(item.totalTokens))
    val estimateLabel = stringResource(R.string.round_token_estimated)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) AppleBlue.copy(alpha = 0.08f) else Color.Transparent)
            .semantics(mergeDescendants = true) { selected = isCurrent }
            .clickable(
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick
            )
            .defaultMinSize(minHeight = 72.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier
                .width(roundNumberWidth)
                .heightIn(min = 32.dp),
            shape = CircleShape,
            color = if (isCurrent) AppleBlue else AppleBlue.copy(alpha = 0.1f),
            contentColor = if (isCurrent) Color.White else AppleBlue,
            tonalElevation = 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = item.displayNumber.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = questionPreview,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = materialColors.primaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = materialColors.secondaryLabel,
                    maxLines = 1
                )
                Text(
                    text = buildString {
                        append(" · ")
                        append(tokenLabel)
                        if (item.isEstimated) {
                            append(" · ")
                            append(estimateLabel)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = materialColors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            item.platformUsages.forEach { usage ->
                PlatformUsageText(usage = usage)
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = if (isCurrent) AppleBlue else materialColors.tertiaryLabel,
            modifier = Modifier
                .padding(top = 7.dp)
                .size(18.dp)
        )
    }
}

@Composable
private fun PlatformUsageText(usage: PlatformTokenUsageLine) {
    val materialColors = settingsMaterialColors()
    val platformName = usage.platformName.ifBlank { stringResource(R.string.round_unknown_platform) }
    val tokenLabel = stringResource(R.string.round_token_count, formatTokenCount(usage.totalTokens))
    val toolTokenLabel = if (usage.toolTokens > 0) {
        stringResource(R.string.round_tool_token_count, formatTokenCount(usage.toolTokens))
    } else {
        null
    }
    val estimateLabel = stringResource(R.string.round_token_estimated).takeIf { usage.isEstimated }

    Text(
        text = buildString {
            append(platformName)
            usage.model.takeIf { it.isNotBlank() }?.let { model ->
                append(" / ")
                append(model)
            }
            listOfNotNull(tokenLabel, toolTokenLabel, estimateLabel).forEach { detail ->
                append(" · ")
                append(detail)
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = materialColors.secondaryLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

internal fun List<RoundNavigationItem>.completedRoundCount(): Int = count { item ->
    item.status == RoundStatus.Completed
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
            .trim(),
        status = status,
        hasSuccessfulAnswer = successCount > 0,
        totalTokens = usages.roundTotalTokens(),
        isEstimated = usages.any { it.isEstimated },
        platformUsages = assistantRow.mapIndexedNotNull { platformIndex, assistantMessage ->
            val usage = assistantMessage.effectiveTokenUsage() ?: return@mapIndexedNotNull null
            val platformUid = enabledPlatformsInChat.getOrNull(platformIndex) ?: assistantMessage.platformType.orEmpty()
            val platform = enabledPlatformLookup[platformUid]
            PlatformTokenUsageLine(
                platformName = platform?.name ?: platformUid,
                model = usage.model.ifBlank { platform?.model.orEmpty() },
                totalTokens = usage.roundTotalTokens(),
                toolTokens = usage.toolTotalTokens,
                isEstimated = usage.isEstimated
            )
        }
    )
}

private val RoundStatus.color: Color
    get() = when (this) {
        RoundStatus.Completed -> AppleGreen
        RoundStatus.Partial -> AppleOrange
        RoundStatus.Failed -> AppleRed
        RoundStatus.Generating -> AppleBlue
    }

@Composable
private fun RoundStatus.localizedLabel(): String = stringResource(
    when (this) {
        RoundStatus.Completed -> R.string.round_status_completed
        RoundStatus.Partial -> R.string.round_status_partial
        RoundStatus.Failed -> R.string.round_status_failed
        RoundStatus.Generating -> R.string.round_status_generating
    }
)

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
