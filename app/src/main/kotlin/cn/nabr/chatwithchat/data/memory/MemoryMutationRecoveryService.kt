package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryTurnBatchDao
import java.time.Clock
import kotlinx.coroutines.CancellationException

class MemoryMutationRecoveryService(
    private val memoryMutationCoordinator: MemoryMutationCoordinator,
    private val turnBatchDao: MemoryTurnBatchDao,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val dailyDistillationFinalizer: MemoryDailyDistillationRecoveryFinalizer? = null,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    suspend fun recoverIncomplete(scheduleRetry: Boolean = true): MemoryMutationRecoveryResult {
        val repair = memoryMutationCoordinator.reconcileIncomplete()
        var finalizationFailureCount = 0
        var activeSourceJobCount = 0
        var recoveredSemanticCount = 0
        val retryGenerations = repair.failedGenerations.toMutableSet().apply {
            repair.continuationGeneration?.let(::add)
        }
        val persistedTerminalConflicts = memoryMutationCoordinator.terminalSemanticConflicts()
            .filter { recovered ->
                val terminalReason = recovered.terminalReason ?: return@filter false
                maintenanceScheduler.needsRecoveredConflictFinalization(
                    jobId = recovered.semanticJobId,
                    reason = terminalReason
                )
            }
        (repair.recoveredSemanticMutations + persistedTerminalConflicts)
            .distinctBy(MemoryRecoveredSemanticMutation::groupId)
            .forEach { recovered ->
                try {
                    if (maintenanceScheduler.isRecoveredSourceJobActive(recovered.semanticJobId)) {
                        activeSourceJobCount += 1
                        return@forEach
                    }
                    val finalizedDailyDistillation = dailyDistillationFinalizer
                        ?.finalizeRecoveredMutation(recovered)
                        ?: false
                    if (!finalizedDailyDistillation) {
                        check(maintenanceScheduler.jobType(recovered.semanticJobId) != MemoryMaintenanceJobType.DISTILL_DAILY_NOTES) {
                            "Recovered daily distillation mutation has no checkpoint finalizer"
                        }
                        turnBatchDao.completeClaimedBatch(recovered.semanticJobId, now())
                    }
                    val sourceJobDisposition = recovered.terminalReason?.let { terminalReason ->
                        maintenanceScheduler.markRecoveredConflict(recovered.semanticJobId, terminalReason)
                    } ?: maintenanceScheduler.markRecoveredSucceeded(recovered.semanticJobId)
                    when (sourceJobDisposition) {
                        MemoryRecoveredJobDisposition.ACTIVE -> activeSourceJobCount += 1
                        MemoryRecoveredJobDisposition.MISSING,
                        MemoryRecoveredJobDisposition.SUCCEEDED -> {
                            memoryMutationCoordinator.acknowledgeSemanticCompletion(recovered.groupId)
                            recoveredSemanticCount += 1
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    finalizationFailureCount += 1
                    retryGenerations += recovered.generation
                }
            }
        if (scheduleRetry) {
            retryGenerations.maxOrNull()?.let { failedGeneration ->
                maintenanceScheduler.enqueue(
                    type = MemoryMaintenanceJobType.RECONCILE_MEMORY_MUTATIONS,
                    idempotencyKey = REPAIR_IDEMPOTENCY_KEY,
                    payloadJson = "{}",
                    generation = failedGeneration
                )
            }
        }
        return MemoryMutationRecoveryResult(
            committedCount = repair.committedCount,
            conflictCount = repair.conflictCount,
            failedCount = repair.failedCount + finalizationFailureCount,
            recoveredSemanticCount = recoveredSemanticCount,
            retryGenerations = retryGenerations,
            hasMore = repair.hasMore,
            activeSourceJobCount = activeSourceJobCount
        )
    }

    private fun now(): Long = clock.instant().epochSecond

    private companion object {
        const val REPAIR_IDEMPOTENCY_KEY = "memory-mutation-repair"
    }
}

data class MemoryMutationRecoveryResult(
    val committedCount: Int,
    val conflictCount: Int,
    val failedCount: Int,
    val recoveredSemanticCount: Int,
    val retryGenerations: Set<Long>,
    val hasMore: Boolean,
    val activeSourceJobCount: Int = 0
) {
    internal val allowsBootstrap: Boolean
        get() = failedCount == 0 &&
            retryGenerations.isEmpty() &&
            !hasMore &&
            activeSourceJobCount == 0
}
