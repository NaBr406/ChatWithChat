package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDistillationCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MemoryDailyDistillationService(
    private val recoveryDao: MemoryRecoveryDao,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val settingRepository: SettingRepository,
    private val memoryIntelligence: MemoryIntelligence,
    private val memoryFileStore: MemoryFileStore,
    private val operationController: MemoryDailyDistillationOperationController,
    private val memoryMutationCoordinator: MemoryMutationCoordinator,
    private val dailyDistillationScheduler: MemoryDailyDistillationScheduler,
    private val commitObserver: MemoryDailyDistillationCommitObserver = MemoryDailyDistillationCommitObserver.None,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
    }
) : MemoryDailyDistillationRecoveryFinalizer {
    suspend fun process(job: MemoryMaintenanceJob): MemoryDailyDistillationProcessResult {
        terminalResultOrNull(job)?.let { return it }
        val payload = decodePayload(job) ?: return terminal(job, "invalid_daily_distillation_payload")
        var checkpoint = checkpointFor(job, payload) ?: return terminal(job, "daily_distillation_checkpoint_missing")
        if (checkpoint.status == MemoryDistillationCheckpointStatus.COMPLETED) {
            maintenanceScheduler.markSucceeded(job)
            val mutation = checkNotNull(memoryMutationCoordinator.findBySemanticJobId(job.jobId)) {
                "Completed daily distillation checkpoint is missing its mutation"
            }
            check(mutation.group.groupId == checkpoint.mutationGroupId) {
                "Completed daily distillation checkpoint has a mismatched mutation"
            }
            memoryMutationCoordinator.acknowledgeSemanticCompletion(mutation.group.groupId)
            dailyDistillationScheduler.ensurePlanningJobs()
            return MemoryDailyDistillationProcessResult(
                status = MemoryDailyDistillationProcessResult.STATUS_DUPLICATE,
                jobId = job.jobId
            )
        }
        if (!settingRepository.fetchMemoryEnabled()) {
            maintenanceScheduler.markDismissed(job, "memory_disabled")
            return MemoryDailyDistillationProcessResult(
                status = MemoryDailyDistillationProcessResult.STATUS_TERMINAL,
                jobId = job.jobId,
                reason = "memory_disabled"
            )
        }
        check(job.status == MemoryMaintenanceJobStatus.RUNNING) { "memory_job_not_claimed" }
        check(!job.leaseOwner.isNullOrBlank()) { "memory_job_missing_lease" }

        var mutation = memoryMutationCoordinator.findBySemanticJobId(job.jobId)
        var operationCount = 0
        if (mutation == null) {
            val sourceState = validateFrozenSources(payload.input)
            if (sourceState != null) {
                checkpoint = transitionCheckpoint(
                    checkpoint = checkpoint,
                    newStatus = sourceState,
                    newTargetSourceHash = checkpoint.targetSourceHash,
                    mutationGroupId = null,
                    processedAt = null
                )
                maintenanceScheduler.markDismissed(job, sourceState)
                dailyDistillationScheduler.ensurePlanningJobs()
                return MemoryDailyDistillationProcessResult(
                    status = MemoryDailyDistillationProcessResult.STATUS_TERMINAL,
                    jobId = job.jobId,
                    reason = sourceState
                )
            }

            maintenanceScheduler.renewClaimedLease(job)
            val proposal = memoryIntelligence.distillDailyMemory(payload.input, preferredMemoryPlatform())
                ?: return retryable(job, "daily_distillation_unavailable_or_invalid")
            maintenanceScheduler.renewClaimedLease(job)
            val validatedOperations = runCatching {
                operationController.validate(payload.input, proposal.operations)
            }.getOrElse { throwable ->
                return retryable(job, "invalid_daily_distillation_operations:${throwable.message}")
            }
            val baseMarkdown = memoryFileStore.readLongTermMemory().getOrThrow()
            val rendered = runCatching {
                operationController.render(
                    input = payload.input,
                    baseMarkdown = baseMarkdown,
                    validatedOperations = validatedOperations,
                    renderedAt = payload.input.createdAt
                )
            }.getOrElse { throwable ->
                rethrowInterruption(throwable)
                val currentHash = memoryFileStore.currentMemoryFileHash(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME)
                    .getOrNull()
                if (currentHash != payload.input.targetBaseHash) {
                    transitionCheckpoint(
                        checkpoint = checkpoint,
                        newStatus = MemoryDistillationCheckpointStatus.STALE_TARGET_BASE,
                        newTargetSourceHash = checkpoint.targetSourceHash,
                        mutationGroupId = null,
                        processedAt = null
                    )
                    maintenanceScheduler.markDismissed(job, MemoryDistillationCheckpointStatus.STALE_TARGET_BASE)
                    dailyDistillationScheduler.ensurePlanningJobs()
                    return MemoryDailyDistillationProcessResult(
                        status = MemoryDailyDistillationProcessResult.STATUS_TERMINAL,
                        jobId = job.jobId,
                        reason = MemoryDistillationCheckpointStatus.STALE_TARGET_BASE
                    )
                }
                return retryable(job, "daily_distillation_render_failed:${throwable.message}")
            }
            operationCount = proposal.operations.size
            mutation = runCatching {
                maintenanceScheduler.renewClaimedLease(job)
                memoryMutationCoordinator.prepare(
                    semanticJobId = job.jobId,
                    semanticBatchId = payload.input.batchId,
                    targets = rendered.targets
                )
            }.getOrElse { throwable ->
                rethrowInterruption(throwable)
                return retryable(job, "daily_distillation_prepare_failed:${throwable.message}")
            }
            if (BuildConfig.DEBUG) commitObserver.afterPrepared(mutation)
            checkpoint = transitionCheckpoint(
                checkpoint = checkpoint,
                newStatus = MemoryDistillationCheckpointStatus.PREPARED,
                newTargetSourceHash = rendered.targetSourceHash,
                mutationGroupId = mutation.group.groupId,
                processedAt = null
            )
        } else {
            checkpoint = alignCheckpointWithMutation(checkpoint, mutation)
        }

        val commitResult = runCatching {
            maintenanceScheduler.renewClaimedLease(job)
            memoryMutationCoordinator.reconcile(mutation)
        }.getOrElse { throwable ->
            rethrowInterruption(throwable)
            return retryable(job, "daily_distillation_commit_failed:${throwable.message}")
        }
        if (commitResult is MemoryMutationCommitResult.Conflict) {
            transitionCheckpoint(
                checkpoint = checkpoint,
                newStatus = MemoryDistillationCheckpointStatus.CONFLICT,
                newTargetSourceHash = checkpoint.targetSourceHash,
                mutationGroupId = commitResult.mutation.group.groupId,
                processedAt = now()
            )
            maintenanceScheduler.markFailedTerminal(
                job,
                "daily_distillation_mutation_conflict:${commitResult.sourcePath}"
            )
            dailyDistillationScheduler.ensurePlanningJobs()
            return MemoryDailyDistillationProcessResult(
                status = MemoryDailyDistillationProcessResult.STATUS_TERMINAL,
                jobId = job.jobId,
                operationCount = operationCount,
                reason = "daily_distillation_mutation_conflict:${commitResult.sourcePath}"
            )
        }

        val committed = commitResult as MemoryMutationCommitResult.CanonicalCommitted
        if (BuildConfig.DEBUG) commitObserver.afterCanonicalFileCommit(committed.mutation)
        checkpoint = completeCheckpoint(checkpoint, committed.mutation)
        if (BuildConfig.DEBUG) commitObserver.afterCheckpointCompletion(checkpoint)
        maintenanceScheduler.markSucceeded(job)
        memoryMutationCoordinator.acknowledgeSemanticCompletion(committed.mutation.group.groupId)
        dailyDistillationScheduler.ensurePlanningJobs()
        return MemoryDailyDistillationProcessResult(
            status = MemoryDailyDistillationProcessResult.STATUS_SUCCEEDED,
            jobId = job.jobId,
            operationCount = operationCount
        )
    }

    override suspend fun finalizeRecoveredMutation(recovered: MemoryRecoveredSemanticMutation): Boolean {
        var checkpoint = recoveryDao.getDistillationCheckpointBySemanticJobId(recovered.semanticJobId) ?: return false
        val mutation = memoryMutationCoordinator.findBySemanticJobId(recovered.semanticJobId)
            ?: error("Recovered daily distillation mutation is missing")
        checkpoint = alignCheckpointWithMutation(checkpoint, mutation)
        if (recovered.terminalReason != null) {
            if (checkpoint.status != MemoryDistillationCheckpointStatus.CONFLICT) {
                transitionCheckpoint(
                    checkpoint = checkpoint,
                    newStatus = MemoryDistillationCheckpointStatus.CONFLICT,
                    newTargetSourceHash = checkpoint.targetSourceHash,
                    mutationGroupId = mutation.group.groupId,
                    processedAt = now()
                )
            }
        } else if (checkpoint.status != MemoryDistillationCheckpointStatus.COMPLETED) {
            completeCheckpoint(checkpoint, mutation)
        }
        dailyDistillationScheduler.ensurePlanningJobs()
        return true
    }

    private fun decodePayload(job: MemoryMaintenanceJob): MemoryDailyDistillationJobPayload? = try {
        check(job.type == MemoryMaintenanceJobType.DISTILL_DAILY_NOTES)
        check(job.family == MemoryMaintenanceJobFamily.SEMANTIC)
        json.decodeFromString<MemoryDailyDistillationJobPayload>(job.payloadJson).also { payload ->
            check(payload.checkpointId.isNotBlank())
            check(payload.input.batchId.isNotBlank())
            check(payload.input.batchKey.isNotBlank())
            check(payload.input.dailySourcePath.matches(DAILY_SOURCE_REGEX))
            check(payload.input.dailySourceHash.matches(SHA_256_REGEX))
            check(payload.input.targetBaseHash.matches(SHA_256_REGEX))
            check(LocalDate.parse(payload.input.dailyDate, DateTimeFormatter.ISO_LOCAL_DATE) < LocalDate.now(clock))
            check(payload.input.dailyEvidence.isNotEmpty())
            check(payload.input.dailyEvidence.map { evidence -> evidence.evidenceKey }.distinct().size == payload.input.dailyEvidence.size)
            check(payload.inputHash == json.encodeToString(payload.input).sha256Utf8())
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private suspend fun checkpointFor(
        job: MemoryMaintenanceJob,
        payload: MemoryDailyDistillationJobPayload
    ): MemoryDistillationCheckpoint? {
        val checkpoint = recoveryDao.getDistillationCheckpointBySemanticJobId(job.jobId) ?: return null
        return checkpoint.takeIf { current ->
            current.checkpointId == payload.checkpointId &&
                current.dailySourcePath == payload.input.dailySourcePath &&
                current.dailySourceHash == payload.input.dailySourceHash &&
                current.batchKey == payload.input.batchKey &&
                current.dailyDate == payload.input.dailyDate &&
                current.targetSourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME &&
                current.targetBaseHash == payload.input.targetBaseHash &&
                current.status in (
                    MemoryDistillationCheckpointStatus.RECOVERABLE +
                        MemoryDistillationCheckpointStatus.REPLANNABLE +
                        setOf(MemoryDistillationCheckpointStatus.COMPLETED, MemoryDistillationCheckpointStatus.CONFLICT)
                    )
        }
    }

    private fun validateFrozenSources(input: MemoryDailyDistillationFrozenInput): String? {
        val dailyHash = memoryFileStore.currentMemoryFileHash(input.dailySourcePath).getOrNull()
        if (dailyHash != input.dailySourceHash) return MemoryDistillationCheckpointStatus.STALE_SOURCE
        val targetHash = memoryFileStore.currentMemoryFileHash(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).getOrNull()
        if (targetHash != input.targetBaseHash) return MemoryDistillationCheckpointStatus.STALE_TARGET_BASE
        return null
    }

    private suspend fun alignCheckpointWithMutation(
        checkpoint: MemoryDistillationCheckpoint,
        mutation: MemoryPreparedMutation
    ): MemoryDistillationCheckpoint {
        if (checkpoint.status == MemoryDistillationCheckpointStatus.COMPLETED) return checkpoint
        val targetHash = mutation.receipts.singleOrNull { receipt ->
            receipt.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
        }?.targetSourceHash ?: checkpoint.targetBaseHash
        if (
            checkpoint.status == MemoryDistillationCheckpointStatus.PREPARED &&
            checkpoint.mutationGroupId == mutation.group.groupId &&
            checkpoint.targetSourceHash == targetHash
        ) {
            return checkpoint
        }
        check(checkpoint.status == MemoryDistillationCheckpointStatus.PENDING)
        return transitionCheckpoint(
            checkpoint = checkpoint,
            newStatus = MemoryDistillationCheckpointStatus.PREPARED,
            newTargetSourceHash = targetHash,
            mutationGroupId = mutation.group.groupId,
            processedAt = null
        )
    }

    private suspend fun completeCheckpoint(
        checkpoint: MemoryDistillationCheckpoint,
        mutation: MemoryPreparedMutation
    ): MemoryDistillationCheckpoint {
        if (checkpoint.status == MemoryDistillationCheckpointStatus.COMPLETED) return checkpoint
        val targetHash = mutation.receipts.singleOrNull { receipt ->
            receipt.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
        }?.targetSourceHash ?: checkpoint.targetBaseHash
        return transitionCheckpoint(
            checkpoint = checkpoint,
            newStatus = MemoryDistillationCheckpointStatus.COMPLETED,
            newTargetSourceHash = targetHash,
            mutationGroupId = mutation.group.groupId,
            processedAt = now()
        )
    }

    private suspend fun transitionCheckpoint(
        checkpoint: MemoryDistillationCheckpoint,
        newStatus: String,
        newTargetSourceHash: String,
        mutationGroupId: String?,
        processedAt: Long?
    ): MemoryDistillationCheckpoint {
        val changed = recoveryDao.transitionDistillationCheckpointCas(
            checkpointId = checkpoint.checkpointId,
            expectedStatus = checkpoint.status,
            expectedRowVersion = checkpoint.rowVersion,
            expectedDailySourcePath = checkpoint.dailySourcePath,
            expectedDailySourceHash = checkpoint.dailySourceHash,
            expectedBatchKey = checkpoint.batchKey,
            expectedSemanticJobId = checkpoint.semanticJobId,
            expectedTargetSourcePath = checkpoint.targetSourcePath,
            expectedTargetBaseHash = checkpoint.targetBaseHash,
            expectedTargetSourceHash = checkpoint.targetSourceHash,
            expectedMutationGroupId = checkpoint.mutationGroupId,
            newStatus = newStatus,
            newTargetSourceHash = newTargetSourceHash,
            mutationGroupId = mutationGroupId,
            updatedAt = now(),
            processedAt = processedAt
        )
        if (changed == 1) {
            return checkNotNull(recoveryDao.getDistillationCheckpointBySemanticJobId(checkpoint.semanticJobId))
        }
        val current = checkNotNull(recoveryDao.getDistillationCheckpointBySemanticJobId(checkpoint.semanticJobId))
        check(
            current.status == newStatus &&
                current.targetSourceHash == newTargetSourceHash &&
                (mutationGroupId == null || current.mutationGroupId == mutationGroupId) &&
                (processedAt == null || current.processedAt != null)
        ) { "Distillation checkpoint changed before its state transition" }
        return current
    }

    private suspend fun preferredMemoryPlatform(): PlatformV2? = settingRepository.fetchPlatformV2s()
        .firstOrNull { platform -> platform.enabled && platform.model.isNotBlank() }

    private suspend fun retryable(
        job: MemoryMaintenanceJob,
        reason: String
    ): MemoryDailyDistillationProcessResult {
        val updated = maintenanceScheduler.markFailedRetryable(job, reason)
        val status = if (updated.status == MemoryMaintenanceJobStatus.FAILED_RETRYABLE) {
            MemoryDailyDistillationProcessResult.STATUS_RETRYABLE
        } else {
            MemoryDailyDistillationProcessResult.STATUS_TERMINAL
        }
        return MemoryDailyDistillationProcessResult(status = status, jobId = job.jobId, reason = reason)
    }

    private suspend fun terminal(
        job: MemoryMaintenanceJob,
        reason: String
    ): MemoryDailyDistillationProcessResult {
        if (job.status == MemoryMaintenanceJobStatus.RUNNING) {
            maintenanceScheduler.markFailedTerminal(job, reason)
        }
        return MemoryDailyDistillationProcessResult(
            status = MemoryDailyDistillationProcessResult.STATUS_TERMINAL,
            jobId = job.jobId,
            reason = reason
        )
    }

    private fun terminalResultOrNull(job: MemoryMaintenanceJob): MemoryDailyDistillationProcessResult? = when (job.status) {
        MemoryMaintenanceJobStatus.SUCCEEDED -> MemoryDailyDistillationProcessResult(
            status = MemoryDailyDistillationProcessResult.STATUS_DUPLICATE,
            jobId = job.jobId
        )
        MemoryMaintenanceJobStatus.FAILED_TERMINAL,
        MemoryMaintenanceJobStatus.DISMISSED -> MemoryDailyDistillationProcessResult(
            status = MemoryDailyDistillationProcessResult.STATUS_TERMINAL,
            jobId = job.jobId,
            reason = "job_${job.status}"
        )
        else -> null
    }

    private fun rethrowInterruption(throwable: Throwable) {
        if (throwable is CancellationException || throwable is MemoryMaintenanceLeaseLostException) throw throwable
    }

    private fun now(): Long = clock.instant().epochSecond

    private companion object {
        val SHA_256_REGEX = Regex("[0-9a-f]{64}")
        val DAILY_SOURCE_REGEX = Regex("memory/\\d{4}-\\d{2}-\\d{2}\\.md")
    }
}

interface MemoryDailyDistillationRecoveryFinalizer {
    suspend fun finalizeRecoveredMutation(recovered: MemoryRecoveredSemanticMutation): Boolean
}
