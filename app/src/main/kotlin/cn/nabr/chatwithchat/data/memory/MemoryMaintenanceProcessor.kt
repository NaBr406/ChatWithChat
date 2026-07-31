package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.repository.SettingRepository
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
    private val memoryVectorIndexBootstrapService: MemoryVectorIndexBootstrapService? = null,
    private val memoryIndexSyncService: MemoryIndexSyncService? = null,
    private val memoryDailyDistillationScheduler: MemoryDailyDistillationScheduler? = null,
    private val memoryDailyDistillationService: MemoryDailyDistillationService? = null,
    private val memoryLongTermConsolidationService: MemoryLongTermConsolidationService? = null,
    private val activityLogger: MemoryActivityLogger = MemoryActivityLogger.None
) {
    suspend fun processRunnableJobs(
        family: String,
        limit: Int = DEFAULT_LIMIT,
        preferredJobId: String? = null
    ): MemoryMaintenanceProcessResult {
        require(family in MemoryMaintenanceJobFamily.ALL) { "Unknown memory maintenance family: $family" }
        val leaseOwner = "$family:${UUID.randomUUID()}"
        var processedCount = 0
        var succeededCount = 0
        var retryableCount = 0
        var terminalCount = 0
        var blockedCount = 0
        var deferredCount = 0
        var shouldContinue = true

        while (processedCount < limit && shouldContinue) {
            val job = if (preferredJobId == null) {
                maintenanceScheduler.claimNextRunnable(
                    family = family,
                    leaseOwner = leaseOwner
                )
            } else {
                maintenanceScheduler.claimRunnableJob(
                    jobId = preferredJobId,
                    family = family,
                    leaseOwner = leaseOwner
                )
            } ?: break
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
                MemoryMaintenanceOutcome.DEFERRED -> {
                    deferredCount += 1
                    shouldContinue = false
                }
                MemoryMaintenanceOutcome.SKIPPED -> Unit
            }
        }

        return MemoryMaintenanceProcessResult(
            processedCount = processedCount,
            succeededCount = succeededCount,
            retryableCount = retryableCount,
            terminalCount = terminalCount,
            blockedCount = blockedCount,
            deferredCount = deferredCount
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
            finishUnavailableSemanticRun(
                job = job,
                status = MemoryActivityStatus.SKIPPED,
                outcomeCode = "memory_disabled"
            )
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
            MemoryMaintenanceJobType.CONSOLIDATE_LONG_TERM_MEMORY ->
                memoryLongTermConsolidationService?.process(job)?.toOutcome() ?: unavailableLongTermConsolidation(job)
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
        val activityRunId = activityLogger.startPlannerRun(job, triggerReason = "job_claimed")
        return try {
            if (!settingRepository.fetchMemoryEnabled()) {
                maintenanceScheduler.markDismissed(job, "memory_disabled")
                activityLogger.finishRunSafely(
                    activityRunId = activityRunId,
                    status = MemoryActivityStatus.SKIPPED,
                    data = MemoryActivityRunData(errorCode = "memory_disabled"),
                    expectedPhase = MemoryActivityPhase.PLANNING
                )
                return MemoryMaintenanceOutcome.TERMINAL
            }
            val scheduler = memoryDailyDistillationScheduler ?: run {
                maintenanceScheduler.markBlockedDependency(job, "daily_distillation_scheduler_not_available")
                activityLogger.finishRunSafely(
                    activityRunId = activityRunId,
                    status = MemoryActivityStatus.BLOCKED,
                    data = MemoryActivityRunData(errorCode = "daily_distillation_scheduler_not_available"),
                    expectedPhase = MemoryActivityPhase.PLANNING
                )
                return MemoryMaintenanceOutcome.BLOCKED
            }
            maintenanceScheduler.renewClaimedLease(job)
            val result = scheduler.processPlan(job)
            maintenanceScheduler.renewClaimedLease(job)
            finishDailyPlanner(job, activityRunId, result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            val outcome = persistFixedFailure(job, "daily_planning_failed")
            activityLogger.finishRunSafely(
                activityRunId = activityRunId,
                status = MemoryActivityStatus.FAILED,
                data = MemoryActivityRunData(errorCode = "daily_planning_failed"),
                expectedPhase = MemoryActivityPhase.PLANNING
            )
            outcome
        }
    }

    private suspend fun finishDailyPlanner(
        job: MemoryMaintenanceJob,
        activityRunId: String,
        result: MemoryDailyDistillationPlanResult
    ): MemoryMaintenanceOutcome {
        val (activityStatus, outcome) = when (result.disposition) {
            MemoryDailyDistillationPlanDisposition.WORK_SCHEDULED -> {
                maintenanceScheduler.markSucceeded(job)
                MemoryActivityStatus.SUCCEEDED to MemoryMaintenanceOutcome.SUCCEEDED
            }
            MemoryDailyDistillationPlanDisposition.NO_ELIGIBLE_INPUT -> {
                maintenanceScheduler.markSucceeded(job)
                MemoryActivityStatus.NO_OP to MemoryMaintenanceOutcome.SUCCEEDED
            }
            MemoryDailyDistillationPlanDisposition.SKIPPED -> {
                if (result.reason == MemoryDailyDistillationPlanReason.MEMORY_DISABLED) {
                    maintenanceScheduler.markDismissed(job, result.reason)
                    MemoryActivityStatus.SKIPPED to MemoryMaintenanceOutcome.TERMINAL
                } else {
                    maintenanceScheduler.markSucceeded(job)
                    MemoryActivityStatus.SKIPPED to MemoryMaintenanceOutcome.SUCCEEDED
                }
            }
            else -> {
                val outcome = persistFixedFailure(job, "invalid_daily_planning_disposition")
                activityLogger.finishRunSafely(
                    activityRunId = activityRunId,
                    status = MemoryActivityStatus.FAILED,
                    data = MemoryActivityRunData(errorCode = "invalid_daily_planning_disposition"),
                    expectedPhase = MemoryActivityPhase.PLANNING
                )
                return outcome
            }
        }
        activityLogger.finishRunSafely(
            activityRunId = activityRunId,
            status = activityStatus,
            data = MemoryActivityRunData(errorCode = result.reason),
            expectedPhase = MemoryActivityPhase.PLANNING
        )
        return outcome
    }

    private suspend fun reconcileMemoryMutations(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        val recoveryService = memoryMutationRecoveryService
            ?: return unavailableMutationRecovery(job)
        return try {
            maintenanceScheduler.renewClaimedLease(job)
            val result = recoveryService.recoverIncomplete(scheduleRetry = false)
            maintenanceScheduler.renewClaimedLease(job)
            if (result.failedCount > 0 || result.retryGenerations.isNotEmpty() || result.hasMore) {
                maintenanceScheduler.markFailedRetryable(
                    job,
                    "memory_mutation_reconciliation_incomplete:${result.failedCount}:has_more=${result.hasMore}:retry_generations=${result.retryGenerations.size}"
                ).toFailureOutcome()
            } else if (result.activeSourceJobCount > 0) {
                maintenanceScheduler.markSucceeded(job)
                leaseWatchdog.scheduleLeaseWatchdog()
                MemoryMaintenanceOutcome.DEFERRED
            } else {
                memoryVectorIndexBootstrapService?.bootstrap()
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
        finishUnavailableSemanticRun(job, MemoryActivityStatus.FAILED, "batch_consolidation_pending")
        return failedJob.toFailureOutcome()
    }

    private suspend fun unavailableDistillation(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        val failedJob = maintenanceScheduler.markFailedRetryable(job, "daily_distillation_not_available")
        finishUnavailableSemanticRun(job, MemoryActivityStatus.FAILED, "daily_distillation_not_available")
        return failedJob.toFailureOutcome()
    }

    private suspend fun unavailableLongTermConsolidation(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        val failedJob = maintenanceScheduler.markFailedRetryable(job, "long_term_consolidation_not_available")
        finishUnavailableSemanticRun(job, MemoryActivityStatus.FAILED, "long_term_consolidation_not_available")
        return failedJob.toFailureOutcome()
    }

    private suspend fun finishUnavailableSemanticRun(
        job: MemoryMaintenanceJob,
        status: String,
        outcomeCode: String
    ) {
        val category = when (job.type) {
            MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
            MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
            MemoryMaintenanceJobType.COMPACTION_FLUSH -> MemoryActivityCategory.TURN_BATCH_CONSOLIDATION
            MemoryMaintenanceJobType.DISTILL_DAILY_NOTES -> MemoryActivityCategory.DAILY_DISTILLATION
            MemoryMaintenanceJobType.CONSOLIDATE_LONG_TERM_MEMORY -> MemoryActivityCategory.LONG_TERM_CONSOLIDATION
            else -> return
        }
        val activityRunId = activityLogger.startSemanticRun(job, category, triggerReason = "job_claimed")
        activityLogger.finishRunSafely(
            activityRunId = activityRunId,
            status = status,
            data = MemoryActivityRunData(errorCode = outcomeCode),
            expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
        )
    }

    private suspend fun unavailableMutationRecovery(job: MemoryMaintenanceJob): MemoryMaintenanceOutcome {
        val failedJob = maintenanceScheduler.markFailedRetryable(job, "memory_mutation_recovery_not_available")
        return failedJob.toFailureOutcome()
    }

    private suspend fun persistUnexpectedFailure(
        job: MemoryMaintenanceJob,
        throwable: Throwable
    ): MemoryMaintenanceOutcome = try {
        val leaseOwner = job.leaseOwner ?: throw MemoryMaintenanceLeaseLostException(job.jobId)
        val current = maintenanceScheduler.getLatestClaimedJob(job.jobId, leaseOwner)
            ?: throw MemoryMaintenanceLeaseLostException(job.jobId)
        maintenanceScheduler.markFailedRetryable(
            job = current,
            error = throwable.message ?: throwable.javaClass.simpleName
        ).toFailureOutcome()
    } catch (_: MemoryMaintenanceLeaseLostException) {
        MemoryMaintenanceOutcome.SKIPPED
    }

    private suspend fun persistFixedFailure(
        job: MemoryMaintenanceJob,
        errorCode: String
    ): MemoryMaintenanceOutcome = try {
        val leaseOwner = job.leaseOwner ?: throw MemoryMaintenanceLeaseLostException(job.jobId)
        val current = maintenanceScheduler.getLatestClaimedJob(job.jobId, leaseOwner)
            ?: throw MemoryMaintenanceLeaseLostException(job.jobId)
        maintenanceScheduler.markFailedRetryable(current, errorCode).toFailureOutcome()
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
        MemoryBatchProcessResult.STATUS_BLOCKED -> MemoryMaintenanceOutcome.BLOCKED
        MemoryBatchProcessResult.STATUS_TERMINAL -> MemoryMaintenanceOutcome.TERMINAL
        else -> MemoryMaintenanceOutcome.RETRYABLE
    }

    private fun MemoryDailyDistillationProcessResult.toOutcome(): MemoryMaintenanceOutcome = when (status) {
        MemoryDailyDistillationProcessResult.STATUS_SUCCEEDED,
        MemoryDailyDistillationProcessResult.STATUS_DUPLICATE -> MemoryMaintenanceOutcome.SUCCEEDED
        MemoryDailyDistillationProcessResult.STATUS_BLOCKED -> MemoryMaintenanceOutcome.BLOCKED
        MemoryDailyDistillationProcessResult.STATUS_TERMINAL -> MemoryMaintenanceOutcome.TERMINAL
        else -> MemoryMaintenanceOutcome.RETRYABLE
    }

    private fun MemoryLongTermProcessResult.toOutcome(): MemoryMaintenanceOutcome = when (status) {
        MemoryLongTermProcessResult.STATUS_SUCCEEDED,
        MemoryLongTermProcessResult.STATUS_DUPLICATE -> MemoryMaintenanceOutcome.SUCCEEDED
        MemoryLongTermProcessResult.STATUS_BLOCKED -> MemoryMaintenanceOutcome.BLOCKED
        MemoryLongTermProcessResult.STATUS_TERMINAL -> MemoryMaintenanceOutcome.TERMINAL
        MemoryLongTermProcessResult.STATUS_DEFERRED -> MemoryMaintenanceOutcome.DEFERRED
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
    DEFERRED,
    SKIPPED
}

data class MemoryMaintenanceProcessResult(
    val processedCount: Int,
    val succeededCount: Int,
    val retryableCount: Int,
    val terminalCount: Int,
    val blockedCount: Int,
    val deferredCount: Int = 0
)
