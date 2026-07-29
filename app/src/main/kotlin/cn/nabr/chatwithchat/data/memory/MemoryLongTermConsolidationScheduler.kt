package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryLongTermConsolidationDao
import cn.nabr.chatwithchat.data.database.entity.MemoryLongTermConsolidationCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.repository.SettingRepository
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MemoryLongTermConsolidationScheduler(
    private val memoryFileStore: MemoryFileStore,
    private val markdownMemoryCodec: MarkdownMemoryCodec,
    private val checkpointDao: MemoryLongTermConsolidationDao,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val settingRepository: SettingRepository,
    private val workEnqueuer: MemoryMaintenanceWorkEnqueuer,
    private val memoryChunker: MemoryChunker = MemoryChunker(markdownMemoryCodec),
    private val activityLogger: MemoryActivityLogger = MemoryActivityLogger.None,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
    }
) : MemoryMaterialMutationObserver {
    override suspend fun onMaterialMutationCommitted() {
        ensureScheduled()
    }

    suspend fun ensureScheduled(completedCheckpointId: String? = null): MemoryLongTermPlanResult {
        if (!settingRepository.fetchMemoryEnabled()) {
            return recordPlanningResult(
                MemoryLongTermPlanResult(scheduled = false, reason = REASON_MEMORY_DISABLED)
            )
        }

        activeCheckpoint()?.let { checkpoint ->
            return recordPlanningResult(ensureSemanticJob(checkpoint, REASON_ACTIVE_CHECKPOINT))
        }

        val latestCompleted = if (completedCheckpointId == null) {
            checkpointDao.getLatestCompleted(MemoryLongTermCheckpointStatus.COMPLETED)
        } else {
            requireNotNull(checkpointDao.getById(completedCheckpointId)).also { checkpoint ->
                require(checkpoint.status == MemoryLongTermCheckpointStatus.COMPLETED) {
                    "long-term scheduling anchor must be completed"
                }
            }
        }
        val completedGeneration = latestCompleted?.completedGeneration ?: 0L
        val materialMutationCount = checkpointDao.sumMaterialMutationsAfterGeneration(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            afterGeneration = completedGeneration
        )
        if (latestCompleted?.continuationRequired == true) {
            val completedJobStatus = maintenanceScheduler.jobStatus(latestCompleted.jobId)
            if (completedJobStatus in ACTIVE_JOB_STATUSES) {
                if (completedJobStatus in RUNNABLE_JOB_STATUSES) {
                    workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
                }
                return MemoryLongTermPlanResult(
                    scheduled = true,
                    checkpointId = latestCompleted.checkpointId,
                    jobId = latestCompleted.jobId,
                    reason = REASON_COMPLETED_JOB_ACTIVE
                ).also { result ->
                    recordScheduledSemanticRun(result, latestCompleted.entryCount)
                    recordPlanningResult(result)
                }
            }
            return createAndSchedule(
                triggerReason = MemoryLongTermTriggerReason.CONTINUATION,
                cycleAnchor = "continuation:${latestCompleted.checkpointId}",
                materialMutationCount = materialMutationCount
            )
        }
        val decision = MemoryLongTermConsolidationSchedulePolicy.evaluate(
            materialMutationCount = materialMutationCount,
            latestCompletedAt = latestCompleted?.completedAt,
            now = now()
        )
        if (!decision.shouldSchedule) {
            scheduleNextEvaluation(checkNotNull(decision.nextDueAt))
            return recordPlanningResult(
                MemoryLongTermPlanResult(scheduled = false, reason = REASON_NOT_DUE)
            )
        }
        return createAndSchedule(
            triggerReason = checkNotNull(decision.triggerReason),
            cycleAnchor = latestCompleted?.checkpointId ?: INITIAL_CYCLE_ANCHOR,
            materialMutationCount = materialMutationCount
        )
    }

    suspend fun ensureContinuationScheduled(previousCheckpointId: String): MemoryLongTermPlanResult {
        if (!settingRepository.fetchMemoryEnabled()) {
            return MemoryLongTermPlanResult(scheduled = false, reason = REASON_MEMORY_DISABLED)
        }
        activeCheckpoint()?.let { checkpoint ->
            return ensureSemanticJob(checkpoint, REASON_ACTIVE_CHECKPOINT)
        }
        val previous = checkpointDao.getById(previousCheckpointId)
            ?: error("missing previous long-term consolidation checkpoint")
        check(previous.status == MemoryLongTermCheckpointStatus.COMPLETED) {
            "continuation requires a completed long-term consolidation checkpoint"
        }
        val materialMutationCount = checkpointDao.sumMaterialMutationsAfterGeneration(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            afterGeneration = previous.completedGeneration ?: previous.baseGeneration
        )
        return createAndSchedule(
            triggerReason = MemoryLongTermTriggerReason.CONTINUATION,
            cycleAnchor = "continuation:${previous.checkpointId}",
            materialMutationCount = materialMutationCount
        )
    }

    suspend fun ensureContinuationScheduled(): MemoryLongTermPlanResult {
        if (!settingRepository.fetchMemoryEnabled()) {
            return MemoryLongTermPlanResult(scheduled = false, reason = REASON_MEMORY_DISABLED)
        }
        val previous = checkpointDao.getLatestCompleted(MemoryLongTermCheckpointStatus.COMPLETED)
            ?: error("missing completed long-term consolidation checkpoint for continuation")
        return ensureContinuationScheduled(previous.checkpointId)
    }

    private suspend fun createAndSchedule(
        triggerReason: String,
        cycleAnchor: String,
        materialMutationCount: Long
    ): MemoryLongTermPlanResult {
        val snapshot = frozenSnapshot() ?: run {
            activeCheckpoint()?.let { checkpoint ->
                return recordPlanningResult(ensureSemanticJob(checkpoint, REASON_ACTIVE_CHECKPOINT))
            }
            return recordPlanningResult(
                MemoryLongTermPlanResult(scheduled = false, reason = REASON_INVALID_CANONICAL_SNAPSHOT)
            )
        }
        activeCheckpoint()?.let { checkpoint ->
            return recordPlanningResult(ensureSemanticJob(checkpoint, REASON_ACTIVE_CHECKPOINT))
        }

        val checkpointId = checkpointId(snapshot, cycleAnchor)
        val jobId = semanticJobId(checkpointId)
        val timestamp = now()
        val candidate = MemoryLongTermConsolidationCheckpoint(
            checkpointId = checkpointId,
            jobId = jobId,
            activeKey = ACTIVE_CHECKPOINT_KEY,
            triggerReason = triggerReason,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            baseSourceHash = snapshot.baseSourceHash,
            resultSourceHash = snapshot.baseSourceHash,
            baseGeneration = snapshot.baseGeneration,
            recallProjectionHash = snapshot.recallProjectionHash,
            entryCount = snapshot.orderedEntryIds.size,
            orderedSnapshotHash = snapshot.orderedSnapshotHash,
            orderedEntryIdsJson = snapshot.orderedEntryIdsJson,
            materialMutationCountAtStart = materialMutationCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            status = MemoryLongTermCheckpointStatus.PENDING,
            createdAt = timestamp,
            updatedAt = timestamp
        )
        val inserted = checkpointDao.insertIgnore(candidate)
        val persisted = if (inserted != -1L) {
            candidate
        } else {
            checkpointDao.getById(checkpointId)
                ?: activeCheckpoint()
                ?: error("long-term consolidation checkpoint insert did not converge")
        }
        if (persisted.status !in MemoryLongTermCheckpointStatus.ACTIVE) {
            return MemoryLongTermPlanResult(
                scheduled = false,
                checkpointId = persisted.checkpointId,
                jobId = persisted.jobId,
                reason = REASON_ALREADY_PLANNED
            ).also { result -> recordPlanningResult(result) }
        }
        val reason = if (persisted.checkpointId == checkpointId) {
            triggerReason
        } else {
            REASON_ACTIVE_CHECKPOINT
        }
        return ensureSemanticJob(persisted, reason)
    }

    private suspend fun frozenSnapshot(): FrozenLongTermSnapshot? {
        val corpus = memoryFileStore.readCorpusFiles(MemoryCorpus.MAINTENANCE_WORKING_SET).getOrThrow()
        val longTerm = corpus.files.single { file ->
            file.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
        }
        val markdown = String(longTerm.bytes, StandardCharsets.UTF_8)
        val repaired = markdownMemoryCodec.repairStructuralRelationships(markdown) ?: return null
        val entries = repaired.entries.takeIf { repaired.repairedCount == 0 }.orEmpty()
        val orderedEntryIds = entries.map(MarkdownMemoryEntry::id)
        val orderedEntryIdsJson = json.encodeToString(orderedEntryIds)
        val baseGeneration = checkpointDao.getLatestCommittedGeneration(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
        )
        require(baseGeneration >= 0L) { "long-term corpus generation must not be negative" }

        return FrozenLongTermSnapshot(
            baseSourceHash = longTerm.bytes.sha256Hex(),
            baseGeneration = baseGeneration,
            recallProjectionHash = memoryChunker.chunksFor(
                sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                markdown = String(longTerm.bytes, StandardCharsets.UTF_8),
                projectionPolicy = MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
            ).projectionHash,
            orderedEntryIds = orderedEntryIds,
            orderedEntryIdsJson = orderedEntryIdsJson,
            orderedSnapshotHash = orderedSnapshotHash(orderedEntryIds)
        )
    }

    private suspend fun ensureSemanticJob(
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        reason: String
    ): MemoryLongTermPlanResult {
        check(checkpoint.status in MemoryLongTermCheckpointStatus.ACTIVE) {
            "cannot schedule a terminal long-term consolidation checkpoint"
        }
        check(checkpoint.activeKey == ACTIVE_CHECKPOINT_KEY) {
            "active long-term consolidation checkpoint has an invalid active key"
        }
        val payloadJson = json.encodeToString(
            MemoryLongTermConsolidationJobPayload(
                checkpointId = checkpoint.checkpointId,
                baseSourceHash = checkpoint.baseSourceHash,
                orderedSnapshotHash = checkpoint.orderedSnapshotHash
            )
        )
        var job = maintenanceScheduler.enqueue(
            type = MemoryMaintenanceJobType.CONSOLIDATE_LONG_TERM_MEMORY,
            idempotencyKey = "memory-long-term-consolidation:${checkpoint.checkpointId}",
            payloadJson = payloadJson,
            jobId = checkpoint.jobId,
            generation = checkpoint.baseGeneration
        )
        validateJob(job, checkpoint, payloadJson)
        if (job.status == MemoryMaintenanceJobStatus.DISMISSED && job.lastError == REASON_MEMORY_DISABLED) {
            job = maintenanceScheduler.reviveDismissedLongTermConsolidation(job.jobId) ?: job
            validateJob(job, checkpoint, payloadJson)
        }
        if (job.status in RUNNABLE_JOB_STATUSES) {
            workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
        }
        val result = MemoryLongTermPlanResult(
            scheduled = job.status in ACTIVE_JOB_STATUSES,
            checkpointId = checkpoint.checkpointId,
            jobId = job.jobId,
            reason = reason
        )
        recordScheduledSemanticRun(result, checkpoint.entryCount, job)
        return result
    }

    private suspend fun recordPlanningResult(result: MemoryLongTermPlanResult): MemoryLongTermPlanResult {
        val status = when (result.reason) {
            REASON_INVALID_CANONICAL_SNAPSHOT -> MemoryActivityStatus.BLOCKED
            REASON_MEMORY_DISABLED,
            REASON_NOT_DUE,
            REASON_ACTIVE_CHECKPOINT,
            REASON_COMPLETED_JOB_ACTIVE,
            REASON_ALREADY_PLANNED -> MemoryActivityStatus.SKIPPED
            else -> return result
        }
        activityLogger.recordStandalonePlanningResult(
            jobType = MemoryMaintenanceJobType.CONSOLIDATE_LONG_TERM_MEMORY,
            triggerReason = "long_term_planning_check",
            status = status,
            outcomeCode = result.reason
        )
        return result
    }

    private suspend fun recordScheduledSemanticRun(
        result: MemoryLongTermPlanResult,
        inputCount: Int,
        knownJob: MemoryMaintenanceJob? = null
    ) {
        if (!result.scheduled) return
        val jobId = result.jobId ?: return
        val job = knownJob ?: maintenanceScheduler.getJob(jobId) ?: return
        if (job.status !in ACTIVE_JOB_STATUSES) return
        activityLogger.startScheduledRun(
            job = job,
            category = MemoryActivityCategory.LONG_TERM_CONSOLIDATION,
            triggerReason = result.reason,
            inputCount = inputCount
        )
    }

    private fun validateJob(
        job: MemoryMaintenanceJob,
        checkpoint: MemoryLongTermConsolidationCheckpoint,
        expectedPayloadJson: String
    ) {
        check(job.jobId == checkpoint.jobId) { "long-term consolidation job identity conflict" }
        check(job.type == MemoryMaintenanceJobType.CONSOLIDATE_LONG_TERM_MEMORY) {
            "long-term consolidation job type conflict"
        }
        check(job.payloadJson == expectedPayloadJson) { "long-term consolidation job payload conflict" }
        check(job.generation == checkpoint.baseGeneration) { "long-term consolidation job generation conflict" }
    }

    private suspend fun activeCheckpoint(): MemoryLongTermConsolidationCheckpoint? = checkpointDao.getActive(
        activeKey = ACTIVE_CHECKPOINT_KEY,
        statuses = MemoryLongTermCheckpointStatus.ACTIVE
    )

    private suspend fun scheduleNextEvaluation(nextDueAt: Long) {
        val timestamp = now()
        val wakeAt = listOfNotNull(
            nextDueAt,
            maintenanceScheduler.nextRepairWakeAt(timestamp)
        ).min()
        workEnqueuer.enqueueWork(
            family = MemoryMaintenanceJobFamily.REPAIR,
            delaySeconds = (wakeAt - timestamp).coerceAtLeast(0L)
        )
    }

    private fun checkpointId(
        snapshot: FrozenLongTermSnapshot,
        cycleAnchor: String
    ): String {
        val identity = buildString {
            appendIdentityField(CHECKPOINT_IDENTITY_VERSION)
            appendIdentityField(cycleAnchor)
            appendIdentityField(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME)
            appendIdentityField(snapshot.baseSourceHash)
            appendIdentityField(snapshot.baseGeneration.toString())
            appendIdentityField(snapshot.orderedSnapshotHash)
        }
        return "lt_checkpoint_${identity.sha256Utf8().take(IDENTITY_HASH_LENGTH)}"
    }

    private fun semanticJobId(checkpointId: String): String = "lt_job_${buildString {
        appendIdentityField(JOB_IDENTITY_VERSION)
        appendIdentityField(checkpointId)
    }.sha256Utf8().take(IDENTITY_HASH_LENGTH)}"

    private fun orderedSnapshotHash(orderedEntryIds: List<String>): String = buildString {
        appendIdentityField(ORDERED_SNAPSHOT_VERSION)
        orderedEntryIds.forEachIndexed { offset, memoryId ->
            appendIdentityField(offset.toString())
            appendIdentityField(memoryId)
        }
    }.sha256Utf8()

    private fun StringBuilder.appendIdentityField(value: String) {
        append(value.length)
        append(':')
        append(value)
    }

    private fun now(): Long = clock.instant().epochSecond

    private data class FrozenLongTermSnapshot(
        val baseSourceHash: String,
        val baseGeneration: Long,
        val recallProjectionHash: String,
        val orderedEntryIds: List<String>,
        val orderedEntryIdsJson: String,
        val orderedSnapshotHash: String
    )

    companion object {
        internal const val REASON_MEMORY_DISABLED = "memory_disabled"
        internal const val REASON_NOT_DUE = "not_due"
        internal const val REASON_ACTIVE_CHECKPOINT = "active_checkpoint"
        internal const val REASON_INVALID_CANONICAL_SNAPSHOT = "invalid_canonical_snapshot"
        internal const val REASON_ALREADY_PLANNED = "already_planned"
        internal const val REASON_COMPLETED_JOB_ACTIVE = "completed_checkpoint_job_active"
        private const val ACTIVE_CHECKPOINT_KEY = "memory-long-term-consolidation:active:v1"
        private const val INITIAL_CYCLE_ANCHOR = "initial"
        private const val CHECKPOINT_IDENTITY_VERSION = "whole-corpus-checkpoint:v1"
        private const val JOB_IDENTITY_VERSION = "whole-corpus-job:v1"
        private const val ORDERED_SNAPSHOT_VERSION = "long-term-ordered-entry-offsets:v1"
        private const val IDENTITY_HASH_LENGTH = 24
        private val RUNNABLE_JOB_STATUSES = setOf(
            MemoryMaintenanceJobStatus.PENDING,
            MemoryMaintenanceJobStatus.FAILED_RETRYABLE
        )
        private val ACTIVE_JOB_STATUSES = RUNNABLE_JOB_STATUSES + MemoryMaintenanceJobStatus.RUNNING
    }
}

internal data class MemoryLongTermConsolidationScheduleDecision(
    val shouldSchedule: Boolean,
    val triggerReason: String? = null,
    val nextDueAt: Long? = null
)

internal object MemoryLongTermConsolidationSchedulePolicy {
    const val MATERIAL_MUTATION_THRESHOLD = 20
    val MAX_COMPLETION_INTERVAL: Duration = Duration.ofDays(7)

    fun evaluate(
        materialMutationCount: Long,
        latestCompletedAt: Long?,
        now: Long
    ): MemoryLongTermConsolidationScheduleDecision {
        require(materialMutationCount >= 0) { "material mutation count must not be negative" }
        require(now >= 0L) { "current time must not be negative" }
        require(latestCompletedAt == null || latestCompletedAt >= 0L) {
            "completion time must not be negative"
        }
        return when {
            materialMutationCount >= MATERIAL_MUTATION_THRESHOLD -> MemoryLongTermConsolidationScheduleDecision(
                shouldSchedule = true,
                triggerReason = MemoryLongTermTriggerReason.MATERIAL_THRESHOLD
            )
            latestCompletedAt == null -> MemoryLongTermConsolidationScheduleDecision(
                shouldSchedule = true,
                triggerReason = MemoryLongTermTriggerReason.WEEKLY_DUE
            )
            now >= weeklyDueAt(latestCompletedAt) ->
                MemoryLongTermConsolidationScheduleDecision(
                    shouldSchedule = true,
                    triggerReason = MemoryLongTermTriggerReason.WEEKLY_DUE
                )
            else -> MemoryLongTermConsolidationScheduleDecision(
                shouldSchedule = false,
                nextDueAt = weeklyDueAt(latestCompletedAt)
            )
        }
    }

    private fun weeklyDueAt(latestCompletedAt: Long): Long =
        if (latestCompletedAt > Long.MAX_VALUE - MAX_COMPLETION_INTERVAL.seconds) {
            Long.MAX_VALUE
        } else {
            latestCompletedAt + MAX_COMPLETION_INTERVAL.seconds
        }
}
