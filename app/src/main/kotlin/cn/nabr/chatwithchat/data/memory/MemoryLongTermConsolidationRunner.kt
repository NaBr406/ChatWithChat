package cn.nabr.chatwithchat.data.memory

import javax.inject.Inject

/** Runs a manually requested consolidation before handing any remaining work back to WorkManager. */
class MemoryLongTermConsolidationRunner @Inject constructor(
    private val scheduler: MemoryLongTermConsolidationScheduler,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val processor: MemoryMaintenanceProcessor
) {
    suspend fun runNow(): MemoryLongTermConsolidationRunResult {
        return runPlanned(scheduler.scheduleNow(enqueueWork = false))
    }

    suspend fun forceNow(): MemoryLongTermConsolidationRunResult {
        return runPlanned(scheduler.scheduleForceNow(enqueueWork = false))
    }

    private suspend fun runPlanned(
        plan: MemoryLongTermPlanResult
    ): MemoryLongTermConsolidationRunResult {
        if (!plan.scheduled || plan.jobId == null) {
            val job = plan.jobId?.let { jobId -> maintenanceScheduler.getJob(jobId) }
            return MemoryLongTermConsolidationRunResult(
                plan = plan,
                finalJobStatus = job?.status,
                finalJobError = job?.lastError ?: job?.blockedReason
            )
        }

        var lastProcess: MemoryMaintenanceProcessResult? = null
        repeat(MAX_DIRECT_INVOCATIONS) {
            val process = processor.processRunnableJobs(
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                limit = 1,
                preferredJobId = plan.jobId
            )
            lastProcess = process
            val job = maintenanceScheduler.getJob(plan.jobId) ?: return@repeat
            if (job.status !in RUNNABLE_JOB_STATUSES || process.processedCount == 0) return@repeat
        }
        val finalJob = maintenanceScheduler.getJob(plan.jobId)
        if (finalJob?.status in RUNNABLE_JOB_STATUSES) {
            // A large corpus may need more invocations; background work continues from the same checkpoint.
            scheduler.ensureScheduled()
        }
        return MemoryLongTermConsolidationRunResult(
            plan = plan,
            process = lastProcess,
            finalJobStatus = finalJob?.status,
            finalJobError = finalJob?.lastError ?: finalJob?.blockedReason
        )
    }

    private companion object {
        const val MAX_DIRECT_INVOCATIONS = 8
        val RUNNABLE_JOB_STATUSES = setOf(
            MemoryMaintenanceJobStatus.PENDING,
            MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
            MemoryMaintenanceJobStatus.RUNNING
        )
    }
}

data class MemoryLongTermConsolidationRunResult(
    val plan: MemoryLongTermPlanResult,
    val process: MemoryMaintenanceProcessResult? = null,
    val finalJobStatus: String? = null,
    val finalJobError: String? = null
)
