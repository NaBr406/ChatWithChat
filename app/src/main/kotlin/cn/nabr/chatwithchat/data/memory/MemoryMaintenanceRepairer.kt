package cn.nabr.chatwithchat.data.memory

import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class MemoryMaintenanceRepairer @Inject constructor(
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val workScheduler: MemoryMaintenanceWorkEnqueuer,
    private val memoryMutationRecoveryService: MemoryMutationRecoveryService? = null,
    private val memoryVectorIndexBootstrapService: MemoryVectorIndexBootstrapService? = null,
    private val memoryVectorIndexRecoveryService: MemoryVectorIndexRecoveryService? = null,
    private val memoryTurnBatchScheduler: MemoryTurnBatchScheduler? = null,
    private val memoryDailyDistillationScheduler: MemoryDailyDistillationScheduler? = null,
    private val memoryLongTermConsolidationScheduler: MemoryLongTermConsolidationScheduler? = null,
    private val activityLogger: MemoryActivityLogger = MemoryActivityLogger.None
) {
    suspend fun repairAndEnqueue(
        reopenWaitingRepair: Boolean = false
    ): MemoryMaintenanceRepairResult {
        val repairStartedAt = maintenanceScheduler.currentEpochSecond()
        var schedulingSucceeded = true
        var mutationRecoveryResult: MemoryMutationRecoveryResult? = null
        if (memoryMutationRecoveryService != null) {
            if (
                !runSchedulingStep("repair_mutation_recovery_failed") {
                    mutationRecoveryResult = memoryMutationRecoveryService.recoverIncomplete()
                }
            ) {
                schedulingSucceeded = false
            }
        }
        val resetCount = runRequiredStep("repair_expired_job_reset_failed") {
            maintenanceScheduler.resetExpiredRunningJobs(now = repairStartedAt)
        }
        if (!runSchedulingStep("repair_activity_reconciliation_failed") { activityLogger.reconcileJobRuns() }) {
            schedulingSucceeded = false
        }
        val reopenedCount = if (reopenWaitingRepair) {
            runRequiredStep("repair_waiting_job_reopen_failed") {
                maintenanceScheduler.reopenWaitingRepairJobs()
            }
        } else {
            0
        }
        if (
            mutationRecoveryResult?.allowsBootstrap == true &&
            memoryVectorIndexBootstrapService != null &&
            !runSchedulingStep("repair_vector_bootstrap_failed") { memoryVectorIndexBootstrapService.bootstrap() }
        ) {
            schedulingSucceeded = false
        }
        if (
            memoryVectorIndexRecoveryService != null &&
            !runSchedulingStep("repair_vector_recovery_failed") { memoryVectorIndexRecoveryService.reconcile() }
        ) {
            schedulingSucceeded = false
        }
        if (
            memoryDailyDistillationScheduler != null &&
            !runSchedulingStep("repair_daily_planning_failed") { memoryDailyDistillationScheduler.ensurePlanningJobs() }
        ) {
            schedulingSucceeded = false
        }
        if (
            memoryLongTermConsolidationScheduler != null &&
            !runSchedulingStep("repair_long_term_planning_failed") { memoryLongTermConsolidationScheduler.ensureScheduled() }
        ) {
            schedulingSucceeded = false
        }
        val turnBatchSchedulingSucceeded = memoryTurnBatchScheduler?.let { scheduler ->
            runSchedulingStep("repair_turn_batch_scheduling_failed") { scheduler.repairAndSchedule() }
        } ?: false
        if (memoryTurnBatchScheduler != null && !turnBatchSchedulingSucceeded) {
            schedulingSucceeded = false
        }
        MemoryMaintenanceJobFamily.ALL.forEach { family ->
            if (
                !runSchedulingStep("repair_${family}_work_enqueue_failed") {
                    if (maintenanceScheduler.hasRunnableJob(family)) {
                        workScheduler.enqueueWork(family)
                    }
                }
            ) {
                schedulingSucceeded = false
            }
        }
        if (
            !runSchedulingStep("repair_index_wake_enqueue_failed") {
                maintenanceScheduler.nextScheduledDelaySeconds(MemoryMaintenanceJobFamily.INDEX)?.let { delaySeconds ->
                    workScheduler.enqueueWork(MemoryMaintenanceJobFamily.INDEX, delaySeconds)
                }
            }
        ) {
            schedulingSucceeded = false
        }
        if (!turnBatchSchedulingSucceeded) {
            if (
                !runSchedulingStep("repair_semantic_wake_enqueue_failed") {
                    maintenanceScheduler.nextScheduledDelaySeconds(MemoryMaintenanceJobFamily.SEMANTIC)?.let { delaySeconds ->
                        workScheduler.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC, delaySeconds)
                    }
                }
            ) {
                schedulingSucceeded = false
            }
            if (
                !runSchedulingStep("repair_repair_wake_enqueue_failed") {
                    maintenanceScheduler.nextRepairDelaySeconds()?.let { delaySeconds ->
                        workScheduler.enqueueWork(
                            family = MemoryMaintenanceJobFamily.REPAIR,
                            delaySeconds = delaySeconds
                        )
                    }
                }
            ) {
                schedulingSucceeded = false
            }
        }
        return MemoryMaintenanceRepairResult(
            resetCount = resetCount,
            reopenedCount = reopenedCount,
            schedulingSucceeded = schedulingSucceeded
        )
    }

    private suspend fun runSchedulingStep(
        errorCode: String,
        block: suspend () -> Unit
    ): Boolean = try {
        block()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        recordFailure(errorCode)
        false
    }

    private suspend fun <T> runRequiredStep(
        errorCode: String,
        block: suspend () -> T
    ): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        recordFailure(errorCode)
        throw throwable
    }

    private suspend fun recordFailure(errorCode: String) {
        activityLogger.recordStandalonePlanningResult(
            jobType = null,
            triggerReason = "repair",
            status = MemoryActivityStatus.FAILED,
            outcomeCode = errorCode
        )
    }
}

data class MemoryMaintenanceRepairResult(
    val resetCount: Int,
    val reopenedCount: Int,
    val schedulingSucceeded: Boolean
)
