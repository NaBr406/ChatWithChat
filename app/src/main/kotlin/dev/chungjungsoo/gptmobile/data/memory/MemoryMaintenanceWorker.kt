package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

class MemoryMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result = runMemoryMaintenanceWorker {
        memoryMaintenanceRepairer().repairAndEnqueue()
    }
}

internal suspend fun CoroutineWorker.runMemoryMaintenanceWorker(
    block: suspend MemoryMaintenanceWorkerEntryPoint.() -> Unit
): ListenableWorker.Result = executeMemoryMaintenanceWorkerInvocation(
    entryPointProvider = {
        EntryPointAccessors.fromApplication(
            applicationContext,
            MemoryMaintenanceWorkerEntryPoint::class.java
        )
    },
    block = block
).toListenableWorkerResult()

internal suspend fun executeMemoryMaintenanceWorkerInvocation(
    entryPointProvider: () -> MemoryMaintenanceWorkerEntryPoint,
    block: suspend MemoryMaintenanceWorkerEntryPoint.() -> Unit
): MemoryMaintenanceWorkerDisposition {
    val entryPoint = try {
        entryPointProvider()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: IllegalArgumentException) {
        return MemoryMaintenanceWorkerDisposition.FAILURE
    } catch (_: IllegalStateException) {
        return MemoryMaintenanceWorkerDisposition.FAILURE
    } catch (_: ClassCastException) {
        return MemoryMaintenanceWorkerDisposition.FAILURE
    } catch (_: Throwable) {
        return MemoryMaintenanceWorkerDisposition.RETRY
    }
    return try {
        entryPoint.block()
        MemoryMaintenanceWorkerDisposition.SUCCESS
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: MemoryMaintenanceLeaseLostException) {
        MemoryMaintenanceWorkerDisposition.SUCCESS
    } catch (_: IllegalArgumentException) {
        MemoryMaintenanceWorkerDisposition.FAILURE
    } catch (_: Throwable) {
        MemoryMaintenanceWorkerDisposition.RETRY
    }
}

internal enum class MemoryMaintenanceWorkerDisposition {
    SUCCESS,
    RETRY,
    FAILURE
}

private fun MemoryMaintenanceWorkerDisposition.toListenableWorkerResult(): ListenableWorker.Result = when (this) {
    MemoryMaintenanceWorkerDisposition.SUCCESS -> ListenableWorker.Result.success()
    MemoryMaintenanceWorkerDisposition.RETRY -> ListenableWorker.Result.retry()
    MemoryMaintenanceWorkerDisposition.FAILURE -> ListenableWorker.Result.failure()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemoryMaintenanceWorkerEntryPoint {
    fun memoryMaintenanceProcessor(): MemoryMaintenanceProcessor
    fun memoryMaintenanceRepairer(): MemoryMaintenanceRepairer
    fun memoryMaintenanceScheduler(): MemoryMaintenanceScheduler
    fun memoryMaintenanceWorkEnqueuer(): MemoryMaintenanceWorkEnqueuer
    fun memoryTurnBatchScheduler(): MemoryTurnBatchScheduler
}

internal suspend fun MemoryMaintenanceWorkerEntryPoint.processAndReschedule(
    family: String
): MemoryMaintenanceProcessResult {
    val processResult = memoryMaintenanceProcessor().processRunnableJobs(family, limit = 1)
    var followUpScheduled = false
    try {
        if (memoryMaintenanceScheduler().hasRunnableJob(family)) {
            memoryMaintenanceWorkEnqueuer().enqueueWork(family)
            followUpScheduled = true
        }
        memoryMaintenanceScheduler().nextScheduledDelaySeconds(family)?.let { delaySeconds ->
            memoryMaintenanceWorkEnqueuer().enqueueWork(family, delaySeconds)
            followUpScheduled = true
        }
        memoryMaintenanceScheduler().nextRepairDelaySeconds()?.let { delaySeconds ->
            memoryMaintenanceWorkEnqueuer().enqueueWork(MemoryMaintenanceJobFamily.REPAIR, delaySeconds)
            followUpScheduled = true
        }
        memoryTurnBatchScheduler().scheduleNextWake()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        if (processResult.processedCount == 0 && !followUpScheduled) throw throwable
    }
    return processResult
}
