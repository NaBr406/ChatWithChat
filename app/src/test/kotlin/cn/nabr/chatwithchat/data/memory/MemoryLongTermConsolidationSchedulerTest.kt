package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryLongTermConsolidationDao
import cn.nabr.chatwithchat.data.database.entity.MemoryLongTermConsolidationCheckpoint
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLongTermConsolidationSchedulerTest {

    @Test
    fun `schedule policy uses exact material and weekly boundaries`() {
        val now = 1_000_000L
        val interval = MemoryLongTermConsolidationSchedulePolicy.MAX_COMPLETION_INTERVAL.seconds

        assertFalse(
            MemoryLongTermConsolidationSchedulePolicy.evaluate(
                materialMutationCount = 19,
                latestCompletedAt = now - interval + 1,
                now = now
            ).shouldSchedule
        )
        assertEquals(
            MemoryLongTermTriggerReason.MATERIAL_THRESHOLD,
            MemoryLongTermConsolidationSchedulePolicy.evaluate(
                materialMutationCount = 20,
                latestCompletedAt = now,
                now = now
            ).triggerReason
        )
        assertEquals(
            MemoryLongTermTriggerReason.MATERIAL_THRESHOLD,
            MemoryLongTermConsolidationSchedulePolicy.evaluate(
                materialMutationCount = 21,
                latestCompletedAt = now,
                now = now
            ).triggerReason
        )
        assertFalse(
            MemoryLongTermConsolidationSchedulePolicy.evaluate(
                materialMutationCount = 0,
                latestCompletedAt = now - interval + 1,
                now = now
            ).shouldSchedule
        )
        assertEquals(
            MemoryLongTermTriggerReason.WEEKLY_DUE,
            MemoryLongTermConsolidationSchedulePolicy.evaluate(
                materialMutationCount = 0,
                latestCompletedAt = now - interval,
                now = now
            ).triggerReason
        )
        assertEquals(
            MemoryLongTermTriggerReason.WEEKLY_DUE,
            MemoryLongTermConsolidationSchedulePolicy.evaluate(
                materialMutationCount = 0,
                latestCompletedAt = null,
                now = now
            ).triggerReason
        )
    }

    @Test
    fun `first pass freezes every ordered entry and repeated planning reuses identities`() = runBlocking {
        val entries = (1..30).map { index -> entry(index) }
        val fixture = fixture(entries = entries)
        fixture.seedCorpusGeneration(42)

        val first = fixture.scheduler.ensureScheduled()
        val second = fixture.scheduler.ensureScheduled()

        assertTrue(first.scheduled)
        assertEquals(MemoryLongTermTriggerReason.WEEKLY_DUE, first.reason)
        assertEquals(first.checkpointId, second.checkpointId)
        assertEquals(first.jobId, second.jobId)
        assertEquals(MemoryLongTermConsolidationScheduler.REASON_ACTIVE_CHECKPOINT, second.reason)
        assertEquals(1, fixture.checkpointDao.checkpoints.size)
        assertEquals(1, fixture.jobDao.jobs.size)

        val checkpoint = fixture.checkpointDao.checkpoints.single()
        val orderedIds = STRICT_JSON.decodeFromString<List<String>>(checkpoint.orderedEntryIdsJson)
        val physicalIds = MarkdownMemoryCodec()
            .parse(fixture.fileStore.readLongTermMemory().getOrThrow())
            .entries
            .map(MarkdownMemoryEntry::id)
        assertEquals(physicalIds, orderedIds)
        assertEquals(30, checkpoint.entryCount)
        assertEquals(0, checkpoint.partitionCursor)
        assertEquals(42L, checkpoint.baseGeneration)
        assertEquals(fixture.fileStore.currentMemoryFileHash(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).getOrThrow(), checkpoint.baseSourceHash)
        assertEquals(64, checkpoint.orderedSnapshotHash.length)
        assertEquals(64, checkpoint.recallProjectionHash.length)
        assertNotEquals(checkpoint.baseSourceHash, checkpoint.recallProjectionHash)
        assertNotEquals(checkpoint.baseSourceHash, checkpoint.orderedSnapshotHash)

        val job = fixture.jobDao.jobs.single()
        val payload = STRICT_JSON.decodeFromString<MemoryLongTermConsolidationJobPayload>(job.payloadJson)
        assertEquals(MemoryMaintenanceJobType.CONSOLIDATE_LONG_TERM_MEMORY, job.type)
        assertEquals(MemoryMaintenanceJobFamily.SEMANTIC, job.family)
        assertEquals(checkpoint.jobId, job.jobId)
        assertEquals(checkpoint.baseGeneration, job.generation)
        assertEquals(checkpoint.checkpointId, payload.checkpointId)
        assertEquals(checkpoint.baseSourceHash, payload.baseSourceHash)
        assertEquals(checkpoint.orderedSnapshotHash, payload.orderedSnapshotHash)
    }

    @Test
    fun `material threshold starts after latest completed generation`() = runBlocking {
        val fixture = fixture(entries = listOf(entry(1)))
        fixture.checkpointDao.seedCompleted(
            completedGeneration = 17,
            completedAt = NOW_EPOCH_SECONDS - 60
        )
        fixture.checkpointDao.materialMutationCount = 20
        fixture.seedCorpusGeneration(21)

        val result = fixture.scheduler.ensureScheduled()

        assertTrue(result.scheduled)
        assertEquals(MemoryLongTermTriggerReason.MATERIAL_THRESHOLD, result.reason)
        assertEquals(17L, fixture.checkpointDao.lastAfterGeneration)
        val active = fixture.checkpointDao.checkpoints.single { checkpoint -> checkpoint.activeKey != null }
        assertEquals(20, active.materialMutationCountAtStart)
        assertEquals(21L, active.baseGeneration)
    }

    @Test
    fun `recent completion below threshold only schedules the weekly evaluation`() = runBlocking {
        val fixture = fixture(entries = listOf(entry(1)))
        fixture.checkpointDao.seedCompleted(
            completedGeneration = 4,
            completedAt = NOW_EPOCH_SECONDS - 60
        )
        fixture.checkpointDao.materialMutationCount = 19

        val result = fixture.scheduler.ensureScheduled()

        assertFalse(result.scheduled)
        assertEquals(MemoryLongTermConsolidationScheduler.REASON_NOT_DUE, result.reason)
        assertEquals(4L, fixture.checkpointDao.lastAfterGeneration)
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertEquals(
            listOf(
                EnqueuedMemoryWork(
                    family = MemoryMaintenanceJobFamily.REPAIR,
                    delaySeconds = MemoryLongTermConsolidationSchedulePolicy.MAX_COMPLETION_INTERVAL.seconds - 60
                )
            ),
            fixture.workEnqueuer.works
        )
    }

    @Test
    fun `unchanged weekly corpus starts a new deterministic cycle`() = runBlocking {
        val fixture = fixture(entries = listOf(entry(1)))
        val first = fixture.scheduler.ensureScheduled()
        fixture.completeActiveCheckpoint(
            completedGeneration = 0,
            completedAt = NOW_EPOCH_SECONDS - MemoryLongTermConsolidationSchedulePolicy.MAX_COMPLETION_INTERVAL.seconds
        )

        val second = fixture.scheduler.ensureScheduled()

        assertTrue(second.scheduled)
        assertEquals(MemoryLongTermTriggerReason.WEEKLY_DUE, second.reason)
        assertNotEquals(first.checkpointId, second.checkpointId)
        assertNotEquals(first.jobId, second.jobId)
        assertEquals(2, fixture.checkpointDao.checkpoints.size)
        assertEquals(2, fixture.jobDao.jobs.size)
    }

    @Test
    fun `force continuation freezes a new snapshot without waiting for periodic threshold`() = runBlocking {
        val fixture = fixture(entries = listOf(entry(1)))
        val first = fixture.scheduler.ensureScheduled()
        fixture.completeActiveCheckpoint(completedGeneration = 0, completedAt = NOW_EPOCH_SECONDS)

        val continuation = fixture.scheduler.ensureContinuationScheduled(checkNotNull(first.checkpointId))
        val replay = fixture.scheduler.ensureContinuationScheduled(checkNotNull(first.checkpointId))

        assertTrue(continuation.scheduled)
        assertEquals(MemoryLongTermTriggerReason.CONTINUATION, continuation.reason)
        assertNotEquals(first.checkpointId, continuation.checkpointId)
        assertEquals(continuation.checkpointId, replay.checkpointId)
        assertEquals(continuation.jobId, replay.jobId)
        val checkpoint = fixture.checkpointDao.checkpoints.single { current ->
            current.checkpointId == continuation.checkpointId
        }
        assertEquals(MemoryLongTermTriggerReason.CONTINUATION, checkpoint.triggerReason)
    }

    @Test
    fun `startup planning resumes durable continuation intent before weekly gate`() = runBlocking {
        val fixture = fixture(entries = listOf(entry(1)))
        val first = fixture.scheduler.ensureScheduled()
        fixture.completeActiveCheckpoint(
            completedGeneration = 0,
            completedAt = NOW_EPOCH_SECONDS,
            continuationRequired = true
        )
        fixture.workEnqueuer.works.clear()

        val resumed = fixture.scheduler.ensureScheduled()

        assertTrue(resumed.scheduled)
        assertEquals(MemoryLongTermTriggerReason.CONTINUATION, resumed.reason)
        assertNotEquals(first.checkpointId, resumed.checkpointId)
        assertNotEquals(first.jobId, resumed.jobId)
        assertEquals(2, fixture.checkpointDao.checkpoints.size)
        assertEquals(2, fixture.jobDao.jobs.size)
        assertEquals(
            listOf(EnqueuedMemoryWork(MemoryMaintenanceJobFamily.SEMANTIC, 0)),
            fixture.workEnqueuer.works
        )
    }

    @Test
    fun `startup planning waits for completed checkpoint job finalization before continuation`() = runBlocking {
        val fixture = fixture(entries = listOf(entry(1)))
        fixture.scheduler.ensureScheduled()
        fixture.completeActiveCheckpoint(
            completedGeneration = 0,
            completedAt = NOW_EPOCH_SECONDS,
            continuationRequired = true
        )
        val completedJob = fixture.jobDao.jobs.single()
        fixture.jobDao.forceUpdate(
            completedJob.copy(
                status = MemoryMaintenanceJobStatus.RUNNING,
                attempts = 1,
                leaseOwner = "completion-finalization",
                leaseExpiresAt = NOW_EPOCH_SECONDS + 60
            )
        )
        fixture.workEnqueuer.works.clear()

        val waiting = fixture.scheduler.ensureScheduled()

        assertTrue(waiting.scheduled)
        assertEquals(MemoryLongTermConsolidationScheduler.REASON_COMPLETED_JOB_ACTIVE, waiting.reason)
        assertEquals(1, fixture.checkpointDao.checkpoints.size)
        assertEquals(1, fixture.jobDao.jobs.size)
        assertTrue(fixture.workEnqueuer.works.isEmpty())

        fixture.jobDao.forceUpdate(
            checkNotNull(fixture.jobDao.getById(completedJob.jobId)).copy(
                status = MemoryMaintenanceJobStatus.SUCCEEDED,
                leaseOwner = null,
                leaseExpiresAt = null
            )
        )
        val resumed = fixture.scheduler.ensureScheduled()

        assertTrue(resumed.scheduled)
        assertEquals(MemoryLongTermTriggerReason.CONTINUATION, resumed.reason)
        assertEquals(2, fixture.checkpointDao.checkpoints.size)
        assertEquals(2, fixture.jobDao.jobs.size)
    }

    @Test
    fun `re-enable revives the dismissed active job without changing identity`() = runBlocking {
        val fixture = fixture(entries = listOf(entry(1)))
        val first = fixture.scheduler.ensureScheduled()
        val dismissed = fixture.jobDao.jobs.single().copy(
            status = MemoryMaintenanceJobStatus.DISMISSED,
            lastError = MemoryLongTermConsolidationScheduler.REASON_MEMORY_DISABLED,
            nextRunAt = null
        )
        fixture.jobDao.forceUpdate(dismissed)
        fixture.workEnqueuer.works.clear()
        fixture.settings.memoryEnabled = false

        val disabled = fixture.scheduler.ensureScheduled()

        assertFalse(disabled.scheduled)
        assertTrue(fixture.workEnqueuer.works.isEmpty())
        assertEquals(MemoryMaintenanceJobStatus.DISMISSED, fixture.jobDao.jobs.single().status)

        fixture.settings.memoryEnabled = true
        val revived = fixture.scheduler.ensureScheduled()

        assertTrue(revived.scheduled)
        assertEquals(first.checkpointId, revived.checkpointId)
        assertEquals(first.jobId, revived.jobId)
        assertEquals(1, fixture.checkpointDao.checkpoints.size)
        assertEquals(1, fixture.jobDao.jobs.size)
        assertEquals(MemoryMaintenanceJobStatus.PENDING, fixture.jobDao.jobs.single().status)
        assertEquals(
            listOf(EnqueuedMemoryWork(MemoryMaintenanceJobFamily.SEMANTIC, 0)),
            fixture.workEnqueuer.works
        )
    }

    @Test
    fun `unrepairable canonical parse fails closed without a partial checkpoint`() = runBlocking {
        val fixture = fixture(entries = listOf(entry(1)))
        val invalidMarkdown = fixture.fileStore.readLongTermMemory().getOrThrow()
            .replace("source=explicit_user_statement", "source=invalid_source")
        fixture.fileStore.replaceLongTermMemory(invalidMarkdown).getOrThrow()

        val result = fixture.scheduler.ensureScheduled()

        assertFalse(result.scheduled)
        assertEquals(MemoryLongTermConsolidationScheduler.REASON_INVALID_CANONICAL_SNAPSHOT, result.reason)
        assertTrue(fixture.checkpointDao.checkpoints.isEmpty())
        assertTrue(fixture.jobDao.jobs.isEmpty())
    }

    @Test
    fun `recall placeholder excludes observation metadata but includes fact text`() = runBlocking {
        val original = entry(1)
        val metadataOnly = original.copy(
            updatedAt = NOW_EPOCH_SECONDS,
            lastObservedAt = NOW_EPOCH_SECONDS,
            evidenceRefs = listOf("turn:metadata-only")
        )
        val textChanged = metadataOnly.copy(text = "A materially changed stable preference.")
        val originalFixture = fixture(entries = listOf(original))
        val metadataFixture = fixture(entries = listOf(metadataOnly))
        val textFixture = fixture(entries = listOf(textChanged))

        originalFixture.scheduler.ensureScheduled()
        metadataFixture.scheduler.ensureScheduled()
        textFixture.scheduler.ensureScheduled()

        val originalCheckpoint = originalFixture.checkpointDao.checkpoints.single()
        val metadataCheckpoint = metadataFixture.checkpointDao.checkpoints.single()
        val textCheckpoint = textFixture.checkpointDao.checkpoints.single()
        assertNotEquals(originalCheckpoint.baseSourceHash, metadataCheckpoint.baseSourceHash)
        assertEquals(originalCheckpoint.orderedSnapshotHash, metadataCheckpoint.orderedSnapshotHash)
        assertEquals(originalCheckpoint.recallProjectionHash, metadataCheckpoint.recallProjectionHash)
        assertNotEquals(metadataCheckpoint.recallProjectionHash, textCheckpoint.recallProjectionHash)
    }

    @Test
    fun `disabled memory neither creates nor wakes periodic work`() = runBlocking {
        val fixture = fixture(memoryEnabled = false, entries = listOf(entry(1)))

        val result = fixture.scheduler.ensureScheduled()
        val continuation = fixture.scheduler.ensureContinuationScheduled()

        assertFalse(result.scheduled)
        assertFalse(continuation.scheduled)
        assertEquals(MemoryLongTermConsolidationScheduler.REASON_MEMORY_DISABLED, result.reason)
        assertEquals(MemoryLongTermConsolidationScheduler.REASON_MEMORY_DISABLED, continuation.reason)
        assertTrue(fixture.checkpointDao.checkpoints.isEmpty())
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertTrue(fixture.workEnqueuer.works.isEmpty())
    }

    private suspend fun fixture(
        memoryEnabled: Boolean = true,
        entries: List<MarkdownMemoryEntry>
    ): Fixture {
        val clock = Clock.fixed(Instant.ofEpochSecond(NOW_EPOCH_SECONDS), ZoneOffset.UTC)
        val fileStore = MemoryFileStore(
            paths = MemoryFilePaths(Files.createTempDirectory("memory-long-term-scheduler").toFile()),
            clock = clock
        )
        fileStore.ensureStore().getOrThrow()
        fileStore.replaceLongTermMemory(MarkdownMemoryCodec().renderLongTerm(entries)).getOrThrow()
        val checkpointDao = InMemoryLongTermConsolidationDao()
        val jobDao = InMemoryMaintenanceJobDao()
        val workEnqueuer = RecordingWorkEnqueuer()
        val settings = FakeMaintenanceSettingRepository(memoryEnabled)
        val maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, clock)
        val scheduler = MemoryLongTermConsolidationScheduler(
            memoryFileStore = fileStore,
            markdownMemoryCodec = MarkdownMemoryCodec(),
            checkpointDao = checkpointDao,
            maintenanceScheduler = maintenanceScheduler,
            settingRepository = settings,
            workEnqueuer = workEnqueuer,
            clock = clock
        )
        return Fixture(
            fileStore = fileStore,
            checkpointDao = checkpointDao,
            jobDao = jobDao,
            workEnqueuer = workEnqueuer,
            settings = settings,
            scheduler = scheduler
        )
    }

    private fun entry(index: Int): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = "memory_$index",
        text = "Stable preference number $index.",
        type = "preference",
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = NOW_EPOCH_SECONDS - index,
        updatedAt = NOW_EPOCH_SECONDS - index,
        lastObservedAt = NOW_EPOCH_SECONDS - index,
        recallState = MemoryRecallState.QUERY
    )

    private data class Fixture(
        val fileStore: MemoryFileStore,
        val checkpointDao: InMemoryLongTermConsolidationDao,
        val jobDao: InMemoryMaintenanceJobDao,
        val workEnqueuer: RecordingWorkEnqueuer,
        val settings: FakeMaintenanceSettingRepository,
        val scheduler: MemoryLongTermConsolidationScheduler
    ) {
        suspend fun seedCorpusGeneration(generation: Long) {
            checkpointDao.latestCommittedGeneration = generation
        }

        fun completeActiveCheckpoint(
            completedGeneration: Long,
            completedAt: Long,
            continuationRequired: Boolean = false
        ) {
            checkpointDao.completeActive(completedGeneration, completedAt, continuationRequired)
            val activeJobIds = checkpointDao.checkpoints
                .filter { checkpoint -> checkpoint.status == MemoryLongTermCheckpointStatus.COMPLETED }
                .map(MemoryLongTermConsolidationCheckpoint::jobId)
                .toSet()
            jobDao.jobs
                .filter { job -> job.jobId in activeJobIds }
                .forEach { job ->
                    jobDao.forceUpdate(
                        job.copy(
                            status = MemoryMaintenanceJobStatus.SUCCEEDED,
                            nextRunAt = null
                        )
                    )
                }
        }
    }

    private companion object {
        const val NOW_EPOCH_SECONDS = 1_785_283_200L
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

private class InMemoryLongTermConsolidationDao : MemoryLongTermConsolidationDao {
    val checkpoints = mutableListOf<MemoryLongTermConsolidationCheckpoint>()
    var materialMutationCount: Long = 0
    var latestCommittedGeneration: Long = 0
    var lastAfterGeneration: Long? = null

    override suspend fun insertIgnore(checkpoint: MemoryLongTermConsolidationCheckpoint): Long {
        val conflicts = checkpoints.any { current ->
            current.checkpointId == checkpoint.checkpointId ||
                current.jobId == checkpoint.jobId ||
                (checkpoint.activeKey != null && current.activeKey == checkpoint.activeKey)
        }
        if (conflicts) return -1L
        checkpoints += checkpoint
        return checkpoints.size.toLong()
    }

    override suspend fun getById(checkpointId: String): MemoryLongTermConsolidationCheckpoint? =
        checkpoints.firstOrNull { checkpoint -> checkpoint.checkpointId == checkpointId }

    override suspend fun getByJobId(jobId: String): MemoryLongTermConsolidationCheckpoint? =
        checkpoints.firstOrNull { checkpoint -> checkpoint.jobId == jobId }

    override suspend fun getActive(
        activeKey: String,
        statuses: List<String>
    ): MemoryLongTermConsolidationCheckpoint? = checkpoints
        .filter { checkpoint -> checkpoint.activeKey == activeKey && checkpoint.status in statuses }
        .sortedWith(compareBy<MemoryLongTermConsolidationCheckpoint> { it.createdAt }.thenBy { it.checkpointId })
        .firstOrNull()

    override suspend fun getLatestCompleted(completedStatus: String): MemoryLongTermConsolidationCheckpoint? = checkpoints
        .filter { checkpoint -> checkpoint.status == completedStatus }
        .sortedWith(
            compareByDescending<MemoryLongTermConsolidationCheckpoint> { it.completedAt ?: Long.MIN_VALUE }
                .thenByDescending { it.checkpointId }
        )
        .firstOrNull()

    override suspend fun sumMaterialMutationsAfterGeneration(
        sourcePath: String,
        afterGeneration: Long
    ): Long {
        assertEquals(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, sourcePath)
        lastAfterGeneration = afterGeneration
        return materialMutationCount
    }

    override suspend fun getLatestCommittedGeneration(sourcePath: String): Long {
        assertEquals(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, sourcePath)
        return latestCommittedGeneration
    }

    override suspend fun advancePartitionCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedBaseSourceHash: String,
        expectedOrderedSnapshotHash: String,
        expectedPartitionCursor: Int,
        expectedProposalHash: String?,
        expectedProposalJson: String?,
        newPartitionCursor: Int,
        newProposalHash: String?,
        newProposalJson: String?,
        updatedAt: Long
    ): Int = error("Not used by scheduler tests")

    override suspend fun setContinuationRequiredCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedContinuationRequired: Boolean,
        continuationRequired: Boolean,
        updatedAt: Long
    ): Int = error("Not used by scheduler tests")

    override suspend fun bindResolvedModelCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        platformUid: String,
        modelId: String,
        resolvedAt: Long,
        updatedAt: Long
    ): Int = error("Not used by scheduler tests")

    override suspend fun recordAttemptCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        attempt: Int,
        updatedAt: Long
    ): Int = error("Not used by scheduler tests")

    override suspend fun recordErrorCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        lastError: String,
        updatedAt: Long
    ): Int = error("Not used by scheduler tests")

    override suspend fun transitionCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedResultSourceHash: String,
        expectedMutationGroupId: String?,
        newStatus: String,
        newActiveKey: String?,
        newResultSourceHash: String,
        newCompletedGeneration: Long?,
        newMutationGroupId: String?,
        lastError: String?,
        completedAt: Long?,
        updatedAt: Long
    ): Int = error("Not used by scheduler tests")

    fun seedCompleted(
        completedGeneration: Long,
        completedAt: Long
    ) {
        val hash = "0".repeat(64)
        checkpoints += MemoryLongTermConsolidationCheckpoint(
            checkpointId = "completed_$completedGeneration",
            jobId = "completed_job_$completedGeneration",
            activeKey = null,
            triggerReason = MemoryLongTermTriggerReason.WEEKLY_DUE,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            baseSourceHash = hash,
            resultSourceHash = hash,
            baseGeneration = completedGeneration,
            completedGeneration = completedGeneration,
            recallProjectionHash = "1".repeat(64),
            entryCount = 0,
            orderedSnapshotHash = "2".repeat(64),
            orderedEntryIdsJson = "[]",
            status = MemoryLongTermCheckpointStatus.COMPLETED,
            createdAt = completedAt,
            updatedAt = completedAt,
            completedAt = completedAt
        )
    }

    fun completeActive(
        completedGeneration: Long,
        completedAt: Long,
        continuationRequired: Boolean
    ) {
        val index = checkpoints.indexOfFirst { checkpoint ->
            checkpoint.activeKey != null && checkpoint.status in MemoryLongTermCheckpointStatus.ACTIVE
        }
        check(index >= 0) { "Missing active checkpoint" }
        checkpoints[index] = checkpoints[index].copy(
            activeKey = null,
            status = MemoryLongTermCheckpointStatus.COMPLETED,
            completedGeneration = completedGeneration,
            continuationRequired = continuationRequired,
            completedAt = completedAt,
            updatedAt = completedAt,
            rowVersion = checkpoints[index].rowVersion + 1
        )
    }
}
