package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.AppSourceNavigationTarget
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.database.entity.SafeMessageSourceTarget
import cn.nabr.chatwithchat.data.database.entity.safeDedupeKey
import cn.nabr.chatwithchat.data.database.entity.safeNavigationTarget
import cn.nabr.chatwithchat.presentation.common.AppleBlue
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
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
    onShowNextRevision: () -> Unit = {}
) {
    val isThinking = isLoading && thoughts.isNotBlank() && text.isBlank()
    var hasObservedStreaming by remember(contentIdentity) { mutableStateOf(isLoading) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            hasObservedStreaming = true
        }
    }
    val visibleText = rememberSmoothedStreamingText(
        targetText = text,
        isStreaming = isLoading,
        contentIdentity = contentIdentity
    )
    val isTextAnimating = isLoading || visibleText != text
    Column(modifier = modifier) {
        if (thoughts.isNotBlank()) {
            ThinkingBlock(
                modifier = Modifier.padding(top = 6.dp),
                thoughts = thoughts,
                contentIdentity = contentIdentity,
                isLoading = isLoading,
                isThinking = isThinking,
                initiallyExpanded = isLoading || hasObservedStreaming
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
                        content = appendStreamingTextTail(displayText, isTextAnimating),
                        contentIdentity = contentIdentity,
                        renderMath = true,
                        useMathJax = !isTextAnimating,
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

            if (!isTextAnimating) {
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
    val materialColors = settingsMaterialColors()
    val visibleSources = sources
        .mapNotNull { source -> source.safeDedupeKey()?.let { key -> key to source } }
        .distinctBy { (key, _) -> key }
        .map { (_, source) -> source }
    if (visibleSources.isEmpty()) return

    var isExpanded by remember(visibleSources) { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "source_rotation"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = materialColors.grouped,
        contentColor = materialColors.primaryLabel,
        border = BorderStroke(0.5.dp, materialColors.separator),
        tonalElevation = 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(AppleBlue.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Language,
                        contentDescription = null,
                        tint = AppleBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = "${stringResource(R.string.sources_title)} (${visibleSources.size})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = materialColors.primaryLabel,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (isExpanded) {
                        stringResource(R.string.collapse)
                    } else {
                        stringResource(R.string.expand)
                    },
                    tint = materialColors.secondaryLabel,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationAngle)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = materialColors.separatorStrong
                    )
                    visibleSources.forEachIndexed { index, source ->
                        SourceMetadataItem(
                            source = source,
                            sourceNumber = index + 1
                        )
                        if (index < visibleSources.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
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

@Composable
private fun SourceMetadataItem(
    source: MessageSourceMetadata,
    sourceNumber: Int
) {
    val materialColors = settingsMaterialColors()
    val uriHandler = LocalUriHandler.current
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
    val openSource: (() -> Unit)? = (target as? SafeMessageSourceTarget.PublicUrl)?.let { publicTarget ->
        { runCatching { uriHandler.openUri(publicTarget.url) } }
    }
    val interactionModifier = openSource?.let { onClick ->
        Modifier.clickable(onClick = onClick)
    } ?: Modifier

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(interactionModifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(materialColors.controlFill.copy(alpha = 0.46f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = sourceNumber.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = materialColors.secondaryLabel
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = source.title.ifBlank { detail },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = materialColors.primaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (openSource != null) AppleBlue else materialColors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            source.snippet.takeIf { it.isNotBlank() }?.let { snippet ->
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = materialColors.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (openSource != null) {
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
    ChatWithChatTheme {
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
    ChatWithChatTheme {
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
