package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.InMemoryMemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.dao.MemoryLongTermConsolidationDao
import cn.nabr.chatwithchat.data.database.entity.MemoryLongTermConsolidationCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.repository.SettingRepository
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLongTermConsolidationInvocationTest {
    @Test
    fun `partition budget durably yields and scans the entire clean corpus`() = runBlocking {
        val entries = (0 until 60).map { index ->
            entry(
                id = "canonical_$index",
                text = "unique${index}token",
                canonicalKey = "preference.unique_$index"
            )
        }
        val intelligence = InvocationRecordingIntelligence()
        val fixture = fixture(entries, intelligence)
        val observer = InvocationPartitionObserver()
        val service = fixture.service(
            observer = observer,
            maxPartitionsPerInvocation = 2,
            maxLlmCallsPerInvocation = 1
        )
        var invocationCount = 0
        var terminalResult: MemoryLongTermProcessResult? = null

        while (terminalResult == null) {
            val job = fixture.claim(invocationCount)
            val partitionsBefore = observer.partitionCount
            val callsBefore = intelligence.requests.size
            val result = service.process(job)
            invocationCount += 1

            assertTrue(observer.partitionCount - partitionsBefore <= 2)
            assertEquals(0, intelligence.requests.size - callsBefore)
            if (result.status == MemoryLongTermProcessResult.STATUS_DEFERRED) {
                val deferred = checkNotNull(fixture.jobDao.getById(job.jobId))
                assertEquals(MemoryMaintenanceJobStatus.PENDING, deferred.status)
                assertEquals(0, deferred.attempts)
                assertTrue(checkNotNull(fixture.checkpointDao.getByJobId(job.jobId)).continuationRequired)
            } else {
                terminalResult = result
            }
            check(invocationCount < 20) { "bounded long-term scan did not converge" }
        }

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, terminalResult.status)
        assertEquals("clean_no_op", terminalResult.reason)
        assertTrue(invocationCount > 1)
        val checkpoint = checkNotNull(fixture.checkpointDao.getByJobId(fixture.jobId))
        assertEquals(entries.size, checkpoint.partitionCursor)
        assertEquals(MemoryLongTermCheckpointStatus.COMPLETED, checkpoint.status)
        assertEquals(false, checkpoint.continuationRequired)
    }

    @Test
    fun `llm budget allows one request per invocation without spending retry attempts`() = runBlocking {
        val entries = (0 until 55).map { index ->
            entry(id = "unkeyed_$index", text = "lexeme${index}x", canonicalKey = null)
        }
        val intelligence = InvocationRecordingIntelligence {
            MemoryLongTermConsolidationProposal()
        }
        val fixture = fixture(entries, intelligence)
        val observer = InvocationPartitionObserver()
        val service = fixture.service(
            observer = observer,
            maxPartitionsPerInvocation = 4,
            maxLlmCallsPerInvocation = 1
        )
        var invocationCount = 0
        var terminalResult: MemoryLongTermProcessResult? = null

        while (terminalResult == null) {
            val job = fixture.claim(invocationCount)
            val callsBefore = intelligence.requests.size
            val result = service.process(job)
            invocationCount += 1

            assertTrue(intelligence.requests.size - callsBefore <= 1)
            if (result.status == MemoryLongTermProcessResult.STATUS_DEFERRED) {
                val deferred = checkNotNull(fixture.jobDao.getById(job.jobId))
                assertEquals(MemoryMaintenanceJobStatus.PENDING, deferred.status)
                assertEquals(0, deferred.attempts)
            } else {
                terminalResult = result
            }
            check(invocationCount < 20) { "bounded long-term LLM scan did not converge" }
        }

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, terminalResult.status)
        assertTrue(invocationCount > 1)
        assertTrue(intelligence.requests.size > 1)
        val checkpoint = checkNotNull(fixture.checkpointDao.getByJobId(fixture.jobId))
        assertEquals(entries.size, checkpoint.partitionCursor)
        assertEquals(MemoryLongTermCheckpointStatus.COMPLETED, checkpoint.status)
        assertEquals(1, checkpoint.attempt)
    }

    @Test
    fun `operation cap recovery schedules continuation from every durable commit point`() = runBlocking {
        InvocationOperationCrashPoint.entries.forEach { crashPoint ->
            val canonicalKey = "communication.response_style"
            val entries = (0 until MemoryControlledOperationPolicy.MAX_OPERATIONS + 8).map { index ->
                entry(
                    id = "operation_cap_${crashPoint.name.lowercase()}_$index",
                    text = "Response style revision $index",
                    canonicalKey = canonicalKey
                )
            }
            val fixture = fixture(entries, InvocationRecordingIntelligence())
            val claimed = fixture.claim(0)

            val interrupted = runCatching {
                fixture.service(
                    observer = InvocationOperationCrashObserver(crashPoint),
                    maxPartitionsPerInvocation = 4,
                    maxLlmCallsPerInvocation = 1
                ).process(claimed)
            }

            assertEquals(crashPoint.error, interrupted.exceptionOrNull()?.message)
            assertTrue(checkNotNull(fixture.checkpointDao.getByJobId(claimed.jobId)).continuationRequired)

            val resumed = fixture.service(
                observer = MemoryLongTermConsolidationCommitObserver.None,
                maxPartitionsPerInvocation = 4,
                maxLlmCallsPerInvocation = 1
            ).process(checkNotNull(fixture.jobDao.getById(claimed.jobId)))

            assertTrue(
                resumed.status in setOf(
                    MemoryLongTermProcessResult.STATUS_SUCCEEDED,
                    MemoryLongTermProcessResult.STATUS_DUPLICATE
                )
            )
            val continuation = checkNotNull(
                fixture.maintenanceScheduler.claimNextRunnable(
                    family = MemoryMaintenanceJobFamily.SEMANTIC,
                    leaseOwner = "operation-cap-continuation-${crashPoint.name.lowercase()}"
                )
            )
            assertNotEquals(claimed.jobId, continuation.jobId)
            assertEquals(
                MemoryLongTermTriggerReason.CONTINUATION,
                checkNotNull(fixture.checkpointDao.getByJobId(continuation.jobId)).triggerReason
            )
        }
    }

    private suspend fun fixture(
        entries: List<MarkdownMemoryEntry>,
        intelligence: InvocationRecordingIntelligence
    ): InvocationFixture {
        val fileStore = MemoryFileStore(
            paths = MemoryFilePaths(Files.createTempDirectory("memory-long-term-invocation").toFile()),
            clock = FIXED_CLOCK
        )
        val codec = MarkdownMemoryCodec()
        fileStore.ensureStore().getOrThrow()
        fileStore.replaceLongTermMemory(codec.renderLongTerm(entries)).getOrThrow()
        val checkpointDao = InvocationCheckpointDao()
        val jobDao = InMemoryMaintenanceJobDao()
        val workEnqueuer = RecordingWorkEnqueuer()
        val settingRepository = settingRepository()
        val maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
        val longTermScheduler = MemoryLongTermConsolidationScheduler(
            memoryFileStore = fileStore,
            markdownMemoryCodec = codec,
            checkpointDao = checkpointDao,
            maintenanceScheduler = maintenanceScheduler,
            settingRepository = settingRepository,
            workEnqueuer = workEnqueuer,
            clock = FIXED_CLOCK
        )
        val mutationCoordinator = MemoryMutationCoordinator(
            recoveryDao = InMemoryMemoryRecoveryDao(),
            memoryFileStore = fileStore,
            maintenanceScheduler = maintenanceScheduler,
            workEnqueuer = workEnqueuer,
            clock = FIXED_CLOCK
        )
        val plan = longTermScheduler.ensureScheduled()
        assertTrue(plan.scheduled)
        assertNotNull(plan.jobId)
        return InvocationFixture(
            fileStore = fileStore,
            codec = codec,
            checkpointDao = checkpointDao,
            jobDao = jobDao,
            settingRepository = settingRepository,
            maintenanceScheduler = maintenanceScheduler,
            longTermScheduler = longTermScheduler,
            mutationCoordinator = mutationCoordinator,
            intelligence = intelligence,
            jobId = checkNotNull(plan.jobId)
        )
    }

    private fun settingRepository(): SettingRepository {
        val handler = java.lang.reflect.InvocationHandler { _, method, arguments ->
            when (method.name) {
                "fetchMemoryEnabled" -> true
                "fetchMemoryModelPreference" -> MemoryModelPreference.Auto
                "fetchPlatformV2s" -> listOf(PLATFORM)
                "fetchPlatformModels" -> if (method.parameterCount == 1) {
                    listOf(PLATFORM_MODEL)
                } else {
                    listOf(PLATFORM_MODEL).filter { model -> model.platformUid == arguments?.firstOrNull() }
                }
                else -> error("Unexpected SettingRepository call: ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            SettingRepository::class.java.classLoader,
            arrayOf(SettingRepository::class.java),
            handler
        ) as SettingRepository
    }

    private fun entry(
        id: String,
        text: String,
        canonicalKey: String?
    ): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = "communication_style",
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = NOW_EPOCH_SECONDS - 60,
        updatedAt = NOW_EPOCH_SECONDS - 30,
        canonicalKey = canonicalKey,
        scope = MemoryScope.GENERAL,
        lastObservedAt = NOW_EPOCH_SECONDS - 30,
        recallState = MemoryRecallState.QUERY
    )

    private data class InvocationFixture(
        val fileStore: MemoryFileStore,
        val codec: MarkdownMemoryCodec,
        val checkpointDao: InvocationCheckpointDao,
        val jobDao: InMemoryMaintenanceJobDao,
        val settingRepository: SettingRepository,
        val maintenanceScheduler: MemoryMaintenanceScheduler,
        val longTermScheduler: MemoryLongTermConsolidationScheduler,
        val mutationCoordinator: MemoryMutationCoordinator,
        val intelligence: InvocationRecordingIntelligence,
        val jobId: String
    ) {
        suspend fun claim(invocation: Int): MemoryMaintenanceJob = checkNotNull(
            maintenanceScheduler.claimNextRunnable(
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                leaseOwner = "long-term-invocation-$invocation"
            )
        )

        fun service(
            observer: MemoryLongTermConsolidationCommitObserver,
            maxPartitionsPerInvocation: Int,
            maxLlmCallsPerInvocation: Int
        ): MemoryLongTermConsolidationService = MemoryLongTermConsolidationService(
            checkpointDao = checkpointDao,
            maintenanceScheduler = maintenanceScheduler,
            settingRepository = settingRepository,
            modelResolver = MemoryModelResolver(settingRepository),
            memoryIntelligence = intelligence,
            memoryFileStore = fileStore,
            markdownMemoryCodec = codec,
            operationController = MemoryLongTermConsolidationOperationController(codec),
            memoryMutationCoordinator = mutationCoordinator,
            longTermScheduler = longTermScheduler,
            commitObserver = observer,
            clock = FIXED_CLOCK,
            maxPartitionsPerInvocation = maxPartitionsPerInvocation,
            maxLlmCallsPerInvocation = maxLlmCallsPerInvocation
        )
    }

    private companion object {
        const val NOW_EPOCH_SECONDS = 1_785_283_200L
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(NOW_EPOCH_SECONDS), ZoneOffset.UTC)
        val PLATFORM = PlatformV2(
            uid = "memory-platform",
            name = "Memory Platform",
            compatibleType = ClientType.OPENAI,
            enabled = true,
            apiUrl = "https://memory.example.test/v1",
            token = "token",
            model = "memory-model"
        )
        val PLATFORM_MODEL = PlatformModelV2(
            platformUid = PLATFORM.uid,
            modelId = PLATFORM.model,
            displayName = "Memory Model",
            enabled = true
        )
    }
}

private class InvocationRecordingIntelligence(
    private val proposal: (MemoryLongTermConsolidationPartitionRequest) -> MemoryLongTermConsolidationProposal? = {
        error("Long-term intelligence was not expected")
    }
) : MemoryIntelligence {
    val requests = mutableListOf<MemoryLongTermConsolidationPartitionRequest>()

    override suspend fun consolidateMemoryBatch(
        request: MemoryBatchConsolidationRequest,
        resolvedPlatform: PlatformV2
    ): MemoryBatchConsolidationProposal? = error("Batch consolidation was not expected")

    override suspend fun distillDailyMemory(
        request: MemoryDailyDistillationFrozenInput,
        resolvedPlatform: PlatformV2
    ): MemoryDailyDistillationProposal? = error("Daily distillation was not expected")

    override suspend fun consolidateLongTermMemory(
        request: MemoryLongTermConsolidationPartitionRequest,
        resolvedPlatform: PlatformV2
    ): MemoryLongTermConsolidationProposal? {
        requests += request
        return proposal(request)
    }
}

private class InvocationPartitionObserver : MemoryLongTermConsolidationCommitObserver {
    var partitionCount: Int = 0

    override suspend fun afterPartitionPersisted(checkpoint: MemoryLongTermConsolidationCheckpoint) {
        partitionCount += 1
    }
}

private enum class InvocationOperationCrashPoint(val error: String) {
    PREPARED("crash_after_operation_cap_prepared"),
    CANONICAL_FILE_COMMITTED("crash_after_operation_cap_file_committed"),
    CHECKPOINT_COMPLETED("crash_after_operation_cap_checkpoint_completed")
}

private class InvocationOperationCrashObserver(
    private val crashPoint: InvocationOperationCrashPoint
) : MemoryLongTermConsolidationCommitObserver {
    override suspend fun afterPrepared(mutation: MemoryPreparedMutation) {
        crashAt(InvocationOperationCrashPoint.PREPARED)
    }

    override suspend fun afterCanonicalFileCommit(mutation: MemoryPreparedMutation) {
        crashAt(InvocationOperationCrashPoint.CANONICAL_FILE_COMMITTED)
    }

    override suspend fun afterCheckpointCompletion(checkpoint: MemoryLongTermConsolidationCheckpoint) {
        crashAt(InvocationOperationCrashPoint.CHECKPOINT_COMPLETED)
    }

    private fun crashAt(point: InvocationOperationCrashPoint) {
        if (crashPoint == point) error(point.error)
    }
}

private class InvocationCheckpointDao : MemoryLongTermConsolidationDao {
    private val checkpoints = mutableListOf<MemoryLongTermConsolidationCheckpoint>()

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

    override suspend fun sumMaterialMutationsAfterGeneration(sourcePath: String, afterGeneration: Long): Long = 0

    override suspend fun getLatestCommittedGeneration(sourcePath: String): Long = 0

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
    ): Int = update(checkpointId) { current ->
        current.takeIf {
            it.status == expectedStatus &&
                it.rowVersion == expectedRowVersion &&
                it.baseSourceHash == expectedBaseSourceHash &&
                it.orderedSnapshotHash == expectedOrderedSnapshotHash &&
                it.partitionCursor == expectedPartitionCursor &&
                it.proposalHash == expectedProposalHash &&
                it.proposalJson == expectedProposalJson &&
                newPartitionCursor in it.partitionCursor..it.entryCount
        }?.copy(
            partitionCursor = newPartitionCursor,
            proposalHash = newProposalHash,
            proposalJson = newProposalJson,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
    }

    override suspend fun setContinuationRequiredCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedContinuationRequired: Boolean,
        continuationRequired: Boolean,
        updatedAt: Long
    ): Int = update(checkpointId) { current ->
        current.takeIf {
            it.status == expectedStatus &&
                it.rowVersion == expectedRowVersion &&
                it.continuationRequired == expectedContinuationRequired
        }?.copy(
            continuationRequired = continuationRequired,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
    }

    override suspend fun bindResolvedModelCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        platformUid: String,
        modelId: String,
        resolvedAt: Long,
        updatedAt: Long
    ): Int = update(checkpointId) { current ->
        current.takeIf {
            it.status == expectedStatus &&
                it.rowVersion == expectedRowVersion &&
                it.resolvedPlatformUid == null &&
                it.resolvedModelId == null &&
                it.resolvedAt == null
        }?.copy(
            resolvedPlatformUid = platformUid,
            resolvedModelId = modelId,
            resolvedAt = resolvedAt,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
    }

    override suspend fun recordAttemptCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        attempt: Int,
        updatedAt: Long
    ): Int = update(checkpointId) { current ->
        current.takeIf {
            it.status == expectedStatus && it.rowVersion == expectedRowVersion
        }?.copy(
            attempt = attempt,
            lastError = null,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
    }

    override suspend fun recordErrorCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        lastError: String,
        updatedAt: Long
    ): Int = update(checkpointId) { current ->
        current.takeIf {
            it.status == expectedStatus && it.rowVersion == expectedRowVersion
        }?.copy(
            lastError = lastError,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
    }

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
    ): Int = update(checkpointId) { current ->
        current.takeIf {
            it.status == expectedStatus &&
                it.rowVersion == expectedRowVersion &&
                it.resultSourceHash == expectedResultSourceHash &&
                it.mutationGroupId == expectedMutationGroupId
        }?.copy(
            status = newStatus,
            activeKey = newActiveKey,
            resultSourceHash = newResultSourceHash,
            completedGeneration = newCompletedGeneration,
            mutationGroupId = newMutationGroupId,
            lastError = lastError,
            completedAt = completedAt,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
    }

    private fun update(
        checkpointId: String,
        transform: (MemoryLongTermConsolidationCheckpoint) -> MemoryLongTermConsolidationCheckpoint?
    ): Int {
        val index = checkpoints.indexOfFirst { checkpoint -> checkpoint.checkpointId == checkpointId }
        if (index == -1) return 0
        val updated = transform(checkpoints[index]) ?: return 0
        checkpoints[index] = updated
        return 1
    }
}
