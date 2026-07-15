package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.InMemoryMemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.InMemoryMemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.dao.MemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.entity.MemoryDistillationCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDailyDistillationServiceTest {

    @Test
    fun `daily evidence is hidden before distillation and visible after canonical commit`() = runBlocking {
        val fixture = fixture(proposal = createProposal())
        val lexical = MarkdownLexicalRetriever(MemoryCorpusSnapshotter(fixture.fileStore, MemoryChunker()))
        assertTrue(lexical.retrieve(recallRequest("silver compass")).getOrThrow().isEmpty())
        val dailyBefore = fixture.fileStore.readDailyMemory(YESTERDAY).getOrThrow()

        val result = fixture.service.process(fixture.claimSemanticJob())

        assertEquals(MemoryDailyDistillationProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, fixture.intelligence.distillationCalls)
        assertEquals("Prefers the silver compass response style.", lexical.retrieve(recallRequest("silver compass")).getOrThrow().single().text)
        assertEquals(dailyBefore, fixture.fileStore.readDailyMemory(YESTERDAY).getOrThrow())
        assertEquals(MemoryDistillationCheckpointStatus.COMPLETED, fixture.checkpoint().status)
        assertTrue(fixture.jobDao.jobs.any { job -> job.type == MemoryMaintenanceJobType.SYNC_VECTOR_INDEX })
    }

    @Test
    fun `prepared mutation replay skips a second semantic call`() = runBlocking {
        val fixture = fixture(proposal = createProposal())
        fixture.prepareMutation(reconcile = false)
        fixture.intelligence.distillationCalls = 0

        val result = fixture.service.process(fixture.claimSemanticJob())

        assertEquals(MemoryDailyDistillationProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(0, fixture.intelligence.distillationCalls)
        assertEquals(MemoryDistillationCheckpointStatus.COMPLETED, fixture.checkpoint().status)
        assertTrue(fixture.fileStore.readLongTermMemory().getOrThrow().contains("silver compass"))
    }

    @Test
    fun `missing staged daily target preserves terminal reason without semantic replay`() = runBlocking {
        val fixture = fixture(proposal = createProposal())
        val mutation = fixture.prepareMutation(reconcile = false)
        val receipt = mutation.receipts.single()
        val root = fixture.fileStore.ensureStore().getOrThrow().rootDirectory
        Files.delete(root.resolve(receipt.stagedTargetPath).toPath())
        fixture.intelligence.distillationCalls = 0

        val result = fixture.service.process(fixture.claimSemanticJob())

        assertEquals(MemoryDailyDistillationProcessResult.STATUS_TERMINAL, result.status)
        assertEquals(MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING, result.reason)
        assertEquals(0, fixture.intelligence.distillationCalls)
        assertEquals(MemoryDistillationCheckpointStatus.CONFLICT, fixture.checkpoint().status)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, fixture.semanticJob().status)
        assertEquals(MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING, fixture.semanticJob().lastError)
        assertFalse(fixture.fileStore.readLongTermMemory().getOrThrow().contains("silver compass"))
    }

    @Test
    fun `canonical commit replay advances checkpoint without a second semantic call`() = runBlocking {
        val fixture = fixture(proposal = createProposal())
        fixture.prepareMutation(reconcile = true)
        fixture.intelligence.distillationCalls = 0

        val result = fixture.service.process(fixture.claimSemanticJob())

        assertEquals(MemoryDailyDistillationProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(0, fixture.intelligence.distillationCalls)
        assertEquals(MemoryDistillationCheckpointStatus.COMPLETED, fixture.checkpoint().status)
        assertEquals(1, fixture.codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries.size)
    }

    @Test
    fun `completed checkpoint replay acknowledges mutation and schedules changed daily source`() = runBlocking {
        val fixture = fixture(proposal = createProposal())
        val originalJob = fixture.semanticJob()
        val mutation = fixture.prepareMutation(reconcile = true)
        fixture.completeCheckpoint(mutation)
        val originalPlanCount = fixture.jobDao.jobs.count { job ->
            job.type == MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION
        }
        fixture.fileStore.appendDailyNote(
            fixture.codec.renderDailyAppend(
                listOf(
                    MarkdownMemoryEntry(
                        id = "new-after-completion",
                        text = "A newer stable preference arrived after the completed batch.",
                        type = "stable_profile",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT
                    )
                )
            ),
            YESTERDAY
        ).getOrThrow()
        fixture.intelligence.distillationCalls = 0

        val result = fixture.service.process(fixture.claimSemanticJob())

        assertEquals(MemoryDailyDistillationProcessResult.STATUS_DUPLICATE, result.status)
        assertEquals(0, fixture.intelligence.distillationCalls)
        assertFalse(
            fixture.mutationCoordinator.findBySemanticJobId(originalJob.jobId)?.group?.state ==
                MemoryMutationState.SEMANTIC_ACK_PENDING
        )
        assertTrue(
            fixture.jobDao.jobs.count { job ->
                job.type == MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION
            } > originalPlanCount
        )
    }

    @Test
    fun `empty proposal creates a durable semantic marker without changing Markdown`() = runBlocking {
        val fixture = fixture(proposal = MemoryDailyDistillationProposal())
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()

        val result = fixture.service.process(fixture.claimSemanticJob())

        assertEquals(MemoryDailyDistillationProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(before, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(MemoryDistillationCheckpointStatus.COMPLETED, fixture.checkpoint().status)
        val mutation = fixture.mutationCoordinator.findBySemanticJobId(fixture.semanticJob().jobId)
        assertTrue(mutation?.receipts?.isEmpty() == true)
    }

    @Test
    fun `stale target base makes zero semantic calls and replans`() = runBlocking {
        val fixture = fixture(proposal = createProposal())
        val originalJob = fixture.semanticJob()
        fixture.fileStore.appendLongTermMemory("Manual concurrent edit.").getOrThrow()

        val result = fixture.service.process(fixture.claimSemanticJob())

        assertEquals(MemoryDailyDistillationProcessResult.STATUS_TERMINAL, result.status)
        assertEquals(0, fixture.intelligence.distillationCalls)
        assertEquals(MemoryMaintenanceJobStatus.DISMISSED, fixture.jobDao.jobs.single { job -> job.jobId == originalJob.jobId }.status)
        assertTrue(
            fixture.jobDao.jobs.any { job ->
                job.type == MemoryMaintenanceJobType.DISTILL_DAILY_NOTES && job.jobId != originalJob.jobId
            }
        )
    }

    @Test
    fun `changed daily source makes zero semantic calls and preserves the newer file`() = runBlocking {
        val fixture = fixture(proposal = createProposal())
        fixture.fileStore.appendDailyNote(
            fixture.codec.renderDailyAppend(
                listOf(
                    MarkdownMemoryEntry(
                        id = "newer-evidence",
                        text = "Newer closed-day evidence.",
                        type = "stable_profile",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT
                    )
                )
            ),
            YESTERDAY
        ).getOrThrow()

        val result = fixture.service.process(fixture.claimSemanticJob())

        assertEquals(MemoryDailyDistillationProcessResult.STATUS_TERMINAL, result.status)
        assertEquals(0, fixture.intelligence.distillationCalls)
        assertTrue(fixture.fileStore.readDailyMemory(YESTERDAY).getOrThrow().contains("Newer closed-day evidence"))
    }

    @Test
    fun `memory disabled dismisses distillation without a semantic call or retry`() = runBlocking {
        val fixture = fixture(proposal = createProposal())
        fixture.settings.memoryEnabled = false

        val result = fixture.service.process(fixture.claimSemanticJob())

        assertEquals(MemoryDailyDistillationProcessResult.STATUS_TERMINAL, result.status)
        assertEquals(0, fixture.intelligence.distillationCalls)
        val job = fixture.jobDao.jobs.single { item -> item.type == MemoryMaintenanceJobType.DISTILL_DAILY_NOTES }
        assertEquals(MemoryMaintenanceJobStatus.DISMISSED, job.status)
        assertEquals("memory_disabled", job.lastError)
        assertEquals(null, job.nextRunAt)
    }

    @Test
    fun `startup recovery finalizes daily checkpoint without turn batch completion`() = runBlocking {
        val fixture = fixture(proposal = createProposal())
        val mutation = fixture.prepareMutation(reconcile = true)
        val recovery = MemoryMutationRecoveryService(
            memoryMutationCoordinator = fixture.mutationCoordinator,
            turnBatchDao = ThrowingTurnBatchDao(),
            maintenanceScheduler = fixture.maintenanceScheduler,
            dailyDistillationFinalizer = fixture.service,
            clock = FIXED_CLOCK
        )

        val result = recovery.recoverIncomplete(scheduleRetry = false)

        assertEquals(1, result.recoveredSemanticCount)
        assertEquals(0, result.failedCount)
        assertEquals(MemoryDistillationCheckpointStatus.COMPLETED, fixture.checkpoint().status)
        assertFalse(
            fixture.mutationCoordinator.findBySemanticJobId(fixture.semanticJob().jobId)?.group?.state ==
                MemoryMutationState.SEMANTIC_ACK_PENDING
        )
        assertEquals(mutation.group.groupId, fixture.checkpoint().mutationGroupId)
    }

    @Test
    fun `process death after prepared receipt recovers without a second semantic call`() = runBlocking {
        val clock = MutableDailyDistillationClock(FIXED_CLOCK.instant())
        val fixture = fixture(
            proposal = createProposal(),
            clock = clock,
            commitObserver = OneShotDailyDistillationCommitObserver(
                DailyDistillationInterruptionPoint.AFTER_PREPARED
            )
        )
        val job = fixture.claimSemanticJob()

        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()

        assertTrue(failure is SimulatedDailyDistillationProcessDeath)
        assertEquals(1, fixture.intelligence.distillationCalls)
        assertEquals(MemoryDistillationCheckpointStatus.PENDING, fixture.checkpoint().status)
        assertEquals(
            MemoryMutationState.PREPARED,
            fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId)?.state
        )
        assertFalse(fixture.fileStore.readLongTermMemory().getOrThrow().contains("silver compass"))

        val recovery = fixture.recoverAfterProcessDeath(job, clock)

        assertEquals(1, recovery.recoveredSemanticCount)
        assertEquals(0, recovery.failedCount)
        assertEquals(1, fixture.intelligence.distillationCalls)
        fixture.assertRecoveredAndAcknowledged(job)
        assertTrue(fixture.fileStore.readLongTermMemory().getOrThrow().contains("silver compass"))
    }

    @Test
    fun `process death after canonical commit recovers without a second semantic call`() = runBlocking {
        val clock = MutableDailyDistillationClock(FIXED_CLOCK.instant())
        val fixture = fixture(
            proposal = createProposal(),
            clock = clock,
            commitObserver = OneShotDailyDistillationCommitObserver(
                DailyDistillationInterruptionPoint.AFTER_CANONICAL_COMMIT
            )
        )
        val job = fixture.claimSemanticJob()

        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()

        assertTrue(failure is SimulatedDailyDistillationProcessDeath)
        assertEquals(1, fixture.intelligence.distillationCalls)
        assertEquals(MemoryDistillationCheckpointStatus.PREPARED, fixture.checkpoint().status)
        assertEquals(
            MemoryMutationState.SEMANTIC_ACK_PENDING,
            fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId)?.state
        )
        assertTrue(fixture.fileStore.readLongTermMemory().getOrThrow().contains("silver compass"))

        val recovery = fixture.recoverAfterProcessDeath(job, clock)

        assertEquals(1, recovery.recoveredSemanticCount)
        assertEquals(0, recovery.failedCount)
        assertEquals(1, fixture.intelligence.distillationCalls)
        fixture.assertRecoveredAndAcknowledged(job)
        assertEquals(1, fixture.codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries.size)
    }

    @Test
    fun `process death after checkpoint completion recovers job and semantic acknowledgement`() = runBlocking {
        val clock = MutableDailyDistillationClock(FIXED_CLOCK.instant())
        val fixture = fixture(
            proposal = createProposal(),
            clock = clock,
            commitObserver = OneShotDailyDistillationCommitObserver(
                DailyDistillationInterruptionPoint.AFTER_CHECKPOINT_COMPLETION
            )
        )
        val job = fixture.claimSemanticJob()

        val failure = runCatching { fixture.service.process(job) }.exceptionOrNull()

        assertTrue(failure is SimulatedDailyDistillationProcessDeath)
        assertEquals(1, fixture.intelligence.distillationCalls)
        assertEquals(MemoryDistillationCheckpointStatus.COMPLETED, fixture.checkpoint().status)
        assertEquals(MemoryMaintenanceJobStatus.RUNNING, fixture.jobDao.getById(job.jobId)?.status)
        assertEquals(
            MemoryMutationState.SEMANTIC_ACK_PENDING,
            fixture.recoveryDao.getMutationGroupBySemanticJobId(job.jobId)?.state
        )

        val recovery = fixture.recoverAfterProcessDeath(job, clock)

        assertEquals(1, recovery.recoveredSemanticCount)
        assertEquals(0, recovery.failedCount)
        assertEquals(1, fixture.intelligence.distillationCalls)
        fixture.assertRecoveredAndAcknowledged(job)
        assertEquals(1, fixture.codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries.size)
    }

    private suspend fun fixture(
        proposal: MemoryDailyDistillationProposal,
        clock: Clock = FIXED_CLOCK,
        commitObserver: MemoryDailyDistillationCommitObserver = MemoryDailyDistillationCommitObserver.None
    ): Fixture {
        val codec = MarkdownMemoryCodec()
        val fileStore = MemoryFileStore(
            MemoryFilePaths(Files.createTempDirectory("daily-distillation-service").toFile()),
            clock
        )
        fileStore.ensureStore().getOrThrow()
        fileStore.appendDailyNote(
            codec.renderDailyAppend(
                listOf(
                    MarkdownMemoryEntry(
                        id = "daily-evidence",
                        text = "The user explicitly prefers the silver compass response style.",
                        type = "communication_style",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        createdAt = 1,
                        updatedAt = 2
                    )
                )
            ),
            YESTERDAY
        ).getOrThrow()
        val recoveryDao = InMemoryMemoryRecoveryDao()
        val jobDao = InMemoryMaintenanceJobDao()
        val workEnqueuer = RecordingWorkEnqueuer()
        val settings = FakeMaintenanceSettingRepository(memoryEnabled = true)
        val maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, clock)
        val dailyScheduler = MemoryDailyDistillationScheduler(
            memoryFileStore = fileStore,
            markdownMemoryCodec = codec,
            recoveryDao = recoveryDao,
            maintenanceScheduler = maintenanceScheduler,
            settingRepository = settings,
            workEnqueuer = workEnqueuer,
            clock = clock
        )
        dailyScheduler.ensurePlanningJobs()
        dailyScheduler.processPlan(
            jobDao.jobs.single { job ->
                job.type == MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION && job.nextRunAt == null
            }
        )
        val mutationCoordinator = MemoryMutationCoordinator(
            recoveryDao = recoveryDao,
            memoryFileStore = fileStore,
            maintenanceScheduler = maintenanceScheduler,
            workEnqueuer = workEnqueuer,
            clock = clock
        )
        val semanticPayload = STRICT_JSON.decodeFromString<MemoryDailyDistillationJobPayload>(
            jobDao.jobs.single { job -> job.type == MemoryMaintenanceJobType.DISTILL_DAILY_NOTES }.payloadJson
        )
        val evidenceKey = semanticPayload.input.dailyEvidence.single().evidenceKey
        val intelligence = FakeMemoryIntelligence(
            distillationProposal = proposal.copy(
                operations = proposal.operations.map { operation ->
                    operation.copy(evidenceKeys = listOf(evidenceKey))
                }
            )
        )
        val controller = MemoryDailyDistillationOperationController(codec, targetIndexFingerprint = "f".repeat(64))
        val service = MemoryDailyDistillationService(
            recoveryDao = recoveryDao,
            maintenanceScheduler = maintenanceScheduler,
            settingRepository = settings,
            memoryIntelligence = intelligence,
            memoryFileStore = fileStore,
            operationController = controller,
            memoryMutationCoordinator = mutationCoordinator,
            dailyDistillationScheduler = dailyScheduler,
            commitObserver = commitObserver,
            clock = clock
        )
        return Fixture(
            codec = codec,
            fileStore = fileStore,
            recoveryDao = recoveryDao,
            jobDao = jobDao,
            settings = settings,
            maintenanceScheduler = maintenanceScheduler,
            dailyScheduler = dailyScheduler,
            mutationCoordinator = mutationCoordinator,
            intelligence = intelligence,
            operationController = controller,
            service = service
        )
    }

    private fun createProposal() = MemoryDailyDistillationProposal(
        operations = listOf(
            MemoryDailyDistillationOperation(
                action = MemoryDailyDistillationAction.CREATE,
                text = "Prefers the silver compass response style.",
                type = "communication_style",
                sensitivity = MemorySensitivity.NORMAL,
                source = MemorySource.EXPLICIT_USER_STATEMENT,
                evidenceKeys = listOf("placeholder"),
                reason = "stable explicit preference"
            )
        )
    )

    private fun recallRequest(query: String) = MemoryRetrievalRequest(
        corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
        query = query,
        strategy = MemoryRetrievalStrategy.LEXICAL
    )

    private data class Fixture(
        val codec: MarkdownMemoryCodec,
        val fileStore: MemoryFileStore,
        val recoveryDao: InMemoryMemoryRecoveryDao,
        val jobDao: InMemoryMaintenanceJobDao,
        val settings: FakeMaintenanceSettingRepository,
        val maintenanceScheduler: MemoryMaintenanceScheduler,
        val dailyScheduler: MemoryDailyDistillationScheduler,
        val mutationCoordinator: MemoryMutationCoordinator,
        val intelligence: FakeMemoryIntelligence,
        val operationController: MemoryDailyDistillationOperationController,
        val service: MemoryDailyDistillationService
    ) {
        fun semanticJob(): MemoryMaintenanceJob = jobDao.jobs.first { job ->
            job.type == MemoryMaintenanceJobType.DISTILL_DAILY_NOTES
        }

        suspend fun claimSemanticJob(): MemoryMaintenanceJob = checkNotNull(
            maintenanceScheduler.claimNextRunnable(MemoryMaintenanceJobFamily.SEMANTIC, "test-owner")
        )

        suspend fun checkpoint() = checkNotNull(
            recoveryDao.getDistillationCheckpointBySemanticJobId(semanticJob().jobId)
        )

        suspend fun prepareMutation(reconcile: Boolean): MemoryPreparedMutation {
            val job = semanticJob()
            val payload = STRICT_JSON.decodeFromString<MemoryDailyDistillationJobPayload>(job.payloadJson)
            val evidenceKey = payload.input.dailyEvidence.single().evidenceKey
            val proposal = checkNotNull(intelligence.distillationProposal).copy(
                operations = checkNotNull(intelligence.distillationProposal).operations.map { operation ->
                    operation.copy(evidenceKeys = listOf(evidenceKey))
                }
            )
            val operations = operationController.validate(payload.input, proposal.operations)
            val rendered = operationController.render(
                payload.input,
                fileStore.readLongTermMemory().getOrThrow(),
                operations
            )
            val mutation = mutationCoordinator.prepare(job.jobId, payload.input.batchId, rendered.targets)
            if (reconcile) mutationCoordinator.reconcile(mutation)
            return mutation
        }

        suspend fun completeCheckpoint(mutation: MemoryPreparedMutation) {
            var current = checkpoint()
            val targetHash = mutation.receipts.singleOrNull { receipt ->
                receipt.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
            }?.targetSourceHash ?: current.targetBaseHash
            assertEquals(
                1,
                recoveryDao.transitionDistillationCheckpointCas(
                    checkpointId = current.checkpointId,
                    expectedStatus = current.status,
                    expectedRowVersion = current.rowVersion,
                    expectedDailySourcePath = current.dailySourcePath,
                    expectedDailySourceHash = current.dailySourceHash,
                    expectedBatchKey = current.batchKey,
                    expectedSemanticJobId = current.semanticJobId,
                    expectedTargetSourcePath = current.targetSourcePath,
                    expectedTargetBaseHash = current.targetBaseHash,
                    expectedTargetSourceHash = current.targetSourceHash,
                    expectedMutationGroupId = current.mutationGroupId,
                    newStatus = MemoryDistillationCheckpointStatus.PREPARED,
                    newTargetSourceHash = targetHash,
                    mutationGroupId = mutation.group.groupId,
                    updatedAt = FIXED_CLOCK.instant().epochSecond,
                    processedAt = null
                )
            )
            current = checkpoint()
            assertEquals(
                1,
                recoveryDao.transitionDistillationCheckpointCas(
                    checkpointId = current.checkpointId,
                    expectedStatus = current.status,
                    expectedRowVersion = current.rowVersion,
                    expectedDailySourcePath = current.dailySourcePath,
                    expectedDailySourceHash = current.dailySourceHash,
                    expectedBatchKey = current.batchKey,
                    expectedSemanticJobId = current.semanticJobId,
                    expectedTargetSourcePath = current.targetSourcePath,
                    expectedTargetBaseHash = current.targetBaseHash,
                    expectedTargetSourceHash = current.targetSourceHash,
                    expectedMutationGroupId = current.mutationGroupId,
                    newStatus = MemoryDistillationCheckpointStatus.COMPLETED,
                    newTargetSourceHash = targetHash,
                    mutationGroupId = mutation.group.groupId,
                    updatedAt = FIXED_CLOCK.instant().epochSecond,
                    processedAt = FIXED_CLOCK.instant().epochSecond
                )
            )
        }

        suspend fun recoverAfterProcessDeath(
            job: MemoryMaintenanceJob,
            clock: MutableDailyDistillationClock
        ): MemoryMutationRecoveryResult {
            clock.setInstant(Instant.ofEpochSecond(checkNotNull(job.leaseExpiresAt) + 1L))
            check(maintenanceScheduler.resetExpiredRunningJobs() == 1)
            return MemoryMutationRecoveryService(
                memoryMutationCoordinator = mutationCoordinator,
                turnBatchDao = ThrowingTurnBatchDao(),
                maintenanceScheduler = maintenanceScheduler,
                dailyDistillationFinalizer = service,
                clock = clock
            ).recoverIncomplete(scheduleRetry = false)
        }

        suspend fun assertRecoveredAndAcknowledged(job: MemoryMaintenanceJob) {
            assertEquals(MemoryDistillationCheckpointStatus.COMPLETED, checkpoint().status)
            assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, jobDao.getById(job.jobId)?.status)
            assertEquals(
                MemoryMutationState.INDEX_PENDING,
                recoveryDao.getMutationGroupBySemanticJobId(job.jobId)?.state
            )
        }
    }

    private class ThrowingTurnBatchDao : MemoryTurnBatchDao by InMemoryMemoryTurnBatchDao() {
        override suspend fun completeClaimedBatch(jobId: String, updatedAt: Long): Boolean =
            error("Daily distillation recovery must not finalize a turn batch")
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-12T02:00:00Z"), ZoneOffset.UTC)
        val YESTERDAY: LocalDate = LocalDate.parse("2026-07-11")
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

private enum class DailyDistillationInterruptionPoint {
    AFTER_PREPARED,
    AFTER_CANONICAL_COMMIT,
    AFTER_CHECKPOINT_COMPLETION
}

private class SimulatedDailyDistillationProcessDeath(message: String) : RuntimeException(message)

private class OneShotDailyDistillationCommitObserver(
    private val interruptionPoint: DailyDistillationInterruptionPoint
) : MemoryDailyDistillationCommitObserver {
    private var interrupted = false

    override suspend fun afterPrepared(mutation: MemoryPreparedMutation) {
        interruptAt(DailyDistillationInterruptionPoint.AFTER_PREPARED)
    }

    override suspend fun afterCanonicalFileCommit(mutation: MemoryPreparedMutation) {
        interruptAt(DailyDistillationInterruptionPoint.AFTER_CANONICAL_COMMIT)
    }

    override suspend fun afterCheckpointCompletion(checkpoint: MemoryDistillationCheckpoint) {
        interruptAt(DailyDistillationInterruptionPoint.AFTER_CHECKPOINT_COMPLETION)
    }

    private fun interruptAt(point: DailyDistillationInterruptionPoint) {
        if (!interrupted && interruptionPoint == point) {
            interrupted = true
            throw SimulatedDailyDistillationProcessDeath("Simulated process death at $point")
        }
    }
}

private class MutableDailyDistillationClock(initialInstant: Instant) : Clock() {
    private var currentInstant = initialInstant

    fun setInstant(instant: Instant) {
        currentInstant = instant
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(currentInstant, zone)

    override fun instant(): Instant = currentInstant
}
