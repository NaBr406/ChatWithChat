package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTurnBatchSchedulerTest {
    @Test
    fun `fifth completed turn creates one immutable threshold job`() = runBlocking {
        val fixture = fixture()
        val coordinator = MemoryTurnBatchCoordinator(fixture.turnDao, fixture.scheduler)

        (1..5).forEach { coordinator.recordCompletedTurn(input(CHAT_ONE, it)) }
        coordinator.recordCompletedTurn(input(CHAT_ONE, 5, assistantContent = "Updated answer"))

        assertEquals(1, fixture.jobDao.jobs.size)
        val job = fixture.jobDao.jobs.single()
        val payload = Json.decodeFromString<MemoryTurnBatchJobPayload>(job.payloadJson)
        assertEquals(MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH, job.type)
        assertEquals(MemoryTurnBatchTriggerReason.THRESHOLD, payload.triggerReason)
        assertEquals(5, payload.turns.size)
        assertEquals(5, fixture.turnDao.getTurnsClaimedByJob(job.jobId).size)
        assertEquals(0, fixture.turnDao.countUnclaimedTurns(CHAT_ONE))
        assertEquals(1, fixture.enqueuer.delays.count { it == 0L })
    }

    @Test
    fun `idle worker rechecks persisted deadline before creating a job`() = runBlocking {
        val fixture = fixture()
        MemoryTurnBatchCoordinator(fixture.turnDao).recordCompletedTurn(
            input(CHAT_ONE, 1, userCreatedAt = 100L, completedAt = 120L)
        )

        assertEquals(0, fixture.scheduler.promoteDueIdleBatches(now = 1_899L))
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertEquals(1, fixture.scheduler.promoteDueIdleBatches(now = 1_900L))
        assertEquals(1, fixture.jobDao.jobs.size)
        assertEquals(
            MemoryTurnBatchTriggerReason.IDLE,
            Json.decodeFromString<MemoryTurnBatchJobPayload>(fixture.jobDao.jobs.single().payloadJson).triggerReason
        )
    }

    @Test
    fun `new prompt postpones partial idle batch without a network job`() = runBlocking {
        val fixture = fixture()
        val coordinator = MemoryTurnBatchCoordinator(fixture.turnDao)
        coordinator.recordCompletedTurn(input(CHAT_ONE, 1, userCreatedAt = 100L, completedAt = 120L))

        coordinator.recordUserActivity(CHAT_ONE, activityAt = 500L)

        assertEquals(0, fixture.scheduler.promoteDueIdleBatches(now = 1_900L))
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertEquals(1, fixture.scheduler.promoteDueIdleBatches(now = 2_300L))
    }

    @Test
    fun `threshold and compaction triggers converge on one batch`() = runBlocking {
        val fixture = fixture()
        val coordinator = MemoryTurnBatchCoordinator(fixture.turnDao, fixture.scheduler)
        (1..5).forEach { coordinator.recordCompletedTurn(input(CHAT_ONE, it)) }

        val duplicate = fixture.scheduler.markCompactionUrgent(CHAT_ONE, lastOmittedUserMessageId = 5)

        assertNull(duplicate)
        assertEquals(1, fixture.jobDao.jobs.size)
    }

    @Test
    fun `ten recovered turns become two sequential batches`() = runBlocking {
        val fixture = fixture()
        val coordinator = MemoryTurnBatchCoordinator(fixture.turnDao)
        (1..10).forEach { coordinator.recordCompletedTurn(input(CHAT_ONE, it)) }

        val firstRepair = fixture.scheduler.repairAndSchedule()
        val firstJob = fixture.jobDao.jobs.single()
        assertEquals(1, firstRepair.thresholdBatchCount)
        assertEquals(5, fixture.turnDao.countUnclaimedTurns(CHAT_ONE))

        fixture.turnDao.completeClaimedBatch(firstJob.jobId, updatedAt = 2_000L)
        fixture.jobDao.update(firstJob.copy(status = MemoryMaintenanceJobStatus.SUCCEEDED))
        val secondRepair = fixture.scheduler.repairAndSchedule()

        assertEquals(1, secondRepair.thresholdBatchCount)
        assertEquals(2, fixture.jobDao.jobs.size)
        assertEquals(0, fixture.turnDao.countUnclaimedTurns(CHAT_ONE))
        assertEquals(5, fixture.turnDao.getTurnsClaimedByJob(fixture.jobDao.jobs.last().jobId).size)
    }

    @Test
    fun `later chat cannot postpone an earlier idle wake`() = runBlocking {
        val fixture = fixture()
        val coordinator = MemoryTurnBatchCoordinator(fixture.turnDao, fixture.scheduler)
        coordinator.recordCompletedTurn(input(CHAT_ONE, 1, userCreatedAt = 100L, completedAt = 120L))
        coordinator.recordCompletedTurn(input(CHAT_TWO, 1, userCreatedAt = 200L, completedAt = 220L))

        assertEquals(900L, fixture.enqueuer.delays.last())
    }

    @Test
    fun `repair recreates a missing job for claimed turns`() = runBlocking {
        val fixture = fixture()
        val coordinator = MemoryTurnBatchCoordinator(fixture.turnDao)
        (1..5).forEach { coordinator.recordCompletedTurn(input(CHAT_ONE, it)) }
        fixture.turnDao.claimOldestPendingTurns(CHAT_ONE, "orphan-job", 5, updatedAt = 1_000L)

        val result = fixture.scheduler.repairAndSchedule()

        assertEquals(1, result.repairedClaimCount)
        assertEquals("orphan-job", fixture.jobDao.jobs.single().jobId)
        assertEquals(
            MemoryTurnBatchTriggerReason.MANUAL_RETRY,
            Json.decodeFromString<MemoryTurnBatchJobPayload>(fixture.jobDao.jobs.single().payloadJson).triggerReason
        )
    }

    @Test
    fun `disabling memory dismisses unstarted batch and advances baseline`() = runBlocking {
        val fixture = fixture()
        val coordinator = MemoryTurnBatchCoordinator(fixture.turnDao, fixture.scheduler)
        (1..5).forEach { coordinator.recordCompletedTurn(input(CHAT_ONE, it)) }

        fixture.settingRepository.memoryEnabled = false
        val result = fixture.scheduler.onMemoryEnabledChanged(false)

        assertEquals(1, result.dismissedJobCount)
        assertEquals(5, result.discardedTurnCount)
        assertEquals(MemoryMaintenanceJobStatus.DISMISSED, fixture.jobDao.jobs.single().status)
        assertTrue(fixture.turnDao.getPendingTurnsForChat(CHAT_ONE).isEmpty())
        val checkpoint = fixture.turnDao.getCheckpoint(CHAT_ONE)!!
        assertEquals(checkpoint.lastObservedUserMessageId, checkpoint.lastProcessedUserMessageId)
        assertNull(checkpoint.idleDueAt)
    }

    private fun fixture(): Fixture {
        val turnDao = InMemoryMemoryTurnBatchDao()
        val jobDao = InMemoryMaintenanceJobDao()
        val enqueuer = RecordingWorkEnqueuer()
        val settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = true)
        val maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
        return Fixture(
            turnDao = turnDao,
            jobDao = jobDao,
            enqueuer = enqueuer,
            settingRepository = settingRepository,
            scheduler = MemoryTurnBatchScheduler(
                turnBatchDao = turnDao,
                maintenanceJobDao = jobDao,
                maintenanceScheduler = maintenanceScheduler,
                workEnqueuer = enqueuer,
                settingRepository = settingRepository,
                clock = FIXED_CLOCK
            )
        )
    }

    private fun input(
        chatId: Int,
        userMessageId: Int,
        assistantContent: String = "Answer $userMessageId",
        userCreatedAt: Long = userMessageId.toLong(),
        completedAt: Long = userMessageId.toLong() + 10L
    ): MemoryCompletedTurnInput = MemoryCompletedTurnInput(
        chatRoom = ChatRoomV2(id = chatId, title = "Chat $chatId", enabledPlatform = listOf("platform-1")),
        userMessage = MessageV2(
            id = userMessageId,
            chatId = chatId,
            content = "Question $userMessageId",
            platformType = null,
            createdAt = userCreatedAt
        ),
        assistantMessages = listOf(
            MessageV2(
                id = userMessageId + 100,
                chatId = chatId,
                content = assistantContent,
                platformType = "platform-1"
            )
        ),
        preferredPlatformUid = "platform-1",
        stablePlatformOrder = listOf("platform-1"),
        completedAt = completedAt
    )

    private data class Fixture(
        val turnDao: InMemoryMemoryTurnBatchDao,
        val jobDao: InMemoryMaintenanceJobDao,
        val enqueuer: RecordingWorkEnqueuer,
        val settingRepository: FakeMaintenanceSettingRepository,
        val scheduler: MemoryTurnBatchScheduler
    )

    companion object {
        private const val CHAT_ONE = 7
        private const val CHAT_TWO = 8
        private val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(1_000L), ZoneOffset.UTC)
    }
}
