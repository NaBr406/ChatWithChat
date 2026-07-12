package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters

class MemoryRepairWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result = runMemoryMaintenanceWorker {
        memoryMaintenanceRepairer().repairAndEnqueue()
        processAndReschedule(MemoryMaintenanceJobFamily.REPAIR)
    }
}
