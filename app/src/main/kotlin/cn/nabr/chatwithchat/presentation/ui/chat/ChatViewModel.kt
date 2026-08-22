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
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.context.ContextCheckpointRepository
import cn.nabr.chatwithchat.data.context.ContextRange
import cn.nabr.chatwithchat.data.context.ContextRangeCorrection
import cn.nabr.chatwithchat.data.context.ContextStrategy
import cn.nabr.chatwithchat.data.context.semanticAssistantContent
import cn.nabr.chatwithchat.data.database.entity.ACTIVE_REVISION_LATEST
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.database.entity.SessionTextSkillBinding
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.data.database.entity.effectiveStickerRefs
import cn.nabr.chatwithchat.data.database.entity.effectiveThoughts
import cn.nabr.chatwithchat.data.database.entity.effectiveTokenUsage
import cn.nabr.chatwithchat.data.database.entity.isEffectivelyBlank
import cn.nabr.chatwithchat.data.database.entity.resetActiveRevision
import cn.nabr.chatwithchat.data.database.entity.selectRevision
import cn.nabr.chatwithchat.data.database.entity.snapshotLatestAssistantRevision
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.memory.MemoryCompletedTurnInput
import cn.nabr.chatwithchat.data.memory.PreparedMemoryContext
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
import cn.nabr.chatwithchat.data.textskill.ChatRequestContext
import cn.nabr.chatwithchat.data.textskill.PendingTextSkillSelection
import cn.nabr.chatwithchat.data.textskill.SelectedTextSkill
import cn.nabr.chatwithchat.data.textskill.SkillInvocationSource
import cn.nabr.chatwithchat.data.textskill.TextSkillInvocationCandidate
import cn.nabr.chatwithchat.data.textskill.TextSkillInvocationCompiler
import cn.nabr.chatwithchat.data.textskill.TextSkillPackageRepository
import cn.nabr.chatwithchat.data.textskill.TextSkillSelectionScope
import cn.nabr.chatwithchat.data.textskill.TextSkillVariantKey
import cn.nabr.chatwithchat.data.textskill.selectionKey
import cn.nabr.chatwithchat.data.textskill.toSelectableTextSkills
import cn.nabr.chatwithchat.data.title.ChatTitleGenerationService
import cn.nabr.chatwithchat.data.title.ChatTitleTurn
import cn.nabr.chatwithchat.data.tool.TOOL_ARGUMENTS_TOO_LARGE
import cn.nabr.chatwithchat.data.tool.TOOL_BUDGET_EXCEEDED_PREFIX
import cn.nabr.chatwithchat.data.tool.TOOL_PROTOCOL_RESPONSE_TOO_LARGE
import cn.nabr.chatwithchat.data.tool.TOOL_RESULT_BUDGET_EXHAUSTED
import cn.nabr.chatwithchat.data.tool.ToolApprovalBroker
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.di.ApplicationScope
import cn.nabr.chatwithchat.util.AttachmentPayloadCache
import cn.nabr.chatwithchat.util.FileUtils
import cn.nabr.chatwithchat.util.getPlatformName
import cn.nabr.chatwithchat.util.handleStates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val memoryRepository: MemoryRepository,
    private val chatTitleGenerationService: ChatTitleGenerationService,
    private val attachmentUploadCoordinator: AttachmentUploadCoordinator,
    private val toolApprovalBroker: ToolApprovalBroker,
    private val contextCheckpointRepository: ContextCheckpointRepository,
    private val textSkillPackageRepository: TextSkillPackageRepository,
    private val textSkillInvocationCompiler: TextSkillInvocationCompiler,
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

    /**
     * Skills the user picked on the landing page, before this chat existed.
     *
     * Only used to seed the in-memory selections below, and only when they have no saved state of
     * their own yet: after that the SavedStateHandle copy is the source of truth, so a pick the user
     * removed does not come back when the process is recreated.
     */
    private val initialTextSkills: List<PendingTextSkillSelection> = decodePendingTextSkillSelections(
        Uri.decode(savedStateHandle.get<String>("initialTextSkills").orEmpty())
    )
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
    private val _oneShotTextSkills = MutableStateFlow(
        savedStateHandle.get<String>(ONE_SHOT_TEXT_SKILLS_STATE_KEY)
            ?.let { encoded -> runCatching { TEXT_SKILL_SELECTION_JSON.decodeFromString<List<SelectedTextSkill>>(encoded) }.getOrNull().orEmpty() }
            ?: initialTextSkills.skillsInScope(TextSkillSelectionScope.ONE_SHOT)
    )
    val oneShotTextSkills = _oneShotTextSkills.asStateFlow()
    private val _availableTextSkills = MutableStateFlow<List<SelectedTextSkill>>(emptyList())
    val availableTextSkills = _availableTextSkills.asStateFlow()
    private val _sessionTextSkills = MutableStateFlow<List<SessionTextSkillBinding>>(emptyList())
    val sessionTextSkills = _sessionTextSkills.asStateFlow()

    /**
     * Session-scope picks that cannot be stored yet, because `session_text_skill_bindings` is keyed by
     * a persisted chat id and this chat has no row until its first message is saved. They are surfaced
     * through [_sessionTextSkills] as bindings with a non-positive chat id and flushed to the database
     * by [flushPendingSessionTextSkills] as soon as the row exists.
     */
    private val pendingSessionTextSkills = MutableStateFlow(
        savedStateHandle.get<String>(PENDING_SESSION_TEXT_SKILLS_STATE_KEY)
            ?.let { encoded -> decodePendingTextSkillSelections(encoded) }
            ?: initialTextSkills.filter { it.scope == TextSkillSelectionScope.SESSION }
    )
    private val _pendingSelectedAttachmentBatches = MutableStateFlow(0)
    val pendingSelectedAttachmentBatches = _pendingSelectedAttachmentBatches.asStateFlow()
    private val _pendingMessageEditAttachmentBatches = MutableStateFlow(0)
    val pendingMessageEditAttachmentBatches = _pendingMessageEditAttachmentBatches.asStateFlow()
    private val attachmentBatchMutex = Mutex()
    private val memoryContextCache = TurnMemoryContextCache()
    private val pendingOwnedAttachmentPaths = Collections.synchronizedSet(mutableSetOf<String>())

    // Chat messages currently in the chat room
    private val _groupedMessages = MutableStateFlow(GroupedMessages())
    val groupedMessages = _groupedMessages.asStateFlow()

    // Each chat states for assistant chat messages
    // Index of the currently shown message's platform - default is 0 (first platform)
    private val _indexStates = MutableStateFlow(listOf<Int>())
    val indexStates = _indexStates.asStateFlow()

    // Loading states for each platform
    private val _loadingStates = MutableStateFlow<List<LoadingState>>(emptyList())
    val loadingStates = _loadingStates.asStateFlow()
    private val _isPersistingCompletion = MutableStateFlow(false)
    val isPersistingCompletion = _isPersistingCompletion.asStateFlow()
    private val loadingCompletionLock = Any()

    private val _toolProgressStates = MutableStateFlow<Map<String, List<ToolProgressState>>>(emptyMap())
    val toolProgressStates = _toolProgressStates.asStateFlow()
    val pendingToolApproval = toolApprovalBroker.pending

    // Used for text data to show in SelectText Bottom Sheet
    private val _selectedText = MutableStateFlow("")
    val selectedText = _selectedText.asStateFlow()

    // State for the message loading state (From the database)
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded = _isLoaded.asStateFlow()

    // Null while the chat-level context range sheet is closed.
    private val _contextRangeSheet = MutableStateFlow<ContextRangeSheetState?>(null)
    val contextRangeSheet = _contextRangeSheet.asStateFlow()

    /**
     * Lightweight "earlier context was folded" indicator for the overflow menu.
     *
     * Read from the durable checkpoint store rather than from the last request, so it stays correct
     * after process death and never depends on a send having happened in this session.
     */
    private val _isContextCompacted = MutableStateFlow(false)
    val isContextCompacted = _isContextCompacted.asStateFlow()

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
        viewModelScope.launch { loadSessionTextSkills() }
        viewModelScope.launch {
            textSkillPackageRepository.packages.collect { packages ->
                _availableTextSkills.value = packages.toSelectableTextSkills()
            }
        }
        fetchEnabledPlatformsInApp()
        observeStateChanges()
    }

    fun addMessage(userMessage: MessageV2) {
        val platformUid = currentChatPlatformUid() ?: return
        _groupedMessages.update {
            it.copy(
                userMessages = it.userMessages + listOf(userMessage),
                assistantMessages = it.assistantMessages + listOf(
                    listOf(
                        createEmptyAssistantMessage(
                            chatId = chatRoomId,
                            platformUid = platformUid
                        )
                    )
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
            _loadingStates.update { listOf(LoadingState.Loading) }
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
            _loadingStates.update { listOf(LoadingState.Loading) }
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

    /**
     * Opens the chat-level range sheet for whichever provider is currently selected.
     *
     * The sheet is published in its loading state first so the entry point stays instant: the stored
     * range and the fold status are two database reads, and making the menu wait for them would turn
     * a glance into a stall.
     */
    fun openContextRangeSheet() {
        val platform = currentContextPlatform() ?: return
        _contextRangeSheet.update {
            ContextRangeSheetState(
                platformUid = platform.uid,
                platformName = platform.name,
                strategy = ContextStrategy.fromStorage(platform.contextStrategy),
                isLoading = true
            )
        }
        loadContextRangeSheet(platform)
    }

    fun closeContextRangeSheet() = _contextRangeSheet.update { null }

    /**
     * Records the first turn that participates in context assembly for this chat and provider.
     *
     * `null` clears the row back to full history. Neither branch touches `messages_v2`: a range
     * decides what a request carries, never what the conversation contains.
     *
     * Applied optimistically to the sheet so the radio button responds immediately; the write that
     * follows is the same value, and a failed write simply leaves the previous range in place for
     * the next send to use.
     */
    fun selectContextRangeStart(userMessageId: Int?) {
        val sheet = _contextRangeSheet.value ?: return
        if (!sheet.isRangeSelectable) return
        if (userMessageId != null && sheet.turns.none { it.userMessageId == userMessageId }) return

        _contextRangeSheet.update { current ->
            current?.copy(
                selectedStartUserMessageId = userMessageId,
                correction = ContextRangeCorrection.NONE
            )
        }

        val chatId = _chatRoom.value.id
        if (chatId <= 0) return
        val platformUid = sheet.platformUid
        viewModelScope.launch {
            readContextStore {
                if (userMessageId == null) {
                    contextCheckpointRepository.clearRange(chatId, platformUid)
                } else {
                    contextCheckpointRepository.saveRange(
                        ContextRange(
                            chatRoomId = chatId,
                            platformUid = platformUid,
                            startUserMessageId = userMessageId
                        )
                    )
                }
            }
        }
    }

    private fun loadContextRangeSheet(platform: PlatformV2) {
        val chatId = _chatRoom.value.id
        val grouped = _groupedMessages.value
        viewModelScope.launch {
            val turns = buildContextRangeTurns(grouped.userMessages)
            val assistantMessageIds = grouped.assistantMessages
                .flatten()
                .mapTo(mutableSetOf()) { it.id }
            val storedRange = if (chatId > 0) {
                readContextStore { contextCheckpointRepository.loadRange(chatId, platform.uid) }
            } else {
                null
            }
            val resolution = resolveContextRangeAnchor(
                storedStartUserMessageId = storedRange?.startUserMessageId,
                turns = turns,
                assistantMessageIds = assistantMessageIds
            )
            if (chatId > 0 && resolution.correction != ContextRangeCorrection.NONE) {
                // Persist the widening now so the next send does not have to correct it again. The
                // messages the stale anchor pointed at are untouched — only the range row goes.
                readContextStore { contextCheckpointRepository.clearRange(chatId, platform.uid) }
            }
            val foldedThrough = if (chatId > 0) {
                loadFoldedThroughUserMessageId(chatId, platform.uid)
            } else {
                null
            }

            _contextRangeSheet.update { current ->
                // The user may have switched provider or dismissed the sheet while this was loading.
                if (current == null || current.platformUid != platform.uid) {
                    current
                } else {
                    current.copy(
                        turns = turns,
                        selectedStartUserMessageId = resolution.startUserMessageId,
                        correction = resolution.correction,
                        foldedTurnCount = countFoldedTurns(turns, foldedThrough),
                        isLoading = false
                    )
                }
            }
            _isContextCompacted.update { foldedThrough != null }
        }
    }

    private fun refreshContextCompactionNotice() {
        val platform = currentContextPlatform()
        val chatId = _chatRoom.value.id
        if (platform == null || chatId <= 0) {
            _isContextCompacted.update { false }
            return
        }
        viewModelScope.launch {
            val foldedThrough = loadFoldedThroughUserMessageId(chatId, platform.uid)
            _isContextCompacted.update { foldedThrough != null }
        }
    }

    /**
     * Re-reads everything keyed by `chatRoomId + platformUid` after the selection moved to another
     * provider, because a range belongs to one provider and must never be reinterpreted under
     * another's numbering.
     */
    private fun refreshContextStateForCurrentPlatform() {
        refreshContextCompactionNotice()
        if (_contextRangeSheet.value != null) openContextRangeSheet()
    }

    private suspend fun loadFoldedThroughUserMessageId(chatId: Int, platformUid: String): Int? {
        val chain = readContextStore { contextCheckpointRepository.loadChain(chatId, platformUid) }
        chain?.foldedThroughUserMessageId?.let { return it }
        val nativeState = readContextStore { contextCheckpointRepository.loadNativeState(chatId, platformUid) }
        return nativeState?.takeIf { it.isUsable }?.boundaryUserMessageId
    }

    /**
     * Context-store reads are advisory: a database hiccup degrades the sheet to "nothing folded, no
     * range", which is the same conservative answer the request assembly falls back to. Cancellation
     * is rethrown so a closed screen still tears its coroutines down.
     */
    private suspend fun <T> readContextStore(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    private fun currentContextPlatform(): PlatformV2? {
        val selectedUid = _lastSelectedModel.value?.platformUid
            ?: enabledPlatformsInChat.firstOrNull()
            ?: return null
        return _enabledPlatformsInApp.value.firstOrNull { it.uid == selectedUid }
            ?: _platformsInApp.value.firstOrNull { it.uid == selectedUid }
    }

    fun generateDefaultChatTitle(): String? = chatRepository.generateDefaultChatTitle(_groupedMessages.value.userMessages)

    fun retryChat(turnIndex: Int, platformIndex: Int) {
        if (_isPersistingCompletion.value) return
        if (turnIndex !in _groupedMessages.value.assistantMessages.indices) return
        val currentMessage = _groupedMessages.value.assistantMessages
            .getOrNull(turnIndex)
            ?.getOrNull(platformIndex)
            ?: return
        val platform = _enabledPlatformsInApp.value.firstOrNull { it.uid == currentMessage.platformType } ?: return
        val platformSelection = resolvePlatformSelection(platform, currentMessage.effectiveTokenUsage()?.model)
        val revisionToAppendOnSuccess = currentMessage.snapshotLatestAssistantRevision(currentTimeStamp)
        clearToolProgress(turnIndex, platformIndex)
        setOnlyPlatformLoading(platformIndex)
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
            val memoryContext = prepareMemoryContextForTurn(retryContext, platformSelection.platform)
            val frozenTextSkillSnapshot = retryContext.userMessages.lastOrNull()
                ?.id
                ?.takeIf { it > 0 }
                ?.let { userMessageId -> chatRepository.fetchTextSkillInvocation(userMessageId) }
            chatRepository.completeChat(
                retryContext.userMessages,
                retryContext.assistantMessages,
                platformSelection.platform,
                ChatRequestContext(
                    memoryPrompt = memoryContext.prompt,
                    textSkillInvocation = frozenTextSkillSnapshot
                ),
                reasoningMode = platformSelection.reasoningMode
            ).handleStates(
                messageFlow = _groupedMessages,
                turnIndex = turnIndex,
                platformIdx = platformIndex,
                onLoadingComplete = {
                    markPlatformLoadingComplete(platformIndex)
                },
                revisionToAppendOnSuccess = revisionToAppendOnSuccess,
                onToolProgress = { progress -> updateToolProgress(turnIndex, platformIndex, progress) },
                resolveErrorMessage = ::resolveToolErrorMessage
            )
        }
    }

    fun approveToolApproval(requestId: String) {
        toolApprovalBroker.approve(requestId)
    }

    /** Approves this call and stops asking for the same tool for the rest of this conversation. */
    fun approveToolApprovalForSession(requestId: String) {
        toolApprovalBroker.approveForSession(requestId)
    }

    fun denyToolApproval(requestId: String) {
        toolApprovalBroker.deny(requestId)
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
        if (platformIndex !in _groupedMessages.value.assistantMessages.getOrNull(assistantIndex).orEmpty().indices) return

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
        val textSkillSnapshot = runCatching { compileTextSkillSnapshot(includeOneShot = false) }
            .onFailure { error -> _attachmentNotice.update { error.message ?: "Skill 编译失败" } }
            .getOrNull()
        if (_sessionTextSkills.value.any(SessionTextSkillBinding::isEnabled) && textSkillSnapshot == null) {
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

        // Add a slot only for the currently selected model. Historical candidates are display data,
        // not a request fan-out list.
        val currentPlatformUid = currentChatPlatformUid() ?: return false
        _groupedMessages.update {
            it.copy(
                assistantMessages = it.assistantMessages + listOf(
                    listOf(
                        createEmptyAssistantMessage(
                            chatId = chatRoomId,
                            platformUid = currentPlatformUid
                        )
                    )
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

        // An edit starts a new request. It recompiles only current session bindings, never draft one-shot choices.
        if (textSkillSnapshot == null) {
            completeChat()
        } else {
            persistTextSkillSnapshotThenComplete(textSkillSnapshot, clearOneShotAfterPersistence = false)
        }
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
                    appendLine(message.semanticAssistantContent())
                    appendLine()
                }
            }
        }

        // Save the Markdown file
        val fileName = "export_${chatRoom.value.title}_${System.currentTimeMillis()}.md"
        return Pair(fileName, chatHistoryMarkdown)
    }

    private fun completeChat(requestContext: ChatRequestContext = ChatRequestContext()) {
        val currentPlatformUid = currentChatPlatformUid() ?: return
        val platform = _enabledPlatformsInApp.value.firstOrNull { it.uid == currentPlatformUid } ?: return
        val platformSelection = resolvePlatformSelection(platform)
        val turnIndex = _groupedMessages.value.assistantMessages.lastIndex
        val currentPlatformIndex = ensureCurrentTurnAssistantSlot(
            turnIndex = turnIndex,
            platformUid = currentPlatformUid,
            modelId = platformSelection.platform.model
        ) ?: return
        ensureCurrentPlatformTracked(currentPlatformUid)
        setOnlyPlatformLoading(currentPlatformIndex)
        clearToolProgressForTurn(turnIndex)
        val groupedMessages = _groupedMessages.value
        val memoryPlatform = preferredMemoryPlatform()

        viewModelScope.launch {
            val memoryContext = prepareMemoryContextForTurn(groupedMessages, memoryPlatform)

            val latestGroupedMessages = _groupedMessages.value
            chatRepository.completeChat(
                latestGroupedMessages.userMessages,
                latestGroupedMessages.assistantMessages,
                platformSelection.platform,
                requestContext.copy(memoryPrompt = memoryContext.prompt),
                reasoningMode = platformSelection.reasoningMode
            ).handleStates(
                messageFlow = _groupedMessages,
                turnIndex = turnIndex,
                platformIdx = currentPlatformIndex,
                onLoadingComplete = { markPlatformLoadingComplete(currentPlatformIndex) },
                onToolProgress = { progress -> updateToolProgress(turnIndex, currentPlatformIndex, progress) },
                resolveErrorMessage = ::resolveToolErrorMessage
            )
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

    /**
     * Replaces an internal tool-limit code with text a user can read.
     *
     * The repository emits these as data, so the substitution happens here, where string resources are
     * reachable. Anything unrecognised is left alone — the same field carries genuine provider error text.
     */
    private fun resolveToolErrorMessage(message: String): String = when {
        message.contains(TOOL_ARGUMENTS_TOO_LARGE) ->
            context.getString(R.string.tool_error_arguments_too_large)
        message.contains(TOOL_PROTOCOL_RESPONSE_TOO_LARGE) ->
            context.getString(R.string.tool_error_response_too_large)
        message.contains(TOOL_BUDGET_EXCEEDED_PREFIX) ->
            context.getString(R.string.tool_error_budget_exhausted)
        message.contains(TOOL_RESULT_BUDGET_EXHAUSTED) ->
            context.getString(R.string.tool_error_result_budget_exhausted)
        else -> message
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

        _chatRoom.update { chatRoom ->
            if (platformUid in chatRoom.enabledPlatform) chatRoom
            else chatRoom.copy(enabledPlatform = chatRoom.enabledPlatform + platformUid)
        }
        _chatPlatformModels.update { current -> current + (selectedModel.platformUid to nextConfig) }
        _lastSelectedModel.update {
            LastSelectedModel(
                platformUid = selectedModel.platformUid,
                model = selectedModel.modelId,
                reasoningMode = nextReasoningMode
            )
        }
        _currentReasoningMode.update { nextReasoningMode }
        _loadingStates.update { emptyList() }

        viewModelScope.launch {
            settingRepository.updateLastSelectedModel(selectedModel.platformUid, selectedModel.modelId, nextReasoningMode)
            if (_chatRoom.value.id > 0) {
                chatRepository.saveChatPlatformModels(_chatRoom.value.id, _chatPlatformModels.value)
                persistCurrentChatSnapshot()
            }
        }
        refreshContextStateForCurrentPlatform()
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

        _chatPlatformModels.update { current -> current + (selectedModel.platformUid to nextConfig) }
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

    private fun currentChatPlatformUid(): String? = _lastSelectedModel.value?.platformUid
        ?: enabledPlatformsInChat.firstOrNull()

    private fun currentChatPlatform(): PlatformV2? = currentChatPlatformUid()
        ?.let { uid -> _enabledPlatformsInApp.value.firstOrNull { platform -> platform.uid == uid } }

    private fun setOnlyPlatformLoading(platformIndex: Int) {
        _loadingStates.update { current ->
            List(maxOf(current.size, platformIndex + 1)) { index ->
                if (index == platformIndex) LoadingState.Loading else LoadingState.Idle
            }
        }
    }

    private fun ensureCurrentTurnAssistantSlot(
        turnIndex: Int,
        platformUid: String,
        modelId: String
    ): Int? {
        val existingIndex = _groupedMessages.value.assistantMessages
            .getOrNull(turnIndex)
            ?.indexOfLast { message ->
                assistantCandidateMatchesModel(
                    message = message,
                    platformUid = platformUid,
                    modelId = modelId
                )
            }
            ?.takeIf { index -> index >= 0 }
        if (existingIndex != null) return existingIndex

        if (turnIndex !in _groupedMessages.value.assistantMessages.indices) return null
        _groupedMessages.update { grouped ->
            val currentTurn = grouped.assistantMessages.getOrNull(turnIndex) ?: return@update grouped
            val updatedAssistantMessages = grouped.assistantMessages.toMutableList()
            updatedAssistantMessages[turnIndex] = currentTurn + createEmptyAssistantMessage(chatRoomId, platformUid)
            grouped.copy(assistantMessages = updatedAssistantMessages)
        }
        return _groupedMessages.value.assistantMessages[turnIndex].lastIndex
    }

    private fun ensureCurrentPlatformTracked(platformUid: String) {
        _chatRoom.update { chatRoom ->
            if (platformUid in chatRoom.enabledPlatform) chatRoom
            else chatRoom.copy(enabledPlatform = chatRoom.enabledPlatform + platformUid)
        }
    }

    private fun reasoningModeForSelectedModel(
        selectedModel: AvailableChatModel,
        requestedMode: ReasoningMode
    ): ReasoningMode = selectedModel.platform.coerceReasoningModeForModel(requestedMode, selectedModel.modelId)

    private suspend fun prepareMemoryContext(
        groupedMessages: GroupedMessages,
        memoryPlatform: PlatformV2?
    ): PreparedMemoryContext = withContext(Dispatchers.IO) {
        prepareMemoryContextWhenEnabled(refreshMemoryEnabled()) {
            memoryRepository.prepareMemoryContext(
                chatRoom = _chatRoom.value,
                userMessages = groupedMessages.userMessages,
                assistantMessages = groupedMessages.assistantMessages,
                memoryPlatform = memoryPlatform
            )
        }
    }

    private suspend fun prepareMemoryContextForTurn(
        groupedMessages: GroupedMessages,
        memoryPlatform: PlatformV2?
    ): PreparedMemoryContext = memoryContextCache.getOrPrepare(
        key = groupedMessages.memoryTurnSnapshotKey(chatRoomId)
    ) {
        prepareMemoryContext(groupedMessages, memoryPlatform)
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
            isSupported = FileUtils.isSupportedAttachmentMimeType(mimeType)
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
            _loadingStates.update { emptyList() }
            return
        }

        if (attachments.any { it.status == ChatAttachmentDraft.Status.Preparing }) {
            return
        }

        if (queuedQuestion.isBlank() && attachments.none { it.status == ChatAttachmentDraft.Status.Ready }) {
            pendingQuestionText = null
            _loadingStates.update { emptyList() }
            return
        }

        pendingQuestionText = null
        sendQuestion(queuedQuestion, attachments)
    }

    private fun sendQuestion(questionText: String, attachments: List<ChatAttachmentDraft>) {
        val candidates = textSkillCandidates()
        if (candidates.isEmpty()) {
            sendQuestionWithTextSkillSnapshot(questionText, attachments, textSkillSnapshot = null)
            return
        }

        // Freeze the selected bindings and drafts at submit time, then read package content away from
        // the main thread before accepting the message or starting a provider request.
        _loadingStates.update { listOf(LoadingState.Loading) }
        viewModelScope.launch {
            val textSkillSnapshot = runCatching {
                withContext(Dispatchers.IO) { textSkillInvocationCompiler.compile(candidates) }
            }.onFailure { error ->
                _attachmentNotice.update { error.message ?: "Skill 编译失败" }
            }.getOrNull() ?: run {
                _loadingStates.update { emptyList() }
                return@launch
            }
            sendQuestionWithTextSkillSnapshot(questionText, attachments, textSkillSnapshot)
        }
    }

    private fun sendQuestionWithTextSkillSnapshot(
        questionText: String,
        attachments: List<ChatAttachmentDraft>,
        textSkillSnapshot: cn.nabr.chatwithchat.data.textskill.TextSkillInvocationSnapshot?
    ) {
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
            _loadingStates.update { listOf(LoadingState.Loading) }
            viewModelScope.launch {
                try {
                    if (textSkillSnapshot == null) {
                        persistInitialRequestBeforeCompletion()
                        completeChat()
                    } else {
                        persistTextSkillSnapshotThenComplete(textSkillSnapshot)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    _attachmentNotice.update { error.message ?: error::class.simpleName.orEmpty() }
                    _loadingStates.update { emptyList() }
                }
            }
        } else {
            if (textSkillSnapshot != null) {
                persistTextSkillSnapshotThenComplete(textSkillSnapshot)
            } else {
                completeChat()
            }
        }
    }

    fun selectOneShotTextSkills(skills: List<SelectedTextSkill>) {
        setOneShotTextSkills(skills.distinctBy(SelectedTextSkill::selectionKey))
    }

    fun selectTextSkills(skills: List<SelectedTextSkill>, scope: TextSkillSelectionScope) {
        when (scope) {
            TextSkillSelectionScope.ONE_SHOT -> selectOneShotTextSkills(skills)
            TextSkillSelectionScope.SESSION -> addSessionTextSkills(skills)
        }
    }

    fun removeOneShotTextSkill(skill: SelectedTextSkill) {
        setOneShotTextSkills(_oneShotTextSkills.value.filterNot { it == skill })
    }

    fun addSessionTextSkills(skills: List<SelectedTextSkill>) {
        viewModelScope.launch {
            val chatId = chatRoomId
            if (chatId <= 0) {
                // No chat row to key the bindings on yet, so hold the picks in memory. They still take
                // part in the very next request and are written out once the row is saved.
                setPendingSessionTextSkills(
                    mergeTextSkillSelection(pendingSessionTextSkills.value, skills, TextSkillSelectionScope.SESSION)
                )
                loadSessionTextSkills()
                return@launch
            }
            val existingKeys = _sessionTextSkills.value.mapTo(mutableSetOf(), SessionTextSkillBinding::selectionKey)
            val additions = skills.filterNot { skill -> skill.selectionKey() in existingKeys }
            val start = _sessionTextSkills.value.size
            additions.forEachIndexed { index, skill ->
                chatRepository.saveSessionTextSkillBinding(
                    SessionTextSkillBinding(
                        chatId = chatId,
                        packageId = skill.packageId,
                        skillId = skill.skillId,
                        variantKey = skill.variantKey,
                        displayName = skill.displayName,
                        orderIndex = start + index
                    )
                )
            }
            loadSessionTextSkills()
        }
    }

    fun setSessionTextSkillEnabled(binding: SessionTextSkillBinding, enabled: Boolean) {
        viewModelScope.launch {
            if (binding.chatId > 0) {
                chatRepository.setSessionTextSkillBindingEnabled(
                    binding.chatId,
                    binding.packageId,
                    binding.skillId,
                    binding.variantKey,
                    enabled
                )
            } else {
                setPendingSessionTextSkills(
                    pendingSessionTextSkills.value.map { selection ->
                        if (selection.skill.selectionKey() == binding.selectionKey()) {
                            selection.copy(isEnabled = enabled)
                        } else {
                            selection
                        }
                    }
                )
            }
            loadSessionTextSkills()
        }
    }

    fun removeSessionTextSkill(binding: SessionTextSkillBinding) {
        viewModelScope.launch {
            if (binding.chatId > 0) {
                chatRepository.removeSessionTextSkillBinding(
                    binding.chatId,
                    binding.packageId,
                    binding.skillId,
                    binding.variantKey
                )
            } else {
                setPendingSessionTextSkills(
                    pendingSessionTextSkills.value.filterNot { selection ->
                        selection.skill.selectionKey() == binding.selectionKey()
                    }
                )
            }
            loadSessionTextSkills()
        }
    }

    private suspend fun loadSessionTextSkills() {
        val persisted = if (chatRoomId > 0) chatRepository.fetchSessionTextSkillBindings(chatRoomId) else emptyList()
        _sessionTextSkills.value = persisted + pendingSessionTextSkills.value.mapIndexed { index, selection ->
            selection.toSessionBinding(chatId = UNSAVED_SESSION_CHAT_ID, orderIndex = persisted.size + index)
        }
    }

    private fun setPendingSessionTextSkills(selections: List<PendingTextSkillSelection>) {
        pendingSessionTextSkills.value = selections
        savedStateHandle[PENDING_SESSION_TEXT_SKILLS_STATE_KEY] = encodePendingTextSkillSelections(selections)
    }

    /**
     * Single place where "this chat now has a database row" is recorded, so every save path also gets
     * the chance to write out session-scope Skills the user picked while the row did not exist yet.
     */
    private suspend fun recordPersistedChatRoom(chatId: Int) {
        launchState.recordPersistedChatRoomId(chatId)
        if (flushPendingSessionTextSkills(chatId)) loadSessionTextSkills()
    }

    /**
     * Writes the picks made before the chat row existed, so later turns read them back from the
     * database. Returns whether anything was written.
     */
    private suspend fun flushPendingSessionTextSkills(chatId: Int): Boolean {
        val pending = pendingSessionTextSkills.value
        if (chatId <= 0 || pending.isEmpty()) return false
        val existingKeys = chatRepository.fetchSessionTextSkillBindings(chatId)
            .mapTo(mutableSetOf(), SessionTextSkillBinding::selectionKey)
        var orderIndex = existingKeys.size
        pending.forEach { selection ->
            if (!existingKeys.add(selection.skill.selectionKey())) return@forEach
            chatRepository.saveSessionTextSkillBinding(
                selection.toSessionBinding(chatId = chatId, orderIndex = orderIndex)
            )
            orderIndex++
        }
        setPendingSessionTextSkills(emptyList())
        return true
    }

    private fun compileTextSkillSnapshot(includeOneShot: Boolean = true) = textSkillInvocationCompiler.compile(
        textSkillCandidates(includeOneShot)
    )

    private fun textSkillCandidates(includeOneShot: Boolean = true): List<TextSkillInvocationCandidate> =
        _sessionTextSkills.value.filter(SessionTextSkillBinding::isEnabled).map { binding ->
            TextSkillInvocationCandidate(
                TextSkillVariantKey(binding.packageId, binding.skillId, binding.variantKey),
                SkillInvocationSource.SESSION
            )
        } + _oneShotTextSkills.value.takeIf { includeOneShot }.orEmpty().map { skill ->
            TextSkillInvocationCandidate(
                TextSkillVariantKey(skill.packageId, skill.skillId, skill.variantKey),
                SkillInvocationSource.ONE_SHOT
            )
        }

    private fun persistTextSkillSnapshotThenComplete(
        snapshot: cn.nabr.chatwithchat.data.textskill.TextSkillInvocationSnapshot,
        clearOneShotAfterPersistence: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                val persisted = withContext(Dispatchers.IO) {
                    chatRepository.saveChatWithTextSkillInvocation(
                        chatRoom = _chatRoom.value,
                        messages = persistableMessages(_groupedMessages.value),
                        chatPlatformModels = _chatPlatformModels.value,
                        snapshot = snapshot
                    )
                }
                _chatRoom.value = persisted
                recordPersistedChatRoom(persisted.id)
                if (isInitialRequestInFlight) {
                    launchState.recordInitialRequestPersisted()
                    isInitialRequestInFlight = false
                }
                _groupedMessages.value = withContext(Dispatchers.IO) { fetchGroupedMessages(persisted.id) }
                if (clearOneShotAfterPersistence) setOneShotTextSkills(emptyList())
                loadSessionTextSkills()
                completeChat(ChatRequestContext(textSkillInvocation = snapshot))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _attachmentNotice.value = error.message ?: "Skill 快照保存失败"
                _loadingStates.value = emptyList()
            }
        }
    }

    private fun setOneShotTextSkills(skills: List<SelectedTextSkill>) {
        _oneShotTextSkills.value = skills
        savedStateHandle[ONE_SHOT_TEXT_SKILLS_STATE_KEY] = TEXT_SKILL_SELECTION_JSON.encodeToString(skills)
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
            _loadingStates.update { emptyList() }
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

        return GroupedMessages(userMessages, assistantMessages)
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
                    recordPersistedChatRoom(recoveredChatRoom.id)
                    launchState.recordInitialRequestPersisted()
                    _chatRoom.value = recoveredChatRoom
                    _groupedMessages.value = recoveredMessages
                    _indexStates.value = List(recoveredMessages.assistantMessages.size) { 0 }
                    _loadingStates.value = emptyList()
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
        recordPersistedChatRoom(savedChatRoom.id)
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
                val loadedChatRoom = if (chatRoomId == 0) {
                    ChatRoomV2(id = 0, title = context.getString(R.string.untitled_chat), enabledPlatform = enabledPlatformsInChat)
                } else {
                    chatRepository.fetchChatListV2().first { it.id == chatRoomId }
                }
                currentChatPlatformUid()?.let { platformUid ->
                    if (platformUid in loadedChatRoom.enabledPlatform) loadedChatRoom
                    else loadedChatRoom.copy(enabledPlatform = loadedChatRoom.enabledPlatform + platformUid)
                } ?: loadedChatRoom
            }
            maybeSendInitialQuestion()
            refreshContextCompactionNotice()
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
            refreshContextCompactionNotice()
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
        val lastSelectedModel = settingRepository.fetchLastSelectedModel()
        val selectedModel = selectPersistedModel(availableModels, persistedModels, lastSelectedModel)
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
        val mergedModels = persistedModels + (selectedModel.platformUid to selectedConfig)
        _chatRoom.update { chatRoom ->
            if (selectedModel.platformUid in chatRoom.enabledPlatform) {
                chatRoom
            } else {
                chatRoom.copy(enabledPlatform = chatRoom.enabledPlatform + selectedModel.platformUid)
            }
        }
        _loadingStates.update { emptyList() }
        _chatPlatformModels.update { mergedModels }

        if (chatRoomId != 0 && mergedModels != persistedModels) {
            chatRepository.saveChatPlatformModels(chatRoomId, mergedModels)
        }
        if (chatRoomId != 0) {
            _groupedMessages.update { fetchGroupedMessages(chatRoomId) }
            _indexStates.update { current ->
                List(_groupedMessages.value.assistantMessages.size) { index -> current.getOrElse(index) { 0 } }
            }
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
        persistedModels: Map<String, ChatPlatformConfig>,
        lastSelectedModel: LastSelectedModel?
    ): AvailableChatModel? {
        if (chatRoomId == 0) return null

        lastSelectedModel?.let { selected ->
            availableModels.firstOrNull { model ->
                model.platformUid == selected.platformUid && model.modelId == selected.model
            }?.let { return it }
        }
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
                        recordPersistedChatRoom(savedChatRoom.id)
                        _chatRoom.update { currentChatRoom ->
                            if (currentChatRoom.id == chatRoom.id && chatRoom.id == 0) {
                                savedChatRoom
                            } else {
                                // Only the timestamp is adopted: the rest of the row may have been
                                // edited meanwhile (a rename), but keeping the pre-save last-use time
                                // would let the next save write an older value back over this one.
                                currentChatRoom.copy(updatedAt = savedChatRoom.updatedAt)
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
                        maybeGenerateChatTitle(savedChatRoom, persistedGroupedMessages)
                        refreshContextCompactionNotice()
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

    private fun resolvePlatformSelection(
        platform: PlatformV2,
        requestedModelId: String? = null
    ): ResolvedChatPlatform {
        val chatConfig = _chatPlatformModels.value[platform.uid]
        val chatModel = requestedModelId?.trim().takeIf { it?.isNotBlank() == true }
            ?: chatConfig?.model?.trim().orEmpty()
        val platformWithChatModel = if (chatModel.isBlank() || chatModel == platform.model) {
            platform
        } else {
            platform.copy(model = chatModel)
        }
        val reasoningMode = platformWithChatModel.coerceReasoningModeForModel(
            chatConfig
                ?.takeIf { config -> config.model == platformWithChatModel.model }
                ?.reasoningMode
                ?: platform.defaultReasoningMode(),
            platformWithChatModel.model
        )

        return ResolvedChatPlatform(
            platform = platformWithChatModel,
            reasoningMode = reasoningMode
        )
    }

    private fun preferredMemoryPlatform(): PlatformV2? {
        val chatPlatform = currentChatPlatform() ?: return null
        return resolvePlatformSelection(chatPlatform).platform
    }

    /**
     * Names the chat with one LLM call after its first complete turn.
     *
     * Runs on [applicationScope], not [viewModelScope]: the user very often leaves the chat as soon
     * as the answer finishes, and a title that dies with the screen would leave the truncated first
     * message behind exactly in the case it is least wanted.
     *
     * Deliberately narrow: first turn only, and only while the stored title is still the truncated
     * first message that `saveChat` writes. Comparing against that truncation — rather than against
     * whatever the title was a moment ago — is what keeps this idempotent: reopening a one-turn chat
     * re-enters this path, and a chat already named by the model or renamed by hand no longer matches,
     * so it is never regenerated. The title is re-read from the database immediately before the write
     * because [updateChatTitle] can land while the request is in flight.
     */
    private fun maybeGenerateChatTitle(chatRoom: ChatRoomV2, groupedMessages: GroupedMessages) {
        if (chatRoom.id <= 0) return
        if (groupedMessages.userMessages.size != 1) return

        val platform = preferredMemoryPlatform() ?: return
        val userMessage = groupedMessages.userMessages.firstOrNull()?.content?.takeIf { it.isNotBlank() } ?: return
        val assistantMessage = groupedMessages.assistantMessages
            .firstOrNull()
            ?.firstOrNull { it.effectiveContent().isNotBlank() }
            ?.effectiveContent()
        val autoTitle = chatRepository.generateDefaultChatTitle(groupedMessages.userMessages) ?: return
        if (chatRoom.title != autoTitle) return

        applicationScope.launch(Dispatchers.IO) {
            try {
                val generated = chatTitleGenerationService.generateTitle(
                    platform = platform,
                    turns = listOf(
                        ChatTitleTurn(
                            userMessage = userMessage,
                            assistantMessage = assistantMessage
                        )
                    )
                ) ?: return@launch

                val storedTitle = chatRepository.fetchChatListV2().firstOrNull { it.id == chatRoom.id }?.title
                if (storedTitle != autoTitle) return@launch
                if (generated == autoTitle) return@launch

                chatRepository.updateChatTitle(chatRoom, generated)
                _chatRoom.update { current ->
                    if (current.id == chatRoom.id && current.title == autoTitle) {
                        current.copy(title = generated)
                    } else {
                        current
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // A title is cosmetic and the truncated first message is already stored, so nothing
                // here may surface as a chat error.
            }
        }
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
        private const val ONE_SHOT_TEXT_SKILLS_STATE_KEY = "one_shot_text_skills"
        private const val PENDING_SESSION_TEXT_SKILLS_STATE_KEY = "pending_session_text_skills"

        // Chat id carried by session bindings that only exist in memory, because the chat has no row yet.
        private const val UNSAVED_SESSION_CHAT_ID = 0
        private val TEXT_SKILL_SELECTION_JSON = Json

        fun toolProgressKey(turnIndex: Int, platformIndex: Int): String = "$turnIndex:$platformIndex"
    }
}

internal fun SessionTextSkillBinding.selectionKey(): String = "$packageId:$skillId:$variantKey"

internal fun PendingTextSkillSelection.toSessionBinding(chatId: Int, orderIndex: Int): SessionTextSkillBinding =
    SessionTextSkillBinding(
        chatId = chatId,
        packageId = skill.packageId,
        skillId = skill.skillId,
        variantKey = skill.variantKey,
        displayName = skill.displayName,
        orderIndex = orderIndex,
        isEnabled = isEnabled
    )

internal fun shouldRecoverInitialRequest(
    routeChatRoomId: Int,
    initialRequestId: Int,
    initialQuestion: String,
    initialAttachmentPaths: List<String>
): Boolean = routeChatRoomId == 0 &&
    initialRequestId < 0 &&
    (initialQuestion.isNotBlank() || initialAttachmentPaths.isNotEmpty())

internal suspend fun prepareMemoryContextWhenEnabled(
    memoryEnabled: Boolean,
    prepare: suspend () -> PreparedMemoryContext
): PreparedMemoryContext = if (memoryEnabled) {
    runCatching { prepare() }.getOrDefault(PreparedMemoryContext())
} else {
    PreparedMemoryContext()
}

internal data class MemoryTurnSnapshotKey(
    val chatId: Int,
    val turnIndex: Int,
    val createdAt: Long,
    val content: String,
    val attachmentSemantics: List<Pair<String, String>>
)

internal fun ChatViewModel.GroupedMessages.memoryTurnSnapshotKey(chatId: Int): MemoryTurnSnapshotKey? {
    val latest = userMessages.lastOrNull() ?: return null
    return MemoryTurnSnapshotKey(
        chatId = chatId,
        turnIndex = userMessages.lastIndex,
        createdAt = latest.createdAt,
        content = latest.content,
        attachmentSemantics = latest.attachments.map { attachment ->
            attachment.resolvedDisplayName to attachment.mimeType
        }
    )
}

internal class TurnMemoryContextCache(
    private val maxEntries: Int = 32
) {
    private val mutex = Mutex()
    private val contexts = LinkedHashMap<MemoryTurnSnapshotKey, PreparedMemoryContext>()

    suspend fun getOrPrepare(
        key: MemoryTurnSnapshotKey?,
        prepare: suspend () -> PreparedMemoryContext
    ): PreparedMemoryContext {
        if (key == null) return prepare()
        return mutex.withLock {
            contexts[key]?.let { cached -> return@withLock cached }
            prepare().also { prepared ->
                contexts[key] = prepared
                while (contexts.size > maxEntries.coerceAtLeast(1)) {
                    contexts.remove(contexts.keys.first())
                }
            }
        }
    }
}

internal fun List<ChatViewModel.ToolProgressState>.appendToolProgress(progress: ApiState): List<ChatViewModel.ToolProgressState> {
    if (progress.toolNameOrNull()?.isStickerToolName() == true) return this

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

private fun ApiState.toolNameOrNull(): String? = when (this) {
    is ApiState.ToolStarted -> toolName
    is ApiState.ToolFinished -> toolName
    is ApiState.ToolFailed -> toolName
    else -> null
}

internal fun String.isStickerToolName(): Boolean = this == ToolDefinition.SearchStickers.name ||
    this == ToolDefinition.SendSticker.name

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
                it.attachments.isNotEmpty() ||
                it.effectiveStickerRefs().isNotEmpty()
        }
        .sortedBy { it.createdAt }
}

internal fun createEmptyAssistantMessage(
    chatId: Int,
    platformUid: String
): MessageV2 = MessageV2(
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

/**
 * A platform UID identifies provider credentials, not a concrete model. Preserve a completed
 * answer when the user changes models under the same provider by only reusing an unfinished slot
 * or an answer whose usage was recorded for the selected model.
 */
internal fun assistantCandidateMatchesModel(
    message: MessageV2,
    platformUid: String,
    modelId: String
): Boolean {
    if (message.platformType != platformUid) return false

    val candidateModel = message.effectiveTokenUsage()?.model?.trim().orEmpty()
    return candidateModel == modelId.trim() ||
        (candidateModel.isBlank() && message.isEffectivelyBlank())
}

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
