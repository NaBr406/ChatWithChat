package dev.chungjungsoo.gptmobile.data.memory

import javax.inject.Inject

class MemoryMaintenanceRepairer @Inject constructor(
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val workScheduler: MemoryMaintenanceWorkEnqueuer
) {
    suspend fun repairAndEnqueue(): MemoryMaintenanceRepairResult {
        val resetCount = maintenanceScheduler.resetStaleRunningJobs()
        workScheduler.enqueueRepairWork()
        return MemoryMaintenanceRepairResult(resetCount = resetCount)
    }
}

data class MemoryMaintenanceRepairResult(
    val resetCount: Int
)
