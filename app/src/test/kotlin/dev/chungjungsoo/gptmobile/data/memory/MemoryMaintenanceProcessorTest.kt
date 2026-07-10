package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryIndexDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMaintenanceJobDao
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

        val result = processor.processRunnableJobs()

        assertEquals(1, result.succeededCount)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, jobDao.jobs.single().status)
        assertTrue(indexDao.documents.containsKey("MEMORY.md"))
        assertTrue(indexDao.chunks.isNotEmpty())
    }

    @Test
    fun `processor retries persisted markdown learning jobs without duplicating notes`() = runBlocking {
        val fileStore = MemoryFileStore(MemoryFilePaths(Files.createTempDirectory("memory-maintenance-processor-learning").toFile()), FIXED_CLOCK)
        val jobDao = InMemoryMaintenanceJobDao(
            listOf(
                job(
                    type = MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
                    payloadJson = """
                    {
                      "chatId": 1,
                      "chatTitle": "Chat",
                      "learningKey": "retry-key",
                      "recentMessages": [{"role":"user","content":"Remember the durable markdown retry."}],
                      "createdAt": 100
                    }
                    """.trimIndent()
                )
            )
        )
        val intelligence = FakeMemoryIntelligence(
            markdownProposal = MarkdownMemoryLearningProposal(
                dailyNotes = listOf(
                    MarkdownMemoryLearningNote(
                        text = "The app retries persisted Markdown learning jobs.",
                        type = "project_context",
                        source = MemorySource.EXPLICIT_USER_STATEMENT
                    )
                )
            )
        )
        val processor = createProcessor(
            jobDao = jobDao,
            fileStore = fileStore,
            intelligence = intelligence
        )

        val result = processor.processRunnableJobs()
        val secondResult = processor.processRunnableJobs()
        val dailyMarkdown = fileStore.readDailyMemory().getOrThrow()

        assertEquals(1, result.succeededCount)
        assertEquals(0, secondResult.processedCount)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, jobDao.jobs.single().status)
        assertEquals(1, dailyMarkdown.split("The app retries persisted Markdown learning jobs.").size - 1)
    }

    @Test
    fun `processor keeps unattended distillation jobs retryable`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.DISTILL_DAILY_NOTES)))
        val processor = createProcessor(jobDao = jobDao)

        val result = processor.processRunnableJobs()

        assertEquals(1, result.retryableCount)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, jobDao.jobs.single().status)
        assertEquals("llm_memory_worker_pending", jobDao.jobs.single().lastError)
    }

    @Test
    fun `batch consolidation becomes terminal after three automatic attempts`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH)))
        val processor = createProcessor(jobDao = jobDao)

        repeat(3) {
            processor.processRunnableJobs()
            val currentJob = jobDao.jobs.single()
            if (currentJob.status == MemoryMaintenanceJobStatus.FAILED_RETRYABLE) {
                jobDao.update(currentJob.copy(nextRunAt = 1_000L))
            }
        }

        val terminalJob = jobDao.jobs.single()
        assertEquals(3, terminalJob.attempts)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, terminalJob.status)
        assertEquals("batch_consolidation_pending", terminalJob.lastError)
        assertEquals(0, processor.processRunnableJobs().processedCount)
    }

    @Test
    fun `memory disabled dismisses batch without retrying`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(listOf(job(MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH)))
        val processor = createProcessor(jobDao = jobDao, memoryEnabled = false)

        val result = processor.processRunnableJobs()

        assertEquals(1, result.terminalCount)
        assertEquals(MemoryMaintenanceJobStatus.DISMISSED, jobDao.jobs.single().status)
        assertEquals(0, jobDao.jobs.single().attempts)
        assertEquals(0, processor.processRunnableJobs().processedCount)
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
        assertEquals(1, enqueuer.enqueueCalls)
        assertEquals(listOf(0L), enqueuer.delays)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, jobDao.jobs.single().status)
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

        assertEquals(listOf(0L, 120L), enqueuer.delays)
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
        nextRunAt = nextRunAt
    )

    private fun createProcessor(
        jobDao: InMemoryMaintenanceJobDao,
        fileStore: MemoryFileStore = MemoryFileStore(MemoryFilePaths(Files.createTempDirectory("memory-maintenance-processor-default").toFile()), FIXED_CLOCK),
        indexDao: InMemoryProcessorMemoryIndexDao = InMemoryProcessorMemoryIndexDao(),
        intelligence: FakeMemoryIntelligence = FakeMemoryIntelligence(),
        memoryEnabled: Boolean = true
    ): MemoryMaintenanceProcessor = MemoryMaintenanceProcessor(
        maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK),
        memoryIndexRepository = MemoryIndexRepository(fileStore, indexDao, MemoryChunker(), FIXED_CLOCK),
        settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = memoryEnabled),
        personalMemoryDao = InMemoryPersonalMemoryDao(),
        memoryIntelligence = intelligence,
        markdownMemoryLearningService = MarkdownMemoryLearningService(
            memoryFileStore = fileStore,
            markdownMemoryCodec = MarkdownMemoryCodec(),
            maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK),
            memoryIndexRebuilder = MemoryIndexRepository(fileStore, indexDao, MemoryChunker(), FIXED_CLOCK),
            clock = FIXED_CLOCK
        )
    )

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(1_000L), ZoneOffset.UTC)
    }
}

internal class RecordingWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
    var enqueueCalls = 0
    val delays = mutableListOf<Long>()

    override fun enqueueRepairWork(delaySeconds: Long) {
        enqueueCalls += 1
        delays += delaySeconds
    }
}

internal class InMemoryMaintenanceJobDao(
    initialJobs: List<MemoryMaintenanceJob> = emptyList()
) : MemoryMaintenanceJobDao {
    val jobs = initialJobs.toMutableList()

    override suspend fun getById(jobId: String): MemoryMaintenanceJob? =
        jobs.firstOrNull { it.jobId == jobId }

    override suspend fun getByIdempotencyKey(idempotencyKey: String): MemoryMaintenanceJob? =
        jobs.firstOrNull { it.idempotencyKey == idempotencyKey }

    override suspend fun getByTypeAndStatuses(type: String, statuses: List<String>): List<MemoryMaintenanceJob> =
        jobs.filter { it.type == type && it.status in statuses }.sortedBy { it.createdAt }

    override suspend fun getStaleJobs(status: String, before: Long): List<MemoryMaintenanceJob> =
        jobs.filter { it.status == status && it.updatedAt < before }.sortedBy { it.updatedAt }

    override suspend fun getVisibleJobs(limit: Int): List<MemoryMaintenanceJob> =
        jobs.sortedByDescending { it.updatedAt }.take(limit)

    override suspend fun getRunnableJobs(
        statuses: List<String>,
        now: Long,
        limit: Int
    ): List<MemoryMaintenanceJob> = jobs
        .filter { job -> job.status in statuses && now >= (job.nextRunAt ?: 0L) }
        .sortedBy { it.createdAt }
        .take(limit)

    override suspend fun getEarliestFutureRunAt(now: Long): Long? =
        jobs
            .filter { job -> job.status in listOf(MemoryMaintenanceJobStatus.PENDING, MemoryMaintenanceJobStatus.FAILED_RETRYABLE) }
            .mapNotNull { job -> job.nextRunAt }
            .filter { nextRunAt -> nextRunAt > now }
            .minOrNull()

    override suspend fun insertIgnore(job: MemoryMaintenanceJob): Long {
        if (jobs.any { it.idempotencyKey == job.idempotencyKey }) return -1L
        jobs += job
        return jobs.size.toLong()
    }

    override suspend fun update(job: MemoryMaintenanceJob) {
        val index = jobs.indexOfFirst { it.jobId == job.jobId }
        if (index != -1) jobs[index] = job
    }

    override suspend fun moveStaleJobs(
        fromStatus: String,
        status: String,
        before: Long,
        lastError: String?,
        updatedAt: Long,
        nextRunAt: Long?
    ): Int {
        var changedCount = 0
        jobs.replaceAll { job ->
            if (job.status == fromStatus && job.updatedAt < before) {
                changedCount += 1
                job.copy(status = status, lastError = lastError, updatedAt = updatedAt, nextRunAt = nextRunAt)
            } else {
                job
            }
        }
        return changedCount
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
    override suspend fun fetchWebSearchMode(): WebSearchMode = WebSearchMode.Off
    override suspend fun fetchWebSearchSearxngBaseUrl(): String = ""
    override suspend fun migrateToPlatformV2() = Unit
    override suspend fun updatePlatforms(platforms: List<Platform>) = Unit
    override suspend fun updateThemes(themeSetting: ThemeSetting) = Unit
    override suspend fun updateLastSelectedModel(platformUid: String, model: String, reasoningMode: ReasoningMode) = Unit
    override suspend fun updateMemoryEnabled(enabled: Boolean) = Unit
    override suspend fun updateMemoryMaintenanceNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun updateToolCallingMode(mode: ToolCallingMode) = Unit
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
