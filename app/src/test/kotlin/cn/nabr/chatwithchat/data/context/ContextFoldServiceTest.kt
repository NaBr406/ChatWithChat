package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.InMemoryMemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.dao.MemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.dao.MessageV2Dao
import cn.nabr.chatwithchat.data.database.dao.PlatformModelV2Dao
import cn.nabr.chatwithchat.data.database.dao.PlatformV2Dao
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.MemoryPendingTurn
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.memory.FakeMaintenanceSettingRepository
import cn.nabr.chatwithchat.data.memory.InMemoryMaintenanceJobDao
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobFamily
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobStatus
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobType
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchScheduler
import cn.nabr.chatwithchat.data.memory.RecordingWorkEnqueuer
import cn.nabr.chatwithchat.data.model.ClientType
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executing a queued fold.
 *
 * The through-line is *persist only on success*, so most of these tests end by asserting that nothing
 * was written. The two failure modes are wildly asymmetric: a fold that does not happen costs some
 * tokens on the next request, while a fold that writes the wrong body corrupts a checkpoint the user
 * cannot see, will not know to distrust, and that every later segment then chains onto.
 */
class ContextFoldServiceTest {

    @Test
    fun `a successful fold appends exactly one segment and closes the job`() = runBlocking {
        val fixture = Fixture()

        val result = fixture.process(fixture.enqueueFold())

        assertEquals(ContextFoldProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.foldedTurnCount)
        assertEquals(1, fixture.checkpointRepository.appendCount)

        val segment = fixture.checkpointRepository.segments.single()
        assertEquals(0, segment.segmentIndex)
        assertEquals(10, segment.startUserMessageId)
        assertEquals(11, segment.endUserMessageId)
        assertEquals(CheckpointSegmentStatus.VALID, segment.status)
        // Compaction is real spend that belongs to no message row. Unrecorded, the session totals
        // would quietly understate what this feature costs.
        assertNotNull(segment.compactionUsage)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, fixture.job().status)
    }

    @Test
    fun `the stored segment carries the hash the job was queued for`() = runBlocking {
        val fixture = Fixture()
        val job = fixture.enqueueFold()
        val queuedHash = fixture.payloadOf(job).sourceTurnsHash

        fixture.process(job)

        // The next fold chains onto this hash. Storing a freshly recomputed one instead would let a
        // fold that verified batch A commit a segment claiming to describe batch B.
        assertEquals(queuedHash, fixture.checkpointRepository.segments.single().sourceTurnsHash)
    }

    @Test
    fun `a second segment is folded together with the first segment's body`() = runBlocking {
        val fixture = Fixture(messages = conversation(userIds = listOf(10, 11, 12, 13)))
        fixture.process(fixture.enqueueFold(startUserMessageId = 10, endUserMessageId = 11))

        val result = fixture.process(fixture.enqueueFold(startUserMessageId = 12, endUserMessageId = 13))

        assertEquals(ContextFoldProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(listOf(0, 1), fixture.checkpointRepository.segments.map { it.segmentIndex })
        // Segment 1 must summarise "segment 0's body plus these turns". Folding the new turns alone
        // would silently drop everything the chain had accumulated so far.
        assertFalse(fixture.compactionClient.calls.first().userPayload.contains(CHECKPOINT_MARKER))
        assertTrue(fixture.compactionClient.calls.last().userPayload.contains(CHECKPOINT_MARKER))
    }

    @Test
    fun `folding runs even when long term memory is switched off`() = runBlocking {
        val fixture = Fixture(memoryEnabled = false)

        val result = fixture.process(fixture.enqueueFold())

        // Context management is a separate feature that merely borrows the memory queue. If the
        // memory-enabled switch reached this job, users with memory off would lose compaction
        // entirely and hit the context window with no explanation.
        assertEquals(ContextFoldProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, fixture.checkpointRepository.appendCount)
        // ...and the switch stays where it belongs: inside the memory scheduler, not around the fold.
        assertTrue(fixture.turnBatchDao.urgentCalls.isEmpty())
    }

    @Test
    fun `a successful fold tells long term memory the batch has aged out`() = runBlocking {
        val fixture = Fixture()

        fixture.process(fixture.enqueueFold())

        // Compaction used to be what told memory a batch had aged out; folding inherits that role.
        // Without it the signal disappears silently and memory stops learning from older turns.
        assertEquals(listOf(CHAT_ID to 11), fixture.turnBatchDao.urgentCalls)
    }

    @Test
    fun `a failed fold never notifies memory`() = runBlocking {
        val fixture = Fixture(compactionResponse = Result.failure(IOException("provider down")))

        fixture.process(fixture.enqueueFold())

        // The turns are still in the raw context, so telling memory they aged out would consolidate
        // them early and then consolidate them again once the fold eventually succeeds.
        assertTrue(fixture.turnBatchDao.urgentCalls.isEmpty())
    }

    @Test
    fun `a memory nudge that fails does not undo the committed fold`() = runBlocking {
        val fixture = Fixture(failMemoryNudge = true)

        val result = fixture.process(fixture.enqueueFold())

        // The checkpoint is already written. Reporting this as failed would retry the job and fold
        // the same batch twice; losing one memory nudge is by far the cheaper failure.
        assertEquals(ContextFoldProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, fixture.checkpointRepository.appendCount)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, fixture.job().status)
    }

    @Test
    fun `a batch edited while the job was queued is abandoned rather than folded`() = runBlocking {
        val fixture = Fixture()
        val job = fixture.enqueueFold()

        fixture.editMessage(id = 11, content = "完全不同的问题")
        val result = fixture.process(job)

        // This is the entire reason the payload carries a hash instead of the text: the queued job
        // would otherwise summarise a message the user has since rewritten.
        assertEquals(ContextFoldProcessResult.STATUS_SUPERSEDED, result.status)
        assertEquals("context_fold_batch_changed", result.outcomeCode)
        assertEquals(0, fixture.checkpointRepository.appendCount)
        assertTrue(fixture.compactionClient.calls.isEmpty())
    }

    @Test
    fun `a batch whose turns were deleted is abandoned`() = runBlocking {
        val fixture = Fixture()
        val job = fixture.enqueueFold()

        fixture.deleteAllMessages()
        val result = fixture.process(job)

        assertEquals(ContextFoldProcessResult.STATUS_SUPERSEDED, result.status)
        assertEquals("context_fold_batch_empty", result.outcomeCode)
        assertEquals(0, fixture.checkpointRepository.appendCount)
    }

    @Test
    fun `a job whose slot was already taken is abandoned`() = runBlocking {
        val fixture = Fixture()
        val job = fixture.enqueueFold()

        // A racing fold landed first and now occupies segment 0.
        fixture.checkpointRepository.appendSegment(
            ConversationCheckpoint(
                chatRoomId = CHAT_ID,
                platformUid = PLATFORM_UID,
                segmentIndex = 0,
                startUserMessageId = 10,
                endUserMessageId = 11,
                body = "已经折叠过的内容",
                sourceTurnsHash = "raced",
                estimatedTokens = 40
            )
        )
        val result = fixture.process(job)

        assertEquals(ContextFoldProcessResult.STATUS_SUPERSEDED, result.status)
        assertEquals("context_fold_chain_moved", result.outcomeCode)
        // Only the racing write. This job added nothing on top of it.
        assertEquals(1, fixture.checkpointRepository.appendCount)
    }

    @Test
    fun `a job chained to a prefix that was invalidated is abandoned`() = runBlocking {
        val fixture = Fixture(
            segments = listOf(
                ConversationCheckpoint(
                    chatRoomId = CHAT_ID,
                    platformUid = PLATFORM_UID,
                    segmentIndex = 0,
                    startUserMessageId = 1,
                    endUserMessageId = 9,
                    body = "更早的折叠内容",
                    sourceTurnsHash = "segment-zero",
                    estimatedTokens = 50
                )
            )
        )
        val job = fixture.enqueueFold()

        fixture.checkpointRepository.invalidateFrom(CHAT_ID, PLATFORM_UID, userMessageId = 5)
        val result = fixture.process(job)

        // Segment 1's body would summarise "segment 0 plus these turns", and segment 0 no longer
        // describes the conversation correctly.
        assertEquals(ContextFoldProcessResult.STATUS_SUPERSEDED, result.status)
        assertEquals("context_fold_chain_moved", result.outcomeCode)
        assertEquals(0, fixture.checkpointRepository.appendCount)
    }

    @Test
    fun `an unreadable payload is dismissed instead of retried`() = runBlocking {
        val fixture = Fixture()

        val result = fixture.process(fixture.enqueueRawJob(payloadJson = "{not json"))

        assertEquals(ContextFoldProcessResult.STATUS_TERMINAL, result.status)
        assertEquals("context_fold_payload_unreadable", result.outcomeCode)
        assertEquals(MemoryMaintenanceJobStatus.DISMISSED, fixture.job().status)
    }

    @Test
    fun `a deleted platform is dismissed instead of retried`() = runBlocking {
        val fixture = Fixture()
        val job = fixture.enqueueFold()

        fixture.removePlatform()
        val result = fixture.process(job)

        // Retrying cannot bring the provider back, and the job would otherwise hold a queue slot for
        // three attempts before giving up.
        assertEquals(ContextFoldProcessResult.STATUS_TERMINAL, result.status)
        assertEquals("context_fold_platform_missing", result.outcomeCode)
        assertEquals(MemoryMaintenanceJobStatus.DISMISSED, fixture.job().status)
        assertEquals(0, fixture.checkpointRepository.appendCount)
    }

    @Test
    fun `a job for a natively compacting model is dismissed`() = runBlocking {
        val fixture = Fixture(platform = platform(clientType = ClientType.OPENAI, model = "gpt-5.3"))

        val result = fixture.process(fixture.enqueueFold())

        // Reaching here with such a job queued means the gate moved under us. Folding anyway would
        // send the app-side prompt to a model that already folds server-side, producing a second
        // competing summary of the same turns.
        assertEquals(ContextFoldProcessResult.STATUS_TERMINAL, result.status)
        assertEquals("context_fold_native_path_required", result.outcomeCode)
        assertTrue(fixture.compactionClient.calls.isEmpty())
    }

    @Test
    fun `an ineligible provider blocks the job on its dependency`() = runBlocking {
        val fixture = Fixture(platform = platform(token = null))

        val result = fixture.process(fixture.enqueueFold())

        // Not a retry: nothing changes until the user supplies a credential.
        assertEquals(ContextFoldProcessResult.STATUS_BLOCKED, result.status)
        assertEquals(ContextCompactionFailure.PROVIDER_NOT_ELIGIBLE.code, result.outcomeCode)
        assertEquals(MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY, fixture.job().status)
        assertEquals(0, fixture.checkpointRepository.appendCount)
    }

    @Test
    fun `a call failure is retryable and writes nothing`() = runBlocking {
        val fixture = Fixture(compactionResponse = Result.failure(IOException("connection reset")))

        val result = fixture.process(fixture.enqueueFold())

        assertEquals(ContextFoldProcessResult.STATUS_RETRYABLE, result.status)
        assertEquals(ContextCompactionFailure.CALL_FAILED.code, result.outcomeCode)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, fixture.job().status)
        assertEquals(0, fixture.checkpointRepository.appendCount)
    }

    @Test
    fun `the extractive fallback is deliberately not stored`() = runBlocking {
        val fixture = Fixture(compactionResponse = Result.failure(IOException("connection reset")))

        fixture.process(fixture.enqueueFold())

        // ContextCompactionService hands back a fallback summary, and the request assembler builds
        // one on its own at display time anyway. Persisting it here would turn a momentary network
        // failure into a permanently worse checkpoint that nothing ever revisits.
        assertTrue(fixture.checkpointRepository.segments.isEmpty())
    }

    @Test
    fun `exhausting the retries records a degradation for the pair`() = runBlocking {
        val fixture = Fixture(compactionResponse = Result.failure(IOException("provider down")))
        val job = fixture.enqueueFold()

        var last = fixture.process(job)
        repeat(2) { retry ->
            last = fixture.process(fixture.reclaim(job.jobId, afterSeconds = (retry + 1) * 1_000L))
        }

        assertEquals(ContextFoldProcessResult.STATUS_TERMINAL, last.status)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, fixture.job().status)
        // Every turn hashes a different batch, so the queue's idempotency never collides. Without
        // this record the next send simply queues a brand new job against the same dead provider.
        val degradation = fixture.degradationRegistry.current(CHAT_ID, PLATFORM_UID)
        assertNotNull(degradation)
        assertEquals(ContextCompactionFailure.CALL_FAILED.code, degradation!!.reason)
        assertEquals(0, fixture.checkpointRepository.appendCount)
    }

    @Test
    fun `a fold that succeeds after failures clears the degradation`() = runBlocking {
        val fixture = Fixture()
        fixture.degradationRegistry.recordBackgroundFoldExhausted(
            CHAT_ID,
            PLATFORM_UID,
            ContextCompactionFailure.TIMED_OUT.code
        )

        fixture.process(fixture.enqueueFold())

        // A pair that folds again is not degraded, whatever it did before.
        assertNull(fixture.degradationRegistry.current(CHAT_ID, PLATFORM_UID))
    }

    @Test
    fun `a cancelled worker leaves the job claimed rather than burning an attempt`() = runBlocking {
        val fixture = Fixture(cancelDuringCompaction = true)
        val job = fixture.enqueueFold()

        var propagated = false
        try {
            fixture.process(job)
        } catch (_: CancellationException) {
            propagated = true
        }

        // The lease watchdog releases it and it runs again intact. Marking it failed here would
        // spend one of three attempts on a fold that never reached the provider.
        assertTrue(propagated)
        assertEquals(MemoryMaintenanceJobStatus.RUNNING, fixture.job().status)
        assertEquals(0, fixture.checkpointRepository.appendCount)
    }

    private class Fixture(
        messages: List<MessageV2> = conversation(userIds = listOf(10, 11)),
        segments: List<ConversationCheckpoint> = emptyList(),
        private val platform: PlatformV2 = platform(),
        memoryEnabled: Boolean = true,
        failMemoryNudge: Boolean = false,
        compactionResponse: Result<ContextCompactionModelResponse> =
            Result.success(ContextCompactionModelResponse(COMPACTED_BODY)),
        cancelDuringCompaction: Boolean = false
    ) {
        val jobDao = InMemoryMaintenanceJobDao()
        val checkpointRepository = InMemoryContextCheckpointRepository(initialSegments = segments)
        val compactionClient = RecordingCompactionClient(compactionResponse, cancelDuringCompaction)
        val degradationRegistry = ContextFoldDegradationRegistry(clock = CLOCK)

        /**
         * [MemoryTurnBatchScheduler] is a final class, so the memory nudge is observed one level down
         * instead: `getUnclaimedTurnsThrough` is the first thing `markCompactionUrgent` touches after
         * its own memory-enabled check, which makes it an exact witness for "the nudge fired".
         */
        val turnBatchDao = RecordingTurnBatchDao(InMemoryMemoryTurnBatchDao(), failOnUrgent = failMemoryNudge)

        private val messageDao = InMemoryMessageDao(messages)
        private val platformDao = InMemoryPlatformDao(platform)
        private val contextBuilder = ContextBuilder()
        private val maintenanceScheduler = MemoryMaintenanceScheduler(jobDao = jobDao, clock = CLOCK)

        private val service = ContextFoldService(
            messageDao = messageDao,
            platformDao = platformDao,
            contextBuilder = contextBuilder,
            checkpointRepository = checkpointRepository,
            compactionService = ContextCompactionService(compactionClient),
            contextWindowResolver = ContextWindowResolver(InMemoryPlatformModelDao()),
            maintenanceScheduler = maintenanceScheduler,
            memoryTurnBatchScheduler = MemoryTurnBatchScheduler(
                turnBatchDao = turnBatchDao,
                maintenanceJobDao = jobDao,
                maintenanceScheduler = maintenanceScheduler,
                workEnqueuer = RecordingWorkEnqueuer(),
                settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = memoryEnabled),
                clock = CLOCK
            ),
            degradationRegistry = degradationRegistry
        )

        suspend fun process(job: MemoryMaintenanceJob): ContextFoldProcessResult = service.process(job)

        /** The fold job. Named by type so a memory job enqueued as a side effect cannot hide here. */
        fun job(): MemoryMaintenanceJob =
            jobDao.jobs.single { it.type == MemoryMaintenanceJobType.FOLD_CONTEXT_CHECKPOINT }

        fun payloadOf(job: MemoryMaintenanceJob): ContextFoldJobPayload =
            Json.decodeFromString(job.payloadJson)

        /** Queues a fold exactly as [ContextFoldScheduler] would, then claims it as the worker does. */
        suspend fun enqueueFold(
            startUserMessageId: Int = 10,
            endUserMessageId: Int = 11
        ): MemoryMaintenanceJob {
            val chain = checkpointRepository.loadChain(CHAT_ID, PLATFORM_UID)
            val previousHash = chain.validSegments.lastOrNull()?.sourceTurnsHash
            val turns = foldableTurns(startUserMessageId, endUserMessageId)
            return enqueueRawJob(
                payloadJson = Json.encodeToString(
                    ContextFoldJobPayload(
                        chatRoomId = CHAT_ID,
                        platformUid = PLATFORM_UID,
                        segmentIndex = chain.nextSegmentIndex,
                        startUserMessageId = startUserMessageId,
                        endUserMessageId = endUserMessageId,
                        previousSegmentHash = previousHash,
                        sourceTurnsHash = ContextTurnHasher.hashSegment(previousHash, turns),
                        createdAt = NOW.epochSecond
                    )
                )
            )
        }

        suspend fun enqueueRawJob(payloadJson: String): MemoryMaintenanceJob {
            val job = maintenanceScheduler.enqueue(
                type = MemoryMaintenanceJobType.FOLD_CONTEXT_CHECKPOINT,
                idempotencyKey = "context_fold:${UUID.randomUUID()}",
                payloadJson = payloadJson
            )
            return reclaim(job.jobId)
        }

        /**
         * Claims the job so it carries a live lease, as the processor does before every run.
         *
         * [afterSeconds] steps past the backoff a previous failure set: `markSucceeded` and
         * `markFailedRetryable` both go through the lease CAS, and a job whose `nextRunAt` is still
         * in the future cannot be re-claimed at all.
         */
        suspend fun reclaim(jobId: String, afterSeconds: Long = 0L): MemoryMaintenanceJob = checkNotNull(
            maintenanceScheduler.claimRunnableJob(
                jobId = jobId,
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                leaseOwner = "test-worker",
                now = NOW.epochSecond + afterSeconds
            )
        ) { "job $jobId was not runnable" }

        fun editMessage(id: Int, content: String) {
            messageDao.replace(id) { it.copy(content = content) }
        }

        fun deleteAllMessages() {
            messageDao.clear()
        }

        fun removePlatform() {
            platformDao.platforms.clear()
        }

        /**
         * Pairs turns the way the service will.
         *
         * Delegating to the real [ContextBuilder] is the point: assistant text is sanitized before it
         * is hashed, so a hash computed over hand-paired raw rows would never match the one the
         * service verifies and every test would land on `context_fold_batch_changed`.
         */
        private suspend fun foldableTurns(start: Int, end: Int): List<ConversationTurn> {
            val ordered = messageDao.loadMessages(CHAT_ID).sortedBy { it.createdAt }
            val userMessages = mutableListOf<MessageV2>()
            val assistantMessages = mutableListOf<MutableList<MessageV2>>()
            ordered.forEach { message ->
                if (message.platformType == null) {
                    userMessages += message
                    assistantMessages.add(mutableListOf())
                } else {
                    assistantMessages.lastOrNull()?.add(message)
                }
            }
            return contextBuilder
                .buildFoldableTurns(userMessages, assistantMessages, platform.uid)
                .filter { it.userMessage.id in start..end }
        }
    }

    private class RecordedCompactionCall(val userPayload: String)

    private class RecordingCompactionClient(
        private val response: Result<ContextCompactionModelResponse>,
        private val cancel: Boolean
    ) : ContextCompactionModelClient {
        val calls = mutableListOf<RecordedCompactionCall>()

        override suspend fun requestCompaction(
            platform: PlatformV2,
            systemPrompt: String,
            userPayload: String,
            maxOutputTokens: Int,
            timeoutSeconds: Int
        ): Result<ContextCompactionModelResponse> {
            // Thrown before recording, so a cancelled run leaves no trace of having called out.
            if (cancel) throw CancellationException("worker torn down")
            calls += RecordedCompactionCall(userPayload)
            return response
        }
    }

    /**
     * Records the one call [MemoryTurnBatchScheduler.markCompactionUrgent] makes past its own gate,
     * and can fail it, so the fold's "the checkpoint is committed, swallow this" rule is testable.
     */
    private class RecordingTurnBatchDao(
        private val delegate: MemoryTurnBatchDao,
        private val failOnUrgent: Boolean = false
    ) : MemoryTurnBatchDao by delegate {
        val urgentCalls = mutableListOf<Pair<Int, Int>>()

        override suspend fun getUnclaimedTurnsThrough(
            chatId: Int,
            lastUserMessageId: Int
        ): List<MemoryPendingTurn> {
            urgentCalls += chatId to lastUserMessageId
            if (failOnUrgent) throw IllegalStateException("turn batch storage unavailable")
            return delegate.getUnclaimedTurnsThrough(chatId, lastUserMessageId)
        }
    }

    private class InMemoryMessageDao(initialRows: List<MessageV2>) : MessageV2Dao {
        private val rows = initialRows.toMutableList()

        override suspend fun loadMessages(chatInt: Int): List<MessageV2> = rows.filter { it.chatId == chatInt }

        override suspend fun findChatIdByInitialRequestId(initialRequestId: Int): Int? = null

        override suspend fun searchMessagesByContent(query: String): List<Int> = emptyList()

        override suspend fun countStickerAssetReferences(assetKey: String): Int = 0

        override suspend fun addMessages(vararg messages: MessageV2) {
            rows += messages
        }

        override suspend fun editMessages(vararg message: MessageV2) {
            message.forEach { edited -> replace(edited.id) { edited } }
        }

        override suspend fun deleteMessages(vararg message: MessageV2) {
            rows.removeAll { row -> message.any { it.id == row.id } }
        }

        fun replace(id: Int, transform: (MessageV2) -> MessageV2) {
            val index = rows.indexOfFirst { it.id == id }
            if (index != -1) rows[index] = transform(rows[index])
        }

        fun clear() {
            rows.clear()
        }
    }

    private class InMemoryPlatformDao(platform: PlatformV2) : PlatformV2Dao {
        val platforms = mutableListOf(platform)

        override suspend fun getPlatforms(): List<PlatformV2> = platforms.toList()

        override suspend fun getPlatform(id: Int): PlatformV2? = platforms.firstOrNull { it.id == id }

        override suspend fun addPlatform(platform: PlatformV2): Long = 0

        override suspend fun editPlatform(platform: PlatformV2) = Unit

        override suspend fun deletePlatform(platform: PlatformV2) {
            platforms.remove(platform)
        }
    }

    /** Empty on purpose: an unknown model falls back to the provider policy's default window. */
    private class InMemoryPlatformModelDao : PlatformModelV2Dao {
        override suspend fun getModels(): List<PlatformModelV2> = emptyList()

        override suspend fun getModelsForPlatform(platformUid: String): List<PlatformModelV2> = emptyList()

        override suspend fun getModel(platformUid: String, modelId: String): PlatformModelV2? = null

        override suspend fun upsertModels(models: List<PlatformModelV2>) = Unit

        override suspend fun updateEnabled(
            platformUid: String,
            modelId: String,
            enabled: Boolean,
            updatedAt: Long
        ) = Unit

        override suspend fun setDefault(platformUid: String, modelId: String, updatedAt: Long) = Unit

        override suspend fun clearDefault(platformUid: String, modelId: String, updatedAt: Long) = Unit

        override suspend fun deleteByPlatformUid(platformUid: String) = Unit
    }

    private companion object {
        const val CHAT_ID = 42
        const val PLATFORM_UID = "platform"
        val NOW: Instant = Instant.ofEpochSecond(1_700_000_000L)
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        /** A phrase that appears only in a stored checkpoint, never in the raw turns. */
        const val CHECKPOINT_MARKER = "用户正在讨论差旅报销流程"

        val COMPACTED_BODY = """
            ## 目标与约束
            $CHECKPOINT_MARKER，需要在月底前完成。

            ## 已确认的决定
            采用线上提交方式。

            ## 未解决的问题
            超额部分的审批人尚未确定。

            ## 关键结果与引用
            报销上限为每日 500 元。

            ## 事实与推断的区分
            上限是用户明确说明的事实；审批流程是推断。
        """.trimIndent()

        fun conversation(userIds: List<Int>): List<MessageV2> = userIds.flatMapIndexed { index, id ->
            listOf(
                MessageV2(
                    id = id,
                    chatId = CHAT_ID,
                    content = "第 $id 轮的问题",
                    platformType = null,
                    createdAt = (index * 2).toLong()
                ),
                MessageV2(
                    id = 1_000 + id,
                    chatId = CHAT_ID,
                    content = "第 $id 轮的回答",
                    platformType = PLATFORM_UID,
                    createdAt = (index * 2 + 1).toLong()
                )
            )
        }

        fun platform(
            clientType: ClientType = ClientType.CUSTOM,
            model: String = "custom-model",
            token: String? = "token"
        ) = PlatformV2(
            uid = PLATFORM_UID,
            name = "Provider",
            compatibleType = clientType,
            apiUrl = "https://example.com",
            token = token,
            model = model
        )
    }
}
