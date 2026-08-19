package cn.nabr.chatwithchat.presentation.ui.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.textskill.PendingTextSkillSelection
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.ui.home.ChatHistoryDrawer
import cn.nabr.chatwithchat.presentation.ui.home.DeleteWarningDialog
import cn.nabr.chatwithchat.presentation.ui.home.HomeModelsState
import cn.nabr.chatwithchat.presentation.ui.home.HomeViewModel
import cn.nabr.chatwithchat.presentation.ui.home.modelsOrEmpty
import cn.nabr.chatwithchat.presentation.ui.home.shouldResetSelectionAfterDrawerTransition
import java.util.UUID
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 抽屉和它身后的聊天页在同一个窗口里，因此 IME insets 是全窗口共享的：抽屉里的搜索框弹出键盘时，
 * 聊天页若照常响应，输入框会跟着一起上浮。抽屉打开（含开合动画）期间该值为 true，聊天页据此忽略键盘。
 */
internal val LocalChatDrawerImeSuppressed = compositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatShellScreen(
    homeViewModel: HomeViewModel = hiltViewModel(),
    settingOnClick: () -> Unit,
    onAboutClick: () -> Unit,
    onExistingChatClick: (ChatRoomV2) -> Unit,
    navigateToNewChat: (
        enabledPlatforms: List<String>,
        initialQuestion: String?,
        initialModel: String?,
        initialAttachmentPaths: List<String>,
        initialRequestId: Int,
        initialTextSkills: List<PendingTextSkillSelection>
    ) -> Unit,
    content: @Composable (
        openDrawer: () -> Unit,
        homeViewModel: HomeViewModel,
        startNewChat: (
            initialQuestion: String?,
            initialAttachmentPaths: List<String>,
            initialTextSkills: List<PendingTextSkillSelection>,
            preferPrimaryPlatform: Boolean
        ) -> Boolean
    ) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val chatListState by homeViewModel.chatListState.collectAsStateWithLifecycle()
    val platformState by homeViewModel.platformState.collectAsStateWithLifecycle()
    val searchQuery by homeViewModel.searchQuery.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val materialColors = settingsMaterialColors()
    // 打开中、已打开、关闭中三个阶段都算抽屉持有键盘：这期间聊天页都不该跟着 IME 变形。
    val drawerImeSuppressed = drawerState.currentValue == DrawerValue.Open ||
        drawerState.targetValue == DrawerValue.Open

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
        initialTextSkills: List<PendingTextSkillSelection>,
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
        navigateToNewChat(
            listOf(model.platformUid),
            initialQuestion,
            model.modelId,
            initialAttachmentPaths,
            initialRequestId,
            initialTextSkills
        )
        return true
    }

    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED && !chatListState.isSelectionMode && !chatListState.isSearchMode) {
            homeViewModel.fetchChats()
            homeViewModel.fetchPlatformStatus()
        }
    }

    // 抽屉不是独立窗口，打开它并不会让聊天输入框失焦，键盘于是赖着不走。这里在每次开合抽屉时主动收键盘；
    // 挂在 targetValue 上而不是写进 openDrawer()，是为了同样覆盖侧滑手势和返回手势触发的开合。
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.targetValue }
            .drop(1)
            .collect {
                keyboardController?.hide()
                focusManager.clearFocus()
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                // 传 drawerState 才会走到带 DrawerPredictiveBackHandler 的那个重载：抽屉于是随返回
                // 手势缩放位移。不传的重载完全不接预见式返回，只能等松手瞬间关闭。
                drawerState = drawerState,
                drawerContainerColor = materialColors.canvas,
                drawerContentColor = materialColors.primaryLabel,
                drawerTonalElevation = 0.dp
            ) {
                // 只接多选/搜索两态，普通关抽屉交给上面的原生跟手动画。
                // 位置很关键：OnBackPressedDispatcher 是 LIFO 派发，只有组合在 ModalDrawerSheet 内部
                // 才会晚于抽屉自己的 handler 注册，从而在这两态下抢先响应。
                BackHandler(enabled = chatListState.isSelectionMode || chatListState.isSearchMode) {
                    if (chatListState.isSelectionMode) {
                        homeViewModel.disableSelectionMode()
                    } else {
                        homeViewModel.disableSearchMode()
                    }
                }
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
                    onNewChatClick = {
                        startNewChat(
                            initialQuestion = null,
                            initialAttachmentPaths = emptyList(),
                            initialTextSkills = emptyList(),
                            preferPrimaryPlatform = false
                        )
                    },
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
        CompositionLocalProvider(LocalChatDrawerImeSuppressed provides drawerImeSuppressed) {
            Box(
                // 抽屉持有键盘时把 IME insets 就地消费掉，聊天页里的 imePadding() 于是解析为 0，
                // 输入框不再跟着抽屉的搜索键盘上浮。
                modifier = if (drawerImeSuppressed) {
                    Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(WindowInsets.ime)
                } else {
                    Modifier.fillMaxSize()
                }
            ) {
                content(
                    ::openDrawer,
                    homeViewModel,
                    ::startNewChat
                )
            }
        }
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
