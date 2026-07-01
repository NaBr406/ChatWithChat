package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.presentation.ui.home.ChatHistoryDrawer
import dev.chungjungsoo.gptmobile.presentation.ui.home.DeleteWarningDialog
import dev.chungjungsoo.gptmobile.presentation.ui.home.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatShellScreen(
    homeViewModel: HomeViewModel = hiltViewModel(),
    settingOnClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onAboutClick: () -> Unit,
    onExistingChatClick: (ChatRoomV2) -> Unit,
    navigateToNewChat: (enabledPlatforms: List<String>, initialQuestion: String?, initialModel: String?) -> Unit,
    content: @Composable (
        openDrawer: () -> Unit,
        homeViewModel: HomeViewModel,
        startNewChat: (initialQuestion: String?, preferPrimaryPlatform: Boolean) -> Unit,
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
    val searchQuery by homeViewModel.searchQuery.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    var pendingInitialQuestion by remember { mutableStateOf<String?>(null) }
    var showModelPickerSheet by remember { mutableStateOf(false) }
    var startAfterModelSelection by remember { mutableStateOf(false) }

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun enabledPlatforms() = platformState.filter { it.enabled }

    fun preferredSinglePlatformUid(): String? {
        val enabledPlatforms = enabledPlatforms()
        return lastSelectedModel
            ?.platformUid
            ?.takeIf { selectedUid -> enabledPlatforms.any { it.uid == selectedUid } }
            ?: enabledPlatforms.firstOrNull()?.uid
    }

    fun openNewChatModelPicker(initialQuestion: String?, startAfterSelection: Boolean) {
        pendingInitialQuestion = initialQuestion
        startAfterModelSelection = startAfterSelection
        showModelPickerSheet = true
    }

    fun startNewChat(initialQuestion: String?, preferPrimaryPlatform: Boolean) {
        val enabledPlatformUids = enabledPlatforms().map { it.uid }
        when {
            enabledPlatformUids.isEmpty() -> openNewChatModelPicker(initialQuestion, startAfterSelection = true)
            enabledPlatformUids.size == 1 || preferPrimaryPlatform -> {
                val platformUid = preferredSinglePlatformUid() ?: return
                closeDrawer()
                navigateToNewChat(
                    listOf(platformUid),
                    initialQuestion,
                    lastSelectedModel?.takeIf { it.platformUid == platformUid }?.model
                )
            }
            else -> openNewChatModelPicker(initialQuestion, startAfterSelection = true)
        }
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
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
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
                    onNewChatClick = { startNewChat(null, preferPrimaryPlatform = false) },
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
                    onMemoryClick = {
                        closeDrawer()
                        onMemoryClick()
                    },
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
            { openNewChatModelPicker(null, startAfterSelection = false) }
        )
    }

    if (showModelPickerSheet) {
        val enabledPlatformUids = enabledPlatforms().map { it.uid }
        val selectedPlatformUids = preferredSinglePlatformUid()
            ?.let { listOf(it) }
            ?: enabledPlatformUids.take(1)

        ModelPickerSheet(
            platforms = platformState,
            selectedPlatformUids = selectedPlatformUids,
            modelOverrides = emptyMap(),
            lastSelectedModel = lastSelectedModel,
            allowCompare = enabledPlatformUids.size > 1,
            onDismissRequest = {
                pendingInitialQuestion = null
                startAfterModelSelection = false
                showModelPickerSheet = false
            },
            onSingleModelSelected = { platformUid, model ->
                homeViewModel.updateLastSelectedModel(platformUid, model)
                val initialQuestion = pendingInitialQuestion
                val shouldStartChat = startAfterModelSelection || initialQuestion != null
                pendingInitialQuestion = null
                startAfterModelSelection = false
                showModelPickerSheet = false

                if (shouldStartChat) {
                    closeDrawer()
                    navigateToNewChat(listOf(platformUid), initialQuestion, model)
                }
            },
            onCompareSelected = { platformUids ->
                val initialQuestion = pendingInitialQuestion
                pendingInitialQuestion = null
                startAfterModelSelection = false
                showModelPickerSheet = false
                closeDrawer()
                navigateToNewChat(platformUids, initialQuestion, null)
            }
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
