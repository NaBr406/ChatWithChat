package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class MemoryMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result =
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                MemoryMaintenanceWorkerEntryPoint::class.java
            )
            entryPoint.memoryMaintenanceProcessor().processRunnableJobs()
            entryPoint.memoryMaintenanceScheduler().nextScheduledDelaySeconds()?.let { delaySeconds ->
                entryPoint.memoryMaintenanceWorkEnqueuer().enqueueRepairWork(delaySeconds)
            }
            ListenableWorker.Result.success()
        }.getOrElse {
            ListenableWorker.Result.retry()
        }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemoryMaintenanceWorkerEntryPoint {
    fun memoryMaintenanceProcessor(): MemoryMaintenanceProcessor
    fun memoryMaintenanceScheduler(): MemoryMaintenanceScheduler
    fun memoryMaintenanceWorkEnqueuer(): MemoryMaintenanceWorkEnqueuer
}
