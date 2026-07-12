package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.AppSourceNavigationTarget
import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceMetadata
import dev.chungjungsoo.gptmobile.data.database.entity.SafeMessageSourceTarget
import dev.chungjungsoo.gptmobile.data.database.entity.safeDedupeKey
import dev.chungjungsoo.gptmobile.data.database.entity.safeNavigationTarget
import dev.chungjungsoo.gptmobile.presentation.common.AppleBlue
import dev.chungjungsoo.gptmobile.presentation.common.settingsMaterialColors
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import java.io.File

private val OutgoingMessageBlue = Color(0xFF0062CC)

@Composable
fun UserChatBubble(
    modifier: Modifier = Modifier,
    text: String,
    files: List<String> = emptyList(),
    onLongPress: () -> Unit
) {
    Column(horizontalAlignment = Alignment.End) {
        Surface(
            modifier = modifier
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { onLongPress.invoke() })
                },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
            color = OutgoingMessageBlue,
            contentColor = Color.White
        ) {
            ChatMarkdown(
                content = text,
                contentColor = Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
        MessageFileThumbnailRow(
            files = files,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
fun OpponentChatBubble(
    modifier: Modifier = Modifier,
    canRetry: Boolean,
    isLoading: Boolean,
    showPendingIndicator: Boolean = true,
    isError: Boolean = false,
    text: String,
    thoughts: String = "",
    attachments: List<String> = emptyList(),
    sourceMetadata: List<MessageSourceMetadata> = emptyList(),
    contentIdentity: Any = text,
    canEdit: Boolean = false,
    revisionIndexLabel: String? = null,
    canShowPreviousRevision: Boolean = false,
    canShowNextRevision: Boolean = false,
    onCopyClick: () -> Unit = {},
    onSelectClick: () -> Unit = {},
    onRetryClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onShowPreviousRevision: () -> Unit = {},
    onShowNextRevision: () -> Unit = {},
    onStreamingTextDisplayed: () -> Unit = {}
) {
    val isThinking = isLoading && thoughts.isNotBlank() && text.isBlank()
    val visibleText = rememberSmoothedStreamingText(
        targetText = text,
        isStreaming = isLoading,
        contentIdentity = contentIdentity
    )
    LaunchedEffect(isLoading, visibleText) {
        if (isLoading && visibleText.isNotBlank()) {
            onStreamingTextDisplayed()
        }
    }

    Column(modifier = modifier) {
        if (thoughts.isNotBlank()) {
            ThinkingBlock(
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                thoughts = thoughts,
                contentIdentity = contentIdentity,
                isLoading = isThinking,
                onStreamingTextDisplayed = onStreamingTextDisplayed
            )
        }

        Column {
            val displayText = visibleText

            when {
                isLoading && displayText.isBlank() && thoughts.isBlank() && showPendingIndicator -> {
                    ResponseLoadingIndicator()
                }
                displayText.isNotBlank() || !isLoading -> {
                    ChatMarkdown(
                        content = appendStreamingTextTail(displayText, isLoading),
                        contentIdentity = contentIdentity,
                        renderMath = true,
                        useMathJax = !isLoading,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }

            MessageFileThumbnailRow(
                files = attachments,
                usePrimaryColors = false,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
            )

            SourceMetadataBlock(
                sources = sourceMetadata,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            if (!isLoading) {
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .alpha(0.72f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (!isError) {
                        CopyTextIcon(onCopyClick)
                        SelectTextIcon(onSelectClick)
                        if (canEdit) {
                            EditTextIcon(onEditClick)
                        }
                    }
                    if (canRetry) {
                        RetryIcon(onRetryClick)
                    }
                }

                revisionIndexLabel?.let { label ->
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            modifier = Modifier.size(36.dp),
                            enabled = canShowPreviousRevision,
                            onClick = onShowPreviousRevision
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.previous_revision),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            modifier = Modifier.size(36.dp),
                            enabled = canShowNextRevision,
                            onClick = onShowNextRevision
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.next_revision),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResponseLoadingIndicator() {
    val materialColors = settingsMaterialColors()
    Row(
        modifier = Modifier.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(AppleBlue.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = AppleBlue
            )
        }
        Text(
            text = stringResource(R.string.response_preparing),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = materialColors.secondaryLabel
        )
    }
}

@Composable
private fun SourceMetadataBlock(
    sources: List<MessageSourceMetadata>,
    modifier: Modifier = Modifier
) {
    val visibleSources = sources
        .mapNotNull { source -> source.safeDedupeKey()?.let { key -> key to source } }
        .distinctBy { (key, _) -> key }
        .map { (_, source) -> source }
        .take(MAX_VISIBLE_SOURCES)
    if (visibleSources.isEmpty()) return

    var isExpanded by remember(visibleSources) { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "source_rotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${stringResource(R.string.sources_title)} (${visibleSources.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (isExpanded) {
                    stringResource(R.string.collapse)
                } else {
                    stringResource(R.string.expand)
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                modifier = Modifier.rotate(rotationAngle)
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visibleSources.forEach { source ->
                    SourceMetadataItem(source = source)
                }
            }
        }
    }
}

@Composable
private fun SourceMetadataItem(source: MessageSourceMetadata) {
    val target = source.safeNavigationTarget() ?: return
    val detail = when (target) {
        is SafeMessageSourceTarget.PublicUrl -> target.url
        is SafeMessageSourceTarget.LocalApp -> {
            val targetLabel = when (target.navigationTarget) {
                AppSourceNavigationTarget.CHAT_ROOM -> stringResource(R.string.source_local_chat)
                AppSourceNavigationTarget.MEMORY -> stringResource(R.string.source_local_memory)
            }
            "$targetLabel · ${target.entityId}"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = source.title.ifBlank { detail },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        source.snippet.takeIf { it.isNotBlank() }?.let { snippet ->
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private const val MAX_VISIBLE_SOURCES = 5

@Composable
fun PlatformButton(
    isLoading: Boolean,
    name: String,
    selected: Boolean,
    onPlatformClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .widthIn(max = 152.dp)
            .heightIn(min = 32.dp)
            .clickable(onClick = onPlatformClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun CopyTextIcon(onCopyClick: () -> Unit) {
    IconButton(modifier = Modifier.size(36.dp), onClick = onCopyClick) {
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_copy),
            contentDescription = stringResource(R.string.copy_text),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SelectTextIcon(onSelectClick: () -> Unit) {
    IconButton(modifier = Modifier.size(36.dp), onClick = onSelectClick) {
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_select),
            contentDescription = stringResource(R.string.select_text),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun RetryIcon(onRetryClick: () -> Unit) {
    IconButton(modifier = Modifier.size(36.dp), onClick = onRetryClick) {
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = stringResource(R.string.retry),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun EditTextIcon(onEditClick: () -> Unit) {
    IconButton(modifier = Modifier.size(36.dp), onClick = onEditClick) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = stringResource(R.string.edit),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Preview
@Composable
fun UserChatBubblePreview() {
    val sampleText = """
        How can I print hello world
        in Python?
    """.trimIndent()
    GPTMobileTheme {
        UserChatBubble(text = sampleText, files = emptyList(), onLongPress = {})
    }
}

@Preview
@Composable
fun AssistantChatBubblePreview() {
    val sampleText = """
        # Demo
    
        Emphasis, aka italics, with *asterisks* or _underscores_. Strong emphasis, aka bold, with **asterisks** or __underscores__. Combined emphasis with **asterisks and _underscores_**. [Links with two blocks, text in square-brackets, destination is in parentheses.](https://www.example.com). Inline `code` has `back-ticks around` it.
    
        1. First ordered list item
        2. Another item
            * Unordered sub-list.
        3. And another item.
            You can have properly indented paragraphs within list items. Notice the blank line above, and the leading spaces (at least one, but we'll use three here to also align the raw Markdown).
    
        * Unordered list can use asterisks
        - Or minuses
        + Or pluses
    """.trimIndent()
    GPTMobileTheme {
        OpponentChatBubble(
            text = sampleText,
            canRetry = true,
            isLoading = false,
            revisionIndexLabel = "Revision 1/1",
            onCopyClick = {},
            onRetryClick = {}
        )
    }
}

@Composable
internal fun MessageFileThumbnailRow(
    files: List<String>,
    modifier: Modifier = Modifier,
    usePrimaryColors: Boolean = true
) {
    // Filter out empty strings and check if we have valid files
    val validFiles = files.filter { it.isNotEmpty() && it.isNotBlank() }

    if (validFiles.isEmpty()) {
        return
    }

    Row(
        modifier = modifier
            .wrapContentHeight()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        validFiles.forEach { filePath ->
            MessageFileThumbnail(
                filePath = filePath,
                usePrimaryColors = usePrimaryColors
            )
        }
    }
}

@Composable
private fun MessageFileThumbnail(
    filePath: String,
    usePrimaryColors: Boolean
) {
    val file = File(filePath)
    val isImage = isImageFile(file.extension)
    val containerColor = if (usePrimaryColors) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (usePrimaryColors) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor)
        ) {
            if (isImage) {
                LocalImageThumbnail(
                    filePath = file.absolutePath,
                    size = 48.dp,
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    fallback = {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_image),
                            contentDescription = file.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            tint = contentColor
                        )
                    }
                )
            } else {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_file),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    tint = contentColor
                )
            }
        }

        Text(
            text = file.name,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp)
                .width(56.dp)
        )
    }
}
