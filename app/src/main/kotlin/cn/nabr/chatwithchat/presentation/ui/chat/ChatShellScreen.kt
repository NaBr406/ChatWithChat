package cn.nabr.chatwithchat.presentation.ui.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.ui.home.ChatHistoryDrawer
import cn.nabr.chatwithchat.presentation.ui.home.DeleteWarningDialog
import cn.nabr.chatwithchat.presentation.ui.home.HomeModelsState
import cn.nabr.chatwithchat.presentation.ui.home.HomeViewModel
import cn.nabr.chatwithchat.presentation.ui.home.modelsOrEmpty
import cn.nabr.chatwithchat.presentation.ui.home.shouldResetSelectionAfterDrawerTransition
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatShellScreen(
    homeViewModel: HomeViewModel = hiltViewModel(),
    settingOnClick: () -> Unit,
    onAboutClick: () -> Unit,
    onExistingChatClick: (ChatRoomV2) -> Unit,
    navigateToNewChat: (enabledPlatforms: List<String>, initialQuestion: String?, initialModel: String?, initialAttachmentPaths: List<String>, initialRequestId: Int) -> Unit,
    content: @Composable (
        openDrawer: () -> Unit,
        homeViewModel: HomeViewModel,
        startNewChat: (initialQuestion: String?, initialAttachmentPaths: List<String>, preferPrimaryPlatform: Boolean) -> Boolean
    ) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val chatListState by homeViewModel.chatListState.collectAsStateWithLifecycle()
    val platformState by homeViewModel.platformState.collectAsStateWithLifecycle()
    val searchQuery by homeViewModel.searchQuery.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val materialColors = settingsMaterialColors()

    fun openDrawer() {
        homeViewModel.resetDrawerSelection()
        scope.launch { drawerState.open() }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun startNewChat(
        initialQuestion: String?,
        initialAttachmentPaths: List<String>,
        preferPrimaryPlatform: Boolean
    ): Boolean {
        val currentModelsState = homeViewModel.homeModelsState.value
        if (currentModelsState is HomeModelsState.Loading) {
            return false
        }
        val availableChatModels = currentModelsState.modelsOrEmpty()
        val lastSelectedModel = homeViewModel.lastSelectedModel.value
        val model = lastSelectedModel?.let { selectedModel ->
            availableChatModels.firstOrNull { availableModel ->
                availableModel.platformUid == selectedModel.platformUid && availableModel.modelId == selectedModel.model
            }
        } ?: availableChatModels.firstOrNull()
        if (model == null) {
            Toast.makeText(context, context.getString(R.string.empty_chat_no_platforms), Toast.LENGTH_SHORT).show()
            return false
        }

        homeViewModel.updateLastSelectedModel(model.platformUid, model.modelId, lastSelectedModel?.reasoningMode)
        closeDrawer()
        val initialRequestId = if (!initialQuestion.isNullOrBlank() || initialAttachmentPaths.isNotEmpty()) {
            newInitialRequestId()
        } else {
            0
        }
        navigateToNewChat(listOf(model.platformUid), initialQuestion, model.modelId, initialAttachmentPaths, initialRequestId)
        return true
    }

    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED && !chatListState.isSelectionMode && !chatListState.isSearchMode) {
            homeViewModel.fetchChats()
            homeViewModel.fetchPlatformStatus()
        }
    }

    LaunchedEffect(drawerState) {
        var wasOpenOrOpening = drawerState.currentValue == DrawerValue.Open || drawerState.targetValue == DrawerValue.Open
        snapshotFlow { drawerState.currentValue to drawerState.targetValue }
            .collect { (currentValue, targetValue) ->
                val isOpenOrOpening = currentValue == DrawerValue.Open || targetValue == DrawerValue.Open
                val isFullyClosed = currentValue == DrawerValue.Closed && targetValue == DrawerValue.Closed
                if (shouldResetSelectionAfterDrawerTransition(wasOpenOrOpening, isFullyClosed)) {
                    homeViewModel.resetDrawerSelection()
                }
                wasOpenOrOpening = if (isFullyClosed) false else wasOpenOrOpening || isOpenOrOpening
            }
    }

    BackHandler(enabled = drawerState.currentValue == DrawerValue.Open) {
        when {
            chatListState.isSelectionMode -> homeViewModel.disableSelectionMode()
            chatListState.isSearchMode -> homeViewModel.disableSearchMode()
            else -> closeDrawer()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = materialColors.canvas,
                drawerContentColor = materialColors.primaryLabel,
                drawerTonalElevation = 0.dp
            ) {
                ChatHistoryDrawer(
                    chatListState = chatListState,
                    platformState = platformState,
                    searchQuery = searchQuery,
                    onSearchQueryChanged = { query ->
                        if (query.isNotBlank() && !chatListState.isSearchMode) {
                            homeViewModel.enableSearchMode()
                        }
                        if (query.isBlank() && chatListState.isSearchMode) {
                            homeViewModel.disableSearchMode()
                        } else {
                            homeViewModel.updateSearchQuery(query)
                        }
                    },
                    onClearSearch = homeViewModel::disableSearchMode,
                    onNewChatClick = { startNewChat(null, emptyList(), preferPrimaryPlatform = false) },
                    onChatClick = { chatRoom ->
                        closeDrawer()
                        onExistingChatClick(chatRoom)
                    },
                    onChatLongClick = { index ->
                        homeViewModel.enableSelectionMode()
                        homeViewModel.selectChat(index)
                    },
                    onChatSelected = homeViewModel::selectChat,
                    onCloseSelection = homeViewModel::disableSelectionMode,
                    onDuplicateSelected = {
                        homeViewModel.duplicateSelectedChat()
                        Toast.makeText(context, context.getString(R.string.duplicated_chat), Toast.LENGTH_SHORT).show()
                    },
                    onDeleteSelected = homeViewModel::openDeleteWarningDialog,
                    onSettingsClick = {
                        closeDrawer()
                        settingOnClick()
                    },
                    onAboutClick = {
                        closeDrawer()
                        onAboutClick()
                    }
                )
            }
        }
    ) {
        content(
            ::openDrawer,
            homeViewModel,
            ::startNewChat
        )
    }

    if (chatListState.showDeleteWarningDialog) {
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

internal fun newInitialRequestId(): Int {
    val hash = UUID.randomUUID().hashCode()
    return if (hash < 0) hash else -hash.coerceAtLeast(1)
}
