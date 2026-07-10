package dev.chungjungsoo.gptmobile.data.memory

import javax.inject.Inject

class MemoryMaintenanceRepairer @Inject constructor(
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val workScheduler: MemoryMaintenanceWorkEnqueuer,
    private val memoryTurnBatchScheduler: MemoryTurnBatchScheduler? = null
) {
    suspend fun repairAndEnqueue(): MemoryMaintenanceRepairResult {
        val resetCount = maintenanceScheduler.resetStaleRunningJobs()
        memoryTurnBatchScheduler?.repairAndSchedule()
        workScheduler.enqueueRepairWork()
        if (memoryTurnBatchScheduler != null) {
            memoryTurnBatchScheduler.scheduleNextWake()
        } else {
            maintenanceScheduler.nextScheduledDelaySeconds()?.let { delaySeconds ->
                workScheduler.enqueueRepairWork(delaySeconds)
            }
        }
        return MemoryMaintenanceRepairResult(resetCount = resetCount)
    }
}

data class MemoryMaintenanceRepairResult(
    val resetCount: Int
)
