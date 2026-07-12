package dev.chungjungsoo.gptmobile.presentation.ui.chat

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.presentation.common.settingsMaterialColors
import dev.chungjungsoo.gptmobile.presentation.ui.home.ChatHistoryDrawer
import dev.chungjungsoo.gptmobile.presentation.ui.home.DeleteWarningDialog
import dev.chungjungsoo.gptmobile.presentation.ui.home.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatShellScreen(
    homeViewModel: HomeViewModel = hiltViewModel(),
    settingOnClick: () -> Unit,
    onAboutClick: () -> Unit,
    onExistingChatClick: (ChatRoomV2) -> Unit,
    navigateToNewChat: (enabledPlatforms: List<String>, initialQuestion: String?, initialModel: String?, initialAttachmentPaths: List<String>) -> Unit,
    content: @Composable (
        openDrawer: () -> Unit,
        homeViewModel: HomeViewModel,
        startNewChat: (initialQuestion: String?, initialAttachmentPaths: List<String>, preferPrimaryPlatform: Boolean) -> Unit,
        openModelPicker: () -> Unit
    ) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val chatListState by homeViewModel.chatListState.collectAsStateWithLifecycle()
    val showDeleteWarningDialog by homeViewModel.showDeleteWarningDialog.collectAsStateWithLifecycle()
    val platformState by homeViewModel.platformState.collectAsStateWithLifecycle()
    val lastSelectedModel by homeViewModel.lastSelectedModel.collectAsStateWithLifecycle()
    val availableChatModels by homeViewModel.availableChatModels.collectAsStateWithLifecycle()
    val searchQuery by homeViewModel.searchQuery.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val materialColors = settingsMaterialColors()

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun preferredModel() = lastSelectedModel?.let { selectedModel ->
        availableChatModels.firstOrNull { model ->
            model.platformUid == selectedModel.platformUid && model.modelId == selectedModel.model
        }
    } ?: availableChatModels.firstOrNull()

    fun startNewChat(
        initialQuestion: String?,
        initialAttachmentPaths: List<String>,
        preferPrimaryPlatform: Boolean
    ) {
        val model = preferredModel()
        if (model == null) {
            Toast.makeText(context, context.getString(R.string.empty_chat_no_platforms), Toast.LENGTH_SHORT).show()
            return
        }

        homeViewModel.updateLastSelectedModel(model.platformUid, model.modelId, lastSelectedModel?.reasoningMode)
        closeDrawer()
        navigateToNewChat(listOf(model.platformUid), initialQuestion, model.modelId, initialAttachmentPaths)
    }

    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED && !chatListState.isSelectionMode && !chatListState.isSearchMode) {
            homeViewModel.fetchChats()
            homeViewModel.fetchPlatformStatus()
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
                    onSearchClick = {
                        if (chatListState.isSearchMode) {
                            homeViewModel.disableSearchMode()
                        } else {
                            homeViewModel.enableSearchMode()
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
            ::startNewChat,
            {}
        )
    }

    if (showDeleteWarningDialog) {
        DeleteWarningDialog(
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
