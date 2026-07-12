package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.presentation.common.AppleBlue
import dev.chungjungsoo.gptmobile.presentation.common.settingsMaterialColors
import dev.chungjungsoo.gptmobile.util.getPlatformName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatHistoryDrawer(
    chatListState: HomeViewModel.ChatListState,
    platformState: List<PlatformV2>,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onNewChatClick: () -> Unit,
    onChatClick: (ChatRoomV2) -> Unit,
    onChatLongClick: (Int) -> Unit,
    onChatSelected: (Int) -> Unit,
    onCloseSelection: () -> Unit,
    onDuplicateSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit
) {
    val groupedChats = remember(chatListState.chats) { groupChatsByUpdatedAt(chatListState.chats) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 328.dp)
            .padding(horizontal = 14.dp, vertical = 18.dp)
    ) {
        DrawerHeader(
            onNewChatClick = onNewChatClick
        )
        ChatDrawerSearchField(
            query = searchQuery,
            onQueryChange = onSearchQueryChanged,
            onClearSearch = onClearSearch
        )

        if (chatListState.isSelectionMode) {
            DrawerSelectionToolbar(
                selectedCount = chatListState.selectedChats.count { it },
                onCloseSelection = onCloseSelection,
                onDuplicateSelected = onDuplicateSelected,
                onDeleteSelected = onDeleteSelected
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (chatListState.chats.isEmpty() && searchQuery.isNotBlank()) {
                item {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 32.dp),
                        text = stringResource(R.string.no_search_results),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            groupedChats.forEach { group ->
                item(key = "group-${group.bucket}") {
                    ChatHistorySectionHeader(title = group.bucket.label())
                }

                items(
                    items = group.chats,
                    key = { it.id }
                ) { chatRoom ->
                    val chatIndex = chatListState.chats.indexOfFirst { it.id == chatRoom.id }
                    val usingPlatform = chatRoom.enabledPlatform.joinToString(", ") { uid ->
                        platformState.getPlatformName(uid)
                    }

                    ChatHistoryRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (chatListState.isSelectionMode && chatIndex >= 0) {
                                        onChatSelected(chatIndex)
                                    } else {
                                        onChatClick(chatRoom)
                                    }
                                },
                                onLongClick = {
                                    if (chatIndex >= 0) {
                                        onChatLongClick(chatIndex)
                                    }
                                }
                            ),
                        title = chatRoom.title,
                        platformLabel = stringResource(R.string.using_certain_platform, usingPlatform),
                        isSelectionMode = chatListState.isSelectionMode,
                        selected = chatListState.selectedChats.getOrElse(chatIndex) { false },
                        onCheckedChange = {
                            if (chatIndex >= 0) {
                                onChatSelected(chatIndex)
                            }
                        }
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
        DrawerActionRow(
            icon = Icons.Outlined.Settings,
            label = stringResource(R.string.settings),
            onClick = onSettingsClick
        )
        DrawerActionRow(
            icon = ImageVector.vectorResource(R.drawable.ic_info),
            label = stringResource(R.string.about),
            onClick = onAboutClick
        )
    }
}

@Composable
fun ChatHistorySectionHeader(title: String) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
    )
}

@Composable
fun ChatHistoryRow(
    modifier: Modifier = Modifier,
    title: String,
    platformLabel: String,
    isSelectionMode: Boolean,
    selected: Boolean,
    onCheckedChange: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onCheckedChange() }
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = platformLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun DrawerHeader(
    onNewChatClick: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.chatwithchat_brand),
            style = MaterialTheme.typography.titleLarge,
            color = materialColors.primaryLabel
        )
        IconButton(
            modifier = Modifier.size(40.dp),
            onClick = onNewChatClick,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = AppleBlue.copy(alpha = 0.12f),
                contentColor = AppleBlue
            )
        ) {
            Icon(
                modifier = Modifier.size(22.dp),
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.new_chat)
            )
        }
    }
}

@Composable
private fun ChatDrawerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    BasicTextField(
        modifier = Modifier
            .padding(top = 14.dp, bottom = 10.dp)
            .fillMaxWidth(),
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = materialColors.primaryLabel),
        cursorBrush = SolidColor(AppleBlue),
        decorationBox = { innerTextField ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = materialColors.grouped,
                border = BorderStroke(0.5.dp, materialColors.separator)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        modifier = Modifier.size(20.dp),
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = materialColors.secondaryLabel
                    )
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_chats),
                                style = MaterialTheme.typography.bodyLarge,
                                color = materialColors.secondaryLabel
                            )
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        IconButton(
                            modifier = Modifier.size(36.dp),
                            onClick = onClearSearch
                        ) {
                            Icon(
                                modifier = Modifier.size(18.dp),
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.clear),
                                tint = materialColors.secondaryLabel
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun DrawerSelectionToolbar(
    selectedCount: Int,
    onCloseSelection: () -> Unit,
    onDuplicateSelected: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCloseSelection) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.close)
            )
        }
        Text(
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.chats_selected, selectedCount),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium
        )
        if (selectedCount == 1) {
            IconButton(onClick = onDuplicateSelected) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.duplicate)
                )
            }
        }
        IconButton(onClick = onDeleteSelected) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.delete)
            )
        }
    }
}

@Composable
private fun DrawerActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

private data class ChatHistoryGroup(
    val bucket: ChatHistoryBucket,
    val chats: List<ChatRoomV2>
)

private enum class ChatHistoryBucket {
    TODAY,
    YESTERDAY,
    PREVIOUS_7_DAYS,
    OLDER
}

@Composable
private fun ChatHistoryBucket.label(): String = when (this) {
    ChatHistoryBucket.TODAY -> stringResource(R.string.history_today)
    ChatHistoryBucket.YESTERDAY -> stringResource(R.string.history_yesterday)
    ChatHistoryBucket.PREVIOUS_7_DAYS -> stringResource(R.string.history_previous_7_days)
    ChatHistoryBucket.OLDER -> stringResource(R.string.history_older)
}

private fun groupChatsByUpdatedAt(
    chats: List<ChatRoomV2>,
    today: LocalDate = LocalDate.now()
): List<ChatHistoryGroup> {
    if (chats.isEmpty()) return emptyList()

    val zone = ZoneId.systemDefault()
    return chats
        .groupBy { chat ->
            val chatDate = Instant
                .ofEpochSecond(chat.updatedAt)
                .atZone(zone)
                .toLocalDate()
            when (ChronoUnit.DAYS.between(chatDate, today)) {
                in Long.MIN_VALUE..0L -> ChatHistoryBucket.TODAY
                1L -> ChatHistoryBucket.YESTERDAY
                in 2L..6L -> ChatHistoryBucket.PREVIOUS_7_DAYS
                else -> ChatHistoryBucket.OLDER
            }
        }
        .map { (bucket, bucketChats) -> ChatHistoryGroup(bucket, bucketChats) }
        .sortedBy { it.bucket.ordinal }
}
