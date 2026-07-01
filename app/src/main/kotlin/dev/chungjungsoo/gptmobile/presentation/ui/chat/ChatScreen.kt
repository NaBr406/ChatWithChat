package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider.getUriForFile
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveThoughts
import java.io.File
import kotlinx.coroutines.delay
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
    val isChatTitleDialogOpen by chatViewModel.isChatTitleDialogOpen.collectAsStateWithLifecycle()
    val messageEditSession by chatViewModel.messageEditSession.collectAsStateWithLifecycle()
    val isSelectTextSheetOpen by chatViewModel.isSelectTextSheetOpen.collectAsStateWithLifecycle()
    val isLoaded by chatViewModel.isLoaded.collectAsStateWithLifecycle()
    val selectedAttachments by chatViewModel.selectedAttachments.collectAsStateWithLifecycle()
    val attachmentNotice by chatViewModel.attachmentNotice.collectAsStateWithLifecycle()
    val appEnabledPlatforms by chatViewModel.enabledPlatformsInApp.collectAsStateWithLifecycle()
    val chatPlatformModels by chatViewModel.chatPlatformModels.collectAsStateWithLifecycle()
    val lastSelectedModel by chatViewModel.lastSelectedModel.collectAsStateWithLifecycle()
    val enabledPlatformLookup = remember(appEnabledPlatforms) { appEnabledPlatforms.associateBy { it.uid } }
    val canUseChat = (chatViewModel.enabledPlatformsInChat.toSet() - appEnabledPlatforms.map { it.uid }.toSet()).isEmpty()
    val isIdle = loadingStates.all { it == ChatViewModel.LoadingState.Idle }
    val platformsInChat = remember(appEnabledPlatforms, chatViewModel.enabledPlatformsInChat) {
        chatViewModel.enabledPlatformsInChat.mapNotNull { uid ->
            appEnabledPlatforms.firstOrNull { it.uid == uid }
        }
    }
    var selectedPlatformUid by remember(platformsInChat, lastSelectedModel) {
        mutableStateOf(
            lastSelectedModel?.platformUid?.takeIf { uid -> platformsInChat.any { it.uid == uid } }
                ?: platformsInChat.firstOrNull()?.uid
        )
    }
    val currentModelOptions = remember(platformsInChat, selectedPlatformUid, chatPlatformModels) {
        buildModelSelectionOptions(
            platforms = platformsInChat,
            selectedPlatformUid = selectedPlatformUid,
            modelForPlatform = { platform ->
                chatPlatformModels[platform.uid]
                    ?.takeIf { it.isNotBlank() }
                    ?: platform.model
            }
        )
    }
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
        isMenuItemEnabled = chatRoom.id > 0,
        groupedMessages = groupedMessages,
        indexStates = indexStates,
        loadingStates = loadingStates,
        enabledPlatformsInChat = chatViewModel.enabledPlatformsInChat,
        enabledPlatformLookup = enabledPlatformLookup,
        canUseChat = canUseChat,
        isIdle = isIdle,
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
        onFileSelected = chatViewModel::addSelectedFile,
        onFileRemoved = chatViewModel::removeSelectedFile,
        onSendButtonClick = chatViewModel::askQuestion,
        onModelOptionSelected = { option ->
            selectedPlatformUid = option.platformUid
            chatViewModel.updateChatPlatformModelAndRemember(option.platformUid, option.model)
        },
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
                    onFileSelected = chatViewModel::addMessageEditFile,
                    onCopyFailed = chatViewModel::notifyAttachmentCopyFailed,
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
                    onFileSelected = chatViewModel::addMessageEditFile,
                    onCopyFailed = chatViewModel::notifyAttachmentCopyFailed,
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
    isMenuItemEnabled: Boolean,
    groupedMessages: ChatViewModel.GroupedMessages,
    indexStates: List<Int>,
    loadingStates: List<ChatViewModel.LoadingState>,
    enabledPlatformsInChat: List<String>,
    enabledPlatformLookup: Map<String, PlatformV2>,
    canUseChat: Boolean,
    isIdle: Boolean,
    isLoaded: Boolean,
    inputState: TextFieldState,
    selectedAttachments: List<ChatAttachmentDraft>,
    onBackAction: () -> Unit,
    onChatTitleItemClick: () -> Unit,
    onModelOptionSelected: (ModelSelectionOption) -> Unit,
    onExportChatItemClick: () -> Unit,
    onEditQuestion: (MessageV2) -> Unit,
    onEditAssistant: (Int, Int) -> Unit,
    onCopyText: (String) -> Unit,
    onPlatformClick: (Int, Int) -> Unit,
    onSelectText: (String) -> Unit,
    onRetry: (Int, Int) -> Unit,
    onShowPreviousRevision: (Int, Int) -> Unit,
    onShowNextRevision: (Int, Int) -> Unit,
    onFileSelected: (String) -> Unit,
    onFileRemoved: (String) -> Unit,
    onSendButtonClick: () -> Unit,
    navigationIcon: ImageVector,
    navigationIconContentDescription: String
) {
    val containerSize = LocalWindowInfo.current.containerSize
    val screenWidthDp = with(LocalDensity.current) { containerSize.width.toDp() }
    val focusManager = LocalFocusManager.current
    val systemChatMargin = 32.dp
    val maximumUserChatBubbleWidth = (screenWidthDp - systemChatMargin) * 0.8F
    val maximumOpponentChatBubbleWidth = screenWidthDp - systemChatMargin
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val lastMessageIndex = groupedMessages.userMessages.lastIndex
    val scope = rememberCoroutineScope()

    suspend fun animateScrollToLatestMessage() {
        if (lastMessageIndex >= 0) {
            listState.animateScrollToItem(lastMessageIndex)
        }
    }

    LaunchedEffect(isIdle) {
        animateScrollToLatestMessage()
    }

    LaunchedEffect(isLoaded) {
        animateScrollToLatestMessage()
    }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            delay(100)
            animateScrollToLatestMessage()
        }
    }

    Scaffold(
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { focusManager.clearFocus() },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatTopBar(
                title = title,
                currentModelLabel = currentModelLabel,
                currentModelOptions = currentModelOptions,
                isMenuItemEnabled = isMenuItemEnabled,
                onBackAction = onBackAction,
                navigationIcon = navigationIcon,
                navigationIconContentDescription = navigationIconContentDescription,
                scrollBehavior = scrollBehavior,
                onChatTitleItemClick = onChatTitleItemClick,
                onModelOptionSelected = onModelOptionSelected,
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
                        key = { index -> chatMessagePairKey(groupedMessages.userMessages[index], index) }
                    ) { index ->
                        ChatMessagePair(
                            messageIndex = index,
                            message = groupedMessages.userMessages[index],
                            assistantMessages = groupedMessages.assistantMessages.getOrNull(index) ?: emptyList(),
                            platformIndexState = indexStates.getOrElse(index) { 0 },
                            loadingStates = loadingStates,
                            enabledPlatformsInChat = enabledPlatformsInChat,
                            enabledPlatformLookup = enabledPlatformLookup,
                            canUseChat = canUseChat,
                            isIdle = isIdle,
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
                            onShowNextRevision = onShowNextRevision
                        )
                    }

                    if (lastMessageIndex >= 0) {
                        item(key = chatMessagePairKey(groupedMessages.userMessages[lastMessageIndex], lastMessageIndex)) {
                            ChatMessagePair(
                                messageIndex = lastMessageIndex,
                                message = groupedMessages.userMessages[lastMessageIndex],
                                assistantMessages = groupedMessages.assistantMessages.getOrNull(lastMessageIndex) ?: emptyList(),
                                platformIndexState = indexStates.getOrElse(lastMessageIndex) { 0 },
                                loadingStates = loadingStates,
                                enabledPlatformsInChat = enabledPlatformsInChat,
                                enabledPlatformLookup = enabledPlatformLookup,
                                canUseChat = canUseChat,
                                isIdle = isIdle,
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
                                onShowNextRevision = onShowNextRevision
                            )
                        }
                    }
                }

                if (listState.canScrollForward) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        ScrollToBottomButton {
                            scope.launch {
                                animateScrollToLatestMessage()
                            }
                        }
                    }
                }
            }

            ChatComposer(
                inputState = inputState,
                chatEnabled = canUseChat,
                sendButtonEnabled = isIdle,
                selectedAttachments = selectedAttachments,
                onFileSelected = onFileSelected,
                onFileRemoved = onFileRemoved
            ) {
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
    enabledPlatformsInChat: List<String>,
    enabledPlatformLookup: Map<String, PlatformV2>,
    canUseChat: Boolean,
    isIdle: Boolean,
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
    onShowNextRevision: (Int, Int) -> Unit
) {
    val selectedAssistantMessage = assistantMessages.getOrNull(platformIndexState)
    val assistantContent = selectedAssistantMessage?.effectiveContent() ?: ""
    val assistantThoughts = selectedAssistantMessage?.effectiveThoughts() ?: ""
    val canShowPreviousRevision = selectedAssistantMessage?.let { assistantMessage ->
        assistantMessage.revisions.isNotEmpty() &&
            assistantMessage.activeRevisionIndex < assistantMessage.revisions.lastIndex
    } ?: false
    val canShowNextRevision = selectedAssistantMessage?.let { assistantMessage ->
        assistantMessage.revisions.isNotEmpty() &&
            assistantMessage.activeRevisionIndex != ACTIVE_REVISION_LATEST
    } ?: false
    val selectedPlatformUid = enabledPlatformsInChat.getOrElse(platformIndexState) { "" }
    val isCurrentPlatformLoading =
        loadingStates.getOrElse(platformIndexState) { ChatViewModel.LoadingState.Idle } == ChatViewModel.LoadingState.Loading
    var isDropDownMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
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
                    canEdit = canUseChat && isIdle,
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
                GPTMobileIcon(loading = isActiveMessage && !isIdle)
                if (enabledPlatformsInChat.size > 1) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
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
            OpponentChatBubble(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp)
                    .widthIn(max = maximumOpponentChatBubbleWidth),
                canEdit = canUseChat && isIdle,
                canRetry = canUseChat && isActiveMessage && !isCurrentPlatformLoading,
                isLoading = isActiveMessage && isCurrentPlatformLoading,
                text = assistantContent,
                thoughts = assistantThoughts,
                attachments = selectedAssistantMessage?.attachments.orEmpty().map { it.filePathForDisplay },
                contentIdentity = "$messageIndex:$selectedPlatformUid",
                revisionIndexLabel = selectedAssistantMessage?.let { assistantMessage ->
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
                onCopyClick = { onCopyText(assistantContent) },
                onSelectClick = { onSelectText(assistantContent) },
                onRetryClick = { onRetry(messageIndex, platformIndexState) },
                onEditClick = { onEditAssistant(messageIndex, platformIndexState) },
                onShowPreviousRevision = { onShowPreviousRevision(messageIndex, platformIndexState) },
                onShowNextRevision = { onShowNextRevision(messageIndex, platformIndexState) }
            )
        }
    }
}

private fun chatMessagePairKey(message: MessageV2, index: Int): String = if (message.id > 0) {
    "message-${message.id}"
} else {
    "message-${message.createdAt}-$index"
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatTopBar(
    title: String,
    currentModelLabel: String,
    currentModelOptions: List<ModelSelectionOption>,
    isMenuItemEnabled: Boolean,
    onBackAction: () -> Unit,
    navigationIcon: ImageVector,
    navigationIconContentDescription: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onChatTitleItemClick: () -> Unit,
    onModelOptionSelected: (ModelSelectionOption) -> Unit,
    onExportChatItemClick: () -> Unit
) {
    var isDropDownMenuExpanded by remember { mutableStateOf(false) }

    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        ),
        title = {
            ModelSelectionMenu(
                label = currentModelLabel,
                options = currentModelOptions,
                enabled = currentModelOptions.isNotEmpty(),
                onOptionSelected = onModelOptionSelected
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
            IconButton(
                onClick = { isDropDownMenuExpanded = isDropDownMenuExpanded.not() }
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.options))
            }

            ChatDropdownMenu(
                isDropdownMenuExpanded = isDropDownMenuExpanded,
                isMenuItemEnabled = isMenuItemEnabled,
                onDismissRequest = { isDropDownMenuExpanded = false },
                onChatTitleItemClick = {
                    onChatTitleItemClick.invoke()
                    isDropDownMenuExpanded = false
                },
                onExportChatItemClick = onExportChatItemClick
            )
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
    DropdownMenu(
        modifier = Modifier.wrapContentSize(),
        expanded = isDropdownMenuExpanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            enabled = isMenuItemEnabled,
            text = { Text(text = stringResource(R.string.update_chat_title)) },
            onClick = onChatTitleItemClick
        )
        /* Export Chat */
        DropdownMenuItem(
            enabled = isMenuItemEnabled,
            text = { Text(text = stringResource(R.string.export_chat)) },
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
