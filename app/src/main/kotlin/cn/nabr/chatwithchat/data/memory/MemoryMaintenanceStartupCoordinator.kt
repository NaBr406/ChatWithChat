package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.memory.embedding.ProductionMemoryEmbeddingProvisioner
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class MemoryMaintenanceStartupCoordinator @Inject constructor(
    private val embeddingProvisioner: ProductionMemoryEmbeddingProvisioner,
    private val memoryMutationRecoveryService: MemoryMutationRecoveryService,
    private val vectorIndexBootstrapService: MemoryVectorIndexBootstrapService,
    private val memoryMaintenanceRepairer: MemoryMaintenanceRepairer,
    private val workEnqueuer: MemoryMaintenanceWorkEnqueuer
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
            repair = { memoryMaintenanceRepairer.repairAndEnqueue(reopenWaitingRepair = true) }
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
    repair: suspend () -> Unit
) {
    runOptionalStartupStep(enqueueRepair)
    runOptionalStartupStep(provision)
    val receiptsRecovered = runOptionalStartupStep {
        val result = recoverReceipts()
        check(result.allowsBootstrap) { "Memory receipt recovery did not complete before bootstrap" }
    }
    if (receiptsRecovered) {
        runOptionalStartupStep(bootstrap)
    }
    repair()
}

private suspend fun runOptionalStartupStep(step: suspend () -> Unit): Boolean {
    try {
        step()
        return true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        return false
    }
}
