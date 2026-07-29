package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.memory.embedding.ProductionMemoryEmbeddingProvisioner
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class MemoryMaintenanceStartupCoordinator @Inject constructor(
    private val embeddingProvisioner: ProductionMemoryEmbeddingProvisioner,
    private val memoryMutationRecoveryService: MemoryMutationRecoveryService,
    private val vectorIndexBootstrapService: MemoryVectorIndexBootstrapService,
    private val memoryMaintenanceRepairer: MemoryMaintenanceRepairer,
    private val workEnqueuer: MemoryMaintenanceWorkEnqueuer,
    private val activityLogger: MemoryActivityLogger
) {
    suspend fun run() {
        runMemoryStartupTasks(
            enqueueRepair = {
                workEnqueuer.enqueueWork(
                    family = MemoryMaintenanceJobFamily.REPAIR,
                    delaySeconds = STARTUP_REPAIR_FALLBACK_SECONDS
                )
            },
            provision = { embeddingProvisioner.provision() },
            recoverReceipts = { memoryMutationRecoveryService.recoverIncomplete() },
            bootstrap = { vectorIndexBootstrapService.bootstrap() },
            repair = { memoryMaintenanceRepairer.repairAndEnqueue(reopenWaitingRepair = true) },
            recordFailure = { errorCode, status ->
                activityLogger.recordStandalonePlanningResult(
                    jobType = null,
                    triggerReason = "startup",
                    status = status,
                    outcomeCode = errorCode
                )
            }
        )
    }

    private companion object {
        const val STARTUP_REPAIR_FALLBACK_SECONDS = 30L
    }
}

internal suspend fun runMemoryStartupTasks(
    enqueueRepair: suspend () -> Unit,
    provision: suspend () -> Unit,
    recoverReceipts: suspend () -> MemoryMutationRecoveryResult,
    bootstrap: suspend () -> Unit,
    repair: suspend () -> Unit,
    recordFailure: suspend (errorCode: String, status: String) -> Unit = { _, _ -> }
) {
    runOptionalStartupStep("startup_repair_enqueue_failed", recordFailure, enqueueRepair)
    runOptionalStartupStep("startup_embedding_provision_failed", recordFailure, provision)
    val receiptsRecovered = try {
        val result = recoverReceipts()
        if (result.allowsBootstrap) {
            true
        } else {
            recordFailure("startup_receipt_recovery_incomplete", MemoryActivityStatus.BLOCKED)
            false
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        recordFailure("startup_receipt_recovery_failed", MemoryActivityStatus.FAILED)
        false
    }
    if (receiptsRecovered) {
        runOptionalStartupStep("startup_vector_bootstrap_failed", recordFailure, bootstrap)
    }
    try {
        repair()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        recordFailure("startup_final_repair_failed", MemoryActivityStatus.FAILED)
        throw throwable
    }
}

private suspend fun runOptionalStartupStep(
    errorCode: String,
    recordFailure: suspend (errorCode: String, status: String) -> Unit,
    step: suspend () -> Unit
): Boolean {
    try {
        step()
        return true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        recordFailure(errorCode, MemoryActivityStatus.FAILED)
        return false
    }
}
