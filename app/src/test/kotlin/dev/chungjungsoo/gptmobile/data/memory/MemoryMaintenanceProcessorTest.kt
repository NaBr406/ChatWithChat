package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.AvailableChatModel
import dev.chungjungsoo.gptmobile.data.model.LastSelectedModel
import dev.chungjungsoo.gptmobile.data.model.ModelRefreshResult
import dev.chungjungsoo.gptmobile.data.model.ReasoningMode
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.tool.ToolCallingMode
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchMode
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMaintenanceProcessorTest {

    @Test
    fun `processor dismisses retired room index jobs without index access`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(
            listOf(
                job(MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX),
                job(MemoryMaintenanceJobType.REPAIR_MARKDOWN_METADATA)
            )
        )
        val processor = createProcessor(jobDao = jobDao)

        val indexResult = processor.processRunnableJobs(MemoryMaintenanceJobFamily.INDEX)
        val repairResult = processor.processRunnableJobs(MemoryMaintenanceJobFamily.REPAIR)

        assertEquals(1, indexResult.terminalCount)
        assertEquals(1, repairResult.terminalCount)
        assertTrue(jobDao.jobs.all { it.status == MemoryMaintenanceJobStatus.DISMISSED })
        assertTrue(jobDao.jobs.all { it.lastError == MemoryMaintenanceProcessor.LEGACY_ROOM_INDEX_DISMISS_REASON })
    }

    @Test
    fun `vector index sync success marks job succeeded`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.SYNC_VECTOR_INDEX)))
        val processor = createProcessor(
            jobDao = jobDao,
            memoryIndexSyncService = syncServiceReturning(MemoryIndexSyncResult.Succeeded)
        )

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.INDEX)

        assertEquals(1, result.succeededCount)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, jobDao.jobs.single().status)
    }

    @Test
    fun `superseded vector index sync marks job succeeded`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.SYNC_VECTOR_INDEX)))
        val processor = createProcessor(
            jobDao = jobDao,
            memoryIndexSyncService = syncServiceReturning(MemoryIndexSyncResult.Superseded)
        )

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.INDEX)

        assertEquals(1, result.succeededCount)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, jobDao.jobs.single().status)
    }

    @Test
    fun `retryable vector index sync marks job retryable`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.SYNC_VECTOR_INDEX)))
        val processor = createProcessor(
            jobDao = jobDao,
            memoryIndexSyncService = syncServiceReturning(MemoryIndexSyncResult.Retryable("embedding_provider_loading"))
        )

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.INDEX)

        assertEquals(1, result.retryableCount)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, jobDao.jobs.single().status)
        assertEquals("embedding_provider_loading", jobDao.jobs.single().lastError)
    }

    @Test
    fun `blocked vector index sync marks dependency blocked`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.SYNC_VECTOR_INDEX)))
        val processor = createProcessor(
            jobDao = jobDao,
            memoryIndexSyncService = syncServiceReturning(MemoryIndexSyncResult.BlockedDependency("artifact_missing"))
        )

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.INDEX)

        assertEquals(1, result.blockedCount)
        assertEquals(MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY, jobDao.jobs.single().status)
        assertEquals("artifact_missing", jobDao.jobs.single().blockedReason)
    }

    @Test
    fun `terminal vector index sync marks job terminal`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.SYNC_VECTOR_INDEX)))
        val processor = createProcessor(
            jobDao = jobDao,
            memoryIndexSyncService = syncServiceReturning(MemoryIndexSyncResult.Terminal("vector_manifest_conflict"))
        )

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.INDEX)

        assertEquals(1, result.terminalCount)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, jobDao.jobs.single().status)
        assertEquals("vector_manifest_conflict", jobDao.jobs.single().lastError)
    }

    @Test
    fun `missing vector index sync service blocks job explicitly`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.SYNC_VECTOR_INDEX)))
        val processor = createProcessor(jobDao = jobDao)

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.INDEX)

        assertEquals(1, result.blockedCount)
        assertEquals(MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY, jobDao.jobs.single().status)
        assertEquals("vector_index_synchronizer_not_available", jobDao.jobs.single().blockedReason)
    }

    @Test
    fun `processor retries distillation when its service is unavailable`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.DISTILL_DAILY_NOTES)))
        val processor = createProcessor(jobDao = jobDao)

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.SEMANTIC)

        assertEquals(1, result.retryableCount)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, jobDao.jobs.single().status)
        assertEquals(
            "daily_distillation_not_available",
            jobDao.jobs.single().lastError
        )
    }

    @Test
    fun `batch consolidation becomes terminal after three automatic attempts`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH)))
        val processor = createProcessor(jobDao = jobDao)

        repeat(3) {
            processor.processRunnableJobs(MemoryMaintenanceJobFamily.SEMANTIC)
            val currentJob = jobDao.jobs.single()
            if (currentJob.status == MemoryMaintenanceJobStatus.FAILED_RETRYABLE) {
                jobDao.forceUpdate(currentJob.copy(nextRunAt = 1_000L))
            }
        }

        val terminalJob = jobDao.jobs.single()
        assertEquals(3, terminalJob.attempts)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, terminalJob.status)
        assertEquals("batch_consolidation_pending", terminalJob.lastError)
        assertEquals(0, processor.processRunnableJobs(MemoryMaintenanceJobFamily.SEMANTIC).processedCount)
    }

    @Test
    fun `memory disabled dismisses batch without retrying`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH)))
        val processor = createProcessor(jobDao = jobDao, memoryEnabled = false)

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.SEMANTIC)

        assertEquals(1, result.terminalCount)
        assertEquals(MemoryMaintenanceJobStatus.DISMISSED, jobDao.jobs.single().status)
        assertTrue(jobDao.jobs.single().attempts > 0)
        assertEquals(0, processor.processRunnableJobs(MemoryMaintenanceJobFamily.SEMANTIC).processedCount)
    }

    @Test
    fun `memory disabled dismisses daily planning without a retry loop`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION)))
        val processor = createProcessor(jobDao = jobDao, memoryEnabled = false)

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.REPAIR)

        assertEquals(1, result.terminalCount)
        assertEquals(MemoryMaintenanceJobStatus.DISMISSED, jobDao.jobs.single().status)
        assertEquals("memory_disabled", jobDao.jobs.single().lastError)
        assertEquals(0, processor.processRunnableJobs(MemoryMaintenanceJobFamily.REPAIR).processedCount)
    }

    @Test
    fun `claim schedules the required lease watchdog`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.PROMOTE_LONG_TERM_CANDIDATE)))
        val watchdog = RecordingLeaseWatchdog()
        val processor = createProcessor(jobDao = jobDao, leaseWatchdog = watchdog)

        processor.processRunnableJobs(MemoryMaintenanceJobFamily.SEMANTIC)

        assertEquals(1, watchdog.scheduleCount)
    }

    @Test
    fun `processor completes persisted mutation reconciliation jobs`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(
            listOf(job(MemoryMaintenanceJobType.RECONCILE_MEMORY_MUTATIONS))
        )
        val scheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
        val fileStore = MemoryFileStore(
            MemoryFilePaths(Files.createTempDirectory("memory-mutation-repair-processor").toFile()),
            FIXED_CLOCK
        )
        fileStore.ensureStore().getOrThrow()
        val coordinator = MemoryMutationCoordinator(
            recoveryDao = InMemoryMemoryRecoveryDao(),
            memoryFileStore = fileStore,
            maintenanceScheduler = scheduler,
            workEnqueuer = RecordingWorkEnqueuer(),
            clock = FIXED_CLOCK
        )
        val recoveryService = MemoryMutationRecoveryService(
            memoryMutationCoordinator = coordinator,
            turnBatchDao = InMemoryMemoryTurnBatchDao(),
            maintenanceScheduler = scheduler,
            clock = FIXED_CLOCK
        )
        val processor = createProcessor(
            jobDao = jobDao,
            memoryMutationRecoveryService = recoveryService
        )

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.REPAIR)

        assertEquals(1, result.succeededCount)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, jobDao.jobs.single().status)
    }

    @Test
    fun `processor materializes a due daily distillation plan`() = runBlocking {
        val fileStore = MemoryFileStore(
            MemoryFilePaths(Files.createTempDirectory("memory-daily-plan-processor").toFile()),
            FIXED_CLOCK
        )
        fileStore.ensureStore().getOrThrow()
        fileStore.appendDailyNote(
            MarkdownMemoryCodec().renderDailyAppend(
                listOf(
                    MarkdownMemoryEntry(
                        id = "daily-stable",
                        text = "Stable preference.",
                        type = "communication_style",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT
                    )
                )
            ),
            LocalDate.of(1969, 12, 31)
        ).getOrThrow()
        val jobDao = InMemoryMaintenanceJobDao()
        val recoveryDao = InMemoryMemoryRecoveryDao()
        val enqueuer = RecordingWorkEnqueuer()
        val dailyScheduler = MemoryDailyDistillationScheduler(
            memoryFileStore = fileStore,
            markdownMemoryCodec = MarkdownMemoryCodec(),
            recoveryDao = recoveryDao,
            maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK),
            settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = true),
            workEnqueuer = enqueuer,
            clock = FIXED_CLOCK
        )
        dailyScheduler.ensurePlanningJobs()
        val processor = createProcessor(
            jobDao = jobDao,
            memoryDailyDistillationScheduler = dailyScheduler
        )

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.REPAIR, limit = 1)

        assertEquals(1, result.succeededCount)
        assertEquals(1, jobDao.jobs.count { job -> job.type == MemoryMaintenanceJobType.DISTILL_DAILY_NOTES })
        assertEquals(1, recoveryDao.getDistillationCheckpointsByStatuses(listOf(MemoryDistillationCheckpointStatus.PENDING)).size)
    }

    @Test
    fun `repairer resets stale running jobs and enqueues work`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(
            listOf(
                job(
                    type = MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
                    status = MemoryMaintenanceJobStatus.RUNNING,
                    updatedAt = 1L
                )
            )
        )
        val enqueuer = RecordingWorkEnqueuer()
        val repairer = MemoryMaintenanceRepairer(
            maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK),
            workScheduler = enqueuer
        )

        val result = repairer.repairAndEnqueue()

        assertEquals(1, result.resetCount)
        assertTrue(result.schedulingSucceeded)
        assertEquals(
            listOf(EnqueuedMemoryWork(MemoryMaintenanceJobFamily.INDEX, 0L)),
            enqueuer.works
        )
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, jobDao.jobs.single().status)
    }

    @Test
    fun `repair scheduling failure keeps persisted retry truth without throwing`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(
            listOf(
                job(
                    type = MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
                    status = MemoryMaintenanceJobStatus.RUNNING,
                    updatedAt = 1L
                )
            )
        )
        val repairer = MemoryMaintenanceRepairer(
            maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK),
            workScheduler = FailingRepairWorkEnqueuer
        )

        val result = repairer.repairAndEnqueue()

        assertEquals(1, result.resetCount)
        assertFalse(result.schedulingSucceeded)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, jobDao.jobs.single().status)
        assertEquals(1_000L, jobDao.jobs.single().nextRunAt)
    }

    @Test
    fun `repairer also enqueues earliest future retry`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(
            listOf(
                job(
                    type = MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
                    status = MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
                    updatedAt = 1L,
                    nextRunAt = 1_120L
                )
            )
        )
        val enqueuer = RecordingWorkEnqueuer()
        val repairer = MemoryMaintenanceRepairer(
            maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK),
            workScheduler = enqueuer
        )

        repairer.repairAndEnqueue()

        assertEquals(
            listOf(EnqueuedMemoryWork(MemoryMaintenanceJobFamily.SEMANTIC, 120L)),
            enqueuer.works
        )
    }

    private fun job(
        type: String,
        status: String = MemoryMaintenanceJobStatus.PENDING,
        updatedAt: Long = 10L,
        payloadJson: String = "{}",
        nextRunAt: Long? = null
    ): MemoryMaintenanceJob = MemoryMaintenanceJob(
        jobId = "job_$type",
        type = type,
        status = status,
        idempotencyKey = "key_$type",
        payloadJson = payloadJson,
        attempts = 0,
        lastError = null,
        createdAt = 1L,
        startedAt = null,
        updatedAt = updatedAt,
        nextRunAt = nextRunAt,
        family = MemoryMaintenanceJobFamily.forType(type)
    )

    private fun createProcessor(
        jobDao: InMemoryMaintenanceJobDao,
        memoryEnabled: Boolean = true,
        leaseWatchdog: MemoryMaintenanceLeaseWatchdog = NoOpLeaseWatchdog,
        memoryMutationRecoveryService: MemoryMutationRecoveryService? = null,
        memoryIndexSyncService: MemoryIndexSyncService? = null,
        memoryDailyDistillationScheduler: MemoryDailyDistillationScheduler? = null
    ): MemoryMaintenanceProcessor = MemoryMaintenanceProcessor(
        maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK),
        settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = memoryEnabled),
        leaseWatchdog = leaseWatchdog,
        memoryMutationRecoveryService = memoryMutationRecoveryService,
        memoryIndexSyncService = memoryIndexSyncService,
        memoryDailyDistillationScheduler = memoryDailyDistillationScheduler
    )

    private fun syncServiceReturning(result: MemoryIndexSyncResult): MemoryIndexSyncService =
        MemoryIndexSyncService { result }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(1_000L), ZoneOffset.UTC)
    }
}

private data object NoOpLeaseWatchdog : MemoryMaintenanceLeaseWatchdog {
    override suspend fun scheduleLeaseWatchdog() = Unit
}

private class RecordingLeaseWatchdog : MemoryMaintenanceLeaseWatchdog {
    var scheduleCount = 0

    override suspend fun scheduleLeaseWatchdog() {
        scheduleCount += 1
    }
}

private data object FailingRepairWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
    override fun enqueueWork(family: String, delaySeconds: Long) {
        error("work scheduling unavailable")
    }
}

internal class FakeMaintenanceSettingRepository(
    var memoryEnabled: Boolean
) : SettingRepository {
    override suspend fun fetchMemoryEnabled(): Boolean = memoryEnabled
    override suspend fun fetchPlatforms(): List<Platform> = emptyList()
    override suspend fun fetchPlatformV2s(): List<PlatformV2> = emptyList()
    override suspend fun fetchPlatformModels(): List<PlatformModelV2> = emptyList()
    override suspend fun fetchPlatformModels(platformUid: String): List<PlatformModelV2> = emptyList()
    override suspend fun fetchEnabledChatModels(): List<AvailableChatModel> = emptyList()
    override suspend fun resolveDefaultChatModel(): AvailableChatModel? = null
    override suspend fun fetchThemes(): ThemeSetting = ThemeSetting()
    override suspend fun fetchLastSelectedModel(): LastSelectedModel? = null
    override suspend fun fetchMemoryMaintenanceNotificationsEnabled(): Boolean = true
    override suspend fun fetchToolCallingMode(): ToolCallingMode = ToolCallingMode.Off
    override suspend fun fetchDisabledToolNames(): Set<String> = emptySet()
    override suspend fun fetchWebSearchMode(): WebSearchMode = WebSearchMode.Off
    override suspend fun fetchWebSearchSearxngBaseUrl(): String = ""
    override suspend fun migrateToPlatformV2() = Unit
    override suspend fun updatePlatforms(platforms: List<Platform>) = Unit
    override suspend fun updateThemes(themeSetting: ThemeSetting) = Unit
    override suspend fun updateLastSelectedModel(platformUid: String, model: String, reasoningMode: ReasoningMode) = Unit
    override suspend fun updateMemoryEnabled(enabled: Boolean) = Unit
    override suspend fun updateMemoryMaintenanceNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun updateToolCallingMode(mode: ToolCallingMode) = Unit
    override suspend fun updateToolEnabled(toolName: String, enabled: Boolean) = Unit
    override suspend fun updateWebSearchMode(mode: WebSearchMode) = Unit
    override suspend fun updateWebSearchSearxngBaseUrl(baseUrl: String) = Unit
    override suspend fun refreshPlatformModels(platformUid: String): ModelRefreshResult = error("Not used")
    override suspend fun updatePlatformModelEnabled(platformUid: String, modelId: String, enabled: Boolean) = Unit
    override suspend fun setPlatformDefaultModel(platformUid: String, modelId: String) = Unit
    override suspend fun addPlatformV2(platform: PlatformV2) = Unit
    override suspend fun updatePlatformV2(platform: PlatformV2) = Unit
    override suspend fun deletePlatformV2(platform: PlatformV2) = Unit
    override suspend fun getPlatformV2ById(id: Int): PlatformV2? = null
}
