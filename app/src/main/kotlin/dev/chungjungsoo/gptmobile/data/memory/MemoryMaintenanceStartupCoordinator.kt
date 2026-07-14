package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.memory.embedding.ProductionMemoryEmbeddingProvisioner
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class MemoryMaintenanceStartupCoordinator @Inject constructor(
    private val embeddingProvisioner: ProductionMemoryEmbeddingProvisioner,
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
    bootstrap: suspend () -> Unit,
    repair: suspend () -> Unit
) {
    runOptionalStartupStep(enqueueRepair)
    runOptionalStartupStep(provision)
    runOptionalStartupStep(bootstrap)
    repair()
}

private suspend fun runOptionalStartupStep(step: suspend () -> Unit) {
    try {
        step()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        Unit
    }
}
