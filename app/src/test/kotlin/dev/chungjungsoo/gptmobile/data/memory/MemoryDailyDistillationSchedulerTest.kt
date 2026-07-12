package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDistillationCheckpoint
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDailyDistillationSchedulerTest {

    @Test
    fun `closed daily file creates one durable plan and excludes today and future`() = runBlocking {
        val fixture = fixture()
        fixture.writeDaily(LocalDate.parse("2026-07-11"), entry("yesterday", "Stable yesterday preference."))
        fixture.writeDaily(LocalDate.parse("2026-07-12"), entry("today", "Today is not closed."))
        fixture.writeDaily(LocalDate.parse("2026-07-13"), entry("future", "Future is not closed."))

        val result = fixture.scheduler.ensurePlanningJobs()

        val plan = fixture.jobDao.jobs.single { job ->
            job.type == MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION && job.nextRunAt == null
        }
        val payload = STRICT_JSON.decodeFromString<MemoryDailyDistillationPlanJobPayload>(plan.payloadJson)
        assertEquals(result.scheduledJobId, plan.jobId)
        assertEquals("memory/2026-07-11.md", payload.dailySourcePath)
        assertTrue(payload.batchKey!!.startsWith("batch_0000_"))
        assertEquals(listOf(EnqueuedMemoryWork(MemoryMaintenanceJobFamily.REPAIR, 0)), fixture.workEnqueuer.works)
        assertEquals(1, fixture.jobDao.jobs.count { job -> job.nextRunAt != null })
    }

    @Test
    fun `processing a current plan freezes one bounded semantic batch and checkpoint`() = runBlocking {
        val fixture = fixture()
        val entries = (1..25).map { index -> entry("entry_$index", "Stable preference number $index.") }
        fixture.writeDaily(LocalDate.parse("2026-07-11"), *entries.toTypedArray())
        fixture.scheduler.ensurePlanningJobs()
        val plan = fixture.currentBatchPlan()

        val result = fixture.scheduler.processPlan(plan)

        val semanticJob = fixture.jobDao.jobs.single { job -> job.type == MemoryMaintenanceJobType.DISTILL_DAILY_NOTES }
        val payload = STRICT_JSON.decodeFromString<MemoryDailyDistillationJobPayload>(semanticJob.payloadJson)
        val checkpoint = fixture.recoveryDao.getDistillationCheckpointBySemanticJobId(semanticJob.jobId)
        assertEquals(result.scheduledJobId, semanticJob.jobId)
        assertEquals(24, payload.input.dailyEvidence.size)
        assertEquals(payload.inputHash, STRICT_JSON.encodeToString(MemoryDailyDistillationFrozenInput.serializer(), payload.input).sha256Utf8())
        assertEquals(MemoryDistillationCheckpointStatus.PENDING, checkpoint?.status)
        assertEquals(payload.checkpointId, checkpoint?.checkpointId)
        assertEquals(payload.input.targetBaseHash, checkpoint?.targetBaseHash)
    }

    @Test
    fun `completed exact batch produces no second semantic job`() = runBlocking {
        val fixture = fixture()
        fixture.writeDaily(LocalDate.parse("2026-07-11"), entry("stable", "Stable preference."))
        fixture.scheduler.ensurePlanningJobs()
        fixture.scheduler.processPlan(fixture.currentBatchPlan())
        val checkpoint = checkNotNull(
            fixture.recoveryDao.getDistillationCheckpointBySemanticJobId(
                fixture.jobDao.jobs.single { job -> job.type == MemoryMaintenanceJobType.DISTILL_DAILY_NOTES }.jobId
            )
        )
        fixture.complete(checkpoint)
        val originalSemanticCount = fixture.jobDao.jobs.count { job -> job.type == MemoryMaintenanceJobType.DISTILL_DAILY_NOTES }

        val result = fixture.scheduler.ensurePlanningJobs()

        assertNull(result.scheduledCheckpointId)
        assertEquals(originalSemanticCount, fixture.jobDao.jobs.count { job -> job.type == MemoryMaintenanceJobType.DISTILL_DAILY_NOTES })
    }

    @Test
    fun `changed daily bytes create a new source identity`() = runBlocking {
        val fixture = fixture()
        val date = LocalDate.parse("2026-07-11")
        fixture.writeDaily(date, entry("first", "First stable preference."))
        fixture.scheduler.ensurePlanningJobs()
        fixture.scheduler.processPlan(fixture.currentBatchPlan())
        val firstCheckpoint = checkNotNull(
            fixture.recoveryDao.getDistillationCheckpointsByStatuses(
                listOf(MemoryDistillationCheckpointStatus.PENDING)
            ).single()
        )
        fixture.complete(firstCheckpoint)
        fixture.writeDaily(date, entry("second", "Second stable preference."))

        fixture.scheduler.ensurePlanningJobs()
        fixture.scheduler.processPlan(fixture.currentBatchPlan())

        val checkpoints = fixture.recoveryDao.getDistillationCheckpointsByStatuses(
            listOf(MemoryDistillationCheckpointStatus.PENDING, MemoryDistillationCheckpointStatus.COMPLETED)
        )
        assertEquals(2, checkpoints.size)
        assertEquals(2, checkpoints.map { checkpoint -> checkpoint.dailySourceHash }.distinct().size)
    }

    @Test
    fun `disabled memory schedules no plan and no semantic work`() = runBlocking {
        val fixture = fixture(memoryEnabled = false)
        fixture.writeDaily(LocalDate.parse("2026-07-11"), entry("stable", "Stable preference."))

        val result = fixture.scheduler.ensurePlanningJobs()

        assertNull(result.scheduledJobId)
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertTrue(fixture.workEnqueuer.works.isEmpty())
    }

    @Test
    fun `next wake uses the next local midnight`() = runBlocking {
        val fixture = fixture(clock = SHANGHAI_MIDNIGHT_CLOCK)

        val result = fixture.scheduler.ensurePlanningJobs()

        assertEquals(Instant.parse("2026-07-12T16:00:00Z").epochSecond, result.nextDailyPlanAt)
        val wake = fixture.jobDao.jobs.single()
        assertEquals(result.nextDailyPlanAt, wake.nextRunAt)
        assertEquals(MemoryMaintenanceJobFamily.REPAIR, wake.family)
    }

    private fun fixture(
        memoryEnabled: Boolean = true,
        clock: Clock = FIXED_CLOCK
    ): Fixture {
        val fileStore = MemoryFileStore(
            MemoryFilePaths(Files.createTempDirectory("daily-distillation-scheduler").toFile()),
            clock
        )
        fileStore.ensureStore().getOrThrow()
        val recoveryDao = InMemoryMemoryRecoveryDao()
        val jobDao = InMemoryMaintenanceJobDao()
        val workEnqueuer = RecordingWorkEnqueuer()
        val scheduler = MemoryDailyDistillationScheduler(
            memoryFileStore = fileStore,
            markdownMemoryCodec = MarkdownMemoryCodec(),
            recoveryDao = recoveryDao,
            maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, clock),
            settingRepository = FakeMaintenanceSettingRepository(memoryEnabled),
            workEnqueuer = workEnqueuer,
            clock = clock
        )
        return Fixture(fileStore, recoveryDao, jobDao, workEnqueuer, scheduler)
    }

    private fun entry(id: String, text: String) = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = "communication_style",
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = 1,
        updatedAt = 2
    )

    private data class Fixture(
        val fileStore: MemoryFileStore,
        val recoveryDao: InMemoryMemoryRecoveryDao,
        val jobDao: InMemoryMaintenanceJobDao,
        val workEnqueuer: RecordingWorkEnqueuer,
        val scheduler: MemoryDailyDistillationScheduler
    ) {
        fun writeDaily(date: LocalDate, vararg entries: MarkdownMemoryEntry) {
            val markdown = MarkdownMemoryCodec().renderDailyAppend(entries.toList())
            fileStore.appendDailyNote(markdown, date).getOrThrow()
        }

        fun currentBatchPlan() = jobDao.jobs.last { job ->
            job.type == MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION && job.nextRunAt == null
        }

        suspend fun complete(checkpoint: MemoryDistillationCheckpoint) {
            assertEquals(
                1,
                recoveryDao.transitionDistillationCheckpointCas(
                    checkpointId = checkpoint.checkpointId,
                    expectedStatus = checkpoint.status,
                    expectedRowVersion = checkpoint.rowVersion,
                    expectedDailySourcePath = checkpoint.dailySourcePath,
                    expectedDailySourceHash = checkpoint.dailySourceHash,
                    expectedBatchKey = checkpoint.batchKey,
                    expectedSemanticJobId = checkpoint.semanticJobId,
                    expectedTargetSourcePath = checkpoint.targetSourcePath,
                    expectedTargetBaseHash = checkpoint.targetBaseHash,
                    expectedTargetSourceHash = checkpoint.targetSourceHash,
                    expectedMutationGroupId = checkpoint.mutationGroupId,
                    newStatus = MemoryDistillationCheckpointStatus.COMPLETED,
                    newTargetSourceHash = checkpoint.targetSourceHash,
                    mutationGroupId = checkpoint.mutationGroupId,
                    updatedAt = checkpoint.updatedAt + 1,
                    processedAt = checkpoint.updatedAt + 1
                )
            )
        }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-07-12T02:00:00Z"),
            ZoneId.of("Asia/Shanghai")
        )
        val SHANGHAI_MIDNIGHT_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-07-12T15:59:59Z"),
            ZoneId.of("Asia/Shanghai")
        )
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
