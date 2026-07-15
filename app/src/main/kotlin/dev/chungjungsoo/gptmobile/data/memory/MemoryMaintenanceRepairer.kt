package dev.chungjungsoo.gptmobile.data.memory

import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class MemoryMaintenanceRepairer @Inject constructor(
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val workScheduler: MemoryMaintenanceWorkEnqueuer,
    private val memoryMutationRecoveryService: MemoryMutationRecoveryService? = null,
    private val memoryVectorIndexBootstrapService: MemoryVectorIndexBootstrapService? = null,
    private val memoryVectorIndexRecoveryService: MemoryVectorIndexRecoveryService? = null,
    private val memoryTurnBatchScheduler: MemoryTurnBatchScheduler? = null,
    private val memoryDailyDistillationScheduler: MemoryDailyDistillationScheduler? = null
) {
    suspend fun repairAndEnqueue(
        reopenWaitingRepair: Boolean = false
    ): MemoryMaintenanceRepairResult {
        val repairStartedAt = maintenanceScheduler.currentEpochSecond()
        var schedulingSucceeded = true
        var mutationRecoveryResult: MemoryMutationRecoveryResult? = null
        if (memoryMutationRecoveryService != null) {
            if (
                !runSchedulingStep {
                    mutationRecoveryResult = memoryMutationRecoveryService.recoverIncomplete()
                }
            ) {
                schedulingSucceeded = false
            }
        }
        val resetCount = maintenanceScheduler.resetExpiredRunningJobs(now = repairStartedAt)
        val reopenedCount = if (reopenWaitingRepair) {
            maintenanceScheduler.reopenWaitingRepairJobs()
        } else {
            0
        }
        if (
            mutationRecoveryResult?.allowsBootstrap == true &&
            memoryVectorIndexBootstrapService != null &&
            !runSchedulingStep { memoryVectorIndexBootstrapService.bootstrap() }
        ) {
            schedulingSucceeded = false
        }
        if (
            memoryVectorIndexRecoveryService != null &&
            !runSchedulingStep { memoryVectorIndexRecoveryService.reconcile() }
        ) {
            schedulingSucceeded = false
        }
        if (
            memoryDailyDistillationScheduler != null &&
            !runSchedulingStep { memoryDailyDistillationScheduler.ensurePlanningJobs() }
        ) {
            schedulingSucceeded = false
        }
        val turnBatchSchedulingSucceeded = memoryTurnBatchScheduler?.let { scheduler ->
            runSchedulingStep { scheduler.repairAndSchedule() }
        } ?: false
        if (memoryTurnBatchScheduler != null && !turnBatchSchedulingSucceeded) {
            schedulingSucceeded = false
        }
        MemoryMaintenanceJobFamily.ALL.forEach { family ->
            if (
                !runSchedulingStep {
                    if (maintenanceScheduler.hasRunnableJob(family)) {
                        workScheduler.enqueueWork(family)
                    }
                }
            ) {
                schedulingSucceeded = false
            }
        }
        if (
            !runSchedulingStep {
                maintenanceScheduler.nextScheduledDelaySeconds(MemoryMaintenanceJobFamily.INDEX)?.let { delaySeconds ->
                    workScheduler.enqueueWork(MemoryMaintenanceJobFamily.INDEX, delaySeconds)
                }
            }
        ) {
            schedulingSucceeded = false
        }
        if (!turnBatchSchedulingSucceeded) {
            if (
                !runSchedulingStep {
                    maintenanceScheduler.nextScheduledDelaySeconds(MemoryMaintenanceJobFamily.SEMANTIC)?.let { delaySeconds ->
                        workScheduler.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC, delaySeconds)
                    }
                }
            ) {
                schedulingSucceeded = false
            }
            if (
                !runSchedulingStep {
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

    private suspend fun runSchedulingStep(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        false
    }
}

data class MemoryMaintenanceRepairResult(
    val resetCount: Int,
    val reopenedCount: Int,
    val schedulingSucceeded: Boolean
)
