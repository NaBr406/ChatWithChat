package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryMaintenanceJobDao
import cn.nabr.chatwithchat.data.database.dao.MemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.MemoryPendingTurn
import cn.nabr.chatwithchat.data.repository.SettingRepository
import java.security.MessageDigest
import java.time.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MemoryTurnBatchScheduler(
    private val turnBatchDao: MemoryTurnBatchDao,
    private val maintenanceJobDao: MemoryMaintenanceJobDao,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val workEnqueuer: MemoryMaintenanceWorkEnqueuer,
    private val settingRepository: SettingRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val json: Json = Json { encodeDefaults = true }
) : MemoryPendingTurnObserver, MemoryMaintenanceLeaseWatchdog {
    override suspend fun onPendingTurnStateChanged(state: MemoryPendingTurnState) {
        if (state.thresholdEligible) {
            enqueueBatchForChat(
                chatId = state.chatId,
                triggerReason = MemoryTurnBatchTriggerReason.THRESHOLD,
                requireFullBatch = true
            )
        }
        scheduleNextWake()
    }

    suspend fun markCompactionUrgent(chatId: Int, lastOmittedUserMessageId: Int): MemoryMaintenanceJob? {
        if (!settingRepository.fetchMemoryEnabled()) return null
        if (turnBatchDao.getUnclaimedTurnsThrough(chatId, lastOmittedUserMessageId).isEmpty()) return null
        return enqueueBatchForChat(
            chatId = chatId,
            triggerReason = MemoryTurnBatchTriggerReason.CONTEXT_COMPACTION,
            requireFullBatch = false
        )
    }

    suspend fun promoteDueIdleBatches(now: Long = now()): Int {
        if (!settingRepository.fetchMemoryEnabled()) return 0
        var enqueuedCount = 0
        turnBatchDao.getDueIdleCheckpoints(now = now, limit = MAX_DUE_CHAT_SCAN).forEach { dueCheckpoint ->
            val currentCheckpoint = turnBatchDao.getCheckpoint(dueCheckpoint.chatId) ?: return@forEach
            if (currentCheckpoint.idleDueAt == null || currentCheckpoint.idleDueAt > now) return@forEach
            if (
                enqueueBatchForChat(
                    chatId = currentCheckpoint.chatId,
                    triggerReason = MemoryTurnBatchTriggerReason.IDLE,
                    requireFullBatch = false
                ) != null
            ) {
                enqueuedCount += 1
            }
        }
        return enqueuedCount
    }

    suspend fun repairAndSchedule(): MemoryTurnBatchRepairResult {
        if (!settingRepository.fetchMemoryEnabled()) {
            return onMemoryEnabledChanged(false)
        }

        var repairedClaimCount = 0
        turnBatchDao.getClaimedJobIds().forEach { jobId ->
            if (maintenanceJobDao.getById(jobId) == null) {
                val claimedTurns = turnBatchDao.getTurnsClaimedByJob(jobId)
                if (claimedTurns.isNotEmpty()) {
                    enqueueClaimedBatch(
                        claimedTurns = claimedTurns,
                        triggerReason = MemoryTurnBatchTriggerReason.MANUAL_RETRY,
                        jobId = jobId
                    )
                    repairedClaimCount += 1
                }
            }
        }

        var thresholdBatchCount = 0
        turnBatchDao.getChatIdsWithUnclaimedTurns().forEach { chatId ->
            if (turnBatchDao.countUnclaimedTurns(chatId) >= MemoryTurnBatchCoordinator.MAX_BATCH_TURNS) {
                if (
                    enqueueBatchForChat(
                        chatId = chatId,
                        triggerReason = MemoryTurnBatchTriggerReason.THRESHOLD,
                        requireFullBatch = true
                    ) != null
                ) {
                    thresholdBatchCount += 1
                }
            }
        }
        val idleBatchCount = promoteDueIdleBatches()
        scheduleNextWake()
        return MemoryTurnBatchRepairResult(
            repairedClaimCount = repairedClaimCount,
            thresholdBatchCount = thresholdBatchCount,
            idleBatchCount = idleBatchCount
        )
    }

    suspend fun onMemoryEnabledChanged(enabled: Boolean): MemoryTurnBatchRepairResult {
        if (enabled) {
            scheduleNextWake()
            return MemoryTurnBatchRepairResult()
        }

        val cancellableJobs = maintenanceJobDao.getByTypeAndStatuses(
            type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
            statuses = listOf(MemoryMaintenanceJobStatus.PENDING, MemoryMaintenanceJobStatus.FAILED_RETRYABLE)
        )
        cancellableJobs.forEach { job -> maintenanceScheduler.markDismissed(job) }
        val discardedTurnCount = turnBatchDao.clearPendingAndAdvanceBaselines(now())
        return MemoryTurnBatchRepairResult(
            dismissedJobCount = cancellableJobs.size,
            discardedTurnCount = discardedTurnCount
        )
    }

    suspend fun dismissBatch(job: MemoryMaintenanceJob): MemoryMaintenanceJob {
        val dismissedJob = maintenanceScheduler.markDismissed(job)
        turnBatchDao.completeClaimedBatch(job.jobId, now())
        scheduleNextWake()
        return dismissedJob
    }

    suspend fun scheduleNextWake(now: Long = now()) {
        listOfNotNull(
            turnBatchDao.getEarliestIdleDueAt(),
            maintenanceScheduler.nextRepairWakeAt(now)
        ).minOrNull()?.let { repairRunAt ->
            workEnqueuer.enqueueWork(
                family = MemoryMaintenanceJobFamily.REPAIR,
                delaySeconds = (repairRunAt - now).coerceAtLeast(0L)
            )
        }
        maintenanceScheduler.nextScheduledRunAt(
            family = MemoryMaintenanceJobFamily.SEMANTIC,
            now = now
        )?.let { semanticRunAt ->
            workEnqueuer.enqueueWork(
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                delaySeconds = (semanticRunAt - now).coerceAtLeast(0L)
            )
        }
    }

    override suspend fun scheduleLeaseWatchdog() {
        scheduleNextWake()
    }

    suspend fun enqueueBatchForChat(
        chatId: Int,
        triggerReason: String,
        requireFullBatch: Boolean
    ): MemoryMaintenanceJob? {
        if (!settingRepository.fetchMemoryEnabled()) return null
        if (turnBatchDao.countClaimedTurns(chatId) > 0) return null

        val candidates = turnBatchDao.getOldestUnclaimedTurns(
            chatId = chatId,
            limit = MemoryTurnBatchCoordinator.MAX_BATCH_TURNS
        )
        if (candidates.isEmpty()) return null
        if (requireFullBatch && candidates.size < MemoryTurnBatchCoordinator.MAX_BATCH_TURNS) return null

        val batchIdentity = batchIdentity(candidates)
        val jobId = "memory-batch-${sha256(batchIdentity).take(JOB_ID_HASH_LENGTH)}"
        val claimedTurns = turnBatchDao.claimOldestPendingTurns(
            chatId = chatId,
            jobId = jobId,
            limit = MemoryTurnBatchCoordinator.MAX_BATCH_TURNS,
            updatedAt = now()
        )
        if (claimedTurns.isEmpty()) return null
        return enqueueClaimedBatch(claimedTurns, triggerReason, jobId)
    }

    private suspend fun enqueueClaimedBatch(
        claimedTurns: List<MemoryPendingTurn>,
        triggerReason: String,
        jobId: String
    ): MemoryMaintenanceJob {
        val orderedTurns = claimedTurns.sortedWith(
            compareBy<MemoryPendingTurn> { it.completedAt }.thenBy { it.userMessageId }
        )
        val chatId = orderedTurns.first().chatId
        check(orderedTurns.all { it.chatId == chatId }) { "A memory batch cannot span chats" }
        val contentHash = sha256(orderedTurns.joinToString(separator = "|") { it.contentHash })
        val idempotencyKey = buildString {
            append("memory_batch:")
            append(chatId)
            append(':')
            append(orderedTurns.first().turnKey)
            append(':')
            append(orderedTurns.last().turnKey)
            append(':')
            append(contentHash)
        }
        val payload = MemoryTurnBatchJobPayload(
            batchId = idempotencyKey,
            chatId = chatId,
            triggerReason = triggerReason,
            turns = orderedTurns.map { turn ->
                MemoryTurnBatchJobTurn(
                    turnKey = turn.turnKey,
                    userMessageId = turn.userMessageId,
                    payloadJson = turn.payloadJson,
                    contentHash = turn.contentHash
                )
            },
            contentHash = contentHash,
            createdAt = now()
        )
        val job = maintenanceScheduler.enqueue(
            type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
            idempotencyKey = idempotencyKey,
            payloadJson = json.encodeToString(payload),
            jobId = jobId
        )
        workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
        return job
    }

    private fun batchIdentity(turns: List<MemoryPendingTurn>): String = buildString {
        append(turns.first().chatId)
        turns.forEach { turn ->
            append('|')
            append(turn.turnKey)
            append('|')
            append(turn.contentHash)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun now(): Long = clock.instant().epochSecond

    companion object {
        private const val MAX_DUE_CHAT_SCAN = 50
        private const val JOB_ID_HASH_LENGTH = 24
    }
}

data class MemoryTurnBatchRepairResult(
    val repairedClaimCount: Int = 0,
    val thresholdBatchCount: Int = 0,
    val idleBatchCount: Int = 0,
    val dismissedJobCount: Int = 0,
    val discardedTurnCount: Int = 0
)
