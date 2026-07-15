package cn.nabr.chatwithchat.presentation.ui.chat

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.ACTIVE_REVISION_LATEST
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.data.database.entity.effectiveThoughts
import cn.nabr.chatwithchat.data.database.entity.resetActiveRevision
import cn.nabr.chatwithchat.data.database.entity.selectRevision
import cn.nabr.chatwithchat.data.database.entity.snapshotLatestAssistantRevision
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.memory.MemoryCompletedTurnInput
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.model.ChatPlatformConfig
import cn.nabr.chatwithchat.data.model.LastSelectedModel
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.model.coerceReasoningModeForModel
import cn.nabr.chatwithchat.data.model.defaultReasoningMode
import cn.nabr.chatwithchat.data.model.reasoningModesForModel
import cn.nabr.chatwithchat.data.repository.AttachmentUploadCoordinator
import cn.nabr.chatwithchat.data.repository.ChatRepository
import cn.nabr.chatwithchat.data.repository.MemoryRepository
import cn.nabr.chatwithchat.data.repository.SettingRepository
import cn.nabr.chatwithchat.di.ApplicationScope
import cn.nabr.chatwithchat.util.AttachmentPayloadCache
import cn.nabr.chatwithchat.util.FileUtils
import cn.nabr.chatwithchat.util.getPlatformName
import cn.nabr.chatwithchat.util.handleStates
import java.util.Collections
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val memoryRepository: MemoryRepository,
    private val attachmentUploadCoordinator: AttachmentUploadCoordinator,
    @param:ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {
    sealed class LoadingState {
        data object Idle : LoadingState()
        data object Loading : LoadingState()
    }

    enum class ToolProgressStatus {
        Running,
        Finished,
        Failed
    }

    data class ToolProgressState(
        val toolName: String,
        val label: String,
        val status: ToolProgressStatus,
        val message: String? = null,
        val errorCode: String? = null
    )

    data class GroupedMessages(
        val userMessages: List<MessageV2> = listOf(),
        val assistantMessages: List<List<MessageV2>> = listOf()
    )

    enum class MessageEditRole {
        USER,
        ASSISTANT
    }

    data class MessageEditSession(
        val message: MessageV2,
        val role: MessageEditRole,
        val turnIndex: Int? = null,
        val platformIndex: Int? = null,
        val attachments: List<ChatAttachmentDraft> = emptyList()
    )

    private data class ResolvedChatPlatform(
        val platform: PlatformV2,
        val reasoningMode: ReasoningMode
    )

    private val routeChatRoomId: Int = checkNotNull(savedStateHandle["chatRoomId"])
    private val launchState = ChatLaunchState(savedStateHandle, routeChatRoomId)
    private val chatRoomId: Int
        get() = launchState.chatRoomId
    private val enabledPlatformString: String = checkNotNull(savedStateHandle["enabledPlatforms"])
    private val initialQuestion: String = Uri.decode(savedStateHandle.get<String>("initialQuestion").orEmpty())
    private val initialModel: String = Uri.decode(savedStateHandle.get<String>("initialModel").orEmpty())
    private val initialRequestId: Int = savedStateHandle["initialRequestId"] ?: 0
    private val initialAttachmentPaths: List<String> = Uri.decode(savedStateHandle.get<String>("initialAttachments").orEmpty())
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    private val initialEnabledPlatformsInChat = enabledPlatformString.split(',').filter { it.isNotBlank() }
    val enabledPlatformsInChat: List<String>
        get() = _chatRoom.value.enabledPlatform

    private val currentTimeStamp: Long
        get() = System.currentTimeMillis() / 1000

    private val _chatRoom = MutableStateFlow(ChatRoomV2(id = -1, title = "", enabledPlatform = initialEnabledPlatformsInChat))
    val chatRoom = _chatRoom.asStateFlow()

    private val _isChatTitleDialogOpen = MutableStateFlow(false)
    val isChatTitleDialogOpen = _isChatTitleDialogOpen.asStateFlow()

    private val _messageEditSession = MutableStateFlow<MessageEditSession?>(null)
    val messageEditSession = _messageEditSession.asStateFlow()

    private val _isSelectTextSheetOpen = MutableStateFlow(false)
    val isSelectTextSheetOpen = _isSelectTextSheetOpen.asStateFlow()

    private val _chatPlatformModels = MutableStateFlow<Map<String, ChatPlatformConfig>>(emptyMap())
    val chatPlatformModels = _chatPlatformModels.asStateFlow()

    private val _lastSelectedModel = MutableStateFlow<LastSelectedModel?>(null)
    val lastSelectedModel = _lastSelectedModel.asStateFlow()

    private val _currentReasoningMode = MutableStateFlow(ReasoningMode.AUTO)
    val currentReasoningMode = _currentReasoningMode.asStateFlow()

    private val _availableChatModels = MutableStateFlow(listOf<AvailableChatModel>())
    val availableChatModels = _availableChatModels.asStateFlow()

    private val memoryEnabledState = MutableStateFlow(false)

    // All platforms configured in app (including disabled)
    private val _platformsInApp = MutableStateFlow(listOf<PlatformV2>())
    val platformsInApp = _platformsInApp.asStateFlow()

    // Enabled platforms list in app
    private val _enabledPlatformsInApp = MutableStateFlow(listOf<PlatformV2>())
    val enabledPlatformsInApp = _enabledPlatformsInApp.asStateFlow()

    // User input used for the chat composer
    val question = TextFieldState()

    // Selected attachment drafts for current message
    private val _selectedAttachments = MutableStateFlow(listOf<ChatAttachmentDraft>())
    val selectedAttachments = _selectedAttachments.asStateFlow()

    private val _attachmentNotice = MutableStateFlow<String?>(null)
    val attachmentNotice = _attachmentNotice.asStateFlow()
    private val _pendingSelectedAttachmentBatches = MutableStateFlow(0)
    val pendingSelectedAttachmentBatches = _pendingSelectedAttachmentBatches.asStateFlow()
    private val _pendingMessageEditAttachmentBatches = MutableStateFlow(0)
    val pendingMessageEditAttachmentBatches = _pendingMessageEditAttachmentBatches.asStateFlow()
    private val attachmentBatchMutex = Mutex()
    private val pendingOwnedAttachmentPaths = Collections.synchronizedSet(mutableSetOf<String>())

    // Chat messages currently in the chat room
    private val _groupedMessages = MutableStateFlow(GroupedMessages())
    val groupedMessages = _groupedMessages.asStateFlow()

    // Each chat states for assistant chat messages
    // Index of the currently shown message's platform - default is 0 (first platform)
    private val _indexStates = MutableStateFlow(listOf<Int>())
    val indexStates = _indexStates.asStateFlow()

    // Loading states for each platform
    private val _loadingStates = MutableStateFlow(List<LoadingState>(initialEnabledPlatformsInChat.size) { LoadingState.Idle })
    val loadingStates = _loadingStates.asStateFlow()
    private val _isPersistingCompletion = MutableStateFlow(false)
    val isPersistingCompletion = _isPersistingCompletion.asStateFlow()
    private val loadingCompletionLock = Any()

    private val _toolProgressStates = MutableStateFlow<Map<String, List<ToolProgressState>>>(emptyMap())
    val toolProgressStates = _toolProgressStates.asStateFlow()

    // Used for text data to show in SelectText Bottom Sheet
    private val _selectedText = MutableStateFlow("")
    val selectedText = _selectedText.asStateFlow()

    // State for the message loading state (From the database)
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded = _isLoaded.asStateFlow()

    private var pendingQuestionText: String? = null
    private var pendingMemoryTurnActivityAt: Long? = null
    private var isInitialRequestInFlight = false
    private var initialRequestRecoveryComplete = !shouldRecoverInitialRequest(
        routeChatRoomId = routeChatRoomId,
        initialRequestId = initialRequestId,
        initialQuestion = initialQuestion,
        initialAttachmentPaths = initialAttachmentPaths
    )
    private var messageEditSessionVersion = 0L

    init {
        recoverInitialRequestIfNeeded()
        fetchChatRoom()
        viewModelScope.launch { fetchMessages() }
        fetchEnabledPlatformsInApp()
        observeStateChanges()
    }

    fun addMessage(userMessage: MessageV2) {
        _groupedMessages.update {
            it.copy(
                userMessages = it.userMessages + listOf(userMessage),
                assistantMessages = it.assistantMessages + listOf(
                    enabledPlatformsInChat.map { p -> MessageV2(chatId = chatRoomId, content = "", platformType = p) }
                )
            )
        }
        _indexStates.update { it + listOf(0) }
    }

    fun askQuestion() {
        val questionText = question.text.toString()
        if (_isPersistingCompletion.value) return
        if (_pendingSelectedAttachmentBatches.value > 0) {
            pendingQuestionText = questionText
            question.clearText()
            _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Loading } }
            return
        }
        val hasReadyAttachments = _selectedAttachments.value.any { it.status == ChatAttachmentDraft.Status.Ready }
        val hasPreparingAttachments = _selectedAttachments.value.any { it.status == ChatAttachmentDraft.Status.Preparing }
        if (questionText.isBlank() && !hasReadyAttachments && !hasPreparingAttachments) return
        if (_selectedAttachments.value.any { it.status == ChatAttachmentDraft.Status.Failed }) {
            _attachmentNotice.update { context.getString(R.string.remove_failed_attachments_before_sending) }
            return
        }

        if (hasPreparingAttachments) {
            pendingQuestionText = questionText
            question.clearText()
            _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Loading } }
            trySendPendingQuestionIfReady()
            return
        }

        sendQuestion(questionText, _selectedAttachments.value)
    }

    override fun onCleared() {
        val selectedDrafts = _selectedAttachments.value
        val editDrafts = _messageEditSession.value?.attachments.orEmpty()
        val pendingPaths = synchronized(pendingOwnedAttachmentPaths) {
            pendingOwnedAttachmentPaths.toList().also { pendingOwnedAttachmentPaths.clear() }
        }
        _selectedAttachments.value = emptyList()
        _messageEditSession.value = null
        selectedDrafts.forEach(::deleteDraftFiles)
        editDrafts.forEach(::deleteDraftFiles)
        pendingPaths.forEach { filePath -> java.io.File(filePath).delete() }
        AttachmentPayloadCache.clear()
        super.onCleared()
    }

    fun closeChatTitleDialog() = _isChatTitleDialogOpen.update { false }

    fun discardMessageEditDialog() {
        messageEditSessionVersion += 1
        _messageEditSession.value?.attachments?.forEach { attachment ->
            attachment.preparedFilePath?.let { AttachmentPayloadCache.remove(it) }
            deleteDraftFiles(attachment)
        }
        _messageEditSession.update { null }
    }

    fun finishMessageEditDialog() {
        messageEditSessionVersion += 1
        _messageEditSession.update { null }
    }

    fun closeSelectTextSheet() {
        _isSelectTextSheetOpen.update { false }
        _selectedText.update { "" }
    }

    fun openChatTitleDialog() = _isChatTitleDialogOpen.update { true }

    fun openUserMessageEditDialog(question: MessageV2) {
        messageEditSessionVersion += 1
        _messageEditSession.update {
            MessageEditSession(
                message = question,
                role = MessageEditRole.USER,
                attachments = question.attachments.map(ChatAttachmentDraft::fromAttachment)
            )
        }
    }

    fun openAssistantMessageEditDialog(turnIndex: Int, platformIndex: Int) {
        val assistantMessage = _groupedMessages.value.assistantMessages
            .getOrNull(turnIndex)
            ?.getOrNull(platformIndex)
            ?: return
        messageEditSessionVersion += 1
        _messageEditSession.update {
            MessageEditSession(
                message = assistantMessage,
                role = MessageEditRole.ASSISTANT,
                turnIndex = turnIndex,
                platformIndex = platformIndex,
                attachments = assistantMessage.attachments.map(ChatAttachmentDraft::fromAttachment)
            )
        }
    }

    fun openSelectTextSheet(content: String) {
        _selectedText.update { content }
        _isSelectTextSheetOpen.update { true }
    }

    fun generateDefaultChatTitle(): String? = chatRepository.generateDefaultChatTitle(_groupedMessages.value.userMessages)

    fun retryChat(turnIndex: Int, platformIndex: Int) {
        if (_isPersistingCompletion.value) return
        if (turnIndex !in _groupedMessages.value.assistantMessages.indices) return
        if (platformIndex >= enabledPlatformsInChat.size || platformIndex < 0) return
        val platform = _enabledPlatformsInApp.value.firstOrNull { it.uid == enabledPlatformsInChat[platformIndex] } ?: return
        val platformSelection = resolvePlatformSelection(platform)
        val revisionToAppendOnSuccess = _groupedMessages.value.assistantMessages
            .getOrNull(turnIndex)
            ?.getOrNull(platformIndex)
            ?.snapshotLatestAssistantRevision(currentTimeStamp)
        clearToolProgress(turnIndex, platformIndex)
        _loadingStates.update { it.toMutableList().apply { this[platformIndex] = LoadingState.Loading } }
        _groupedMessages.update {
            updateAssistantSlot(
                groupedMessages = it,
                turnIndex = turnIndex,
                platformIndex = platformIndex
            ) { currentMessage ->
                createRetryAssistantMessage(
                    currentMessage = currentMessage,
                    chatId = chatRoomId,
                    platformUid = platformSelection.platform.uid
                )
            }
        }

        viewModelScope.launch {
            val retryContext = groupedMessagesThroughTurn(_groupedMessages.value, turnIndex)
            val memoryPrompt = prepareMemoryPrompt(retryContext, platformSelection.platform)
            chatRepository.completeChat(
                retryContext.userMessages,
                retryContext.assistantMessages,
                platformSelection.platform,
                memoryPrompt,
                reasoningMode = platformSelection.reasoningMode
            ).handleStates(
                messageFlow = _groupedMessages,
                turnIndex = turnIndex,
                platformIdx = platformIndex,
                onLoadingComplete = {
                    markPlatformLoadingComplete(platformIndex)
                },
                revisionToAppendOnSuccess = revisionToAppendOnSuccess,
                onToolProgress = { progress -> updateToolProgress(turnIndex, platformIndex, progress) }
            )
        }
    }

    fun updateChatTitle(title: String) {
        // Should be only used for changing chat title after the chatroom is created.
        if (_chatRoom.value.id > 0) {
            _chatRoom.update { it.copy(title = title) }
            viewModelScope.launch {
                chatRepository.updateChatTitle(_chatRoom.value, title)
            }
        }
    }

    fun updateChatPlatformIndex(assistantIndex: Int, platformIndex: Int) {
        // Change the message shown in the screen to another platform
        if (assistantIndex >= _indexStates.value.size || assistantIndex < 0) return
        if (platformIndex >= enabledPlatformsInChat.size || platformIndex < 0) return

        _indexStates.update {
            val updatedIndex = it.toMutableList()
            updatedIndex[assistantIndex] = platformIndex
            updatedIndex
        }
    }

    fun addSelectedFile(filePath: String) {
        addSelectedFiles(listOf(filePath), 0)
    }

    fun addSelectedFiles(filePaths: List<String>, copyFailureCount: Int = 0) {
        enqueueSelectedFiles(filePaths, copyFailureCount)
    }

    private fun enqueueSelectedFiles(
        filePaths: List<String>,
        copyFailureCount: Int = 0,
        onAdmissionComplete: () -> Unit = {}
    ) {
        if (filePaths.isEmpty() && copyFailureCount <= 0) return
        _pendingSelectedAttachmentBatches.update { it + 1 }
        addDraftFiles(
            currentAttachments = { _selectedAttachments.value },
            mutateAttachments = { transform -> _selectedAttachments.update(transform) },
            filePaths = filePaths,
            copyFailureCount = copyFailureCount,
            onNotice = { notice -> _attachmentNotice.update { notice } },
            onAdmissionComplete = onAdmissionComplete,
            onFinished = {
                _pendingSelectedAttachmentBatches.update { (it - 1).coerceAtLeast(0) }
                trySendPendingQuestionIfReady()
            }
        )
    }

    fun removeSelectedFile(filePath: String) {
        removeDraftFile(
            currentAttachments = { _selectedAttachments.value },
            mutateAttachments = { transform -> _selectedAttachments.update(transform) },
            filePath = filePath
        )
        trySendPendingQuestionIfReady()
    }

    fun addMessageEditFile(filePath: String) {
        addMessageEditFiles(listOf(filePath), 0)
    }

    fun addMessageEditFiles(filePaths: List<String>, copyFailureCount: Int = 0) {
        if (filePaths.isEmpty() && copyFailureCount <= 0) return
        val targetSessionVersion = messageEditSessionVersion
        if (_messageEditSession.value == null) {
            filePaths.forEach { filePath ->
                if (isAppOwnedAttachmentPath(filePath)) java.io.File(filePath).delete()
            }
            return
        }
        _pendingMessageEditAttachmentBatches.update { it + 1 }
        addDraftFiles(
            currentAttachments = {
                _messageEditSession.value
                    ?.takeIf { messageEditSessionVersion == targetSessionVersion }
                    ?.attachments
                    .orEmpty()
            },
            mutateAttachments = { transform ->
                _messageEditSession.update { session ->
                    if (session == null || messageEditSessionVersion != targetSessionVersion) {
                        session
                    } else {
                        session.copy(attachments = transform(session.attachments))
                    }
                }
            },
            filePaths = filePaths,
            copyFailureCount = copyFailureCount,
            isTargetActive = {
                _messageEditSession.value != null && messageEditSessionVersion == targetSessionVersion
            },
            onNotice = { notice -> _attachmentNotice.update { notice } },
            onFinished = {
                _pendingMessageEditAttachmentBatches.update { (it - 1).coerceAtLeast(0) }
            }
        )
    }

    fun removeMessageEditFile(filePath: String) {
        removeDraftFile(
            currentAttachments = { _messageEditSession.value?.attachments.orEmpty() },
            mutateAttachments = { transform ->
                _messageEditSession.update { session ->
                    session?.copy(attachments = transform(session.attachments))
                }
            },
            filePath = filePath
        )
    }

    fun clearSelectedFiles() {
        _selectedAttachments.value.forEach { attachment ->
            attachment.preparedFilePath?.let { AttachmentPayloadCache.remove(it) }
        }
        _selectedAttachments.update { emptyList() }
    }

    fun consumeAttachmentNotice() {
        _attachmentNotice.update { null }
    }

    fun saveUserMessageEdit(
        editedMessage: MessageV2,
        attachments: List<ChatAttachmentDraft>
    ): Boolean {
        if (_isPersistingCompletion.value || _pendingMessageEditAttachmentBatches.value > 0) return false
        if (attachments.any { it.status != ChatAttachmentDraft.Status.Ready }) {
            _attachmentNotice.update { context.getString(R.string.wait_for_attachments_before_saving) }
            return false
        }

        val userMessages = _groupedMessages.value.userMessages
        val assistantMessages = _groupedMessages.value.assistantMessages

        // Find the index of the message being edited
        val messageIndex = userMessages.indexOfFirst { it.id == editedMessage.id }
        if (messageIndex == -1) return false

        // Update the message content
        val updatedUserMessages = userMessages.toMutableList()
        updatedUserMessages[messageIndex] = editedMessage.copy(
            attachments = attachments.mapNotNull { it.attachment },
            createdAt = currentTimeStamp
        )

        // Remove all messages after the edited question (both user and assistant messages)
        val remainingUserMessages = updatedUserMessages.take(messageIndex + 1)
        val remainingAssistantMessages = assistantMessages.take(messageIndex)

        // Update the grouped messages
        _groupedMessages.update {
            GroupedMessages(
                userMessages = remainingUserMessages,
                assistantMessages = remainingAssistantMessages
            )
        }

        // Add empty assistant message slots for the edited question
        _groupedMessages.update {
            it.copy(
                assistantMessages = it.assistantMessages + listOf(
                    enabledPlatformsInChat.map { p -> MessageV2(chatId = chatRoomId, content = "", platformType = p) }
                )
            )
        }

        // Update index states to match the new message count - trim the end part
        val removedMessagesCount = userMessages.size - remainingUserMessages.size
        _indexStates.update {
            val currentStates = it.toMutableList()
            repeat(removedMessagesCount) { currentStates.removeLastOrNull() }
            currentStates
        }

        // Start new conversation from the edited question
        completeChat()
        return true
    }

    fun saveAssistantMessageEdit(
        editedMessage: MessageV2,
        thoughts: String,
        attachments: List<ChatAttachmentDraft>
    ): Boolean {
        if (_isPersistingCompletion.value || _pendingMessageEditAttachmentBatches.value > 0) return false
        if (attachments.any { it.status != ChatAttachmentDraft.Status.Ready }) {
            _attachmentNotice.update { context.getString(R.string.wait_for_attachments_before_saving) }
            return false
        }

        val session = _messageEditSession.value ?: return false
        val turnIndex = session.turnIndex ?: return false
        val platformIndex = session.platformIndex ?: return false
        val currentMessage = _groupedMessages.value.assistantMessages
            .getOrNull(turnIndex)
            ?.getOrNull(platformIndex)
            ?: return false

        val updatedContent = editedMessage.content
        val updatedThoughts = thoughts
        val updatedAttachments = attachments.mapNotNull { it.attachment }

        val textChanged = currentMessage.content != updatedContent || currentMessage.thoughts != updatedThoughts
        val updatedRevisions = if (textChanged) {
            currentMessage.snapshotLatestAssistantRevision(currentTimeStamp)
                ?.let { listOf(it) + currentMessage.revisions }
                ?: currentMessage.revisions
        } else {
            currentMessage.revisions
        }

        _groupedMessages.update {
            updateAssistantSlot(
                groupedMessages = it,
                turnIndex = turnIndex,
                platformIndex = platformIndex
            ) { assistantMessage ->
                assistantMessage.copy(
                    content = updatedContent,
                    thoughts = updatedThoughts,
                    attachments = updatedAttachments,
                    revisions = updatedRevisions,
                    tokenUsage = if (textChanged) null else assistantMessage.tokenUsage,
                    createdAt = assistantMessage.createdAt
                ).resetActiveRevision()
            }
        }
        persistCurrentChatSnapshot()
        return true
    }

    fun showPreviousAssistantRevision(turnIndex: Int, platformIndex: Int) {
        updateAssistantRevisionSelection(turnIndex, platformIndex) { message ->
            when {
                message.revisions.isEmpty() -> message.activeRevisionIndex
                message.activeRevisionIndex == ACTIVE_REVISION_LATEST -> 0
                message.activeRevisionIndex < message.revisions.lastIndex -> message.activeRevisionIndex + 1
                else -> message.activeRevisionIndex
            }
        }
    }

    fun showNextAssistantRevision(turnIndex: Int, platformIndex: Int) {
        updateAssistantRevisionSelection(turnIndex, platformIndex) { message ->
            when {
                message.activeRevisionIndex == ACTIVE_REVISION_LATEST -> ACTIVE_REVISION_LATEST
                message.activeRevisionIndex == 0 -> ACTIVE_REVISION_LATEST
                else -> message.activeRevisionIndex - 1
            }
        }
    }

    fun exportChat(): Pair<String, String> {
        // Build the chat history in Markdown format
        val chatHistoryMarkdown = buildString {
            appendLine("# ${context.getString(R.string.chat_export_title)}: \"${chatRoom.value.title}\"")
            appendLine()
            appendLine("**${context.getString(R.string.exported_on)}:** ${formatCurrentDateTime()}")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## ${context.getString(R.string.chat_history)}")
            appendLine()
            _groupedMessages.value.userMessages.forEachIndexed { i, message ->
                appendLine("**${context.getString(R.string.user_label)}:**")
                appendLine(message.content)
                appendLine()

                _groupedMessages.value.assistantMessages[i].forEach { message ->
                    val platformName = message.platformType
                        ?.let { _platformsInApp.value.getPlatformName(it) }
                        ?: context.getString(R.string.unknown)
                    appendLine("**${context.getString(R.string.assistant_label)} ($platformName):**")
                    appendLine(message.effectiveContent())
                    appendLine()
                }
            }
        }

        // Save the Markdown file
        val fileName = "export_${chatRoom.value.title}_${System.currentTimeMillis()}.md"
        return Pair(fileName, chatHistoryMarkdown)
    }

    private fun completeChat() {
        // Update all the platform loading states to Loading
        _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Loading } }
        val turnIndex = _groupedMessages.value.assistantMessages.lastIndex
        clearToolProgressForTurn(turnIndex)
        val groupedMessages = _groupedMessages.value
        val memoryPlatform = preferredMemoryPlatform()

        viewModelScope.launch {
            val memoryPrompt = prepareMemoryPrompt(groupedMessages, memoryPlatform)

            // Send chat completion requests
            enabledPlatformsInChat.forEachIndexed { idx, platformUid ->
                val platform = _enabledPlatformsInApp.value.firstOrNull { it.uid == platformUid } ?: return@forEachIndexed
                val platformSelection = resolvePlatformSelection(platform)
                launch {
                    val latestGroupedMessages = _groupedMessages.value
                    chatRepository.completeChat(
                        latestGroupedMessages.userMessages,
                        latestGroupedMessages.assistantMessages,
                        platformSelection.platform,
                        memoryPrompt,
                        reasoningMode = platformSelection.reasoningMode
                    ).handleStates(
                        messageFlow = _groupedMessages,
                        turnIndex = turnIndex,
                        platformIdx = idx,
                        onLoadingComplete = {
                            markPlatformLoadingComplete(idx)
                        },
                        onToolProgress = { progress -> updateToolProgress(turnIndex, idx, progress) }
                    )
                }
            }
        }
    }

    private fun updateToolProgress(
        turnIndex: Int,
        platformIndex: Int,
        progress: ApiState
    ) {
        val key = toolProgressKey(turnIndex, platformIndex)
        _toolProgressStates.update { current ->
            current + (key to current[key].orEmpty().appendToolProgress(progress))
        }
    }

    private fun clearToolProgress(turnIndex: Int, platformIndex: Int) {
        val key = toolProgressKey(turnIndex, platformIndex)
        _toolProgressStates.update { current -> current - key }
    }

    private fun clearToolProgressForTurn(turnIndex: Int) {
        val prefix = "$turnIndex:"
        _toolProgressStates.update { current ->
            current.filterKeys { key -> !key.startsWith(prefix) }
        }
    }

    fun updateChatPlatformModelAndRemember(platformUid: String, model: String) {
        val sanitizedModel = model.trim()
        if (sanitizedModel.isBlank()) return
        val selectedModel = _availableChatModels.value.firstOrNull { availableModel ->
            availableModel.platformUid == platformUid && availableModel.modelId == sanitizedModel
        } ?: return
        val nextReasoningMode = reasoningModeForSelectedModel(selectedModel, _currentReasoningMode.value)
        val nextConfig = ChatPlatformConfig(
            platformUid = selectedModel.platformUid,
            model = selectedModel.modelId,
            reasoningMode = nextReasoningMode
        )

        _chatRoom.update { it.copy(enabledPlatform = listOf(selectedModel.platformUid)) }
        _chatPlatformModels.update { mapOf(selectedModel.platformUid to nextConfig) }
        _lastSelectedModel.update {
            LastSelectedModel(
                platformUid = selectedModel.platformUid,
                model = selectedModel.modelId,
                reasoningMode = nextReasoningMode
            )
        }
        _currentReasoningMode.update { nextReasoningMode }
        _loadingStates.update { List(1) { LoadingState.Idle } }

        viewModelScope.launch {
            settingRepository.updateLastSelectedModel(selectedModel.platformUid, selectedModel.modelId, nextReasoningMode)
            if (_chatRoom.value.id > 0) {
                chatRepository.saveChatPlatformModels(_chatRoom.value.id, _chatPlatformModels.value)
            }
        }
    }

    fun updateChatReasoningModeAndRemember(reasoningMode: ReasoningMode) {
        val selectedModel = currentSelectedAvailableModel() ?: return
        if (selectedModel.platform.reasoningModesForModel(selectedModel.modelId).isEmpty()) return

        val nextReasoningMode = selectedModel.platform.coerceReasoningModeForModel(reasoningMode, selectedModel.modelId)
        val nextConfig = ChatPlatformConfig(
            platformUid = selectedModel.platformUid,
            model = selectedModel.modelId,
            reasoningMode = nextReasoningMode
        )

        _chatPlatformModels.update { mapOf(selectedModel.platformUid to nextConfig) }
        _lastSelectedModel.update {
            LastSelectedModel(
                platformUid = selectedModel.platformUid,
                model = selectedModel.modelId,
                reasoningMode = nextReasoningMode
            )
        }
        _currentReasoningMode.update { nextReasoningMode }

        viewModelScope.launch {
            settingRepository.updateLastSelectedModel(selectedModel.platformUid, selectedModel.modelId, nextReasoningMode)
            if (_chatRoom.value.id > 0) {
                chatRepository.saveChatPlatformModels(_chatRoom.value.id, _chatPlatformModels.value)
            }
        }
    }

    private fun currentSelectedAvailableModel(): AvailableChatModel? {
        val selectedModel = _lastSelectedModel.value
        return selectedModel?.let { lastSelected ->
            _availableChatModels.value.firstOrNull { availableModel ->
                availableModel.platformUid == lastSelected.platformUid &&
                    availableModel.modelId == lastSelected.model
            }
        } ?: _availableChatModels.value.firstOrNull { availableModel ->
            availableModel.platformUid in enabledPlatformsInChat &&
                _chatPlatformModels.value[availableModel.platformUid]?.model == availableModel.modelId
        }
    }

    private fun reasoningModeForSelectedModel(
        selectedModel: AvailableChatModel,
        requestedMode: ReasoningMode
    ): ReasoningMode = selectedModel.platform.coerceReasoningModeForModel(requestedMode, selectedModel.modelId)

    private suspend fun prepareMemoryPrompt(
        groupedMessages: GroupedMessages,
        memoryPlatform: PlatformV2?
    ): String? = withContext(Dispatchers.IO) {
        prepareMemoryPromptWhenEnabled(refreshMemoryEnabled()) {
            memoryRepository.prepareMemoryContext(
                chatRoom = _chatRoom.value,
                userMessages = groupedMessages.userMessages,
                assistantMessages = groupedMessages.assistantMessages,
                memoryPlatform = memoryPlatform
            ).prompt
        }
    }

    private fun recordUserActivityIfEnabled(chatId: Int, activityAt: Long) {
        if (chatId <= 0) return
        applicationScope.launch(Dispatchers.IO) {
            if (settingRepository.fetchMemoryEnabled()) {
                memoryRepository.recordUserActivity(chatId, activityAt)
            }
        }
    }

    private suspend fun refreshMemoryEnabled(): Boolean {
        val enabled = settingRepository.fetchMemoryEnabled()
        memoryEnabledState.update { enabled }
        return enabled
    }

    private fun addDraftFiles(
        currentAttachments: () -> List<ChatAttachmentDraft>,
        mutateAttachments: ((List<ChatAttachmentDraft>) -> List<ChatAttachmentDraft>) -> Unit,
        filePaths: List<String>,
        copyFailureCount: Int = 0,
        isTargetActive: () -> Boolean = { true },
        onNotice: (String?) -> Unit = {},
        onAdmissionComplete: () -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        if (filePaths.isEmpty() && copyFailureCount <= 0) return

        val existingPathsAtHandoff = currentAttachments().mapTo(mutableSetOf()) { it.sourceFilePath }
        val handedOffOwnedPaths = filePaths
            .asSequence()
            .filterNot { it in existingPathsAtHandoff }
            .filter(::isAppOwnedAttachmentPath)
            .distinct()
            .toList()
        pendingOwnedAttachmentPaths.addAll(handedOffOwnedPaths)

        viewModelScope.launch {
            try {
                attachmentBatchMutex.withLock {
                    val current = currentAttachments()
                    val existingCandidates = withContext(Dispatchers.IO) {
                        current.map { attachment -> attachment.sourceFilePath.toAdmissionCandidate() }
                    }
                    val candidates = withContext(Dispatchers.IO) {
                        filePaths.map { filePath -> filePath.toAdmissionCandidate() }
                    }
                    val admission = admitAttachmentBatch(
                        existingIdentities = existingCandidates.mapTo(mutableSetOf()) { it.identity },
                        existingBytes = existingCandidates.sumOf { it.sizeBytes.coerceAtLeast(0L) },
                        candidates = candidates,
                        maxBytes = FileUtils.MAX_UPLOAD_SIZE_BYTES
                    )
                    val existingPaths = current.mapTo(mutableSetOf()) { it.sourceFilePath }
                    attachmentPathsToDiscard(existingPaths, candidates, admission).forEach { filePath ->
                        if (isAppOwnedAttachmentPath(filePath)) java.io.File(filePath).delete()
                        pendingOwnedAttachmentPaths.remove(filePath)
                    }

                    if (admission.accepted.isNotEmpty()) {
                        val acceptedDrafts = admission.accepted.map { candidate ->
                            ChatAttachmentDraft(
                                sourceFilePath = candidate.path,
                                cleanupOnDiscard = isAppOwnedAttachmentPath(candidate.path)
                            )
                        }
                        if (isTargetActive()) {
                            mutateAttachments { latest ->
                                latest + acceptedDrafts.filterNot { accepted ->
                                    latest.any { attachment -> attachment.sourceFilePath == accepted.sourceFilePath }
                                }
                            }
                        }
                        admission.accepted.forEach { candidate ->
                            if (
                                isTargetActive() &&
                                currentAttachments().any { attachment -> attachment.sourceFilePath == candidate.path }
                            ) {
                                pendingOwnedAttachmentPaths.remove(candidate.path)
                                preprocessDraftAttachment(
                                    currentAttachments = currentAttachments,
                                    mutateAttachments = mutateAttachments,
                                    filePath = candidate.path,
                                    isTargetActive = isTargetActive,
                                    onNotice = onNotice
                                )
                            }
                        }
                    }

                    val totalRejectedCount = admission.totalRejectedCount(copyFailureCount)
                    if (totalRejectedCount > 0) {
                        onNotice(
                            context.getString(R.string.attachment_batch_rejected, totalRejectedCount)
                        )
                    }

                    if (admission.accepted.isEmpty() && totalRejectedCount > 0) {
                        trySendPendingQuestionIfReady()
                    }
                    onAdmissionComplete()
                }
            } finally {
                handedOffOwnedPaths.forEach { filePath ->
                    if (pendingOwnedAttachmentPaths.remove(filePath)) {
                        java.io.File(filePath).delete()
                    }
                }
                onFinished()
            }
        }
    }

    private fun String.toAdmissionCandidate(): AttachmentAdmissionCandidate {
        val mimeType = FileUtils.getMimeType(context, this)
        return AttachmentAdmissionCandidate(
            path = this,
            identity = attachmentIdentity(this),
            sizeBytes = FileUtils.getFileSize(context, this),
            isSupported = FileUtils.isSupportedUploadMimeType(mimeType)
        )
    }

    private fun isAppOwnedAttachmentPath(filePath: String): Boolean {
        val canonicalPath = runCatching { java.io.File(filePath).canonicalPath }.getOrNull() ?: return false
        val roots = buildList {
            add(java.io.File(context.filesDir, "attachments"))
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { picturesDir ->
                add(java.io.File(picturesDir, "attachments"))
            }
        }
        return roots.any { root ->
            val canonicalRoot = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            canonicalPath == canonicalRoot || canonicalPath.startsWith(canonicalRoot + java.io.File.separator)
        }
    }

    private fun removeDraftFile(
        currentAttachments: () -> List<ChatAttachmentDraft>,
        mutateAttachments: ((List<ChatAttachmentDraft>) -> List<ChatAttachmentDraft>) -> Unit,
        filePath: String
    ) {
        val removedAttachment = currentAttachments().firstOrNull { it.sourceFilePath == filePath }
        removedAttachment?.preparedFilePath?.let { AttachmentPayloadCache.remove(it) }
        removedAttachment?.let(::deleteDraftFiles)
        mutateAttachments { attachments -> attachments.filter { it.sourceFilePath != filePath } }
    }

    private fun preprocessDraftAttachment(
        currentAttachments: () -> List<ChatAttachmentDraft>,
        mutateAttachments: ((List<ChatAttachmentDraft>) -> List<ChatAttachmentDraft>) -> Unit,
        filePath: String,
        isTargetActive: () -> Boolean,
        onNotice: (String?) -> Unit = {}
    ) {
        viewModelScope.launch {
            val preparationResult = withContext(Dispatchers.IO) {
                attachmentUploadCoordinator.prepareLocalAttachment(context, filePath)
            }

            if (!isTargetActive() || currentAttachments().none { it.sourceFilePath == filePath }) {
                if (preparationResult != null && preparationResult.preparedFilePath != filePath) {
                    java.io.File(preparationResult.preparedFilePath).delete()
                }
                return@launch
            }

            mutateAttachments { attachments ->
                attachments.map { attachment ->
                    if (attachment.sourceFilePath != filePath) {
                        attachment
                    } else if (preparationResult == null) {
                        attachment.copy(
                            status = ChatAttachmentDraft.Status.Failed,
                            errorMessage = context.getString(R.string.failed_to_prepare_attachment)
                        )
                    } else {
                        attachment.copy(
                            attachment = preparationResult,
                            preparedFilePath = preparationResult.preparedFilePath,
                            mimeType = preparationResult.mimeType,
                            status = ChatAttachmentDraft.Status.Ready,
                            cleanupPreparedOnDiscard = preparationResult.preparedFilePath != filePath,
                            notice = if (preparationResult.wasResized) {
                                context.getString(R.string.large_images_resized_before_upload)
                            } else {
                                null
                            },
                            errorMessage = null
                        )
                    }
                }
            }

            if (preparationResult?.wasResized == true) {
                onNotice(context.getString(R.string.large_images_resized_before_upload))
            } else if (preparationResult == null) {
                onNotice(context.getString(R.string.failed_to_prepare_attachment))
            }

            trySendPendingQuestionIfReady()
        }
    }

    private fun trySendPendingQuestionIfReady() {
        val queuedQuestion = pendingQuestionText ?: return
        if (_pendingSelectedAttachmentBatches.value > 0) return
        val attachments = _selectedAttachments.value

        if (attachments.any { it.status == ChatAttachmentDraft.Status.Failed }) {
            restoreQueuedQuestion(queuedQuestion)
            pendingQuestionText = null
            _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Idle } }
            return
        }

        if (attachments.any { it.status == ChatAttachmentDraft.Status.Preparing }) {
            return
        }

        if (queuedQuestion.isBlank() && attachments.none { it.status == ChatAttachmentDraft.Status.Ready }) {
            pendingQuestionText = null
            _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Idle } }
            return
        }

        pendingQuestionText = null
        sendQuestion(queuedQuestion, attachments)
    }

    private fun sendQuestion(questionText: String, attachments: List<ChatAttachmentDraft>) {
        val activityAt = currentTimeStamp
        MessageV2(
            chatId = chatRoomId,
            content = questionText,
            attachments = attachments.mapNotNull { it.attachment },
            linkedMessageId = initialRequestId.takeIf { isInitialRequestInFlight } ?: 0,
            platformType = null,
            createdAt = activityAt
        ).let { addMessage(it) }
        pendingMemoryTurnActivityAt = activityAt
        recordUserActivityIfEnabled(_chatRoom.value.id, activityAt)
        question.clearText()
        clearSelectedFiles()
        if (isInitialRequestInFlight) {
            _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Loading } }
            viewModelScope.launch {
                try {
                    persistInitialRequestBeforeCompletion()
                    completeChat()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    _attachmentNotice.update { error.message ?: error::class.simpleName.orEmpty() }
                    _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Idle } }
                }
            }
        } else {
            completeChat()
        }
    }

    private fun restoreQueuedQuestion(questionText: String) {
        if (questionText.isBlank()) return
        question.setTextAndPlaceCursorAtEnd(questionText)
    }

    private fun deleteDraftFiles(attachment: ChatAttachmentDraft) {
        attachment.ownedPathsToDeleteOnDiscard().forEach { filePath ->
            java.io.File(filePath).delete()
        }
    }

    /**
     * Assistant revisions are stored newest-first: revisions[0] is the newest
     * saved answer, and ACTIVE_REVISION_LATEST points at the live content.
     */
    private fun updateAssistantRevisionSelection(
        turnIndex: Int,
        platformIndex: Int,
        nextIndex: (MessageV2) -> Int
    ) {
        _groupedMessages.update {
            updateAssistantSlot(
                groupedMessages = it,
                turnIndex = turnIndex,
                platformIndex = platformIndex
            ) { message ->
                message.selectRevision(nextIndex(message))
            }
        }
        persistCurrentChatSnapshot()
    }

    private fun formatCurrentDateTime(): String {
        val currentDate = java.util.Date()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return format.format(currentDate)
    }

    private suspend fun fetchMessages() {
        // If the room isn't new
        if (chatRoomId != 0) {
            _groupedMessages.update { fetchGroupedMessages(chatRoomId) }
            if (_groupedMessages.value.assistantMessages.size != _indexStates.value.size) {
                _indexStates.update { List(_groupedMessages.value.assistantMessages.size) { 0 } }
            }
            _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Idle } }
            _isLoaded.update { true } // Finish fetching
            return
        }

        // When message id should sync after saving chats
        if (_chatRoom.value.id != 0) {
            _groupedMessages.update { fetchGroupedMessages(_chatRoom.value.id) }
            return
        }
    }

    private suspend fun fetchGroupedMessages(chatId: Int): GroupedMessages {
        val messages = chatRepository.fetchMessagesV2(chatId).sortedBy { it.createdAt }

        val userMessages = mutableListOf<MessageV2>()
        val assistantMessages = mutableListOf<MutableList<MessageV2>>()

        messages.forEach { message ->
            if (message.platformType == null) {
                userMessages.add(message)
                assistantMessages.add(mutableListOf())
            } else {
                assistantMessages.last().add(message)
            }
        }

        val normalizedAssistantMessages = assistantMessages.map { assistantMessage ->
            normalizeAssistantRow(
                assistantMessages = assistantMessage,
                enabledPlatformsInChat = enabledPlatformsInChat,
                chatId = chatId
            )
        }

        return GroupedMessages(userMessages, normalizedAssistantMessages)
    }

    private fun recoverInitialRequestIfNeeded() {
        if (initialRequestRecoveryComplete) return
        viewModelScope.launch {
            try {
                val recovered = withContext(Dispatchers.IO) {
                    val recoveredChatId = chatRepository.findChatIdByInitialRequestId(initialRequestId)
                        ?.takeIf { it > 0 }
                        ?: return@withContext null
                    val recoveredChatRoom = chatRepository.fetchChatListV2()
                        .firstOrNull { it.id == recoveredChatId }
                        ?: return@withContext null
                    recoveredChatRoom to fetchGroupedMessages(recoveredChatId)
                }
                if (recovered != null) {
                    val (recoveredChatRoom, recoveredMessages) = recovered
                    launchState.recordPersistedChatRoomId(recoveredChatRoom.id)
                    launchState.recordInitialRequestPersisted()
                    _chatRoom.value = recoveredChatRoom
                    _groupedMessages.value = recoveredMessages
                    _indexStates.value = List(recoveredMessages.assistantMessages.size) { 0 }
                    _loadingStates.value = List(enabledPlatformsInChat.size) { LoadingState.Idle }
                    _isLoaded.value = true
                }
            } finally {
                initialRequestRecoveryComplete = true
                maybeSendInitialQuestion()
            }
        }
    }

    private suspend fun persistInitialRequestBeforeCompletion() {
        val snapshot = _groupedMessages.value
        val savedChatRoom = withContext(Dispatchers.IO) {
            chatRepository.saveChat(
                chatRoom = _chatRoom.value,
                messages = persistableMessages(snapshot),
                chatPlatformModels = _chatPlatformModels.value
            )
        }
        launchState.recordPersistedChatRoomId(savedChatRoom.id)
        launchState.recordInitialRequestPersisted()
        _chatRoom.value = savedChatRoom
        isInitialRequestInFlight = false
        _groupedMessages.value = withContext(Dispatchers.IO) {
            fetchGroupedMessages(savedChatRoom.id)
        }
    }

    private fun fetchChatRoom() {
        viewModelScope.launch {
            _chatRoom.update {
                if (chatRoomId == 0) {
                    ChatRoomV2(id = 0, title = context.getString(R.string.untitled_chat), enabledPlatform = enabledPlatformsInChat)
                } else {
                    chatRepository.fetchChatListV2().first { it.id == chatRoomId }
                }
            }
            _chatPlatformModels.value.keys.firstOrNull()?.let { selectedPlatformUid ->
                _chatRoom.update { it.copy(enabledPlatform = listOf(selectedPlatformUid)) }
            }
            maybeSendInitialQuestion()
        }
    }

    private fun fetchEnabledPlatformsInApp() {
        viewModelScope.launch {
            val allPlatforms = settingRepository.fetchPlatformV2s()
            val availableModels = settingRepository.fetchEnabledChatModels()
            _platformsInApp.update { allPlatforms }
            _enabledPlatformsInApp.update { allPlatforms.filter { it.enabled } }
            _availableChatModels.update { availableModels }
            initializeChatPlatformModels(availableModels)
            maybeSendInitialQuestion()
        }
    }

    private fun maybeSendInitialQuestion() {
        if (!initialRequestRecoveryComplete) return
        if (launchState.initialQuestionConsumed) return
        if (isInitialRequestInFlight) return
        if (chatRoomId != 0) return
        if (initialQuestion.isBlank() && initialAttachmentPaths.isEmpty()) return
        if (_chatRoom.value.id == -1) return

        val enabledPlatformUids = _enabledPlatformsInApp.value.map { it.uid }.toSet()
        if ((enabledPlatformsInChat.toSet() - enabledPlatformUids).isNotEmpty()) return

        isInitialRequestInFlight = true
        val sendInitialQuestion = {
            if (initialQuestion.isNotBlank()) {
                question.setTextAndPlaceCursorAtEnd(initialQuestion)
            }
            askQuestion()
        }
        if (!launchState.initialAttachmentsConsumed) {
            if (initialAttachmentPaths.isNotEmpty()) {
                enqueueSelectedFiles(
                    filePaths = initialAttachmentPaths,
                    onAdmissionComplete = sendInitialQuestion
                )
                return
            }
        }
        sendInitialQuestion()
    }

    private suspend fun initializeChatPlatformModels(availableModels: List<AvailableChatModel>) {
        val persistedModels = if (chatRoomId != 0) {
            chatRepository.fetchChatPlatformModels(chatRoomId)
        } else {
            emptyMap()
        }
        val selectedModel = selectPersistedModel(availableModels, persistedModels)
            ?: selectInitialModel(availableModels)

        memoryEnabledState.update { settingRepository.fetchMemoryEnabled() }
        if (selectedModel == null) {
            _lastSelectedModel.update { null }
            _chatPlatformModels.update { emptyMap() }
            _currentReasoningMode.update { ReasoningMode.AUTO }
            _loadingStates.update { emptyList() }
            return
        }

        val selectedReasoningMode = selectedModel.platform.coerceReasoningModeForModel(
            persistedModels[selectedModel.platformUid]
                ?.takeIf { it.model == selectedModel.modelId }
                ?.reasoningMode
                ?: selectInitialReasoningMode(selectedModel),
            selectedModel.modelId
        )
        val selectedConfig = ChatPlatformConfig(
            platformUid = selectedModel.platformUid,
            model = selectedModel.modelId,
            reasoningMode = selectedReasoningMode
        )
        _lastSelectedModel.update {
            LastSelectedModel(
                platformUid = selectedModel.platformUid,
                model = selectedModel.modelId,
                reasoningMode = selectedReasoningMode
            )
        }
        _currentReasoningMode.update { selectedReasoningMode }
        val mergedModels = mapOf(selectedModel.platformUid to selectedConfig)

        _chatRoom.update { chatRoom ->
            if (chatRoom.id == -1) {
                chatRoom
            } else {
                chatRoom.copy(enabledPlatform = listOf(selectedModel.platformUid))
            }
        }
        _loadingStates.update { List(1) { LoadingState.Idle } }
        _chatPlatformModels.update { mergedModels }

        if (chatRoomId != 0 && mergedModels != persistedModels) {
            chatRepository.saveChatPlatformModels(chatRoomId, mergedModels)
        }
        if (chatRoomId != 0) {
            _groupedMessages.update { fetchGroupedMessages(chatRoomId) }
            _indexStates.update { List(_groupedMessages.value.assistantMessages.size) { 0 } }
        }
    }

    private suspend fun selectInitialModel(availableModels: List<AvailableChatModel>): AvailableChatModel? {
        val routeModel = if (chatRoomId == 0 && initialModel.isNotBlank()) {
            availableModels.firstOrNull { model ->
                model.platformUid in initialEnabledPlatformsInChat && model.modelId == initialModel.trim()
            }
        } else {
            null
        }

        return routeModel ?: settingRepository.resolveDefaultChatModel()
    }

    private suspend fun selectInitialReasoningMode(selectedModel: AvailableChatModel): ReasoningMode {
        val lastSelectedModel = settingRepository.fetchLastSelectedModel()
        return if (
            lastSelectedModel?.platformUid == selectedModel.platformUid &&
            lastSelectedModel.model == selectedModel.modelId
        ) {
            lastSelectedModel.reasoningMode
        } else {
            selectedModel.platform.defaultReasoningMode()
        }
    }

    private fun selectPersistedModel(
        availableModels: List<AvailableChatModel>,
        persistedModels: Map<String, ChatPlatformConfig>
    ): AvailableChatModel? {
        if (chatRoomId == 0) return null

        return persistedModels.firstNotNullOfOrNull { (platformUid, config) ->
            availableModels.firstOrNull { model -> model.platformUid == platformUid && model.modelId == config.model }
        }
    }

    private fun markPlatformLoadingComplete(platformIndex: Int) {
        synchronized(loadingCompletionLock) {
            val current = _loadingStates.value
            if (platformIndex !in current.indices) return
            val next = current.toMutableList().apply { this[platformIndex] = LoadingState.Idle }
            val completionNeedsPersistence = current[platformIndex] == LoadingState.Loading &&
                next.all { it == LoadingState.Idle } &&
                _chatRoom.value.id != -1 &&
                _groupedMessages.value.userMessages.isNotEmpty() &&
                _groupedMessages.value.userMessages.size == _groupedMessages.value.assistantMessages.size
            if (completionNeedsPersistence) {
                _isPersistingCompletion.value = true
            }
            _loadingStates.value = next
        }
    }

    private fun observeStateChanges() {
        viewModelScope.launch {
            _loadingStates.collect { states ->
                if (_chatRoom.value.id != -1 &&
                    states.all { it == LoadingState.Idle } &&
                    (_groupedMessages.value.userMessages.isNotEmpty() && _groupedMessages.value.assistantMessages.isNotEmpty()) &&
                    (_groupedMessages.value.userMessages.size == _groupedMessages.value.assistantMessages.size)
                ) {
                    try {
                        val chatRoom = _chatRoom.value
                        val groupedMessages = _groupedMessages.value
                        val chatPlatformModels = _chatPlatformModels.value
                        val activityAt = pendingMemoryTurnActivityAt
                        val preferredMemoryPlatformUid = preferredMemoryPlatform()?.uid

                        val (savedChatRoom, persistedGroupedMessages) = withContext(Dispatchers.IO) {
                            val saved = chatRepository.saveChat(
                                chatRoom = chatRoom,
                                messages = persistableMessages(groupedMessages),
                                chatPlatformModels = chatPlatformModels
                            )
                            saved to fetchGroupedMessages(saved.id)
                        }
                        launchState.recordPersistedChatRoomId(savedChatRoom.id)
                        _chatRoom.update { currentChatRoom ->
                            if (currentChatRoom.id == chatRoom.id && chatRoom.id == 0) {
                                savedChatRoom
                            } else {
                                currentChatRoom
                            }
                        }
                        if (activityAt != null) {
                            pendingMemoryTurnActivityAt = null
                            val userMessage = persistedGroupedMessages.userMessages.lastOrNull()
                            val assistantMessages = persistedGroupedMessages.assistantMessages.lastOrNull().orEmpty()
                            if (refreshMemoryEnabled() && userMessage != null) {
                                applicationScope.launch(Dispatchers.IO) {
                                    memoryRepository.recordUserActivity(savedChatRoom.id, activityAt)
                                    memoryRepository.recordCompletedTurn(
                                        MemoryCompletedTurnInput(
                                            chatRoom = savedChatRoom,
                                            userMessage = userMessage,
                                            assistantMessages = assistantMessages,
                                            preferredPlatformUid = preferredMemoryPlatformUid,
                                            stablePlatformOrder = savedChatRoom.enabledPlatform,
                                            completedAt = currentTimeStamp
                                        )
                                    )
                                }
                            }
                        }

                        // Sync message ids
                        _groupedMessages.value = persistedGroupedMessages
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        _attachmentNotice.update { error.message ?: error::class.simpleName.orEmpty() }
                    } finally {
                        _isPersistingCompletion.value = false
                    }
                }
            }
        }
    }

    private fun resolvePlatformSelection(platform: PlatformV2): ResolvedChatPlatform {
        val chatConfig = _chatPlatformModels.value[platform.uid]
        val chatModel = chatConfig?.model?.trim().orEmpty()
        val platformWithChatModel = if (chatModel.isBlank() || chatModel == platform.model) {
            platform
        } else {
            platform.copy(model = chatModel)
        }
        val reasoningMode = platformWithChatModel.coerceReasoningModeForModel(
            chatConfig?.reasoningMode ?: platform.defaultReasoningMode(),
            platformWithChatModel.model
        )

        return ResolvedChatPlatform(
            platform = platformWithChatModel,
            reasoningMode = reasoningMode
        )
    }

    private fun preferredMemoryPlatform(): PlatformV2? {
        val chatPlatform = enabledPlatformsInChat
            .firstOrNull()
            ?.let { platformUid -> _enabledPlatformsInApp.value.firstOrNull { it.uid == platformUid } }
            ?: return null

        return resolvePlatformSelection(chatPlatform).platform
    }

    private fun persistCurrentChatSnapshot() {
        viewModelScope.launch {
            val chatRoom = _chatRoom.value
            val groupedMessages = _groupedMessages.value
            if (chatRoom.id <= 0) return@launch
            if (groupedMessages.userMessages.isEmpty()) return@launch
            if (groupedMessages.userMessages.size != groupedMessages.assistantMessages.size) return@launch

            withContext(Dispatchers.IO) {
                chatRepository.saveChat(
                    chatRoom = chatRoom,
                    messages = persistableMessages(groupedMessages),
                    chatPlatformModels = _chatPlatformModels.value
                )
            }
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"

        fun toolProgressKey(turnIndex: Int, platformIndex: Int): String = "$turnIndex:$platformIndex"
    }
}

internal fun shouldRecoverInitialRequest(
    routeChatRoomId: Int,
    initialRequestId: Int,
    initialQuestion: String,
    initialAttachmentPaths: List<String>
): Boolean = routeChatRoomId == 0 &&
    initialRequestId < 0 &&
    (initialQuestion.isNotBlank() || initialAttachmentPaths.isNotEmpty())

internal suspend fun prepareMemoryPromptWhenEnabled(
    memoryEnabled: Boolean,
    prepare: suspend () -> String?
): String? = if (memoryEnabled) runCatching { prepare() }.getOrNull() else null

internal fun List<ChatViewModel.ToolProgressState>.appendToolProgress(progress: ApiState): List<ChatViewModel.ToolProgressState> {
    val progressState = when (progress) {
        is ApiState.ToolStarted -> ChatViewModel.ToolProgressState(
            toolName = progress.toolName,
            label = progress.label,
            status = ChatViewModel.ToolProgressStatus.Running
        )
        is ApiState.ToolFinished -> ChatViewModel.ToolProgressState(
            toolName = progress.toolName,
            label = progress.label,
            status = ChatViewModel.ToolProgressStatus.Finished
        )
        is ApiState.ToolFailed -> ChatViewModel.ToolProgressState(
            toolName = progress.toolName,
            label = lastRunningLabelFor(progress.toolName) ?: progress.toolName,
            status = ChatViewModel.ToolProgressStatus.Failed,
            message = progress.message,
            errorCode = progress.errorCode
        )
        else -> return this
    }

    if (progressState.status == ChatViewModel.ToolProgressStatus.Running) {
        return this + progressState
    }

    val runningIndex = indexOfLast { state ->
        state.status == ChatViewModel.ToolProgressStatus.Running &&
            state.toolName == progressState.toolName &&
            state.label == progressState.label
    }
    if (runningIndex < 0) return this + progressState

    return toMutableList().apply {
        this[runningIndex] = progressState
    }
}

private fun List<ChatViewModel.ToolProgressState>.lastRunningLabelFor(toolName: String): String? =
    lastOrNull { state ->
        state.status == ChatViewModel.ToolProgressStatus.Running &&
            state.toolName == toolName
    }?.label

internal fun groupedMessagesThroughTurn(
    groupedMessages: ChatViewModel.GroupedMessages,
    turnIndex: Int
): ChatViewModel.GroupedMessages = groupedMessages.copy(
    userMessages = groupedMessages.userMessages.take(turnIndex + 1),
    assistantMessages = groupedMessages.assistantMessages.take(turnIndex + 1)
)

internal fun persistableMessages(groupedMessages: ChatViewModel.GroupedMessages): List<MessageV2> {
    val merged = groupedMessages.userMessages + groupedMessages.assistantMessages.flatten()
    return merged
        .filter {
            it.effectiveContent().isNotBlank() ||
                it.effectiveThoughts().isNotBlank() ||
                it.attachments.isNotEmpty()
        }
        .sortedBy { it.createdAt }
}

internal fun createEmptyAssistantMessage(chatId: Int, platformUid: String): MessageV2 = MessageV2(
    chatId = chatId,
    content = "",
    platformType = platformUid
)

internal fun createRetryAssistantMessage(
    currentMessage: MessageV2,
    chatId: Int,
    platformUid: String
): MessageV2 = createEmptyAssistantMessage(chatId, platformUid).copy(
    revisions = currentMessage.revisions
)

internal fun normalizeAssistantRow(
    assistantMessages: List<MessageV2>,
    enabledPlatformsInChat: List<String>,
    chatId: Int
): List<MessageV2> {
    if (enabledPlatformsInChat.isEmpty()) return assistantMessages

    val consumedIndexes = mutableSetOf<Int>()
    val normalizedMessages = enabledPlatformsInChat.map { platformUid ->
        val matchedIndex = assistantMessages.indices.firstOrNull { index ->
            index !in consumedIndexes && assistantMessages[index].platformType == platformUid
        }

        if (matchedIndex == null) {
            createEmptyAssistantMessage(chatId, platformUid)
        } else {
            consumedIndexes += matchedIndex
            assistantMessages[matchedIndex]
        }
    }
    val overflowMessages = assistantMessages.filterIndexed { index, _ -> index !in consumedIndexes }

    return normalizedMessages + overflowMessages
}

internal fun updateAssistantSlot(
    groupedMessages: ChatViewModel.GroupedMessages,
    turnIndex: Int,
    platformIndex: Int,
    transform: (MessageV2) -> MessageV2
): ChatViewModel.GroupedMessages {
    if (turnIndex !in groupedMessages.assistantMessages.indices) return groupedMessages

    val currentTurnMessages = groupedMessages.assistantMessages[turnIndex]
    if (platformIndex !in currentTurnMessages.indices) return groupedMessages

    val updatedTurnMessages = currentTurnMessages.toMutableList()
    val updatedMessage = transform(updatedTurnMessages[platformIndex])
    if (updatedMessage == updatedTurnMessages[platformIndex]) return groupedMessages

    updatedTurnMessages[platformIndex] = updatedMessage
    val assistantMessages = groupedMessages.assistantMessages.toMutableList()
    assistantMessages[turnIndex] = updatedTurnMessages

    return groupedMessages.copy(assistantMessages = assistantMessages)
}
