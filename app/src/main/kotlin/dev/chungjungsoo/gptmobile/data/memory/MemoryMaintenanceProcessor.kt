package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemoryMaintenanceProcessor @Inject constructor(
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val memoryIndexRepository: MemoryIndexRepository,
    private val settingRepository: SettingRepository,
    private val memoryTurnBatchScheduler: MemoryTurnBatchScheduler? = null,
    private val memoryBatchConsolidationService: MemoryBatchConsolidationService? = null
) {
    suspend fun processRunnableJobs(limit: Int = DEFAULT_LIMIT): MemoryMaintenanceProcessResult = PROCESS_MUTEX.withLock {
        memoryTurnBatchScheduler?.promoteDueIdleBatches()
        maintenanceScheduler.resetStaleRunningJobs()
        val jobs = maintenanceScheduler.runnableJobs(limit = limit)
        var succeededCount = 0
        var retryableCount = 0
        var terminalCount = 0

        jobs.forEach { job ->
            if (job.type in LLM_JOB_TYPES) {
                val result = processLlmJob(job)
                when (result.status) {
                    MemoryBatchProcessResult.STATUS_SUCCEEDED,
                    MemoryBatchProcessResult.STATUS_DUPLICATE -> succeededCount += 1
                    MemoryBatchProcessResult.STATUS_TERMINAL -> terminalCount += 1
                    else -> retryableCount += 1
                }
                return@forEach
            }

            val runningJob = maintenanceScheduler.markRunning(job)
            val result = runCatching { processJob(runningJob) }
            result.onSuccess {
                maintenanceScheduler.markSucceeded(runningJob)
                succeededCount += 1
            }.onFailure { throwable ->
                if (throwable.message.orEmpty().startsWith("unknown_memory_maintenance_job_type")) {
                    maintenanceScheduler.markFailedTerminal(runningJob, throwable.message ?: throwable.javaClass.simpleName)
                    terminalCount += 1
                } else {
                    maintenanceScheduler.markFailedRetryable(runningJob, throwable.message ?: throwable.javaClass.simpleName)
                    retryableCount += 1
                }
            }
        }

        MemoryMaintenanceProcessResult(
            processedCount = jobs.size,
            succeededCount = succeededCount,
            retryableCount = retryableCount,
            terminalCount = terminalCount
        )
    }

    private suspend fun processLlmJob(job: MemoryMaintenanceJob): MemoryBatchProcessResult {
        if (!settingRepository.fetchMemoryEnabled()) {
            maintenanceScheduler.markDismissed(job)
            memoryTurnBatchScheduler?.onMemoryEnabledChanged(false)
            return MemoryBatchProcessResult(
                status = MemoryBatchProcessResult.STATUS_TERMINAL,
                jobId = job.jobId,
                reason = "memory_disabled"
            )
        }
        return when (job.type) {
            MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH ->
                memoryBatchConsolidationService?.process(job) ?: unavailableConsolidation(job)
            MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
            MemoryMaintenanceJobType.COMPACTION_FLUSH ->
                memoryBatchConsolidationService?.processLegacy(job) ?: unavailableConsolidation(job)
            else -> unavailableConsolidation(job, "unsupported_memory_job_type:${job.type}")
        }
    }

    private suspend fun unavailableConsolidation(
        job: MemoryMaintenanceJob,
        reason: String = "batch_consolidation_pending"
    ): MemoryBatchProcessResult {
        val runningJob = maintenanceScheduler.markRunning(job)
        val failedJob = maintenanceScheduler.markFailedRetryable(runningJob, reason)
        return MemoryBatchProcessResult(
            status = if (failedJob.status == MemoryMaintenanceJobStatus.FAILED_TERMINAL) {
                MemoryBatchProcessResult.STATUS_TERMINAL
            } else {
                MemoryBatchProcessResult.STATUS_RETRYABLE
            },
            jobId = job.jobId,
            reason = reason
        )
    }

    private suspend fun processJob(job: MemoryMaintenanceJob) {
        when (job.type) {
            MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
            MemoryMaintenanceJobType.REPAIR_MARKDOWN_METADATA -> {
                memoryIndexRepository.rebuildAll().getOrThrow()
            }
            MemoryMaintenanceJobType.DISTILL_DAILY_NOTES,
            MemoryMaintenanceJobType.PROMOTE_LONG_TERM_CANDIDATE -> {
                if (!settingRepository.fetchMemoryEnabled()) {
                    error("memory_disabled")
                }
                error("llm_memory_worker_pending")
            }
            else -> error("unknown_memory_maintenance_job_type:${job.type}")
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 10
        private val LLM_JOB_TYPES = setOf(
            MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
            MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
            MemoryMaintenanceJobType.COMPACTION_FLUSH,
            MemoryMaintenanceJobType.DISTILL_DAILY_NOTES,
            MemoryMaintenanceJobType.PROMOTE_LONG_TERM_CANDIDATE
        )
        private val PROCESS_MUTEX = Mutex()
    }
}

data class MemoryMaintenanceProcessResult(
    val processedCount: Int,
    val succeededCount: Int,
    val retryableCount: Int,
    val terminalCount: Int
)
