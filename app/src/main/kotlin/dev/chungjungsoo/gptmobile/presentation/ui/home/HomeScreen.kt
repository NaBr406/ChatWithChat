package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.presentation.common.AppleBlue
import dev.chungjungsoo.gptmobile.presentation.common.AppleRed
import dev.chungjungsoo.gptmobile.presentation.common.HigActionDialog
import dev.chungjungsoo.gptmobile.presentation.common.settingsMaterialColors
import dev.chungjungsoo.gptmobile.util.getPlatformName

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel = hiltViewModel(),
    settingOnClick: () -> Unit,
    onExistingChatClick: (ChatRoomV2) -> Unit,
    navigateToNewChat: (enabledPlatforms: List<String>) -> Unit,
    onOpenDrawer: (() -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val chatListState by homeViewModel.chatListState.collectAsStateWithLifecycle()
    val showDeleteWarningDialog by homeViewModel.showDeleteWarningDialog.collectAsStateWithLifecycle()
    val platformState by homeViewModel.platformState.collectAsStateWithLifecycle()
    val lastSelectedModel by homeViewModel.lastSelectedModel.collectAsStateWithLifecycle()
    val availableChatModels by homeViewModel.availableChatModels.collectAsStateWithLifecycle()
    val searchQuery by homeViewModel.searchQuery.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val startNewChat = {
        val model = lastSelectedModel?.let { selectedModel ->
            availableChatModels.firstOrNull { model ->
                model.platformUid == selectedModel.platformUid && model.modelId == selectedModel.model
            }
        } ?: availableChatModels.firstOrNull()
        if (model == null) {
            Toast.makeText(context, context.getString(R.string.empty_chat_no_platforms), Toast.LENGTH_SHORT).show()
        } else {
            homeViewModel.updateLastSelectedModel(model.platformUid, model.modelId, lastSelectedModel?.reasoningMode)
            navigateToNewChat(listOf(model.platformUid))
        }
    }

    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED && !chatListState.isSelectionMode && !chatListState.isSearchMode) {
            homeViewModel.fetchChats()
            homeViewModel.fetchPlatformStatus()
        }
    }

    BackHandler(enabled = chatListState.isSelectionMode || chatListState.isSearchMode) {
        when {
            chatListState.isSelectionMode -> homeViewModel.disableSelectionMode()
            chatListState.isSearchMode -> homeViewModel.disableSearchMode()
        }
    }

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = settingsMaterialColors().canvas,
        topBar = {
            HomeTopAppBar(
                isSelectionMode = chatListState.isSelectionMode,
                isSearchMode = chatListState.isSearchMode,
                selectedChats = chatListState.selectedChats.count { it },
                scrollBehavior = scrollBehavior,
                actionOnClick = {
                    if (chatListState.isSelectionMode) {
                        homeViewModel.openDeleteWarningDialog()
                    } else {
                        settingOnClick()
                    }
                },
                duplicateOnClick = {
                    homeViewModel.duplicateSelectedChat()
                    Toast.makeText(context, context.getString(R.string.duplicated_chat), Toast.LENGTH_SHORT).show()
                },
                drawerOnClick = onOpenDrawer,
                navigationOnClick = {
                    if (chatListState.isSelectionMode) {
                        homeViewModel.disableSelectionMode()
                        return@HomeTopAppBar
                    }

                    if (chatListState.isSearchMode) {
                        homeViewModel.disableSearchMode()
                    } else {
                        homeViewModel.enableSearchMode()
                    }
                },
                onSearchQueryChanged = homeViewModel::updateSearchQuery,
                searchQuery = searchQuery
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!chatListState.isSearchMode) {
                item {
                    HomeHero(
                        chatCount = chatListState.chats.size,
                        enabledPlatformCount = platformState.count { it.enabled },
                        onNewChatClick = startNewChat
                    )
                }

                if (chatListState.chats.isNotEmpty()) {
                    item { ChatHistorySectionHeader(title = stringResource(R.string.recent_chats)) }
                }
            }
            if (chatListState.isSearchMode && chatListState.chats.isEmpty() && searchQuery.isNotEmpty()) {
                item {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        text = stringResource(R.string.no_search_results),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            itemsIndexed(chatListState.chats, key = { _, it -> it.id }) { idx, chatRoom ->
                val usingPlatform = chatRoom.enabledPlatform.joinToString(", ") { uid -> platformState.getPlatformName(uid) }
                val selected = chatListState.selectedChats.getOrElse(idx) { false }
                ChatHistoryRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (chatListState.isSelectionMode) {
                                Modifier.toggleable(
                                    value = selected,
                                    role = Role.Checkbox,
                                    onValueChange = { homeViewModel.selectChat(idx) }
                                )
                            } else {
                                Modifier.combinedClickable(
                                    onLongClick = {
                                        if (!chatListState.isSearchMode) {
                                            homeViewModel.enableSelectionMode()
                                            homeViewModel.selectChat(idx)
                                        }
                                    },
                                    onClick = { onExistingChatClick(chatRoom) }
                                )
                            }
                        )
                        .animateItem(),
                    title = chatRoom.title,
                    platformLabel = stringResource(R.string.using_certain_platform, usingPlatform),
                    isSelectionMode = chatListState.isSelectionMode,
                    selected = selected
                )
            }
        }

        if (showDeleteWarningDialog) {
            DeleteWarningDialog(
                selectedCount = chatListState.selectedChats.count { it },
                onDismissRequest = homeViewModel::closeDeleteWarningDialog,
                onConfirm = {
                    val deletedChatRoomCount = chatListState.selectedChats.count { it }
                    homeViewModel.deleteSelectedChats()
                    Toast.makeText(context, context.getString(R.string.deleted_chats, deletedChatRoomCount), Toast.LENGTH_SHORT).show()
                    homeViewModel.closeDeleteWarningDialog()
                }
            )
        }
    }
}

@Composable
private fun HomeHero(
    chatCount: Int,
    enabledPlatformCount: Int,
    onNewChatClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Image(
                painter = painterResource(R.drawable.chatwithchat_logo),
                contentDescription = null,
                modifier = Modifier
                    .padding(14.dp)
                    .size(44.dp)
            )
        }

        Text(
            modifier = Modifier.padding(top = 18.dp),
            text = stringResource(R.string.chatwithchat_brand),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            modifier = Modifier.padding(top = 6.dp),
            text = stringResource(R.string.home_chat_prompt),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            modifier = Modifier.padding(top = 8.dp),
            text = stringResource(R.string.home_status_summary, enabledPlatformCount, chatCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Button(
            modifier = Modifier
                .padding(top = 22.dp)
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            onClick = onNewChatClick
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_chat))
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = stringResource(R.string.new_chat))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopAppBar(
    isSelectionMode: Boolean,
    isSearchMode: Boolean,
    selectedChats: Int,
    scrollBehavior: TopAppBarScrollBehavior,
    actionOnClick: () -> Unit,
    duplicateOnClick: () -> Unit,
    drawerOnClick: (() -> Unit)? = null,
    navigationOnClick: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    searchQuery: String
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            scrolledContainerColor = if (isSelectionMode) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Unspecified,
            containerColor = if (isSelectionMode) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                settingsMaterialColors().navigation
            },
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        title = {
            when {
                isSearchMode -> {
                    TextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChanged,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.search_chats)) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChanged("") }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.clear)
                                    )
                                }
                            }
                        }
                    )
                }

                isSelectionMode -> {
                    Text(
                        modifier = Modifier.padding(4.dp),
                        text = stringResource(R.string.chats_selected, selectedChats),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                else -> {
                    Text(
                        modifier = Modifier.padding(4.dp),
                        text = stringResource(R.string.chats),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = scrollBehavior.state.overlappedFraction),
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            when {
                isSelectionMode -> {
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = navigationOnClick
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }

                isSearchMode -> {
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = navigationOnClick
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }

                else -> {
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = drawerOnClick ?: navigationOnClick
                    ) {
                        if (drawerOnClick == null) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = stringResource(R.string.search_chats)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Menu,
                                contentDescription = stringResource(R.string.open_chat_history)
                            )
                        }
                    }
                }
            }
        },
        actions = {
            when {
                isSelectionMode -> {
                    if (selectedChats == 1) {
                        IconButton(
                            modifier = Modifier.padding(4.dp),
                            enabled = true,
                            onClick = duplicateOnClick
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                contentDescription = stringResource(R.string.duplicate)
                            )
                        }
                    }
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = actionOnClick
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = stringResource(R.string.delete)
                        )
                    }
                }

                !isSearchMode -> {
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = actionOnClick
                    ) {
                        Icon(imageVector = Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsTitle(scrollBehavior: TopAppBarScrollBehavior) {
    Text(
        modifier = Modifier
            .padding(top = 32.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        text = stringResource(R.string.chats),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 1.0F - scrollBehavior.state.overlappedFraction),
        style = MaterialTheme.typography.headlineLarge
    )
}

@Composable
private fun LazyListState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var previousScrollOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousScrollOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousScrollOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}

@Preview
@Composable
fun NewChatButton(
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onClick: () -> Unit = { }
) {
    val orientation = LocalConfiguration.current.orientation
    val fabModifier = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
        modifier.systemBarsPadding()
    } else {
        modifier
    }
    ExtendedFloatingActionButton(
        modifier = fabModifier,
        onClick = { onClick() },
        expanded = expanded,
        icon = { Icon(Icons.Filled.Add, stringResource(R.string.new_chat)) },
        text = { Text(text = stringResource(R.string.new_chat)) }
    )
}

@Composable
fun DeleteWarningDialog(
    selectedCount: Int,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    HigActionDialog(
        title = stringResource(R.string.delete_selected_chats_count, selectedCount),
        message = stringResource(R.string.this_operation_can_t_be_undone),
        primaryActionLabel = stringResource(R.string.delete),
        onPrimaryAction = onConfirm,
        onDismissRequest = onDismissRequest,
        secondaryActionLabel = stringResource(R.string.cancel),
        onSecondaryAction = onDismissRequest,
        primaryActionColor = AppleRed,
        secondaryActionColor = AppleBlue
    )
}
