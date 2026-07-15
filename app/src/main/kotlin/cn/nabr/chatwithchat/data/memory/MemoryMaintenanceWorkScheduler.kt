package cn.nabr.chatwithchat.data.memory

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface MemoryMaintenanceWorkEnqueuer {
    fun enqueueWork(family: String, delaySeconds: Long = 0)
}

class MemoryMaintenanceWorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) : MemoryMaintenanceWorkEnqueuer {
    override fun enqueueWork(family: String, delaySeconds: Long) {
        enqueueWork(context, family, delaySeconds)
    }

    companion object {
        private const val SEMANTIC_IMMEDIATE_UNIQUE_WORK_NAME = "memory_maintenance_semantic_v1_immediate"
        private const val SEMANTIC_DELAYED_UNIQUE_WORK_NAME = "memory_maintenance_semantic_v1_delayed"
        private const val INDEX_IMMEDIATE_UNIQUE_WORK_NAME = "memory_maintenance_index_v1_immediate"
        private const val INDEX_DELAYED_UNIQUE_WORK_NAME = "memory_maintenance_index_v1_delayed"
        private const val REPAIR_IMMEDIATE_UNIQUE_WORK_NAME = "memory_maintenance_repair_v2_immediate"
        private const val REPAIR_DELAYED_UNIQUE_WORK_NAME = "memory_maintenance_repair_v2_delayed"

        fun enqueueWork(
            context: Context,
            family: String,
            delaySeconds: Long = 0
        ) {
            val plan = createPlan(family, delaySeconds)
            WorkManager.getInstance(context).enqueueUniqueWork(
                plan.uniqueWorkName,
                plan.existingWorkPolicy,
                plan.request
            )
        }

        internal fun createPlan(
            family: String,
            delaySeconds: Long = 0
        ): MemoryMaintenanceWorkPlan {
            require(family in MemoryMaintenanceJobFamily.ALL) {
                "Unknown memory maintenance work family: $family"
            }
            val normalizedDelaySeconds = delaySeconds.coerceAtLeast(0)
            val isDelayed = normalizedDelaySeconds > 0
            val requestBuilder = if (isDelayed) {
                OneTimeWorkRequestBuilder<MemoryMaintenanceWakeWorker>()
                    .setInputData(workDataOf(MemoryMaintenanceWakeWorker.INPUT_FAMILY to family))
            } else {
                when (family) {
                    MemoryMaintenanceJobFamily.SEMANTIC -> OneTimeWorkRequestBuilder<MemorySemanticWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                    MemoryMaintenanceJobFamily.INDEX -> OneTimeWorkRequestBuilder<MemoryIndexWorker>()
                    MemoryMaintenanceJobFamily.REPAIR -> OneTimeWorkRequestBuilder<MemoryRepairWorker>()
                    else -> error("Unreachable memory maintenance work family: $family")
                }
            }
            if (isDelayed) {
                requestBuilder.setInitialDelay(normalizedDelaySeconds, TimeUnit.SECONDS)
            }
            return MemoryMaintenanceWorkPlan(
                uniqueWorkName = uniqueWorkName(family, isDelayed),
                existingWorkPolicy = if (isDelayed) {
                    ExistingWorkPolicy.REPLACE
                } else {
                    ExistingWorkPolicy.APPEND_OR_REPLACE
                },
                request = requestBuilder.build()
            )
        }

        private fun uniqueWorkName(family: String, isDelayed: Boolean): String = when (family) {
            MemoryMaintenanceJobFamily.SEMANTIC ->
                if (isDelayed) SEMANTIC_DELAYED_UNIQUE_WORK_NAME else SEMANTIC_IMMEDIATE_UNIQUE_WORK_NAME
            MemoryMaintenanceJobFamily.INDEX ->
                if (isDelayed) INDEX_DELAYED_UNIQUE_WORK_NAME else INDEX_IMMEDIATE_UNIQUE_WORK_NAME
            MemoryMaintenanceJobFamily.REPAIR ->
                if (isDelayed) REPAIR_DELAYED_UNIQUE_WORK_NAME else REPAIR_IMMEDIATE_UNIQUE_WORK_NAME
            else -> error("Unknown memory maintenance work family: $family")
        }
    }
}

internal data class MemoryMaintenanceWorkPlan(
    val uniqueWorkName: String,
    val existingWorkPolicy: ExistingWorkPolicy,
    val request: OneTimeWorkRequest
)
