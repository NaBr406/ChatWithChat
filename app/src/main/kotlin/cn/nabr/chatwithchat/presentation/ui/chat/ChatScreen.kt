package cn.nabr.chatwithchat.presentation.ui.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider.getUriForFile
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.ACTIVE_REVISION_LATEST
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.data.database.entity.effectiveThoughts
import cn.nabr.chatwithchat.data.database.entity.effectiveTokenUsage
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = hiltViewModel(),
    onBackAction: () -> Unit,
    navigationIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    navigationIconContentDescription: String? = null
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val navigationDescription = navigationIconContentDescription ?: stringResource(R.string.go_back)

    val chatRoom by chatViewModel.chatRoom.collectAsStateWithLifecycle()
    val groupedMessages by chatViewModel.groupedMessages.collectAsStateWithLifecycle()
    val indexStates by chatViewModel.indexStates.collectAsStateWithLifecycle()
    val loadingStates by chatViewModel.loadingStates.collectAsStateWithLifecycle()
    val isPersistingCompletion by chatViewModel.isPersistingCompletion.collectAsStateWithLifecycle()
    val pendingSelectedAttachmentBatches by chatViewModel.pendingSelectedAttachmentBatches.collectAsStateWithLifecycle()
    val pendingMessageEditAttachmentBatches by chatViewModel.pendingMessageEditAttachmentBatches.collectAsStateWithLifecycle()
    val toolProgressStates by chatViewModel.toolProgressStates.collectAsStateWithLifecycle()
    val isChatTitleDialogOpen by chatViewModel.isChatTitleDialogOpen.collectAsStateWithLifecycle()
    val messageEditSession by chatViewModel.messageEditSession.collectAsStateWithLifecycle()
    val isSelectTextSheetOpen by chatViewModel.isSelectTextSheetOpen.collectAsStateWithLifecycle()
    val isLoaded by chatViewModel.isLoaded.collectAsStateWithLifecycle()
    val selectedAttachments by chatViewModel.selectedAttachments.collectAsStateWithLifecycle()
    val attachmentNotice by chatViewModel.attachmentNotice.collectAsStateWithLifecycle()
    val appEnabledPlatforms by chatViewModel.enabledPlatformsInApp.collectAsStateWithLifecycle()
    val chatPlatformModels by chatViewModel.chatPlatformModels.collectAsStateWithLifecycle()
    val lastSelectedModel by chatViewModel.lastSelectedModel.collectAsStateWithLifecycle()
    val availableChatModels by chatViewModel.availableChatModels.collectAsStateWithLifecycle()
    val currentReasoningMode by chatViewModel.currentReasoningMode.collectAsStateWithLifecycle()
    val enabledPlatformLookup = remember(appEnabledPlatforms) { appEnabledPlatforms.associateBy { it.uid } }
    val isIdle = loadingStates.all { it == ChatViewModel.LoadingState.Idle }
    val canSubmitOrEdit = isIdle && !isPersistingCompletion
    val currentModelOptions = remember(availableChatModels, lastSelectedModel) {
        buildModelSelectionOptions(
            models = availableChatModels,
            selectedPlatformUid = lastSelectedModel?.platformUid,
            selectedModel = lastSelectedModel?.model
        )
    }
    val canUseChat = currentModelOptions.isNotEmpty() && chatPlatformModels.isNotEmpty()
    val currentModelLabel = currentModelOptions.firstOrNull { it.selected }?.label
        ?: currentModelOptions.firstOrNull()?.label
        ?: stringResource(R.string.chat_models)

    LaunchedEffect(attachmentNotice) {
        attachmentNotice?.let { notice ->
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            chatViewModel.consumeAttachmentNotice()
        }
    }

    ChatContent(
        title = chatRoom.title,
        currentModelLabel = currentModelLabel,
        currentModelOptions = currentModelOptions,
        currentReasoningMode = currentReasoningMode,
        isMenuItemEnabled = chatRoom.id > 0,
        groupedMessages = groupedMessages,
        indexStates = indexStates,
        loadingStates = loadingStates,
        toolProgressStates = toolProgressStates,
        enabledPlatformsInChat = chatViewModel.enabledPlatformsInChat,
        enabledPlatformLookup = enabledPlatformLookup,
        canUseChat = canUseChat,
        isIdle = isIdle,
        canSubmitOrEdit = canSubmitOrEdit,
        isAttachmentImportInProgress = pendingSelectedAttachmentBatches > 0,
        isLoaded = isLoaded,
        inputState = chatViewModel.question,
        selectedAttachments = selectedAttachments,
        onBackAction = onBackAction,
        onChatTitleItemClick = chatViewModel::openChatTitleDialog,
        onExportChatItemClick = { exportChat(context, chatViewModel) },
        onEditQuestion = chatViewModel::openUserMessageEditDialog,
        onEditAssistant = chatViewModel::openAssistantMessageEditDialog,
        onCopyText = { copiedText ->
            scope.launch {
                clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText(copiedText, copiedText)))
            }
        },
        onPlatformClick = chatViewModel::updateChatPlatformIndex,
        onSelectText = chatViewModel::openSelectTextSheet,
        onRetry = chatViewModel::retryChat,
        onShowPreviousRevision = chatViewModel::showPreviousAssistantRevision,
        onShowNextRevision = chatViewModel::showNextAssistantRevision,
        onFilesSelected = chatViewModel::addSelectedFiles,
        onFileRemoved = chatViewModel::removeSelectedFile,
        onSendButtonClick = chatViewModel::askQuestion,
        onModelOptionSelected = { option ->
            chatViewModel.updateChatPlatformModelAndRemember(option.platformUid, option.model)
        },
        onReasoningModeSelected = chatViewModel::updateChatReasoningModeAndRemember,
        navigationIcon = navigationIcon,
        navigationIconContentDescription = navigationDescription
    )

    if (isChatTitleDialogOpen) {
        ChatTitleDialog(
            initialTitle = chatRoom.title,
            onDefaultTitleMode = chatViewModel::generateDefaultChatTitle,
            onConfirmRequest = { title -> chatViewModel.updateChatTitle(title) },
            onDismissRequest = chatViewModel::closeChatTitleDialog
        )
    }

    messageEditSession?.let { session ->
        when (session.role) {
            ChatViewModel.MessageEditRole.USER -> {
                UserMessageEditDialog(
                    initialQuestion = session.message,
                    attachments = session.attachments,
                    isAttachmentImportInProgress = pendingMessageEditAttachmentBatches > 0,
                    onFilesSelected = chatViewModel::addMessageEditFiles,
                    onFileRemoved = chatViewModel::removeMessageEditFile,
                    onDismissRequest = chatViewModel::discardMessageEditDialog,
                    onConfirmRequest = { question ->
                        if (chatViewModel.saveUserMessageEdit(question, session.attachments)) {
                            chatViewModel.finishMessageEditDialog()
                        }
                    }
                )
            }

            ChatViewModel.MessageEditRole.ASSISTANT -> {
                AssistantMessageEditDialog(
                    initialMessage = session.message,
                    attachments = session.attachments,
                    isAttachmentImportInProgress = pendingMessageEditAttachmentBatches > 0,
                    onFilesSelected = chatViewModel::addMessageEditFiles,
                    onFileRemoved = chatViewModel::removeMessageEditFile,
                    onDismissRequest = chatViewModel::discardMessageEditDialog,
                    onConfirmRequest = { message, thoughts ->
                        if (chatViewModel.saveAssistantMessageEdit(message, thoughts, session.attachments)) {
                            chatViewModel.finishMessageEditDialog()
                        }
                    }
                )
            }
        }
    }

    if (isSelectTextSheetOpen) {
        val selectedText by chatViewModel.selectedText.collectAsStateWithLifecycle()
        ModalBottomSheet(onDismissRequest = chatViewModel::closeSelectTextSheet) {
            SelectionContainer(
                modifier = Modifier
                    .padding(24.dp)
                    .heightIn(min = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(selectedText)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatContent(
    title: String,
    currentModelLabel: String,
    currentModelOptions: List<ModelSelectionOption>,
    currentReasoningMode: ReasoningMode,
    isMenuItemEnabled: Boolean,
    groupedMessages: ChatViewModel.GroupedMessages,
    indexStates: List<Int>,
    loadingStates: List<ChatViewModel.LoadingState>,
    toolProgressStates: Map<String, List<ChatViewModel.ToolProgressState>>,
    enabledPlatformsInChat: List<String>,
    enabledPlatformLookup: Map<String, PlatformV2>,
    canUseChat: Boolean,
    isIdle: Boolean,
    canSubmitOrEdit: Boolean,
    isAttachmentImportInProgress: Boolean,
    isLoaded: Boolean,
    inputState: TextFieldState,
    selectedAttachments: List<ChatAttachmentDraft>,
    onBackAction: () -> Unit,
    onChatTitleItemClick: () -> Unit,
    onModelOptionSelected: (ModelSelectionOption) -> Unit,
    onReasoningModeSelected: (ReasoningMode) -> Unit,
    onExportChatItemClick: () -> Unit,
    onEditQuestion: (MessageV2) -> Unit,
    onEditAssistant: (Int, Int) -> Unit,
    onCopyText: (String) -> Unit,
    onPlatformClick: (Int, Int) -> Unit,
    onSelectText: (String) -> Unit,
    onRetry: (Int, Int) -> Unit,
    onShowPreviousRevision: (Int, Int) -> Unit,
    onShowNextRevision: (Int, Int) -> Unit,
    onFilesSelected: (List<String>, Int) -> Unit,
    onFileRemoved: (String) -> Unit,
    onSendButtonClick: () -> Unit,
    navigationIcon: ImageVector,
    navigationIconContentDescription: String
) {
    val containerSize = LocalWindowInfo.current.containerSize
    val screenWidthDp = with(LocalDensity.current) { containerSize.width.toDp() }
    val screenHeightDp = with(LocalDensity.current) { containerSize.height.toDp() }
    val focusManager = LocalFocusManager.current
    val systemChatMargin = 32.dp
    val maximumUserChatBubbleWidth = ((screenWidthDp - systemChatMargin) * 0.78F).coerceAtMost(560.dp)
    val maximumOpponentChatBubbleWidth = screenWidthDp - systemChatMargin
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val lastMessageIndex = groupedMessages.userMessages.lastIndex
    val bottomItemIndex = groupedMessages.userMessages.size
    val roundNavigationItems = remember(groupedMessages, loadingStates, enabledPlatformsInChat, enabledPlatformLookup) {
        buildRoundNavigationItems(
            groupedMessages = groupedMessages,
            loadingStates = loadingStates,
            enabledPlatformsInChat = enabledPlatformsInChat,
            enabledPlatformLookup = enabledPlatformLookup
        )
    }
    val currentTurnIndex by remember(roundNavigationItems, listState) {
        derivedStateOf {
            if (roundNavigationItems.isEmpty()) {
                null
            } else {
                val layoutInfo = listState.layoutInfo
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                val centeredVisibleTurnIndex = layoutInfo.visibleItemsInfo
                    .asSequence()
                    .filter { itemInfo -> itemInfo.index in roundNavigationItems.indices }
                    .minByOrNull { itemInfo ->
                        abs((itemInfo.offset + itemInfo.size / 2) - viewportCenter)
                    }
                    ?.index
                val visibleTurnIndex = centeredVisibleTurnIndex
                    ?: listState.firstVisibleItemIndex.coerceIn(0, roundNavigationItems.lastIndex)
                roundNavigationItems[visibleTurnIndex].turnIndex
            }
        }
    }
    val roundNavigatorMaxHeight = (screenHeightDp * 0.62f).coerceAtMost(520.dp)
    val latestStreamingContentVersion = remember(groupedMessages, toolProgressStates) {
        buildString {
            append(groupedMessages.userMessages.size)
            append('|')
            groupedMessages.assistantMessages.lastOrNull().orEmpty().forEach { message ->
                append(message.effectiveContent().length)
                append(':')
                append(message.effectiveThoughts().length)
                append(':')
                append(message.attachments.size)
                append('|')
            }
            toolProgressStates.entries.sortedBy { it.key }.forEach { (key, states) ->
                append('|')
                append(key)
                append(':')
                append(states.size)
                append(':')
                append(states.lastOrNull()?.status?.name.orEmpty())
            }
        }
    }
    val scope = rememberCoroutineScope()
    val currentBottomItemIndex by rememberUpdatedState(bottomItemIndex)
    val turnKeyRegistry = remember { ChatTurnKeyRegistry() }
    val turnKeys = remember(groupedMessages.userMessages) {
        turnKeyRegistry.update(
            groupedMessages.userMessages.map { message ->
                ChatTurnIdentity(persistedMessageId = message.id)
            }
        )
    }
    var scrollIntent by rememberSaveable(stateSaver = chatScrollIntentSaver) {
        mutableStateOf<ChatScrollIntent>(ChatScrollIntent.FollowingLatest)
    }
    var programmaticScrollInProgress by remember { mutableStateOf(false) }
    var previousBottomItemIndex by remember { mutableStateOf(bottomItemIndex) }
    var hasHandledInitialLoad by rememberSaveable { mutableStateOf(false) }
    var isRoundNavigatorOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = isRoundNavigatorOpen) {
        isRoundNavigatorOpen = false
    }
    val showScrollToBottomButton by remember {
        derivedStateOf {
            listState.canScrollForward &&
                scrollIntent is ChatScrollIntent.ReadingHistory &&
                !programmaticScrollInProgress
        }
    }

    suspend fun scrollToLatestMessage(animated: Boolean) {
        scrollIntent = reduceChatScrollIntent(scrollIntent, ChatScrollEvent.FollowLatestRequested)
        programmaticScrollInProgress = true
        try {
            if (animated) {
                listState.animateScrollToItem(currentBottomItemIndex)
            } else {
                listState.scrollToItem(currentBottomItemIndex)
            }
        } finally {
            programmaticScrollInProgress = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val firstVisibleTurn = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { item -> item.key != CHAT_BOTTOM_KEY }
            ChatScrollViewport(
                isScrollInProgress = listState.isScrollInProgress,
                canScrollForward = listState.canScrollForward,
                anchor = (firstVisibleTurn?.key as? String)?.let { turnKey ->
                    ChatScrollAnchor(
                        turnKey = turnKey,
                        offset = listState.firstVisibleItemScrollOffset
                    )
                }
            )
        }.collect { viewport ->
            if (!programmaticScrollInProgress && viewport.isScrollInProgress) {
                scrollIntent = reduceChatScrollIntent(
                    current = scrollIntent,
                    event = ChatScrollEvent.UserScrolled(
                        canScrollForward = viewport.canScrollForward,
                        anchor = viewport.anchor
                    )
                )
            } else if (
                scrollIntent.shouldRestoreFollowingViewport(
                    isScrollInProgress = viewport.isScrollInProgress,
                    canScrollForward = viewport.canScrollForward,
                    isProgrammaticScrollInProgress = programmaticScrollInProgress
                )
            ) {
                scrollToLatestMessage(animated = false)
            }
        }
    }

    LaunchedEffect(isLoaded) {
        if (isLoaded && !hasHandledInitialLoad && scrollIntent == ChatScrollIntent.FollowingLatest) {
            hasHandledInitialLoad = true
            scrollToLatestMessage(animated = false)
        }
    }

    LaunchedEffect(bottomItemIndex) {
        val previousIndex = previousBottomItemIndex
        previousBottomItemIndex = bottomItemIndex
        if (bottomItemIndex > previousIndex && scrollIntent == ChatScrollIntent.FollowingLatest) {
            scrollToLatestMessage(animated = false)
        }
    }

    LaunchedEffect(latestStreamingContentVersion) {
        if (scrollIntent.shouldFollowStreaming(isStreaming = !isIdle)) {
            scrollToLatestMessage(animated = false)
        }
    }

    LaunchedEffect(isIdle) {
        if (isIdle) {
            scrollIntent = reduceChatScrollIntent(scrollIntent, ChatScrollEvent.StreamCompleted)
        }
    }

    LaunchedEffect(turnKeys) {
        val readingIntent = scrollIntent as? ChatScrollIntent.ReadingHistory ?: return@LaunchedEffect
        val anchorIndex = turnKeys.indexOf(readingIntent.anchor.turnKey).takeIf { it >= 0 }
            ?: return@LaunchedEffect
        val visibleTurnKey = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { item -> item.key != CHAT_BOTTOM_KEY }
            ?.key as? String
        if (
            visibleTurnKey == readingIntent.anchor.turnKey &&
            listState.firstVisibleItemScrollOffset == readingIntent.anchor.offset
        ) {
            return@LaunchedEffect
        }

        programmaticScrollInProgress = true
        try {
            listState.scrollToItem(anchorIndex, readingIntent.anchor.offset)
        } finally {
            programmaticScrollInProgress = false
        }
    }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            scrollToLatestMessage(animated = false)
        }
    }

    Scaffold(
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { focusManager.clearFocus() },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = settingsMaterialColors().canvas,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            ChatTopBar(
                title = title,
                currentModelLabel = currentModelLabel,
                currentModelOptions = currentModelOptions,
                currentReasoningMode = currentReasoningMode,
                roundCount = roundNavigationItems.size,
                isRoundNavigatorOpen = isRoundNavigatorOpen,
                isMenuItemEnabled = isMenuItemEnabled,
                onBackAction = onBackAction,
                navigationIcon = navigationIcon,
                navigationIconContentDescription = navigationIconContentDescription,
                scrollBehavior = scrollBehavior,
                onRoundCountClick = { isRoundNavigatorOpen = !isRoundNavigatorOpen },
                onChatTitleItemClick = onChatTitleItemClick,
                onModelOptionSelected = onModelOptionSelected,
                onReasoningModeSelected = onReasoningModeSelected,
                onExportChatItemClick = onExportChatItemClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
                ) {
                    val historicalMessageCount = lastMessageIndex.coerceAtLeast(0)

                    items(
                        count = historicalMessageCount,
                        key = { index -> turnKeys[index] }
                    ) { index ->
                        ChatMessagePair(
                            messageIndex = index,
                            message = groupedMessages.userMessages[index],
                            assistantMessages = groupedMessages.assistantMessages.getOrNull(index) ?: emptyList(),
                            platformIndexState = indexStates.getOrElse(index) { 0 },
                            loadingStates = loadingStates,
                            toolProgressStates = toolProgressStates,
                            enabledPlatformsInChat = enabledPlatformsInChat,
                            enabledPlatformLookup = enabledPlatformLookup,
                            canUseChat = canUseChat,
                            isIdle = isIdle,
                            canSubmitOrEdit = canSubmitOrEdit,
                            isActiveMessage = false,
                            maximumUserChatBubbleWidth = maximumUserChatBubbleWidth,
                            maximumOpponentChatBubbleWidth = maximumOpponentChatBubbleWidth,
                            onEditQuestion = onEditQuestion,
                            onEditAssistant = onEditAssistant,
                            onCopyText = onCopyText,
                            onPlatformClick = onPlatformClick,
                            onSelectText = onSelectText,
                            onRetry = onRetry,
                            onShowPreviousRevision = onShowPreviousRevision,
                            onShowNextRevision = onShowNextRevision,
                            onStreamingTextDisplayed = {
                                if (scrollIntent.shouldFollowStreaming(isStreaming = !isIdle)) {
                                    scope.launch {
                                        scrollToLatestMessage(animated = false)
                                    }
                                }
                            }
                        )
                    }

                    if (lastMessageIndex >= 0) {
                        item(key = turnKeys[lastMessageIndex]) {
                            ChatMessagePair(
                                messageIndex = lastMessageIndex,
                                message = groupedMessages.userMessages[lastMessageIndex],
                                assistantMessages = groupedMessages.assistantMessages.getOrNull(lastMessageIndex) ?: emptyList(),
                                platformIndexState = indexStates.getOrElse(lastMessageIndex) { 0 },
                                loadingStates = loadingStates,
                                toolProgressStates = toolProgressStates,
                                enabledPlatformsInChat = enabledPlatformsInChat,
                                enabledPlatformLookup = enabledPlatformLookup,
                                canUseChat = canUseChat,
                                isIdle = isIdle,
                                canSubmitOrEdit = canSubmitOrEdit,
                                isActiveMessage = true,
                                maximumUserChatBubbleWidth = maximumUserChatBubbleWidth,
                                maximumOpponentChatBubbleWidth = maximumOpponentChatBubbleWidth,
                                onEditQuestion = onEditQuestion,
                                onEditAssistant = onEditAssistant,
                                onCopyText = onCopyText,
                                onPlatformClick = onPlatformClick,
                                onSelectText = onSelectText,
                                onRetry = onRetry,
                                onShowPreviousRevision = onShowPreviousRevision,
                                onShowNextRevision = onShowNextRevision,
                                onStreamingTextDisplayed = {
                                    if (scrollIntent.shouldFollowStreaming(isStreaming = !isIdle)) {
                                        scope.launch {
                                            scrollToLatestMessage(animated = false)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    item(key = CHAT_BOTTOM_KEY) {
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                }

                if (showScrollToBottomButton) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        ScrollToBottomButton {
                            scope.launch {
                                scrollToLatestMessage(animated = true)
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = isRoundNavigatorOpen,
                    enter = fadeIn(animationSpec = tween(140)) + scaleIn(
                        animationSpec = tween(180),
                        initialScale = 0.96f,
                        transformOrigin = TransformOrigin(1f, 0f)
                    ),
                    exit = fadeOut(animationSpec = tween(110)) + scaleOut(
                        animationSpec = tween(130),
                        targetScale = 0.96f,
                        transformOrigin = TransformOrigin(1f, 0f)
                    )
                ) {
                    RoundNavigatorOverlay(
                        items = roundNavigationItems,
                        currentTurnIndex = currentTurnIndex,
                        maxHeight = roundNavigatorMaxHeight,
                        onDismiss = { isRoundNavigatorOpen = false },
                        onTurnClick = { turnIndex ->
                            isRoundNavigatorOpen = false
                            val turnKey = turnKeys.getOrNull(turnIndex)
                            if (turnKey != null) {
                                scrollIntent = ChatScrollIntent.ReadingHistory(
                                    ChatScrollAnchor(turnKey = turnKey, offset = 0)
                                )
                            }
                            scope.launch {
                                programmaticScrollInProgress = true
                                try {
                                    listState.animateScrollToItem(turnIndex)
                                } finally {
                                    programmaticScrollInProgress = false
                                }
                            }
                        }
                    )
                }
            }

            ChatComposer(
                inputState = inputState,
                chatEnabled = canUseChat,
                sendButtonEnabled = canSubmitOrEdit,
                isAttachmentImportInProgress = isAttachmentImportInProgress,
                selectedAttachments = selectedAttachments,
                onFilesSelected = onFilesSelected,
                onFileRemoved = onFileRemoved
            ) {
                scrollIntent = reduceChatScrollIntent(scrollIntent, ChatScrollEvent.FollowLatestRequested)
                onSendButtonClick()
                focusManager.clearFocus()
            }
        }
    }
}

@Composable
private fun ChatMessagePair(
    messageIndex: Int,
    message: MessageV2,
    assistantMessages: List<MessageV2>,
    platformIndexState: Int,
    loadingStates: List<ChatViewModel.LoadingState>,
    toolProgressStates: Map<String, List<ChatViewModel.ToolProgressState>>,
    enabledPlatformsInChat: List<String>,
    enabledPlatformLookup: Map<String, PlatformV2>,
    canUseChat: Boolean,
    isIdle: Boolean,
    canSubmitOrEdit: Boolean,
    isActiveMessage: Boolean,
    maximumUserChatBubbleWidth: Dp,
    maximumOpponentChatBubbleWidth: Dp,
    onEditQuestion: (MessageV2) -> Unit,
    onEditAssistant: (Int, Int) -> Unit,
    onCopyText: (String) -> Unit,
    onPlatformClick: (Int, Int) -> Unit,
    onSelectText: (String) -> Unit,
    onRetry: (Int, Int) -> Unit,
    onShowPreviousRevision: (Int, Int) -> Unit,
    onShowNextRevision: (Int, Int) -> Unit,
    onStreamingTextDisplayed: () -> Unit
) {
    val selectedAssistantMessage = assistantMessages.getOrNull(platformIndexState)
    val assistantContent = selectedAssistantMessage?.effectiveContent() ?: ""
    val assistantThoughts = selectedAssistantMessage?.effectiveThoughts() ?: ""
    val selectedTokenUsage = selectedAssistantMessage?.effectiveTokenUsage()
    val turnTokenUsages = remember(assistantMessages) { assistantMessages.mapNotNull { it.effectiveTokenUsage() } }
    val canShowPreviousRevision = selectedAssistantMessage?.let { assistantMessage ->
        assistantMessage.revisions.isNotEmpty() &&
            assistantMessage.activeRevisionIndex < assistantMessage.revisions.lastIndex
    } ?: false
    val canShowNextRevision = selectedAssistantMessage?.let { assistantMessage ->
        assistantMessage.revisions.isNotEmpty() &&
            assistantMessage.activeRevisionIndex != ACTIVE_REVISION_LATEST
    } ?: false
    val selectedPlatformUid = enabledPlatformsInChat.getOrElse(platformIndexState) { "" }
    val selectedToolProgressStates = toolProgressStates[ChatViewModel.toolProgressKey(messageIndex, platformIndexState)].orEmpty()
    val isCurrentPlatformLoading =
        loadingStates.getOrElse(platformIndexState) { ChatViewModel.LoadingState.Idle } == ChatViewModel.LoadingState.Loading
    val isInterruptedInitialRequest = shouldShowInterruptedInitialRequest(
        initialRequestId = message.linkedMessageId,
        assistantContent = assistantContent,
        assistantThoughts = assistantThoughts,
        hasAttachments = selectedAssistantMessage?.attachments?.isNotEmpty() == true,
        isLoading = isCurrentPlatformLoading
    )
    val displayedAssistantContent = if (isInterruptedInitialRequest) {
        stringResource(R.string.initial_request_interrupted)
    } else {
        assistantContent
    }
    var isDropDownMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.End
        ) {
            Box {
                UserChatBubble(
                    modifier = Modifier.widthIn(max = maximumUserChatBubbleWidth),
                    text = message.content,
                    files = message.attachments.map { it.filePathForDisplay },
                    onLongPress = { isDropDownMenuExpanded = true }
                )
                ChatBubbleDropdownMenu(
                    isChatBubbleDropdownMenuExpanded = isDropDownMenuExpanded,
                    canEdit = canUseChat && canSubmitOrEdit,
                    onDismissRequest = { isDropDownMenuExpanded = false },
                    onEditItemClick = { onEditQuestion(message) },
                    onCopyItemClick = { onCopyText(message.content) }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (enabledPlatformsInChat.size > 1) 8.dp else 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (enabledPlatformsInChat.size > 1) {
                    Row(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        enabledPlatformsInChat.forEachIndexed { platformIndex, uid ->
                            val platformLoadingState = loadingStates.getOrElse(platformIndex) { ChatViewModel.LoadingState.Idle }
                            PlatformButton(
                                isLoading = isActiveMessage && platformLoadingState == ChatViewModel.LoadingState.Loading,
                                name = enabledPlatformLookup[uid]?.name ?: stringResource(R.string.unknown),
                                selected = platformIndexState == platformIndex,
                                onPlatformClick = { onPlatformClick(messageIndex, platformIndex) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }
            }
            if (selectedToolProgressStates.isNotEmpty()) {
                ToolActivityBlock(
                    progressStates = selectedToolProgressStates,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            OpponentChatBubble(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = maximumOpponentChatBubbleWidth),
                canEdit = canUseChat && canSubmitOrEdit && !isInterruptedInitialRequest,
                canRetry = canUseChat && isActiveMessage && !isCurrentPlatformLoading,
                isLoading = isActiveMessage && isCurrentPlatformLoading,
                showPendingIndicator = selectedToolProgressStates.none { state ->
                    state.status == ChatViewModel.ToolProgressStatus.Running
                },
                isError = isInterruptedInitialRequest,
                text = displayedAssistantContent,
                thoughts = assistantThoughts,
                attachments = selectedAssistantMessage?.attachments.orEmpty().map { it.filePathForDisplay },
                sourceMetadata = selectedAssistantMessage?.sourceMetadata.orEmpty(),
                contentIdentity = "$messageIndex:$selectedPlatformUid",
                revisionIndexLabel = selectedAssistantMessage?.takeUnless { isInterruptedInitialRequest }?.let { assistantMessage ->
                    val totalRevisions = assistantMessage.revisions.size + 1
                    if (assistantMessage.activeRevisionIndex == ACTIVE_REVISION_LATEST) {
                        stringResource(
                            R.string.revision_counter,
                            totalRevisions,
                            totalRevisions
                        )
                    } else {
                        stringResource(
                            R.string.revision_counter,
                            assistantMessage.revisions.size - assistantMessage.activeRevisionIndex,
                            totalRevisions
                        )
                    }
                },
                canShowPreviousRevision = canShowPreviousRevision,
                canShowNextRevision = canShowNextRevision,
                onCopyClick = { onCopyText(displayedAssistantContent) },
                onSelectClick = { onSelectText(displayedAssistantContent) },
                onRetryClick = { onRetry(messageIndex, platformIndexState) },
                onEditClick = { onEditAssistant(messageIndex, platformIndexState) },
                onShowPreviousRevision = { onShowPreviousRevision(messageIndex, platformIndexState) },
                onShowNextRevision = { onShowNextRevision(messageIndex, platformIndexState) },
                onStreamingTextDisplayed = onStreamingTextDisplayed
            )
            TokenUsageRow(
                usage = selectedTokenUsage,
                turnUsages = turnTokenUsages,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = maximumOpponentChatBubbleWidth)
            )
        }
    }
}

private const val CHAT_BOTTOM_KEY = "chat-bottom"

private data class ChatScrollViewport(
    val isScrollInProgress: Boolean,
    val canScrollForward: Boolean,
    val anchor: ChatScrollAnchor?
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatTopBar(
    title: String,
    currentModelLabel: String,
    currentModelOptions: List<ModelSelectionOption>,
    currentReasoningMode: ReasoningMode,
    roundCount: Int,
    isRoundNavigatorOpen: Boolean,
    isMenuItemEnabled: Boolean,
    onBackAction: () -> Unit,
    navigationIcon: ImageVector,
    navigationIconContentDescription: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onRoundCountClick: () -> Unit,
    onChatTitleItemClick: () -> Unit,
    onModelOptionSelected: (ModelSelectionOption) -> Unit,
    onReasoningModeSelected: (ReasoningMode) -> Unit,
    onExportChatItemClick: () -> Unit
) {
    var isDropDownMenuExpanded by remember { mutableStateOf(false) }

    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = settingsMaterialColors().navigation,
            scrolledContainerColor = settingsMaterialColors().navigation,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        ),
        title = {
            ModelSelectionMenu(
                label = currentModelLabel,
                options = currentModelOptions,
                selectedReasoningMode = currentReasoningMode,
                enabled = currentModelOptions.isNotEmpty(),
                onOptionSelected = onModelOptionSelected,
                onReasoningModeSelected = onReasoningModeSelected
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onBackAction
            ) {
                Icon(imageVector = navigationIcon, contentDescription = navigationIconContentDescription)
            }
        },
        actions = {
            if (roundCount > 0) {
                CompletedRoundButton(
                    roundCount = roundCount,
                    isExpanded = isRoundNavigatorOpen,
                    onClick = onRoundCountClick
                )
            }
            Box(contentAlignment = Alignment.Center) {
                val overflowButtonColor by animateColorAsState(
                    targetValue = settingsMaterialColors().controlFill.copy(
                        alpha = if (isDropDownMenuExpanded) 0.44f else 0f
                    ),
                    label = "chatOverflowButtonColor"
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(overflowButtonColor, CircleShape)
                )
                IconButton(
                    onClick = { isDropDownMenuExpanded = isDropDownMenuExpanded.not() }
                ) {
                    Icon(
                        modifier = Modifier.size(22.dp),
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(R.string.options)
                    )
                }

                ChatDropdownMenu(
                    isDropdownMenuExpanded = isDropDownMenuExpanded,
                    isMenuItemEnabled = isMenuItemEnabled,
                    onDismissRequest = { isDropDownMenuExpanded = false },
                    onChatTitleItemClick = {
                        onChatTitleItemClick()
                        isDropDownMenuExpanded = false
                    },
                    onExportChatItemClick = onExportChatItemClick
                )
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
fun ChatDropdownMenu(
    isDropdownMenuExpanded: Boolean,
    isMenuItemEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onChatTitleItemClick: () -> Unit,
    onExportChatItemClick: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    DropdownMenu(
        modifier = Modifier.widthIn(min = 216.dp, max = 280.dp),
        expanded = isDropdownMenuExpanded,
        onDismissRequest = onDismissRequest,
        offset = DpOffset(x = (-8).dp, y = 4.dp),
        shape = RoundedCornerShape(14.dp),
        containerColor = materialColors.grouped,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(0.5.dp, materialColors.separatorStrong)
    ) {
        DropdownMenuItem(
            modifier = Modifier.heightIn(min = 52.dp),
            enabled = isMenuItemEnabled,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.update_chat_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            },
            onClick = onChatTitleItemClick
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp, end = 12.dp),
            thickness = 0.5.dp,
            color = materialColors.separator
        )
        DropdownMenuItem(
            modifier = Modifier.heightIn(min = 52.dp),
            enabled = isMenuItemEnabled,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.IosShare,
                    contentDescription = null
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.export_chat),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            },
            onClick = {
                onExportChatItemClick()
                onDismissRequest()
            }
        )
    }
}

@Composable
fun ChatBubbleDropdownMenu(
    isChatBubbleDropdownMenuExpanded: Boolean,
    canEdit: Boolean,
    onDismissRequest: () -> Unit,
    onEditItemClick: () -> Unit,
    onCopyItemClick: () -> Unit
) {
    DropdownMenu(
        modifier = Modifier.wrapContentSize(),
        expanded = isChatBubbleDropdownMenuExpanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            enabled = canEdit,
            leadingIcon = {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.edit)
                )
            },
            text = { Text(text = stringResource(R.string.edit)) },
            onClick = {
                onEditItemClick.invoke()
                onDismissRequest.invoke()
            }
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.copy_text)
                )
            },
            text = { Text(text = stringResource(R.string.copy_text)) },
            onClick = {
                onCopyItemClick.invoke()
                onDismissRequest.invoke()
            }
        )
    }
}

private fun exportChat(context: Context, chatViewModel: ChatViewModel) {
    try {
        val (fileName, fileContent) = chatViewModel.exportChat()
        val file = File(context.getExternalFilesDir(null), fileName)
        file.writeText(fileContent)
        val uri = getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share_chat_export)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resInfo = context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        resInfo.forEach { res ->
            context.grantUriPermission(res.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.e("ChatExport", "导出会话失败", e)
        Toast.makeText(context, context.getString(R.string.export_chat_failed), Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun ScrollToBottomButton(onClick: () -> Unit) {
    SmallFloatingActionButton(
        modifier = Modifier.size(40.dp),
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = stringResource(R.string.scroll_to_bottom_icon),
            modifier = Modifier.size(20.dp)
        )
    }
}
