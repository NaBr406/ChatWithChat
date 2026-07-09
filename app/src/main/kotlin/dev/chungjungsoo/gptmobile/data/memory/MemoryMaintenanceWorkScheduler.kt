package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface MemoryMaintenanceWorkEnqueuer {
    fun enqueueRepairWork(delaySeconds: Long = 0)
}

class MemoryMaintenanceWorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) : MemoryMaintenanceWorkEnqueuer {
    override fun enqueueRepairWork(delaySeconds: Long) {
        enqueueRepairWork(context, delaySeconds)
    }

    companion object {
        private const val IMMEDIATE_UNIQUE_WORK_NAME = "memory_maintenance_repair_immediate"
        private const val DELAYED_UNIQUE_WORK_NAME = "memory_maintenance_repair_delayed"

        fun enqueueRepairWork(context: Context, delaySeconds: Long = 0) {
            val normalizedDelaySeconds = delaySeconds.coerceAtLeast(0)
            val requestBuilder = OneTimeWorkRequestBuilder<MemoryMaintenanceWorker>()
            if (normalizedDelaySeconds > 0) {
                requestBuilder.setInitialDelay(normalizedDelaySeconds, TimeUnit.SECONDS)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (normalizedDelaySeconds > 0) DELAYED_UNIQUE_WORK_NAME else IMMEDIATE_UNIQUE_WORK_NAME,
                if (normalizedDelaySeconds > 0) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                requestBuilder.build()
            )
        }
    }
}
