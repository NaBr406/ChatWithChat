package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryIndexDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryChunk
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDocument
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
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMaintenanceProcessorTest {

    @Test
    fun `processor rebuilds memory index jobs`() = runBlocking {
        val fileStore = MemoryFileStore(MemoryFilePaths(Files.createTempDirectory("memory-maintenance-processor").toFile()))
        fileStore.replaceLongTermMemory(
            """
            # ChatWithChat Memory

            ## Stable Preferences

            <!-- memory:id=mem_1 type=communication_style sensitivity=normal source=explicit_user_statement -->
            - The user prefers direct answers.
            """.trimIndent()
        ).getOrThrow()
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX)))
        val indexDao = InMemoryProcessorMemoryIndexDao()
        val processor = createProcessor(
            jobDao = jobDao,
            fileStore = fileStore,
            indexDao = indexDao
        )

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.INDEX)

        assertEquals(1, result.succeededCount)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, jobDao.jobs.single().status)
        assertTrue(indexDao.documents.containsKey("MEMORY.md"))
        assertTrue(indexDao.chunks.isNotEmpty())
    }

    @Test
    fun `processor blocks distillation until its implementation is available`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.DISTILL_DAILY_NOTES)))
        val processor = createProcessor(jobDao = jobDao)

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.SEMANTIC)

        assertEquals(1, result.blockedCount)
        assertEquals(MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY, jobDao.jobs.single().status)
        assertEquals(
            "daily_distillation_not_available",
            jobDao.jobs.single().blockedReason
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
            fileStore = fileStore,
            memoryMutationRecoveryService = recoveryService
        )

        val result = processor.processRunnableJobs(MemoryMaintenanceJobFamily.REPAIR)

        assertEquals(1, result.succeededCount)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, jobDao.jobs.single().status)
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
        fileStore: MemoryFileStore = MemoryFileStore(MemoryFilePaths(Files.createTempDirectory("memory-maintenance-processor-default").toFile()), FIXED_CLOCK),
        indexDao: InMemoryProcessorMemoryIndexDao = InMemoryProcessorMemoryIndexDao(),
        memoryEnabled: Boolean = true,
        leaseWatchdog: MemoryMaintenanceLeaseWatchdog = NoOpLeaseWatchdog,
        memoryMutationRecoveryService: MemoryMutationRecoveryService? = null
    ): MemoryMaintenanceProcessor = MemoryMaintenanceProcessor(
        maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK),
        memoryIndexRepository = MemoryIndexRepository(fileStore, indexDao, MemoryChunker(), FIXED_CLOCK),
        settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = memoryEnabled),
        leaseWatchdog = leaseWatchdog,
        memoryMutationRecoveryService = memoryMutationRecoveryService
    )

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

internal class InMemoryProcessorMemoryIndexDao : MemoryIndexDao {
    val documents = linkedMapOf<String, MemoryDocument>()
    val chunks = linkedMapOf<String, MemoryChunk>()

    override suspend fun getDocuments(): List<MemoryDocument> = documents.values.toList()
    override suspend fun getDocument(sourcePath: String): MemoryDocument? = documents[sourcePath]
    override suspend fun getChunksForSource(sourcePath: String): List<MemoryChunk> = chunks.values.filter { it.sourcePath == sourcePath }
    override suspend fun getSearchCandidates(sourcePath: String?, includePrivate: Boolean, limit: Int): List<MemoryChunk> = chunks.values.take(limit)
    override suspend fun upsertDocument(document: MemoryDocument) {
        documents[document.sourcePath] = document
    }
    override suspend fun upsertDocuments(documents: List<MemoryDocument>) {
        documents.forEach { upsertDocument(it) }
    }
    override suspend fun insertChunks(chunks: List<MemoryChunk>) {
        chunks.forEach { chunk -> this.chunks[chunk.chunkId] = chunk }
    }
    override suspend fun deleteChunksForSource(sourcePath: String) {
        chunks.values.removeAll { it.sourcePath == sourcePath }
    }
    override suspend fun deleteDocument(sourcePath: String) {
        documents.remove(sourcePath)
    }
    override suspend fun clearChunks() {
        chunks.clear()
    }
    override suspend fun clearDocuments() {
        documents.clear()
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
