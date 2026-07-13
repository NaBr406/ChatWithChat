package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MemoryMaintenanceProcessor @Inject constructor(
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val settingRepository: SettingRepository,
    private val leaseWatchdog: MemoryMaintenanceLeaseWatchdog,
    private val memoryTurnBatchScheduler: MemoryTurnBatchScheduler? = null,
    private val memoryBatchConsolidationService: MemoryBatchConsolidationService? = null,
    private val memoryMutationRecoveryService: MemoryMutationRecoveryService? = null,
    private val memoryIndexSyncService: MemoryIndexSyncService? = null,
    private val memoryDailyDistillationScheduler: MemoryDailyDistillationScheduler? = null,
    private val memoryDailyDistillationService: MemoryDailyDistillationService? = null
) {
    suspend fun processRunnableJobs(
        family: String,
        limit: Int = DEFAULT_LIMIT
    ): MemoryMaintenanceProcessResult {
        require(family in MemoryMaintenanceJobFamily.ALL) { "Unknown memory maintenance family: $family" }
        val leaseOwner = "$family:${UUID.randomUUID()}"
        var processedCount = 0
        var succeededCount = 0
        var retryableCount = 0
        var terminalCount = 0
        var blockedCount = 0

        while (processedCount < limit) {
            val job = maintenanceScheduler.claimNextRunnable(
                family = family,
                leaseOwner = leaseOwner
            ) ?: break
            leaseWatchdog.scheduleLeaseWatchdog()
            processedCount += 1
            val outcome = try {
                runWithMemoryMaintenanceLeaseHeartbeat(
                    job = job,
                    maintenanceScheduler = maintenanceScheduler,
                    heartbeatIntervalMillis = LEASE_HEARTBEAT_INTERVAL_MILLIS
                ) {
                    processClaimedJob(job)
                }
            } catch (_: MemoryMaintenanceLeaseLostException) {
                MemoryMaintenanceOutcome.SKIPPED
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                persistUnexpectedFailure(job, throwable)
            }
            when (outcome) {
                MemoryMaintenanceOutcome.SUCCEEDED -> succeededCount += 1
                MemoryMaintenanceOutcome.RETRYABLE -> retryableCount += 1
                MemoryMaintenanceOutcome.TERMINAL -> terminalCount += 1
                MemoryMaintenanceOutcome.BLOCKED -> blockedCount += 1
                MemoryMaintenanceOutcome.SKIPPED -> Unit
            }
        }

        return MemoryMaintenanceProcessResult(
            processedCount = processedCount,
            succeededCount = succeededCount,
            retryableCount = retryableCount,
            terminalCount = terminalCount,
            blockedCount = blockedCount
        )
    }

    private suspend fun processClaimedJob(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        check(job.status == MemoryMaintenanceJobStatus.RUNNING)
        check(job.leaseOwner != null)
        if (job.type in LEGACY_ROOM_INDEX_JOB_TYPES) {
            maintenanceScheduler.markDismissed(job, LEGACY_ROOM_INDEX_DISMISS_REASON)
            return MemoryMaintenanceOutcome.TERMINAL
        }
        return when (job.family) {
            MemoryMaintenanceJobFamily.SEMANTIC -> processSemanticJob(job)
            MemoryMaintenanceJobFamily.INDEX -> processIndexJob(job)
            MemoryMaintenanceJobFamily.REPAIR -> processRepairJob(job)
            else -> dismissUnknownJob(job)
        }
    }

    private suspend fun processSemanticJob(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        if (!settingRepository.fetchMemoryEnabled()) {
            maintenanceScheduler.markDismissed(job, "memory_disabled")
            memoryTurnBatchScheduler?.onMemoryEnabledChanged(false)
            return MemoryMaintenanceOutcome.TERMINAL
        }
        return when (job.type) {
            MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH ->
                memoryBatchConsolidationService?.process(job)?.toOutcome() ?: unavailableConsolidation(job)
            MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
            MemoryMaintenanceJobType.COMPACTION_FLUSH ->
                memoryBatchConsolidationService?.processLegacy(job)?.toOutcome() ?: unavailableConsolidation(job)
            MemoryMaintenanceJobType.DISTILL_DAILY_NOTES ->
                memoryDailyDistillationService?.process(job)?.toOutcome() ?: unavailableDistillation(job)
            MemoryMaintenanceJobType.PROMOTE_LONG_TERM_CANDIDATE -> {
                maintenanceScheduler.markDismissed(job, "superseded_by_daily_distillation")
                MemoryMaintenanceOutcome.TERMINAL
            }
            else -> dismissUnknownJob(job)
        }
    }

    private suspend fun processIndexJob(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome = when (job.type) {
        MemoryMaintenanceJobType.SYNC_VECTOR_INDEX -> synchronizeVectorIndex(job)
        MemoryMaintenanceJobType.REBUILD_VECTOR_INDEX -> {
            maintenanceScheduler.markBlockedDependency(job, "vector_index_rebuild_payload_not_available")
            MemoryMaintenanceOutcome.BLOCKED
        }
        else -> dismissUnknownJob(job)
    }

    private suspend fun synchronizeVectorIndex(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        val syncService = memoryIndexSyncService ?: run {
            maintenanceScheduler.markBlockedDependency(job, "vector_index_synchronizer_not_available")
            return MemoryMaintenanceOutcome.BLOCKED
        }
        return when (val result = syncService.synchronize(job)) {
            MemoryIndexSyncResult.Succeeded,
            MemoryIndexSyncResult.Superseded -> {
                maintenanceScheduler.markSucceeded(job)
                MemoryMaintenanceOutcome.SUCCEEDED
            }
            is MemoryIndexSyncResult.Retryable -> {
                maintenanceScheduler.markFailedRetryable(job, result.reason).toFailureOutcome()
            }
            is MemoryIndexSyncResult.BlockedDependency -> {
                maintenanceScheduler.markBlockedDependency(job, result.reason)
                MemoryMaintenanceOutcome.BLOCKED
            }
            is MemoryIndexSyncResult.Terminal -> {
                maintenanceScheduler.markFailedTerminal(job, result.reason)
                MemoryMaintenanceOutcome.TERMINAL
            }
        }
    }

    private suspend fun processRepairJob(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome = when (job.type) {
        MemoryMaintenanceJobType.RECONCILE_MEMORY_MUTATIONS -> reconcileMemoryMutations(job)
        MemoryMaintenanceJobType.PLAN_DAILY_DISTILLATION -> planDailyDistillation(job)
        else -> dismissUnknownJob(job)
    }

    private suspend fun planDailyDistillation(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        if (!settingRepository.fetchMemoryEnabled()) {
            maintenanceScheduler.markDismissed(job, "memory_disabled")
            return MemoryMaintenanceOutcome.TERMINAL
        }
        val scheduler = memoryDailyDistillationScheduler ?: run {
            maintenanceScheduler.markBlockedDependency(job, "daily_distillation_scheduler_not_available")
            return MemoryMaintenanceOutcome.BLOCKED
        }
        return try {
            maintenanceScheduler.renewClaimedLease(job)
            scheduler.processPlan(job)
            maintenanceScheduler.renewClaimedLease(job)
            maintenanceScheduler.markSucceeded(job)
            MemoryMaintenanceOutcome.SUCCEEDED
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            persistUnexpectedFailure(job, throwable)
        }
    }

    private suspend fun reconcileMemoryMutations(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        val recoveryService = memoryMutationRecoveryService
            ?: return unavailableMutationRecovery(job)
        return try {
            maintenanceScheduler.renewClaimedLease(job)
            val result = recoveryService.recoverIncomplete(scheduleRetry = false)
            maintenanceScheduler.renewClaimedLease(job)
            if (result.failedCount > 0 || result.hasMore) {
                maintenanceScheduler.markFailedRetryable(
                    job,
                    "memory_mutation_reconciliation_incomplete:${result.failedCount}:has_more=${result.hasMore}"
                ).toFailureOutcome()
            } else {
                maintenanceScheduler.markSucceeded(job)
                MemoryMaintenanceOutcome.SUCCEEDED
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            persistUnexpectedFailure(job, throwable)
        }
    }

    private suspend fun unavailableConsolidation(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        val failedJob = maintenanceScheduler.markFailedRetryable(job, "batch_consolidation_pending")
        return failedJob.toFailureOutcome()
    }

    private suspend fun unavailableDistillation(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        val failedJob = maintenanceScheduler.markFailedRetryable(job, "daily_distillation_not_available")
        return failedJob.toFailureOutcome()
    }

    private suspend fun unavailableMutationRecovery(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        val failedJob = maintenanceScheduler.markFailedRetryable(job, "memory_mutation_recovery_not_available")
        return failedJob.toFailureOutcome()
    }

    private suspend fun persistUnexpectedFailure(
        job: MemoryMaintenanceJob,
        throwable: Throwable
    ): MemoryMaintenanceOutcome = try {
        maintenanceScheduler.markFailedRetryable(
            job = job,
            error = throwable.message ?: throwable.javaClass.simpleName
        ).toFailureOutcome()
    } catch (_: MemoryMaintenanceLeaseLostException) {
        MemoryMaintenanceOutcome.SKIPPED
    }

    private suspend fun dismissUnknownJob(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        maintenanceScheduler.markDismissed(job, "unsupported_memory_job_type:${job.type}")
        return MemoryMaintenanceOutcome.TERMINAL
    }

    private fun MemoryBatchProcessResult.toOutcome(): MemoryMaintenanceOutcome = when (status) {
        MemoryBatchProcessResult.STATUS_SUCCEEDED,
        MemoryBatchProcessResult.STATUS_DUPLICATE -> MemoryMaintenanceOutcome.SUCCEEDED
        MemoryBatchProcessResult.STATUS_TERMINAL -> MemoryMaintenanceOutcome.TERMINAL
        else -> MemoryMaintenanceOutcome.RETRYABLE
    }

    private fun MemoryDailyDistillationProcessResult.toOutcome(): MemoryMaintenanceOutcome = when (status) {
        MemoryDailyDistillationProcessResult.STATUS_SUCCEEDED,
        MemoryDailyDistillationProcessResult.STATUS_DUPLICATE -> MemoryMaintenanceOutcome.SUCCEEDED
        MemoryDailyDistillationProcessResult.STATUS_TERMINAL -> MemoryMaintenanceOutcome.TERMINAL
        else -> MemoryMaintenanceOutcome.RETRYABLE
    }

    private fun MemoryMaintenanceJob.toFailureOutcome(): MemoryMaintenanceOutcome = when (status) {
        MemoryMaintenanceJobStatus.FAILED_RETRYABLE -> MemoryMaintenanceOutcome.RETRYABLE
        MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY,
        MemoryMaintenanceJobStatus.WAITING_REPAIR -> MemoryMaintenanceOutcome.BLOCKED
        else -> MemoryMaintenanceOutcome.TERMINAL
    }

    companion object {
        private const val DEFAULT_LIMIT = 10
        private const val LEASE_HEARTBEAT_INTERVAL_MILLIS = 5 * 60 * 1_000L
        const val LEGACY_ROOM_INDEX_DISMISS_REASON = "schema16_legacy_room_index_removed"
        val LEGACY_ROOM_INDEX_JOB_TYPES = setOf(
            MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
            MemoryMaintenanceJobType.REPAIR_MARKDOWN_METADATA
        )
    }
}

internal suspend fun <T> runWithMemoryMaintenanceLeaseHeartbeat(
    job: MemoryMaintenanceJob,
    maintenanceScheduler: MemoryMaintenanceScheduler,
    heartbeatIntervalMillis: Long,
    block: suspend () -> T
): T {
    require(heartbeatIntervalMillis > 0) { "Memory maintenance heartbeat interval must be positive" }
    maintenanceScheduler.renewClaimedLease(job)
    return coroutineScope {
        val heartbeat = launch {
            while (true) {
                delay(heartbeatIntervalMillis)
                try {
                    maintenanceScheduler.renewClaimedLease(job)
                } catch (_: MemoryMaintenanceLeaseLostException) {
                    return@launch
                }
            }
        }
        try {
            block()
        } finally {
            heartbeat.cancelAndJoin()
        }
    }
}

interface MemoryMaintenanceLeaseWatchdog {
    suspend fun scheduleLeaseWatchdog()
}

private enum class MemoryMaintenanceOutcome {
    SUCCEEDED,
    RETRYABLE,
    TERMINAL,
    BLOCKED,
    SKIPPED
}

data class MemoryMaintenanceProcessResult(
    val processedCount: Int,
    val succeededCount: Int,
    val retryableCount: Int,
    val terminalCount: Int,
    val blockedCount: Int
)
