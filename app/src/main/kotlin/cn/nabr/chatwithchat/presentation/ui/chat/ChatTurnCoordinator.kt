package cn.nabr.chatwithchat.presentation.ui.chat

import android.content.Context
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.data.memory.MemoryCompletedTurnInput
import cn.nabr.chatwithchat.data.model.ChatPlatformConfig
import cn.nabr.chatwithchat.data.repository.ChatRepository
import cn.nabr.chatwithchat.data.repository.MemoryRepository
import cn.nabr.chatwithchat.data.repository.TOOL_CALL_CHANNEL_LOST
import cn.nabr.chatwithchat.data.title.ChatTitleGenerationService
import cn.nabr.chatwithchat.data.title.ChatTitleTurn
import cn.nabr.chatwithchat.data.tool.TOOL_ARGUMENTS_TOO_LARGE
import cn.nabr.chatwithchat.data.tool.TOOL_BUDGET_EXCEEDED_PREFIX
import cn.nabr.chatwithchat.data.tool.TOOL_PROTOCOL_RESPONSE_TOO_LARGE
import cn.nabr.chatwithchat.data.tool.TOOL_RESULT_BUDGET_EXHAUSTED
import cn.nabr.chatwithchat.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns running turns, so that leaving a chat stops being the same thing as destroying its answer.
 *
 * A turn used to live on `viewModelScope`, which made navigation destructive in the worst way: the
 * scope died, `observeStateChanges` — the thing that persists a finished turn — died with it, and the
 * whole answer was discarded without a trace. Not even the partial text survived, because
 * `handleStates`' `finally` cannot run until the main thread is free, which is after `onCleared`
 * returns. A tool loop makes that routine rather than rare: sixteen sequential calls per round against
 * a twenty-second timeout, ten rounds, is minutes of work to lose.
 *
 * Turns run on the application scope and are keyed, so the guarantee is narrow and checkable: **one
 * turn per key, and its result is persisted whether or not a screen is still watching.** A view model
 * that is alive mirrors the turn's flows; one that arrives later adopts them mid-stream.
 *
 * Deliberately not durable. Turns die with the process, and nothing is resumed on the next launch:
 * replaying a round would re-run tools that are not idempotent — an alarm, a calendar entry, an MCP
 * write — so a crash would leave two of whatever the model had already done once.
 */
@Singleton
class ChatTurnCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val chatTitleGenerationService: ChatTitleGenerationService,
    @param:ApplicationScope private val applicationScope: CoroutineScope
) {

    /**
     * One running turn's state, written only by the coordinator.
     *
     * The four flows are the same four pieces of state a chat screen renders while streaming. They live
     * here rather than in the view model for exactly as long as the turn does; outside a turn the view
     * model owns its own copies again.
     */
    class LiveTurn internal constructor(
        val key: String,
        internal val messagesFlow: MutableStateFlow<ChatViewModel.GroupedMessages>,
        internal val loadingFlow: MutableStateFlow<List<ChatViewModel.LoadingState>>,
        internal val toolProgressFlow: MutableStateFlow<Map<String, List<ChatViewModel.ToolProgressState>>>,
        internal val persistingFlow: MutableStateFlow<Boolean>,
        internal val request: TurnRequest
    ) {
        val messages: StateFlow<ChatViewModel.GroupedMessages> = messagesFlow.asStateFlow()
        val loadingStates: StateFlow<List<ChatViewModel.LoadingState>> = loadingFlow.asStateFlow()
        val toolProgress: StateFlow<Map<String, List<ChatViewModel.ToolProgressState>>> =
            toolProgressFlow.asStateFlow()
        val isPersisting: StateFlow<Boolean> = persistingFlow.asStateFlow()

        internal var job: Job? = null

        /**
         * Set just before the job is cancelled by [stop], and read from `handleStates`' cancellation
         * path. It lives on the turn rather than the view model so a stop issued from one screen still
         * reads true in a turn that outlived a different one.
         */
        internal var stopRequested = false

        /**
         * Set by the turn body once it has read the setting, and read back at save time.
         *
         * It belongs to the turn rather than the request because the value is not known synchronously at
         * `start` — reading it is a suspending settings lookup — and because recall and recording for one
         * turn must agree: a turn that ran with memory on should not decline to write what it learned
         * because the switch flipped while it streamed.
         */
        internal var memoryEnabled = false

        /** Written after a save assigns an id to a chat that did not have one. */
        internal var resolvedChatId: Int = request.chatRoom.value.id

        internal fun markPlatformIdle(platformIndex: Int) {
            loadingFlow.update { current ->
                if (platformIndex !in current.indices) {
                    current
                } else {
                    current.toMutableList().apply { this[platformIndex] = ChatViewModel.LoadingState.Idle }
                }
            }
        }

        internal fun appendToolProgress(turnIndex: Int, platformIndex: Int, progress: cn.nabr.chatwithchat.data.dto.ApiState) {
            val progressKey = ChatViewModel.toolProgressKey(turnIndex, platformIndex)
            toolProgressFlow.update { current ->
                current + (progressKey to current[progressKey].orEmpty().appendToolProgress(progress))
            }
        }
    }

    /**
     * Everything the coordinator needs to finish a turn without a view model.
     *
     * [chatRoom] and [chatPlatformModels] are flows rather than snapshots on purpose. `saveChat` writes
     * the whole chat row, so a value captured at turn start would silently undo a rename or a model
     * change made while the turn ran. Reading `.value` at save time gets the newest value the view model
     * published; if the view model is gone, that is its last known value, which is no worse than what a
     * snapshot would have given.
     */
    data class TurnRequest(
        val chatRoom: StateFlow<ChatRoomV2>,
        val chatPlatformModels: StateFlow<Map<String, ChatPlatformConfig>>,
        val seedMessages: ChatViewModel.GroupedMessages,
        val seedLoadingStates: List<ChatViewModel.LoadingState>,
        val turnIndex: Int,
        val platformIndex: Int,
        /** Non-null only for a fresh user message; a retry is not new activity. */
        val memoryActivityAt: Long? = null,
        val preferredMemoryPlatform: PlatformV2? = null
    )

    /** Emitted after a turn is persisted, so a live screen can adopt the ids the database assigned. */
    data class TurnCompletion(
        val key: String,
        val savedChatRoom: ChatRoomV2,
        val persistedMessages: ChatViewModel.GroupedMessages
    )

    private val turns = MutableStateFlow<Map<String, LiveTurn>>(emptyMap())

    private val _completions = MutableSharedFlow<TurnCompletion>(extraBufferCapacity = 8)
    val completions: SharedFlow<TurnCompletion> = _completions.asSharedFlow()

    /** Persisted chats with a turn running, for the history drawer's indicator. */
    val runningChatIds: StateFlow<Set<Int>> = turns
        .map { live -> runningChatIdsFrom(live.keys) }
        .stateIn(applicationScope, SharingStarted.Eagerly, emptySet())

    /**
     * The turn running under [key], as it comes and goes.
     *
     * Cold on purpose. An eagerly-shared `StateFlow` here would spawn a collector on the application
     * scope per call that never completes, and callers ask again on every key change — a new chat re-keys
     * from its draft nonce to `chat:<id>` the moment it is saved — so the leak would grow with ordinary
     * use. Collectors get the current turn immediately because [turns] is itself a `StateFlow`; for a
     * synchronous answer use [isLive].
     */
    fun liveTurn(key: String): Flow<LiveTurn?> = turns
        .map { live -> live[key] }
        .distinctUntilChanged()

    fun isLive(key: String): Boolean = turns.value.containsKey(key)

    /**
     * Starts a turn under [key], or returns null if one is already running there.
     *
     * Refusing rather than replacing is what makes "one turn per chat" true. Two turns in one chat would
     * each finish by writing the whole message list, so the later save would drop whatever the earlier
     * one added.
     *
     * [block] receives the turn and is expected to stream into its flows — in practice by handing
     * `turn.messagesFlow` to `handleStates`.
     */
    fun start(key: String, request: TurnRequest, block: suspend (LiveTurn) -> Unit): LiveTurn? {
        val turn = synchronized(turns) {
            if (turns.value.containsKey(key)) return null
            LiveTurn(
                key = key,
                messagesFlow = MutableStateFlow(request.seedMessages),
                loadingFlow = MutableStateFlow(request.seedLoadingStates),
                toolProgressFlow = MutableStateFlow(emptyMap()),
                persistingFlow = MutableStateFlow(false),
                request = request
            ).also { created -> turns.update { current -> current + (key to created) } }
        }

        turn.job = applicationScope.launch {
            try {
                block(turn)
                // Persistence is part of the turn, not a reaction to it. The old code waited for the
                // loading state to go all-Idle and then saved from a view-model collector, which is
                // exactly the indirection that made a departing screen lose the answer.
                persistTurn(turn)
            } catch (cancellation: CancellationException) {
                // A user-requested stop still leaves a real partial answer worth keeping; a cancel that
                // came from `cancelForChats` does not, and that path clears the turn first so the save
                // below is skipped.
                if (turn.stopRequested && turns.value[key] === turn) {
                    withContext(kotlinx.coroutines.NonCancellable) { persistTurn(turn) }
                }
                throw cancellation
            } finally {
                turn.persistingFlow.value = false
                turns.update { current -> if (current[key] === turn) current - key else current }
            }
        }
        return turn
    }

    /**
     * Stops the turn under [key], keeping what it produced.
     *
     * Cancelling reaches the whole turn: the provider stream, the sequential tool executions, and a
     * pending approval wait are all suspended inside the same job.
     */
    fun stop(key: String) {
        val turn = turns.value[key] ?: return
        turn.stopRequested = true
        turn.job?.cancel(CancellationException("stopped_by_user"))
    }

    /**
     * Drops turns belonging to chats that no longer exist, without saving them.
     *
     * Called before the rows are deleted. Saving here would recreate the chat the user just removed,
     * because `saveChat` upserts.
     */
    fun cancelForChats(chatIds: Set<Int>) {
        if (chatIds.isEmpty()) return
        val doomed = turns.value.filterKeys { key ->
            chatIdFromKey(key)?.let { it in chatIds } == true
        }
        if (doomed.isEmpty()) return
        turns.update { current -> current - doomed.keys }
        doomed.values.forEach { turn -> turn.job?.cancel(CancellationException("chat_deleted")) }
    }

    /**
     * Maps an internal tool-limit code to text a user can read.
     *
     * Lives here rather than on the view model because it runs as part of the turn: a view-model method
     * reference would keep a dead screen's object graph alive, and worse, would tempt the same treatment
     * for the progress callbacks, which must write into the turn's flows so a returning screen sees them.
     */
    internal fun resolveToolErrorMessage(message: String): String = when {
        message.contains(TOOL_ARGUMENTS_TOO_LARGE) ->
            context.getString(R.string.tool_error_arguments_too_large)
        message.contains(TOOL_PROTOCOL_RESPONSE_TOO_LARGE) ->
            context.getString(R.string.tool_error_response_too_large)
        message.contains(TOOL_BUDGET_EXCEEDED_PREFIX) ->
            context.getString(R.string.tool_error_budget_exhausted)
        message.contains(TOOL_RESULT_BUDGET_EXHAUSTED) ->
            context.getString(R.string.tool_error_result_budget_exhausted)
        message.contains(TOOL_CALL_CHANNEL_LOST) ->
            context.getString(R.string.tool_error_tool_call_channel_lost)
        else -> message
    }

    private suspend fun persistTurn(turn: LiveTurn) {
        val chatRoom = turn.request.chatRoom.value
        val messages = turn.messagesFlow.value
        if (!shouldPersistTurn(chatRoom.id, messages)) return

        turn.persistingFlow.value = true
        try {
            // A chat deleted while its turn ran must not come back: `saveChat` upserts the row, so an
            // id that no longer resolves means the save has nothing left to belong to.
            if (chatRoom.id > 0 && chatRepository.fetchChatListV2().none { it.id == chatRoom.id }) return

            val (savedChatRoom, persistedMessages) = withContext(Dispatchers.IO) {
                val saved = chatRepository.saveChat(
                    chatRoom = chatRoom,
                    messages = persistableMessages(messages),
                    chatPlatformModels = turn.request.chatPlatformModels.value
                )
                saved to chatRepository.groupedMessagesFor(saved.id)
            }
            turn.resolvedChatId = savedChatRoom.id
            turn.messagesFlow.value = persistedMessages
            _completions.emit(
                TurnCompletion(
                    key = turn.key,
                    savedChatRoom = savedChatRoom,
                    persistedMessages = persistedMessages
                )
            )

            recordCompletedTurnForMemory(turn, savedChatRoom, persistedMessages)
            maybeGenerateChatTitle(savedChatRoom, persistedMessages, turn.request.preferredMemoryPlatform)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A failed save must not take the process with it. The message list is still in memory and
            // the next turn's save covers the same rows.
        }
    }

    private suspend fun recordCompletedTurnForMemory(
        turn: LiveTurn,
        savedChatRoom: ChatRoomV2,
        persistedMessages: ChatViewModel.GroupedMessages
    ) {
        val activityAt = turn.request.memoryActivityAt ?: return
        val userMessage = persistedMessages.userMessages.lastOrNull() ?: return
        if (!turn.memoryEnabled) return

        memoryRepository.recordUserActivity(savedChatRoom.id, activityAt)
        memoryRepository.recordCompletedTurn(
            MemoryCompletedTurnInput(
                chatRoom = savedChatRoom,
                userMessage = userMessage,
                assistantMessages = persistedMessages.assistantMessages.lastOrNull().orEmpty(),
                preferredPlatformUid = turn.request.preferredMemoryPlatform?.uid,
                stablePlatformOrder = savedChatRoom.enabledPlatform,
                completedAt = System.currentTimeMillis() / 1000
            )
        )
    }

    /**
     * Names the chat with one model call after its first complete turn.
     *
     * Deliberately narrow: first turn only, and only while the stored title is still the truncated first
     * message that `saveChat` writes. Comparing against that truncation — rather than against whatever
     * the title was a moment ago — is what keeps it idempotent, and is also why a turn whose screen was
     * gone still gets a title eventually: reopening a one-turn chat re-enters this path, while a chat
     * already named by the model or renamed by hand no longer matches. The title is re-read immediately
     * before the write because a rename can land while the request is in flight.
     */
    private suspend fun maybeGenerateChatTitle(
        chatRoom: ChatRoomV2,
        groupedMessages: ChatViewModel.GroupedMessages,
        platform: PlatformV2?
    ) {
        if (chatRoom.id <= 0) return
        if (groupedMessages.userMessages.size != 1) return
        if (platform == null) return

        val userMessage = groupedMessages.userMessages.firstOrNull()?.content?.takeIf { it.isNotBlank() } ?: return
        val assistantMessage = groupedMessages.assistantMessages
            .firstOrNull()
            ?.firstOrNull { it.effectiveContent().isNotBlank() }
            ?.effectiveContent()
        val autoTitle = chatRepository.generateDefaultChatTitle(groupedMessages.userMessages) ?: return
        if (chatRoom.title != autoTitle) return

        try {
            val generated = chatTitleGenerationService.generateTitle(
                platform = platform,
                turns = listOf(ChatTitleTurn(userMessage = userMessage, assistantMessage = assistantMessage))
            ) ?: return

            val storedTitle = chatRepository.fetchChatListV2().firstOrNull { it.id == chatRoom.id }?.title
            if (storedTitle != autoTitle) return
            if (generated == autoTitle) return

            chatRepository.updateChatTitle(chatRoom, generated)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A title is cosmetic and the truncated first message is already stored, so no failure here
            // may surface as a chat error.
        }
    }
}

/**
 * Whether a finished turn is worth writing.
 *
 * The size check is the interesting one: a mismatch means the turn is mid-assembly — a user message
 * added without its assistant row, or the reverse — and persisting that shape would store a chat the
 * reader cannot group back into turns. `-1` is the "not loaded yet" chat id, distinct from the `0` a
 * new chat carries before its first save.
 */
internal fun shouldPersistTurn(chatId: Int, messages: ChatViewModel.GroupedMessages): Boolean =
    chatId != -1 &&
        messages.userMessages.isNotEmpty() &&
        messages.userMessages.size == messages.assistantMessages.size

/**
 * The key a chat's turns run under.
 *
 * A persisted chat keys on its id, which is what enforces one turn per chat. A chat that has not been
 * saved yet has id `0` for every one of them, so it keys on a nonce held in its own `SavedStateHandle`
 * instead: sending in a new chat, leaving immediately, and starting another new chat would otherwise
 * collide on `0`, and the second chat would adopt the first one's turn.
 */
internal fun turnKeyFor(chatRoomId: Int, draftNonce: String): String =
    if (chatRoomId > 0) "$TURN_KEY_CHAT_PREFIX$chatRoomId" else "$TURN_KEY_DRAFT_PREFIX$draftNonce"

/** Persisted chat ids among [keys]; draft keys have no row in the list yet and are skipped. */
internal fun runningChatIdsFrom(keys: Collection<String>): Set<Int> =
    keys.mapNotNull(::chatIdFromKey).toSet()

internal fun chatIdFromKey(key: String): Int? =
    key.removePrefix(TURN_KEY_CHAT_PREFIX).takeIf { it != key }?.toIntOrNull()

/**
 * Whether entering a chat should skip the database read.
 *
 * A live turn holds newer state than the database does — it has not saved yet — so loading over it
 * would replace a streaming answer with the pre-turn history and clear the loading state, leaving the
 * screen looking idle while work continues.
 */
internal fun shouldYieldRoomLoad(hasLiveTurn: Boolean): Boolean = hasLiveTurn

private const val TURN_KEY_CHAT_PREFIX = "chat:"
private const val TURN_KEY_DRAFT_PREFIX = "draft:"
