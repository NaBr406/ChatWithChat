package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.memory.embedding.ProductionMemoryEmbeddingProvisioner
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class MemoryMaintenanceStartupCoordinator @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val embeddingProvisioner: ProductionMemoryEmbeddingProvisioner,
    private val vectorIndexBootstrapService: MemoryVectorIndexBootstrapService,
    private val memoryMaintenanceRepairer: MemoryMaintenanceRepairer,
    private val workEnqueuer: MemoryMaintenanceWorkEnqueuer
) {
    suspend fun run() {
        runMemoryStartupTasks(
            migrate = { memoryRepository.migrateActiveMemoriesToMarkdown() },
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
    migrate: suspend () -> Unit,
    enqueueRepair: suspend () -> Unit,
    provision: suspend () -> Unit,
    bootstrap: suspend () -> Unit,
    repair: suspend () -> Unit
) {
    runOptionalStartupStep(enqueueRepair)
    runOptionalStartupStep(migrate)
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
