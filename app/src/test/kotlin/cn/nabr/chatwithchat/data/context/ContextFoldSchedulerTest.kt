package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.memory.InMemoryMaintenanceJobDao
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobFamily
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobType
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler
import cn.nabr.chatwithchat.data.memory.RecordingWorkEnqueuer
import cn.nabr.chatwithchat.data.model.ClientType
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding when a fold gets queued.
 *
 * This runs on the send path, so the interesting assertions are the ones about *not* queuing. The
 * scheduler is called on every single turn of every conversation; each unnecessary job is a
 * provider call the user did not ask for and did not see.
 */
class ContextFoldSchedulerTest {

    @Test
    fun `crossing the pre-fold line queues one fold and wakes the worker`() = runBlocking {
        val fixture = Fixture()

        val job = fixture.enqueue(result = foldableResult())

        assertEquals(MemoryMaintenanceJobType.FOLD_CONTEXT_CHECKPOINT, job?.type)
        assertEquals(1, fixture.jobDao.jobs.size)
        // Queuing without waking the worker would leave the fold sitting until some unrelated job
        // happened to run the family.
        assertEquals(listOf(MemoryMaintenanceJobFamily.SEMANTIC), fixture.workEnqueuer.works.map { it.family })
    }

    @Test
    fun `the payload identifies the batch without embedding any message text`() = runBlocking {
        val fixture = Fixture()
        val turns = listOf(
            turn(10, content = "报销单据的金额是多少"),
            turn(11, content = "第二个问题"),
            turn(12, content = "第三个问题")
        )

        val job = fixture.enqueue(result = foldableResult(pendingFoldTurns = turns))

        val payload = Json.decodeFromString<ContextFoldJobPayload>(job!!.payloadJson)
        assertEquals(CHAT_ID, payload.chatRoomId)
        assertEquals(PLATFORM_UID, payload.platformUid)
        assertEquals(0, payload.segmentIndex)
        assertEquals(10, payload.startUserMessageId)
        assertEquals(12, payload.endUserMessageId)
        assertNull(payload.previousSegmentHash)
        assertEquals(ContextTurnHasher.hashSegment(null, turns), payload.sourceTurnsHash)
        assertEquals(NOW.epochSecond, payload.createdAt)
        // Turns are re-read when the job runs. Carrying the text here would let a fold summarise a
        // message the user edited or deleted while the job sat in the queue — and would put
        // conversation content in a row that outlives the message it came from.
        assertFalse(job.payloadJson.contains("报销单据的金额是多少"))
        assertFalse(job.payloadJson.contains("answer-10"))
    }

    @Test
    fun `the payload chains onto the last valid segment`() = runBlocking {
        val existing = segment(index = 0, endUserMessageId = 9, hash = "segment-zero-hash")
        val fixture = Fixture(segments = listOf(existing))
        val turns = listOf(turn(10), turn(11))

        val job = fixture.enqueue(result = foldableResult(pendingFoldTurns = turns))

        val payload = Json.decodeFromString<ContextFoldJobPayload>(job!!.payloadJson)
        assertEquals(1, payload.segmentIndex)
        assertEquals("segment-zero-hash", payload.previousSegmentHash)
        assertEquals(ContextTurnHasher.hashSegment("segment-zero-hash", turns), payload.sourceTurnsHash)
    }

    @Test
    fun `the chain is re-read at enqueue time rather than trusted from the result`() = runBlocking {
        val fixture = Fixture()
        val turns = listOf(turn(10), turn(11))

        // A fold queued a moment ago already advanced the chain; the result in hand predates it.
        fixture.checkpointRepository.appendSegment(segment(index = 0, endUserMessageId = 9, hash = "raced"))
        val job = fixture.enqueue(result = foldableResult(pendingFoldTurns = turns))

        val payload = Json.decodeFromString<ContextFoldJobPayload>(job!!.payloadJson)
        assertEquals(1, payload.segmentIndex)
        assertEquals("raced", payload.previousSegmentHash)
    }

    @Test
    fun `re-sending the same batch dedupes onto one job`() = runBlocking {
        val fixture = Fixture()
        val result = foldableResult(pendingFoldTurns = listOf(turn(10), turn(11)))

        val first = fixture.enqueue(result = result)
        val second = fixture.enqueue(result = result)

        assertEquals(first?.jobId, second?.jobId)
        assertEquals(1, fixture.jobDao.jobs.size)
    }

    @Test
    fun `a genuinely different batch gets its own job`() = runBlocking {
        val fixture = Fixture()

        fixture.enqueue(result = foldableResult(pendingFoldTurns = listOf(turn(10), turn(11))))
        fixture.enqueue(result = foldableResult(pendingFoldTurns = listOf(turn(10), turn(11), turn(12))))

        assertEquals(2, fixture.jobDao.jobs.size)
        assertNotEquals(fixture.jobDao.jobs[0].idempotencyKey, fixture.jobDao.jobs[1].idempotencyKey)
    }

    @Test
    fun `an edited turn is a different batch even at the same boundaries`() = runBlocking {
        val fixture = Fixture()

        fixture.enqueue(result = foldableResult(pendingFoldTurns = listOf(turn(10), turn(11))))
        fixture.enqueue(
            result = foldableResult(pendingFoldTurns = listOf(turn(10), turn(11, content = "edited")))
        )

        // The boundary ids are identical, so a key built from ids alone would have collapsed these
        // into one job and folded stale text.
        assertEquals(2, fixture.jobDao.jobs.size)
    }

    @Test
    fun `nothing is queued for a batch too small to be worth a provider call`() = runBlocking {
        val fixture = Fixture()

        val job = fixture.enqueue(
            result = foldableResult(pendingFoldTokens = BUDGET.backgroundFoldMinBatchTokens - 1)
        )

        assertNull(job)
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertTrue(fixture.workEnqueuer.works.isEmpty())
    }

    @Test
    fun `nothing is queued when there is no batch to fold`() = runBlocking {
        val fixture = Fixture()

        val job = fixture.enqueue(result = foldableResult(pendingFoldTurns = emptyList()))

        assertNull(job)
        assertTrue(fixture.jobDao.jobs.isEmpty())
    }

    @Test
    fun `an unsaved chat never queues a fold`() = runBlocking {
        val fixture = Fixture()

        val job = fixture.enqueue(chatRoomId = 0, result = foldableResult())

        // Without a chat row the job could never re-read its turns, so it would fail every attempt.
        assertNull(job)
        assertTrue(fixture.jobDao.jobs.isEmpty())
    }

    @Test
    fun `a natively compacting model never queues an app side fold`() = runBlocking {
        val fixture = Fixture()

        val job = fixture.enqueue(
            platform = platform(clientType = ClientType.OPENAI, model = "gpt-5.3"),
            result = foldableResult()
        )

        // The provider folds server-side inside the ordinary request; an app-side job would produce
        // a second, competing summary of the same turns.
        assertNull(job)
        assertTrue(fixture.jobDao.jobs.isEmpty())
    }

    @Test
    fun `a degraded pair stops queuing folds during its cooldown`() = runBlocking {
        val clock = MutableClock()
        val fixture = Fixture(clock = clock, degradationRegistry = registry(clock))
        fixture.degradationRegistry!!.recordBackgroundFoldExhausted(
            CHAT_ID,
            PLATFORM_UID,
            "context_compaction_call_failed"
        )

        val job = fixture.enqueue(result = foldableResult())

        // Every turn hashes a different batch, so without this the queue's idempotency never collides
        // and a dead provider gets a brand new job — three attempts each — on every single send.
        assertNull(job)
        assertTrue(fixture.jobDao.jobs.isEmpty())
    }

    @Test
    fun `one probe is queued once the cooldown expires`() = runBlocking {
        val clock = MutableClock()
        val fixture = Fixture(clock = clock, degradationRegistry = registry(clock))
        fixture.degradationRegistry!!.recordBackgroundFoldExhausted(
            CHAT_ID,
            PLATFORM_UID,
            "context_compaction_timed_out"
        )

        clock.advanceSeconds(COOLDOWN)

        assertNotNull(fixture.enqueue(result = foldableResult(pendingFoldTurns = listOf(turn(10)))))
        assertNull(fixture.enqueue(result = foldableResult(pendingFoldTurns = listOf(turn(11)))))
    }

    @Test
    fun `a request that bails out for another reason does not consume the probe`() = runBlocking {
        val clock = MutableClock()
        val fixture = Fixture(clock = clock, degradationRegistry = registry(clock))
        val degradation = fixture.degradationRegistry!!
        degradation.recordBackgroundFoldExhausted(CHAT_ID, PLATFORM_UID, "context_compaction_call_failed")
        clock.advanceSeconds(COOLDOWN)

        // Too small a batch, so this send was never going to fold. Claiming the slot here would
        // refresh the cooldown and burn the one probe the window allows on a request that did nothing.
        assertNull(
            fixture.enqueue(
                result = foldableResult(pendingFoldTokens = BUDGET.backgroundFoldMinBatchTokens - 1)
            )
        )

        assertNotNull(fixture.enqueue(result = foldableResult()))
    }

    @Test
    fun `a scheduler without a degradation registry still queues normally`() = runBlocking {
        // The registry is optional plumbing. Without it the scheduler must behave exactly as it did
        // before it existed — wasteful against a dead provider, but never wrong.
        val fixture = Fixture(degradationRegistry = null)
        fixture.enqueue(result = foldableResult(pendingFoldTurns = listOf(turn(10))))

        val job = fixture.enqueue(result = foldableResult(pendingFoldTurns = listOf(turn(11))))

        assertNotNull(job)
        assertEquals(2, fixture.jobDao.jobs.size)
    }

    private class Fixture(
        segments: List<ConversationCheckpoint> = emptyList(),
        val clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC),
        val degradationRegistry: ContextFoldDegradationRegistry? = null
    ) {
        val jobDao = InMemoryMaintenanceJobDao()
        val workEnqueuer = RecordingWorkEnqueuer()
        val checkpointRepository = InMemoryContextCheckpointRepository(initialSegments = segments)
        val scheduler = ContextFoldScheduler(
            maintenanceScheduler = MemoryMaintenanceScheduler(jobDao = jobDao, clock = clock),
            checkpointRepository = checkpointRepository,
            workEnqueuer = workEnqueuer,
            degradationRegistry = degradationRegistry,
            clock = clock
        )

        suspend fun enqueue(
            chatRoomId: Int = CHAT_ID,
            platform: PlatformV2 = platform(),
            result: ContextBuildResult
        ) = scheduler.enqueueFoldIfNeeded(chatRoomId, platform, result)
    }

    /** A [Clock] whose instant only moves when a test moves it, so cooldowns are exact. */
    private class MutableClock(
        private var current: Instant = NOW,
        private val zone: ZoneId = ZoneOffset.UTC
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }

    private companion object {
        const val CHAT_ID = 42
        const val PLATFORM_UID = "platform"
        const val COOLDOWN = 600L
        val NOW: Instant = Instant.ofEpochSecond(1_700_000_000L)
        val BUDGET: ContextBudget = ContextBudget.of(modelContextWindow = 32_768, providerSummaryTarget = 1_200)

        fun registry(clock: Clock) = ContextFoldDegradationRegistry(clock = clock, cooldownSeconds = COOLDOWN)

        fun foldableResult(
            pendingFoldTokens: Int = BUDGET.backgroundFoldMinBatchTokens,
            pendingFoldTurns: List<ConversationTurn> = listOf(turn(10), turn(11))
        ) = ContextBuildResult(
            strategy = ContextStrategy.AUTO_COMPACT_BALANCED,
            range = ContextRange.fullHistory(CHAT_ID, PLATFORM_UID),
            budget = BUDGET,
            pendingFoldTurns = pendingFoldTurns,
            pendingFoldTokens = pendingFoldTokens,
            // Deliberately a bounded, unremarkable request size: what decides a fold is the backlog in
            // pendingFoldTokens, never how large the request being sent happens to be.
            estimatedTokens = BUDGET.recentRawTail,
            compactionState = ContextCompactionState.PENDING_BACKGROUND_FOLD
        )

        fun turn(userId: Int, content: String = "question-$userId") = ConversationTurn(
            userMessage = MessageV2(id = userId, content = content, platformType = null),
            assistantMessage = MessageV2(id = 1_000 + userId, content = "answer-$userId", platformType = PLATFORM_UID),
            isCurrentTurn = false
        )

        fun segment(index: Int, endUserMessageId: Int, hash: String) = ConversationCheckpoint(
            chatRoomId = CHAT_ID,
            platformUid = PLATFORM_UID,
            segmentIndex = index,
            startUserMessageId = 1,
            endUserMessageId = endUserMessageId,
            body = "folded body",
            sourceTurnsHash = hash,
            estimatedTokens = 120
        )

        fun platform(
            clientType: ClientType = ClientType.CUSTOM,
            model: String = "custom-model"
        ) = PlatformV2(
            uid = PLATFORM_UID,
            name = "Provider",
            compatibleType = clientType,
            apiUrl = "https://example.com",
            token = "token",
            model = model
        )
    }
}
