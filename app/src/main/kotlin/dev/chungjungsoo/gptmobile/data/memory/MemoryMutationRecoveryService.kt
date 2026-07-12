package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryTurnBatchDao
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
        var recoveredSemanticCount = 0
        val retryGenerations = repair.failedGenerations.toMutableSet().apply {
            repair.continuationGeneration?.let(::add)
        }
        repair.recoveredSemanticMutations.forEach { recovered ->
            try {
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
                    maintenanceScheduler.markRecoveredTerminal(recovered.semanticJobId, terminalReason)
                } ?: maintenanceScheduler.markRecoveredSucceeded(recovered.semanticJobId)
                if (sourceJobDisposition != MemoryRecoveredJobDisposition.ACTIVE) {
                    memoryMutationCoordinator.acknowledgeSemanticCompletion(recovered.groupId)
                    recoveredSemanticCount += 1
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
            hasMore = repair.hasMore
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
    val hasMore: Boolean
)
