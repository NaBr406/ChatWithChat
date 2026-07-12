package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors

class MemoryMaintenanceWakeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        val family = inputData.getString(INPUT_FAMILY)
            ?.takeIf { candidate -> candidate in MemoryMaintenanceJobFamily.ALL }
            ?: return ListenableWorker.Result.failure()
        return executeMemoryMaintenanceWorkerInvocation(
            entryPointProvider = {
                EntryPointAccessors.fromApplication(
                    applicationContext,
                    MemoryMaintenanceWorkerEntryPoint::class.java
                )
            }
        ) {
            memoryMaintenanceWorkEnqueuer().enqueueWork(family)
        }.toWakeWorkerResult()
    }

    companion object {
        internal const val INPUT_FAMILY = "memory_maintenance_family"
    }
}

private fun MemoryMaintenanceWorkerDisposition.toWakeWorkerResult(): ListenableWorker.Result = when (this) {
    MemoryMaintenanceWorkerDisposition.SUCCESS -> ListenableWorker.Result.success()
    MemoryMaintenanceWorkerDisposition.RETRY -> ListenableWorker.Result.retry()
    MemoryMaintenanceWorkerDisposition.FAILURE -> ListenableWorker.Result.failure()
}
