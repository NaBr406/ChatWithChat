package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

interface MemoryMaintenanceWorkEnqueuer {
    fun enqueueRepairWork()
}

class MemoryMaintenanceWorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) : MemoryMaintenanceWorkEnqueuer {
    override fun enqueueRepairWork() {
        enqueueRepairWork(context)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "memory_maintenance_repair"

        fun enqueueRepairWork(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MemoryMaintenanceWorker>().build()
            )
        }
    }
}
