package cn.nabr.chatwithchat.data.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters

class MemoryIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result = runMemoryMaintenanceWorker {
        processAndReschedule(MemoryMaintenanceJobFamily.INDEX)
    }
}
