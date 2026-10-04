package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.data.database.entity.effectiveStickerRefs
import cn.nabr.chatwithchat.data.database.entity.effectiveTokenUsage
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
        targetValue = if (isExpanded) materialColors.primaryLabel.copy(alpha = 0.12f) else Color.Transparent,
        label = "roundCountContainerColor"
    ).value
    val contentColor = animateColorAsState(
        targetValue = if (isExpanded) materialColors.primaryLabel else materialColors.secondaryLabel,
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
    // 轮次号只保留一个右对齐的窄栏位：宽度按最大位数算，行与行之间的提问才对得齐。
    val roundNumberWidth = (8.dp * maximumRoundDigits * LocalDensity.current.fontScale)
        .coerceAtLeast(14.dp)
    // 整段会话都用同一个模型时，逐行标模型名和顶栏里显示的完全重复；只有中途换过模型才值得占一行。
    val showModel = remember(items) {
        items
            .mapNotNull { item -> item.platformUsages.firstOrNull()?.model?.takeIf(String::isNotBlank) }
            .distinct()
            .size > 1
    }

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
                .chatSelectionGlass(RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
                },
            shape = RoundedCornerShape(14.dp),
            color = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = materialColors.primaryLabel,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
            ) {
                // 标题和统计并排放在一行：它们加起来也就十几个字，堆成两行只是把面板顶高。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        modifier = Modifier.semantics { heading() },
                        text = stringResource(R.string.round_navigator_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = materialColors.primaryLabel,
                        maxLines = 1
                    )
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.round_navigator_summary, items.size, completedCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = materialColors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        modifier = Modifier.size(40.dp),
                        onClick = onDismiss
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = materialColors.secondaryLabel,
                            modifier = Modifier.size(18.dp)
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
                            .heightIn(min = 88.dp),
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
                        // 单行行高配合行间不再画分隔线：一行文字本身就是分隔，横线只是多一层视觉噪音。
                        items(
                            items = items,
                            key = { item -> item.turnIndex }
                        ) { item ->
                            RoundNavigatorItem(
                                item = item,
                                isCurrent = item.turnIndex == currentTurnIndex,
                                roundNumberWidth = roundNumberWidth,
                                showModel = showModel,
                                onClick = { onTurnClick(item.turnIndex) }
                            )
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
    showModel: Boolean,
    onClick: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    val onClickLabel = stringResource(R.string.round_navigator_jump_to, item.displayNumber)
    val questionPreview = item.questionPreview.ifBlank { stringResource(R.string.round_question_empty) }
    // 绝大多数轮次都是“已完成”，每行都写一遍等于没写；只有异常状态才值得占位置。
    val statusLabel = item.status.takeIf { status -> status != RoundStatus.Completed }?.localizedLabel()
    val tokenValue = formatTokenCount(item.totalTokens)
    // 估算标记压成一个 ≈ 前缀，省下“估算”两个字；朗读时仍然给完整文案。
    val tokenText = tokenValue.takeIf { item.totalTokens > 0 }
        ?.let { value -> if (item.isEstimated) "≈$value" else value }
    val tokenDescription = stringResource(R.string.round_token_count, tokenValue) +
        if (item.isEstimated) " ${stringResource(R.string.round_token_estimated)}" else ""
    val modelLabel = item.platformUsages.firstOrNull()
        ?.model
        ?.takeIf { model -> showModel && model.isNotBlank() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) materialColors.primaryLabel.copy(alpha = 0.07f) else Color.Transparent)
            .semantics(mergeDescendants = true) { selected = isCurrent }
            .clickable(
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick
            )
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            modifier = Modifier.widthIn(min = roundNumberWidth),
            text = item.displayNumber.toString(),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium,
            color = if (isCurrent) materialColors.primaryLabel else materialColors.tertiaryLabel,
            maxLines = 1
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = questionPreview,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                color = materialColors.primaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            modelLabel?.let { model ->
                Text(
                    text = model,
                    style = MaterialTheme.typography.labelSmall,
                    color = materialColors.tertiaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        statusLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = item.status.color(),
                maxLines = 1
            )
        }

        tokenText?.let { token ->
            Text(
                modifier = Modifier.semantics { contentDescription = tokenDescription },
                text = token,
                style = MaterialTheme.typography.labelSmall,
                color = materialColors.secondaryLabel,
                maxLines = 1
            )
        }
    }
}

internal fun List<RoundNavigationItem>.completedRoundCount(): Int = count { item ->
    item.status == RoundStatus.Completed
}

fun buildRoundNavigationItems(
    groupedMessages: ChatViewModel.GroupedMessages,
    loadingStates: List<ChatViewModel.LoadingState>,
    enabledPlatformLookup: Map<String, PlatformV2>,
    selectedPlatformIndices: List<Int> = emptyList()
): List<RoundNavigationItem> = groupedMessages.userMessages.mapIndexed { index, userMessage ->
    val assistantRow = groupedMessages.assistantMessages.getOrNull(index).orEmpty()
    val selectedPlatformIndex = selectedPlatformIndices.getOrNull(index)
        ?.coerceIn(0, assistantRow.lastIndex.coerceAtLeast(0))
        ?: 0
    val selectedAssistantMessage = assistantRow.getOrNull(selectedPlatformIndex)
    val selectedUsage = selectedAssistantMessage?.effectiveTokenUsage()
    val selectedPlatformUid = selectedAssistantMessage?.platformType.orEmpty()
    val selectedPlatform = enabledPlatformLookup[selectedPlatformUid]
    val successCount = selectedAssistantMessage?.let { message ->
        if (message.hasSuccessfulAssistantAnswer()) 1 else 0
    } ?: 0
    val failedCount = selectedAssistantMessage?.let { message ->
        if (message.hasAssistantFailure()) 1 else 0
    } ?: 0
    val expectedCount = 1
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
        totalTokens = selectedUsage?.roundTotalTokens() ?: 0,
        isEstimated = selectedUsage?.isEstimated ?: false,
        platformUsages = listOfNotNull(
            selectedUsage?.let { usage ->
                val platformUid = selectedPlatformUid
                val platform = selectedPlatform
                PlatformTokenUsageLine(
                    platformName = platform?.name ?: platformUid,
                    model = usage.model,
                    totalTokens = usage.roundTotalTokens(),
                    toolTokens = usage.toolTotalTokens,
                    isEstimated = usage.isEstimated
                )
            }
        )
    )
}

@Composable
private fun RoundStatus.color(): Color = when (this) {
    RoundStatus.Completed -> AppleGreen
    RoundStatus.Partial -> AppleOrange
    RoundStatus.Failed -> AppleRed
    RoundStatus.Generating -> settingsMaterialColors().primaryLabel
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
    return stripAssistantErrorNote(content).trim().isNotBlank() || effectiveStickerRefs().isNotEmpty()
}

private fun MessageV2.hasAssistantFailure(): Boolean {
    val content = effectiveContent()
    return isAssistantErrorMessage(content) || stripAssistantErrorNote(content) != content
}
