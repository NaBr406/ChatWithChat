package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject

class MemoryMaintenanceProcessor @Inject constructor(
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val memoryIndexRepository: MemoryIndexRepository,
    private val settingRepository: SettingRepository
) {
    suspend fun processRunnableJobs(limit: Int = DEFAULT_LIMIT): MemoryMaintenanceProcessResult {
        maintenanceScheduler.resetStaleRunningJobs()
        val jobs = maintenanceScheduler.runnableJobs(limit = limit)
        var succeededCount = 0
        var retryableCount = 0
        var terminalCount = 0

        jobs.forEach { job ->
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

        return MemoryMaintenanceProcessResult(
            processedCount = jobs.size,
            succeededCount = succeededCount,
            retryableCount = retryableCount,
            terminalCount = terminalCount
        )
    }

    private suspend fun processJob(job: MemoryMaintenanceJob) {
        when (job.type) {
            MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
            MemoryMaintenanceJobType.REPAIR_MARKDOWN_METADATA -> {
                memoryIndexRepository.rebuildAll().getOrThrow()
            }
            MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
            MemoryMaintenanceJobType.COMPACTION_FLUSH,
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
    }
}

data class MemoryMaintenanceProcessResult(
    val processedCount: Int,
    val succeededCount: Int,
    val retryableCount: Int,
    val terminalCount: Int
)
