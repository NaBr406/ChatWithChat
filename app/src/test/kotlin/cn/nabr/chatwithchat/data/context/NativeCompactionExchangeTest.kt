package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.InMemoryMemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.dao.MemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.entity.MemoryPendingTurn
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseContextManagement
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseFunctionCallInputItem
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseFunctionCallOutputItem
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsePassthroughInputItem
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesCompactRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.dto.openai.response.OutputItem
import cn.nabr.chatwithchat.data.dto.openai.response.OutputItemDoneEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseError
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseErrorEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseFailedEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseObject
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesCompactResult
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesStreamEvent
import cn.nabr.chatwithchat.data.memory.FakeMaintenanceSettingRepository
import cn.nabr.chatwithchat.data.memory.InMemoryMaintenanceJobDao
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchScheduler
import cn.nabr.chatwithchat.data.memory.RecordingWorkEnqueuer
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.network.OpenAICompactionException
import cn.nabr.chatwithchat.data.network.ProviderEndpoint
import cn.nabr.chatwithchat.data.network.UploadedProviderFile
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [NativeCompactionExchange] — the per-turn bookkeeping around OpenAI's
 * server-side compaction.
 *
 * Two rules drive most of what is asserted here.
 *
 * **The window is opaque.** Nothing may parse, rewrite or summarise a compaction item, so the tests
 * check identity of the raw JSON rather than its shape, and the app-side summarization prompt must
 * never appear anywhere on this path.
 *
 * **Persistence strictly precedes pruning.** `supersedesAssembledInput` may only flip after a durable
 * write, so every failure case asserts both "nothing stored" and "still sending the assembled input".
 */
class NativeCompactionExchangeTest {

    // ------------------------------------------------------------------ enablement

    @Test
    fun `a native model gets a context management array pinned to the app's own trigger`() = runBlocking {
        val exchange = exchange()

        assertTrue(exchange.isEnabled)
        val entry = checkNotNull(exchange.contextManagement).single()
        assertEquals(ResponseContextManagement.TYPE_COMPACTION, entry.type)
        assertEquals(BUDGET.compactTrigger, entry.compactThreshold)
    }

    @Test
    fun `raw preferred uses an independent native compaction threshold`() = runBlocking {
        val largeBudget = ContextBudget.of(1_000_000, 1_200)
        val exchange = exchange(
            managed = managed(
                strategy = ContextStrategy.RAW_PREFERRED,
                budget = largeBudget
            )
        )

        assertTrue(exchange.isEnabled)
        val entry = checkNotNull(exchange.contextManagement).single()
        assertEquals(largeBudget.rawPreferredFoldTrigger, entry.compactThreshold)
        assertTrue(entry.compactThreshold!! > largeBudget.compactTrigger * 5)
    }

    /**
     * An OpenAI-compatible gateway reached through `CUSTOM` must never receive `context_management`:
     * an unknown parameter there is a hard failure, not a graceful degradation.
     */
    @Test
    fun `a compatible gateway never receives the parameter`() = runBlocking {
        val exchange = exchange(platform = platform(clientType = ClientType.CUSTOM, model = "gpt-5.4"))

        assertFalse(exchange.isEnabled)
        assertNull(exchange.contextManagement)
        assertTrue(exchange.replayItems.isEmpty())
    }

    /** The gate is the single authority on which models qualify; an unlisted family degrades quietly. */
    @Test
    fun `a model the gate does not recognise is left on the app side path`() = runBlocking {
        val exchange = exchange(platform = platform(model = "gpt-5.1"))

        assertFalse(exchange.isEnabled)
    }

    /**
     * The native decision is taken twice on purpose, and the two must agree. A build that chose the
     * app-side path never gets the parameter attached, even on a model that would accept it.
     */
    @Test
    fun `a build that chose the app side path is not upgraded to native`() = runBlocking {
        val exchange = exchange(managed = managed(compactionState = ContextCompactionState.COMPACTED_APP_SIDE))

        assertFalse(exchange.isEnabled)
    }

    @Test
    fun `reusing an existing native window still counts as the native path`() = runBlocking {
        val exchange = exchange(managed = managed(compactionState = ContextCompactionState.REUSED_NATIVE_STATE))

        assertTrue(exchange.isEnabled)
    }

    @Test
    fun `an unsaved chat has nothing to key a window by`() = runBlocking {
        val exchange = exchange(chatRoomId = 0)

        assertFalse(exchange.isEnabled)
    }

    @Test
    fun `an exchange without a repository cannot store anything so it stays disabled`() = runBlocking {
        val fixture = Fixture()
        val exchange = NativeCompactionExchange.create(
            platform = platform(),
            chatRoomId = CHAT_ID,
            managed = managed(),
            service = fixture.service,
            checkpointRepository = null,
            memoryTurnBatchScheduler = null
        )

        assertFalse(exchange.isEnabled)
    }

    /**
     * The rejection may have been recorded by an earlier round of this very turn, so the state is
     * re-read at construction rather than taken from the build snapshot.
     */
    @Test
    fun `a model already marked unsupported is not asked again`() = runBlocking {
        val fixture = Fixture(
            storedNativeState = nativeState(nativeUnsupported = true, isReadyForInput = false)
        )

        assertFalse(fixture.exchange().isEnabled)
    }

    // ------------------------------------------------------------------ replay

    @Test
    fun `a stored window is replayed verbatim at the head of the next request`() = runBlocking {
        val stored = listOf(compactionItem("earlier history"), messageItem("msg_1", "答案"))
        val exchange = exchange(managed = managed(nativeItems = stored.jsonArrayString()))

        val replayed = exchange.replayItems.map { (it as ResponsePassthroughInputItem).raw }
        assertEquals(stored, replayed)
    }

    /**
     * A row that no longer parses is treated as absent rather than as an error: the request still goes
     * out, the server folds again, and the next commit overwrites the unreadable row.
     */
    @Test
    fun `a stored window that no longer parses is treated as absent`() = runBlocking {
        val exchange = exchange(managed = managed(nativeItems = "{ this was truncated"))

        assertTrue(exchange.isEnabled)
        assertTrue(exchange.replayItems.isEmpty())
    }

    @Test
    fun `a first ever native request replays nothing`() = runBlocking {
        val exchange = exchange()

        assertTrue(exchange.replayItems.isEmpty())
        assertFalse(exchange.supersedesAssembledInput)
    }

    // ------------------------------------------------------------------ capture

    @Test
    fun `a captured compaction item is written and unlocks pruning`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        val item = compactionItem("folded so far")

        exchange.recordRound(listOf(itemDone(item)))

        assertTrue(exchange.commit())
        assertTrue(exchange.supersedesAssembledInput)
        assertEquals(listOf(item), fixture.storedWindow())
    }

    @Test
    fun `the stored window is keyed to the turn the request ended on`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        exchange.recordRound(listOf(itemDone(compactionItem("folded"))))

        exchange.commit()

        val stored = checkNotNull(fixture.repository.loadNativeState(CHAT_ID, PLATFORM_UID))
        assertEquals(LAST_USER_MESSAGE_ID, stored.boundaryUserMessageId)
        assertTrue(stored.isReadyForInput)
        assertFalse(stored.nativeUnsupported)
        assertTrue(stored.sourceTurnsHash.isNotBlank())
    }

    /** Whatever preceded the compaction item is exactly what the server just folded into it. */
    @Test
    fun `items emitted before the compaction item are dropped`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        val item = compactionItem("folded")

        exchange.recordRound(
            listOf(
                itemDone(messageItem("msg_old", "旧回答")),
                itemDone(reasoningItem("rs_old")),
                itemDone(item)
            )
        )
        exchange.commit()

        assertEquals(listOf(item), fixture.storedWindow())
    }

    @Test
    fun `a message emitted after the compaction item is carried over`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        val compaction = compactionItem("folded")
        val reply = messageItem("msg_new", "新回答")

        exchange.recordRound(listOf(itemDone(compaction), itemDone(reply)))
        exchange.commit()

        assertEquals(listOf(compaction, reply), fixture.storedWindow())
    }

    /**
     * This path is stateless (`store = false`), and a reasoning item replayed by id without its
     * encrypted content is rejected outright.
     */
    @Test
    fun `a reasoning item after the compaction item is skipped`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        val compaction = compactionItem("folded")
        val reply = messageItem("msg_new", "新回答")

        exchange.recordRound(
            listOf(itemDone(compaction), itemDone(reasoningItem("rs_new")), itemDone(reply))
        )
        exchange.commit()

        assertEquals(listOf(compaction, reply), fixture.storedWindow())
    }

    /** The newest compaction item subsumes every earlier one; keeping both replays history twice. */
    @Test
    fun `a second compaction item supersedes the first`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        val newest = compactionItem("folded again")

        exchange.recordRound(
            listOf(
                itemDone(compactionItem("folded once")),
                itemDone(messageItem("msg_mid", "中间回答")),
                itemDone(newest)
            )
        )
        exchange.commit()

        assertEquals(listOf(newest), fixture.storedWindow())
    }

    @Test
    fun `a round the server never folded writes nothing`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()

        exchange.recordRound(listOf(itemDone(messageItem("msg_1", "回答"))))

        assertFalse(exchange.commit())
        assertFalse(exchange.supersedesAssembledInput)
        assertNull(fixture.repository.loadNativeState(CHAT_ID, PLATFORM_UID))
    }

    @Test
    fun `a disabled exchange ignores everything it is told`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange(platform = platform(clientType = ClientType.CUSTOM))

        exchange.recordRound(listOf(itemDone(compactionItem("folded"))))
        exchange.recordContinuationItems(listOf(functionCall("call_1")))

        assertFalse(exchange.commit())
        assertFalse(exchange.supersedesAssembledInput)
        assertNull(fixture.repository.loadNativeState(CHAT_ID, PLATFORM_UID))
    }

    // ------------------------------------------------------------------ tool loop continuation

    @Test
    fun `a tool call and its output are carried into the window`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        val compaction = compactionItem("folded")

        exchange.recordRound(listOf(itemDone(compaction)))
        exchange.recordContinuationItems(listOf(functionCall("call_1"), functionCallOutput("call_1")))
        exchange.commit()

        val stored = fixture.storedWindow()
        assertEquals(3, stored.size)
        assertEquals(compaction, stored.first())
        assertEquals(listOf("function_call", "function_call_output"), stored.drop(1).map { it.type() })
        assertEquals(listOf("call_1", "call_1"), stored.drop(1).map { it.callId() })
    }

    @Test
    fun `continuation items before any fold are not collected`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()

        exchange.recordContinuationItems(listOf(functionCall("call_1"), functionCallOutput("call_1")))

        assertFalse(exchange.commit())
        assertNull(fixture.repository.loadNativeState(CHAT_ID, PLATFORM_UID))
    }

    /**
     * A window is replayed verbatim, and the provider rejects a call whose output never arrived. Half
     * a pair survives a loop that was cancelled mid-flight, so the invariant is enforced on write.
     */
    @Test
    fun `a tool call whose output never arrived is dropped from the window`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        val compaction = compactionItem("folded")

        exchange.recordRound(listOf(itemDone(compaction)))
        exchange.recordContinuationItems(
            listOf(functionCall("call_1"), functionCall("call_2"), functionCallOutput("call_1"))
        )
        exchange.commit()

        val stored = fixture.storedWindow()
        assertEquals(listOf(compaction), stored.filter { it.type() == "compaction" })
        assertEquals(listOf("call_1", "call_1"), stored.drop(1).map { it.callId() })
    }

    @Test
    fun `a tool output with no matching call is dropped from the window`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()

        exchange.recordRound(listOf(itemDone(compactionItem("folded"))))
        exchange.recordContinuationItems(listOf(functionCallOutput("call_orphan")))
        exchange.commit()

        assertEquals(listOf("compaction"), fixture.storedWindow().map { it.type() })
    }

    // ------------------------------------------------------------------ write failure

    /**
     * The whole ordering rule in one test: a failed write must leave the exchange sending exactly what
     * it would have sent without native compaction, and must not disturb the window already stored.
     */
    @Test
    fun `a failed write prunes nothing and keeps the previous window intact`() = runBlocking {
        val previous = listOf(compactionItem("previously folded"))
        val fixture = Fixture(
            storedNativeState = nativeState(itemsJson = previous.jsonArrayString()),
            failSaves = true
        )
        val exchange = fixture.exchange(
            managed = managed(nativeItems = previous.jsonArrayString())
        )
        exchange.recordRound(listOf(itemDone(compactionItem("newly folded"))))

        assertFalse(exchange.commit())
        assertFalse(exchange.supersedesAssembledInput)
        assertEquals(previous, fixture.storedWindow())
        assertEquals(previous, exchange.replayItems.map { (it as ResponsePassthroughInputItem).raw })
    }

    @Test
    fun `a failed write leaves the fold pending so the next round can retry it`() = runBlocking {
        val fixture = Fixture(failSaves = true)
        val exchange = fixture.exchange()
        val item = compactionItem("folded")
        exchange.recordRound(listOf(itemDone(item)))

        assertFalse(exchange.commit())

        fixture.failSaves = false
        assertTrue(exchange.commit())
        assertEquals(listOf(item), fixture.storedWindow())
    }

    // ------------------------------------------------------------------ unsupported parameter

    @Test
    fun `an unsupported parameter error durably disables native compaction`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()

        exchange.recordRound(
            listOf(ResponseErrorEvent(code = OpenAICompactionException.UNSUPPORTED_PARAMETER, message = "no"))
        )

        assertFalse(exchange.commit())
        assertEquals(1, fixture.repository.markNativeUnsupportedCount)
        assertTrue(checkNotNull(fixture.repository.loadNativeState(CHAT_ID, PLATFORM_UID)).nativeUnsupported)
    }

    @Test
    fun `an unsupported value carried on a failed response also disables it`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()

        exchange.recordRound(
            listOf(
                ResponseFailedEvent(
                    response = ResponseObject(
                        id = "resp_1",
                        status = "failed",
                        error = ResponseError(code = OpenAICompactionException.UNSUPPORTED_VALUE, message = "no")
                    )
                )
            )
        )

        assertFalse(exchange.commit())
        assertEquals(1, fixture.repository.markNativeUnsupportedCount)
    }

    /** The streamed error event does not always carry a code, so the message is a fallback. */
    @Test
    fun `a message naming the parameter is enough when no code is supplied`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()

        exchange.recordRound(
            listOf(ResponseErrorEvent(message = "Unknown parameter: 'context_management'."))
        )

        assertFalse(exchange.commit())
        assertEquals(1, fixture.repository.markNativeUnsupportedCount)
    }

    /**
     * Marking a model unsupported is durable and silently degrades every later turn, so an ordinary
     * transient failure must never reach it.
     */
    @Test
    fun `an unrelated provider failure never disables native compaction`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()

        exchange.recordRound(
            listOf(
                ResponseErrorEvent(code = "rate_limit_exceeded", message = "Rate limit reached, please retry."),
                ResponseErrorEvent(code = OpenAICompactionException.CONTEXT_LENGTH_EXCEEDED, message = "too long")
            )
        )

        assertFalse(exchange.commit())
        assertEquals(0, fixture.repository.markNativeUnsupportedCount)
        assertNull(fixture.repository.loadNativeState(CHAT_ID, PLATFORM_UID))
    }

    /**
     * A response that folded and then rejected the parameter is still a rejection: the rejection wins
     * and the half-finished window is never stored.
     */
    @Test
    fun `a rejection in the same round beats a compaction item`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()

        exchange.recordRound(
            listOf(
                itemDone(compactionItem("folded")),
                ResponseErrorEvent(code = OpenAICompactionException.UNSUPPORTED_PARAMETER, message = "no")
            )
        )

        assertFalse(exchange.commit())
        assertFalse(exchange.supersedesAssembledInput)
        assertEquals(1, fixture.repository.markNativeUnsupportedCount)
        assertTrue(fixture.storedWindow().isEmpty())
    }

    @Test
    fun `the rejection is recorded once rather than on every later commit`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        exchange.recordRound(
            listOf(ResponseErrorEvent(code = OpenAICompactionException.UNSUPPORTED_PARAMETER, message = "no"))
        )

        exchange.commit()
        exchange.commit()

        assertEquals(1, fixture.repository.markNativeUnsupportedCount)
    }

    // ------------------------------------------------------------------ abandon

    /**
     * A compaction item from a response that then errored describes a context the model never
     * finished answering from; replaying it would give the next turn a history it never had.
     */
    @Test
    fun `abandoning a round drops the window it had folded`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        exchange.recordRound(listOf(itemDone(compactionItem("folded"))))

        exchange.abandon()

        assertFalse(exchange.supersedesAssembledInput)
        assertNull(fixture.repository.loadNativeState(CHAT_ID, PLATFORM_UID))
    }

    @Test
    fun `abandoning still keeps the durable rejection`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        exchange.recordRound(
            listOf(
                itemDone(compactionItem("folded")),
                ResponseErrorEvent(code = OpenAICompactionException.UNSUPPORTED_PARAMETER, message = "no")
            )
        )

        exchange.abandon()

        assertEquals(1, fixture.repository.markNativeUnsupportedCount)
    }

    // ------------------------------------------------------------------ memory hand-off

    /**
     * The server folded instead of [ContextFoldService], but the local consequence is the same: turns
     * have aged out of the model's view, so long-term memory has to be told or native users would
     * silently stop getting consolidation.
     */
    @Test
    fun `a freshly written window tells memory the turns aged out`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        exchange.recordRound(listOf(itemDone(compactionItem("folded"))))

        exchange.commit()

        assertEquals(listOf(CHAT_ID to LAST_USER_MESSAGE_ID), fixture.turnBatchDao.urgentCalls)
    }

    @Test
    fun `a commit that writes nothing never nudges memory`() = runBlocking {
        val fixture = Fixture()
        val exchange = fixture.exchange()
        exchange.recordRound(listOf(itemDone(messageItem("msg_1", "回答"))))

        exchange.commit()

        assertTrue(fixture.turnBatchDao.urgentCalls.isEmpty())
    }

    /** The window is already durably stored; a bookkeeping nudge must not be able to undo that. */
    @Test
    fun `a failing memory nudge does not undo the committed window`() = runBlocking {
        val fixture = Fixture(failMemoryNudge = true)
        val exchange = fixture.exchange()
        val item = compactionItem("folded")
        exchange.recordRound(listOf(itemDone(item)))

        assertTrue(exchange.commit())
        assertTrue(exchange.supersedesAssembledInput)
        assertEquals(listOf(item), fixture.storedWindow())
    }

    // ------------------------------------------------------------------ fixtures

    private suspend fun exchange(
        platform: PlatformV2 = platform(),
        chatRoomId: Int = CHAT_ID,
        managed: ContextBuildResult? = managed()
    ): NativeCompactionExchange = Fixture().exchange(platform, chatRoomId, managed)

    /**
     * Wires the exchange to storage that can be made to fail, and to a real
     * [MemoryTurnBatchScheduler] rather than a stand-in — the nudge crossing a real scheduler's own
     * memory-enabled gate is part of what these tests check.
     */
    private class Fixture(
        storedNativeState: OpenAINativeCompactionState? = null,
        var failSaves: Boolean = false,
        failMemoryNudge: Boolean = false
    ) {
        val repository = FailableCheckpointRepository(
            delegate = InMemoryContextCheckpointRepository(nativeState = storedNativeState),
            failSaves = { failSaves }
        )

        /**
         * [MemoryTurnBatchScheduler] is a final class, so the nudge is observed one level down:
         * `getUnclaimedTurnsThrough` is the first thing `markCompactionUrgent` touches after its own
         * memory-enabled check, which makes it an exact witness for "the nudge fired".
         */
        val turnBatchDao = RecordingTurnBatchDao(InMemoryMemoryTurnBatchDao(), failOnUrgent = failMemoryNudge)

        private val jobDao = InMemoryMaintenanceJobDao()

        val service = OpenAINativeCompactionService(UnusedOpenAIAPI())

        suspend fun exchange(
            platform: PlatformV2 = platform(),
            chatRoomId: Int = CHAT_ID,
            managed: ContextBuildResult? = managed()
        ): NativeCompactionExchange = NativeCompactionExchange.create(
            platform = platform,
            chatRoomId = chatRoomId,
            managed = managed,
            service = service,
            checkpointRepository = repository,
            memoryTurnBatchScheduler = MemoryTurnBatchScheduler(
                turnBatchDao = turnBatchDao,
                maintenanceJobDao = jobDao,
                maintenanceScheduler = MemoryMaintenanceScheduler(jobDao = jobDao, clock = CLOCK),
                workEnqueuer = RecordingWorkEnqueuer(),
                settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = true),
                clock = CLOCK
            )
        )

        /** The stored window, parsed back out of its opaque JSON payload. */
        suspend fun storedWindow(): List<JsonObject> {
            val payload = repository.loadNativeState(CHAT_ID, PLATFORM_UID)?.compactionItemsJson
            if (payload.isNullOrBlank()) return emptyList()
            return Json.parseToJsonElement(payload).jsonArray.map { it.jsonObject }
        }
    }

    /** Lets a single test make [saveNativeState] fail without disturbing any other operation. */
    private class FailableCheckpointRepository(
        private val delegate: InMemoryContextCheckpointRepository,
        private val failSaves: () -> Boolean
    ) : ContextCheckpointRepository by delegate {
        val markNativeUnsupportedCount: Int get() = delegate.markNativeUnsupportedCount

        override suspend fun saveNativeState(state: OpenAINativeCompactionState) {
            if (failSaves()) throw IllegalStateException("context storage unavailable")
            delegate.saveNativeState(state)
        }
    }

    private class RecordingTurnBatchDao(
        private val delegate: MemoryTurnBatchDao,
        private val failOnUrgent: Boolean = false
    ) : MemoryTurnBatchDao by delegate {
        val urgentCalls = mutableListOf<Pair<Int, Int>>()

        override suspend fun getUnclaimedTurnsThrough(chatId: Int, lastUserMessageId: Int): List<MemoryPendingTurn> {
            urgentCalls += chatId to lastUserMessageId
            if (failOnUrgent) throw IllegalStateException("turn batch storage unavailable")
            return delegate.getUnclaimedTurnsThrough(chatId, lastUserMessageId)
        }
    }

    /**
     * [OpenAINativeCompactionService] consults the model gate and never calls the API when deciding
     * whether a platform gets `context_management`, so any call here means the decision reached for
     * the network — which is itself the bug.
     */
    private class UnusedOpenAIAPI : OpenAIAPI {
        override fun streamChatCompletion(
            request: ChatCompletionRequest,
            timeoutSeconds: Int,
            endpoint: ProviderEndpoint
        ): Flow<ChatCompletionChunk> = unexpected()

        override fun streamResponses(request: ResponsesRequest, timeoutSeconds: Int, endpoint: ProviderEndpoint): Flow<ResponsesStreamEvent> =
            unexpected()

        override suspend fun uploadFile(filePath: String, fileName: String, mimeType: String, endpoint: ProviderEndpoint): UploadedProviderFile =
            unexpected()

        override suspend fun isFileAvailable(fileId: String, endpoint: ProviderEndpoint): Boolean = unexpected()

        override suspend fun compactResponses(
            request: ResponsesCompactRequest,
            timeoutSeconds: Int,
            endpoint: ProviderEndpoint
        ): ResponsesCompactResult = unexpected()

        private fun unexpected(): Nothing =
            throw AssertionError("deciding whether to send context_management must not call the provider")
    }

    private companion object {
        const val CHAT_ID = 7
        const val PLATFORM_UID = "platform-openai"
        const val LAST_USER_MESSAGE_ID = 42
        val BUDGET: ContextBudget = ContextBudget.of(modelContextWindow = 2_048, providerSummaryTarget = 768)
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC)

        fun platform(
            clientType: ClientType = ClientType.OPENAI,
            model: String = "gpt-5.4"
        ) = PlatformV2(
            uid = PLATFORM_UID,
            name = "OpenAI",
            compatibleType = clientType,
            apiUrl = "https://api.openai.com/v1/",
            model = model,
            token = "token"
        )

        fun managed(
            compactionState: ContextCompactionState = ContextCompactionState.NATIVE_SERVER_SIDE,
            nativeItems: String? = null,
            lastUserMessageId: Int = LAST_USER_MESSAGE_ID,
            strategy: ContextStrategy = ContextStrategy.AUTO_COMPACT_BALANCED,
            budget: ContextBudget = BUDGET
        ) = ContextBuildResult(
            strategy = strategy,
            range = ContextRange.fullHistory(CHAT_ID, PLATFORM_UID),
            budget = budget,
            turns = listOf(
                ConversationTurn(
                    userMessage = MessageV2(id = lastUserMessageId, content = "最新的问题", platformType = null),
                    assistantMessage = null,
                    isCurrentTurn = true
                )
            ),
            nativeCompactionItemsJson = nativeItems,
            compactionState = compactionState
        )

        fun nativeState(
            itemsJson: String = "[]",
            isReadyForInput: Boolean = true,
            nativeUnsupported: Boolean = false
        ) = OpenAINativeCompactionState(
            chatRoomId = CHAT_ID,
            platformUid = PLATFORM_UID,
            compactionItemsJson = itemsJson,
            boundaryUserMessageId = LAST_USER_MESSAGE_ID,
            sourceTurnsHash = "hash",
            estimatedTokens = 12,
            isReadyForInput = isReadyForInput,
            nativeUnsupported = nativeUnsupported
        )

        fun itemDone(item: JsonObject) = OutputItemDoneEvent(outputIndex = 0, item = OutputItem(item))

        fun compactionItem(body: String) = buildJsonObject {
            put("type", NativeCompactionExchange.COMPACTION_ITEM_TYPE)
            put("id", "cmp_${body.hashCode()}")
            put("content", body)
        }

        fun messageItem(id: String, text: String) = buildJsonObject {
            put("type", "message")
            put("id", id)
            put("role", "assistant")
            put("content", text)
        }

        fun reasoningItem(id: String) = buildJsonObject {
            put("type", "reasoning")
            put("id", id)
        }

        fun functionCall(callId: String) = ResponseFunctionCallInputItem(
            callId = callId,
            name = "search",
            arguments = """{"q":"x"}"""
        )

        fun functionCallOutput(callId: String) = ResponseFunctionCallOutputItem(
            callId = callId,
            output = "result"
        )

        fun List<JsonObject>.jsonArrayString(): String = joinToString(prefix = "[", postfix = "]") { it.toString() }

        fun JsonObject.type(): String? = this["type"]?.jsonPrimitive?.contentOrNull

        fun JsonObject.callId(): String? = this["call_id"]?.jsonPrimitive?.contentOrNull
    }
}
