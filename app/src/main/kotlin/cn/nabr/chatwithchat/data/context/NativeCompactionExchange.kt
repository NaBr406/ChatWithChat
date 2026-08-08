package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseContextManagement
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputItem
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputItemSerializer
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsePassthroughInputItem
import cn.nabr.chatwithchat.data.dto.openai.response.OutputItemDoneEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseErrorEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseFailedEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesStreamEvent
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchScheduler
import cn.nabr.chatwithchat.data.network.OpenAICompactionException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Per-request bookkeeping for OpenAI server-side compaction.
 *
 * One instance is created per user turn and lives for the whole exchange, which on the native
 * tool-loop path spans several `/v1/responses` round trips. It owns three things that would
 * otherwise be scattered across `ChatRepositoryImpl`:
 *
 * 1. the `context_management` array attached to every round,
 * 2. the stored opaque window replayed at the head of every round's input array,
 * 3. capture and persistence of a newly emitted `type=compaction` item.
 *
 * **The window is opaque.** Nothing here parses, edits, truncates or displays the compaction item —
 * it is moved between the stream and Room as raw JSON and handed straight back to the provider. The
 * user-visible transcript stays in `messages_v2`; this is purely what the *model* sees.
 *
 * **Persistence strictly precedes pruning.** [commit] writes first and only then flips
 * [supersedesAssembledInput], so a failed write leaves the next round sending the full assembled
 * input exactly as it would have without this class. Losing a window costs one redundant server-side
 * fold; pruning before a successful write would lose conversation.
 */
internal class NativeCompactionExchange private constructor(
    private val platform: PlatformV2,
    private val chatRoomId: Int,
    private val boundaryUserMessageId: Int,
    private val sourceTurnsHash: String,
    private val checkpointRepository: ContextCheckpointRepository?,
    private val memoryTurnBatchScheduler: MemoryTurnBatchScheduler?,
    private val tokenCounter: ContextTokenCounter?,
    private val json: Json,
    /**
     * The `context_management` array for every round of this exchange, or null when this provider or
     * model must not receive one. Doubles as the enabled flag: everything else here no-ops when it is
     * null, so the non-native providers pay nothing for going through the same call sites.
     */
    val contextManagement: List<ResponseContextManagement>?,
    committedWindow: List<JsonObject>
) {

    private var committed: List<JsonObject> = committedWindow
    private val pending = mutableListOf<JsonObject>()
    private var pendingHasCompaction = false
    private var unsupportedSignalled = false

    val isEnabled: Boolean get() = contextManagement != null

    /**
     * The stored window, ready to be prepended to a round's input array.
     *
     * Empty until the provider has actually folded something once — a first-ever native request
     * sends ordinary turns and gets its window back in the response.
     */
    val replayItems: List<ResponseInputItem>
        get() = committed.map { item -> ResponsePassthroughInputItem(item) }

    /**
     * True once a window written during *this* exchange has replaced the locally assembled turns.
     *
     * Only ever set by a successful [commit]. Callers use it to stop re-sending history the server
     * has already folded; see the class comment for why the ordering matters.
     */
    var supersedesAssembledInput: Boolean = false
        private set

    /**
     * Folds one round's stream into the in-flight window.
     *
     * A `type=compaction` item resets the buffer rather than appending to it: the newest compaction
     * item subsumes every earlier one, so keeping both would replay the same history twice at
     * increasing cost. Items *before* it are dropped for the same reason — that is exactly what the
     * server just folded.
     *
     * Only `message` items are carried over after it. `reasoning` items are deliberately skipped:
     * this path is stateless (`store = false`), and a reasoning item referenced by id without its
     * encrypted content is rejected when replayed. `function_call` items are skipped too, because
     * the tool adapter rebuilds them paired with their outputs in [recordContinuationItems] and
     * capturing them here as well would duplicate every `call_id`.
     */
    fun recordRound(events: List<ResponsesStreamEvent>) {
        if (!isEnabled) return

        events.forEach { event ->
            when (event) {
                is OutputItemDoneEvent -> {
                    val item = event.item
                    when {
                        item.type == COMPACTION_ITEM_TYPE -> {
                            pending.clear()
                            pending += item.raw
                            pendingHasCompaction = true
                        }

                        !pendingHasCompaction -> Unit
                        item.type == MESSAGE_ITEM_TYPE -> pending += item.raw
                        else -> Unit
                    }
                }

                is ResponseErrorEvent -> noteProviderError(event.code, event.message)
                is ResponseFailedEvent -> noteProviderError(
                    event.response.error?.code,
                    event.response.error?.message
                )

                else -> Unit
            }
        }
    }

    /**
     * Adds the tool-loop continuation items produced after a compaction item to the in-flight window.
     *
     * Without this the window would end at the compaction item while the `function_call` /
     * `function_call_output` pairs that followed it lived only in the caller's transient list — and
     * pruning that list after [commit] would break the pairing the provider requires.
     */
    fun recordContinuationItems(items: List<ResponseInputItem>) {
        if (!isEnabled || !pendingHasCompaction || items.isEmpty()) return

        items.forEach { item ->
            val encoded = runCatching {
                json.encodeToJsonElement(ResponseInputItemSerializer, item).jsonObject
            }.getOrNull()
            if (encoded != null) pending += encoded
        }
    }

    /**
     * Persists whatever this exchange learned, and reports whether the caller may now prune.
     *
     * Returns true only when a new window was durably written. A false covers every other case —
     * nothing to write, no repository, or a failed write — and always means "keep sending what you
     * were sending".
     *
     * An `unsupported_parameter` answer is handled here as well, and is the one durable negative
     * signal in this class: the model is marked as not supporting native compaction so the next turn
     * takes the app-side checkpoint path instead of retrying the same rejected parameter forever.
     */
    suspend fun commit(): Boolean {
        if (!isEnabled) return false
        val repository = checkpointRepository ?: return false

        if (unsupportedSignalled) {
            unsupportedSignalled = false
            try {
                repository.markNativeUnsupported(chatRoomId, platform.uid)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Next turn will observe the same rejection and try again. Failing the user's
                // response over a bookkeeping write would be a far worse trade.
            }
            return false
        }

        if (!pendingHasCompaction) return false
        val window = pending.withBalancedToolItems()
        if (window.isEmpty()) return false

        val payload = JsonArray(window).toString()
        try {
            repository.saveNativeState(
                OpenAINativeCompactionState(
                    chatRoomId = chatRoomId,
                    platformUid = platform.uid,
                    compactionItemsJson = payload,
                    boundaryUserMessageId = boundaryUserMessageId,
                    sourceTurnsHash = sourceTurnsHash,
                    estimatedTokens = tokenCounter?.estimateText(payload) ?: 0,
                    isReadyForInput = true,
                    nativeUnsupported = false
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Nothing is pruned and nothing is marked: the exchange continues against the assembled
            // input, and the next request simply lets the server fold again.
            return false
        }

        committed = window
        pending.clear()
        pendingHasCompaction = false
        supersedesAssembledInput = true
        notifyMemoryOfFoldedBatch()
        return true
    }

    /**
     * Ends a round that failed, keeping only its durable negative signal.
     *
     * The in-flight window is dropped: a compaction item emitted by a response that then errored
     * describes a context the model never finished answering from, and storing it would make the
     * next request replay a window the conversation never actually had.
     */
    suspend fun abandon() {
        if (!isEnabled) return
        pending.clear()
        pendingHasCompaction = false
        commit()
    }

    /**
     * Tells long-term memory that history has aged out of the raw context.
     *
     * The server folded instead of [ContextFoldService], but the local consequence is identical, so
     * the trigger has to fire here too or native users would silently stop getting memory
     * consolidation. `markCompactionUrgent` gates on the memory switch itself.
     *
     * Fires only on the transition into a freshly written window, which is what keeps it to once per
     * folded batch: a retry that produces no new compaction item never reaches this point.
     */
    private suspend fun notifyMemoryOfFoldedBatch() {
        val scheduler = memoryTurnBatchScheduler ?: return
        if (chatRoomId <= 0 || boundaryUserMessageId <= 0) return
        try {
            scheduler.markCompactionUrgent(chatRoomId, boundaryUserMessageId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The window is already committed; a missed memory nudge must not undo that.
        }
    }

    private fun noteProviderError(code: String?, message: String?) {
        if (indicatesUnsupportedContextManagement(code, message)) {
            unsupportedSignalled = true
        }
    }

    /**
     * Drops `function_call` / `function_call_output` items whose counterpart is not in the window.
     *
     * A window is replayed verbatim on the next request, and the provider rejects a call without its
     * output (or an output without its call) outright. Half a pair can survive an exchange that ended
     * early — a cancelled loop, a round that errored after emitting calls — so the invariant is
     * enforced at the boundary rather than assumed.
     */
    private fun List<JsonObject>.withBalancedToolItems(): List<JsonObject> {
        val callIds = itemCallIds(FUNCTION_CALL_TYPE)
        val outputIds = itemCallIds(FUNCTION_CALL_OUTPUT_TYPE)
        if (callIds.isEmpty() && outputIds.isEmpty()) return toList()

        return filter { item ->
            when (item.itemType()) {
                FUNCTION_CALL_TYPE -> item.callId() in outputIds
                FUNCTION_CALL_OUTPUT_TYPE -> item.callId() in callIds
                else -> true
            }
        }
    }

    private fun List<JsonObject>.itemCallIds(type: String): Set<String> = asSequence()
        .filter { item -> item.itemType() == type }
        .mapNotNull { item -> item.callId() }
        .toSet()

    private fun JsonObject.itemType(): String? = this[FIELD_TYPE]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.callId(): String? = this[FIELD_CALL_ID]?.jsonPrimitive?.contentOrNull

    companion object {
        const val COMPACTION_ITEM_TYPE = "compaction"

        private const val MESSAGE_ITEM_TYPE = "message"
        private const val FUNCTION_CALL_TYPE = "function_call"
        private const val FUNCTION_CALL_OUTPUT_TYPE = "function_call_output"
        private const val FIELD_TYPE = "type"
        private const val FIELD_CALL_ID = "call_id"

        /**
         * Builds the exchange for one user turn, or a disabled no-op when anything about the setup
         * rules native compaction out.
         *
         * The native decision is taken twice on purpose. [ContextBuilder] has already decided, and
         * its verdict is visible in [ContextBuildResult.compactionState]; asking
         * [OpenAINativeCompactionService] again means the model gate is still the single authority on
         * *which* models qualify, and the two must agree before a request carries the parameter. A
         * disagreement degrades to the app-side path, never to sending `context_management` at a
         * provider that would reject it.
         */
        suspend fun create(
            platform: PlatformV2,
            chatRoomId: Int,
            managed: ContextBuildResult?,
            service: OpenAINativeCompactionService?,
            checkpointRepository: ContextCheckpointRepository?,
            memoryTurnBatchScheduler: MemoryTurnBatchScheduler?,
            json: Json = DEFAULT_JSON
        ): NativeCompactionExchange {
            val disabled = {
                NativeCompactionExchange(
                    platform = platform,
                    chatRoomId = chatRoomId,
                    boundaryUserMessageId = 0,
                    sourceTurnsHash = "",
                    checkpointRepository = null,
                    memoryTurnBatchScheduler = null,
                    tokenCounter = null,
                    json = json,
                    contextManagement = null,
                    committedWindow = emptyList()
                )
            }

            if (managed == null || service == null || checkpointRepository == null) return disabled()
            if (chatRoomId <= 0) return disabled()
            if (!managed.usesNativeCompaction()) return disabled()

            val contextManagement = service.contextManagementFor(
                platform = platform,
                budget = managed.budget,
                nativeUnsupported = false
            ) ?: return disabled()

            val previousState = runCatching {
                checkpointRepository.loadNativeState(chatRoomId, platform.uid)
            }.getOrNull()

            // Re-read rather than trusting the snapshot: within a single user turn an earlier round
            // may have just recorded the rejection, and the fallback request that follows must not
            // send the same rejected parameter again.
            if (previousState?.nativeUnsupported == true) return disabled()

            return NativeCompactionExchange(
                platform = platform,
                chatRoomId = chatRoomId,
                // The window covers everything this request submitted, which ends at the message the
                // user just sent; the reply that follows the compaction item is captured into the
                // window itself, so the next build can drop this whole turn from the raw tail without
                // losing the answer.
                boundaryUserMessageId = managed.turns.lastOrNull()?.userMessage?.id ?: 0,
                sourceTurnsHash = ContextTurnHasher.hashSegment(
                    previousSegmentHash = previousState?.sourceTurnsHash,
                    turns = managed.turns
                ),
                checkpointRepository = checkpointRepository,
                memoryTurnBatchScheduler = memoryTurnBatchScheduler,
                tokenCounter = ContextTokenCounter.forPlatform(platform),
                json = json,
                contextManagement = contextManagement,
                committedWindow = managed.nativeCompactionItemsJson.toWindowItems(json)
            )
        }

        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        private fun ContextBuildResult.usesNativeCompaction(): Boolean =
            compactionState == ContextCompactionState.NATIVE_SERVER_SIDE ||
                compactionState == ContextCompactionState.REUSED_NATIVE_STATE

        /**
         * A stored window that no longer parses is treated as absent rather than as an error: the
         * request still goes out, the server folds again, and [commit] overwrites the unreadable row.
         */
        private fun String?.toWindowItems(json: Json): List<JsonObject> {
            val payload = this?.takeIf { it.isNotBlank() } ?: return emptyList()
            return runCatching {
                json.parseToJsonElement(payload).jsonArray.map { element -> element.jsonObject }
            }.getOrDefault(emptyList())
        }

        /**
         * Whether a provider error means "this model does not accept `context_management`".
         *
         * The code is authoritative when present. The message check exists because the streamed
         * error event does not always carry one, and it deliberately requires both an
         * unsupported-parameter phrase *and* a mention of the parameter itself: marking a model
         * unsupported is durable, and a generic rate-limit or auth message must never trip it.
         */
        internal fun indicatesUnsupportedContextManagement(code: String?, message: String?): Boolean {
            if (code == OpenAICompactionException.UNSUPPORTED_PARAMETER ||
                code == OpenAICompactionException.UNSUPPORTED_VALUE
            ) {
                return true
            }

            val normalized = message?.lowercase() ?: return false
            val namesParameter = normalized.contains("context_management") ||
                normalized.contains("context management")
            if (!namesParameter) return false

            return normalized.contains("unsupported") ||
                normalized.contains("unknown parameter") ||
                normalized.contains("unrecognized") ||
                normalized.contains("invalid parameter") ||
                normalized.contains("not supported")
        }
    }
}
