package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.dao.PersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemoryMaintenanceProcessor @Inject constructor(
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val memoryIndexRepository: MemoryIndexRepository,
    private val settingRepository: SettingRepository,
    private val personalMemoryDao: PersonalMemoryDao,
    private val memoryIntelligence: MemoryIntelligence,
    private val markdownMemoryLearningService: MarkdownMemoryLearningService,
    private val memoryTurnBatchScheduler: MemoryTurnBatchScheduler? = null
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
                    MarkdownMemoryLearningResult.STATUS_APPLIED,
                    MarkdownMemoryLearningResult.STATUS_SKIPPED_NO_NOTES,
                    MarkdownMemoryLearningResult.STATUS_SKIPPED_DUPLICATE_JOB -> succeededCount += 1
                    MarkdownMemoryLearningResult.STATUS_FAILED_TERMINAL -> terminalCount += 1
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

    private suspend fun processLlmJob(job: MemoryMaintenanceJob): MarkdownMemoryLearningResult {
        if (!settingRepository.fetchMemoryEnabled()) {
            maintenanceScheduler.markDismissed(job)
            memoryTurnBatchScheduler?.onMemoryEnabledChanged(false)
            return MarkdownMemoryLearningResult.skipped(
                status = MarkdownMemoryLearningResult.STATUS_FAILED_TERMINAL,
                jobId = job.jobId,
                reason = "memory_disabled"
            )
        }
        if (job.type == MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH) {
            val runningJob = maintenanceScheduler.markRunning(job)
            val failedJob = maintenanceScheduler.markFailedRetryable(runningJob, "batch_consolidation_pending")
            return MarkdownMemoryLearningResult.skipped(
                status = if (failedJob.status == MemoryMaintenanceJobStatus.FAILED_TERMINAL) {
                    MarkdownMemoryLearningResult.STATUS_FAILED_TERMINAL
                } else {
                    MarkdownMemoryLearningResult.STATUS_FAILED_RETRYABLE
                },
                jobId = job.jobId,
                reason = "batch_consolidation_pending"
            )
        }
        val platform = settingRepository.fetchPlatformV2s()
            .firstOrNull { platform -> platform.enabled && platform.model.isNotBlank() }
        val existingRoomMemories = personalMemoryDao.getRecallCandidates().take(MAX_ROOM_MEMORY_CANDIDATES)
        return when (job.type) {
            MemoryMaintenanceJobType.APPEND_DAILY_NOTE -> markdownMemoryLearningService.retryLearningJob(
                job = job,
                existingRoomMemories = existingRoomMemories,
                memoryIntelligence = memoryIntelligence,
                preferredPlatform = platform
            )
            MemoryMaintenanceJobType.COMPACTION_FLUSH -> markdownMemoryLearningService.retryCompactionFlushJob(
                job = job,
                existingRoomMemories = existingRoomMemories,
                memoryIntelligence = memoryIntelligence,
                preferredPlatform = platform
            )
            else -> {
                maintenanceScheduler.markFailedRetryable(job, "llm_memory_worker_pending")
                MarkdownMemoryLearningResult.skipped(
                    status = MarkdownMemoryLearningResult.STATUS_FAILED_RETRYABLE,
                    jobId = job.jobId,
                    reason = "llm_memory_worker_pending"
                )
            }
        }
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
        private const val MAX_ROOM_MEMORY_CANDIDATES = 24
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
