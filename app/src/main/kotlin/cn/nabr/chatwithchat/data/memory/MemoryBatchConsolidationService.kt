package cn.nabr.chatwithchat.data.memory

import android.util.Log
import cn.nabr.chatwithchat.data.database.dao.MemoryFrozenTurnClaimResult
import cn.nabr.chatwithchat.data.database.dao.MemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexDefaults
import cn.nabr.chatwithchat.data.repository.SettingRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class MemoryBatchConsolidationService(
    private val turnBatchDao: MemoryTurnBatchDao,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val turnBatchScheduler: MemoryTurnBatchScheduler,
    private val memoryProposalStore: MemoryProposalStore,
    private val settingRepository: SettingRepository,
    private val modelResolver: MemoryModelResolver,
    private val memoryIntelligence: MemoryIntelligence,
    private val memoryFileStore: MemoryFileStore,
    private val markdownMemoryCodec: MarkdownMemoryCodec,
    private val memoryMaintenanceCorpusReader: MemoryMaintenanceCorpusReader,
    private val memoryMutationCoordinator: MemoryMutationCoordinator,
    private val failureResponseStore: MemoryMaintenanceFailureResponseStore = MemoryMaintenanceFailureResponseStore.None,
    private val activityLogger: MemoryActivityLogger = MemoryActivityLogger.None,
    private val commitObserver: MemoryBatchCommitObserver = MemoryBatchCommitObserver.None,
    private val targetIndexFingerprint: String = MemoryVectorIndexDefaults.configuration.fingerprint(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
    }
) {
    private val canonicalMemoryMergePolicy = CanonicalMemoryMergePolicy(markdownMemoryCodec)

    suspend fun process(job: MemoryMaintenanceJob): MemoryBatchProcessResult {
        terminalResultOrNull(job)?.let { return it }
        val payload = decodePayload(job) ?: run {
            val activityRunId = activityLogger.startSemanticRun(job, MemoryActivityCategory.TURN_BATCH_CONSOLIDATION)
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                MemoryActivityRunData(errorCode = "invalid_batch_payload"),
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
            )
            return terminal(job, "invalid_batch_payload")
        }
        val activityRunId = activityLogger.startSemanticRun(
            job = job,
            category = MemoryActivityCategory.TURN_BATCH_CONSOLIDATION,
            triggerReason = payload.triggerReason,
            inputCount = payload.turns.size
        )
        val turns = runCatching {
            payload.turns.map { turn -> json.decodeFromString<MemoryCompletedTurnSnapshot>(turn.payloadJson) }
        }.getOrElse {
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                MemoryActivityRunData(inputCount = payload.turns.size, errorCode = "invalid_batch_turns"),
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
            )
            return terminal(job, "invalid_batch_turns")
        }
        when (ensureFrozenTurnClaim(job, payload)) {
            MemoryFrozenTurnClaimResult.CLAIMED -> Unit
            MemoryFrozenTurnClaimResult.ALREADY_COMPLETED -> {
                activityLogger.finishRunSafely(
                    activityRunId,
                    MemoryActivityStatus.NO_OP,
                    MemoryActivityRunData(inputCount = payload.turns.size, errorCode = "frozen_batch_already_complete"),
                    expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
                )
                maintenanceScheduler.markSucceeded(job)
                turnBatchScheduler.repairAndSchedule()
                return MemoryBatchProcessResult(
                    status = MemoryBatchProcessResult.STATUS_SUCCEEDED,
                    jobId = job.jobId,
                    reason = "frozen_batch_already_complete"
                )
            }
            MemoryFrozenTurnClaimResult.CONFLICTED -> {
                activityLogger.finishRunSafely(
                    activityRunId,
                    MemoryActivityStatus.FAILED,
                    MemoryActivityRunData(inputCount = payload.turns.size, errorCode = "frozen_batch_claim_conflict"),
                    expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
                )
                return terminal(job, "frozen_batch_claim_conflict")
            }
        }
        val existingMutation = memoryMutationCoordinator.findBySemanticJobId(job.jobId)
        val batchAlreadyComplete = existingMutation == null && isClaimedBatchComplete(job.jobId, payload)
        if (existingMutation == null && !batchAlreadyComplete && !validateClaimedTurns(job, payload)) {
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                MemoryActivityRunData(inputCount = payload.turns.size, errorCode = "invalid_claimed_batch"),
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
            )
            return terminal(job, "invalid_claimed_batch")
        }
        val existingMemories = if (existingMutation == null && !batchAlreadyComplete) {
            retrieveExistingMemories(turns)
        } else {
            emptyList()
        }
        val existingMemoryAliases = existingMemories.mapIndexed { index, memory ->
            MemoryAliasBinding(
                alias = "M${(index + 1).toString().padStart(2, '0')}",
                entryId = memory.id
            )
        }
        val boundProposals = memoryProposalStore.getBoundForTurns(turns.map(MemoryCompletedTurnSnapshot::turnKey))
            .mapIndexed { index, proposal ->
                MemoryBatchProposal(
                    ref = "P${(index + 1).toString().padStart(2, '0')}",
                    proposalId = proposal.proposalId,
                    intent = proposal.intent,
                    targetRef = proposal.targetRef,
                    text = proposal.normalizedText,
                    reason = proposal.reason,
                    evidenceStrength = proposal.effectiveEvidenceStrength,
                    boundTurnKey = checkNotNull(proposal.boundTurnKey)
                )
            }
        val request = MemoryBatchConsolidationRequest(
            batchId = payload.batchId,
            chatId = payload.chatId,
            chatTitle = turns.firstNotNullOfOrNull { turn -> turn.chatTitle.takeIf(String::isNotBlank) }.orEmpty(),
            triggerReason = payload.triggerReason,
            turns = turns,
            existingMemories = existingMemories,
            existingMemoryAliases = existingMemoryAliases,
            proposals = boundProposals
        )
        return execute(
            job = job,
            request = request,
            existingMutation = existingMutation,
            batchAlreadyComplete = batchAlreadyComplete,
            activityRunId = activityRunId,
            complete = { turnBatchDao.completeClaimedBatch(job.jobId, now()) },
            isComplete = { isClaimedBatchComplete(job.jobId, payload) }
        )
    }

    suspend fun processLegacy(job: MemoryMaintenanceJob): MemoryBatchProcessResult {
        terminalResultOrNull(job)?.let { return it }
        val requestWithoutMemories = decodeLegacyRequest(job) ?: run {
            val activityRunId = activityLogger.startSemanticRun(job, MemoryActivityCategory.TURN_BATCH_CONSOLIDATION)
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                MemoryActivityRunData(errorCode = "invalid_legacy_memory_payload"),
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
            )
            return terminal(job, "invalid_legacy_memory_payload")
        }
        val activityRunId = activityLogger.startSemanticRun(
            job = job,
            category = MemoryActivityCategory.TURN_BATCH_CONSOLIDATION,
            triggerReason = requestWithoutMemories.triggerReason,
            inputCount = requestWithoutMemories.turns.size
        )
        val existingMutation = memoryMutationCoordinator.findBySemanticJobId(job.jobId)
        val existingMemories = if (existingMutation == null) {
            retrieveExistingMemories(requestWithoutMemories.turns)
        } else {
            emptyList()
        }
        val request = requestWithoutMemories.copy(
            existingMemories = existingMemories,
            existingMemoryAliases = existingMemories.mapIndexed { index, memory ->
                MemoryAliasBinding("M${(index + 1).toString().padStart(2, '0')}", memory.id)
            }
        )
        return execute(
            job = job,
            request = request,
            existingMutation = existingMutation,
            batchAlreadyComplete = false,
            activityRunId = activityRunId,
            complete = { true },
            isComplete = { true }
        )
    }

    private suspend fun execute(
        job: MemoryMaintenanceJob,
        request: MemoryBatchConsolidationRequest,
        existingMutation: MemoryPreparedMutation?,
        batchAlreadyComplete: Boolean,
        activityRunId: String,
        complete: suspend () -> Boolean,
        isComplete: suspend () -> Boolean
    ): MemoryBatchProcessResult {
        if (existingMutation == null && !batchAlreadyComplete && !settingRepository.fetchMemoryEnabled()) {
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.SKIPPED,
                MemoryActivityRunData(inputCount = request.turns.size, errorCode = "memory_disabled"),
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
            )
            turnBatchScheduler.onMemoryEnabledChanged(false)
            return terminal(job, "memory_disabled", dismiss = true)
        }

        check(job.status == MemoryMaintenanceJobStatus.RUNNING) { "memory_job_not_claimed" }
        check(!job.leaseOwner.isNullOrBlank()) { "memory_job_missing_lease" }
        var runningJob = job
        runningJob = maintenanceScheduler.renewClaimedLease(runningJob)
        val startedAt = System.currentTimeMillis()
        logBatch(runningJob, request, "started", proposalCount = null, elapsedMs = null)

        var operationCount = 0
        var dailyWriteCount = 0
        var longTermWriteCount = 0
        var operationDiagnostics = emptyList<MemoryOperationDiagnostic>()
        var activityPlatform: PlatformV2? = null
        var rawResponseJson: String? = null
        val preparedMutation: MemoryPreparedMutation?

        if (existingMutation != null || batchAlreadyComplete) {
            activityLogger.advanceRunSafely(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION,
                nextPhase = MemoryActivityPhase.ORGANIZATION,
                data = MemoryActivityRunData(inputCount = request.turns.size)
            )
            preparedMutation = existingMutation
        } else {
            val binding = resolveClaimedMemoryModel(
                job = runningJob,
                settingRepository = settingRepository,
                modelResolver = modelResolver,
                maintenanceScheduler = maintenanceScheduler
            )
            val resolvedPlatform = when (binding) {
                is ClaimedMemoryModelBinding.Unavailable -> {
                    val reason = binding.reason.code
                    activityLogger.finishRunSafely(
                        activityRunId,
                        MemoryActivityStatus.BLOCKED,
                        MemoryActivityRunData(inputCount = request.turns.size, errorCode = reason),
                        expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
                    )
                    maintenanceScheduler.markBlockedDependency(runningJob, reason)
                    turnBatchScheduler.scheduleNextWake()
                    return MemoryBatchProcessResult(
                        status = MemoryBatchProcessResult.STATUS_BLOCKED,
                        jobId = runningJob.jobId,
                        reason = reason
                    )
                }
                is ClaimedMemoryModelBinding.Resolved -> {
                    runningJob = binding.job
                    binding.platform
                }
            }
            activityPlatform = resolvedPlatform
            val response = memoryIntelligence.consolidateMemoryBatchWithRawResponse(request, resolvedPlatform, activityRunId)
            rawResponseJson = response.rawResponseJson
            val proposal = response.proposal
            runningJob = maintenanceScheduler.renewClaimedLease(runningJob)
            if (proposal == null) {
                val reason = if (response.truncated) {
                    MEMORY_RESPONSE_TRUNCATED_REASON
                } else {
                    "consolidation_unavailable_or_invalid"
                }
                activityLogger.finishRunSafely(
                    activityRunId,
                    MemoryActivityStatus.FAILED,
                    resolvedPlatform.toMemoryActivityData(
                        inputCount = request.turns.size,
                        errorCode = reason
                    ),
                    expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
                )
                // A payload cut on the output cap reproduces byte for byte at temperature 0, so the
                // automatic attempts would only replay the same failure.
                if (response.truncated) {
                    recordFailureResponse(runningJob, reason, rawResponseJson)
                    return terminal(runningJob, reason)
                }
                return retryable(
                    runningJob,
                    request,
                    reason,
                    startedAt,
                    null,
                    rawResponseJson
                )
            }
            activityLogger.advanceRunSafely(
                activityRunId = activityRunId,
                expectedPhase = MemoryActivityPhase.GENERATION,
                nextPhase = MemoryActivityPhase.ORGANIZATION,
                data = resolvedPlatform.toMemoryActivityData(
                    inputCount = request.turns.size,
                    operationCount = proposal.operations.size
                )
            )
            val validation = runCatching { validateOperations(request, proposal.operations) }
                .getOrElse { throwable ->
                    activityLogger.finishRunSafely(
                        activityRunId,
                        MemoryActivityStatus.FAILED,
                        resolvedPlatform.toMemoryActivityData(
                            inputCount = request.turns.size,
                            operationCount = proposal.operations.size,
                            errorCode = "invalid_consolidation_operations"
                        ),
                        expectedPhase = MemoryActivityPhase.ORGANIZATION
                    )
                    return retryable(
                        runningJob,
                        request,
                        "invalid_consolidation_operations:${throwable.message}",
                        startedAt,
                        proposal.operations.size,
                        rawResponseJson
                    )
                }
            if (proposal.operations.isNotEmpty() && validation.operations.isEmpty()) {
                activityLogger.finishRunSafely(
                    activityRunId,
                    MemoryActivityStatus.FAILED,
                    resolvedPlatform.toMemoryActivityData(
                        inputCount = request.turns.size,
                        operationCount = proposal.operations.size,
                        errorCode = "all_consolidation_operations_invalid",
                        errorDetail = validation.diagnostics.toActivityDetail()
                    ),
                    expectedPhase = MemoryActivityPhase.ORGANIZATION
                )
                return retryable(
                    runningJob,
                    request,
                    "all_consolidation_operations_invalid",
                    startedAt,
                    proposal.operations.size,
                    rawResponseJson
                )
            }
            val renderedBatch = runCatching {
                renderOperations(
                    batchId = request.batchId,
                    chatId = request.chatId,
                    existingMemories = request.existingMemories,
                    operations = validation.operations
                )
            }.getOrElse { throwable ->
                rethrowCommitInterruption(throwable)
                activityLogger.finishRunSafely(
                    activityRunId,
                    MemoryActivityStatus.FAILED,
                    resolvedPlatform.toMemoryActivityData(
                        inputCount = request.turns.size,
                        operationCount = proposal.operations.size,
                        errorCode = "memory_render_failed"
                    ),
                    expectedPhase = MemoryActivityPhase.ORGANIZATION
                )
                return retryable(
                    runningJob,
                    request,
                    "memory_render_failed:${throwable.message}",
                    startedAt,
                    proposal.operations.size,
                    rawResponseJson
                )
            }
            operationCount = validation.operations.size
            operationDiagnostics = validation.diagnostics
            dailyWriteCount = renderedBatch.dailyWriteCount
            longTermWriteCount = renderedBatch.longTermWriteCount
            preparedMutation = runCatching {
                runningJob = maintenanceScheduler.renewClaimedLease(runningJob)
                memoryMutationCoordinator.prepare(
                    semanticJobId = runningJob.jobId,
                    semanticBatchId = request.batchId,
                    targets = renderedBatch.targets
                ).also { mutation -> commitObserver.afterPrepared(mutation) }
            }.getOrElse { throwable ->
                rethrowCommitInterruption(throwable)
                activityLogger.finishRunSafely(
                    activityRunId,
                    MemoryActivityStatus.FAILED,
                    resolvedPlatform.toMemoryActivityData(
                        inputCount = request.turns.size,
                        operationCount = proposal.operations.size,
                        errorCode = "memory_prepare_failed"
                    ),
                    expectedPhase = MemoryActivityPhase.ORGANIZATION
                )
                return retryable(
                    runningJob,
                    request,
                    "memory_prepare_failed:${throwable.message}",
                    startedAt,
                    proposal.operations.size,
                    rawResponseJson
                )
            }
        }

        if (preparedMutation != null) {
            val commitResult = runCatching {
                runningJob = maintenanceScheduler.renewClaimedLease(runningJob)
                memoryMutationCoordinator.reconcile(preparedMutation).also { result ->
                    if (result is MemoryMutationCommitResult.CanonicalCommitted) {
                        commitObserver.afterCanonicalFileCommit(result.mutation)
                    }
                }
            }.getOrElse { throwable ->
                rethrowCommitInterruption(throwable)
                activityLogger.finishRunSafely(
                    activityRunId,
                    MemoryActivityStatus.FAILED,
                    activityPlatform.activityData(
                        inputCount = request.turns.size,
                        operationCount = operationCount.takeIf { it > 0 },
                        errorCode = "memory_commit_failed"
                    ),
                    expectedPhase = MemoryActivityPhase.ORGANIZATION
                )
                return retryable(
                    runningJob,
                    request,
                    "memory_commit_failed:${throwable.message}",
                    startedAt,
                    operationCount.takeIf { it > 0 },
                    rawResponseJson
                )
            }
            if (commitResult is MemoryMutationCommitResult.Conflict) {
                activityLogger.finishRunSafely(
                    activityRunId,
                    MemoryActivityStatus.FAILED,
                    activityPlatform.activityData(
                        inputCount = request.turns.size,
                        operationCount = operationCount.takeIf { it > 0 },
                        errorCode = "memory_commit_conflict"
                    ),
                    expectedPhase = MemoryActivityPhase.ORGANIZATION
                )
                val conflictBatchCompleted = runCatching { complete() || isComplete() }.getOrElse { throwable ->
                    rethrowCommitInterruption(throwable)
                    return retryable(
                        runningJob,
                        request,
                        "conflicted_batch_completion_failed:${throwable.message}",
                        startedAt,
                        operationCount.takeIf { it > 0 },
                        rawResponseJson
                    )
                }
                if (!conflictBatchCompleted) {
                    return retryable(
                        runningJob,
                        request,
                        "conflicted_batch_completion_failed",
                        startedAt,
                        operationCount.takeIf { it > 0 },
                        rawResponseJson
                    )
                }
                commitObserver.afterBatchCompletion(runningJob.jobId)
                recordFailureResponse(runningJob, commitResult.reason, rawResponseJson)
                return terminal(runningJob, commitResult.reason)
            }
        }

        runningJob = maintenanceScheduler.renewClaimedLease(runningJob)
        val batchCompleted = runCatching { complete() || isComplete() }.getOrElse { throwable ->
            rethrowCommitInterruption(throwable)
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                activityPlatform.activityData(
                    inputCount = request.turns.size,
                    operationCount = operationCount.takeIf { it > 0 },
                    errorCode = "batch_completion_failed"
                ),
                expectedPhase = MemoryActivityPhase.ORGANIZATION
            )
            return retryable(
                runningJob,
                request,
                "claimed_batch_completion_failed:${throwable.message}",
                startedAt,
                operationCount.takeIf { it > 0 },
                rawResponseJson
            )
        }
        if (!batchCompleted) {
            activityLogger.finishRunSafely(
                activityRunId,
                MemoryActivityStatus.FAILED,
                activityPlatform.activityData(
                    inputCount = request.turns.size,
                    operationCount = operationCount.takeIf { it > 0 },
                    errorCode = "batch_completion_failed"
                ),
                expectedPhase = MemoryActivityPhase.ORGANIZATION
            )
            return retryable(
                runningJob,
                request,
                "claimed_batch_completion_failed",
                startedAt,
                operationCount.takeIf { it > 0 },
                rawResponseJson
            )
        }
        commitObserver.afterBatchCompletion(runningJob.jobId)
        maintenanceScheduler.markSucceeded(runningJob)
        commitObserver.afterSourceJobCompletion(runningJob.jobId)
        preparedMutation?.let { mutation ->
            memoryMutationCoordinator.acknowledgeSemanticCompletion(mutation.group.groupId)
        }
        memoryProposalStore.markConsumed(
            proposalIds = request.proposals.map(MemoryBatchProposal::proposalId),
            mutationGroupId = preparedMutation?.group?.groupId,
            now = now()
        )
        turnBatchScheduler.repairAndSchedule()
        activityLogger.finishRunSafely(
            activityRunId,
            if (operationCount == 0 && existingMutation == null && !batchAlreadyComplete) {
                MemoryActivityStatus.NO_OP
            } else {
                MemoryActivityStatus.SUCCEEDED
            },
            activityPlatform.activityData(
                inputCount = request.turns.size,
                operationCount = operationCount,
                errorDetail = operationDiagnostics.toActivityDetail()
            ),
            expectedPhase = MemoryActivityPhase.ORGANIZATION
        )
        logBatch(
            runningJob,
            request,
            "succeeded",
            proposalCount = operationCount.takeIf { it > 0 },
            elapsedMs = System.currentTimeMillis() - startedAt
        )
        return MemoryBatchProcessResult(
            status = MemoryBatchProcessResult.STATUS_SUCCEEDED,
            jobId = job.jobId,
            operationCount = operationCount,
            dailyWriteCount = dailyWriteCount,
            longTermWriteCount = longTermWriteCount
        )
    }

    private fun terminalResultOrNull(job: MemoryMaintenanceJob): MemoryBatchProcessResult? = when (job.status) {
        MemoryMaintenanceJobStatus.SUCCEEDED ->
            MemoryBatchProcessResult(MemoryBatchProcessResult.STATUS_DUPLICATE, job.jobId)
        MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY -> MemoryBatchProcessResult(
            status = MemoryBatchProcessResult.STATUS_BLOCKED,
            jobId = job.jobId,
            reason = job.blockedReason ?: job.lastError
        )
        MemoryMaintenanceJobStatus.FAILED_TERMINAL,
        MemoryMaintenanceJobStatus.DISMISSED -> MemoryBatchProcessResult(
            status = MemoryBatchProcessResult.STATUS_TERMINAL,
            jobId = job.jobId,
            reason = "job_${job.status}"
        )
        else -> null
    }

    private fun decodePayload(job: MemoryMaintenanceJob): MemoryTurnBatchJobPayload? = runCatching {
        check(job.type == MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH)
        val payload = json.decodeFromString<MemoryTurnBatchJobPayload>(job.payloadJson)
        check(payload.chatId > 0)
        check(payload.turns.size in 1..MemoryTurnBatchCoordinator.MAX_BATCH_TURNS)
        check(payload.triggerReason in VALID_TRIGGER_REASONS)
        check(payload.batchId == job.sourceIdempotencyKey)
        check(payload.turns.map { it.turnKey }.distinct().size == payload.turns.size)
        payload.turns.forEach { jobTurn ->
            check(sha256(jobTurn.payloadJson) == jobTurn.contentHash)
            val snapshot = json.decodeFromString<MemoryCompletedTurnSnapshot>(jobTurn.payloadJson)
            check(snapshot.turnKey == jobTurn.turnKey)
            check(snapshot.chatId == payload.chatId)
            check(snapshot.userMessageId == jobTurn.userMessageId)
        }
        val combinedHash = sha256(payload.turns.joinToString(separator = "|") { it.contentHash })
        check(combinedHash == payload.contentHash)
        payload
    }.getOrElse { throwable ->
        runCatching { Log.w(TAG, "Memory batch ${job.jobId} has invalid local payload: ${throwable.message}") }
        null
    }

    private suspend fun validateClaimedTurns(
        job: MemoryMaintenanceJob,
        payload: MemoryTurnBatchJobPayload
    ): Boolean = runCatching {
        val claimedTurns = turnBatchDao.getTurnsClaimedByJob(job.jobId)
        check(claimedTurns.size == payload.turns.size)
        val claimedByKey = claimedTurns.associateBy { it.turnKey }
        payload.turns.forEach { jobTurn ->
            val claimedTurn = checkNotNull(claimedByKey[jobTurn.turnKey])
            check(claimedTurn.chatId == payload.chatId)
            check(claimedTurn.userMessageId == jobTurn.userMessageId)
            check(claimedTurn.contentHash == jobTurn.contentHash)
            check(claimedTurn.payloadJson == jobTurn.payloadJson)
        }
        true
    }.getOrElse { throwable ->
        runCatching { Log.w(TAG, "Memory batch ${job.jobId} has invalid claimed turns: ${throwable.message}") }
        false
    }

    private suspend fun ensureFrozenTurnClaim(
        job: MemoryMaintenanceJob,
        payload: MemoryTurnBatchJobPayload
    ): MemoryFrozenTurnClaimResult {
        if (validateClaimedTurns(job, payload)) return MemoryFrozenTurnClaimResult.CLAIMED
        return turnBatchDao.claimFrozenTurnsForRequeue(
            turnKeys = payload.turns.map { turn -> turn.turnKey },
            jobId = job.jobId,
            updatedAt = now()
        )
    }

    private suspend fun isClaimedBatchComplete(
        jobId: String,
        payload: MemoryTurnBatchJobPayload
    ): Boolean {
        if (turnBatchDao.getTurnsClaimedByJob(jobId).isNotEmpty()) return false
        val checkpoint = turnBatchDao.getCheckpoint(payload.chatId) ?: return false
        return checkpoint.lastProcessedUserMessageId >= payload.turns.maxOf { turn -> turn.userMessageId }
    }

    private fun decodeLegacyRequest(job: MemoryMaintenanceJob): MemoryBatchConsolidationRequest? = runCatching {
        val decoded = when (job.type) {
            MemoryMaintenanceJobType.APPEND_DAILY_NOTE -> {
                val payload = json.decodeFromString<MarkdownMemoryLearningJobPayload>(job.payloadJson)
                LegacyMemoryJobContent(
                    chatId = payload.chatId,
                    chatTitle = payload.chatTitle,
                    platformUid = LEGACY_PLATFORM_UID,
                    messages = payload.recentMessages,
                    createdAt = payload.createdAt,
                    triggerReason = MemoryTurnBatchTriggerReason.LEGACY_APPEND_DAILY_NOTE
                )
            }
            MemoryMaintenanceJobType.COMPACTION_FLUSH -> {
                val payload = json.decodeFromString<MemoryCompactionFlushJobPayload>(job.payloadJson)
                LegacyMemoryJobContent(
                    chatId = payload.chatId,
                    chatTitle = "Legacy context compaction",
                    platformUid = payload.platformUid,
                    messages = payload.messages,
                    createdAt = payload.createdAt,
                    triggerReason = MemoryTurnBatchTriggerReason.LEGACY_COMPACTION_FLUSH
                )
            }
            else -> error("unsupported_legacy_memory_job_type:${job.type}")
        }
        check(decoded.chatId > 0)
        val userContent = decoded.messages
            .filterNot { message -> message.role == "assistant" }
            .joinToString(separator = "\n") { message -> "${message.role}: ${message.content.trim()}" }
            .trim()
            .take(MAX_LEGACY_MESSAGE_CHARS)
        val assistantContent = decoded.messages
            .filter { message -> message.role == "assistant" }
            .joinToString(separator = "\n") { message -> message.content.trim() }
            .trim()
            .take(MAX_LEGACY_MESSAGE_CHARS)
        check(userContent.isNotBlank() || assistantContent.isNotBlank())
        val turnKey = "legacy:${sha256(job.idempotencyKey).take(24)}"
        val turn = MemoryCompletedTurnSnapshot(
            turnKey = turnKey,
            chatId = decoded.chatId,
            chatTitle = decoded.chatTitle.trim().take(MAX_LEGACY_TITLE_CHARS),
            userMessageId = 1,
            userContent = userContent,
            userAttachments = emptyList(),
            assistantPlatformUid = decoded.platformUid.ifBlank { LEGACY_PLATFORM_UID },
            assistantContent = assistantContent,
            completedAt = decoded.createdAt
        )
        MemoryBatchConsolidationRequest(
            batchId = "legacy_memory_job:${job.idempotencyKey}",
            chatId = decoded.chatId,
            chatTitle = turn.chatTitle,
            triggerReason = decoded.triggerReason,
            turns = listOf(turn),
            existingMemories = emptyList()
        )
    }.getOrElse { throwable ->
        runCatching { Log.w(TAG, "Legacy memory job ${job.jobId} has invalid local payload: ${throwable.message}") }
        null
    }

    private suspend fun retrieveExistingMemories(
        turns: List<MemoryCompletedTurnSnapshot>
    ): List<MemoryBatchExistingMemory> {
        val query = turns
            .flatMap { turn -> listOf(turn.userContent, turn.assistantContent) }
            .joinToString(separator = "\n")
            .take(MAX_RETRIEVAL_QUERY_CHARS)
        if (query.isBlank()) return emptyList()
        val results = memoryMaintenanceCorpusReader.retrieveWorkingSet(
            MemoryRetrievalRequest(
                corpus = MemoryCorpus.MAINTENANCE_WORKING_SET,
                query = query,
                includePrivate = true,
                limit = MAX_EXISTING_MEMORIES,
                candidateLimit = MAX_EXISTING_CANDIDATES,
                tokenBudget = MAX_EXISTING_MEMORY_TOKEN_BUDGET
            )
        ).getOrDefault(emptyList())
        val entriesBySourceAndId = memoryFileStore.readCorpusFiles(MemoryCorpus.MAINTENANCE_WORKING_SET)
            .getOrNull()
            ?.files
            .orEmpty()
            .flatMap { file ->
                markdownMemoryCodec.parse(String(file.bytes, StandardCharsets.UTF_8)).entries.map { entry ->
                    (file.sourcePath to entry.id) to entry
                }
            }
            .toMap()
        val recalledIds = turns
            .flatMap { turn -> turn.recalledMemoryAliases }
            .associate { binding -> binding.entryId to binding.alias }
        val recalledEntries = recalledIds.keys.mapNotNull { entryId ->
            entriesBySourceAndId.entries.firstOrNull { (_, entry) -> entry.id == entryId }?.let { (sourceAndId, entry) ->
                val sourcePath = sourceAndId.first
                MemoryBatchExistingMemory(
                    id = entry.id,
                    sourcePath = sourcePath,
                    text = entry.text,
                    type = entry.type,
                    sensitivity = entry.sensitivity,
                    source = entry.source,
                    updatedAt = entry.updatedAt,
                    createdAt = entry.createdAt,
                    canonicalKey = entry.canonicalKey,
                    scope = entry.scope,
                    lastObservedAt = entry.lastObservedAt,
                    validity = entry.validity,
                    supersededBy = entry.supersededBy,
                    recallState = entry.recallState,
                    evidenceRefs = entry.evidenceRefs
                )
            }
        }
        val retrieved = results
            .filter { result ->
                !result.entryId.isNullOrBlank() &&
                    !result.type.isNullOrBlank() &&
                    !result.sensitivity.isNullOrBlank() &&
                    !result.source.isNullOrBlank() &&
                    entriesBySourceAndId.containsKey(result.sourcePath to result.entryId)
            }
            .groupBy { it.entryId!! }
            .filterValues { sameIdResults -> sameIdResults.map { it.sourcePath }.distinct().size == 1 }
            .map { (entryId, sameIdResults) ->
                val result = sameIdResults.maxBy { it.fusedScore }
                val entry = checkNotNull(entriesBySourceAndId[result.sourcePath to entryId])
                MemoryBatchExistingMemory(
                    id = entryId,
                    sourcePath = result.sourcePath,
                    text = entry.text,
                    type = entry.type,
                    sensitivity = entry.sensitivity,
                    source = entry.source,
                    updatedAt = entry.updatedAt,
                    createdAt = entry.createdAt,
                    canonicalKey = entry.canonicalKey,
                    scope = entry.scope,
                    lastObservedAt = entry.lastObservedAt,
                    validity = entry.validity,
                    supersededBy = entry.supersededBy,
                    recallState = entry.recallState,
                    evidenceRefs = entry.evidenceRefs
                )
            }
            .toList()
        return (recalledEntries + retrieved)
            .distinctBy(MemoryBatchExistingMemory::id)
            .sortedWith(
                compareBy<MemoryBatchExistingMemory> { memory ->
                    if (memory.validity == MemoryValidity.CURRENT) 0 else 1
                }
                    .thenByDescending(MemoryBatchExistingMemory::updatedAt)
                    .thenBy(MemoryBatchExistingMemory::id)
            )
            .take(MAX_EXISTING_MEMORIES)
    }

    private fun validateOperations(
        request: MemoryBatchConsolidationRequest,
        operations: List<MemoryBatchOperation>
    ): MemoryBatchOperationValidationResult {
        check(operations.size <= MemoryControlledOperationPolicy.MAX_OPERATIONS) { "too_many_operations" }
        val existingById = request.existingMemories.associateBy { it.id }
        val idsByAlias = request.existingMemoryAliases.associate { binding -> binding.alias to binding.entryId }
        val recalledIdsByAlias = request.turns
            .flatMap { turn -> turn.recalledMemoryAliases }
            .associate { binding -> binding.alias to binding.entryId }
        val proposalsByRef = request.proposals.associateBy { it.ref }
        val turnsByKey = request.turns.associateBy { it.turnKey }
        val diagnostics = mutableListOf<MemoryOperationDiagnostic>()

        val validatedIndexed = operations.mapIndexedNotNull { index, operation ->
            val validatedOperation = runCatching {
            val proposal = operation.proposalRef?.let { ref ->
                check(ref.matches(PROPOSAL_REF_PATTERN)) { "proposal_ref_invalid" }
                checkNotNull(proposalsByRef[ref]) { "proposal_ref_unknown" }
            }
            val proposalTargetId = proposal?.targetRef?.let { ref ->
                recalledIdsByAlias[ref] ?: idsByAlias[ref]
            }
            val operationWithProposal = if (proposal == null) {
                operation
            } else {
                check(operation.text.isBlank()) { "proposal_text_must_be_blank" }
                val expectedAction = when (proposal.intent) {
                    "remember" -> MemoryBatchAction.CREATE
                    "update" -> MemoryBatchAction.REPLACE
                    "forget" -> MemoryBatchAction.REMOVE
                    else -> error("proposal_intent_invalid")
                }
                check(operation.action == expectedAction) { "proposal_action_mismatch" }
                check(proposal.intent == "remember" || proposalTargetId != null) { "proposal_target_unresolved" }
                operation.copy(
                    targetMemoryId = operation.targetMemoryId ?: proposalTargetId,
                    text = proposal.text,
                    source = proposal.evidenceStrength.toMemorySource(),
                    evidenceTurnKeys = (operation.evidenceTurnKeys + proposal.boundTurnKey).distinct()
                )
            }
            val operation = operationWithProposal
            check(operation.destination in VALID_DESTINATIONS) { "destination_invalid" }
            check(operation.action in VALID_ACTIONS) { "action_invalid" }
            check(operation.type in MemoryControlledOperationPolicy.validTypes) { "type_invalid" }
            check(operation.sensitivity in MemoryControlledOperationPolicy.validSensitivities) { "sensitivity_invalid" }
            check(operation.source in MemoryControlledOperationPolicy.validSources) { "source_invalid" }
            check(operation.reason.length <= MemoryControlledOperationPolicy.MAX_REASON_CHARS) { "reason_too_long" }
            check(operation.evidenceTurnKeys.size <= MemoryControlledOperationPolicy.MAX_EVIDENCE_KEYS) { "too_many_evidence_turns" }
            check(operation.evidenceTurnKeys.distinct().size == operation.evidenceTurnKeys.size) { "duplicate_evidence_turn" }
            check(operation.evidenceTurnKeys.all(turnsByKey::containsKey)) { "unknown_evidence_turn" }

            when (operation.action) {
                MemoryBatchAction.IGNORE -> {
                    check(operation.targetMemoryId.isNullOrBlank()) { "ignore_target_id_forbidden" }
                    check(operation.targetMemoryRef.isNullOrBlank()) { "ignore_target_ref_forbidden" }
                    check(operation.text.isBlank()) { "ignore_text_forbidden" }
                    check(operation.canonicalKey == null) { "ignore_canonical_key_forbidden" }
                    check(operation.scope == null) { "ignore_scope_forbidden" }
                    check(operation.evidenceAt == null) { "ignore_evidence_at_forbidden" }
                    check(operation.recallState == null) { "ignore_recall_state_forbidden" }
                    check(operation.memoryClass == null) { "ignore_memory_class_forbidden" }
                    operation.copy(reason = operation.reason.trim())
                }
                MemoryBatchAction.CREATE -> {
                    check(operation.targetMemoryId.isNullOrBlank()) { "create_target_id_forbidden" }
                    check(operation.targetMemoryRef.isNullOrBlank()) { "create_target_ref_forbidden" }
                    validateWriteText(operation.text)
                    check(operation.evidenceTurnKeys.isNotEmpty()) { "create_requires_evidence" }
                    check(
                        operation.destination != MemoryBatchDestination.LONG_TERM ||
                            operation.source != MemorySource.ASSISTANT_INFERRED ||
                            hasCrossDayEvidence(operation.evidenceTurnKeys, turnsByKey)
                    ) { "assistant_inferred_long_term_create_requires_repeated_evidence" }
                    validatedCanonicalWrite(operation, null, turnsByKey)
                }
                MemoryBatchAction.REPLACE -> {
                    val targetId = operation.targetMemoryId?.takeIf { it.isNotBlank() }
                        ?: operation.targetMemoryRef?.let { ref -> idsByAlias[ref] }
                    checkNotNull(targetId) { "replace_target_unresolved" }
                    val existing = checkNotNull(existingById[targetId]) { "replace_target_unknown" }
                    check(operation.destination == destinationFor(existing.sourcePath)) { "replace_destination_mismatch" }
                    validateWriteText(operation.text)
                    check(operation.evidenceTurnKeys.isNotEmpty()) { "replace_requires_evidence" }
                    validatedCanonicalWrite(operation, existing, turnsByKey).copy(
                        targetMemoryId = targetId,
                        targetMemoryRef = null
                    )
                }
                MemoryBatchAction.REMOVE -> {
                    val targetId = operation.targetMemoryId?.takeIf { it.isNotBlank() }
                        ?: operation.targetMemoryRef?.let { ref -> idsByAlias[ref] }
                    checkNotNull(targetId) { "remove_target_unresolved" }
                    val existing = checkNotNull(existingById[targetId]) { "remove_target_unknown" }
                    check(operation.destination == destinationFor(existing.sourcePath)) { "remove_destination_mismatch" }
                    check(operation.text.isBlank()) { "remove_text_forbidden" }
                    check(operation.evidenceTurnKeys.isNotEmpty()) { "remove_requires_evidence" }
                    check(operation.canonicalKey == null) { "remove_canonical_key_forbidden" }
                    check(operation.scope == null) { "remove_scope_forbidden" }
                    check(operation.evidenceAt == null) { "remove_evidence_at_forbidden" }
                    check(operation.recallState == null) { "remove_recall_state_forbidden" }
                    check(operation.memoryClass == null) { "remove_memory_class_forbidden" }
                    operation.copy(
                        targetMemoryId = targetId,
                        targetMemoryRef = null,
                        evidenceTurnKeys = operation.evidenceTurnKeys.sorted(),
                        reason = operation.reason.trim()
                    )
                }
                else -> error("action_unsupported")
            }
            }.getOrElse { throwable ->
                diagnostics += MemoryOperationDiagnostic(index, throwable.safeValidationCode())
                return@mapIndexedNotNull null
            }
            IndexedValue(index, validatedOperation)
        }
        val destinationByText = mutableMapOf<String, String>()
        val writeKeys = mutableSetOf<String>()
        val retained = validatedIndexed.mapNotNull { indexed ->
            val operation = indexed.value
            if (operation.action !in setOf(MemoryBatchAction.CREATE, MemoryBatchAction.REPLACE)) {
                return@mapNotNull indexed
            }
            val normalizedText = normalizeExactMemoryText(operation.text)
            val previousDestination = destinationByText[normalizedText]
            if (previousDestination != null && previousDestination != operation.destination) {
                diagnostics += MemoryOperationDiagnostic(indexed.index, "cross_destination_exact_memory_text")
                return@mapNotNull null
            }
            val writeKey = "${operation.destination}|$normalizedText"
            if (!writeKeys.add(writeKey)) {
                diagnostics += MemoryOperationDiagnostic(indexed.index, "duplicate_exact_memory_text")
                return@mapNotNull null
            }
            destinationByText[normalizedText] = operation.destination
            indexed
        }

        val targetedIds = mutableSetOf<String>()
        val operationsWithDistinctTargets = retained.mapNotNull { indexed ->
            val operation = indexed.value
            val targetId = operation.targetMemoryId
            if (
                operation.action !in setOf(MemoryBatchAction.REPLACE, MemoryBatchAction.REMOVE) ||
                targetedIds.add(checkNotNull(targetId))
            ) {
                indexed
            } else {
                diagnostics += MemoryOperationDiagnostic(
                    indexed.index,
                    "duplicate_target_memory"
                )
                null
            }
        }

        return MemoryBatchOperationValidationResult(
            operationsWithDistinctTargets.map { it.value },
            diagnostics.sortedBy(MemoryOperationDiagnostic::index)
        )
    }

    private fun validatedCanonicalWrite(
        operation: MemoryBatchOperation,
        target: MemoryBatchExistingMemory?,
        turnsByKey: Map<String, MemoryCompletedTurnSnapshot>
    ): MemoryBatchOperation {
        val canonicalKey = checkNotNull(operation.canonicalKey) { "canonical_key_missing" }
        val scope = checkNotNull(operation.scope) { "scope_missing" }
        val normalizedCanonicalKey = MemoryCanonicalIdentityPolicy.normalizeCanonicalKey(canonicalKey, scope)
        val recallState = checkNotNull(operation.recallState) { "recall_state_missing" }
        // The cited turns own the evidence time. Whatever the model echoed back is discarded rather
        // than validated: a model that miscopies one epoch second must not cost the whole batch.
        val evidenceAt = operation.evidenceTurnKeys.maxOf { key -> turnsByKey.getValue(key).completedAt }
        check(MarkdownMemoryMetadataPolicy.isCanonicalKey(normalizedCanonicalKey)) { "canonical_key_invalid" }
        check(MarkdownMemoryMetadataPolicy.isScope(scope)) { "scope_invalid" }
        check(evidenceAt >= 0L) { "evidence_at_negative" }
        check(recallState in ACTIVE_RECALL_STATES) { "recall_state_invalid" }
        // Obsolete ids are recoverable history. Their own metadata may describe an old merge;
        // the full canonical document contains the successor and performs final validation.
        target
            ?.takeIf { existing -> existing.validity != MemoryValidity.OBSOLETE }
            ?.let { existing ->
                check(existing.type == operation.type) { "target_type_mismatch" }
                check(
                    existing.canonicalKey == null ||
                        MemoryCanonicalIdentityPolicy.allowsRebinding(
                            fromKey = existing.canonicalKey,
                            fromScope = existing.scope,
                            toKey = normalizedCanonicalKey,
                            toScope = scope
                        )
                ) { "canonical_key_rebinding_forbidden" }
                check(existing.canonicalKey == null || existing.scope == scope) { "target_scope_mismatch" }
            }
        val normalizedMemoryClass = MemoryGenerationPolicy.validate(
            memoryClass = operation.memoryClass,
            type = operation.type,
            scope = scope,
            source = operation.source,
            recallState = recallState,
            destination = operation.destination
        )
        if (
            operation.destination == MemoryBatchDestination.LONG_TERM &&
            operation.source == MemorySource.ASSISTANT_INFERRED &&
            operation.type in setOf("stable_profile", "interest")
        ) {
            check(hasCrossDayEvidence(operation.evidenceTurnKeys, turnsByKey)) {
                "assistant_inferred_profile_requires_cross_day_evidence"
            }
        }
        return operation.copy(
            text = operation.text.trim(),
            evidenceTurnKeys = operation.evidenceTurnKeys.sorted(),
            canonicalKey = normalizedCanonicalKey,
            scope = scope,
            evidenceAt = evidenceAt,
            recallState = recallState,
            memoryClass = normalizedMemoryClass,
            reason = operation.reason.trim()
        )
    }

    private fun String.toMemorySource(): String = when (this) {
        MemoryEvidenceStrength.USER_EXPLICIT -> MemorySource.EXPLICIT_USER_STATEMENT
        MemoryEvidenceStrength.USER_CONFIRMED -> MemorySource.USER_CONFIRMED
        else -> MemorySource.ASSISTANT_INFERRED
    }

    private fun hasCrossDayEvidence(
        evidenceTurnKeys: List<String>,
        turnsByKey: Map<String, MemoryCompletedTurnSnapshot>
    ): Boolean = evidenceTurnKeys.size >= MIN_INFERRED_LONG_TERM_EVIDENCE &&
        evidenceTurnKeys
            .map { turnKey ->
                Instant.ofEpochSecond(turnsByKey.getValue(turnKey).completedAt)
                    .atZone(clock.zone)
                    .toLocalDate()
            }
            .distinct()
            .size >= MIN_DISTINCT_OBSERVATION_DAYS

    private fun validateWriteText(text: String) {
        val normalized = text.trim()
        check(normalized.isNotBlank()) { "text_blank" }
        check(normalized.length <= MemoryControlledOperationPolicy.MAX_MEMORY_TEXT_CHARS) { "text_too_long" }
        check(!normalized.startsWith("The user said:", ignoreCase = true)) { "text_is_transcript" }
    }

    private fun renderOperations(
        batchId: String,
        chatId: Int,
        existingMemories: List<MemoryBatchExistingMemory>,
        operations: List<MemoryBatchOperation>
    ): RenderedMemoryBatch {
        val snapshot = memoryFileStore.ensureStore().getOrThrow()
        val filesByPath = memoryFileStore.listMemoryFiles().getOrThrow().associateBy { file ->
            memoryFileStore.relativePath(file).getOrThrow()
        }
        val todayPath = memoryFileStore.relativePath(snapshot.todayMemoryFile).getOrThrow()
        val existingById = existingMemories.associateBy { it.id }
        val dailyEntriesByIdentity = filesByPath
            .filterKeys { sourcePath -> sourcePath.startsWith("${MemoryFilePaths.DAILY_MEMORY_DIRECTORY_NAME}/") }
            .flatMap { (sourcePath, file) ->
                markdownMemoryCodec.parse(memoryFileStore.readMemoryFile(file).getOrThrow()).entries
                    .asSequence()
                    .filter { entry -> entry.validity != MemoryValidity.OBSOLETE }
                    .mapNotNull { entry ->
                        entry.canonicalKey?.let { canonicalKey ->
                            DailyMemoryLocation(
                                identity = MemoryCanonicalIdentityPolicy.normalizeCanonicalKey(canonicalKey, entry.scope) +
                                    "\u0000${entry.scope}",
                                sourcePath = sourcePath,
                                entry = entry
                            )
                        }
                    }
                    .toList()
            }
            .groupBy(DailyMemoryLocation::identity)
            .mapValues { (_, locations) ->
                locations.maxWith(
                    compareBy<DailyMemoryLocation> { location -> location.entry.lastObservedAt }
                        .thenBy(DailyMemoryLocation::sourcePath)
                        .thenBy { location -> location.entry.id }
                )
            }
        val dailyObservationLocations = operations.withIndex()
            .filter { indexedOperation ->
                indexedOperation.value.action == MemoryBatchAction.CREATE &&
                    indexedOperation.value.destination == MemoryBatchDestination.DAILY
            }
            .mapNotNull { indexedOperation ->
                val operation = indexedOperation.value
                val identity = requireNotNull(operation.canonicalKey) + "\u0000${requireNotNull(operation.scope)}"
                dailyEntriesByIdentity[identity]?.let { location -> indexedOperation.index to location }
            }
            .toMap()
        val requiredPaths = buildSet {
            operations.withIndex().forEach { indexedOperation ->
                val operation = indexedOperation.value
                when (operation.action) {
                    MemoryBatchAction.CREATE -> add(
                        dailyObservationLocations[indexedOperation.index]?.sourcePath ?: pathForDestination(
                            operation.destination,
                            todayPath
                        )
                    )
                    MemoryBatchAction.REPLACE,
                    MemoryBatchAction.REMOVE -> operation.targetMemoryId?.let { targetId ->
                        existingById[targetId]?.sourcePath?.let(::add)
                    }
                }
            }
        }
        val originalMarkdown = requiredPaths.associateWith { sourcePath ->
            val file = checkNotNull(filesByPath[sourcePath]) { "Unknown memory source path" }
            memoryFileStore.readMemoryFile(file).getOrThrow()
        }
        val editedMarkdown = originalMarkdown.toMutableMap()
        val renderedAt = now()
        var dailyWriteCount = 0
        var longTermWriteCount = 0
        var longTermRequiresIndexSync = false
        var longTermMaterialMutationCount = 0

        val indexedOperationsByPath = operations
            .withIndex()
            .filter { indexedOperation ->
                indexedOperation.value.action != MemoryBatchAction.IGNORE &&
                    indexedOperation.index !in dailyObservationLocations
            }
            .groupBy { indexedOperation ->
                val operation = indexedOperation.value
                when (operation.action) {
                    MemoryBatchAction.CREATE -> pathForDestination(operation.destination, todayPath)
                    else -> checkNotNull(existingById[operation.targetMemoryId]?.sourcePath)
                }
            }
        indexedOperationsByPath.forEach { (sourcePath, indexedOperations) ->
            if (sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) {
                var currentMarkdown = checkNotNull(originalMarkdown[sourcePath])
                var removeCount = 0
                indexedOperations
                    .filter { indexedOperation -> indexedOperation.value.action == MemoryBatchAction.REMOVE }
                    .sortedBy(IndexedValue<MemoryBatchOperation>::index)
                    .forEach { indexedOperation ->
                        val targetId = checkNotNull(indexedOperation.value.targetMemoryId)
                        currentMarkdown = detachObsoleteSupersessionTargets(
                            markdown = currentMarkdown,
                            targetId = targetId
                        )
                        val removal = markdownMemoryCodec.removeEntriesById(
                            markdown = currentMarkdown,
                            entryIds = setOf(targetId)
                        )
                        check(removal.deletedCount == 1)
                        currentMarkdown = removal.markdown
                        removeCount += 1
                    }
                val mergeResult = canonicalMemoryMergePolicy.merge(
                    baseMarkdown = currentMarkdown,
                    candidates = indexedOperations
                        .filter { indexedOperation ->
                            indexedOperation.value.action in setOf(MemoryBatchAction.CREATE, MemoryBatchAction.REPLACE)
                        }
                        .map { indexedOperation -> indexedOperation.value.toCanonicalCandidate(chatId) },
                    mutationAt = renderedAt
                )
                editedMarkdown[sourcePath] = mergeResult.markdown
                longTermWriteCount += removeCount + if (mergeResult.markdown == currentMarkdown) {
                    0
                } else {
                    mergeResult.acceptedCandidateCount
                }
                longTermRequiresIndexSync = removeCount > 0 || mergeResult.requiresIndexSync
                longTermMaterialMutationCount = removeCount + mergeResult.materialMutationCount
                return@forEach
            }

            val originalExactTextCounts = exactTextCounts(checkNotNull(originalMarkdown[sourcePath]))
            // Destructive edits run first so CREATE can relocate text removed by the same proposal.
            val orderedOperations = indexedOperations.filter { indexedOperation ->
                indexedOperation.value.action != MemoryBatchAction.CREATE
            } + indexedOperations.filter { indexedOperation ->
                indexedOperation.value.action == MemoryBatchAction.CREATE
            }
            orderedOperations.forEach operationLoop@{ indexedOperation ->
                val operationIndex = indexedOperation.index
                val operation = indexedOperation.value
                val currentMarkdown = checkNotNull(editedMarkdown[sourcePath])
                val parsedCurrentEntries = parseEntriesOrThrow(currentMarkdown).entries
                val currentEntries = parsedCurrentEntries.associateBy { it.id }
                val updatedMarkdown = when (operation.action) {
                    MemoryBatchAction.CREATE -> {
                        val normalizedText = normalizeExactMemoryText(operation.text)
                        val originalCount = originalExactTextCounts[normalizedText] ?: 0
                        val currentCount = parsedCurrentEntries.count { entry ->
                            normalizeExactMemoryText(entry.text) == normalizedText
                        }
                        if (currentCount >= maxOf(originalCount, 1)) return@operationLoop
                        val generatedId = generatedEntryId(batchId, operationIndex, operation.destination)
                        check(generatedId !in currentEntries) { "generated_memory_id_conflict" }
                        val entry = operation.toEntry(
                            id = generatedId,
                            chatId = chatId,
                            createdAt = renderedAt
                        )
                        if (operation.destination == MemoryBatchDestination.LONG_TERM) {
                            markdownMemoryCodec.appendLongTermEntries(currentMarkdown, listOf(entry))
                        } else {
                            val append = markdownMemoryCodec.renderDailyAppend(listOf(entry))
                            currentMarkdown.trimEnd() + "\n\n" + append.trim() + "\n"
                        }
                    }
                    MemoryBatchAction.REPLACE -> {
                        val targetId = checkNotNull(operation.targetMemoryId)
                        val existingEntry = checkNotNull(currentEntries[targetId])
                        val replacement = markdownMemoryCodec.replaceEntriesById(
                            currentMarkdown,
                            listOf(
                                existingEntry.copy(
                                    text = operation.text.trim(),
                                    type = operation.type,
                                    sensitivity = operation.sensitivity,
                                    source = operation.source,
                                    updatedAt = renderedAt
                                )
                            )
                        )
                        check(replacement.replacedCount == 1)
                        replacement.markdown
                    }
                    MemoryBatchAction.REMOVE -> {
                        val removal = markdownMemoryCodec.removeEntriesById(
                            currentMarkdown,
                            setOf(checkNotNull(operation.targetMemoryId))
                        )
                        check(removal.deletedCount == 1)
                        removal.markdown
                    }
                    else -> currentMarkdown
                }
                editedMarkdown[sourcePath] = updatedMarkdown
                dailyWriteCount += 1
            }
        }

        dailyObservationLocations
            .toList()
            .groupBy { (_, location) -> location.sourcePath }
            .forEach { (sourcePath, observations) ->
                val updates = observations
                    .groupBy { (_, location) -> location.entry.id }
                    .map { (entryId, matching) ->
                        MarkdownMemoryObservationUpdate(
                            entryId = entryId,
                            lastObservedAt = matching.maxOf { (operationIndex, _) ->
                                requireNotNull(operations[operationIndex].evidenceAt)
                            },
                            evidenceRefs = matching
                                .flatMap { (operationIndex, _) -> operations[operationIndex].evidenceTurnKeys }
                                .distinct()
                                .sorted()
                        )
                    }
                val result = markdownMemoryCodec.updateObservations(
                    markdown = checkNotNull(editedMarkdown[sourcePath]),
                    updates = updates
                )
                editedMarkdown[sourcePath] = result.markdown
                dailyWriteCount += result.updatedCount
            }

        editedMarkdown.forEach { (sourcePath, markdown) ->
            validateNoNewExactTextDuplicates(
                originalMarkdown = checkNotNull(originalMarkdown[sourcePath]),
                renderedMarkdown = markdown
            )
        }
        val changedMarkdown = editedMarkdown.filter { (sourcePath, markdown) -> markdown != originalMarkdown[sourcePath] }
        return RenderedMemoryBatch(
            targets = changedMarkdown.toSortedMap().map { (sourcePath, markdown) ->
                MemoryMutationTarget(
                    sourcePath = sourcePath,
                    baseContent = checkNotNull(originalMarkdown[sourcePath]),
                    targetContent = markdown,
                    targetIndexFingerprint = targetIndexFingerprint.takeIf {
                        sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME && longTermRequiresIndexSync
                    },
                    materialMutationCount = if (sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) {
                        longTermMaterialMutationCount
                    } else {
                        0
                    }
                )
            },
            dailyWriteCount = dailyWriteCount,
            longTermWriteCount = longTermWriteCount
        )
    }

    private fun detachObsoleteSupersessionTargets(
        markdown: String,
        targetId: String
    ): String {
        val dependents = parseEntriesOrThrow(markdown)
            .entries
            .filter { entry ->
                entry.validity == MemoryValidity.OBSOLETE && entry.supersededBy == targetId
            }
        if (dependents.isEmpty()) return markdown

        val replacement = markdownMemoryCodec.replaceEntriesById(
            markdown = markdown,
            replacements = dependents.map { entry -> entry.copy(supersededBy = null) }
        )
        check(replacement.replacedCount == dependents.size)
        return replacement.markdown
    }

    private fun MemoryBatchOperation.toEntry(
        id: String,
        chatId: Int?,
        createdAt: Long,
        section: String? = null
    ): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = text.trim(),
        type = type,
        sensitivity = sensitivity,
        source = source,
        chatId = chatId,
        createdAt = createdAt,
        updatedAt = now(),
        section = section,
        canonicalKey = requireNotNull(canonicalKey),
        scope = requireNotNull(scope),
        lastObservedAt = requireNotNull(evidenceAt),
        recallState = requireNotNull(recallState),
        evidenceRefs = evidenceTurnKeys.sorted(),
        durability = when (memoryClass) {
            MemoryClass.ACTIVE_PROJECT -> MemoryDurability.ACTIVE
            MemoryClass.TEMPORARY_OBSERVATION -> MemoryDurability.EPHEMERAL
            else -> MemoryDurability.DURABLE
        },
        freshness = when (memoryClass) {
            MemoryClass.ACTIVE_PROJECT -> MemoryFreshness.REFRESHABLE
            MemoryClass.TEMPORARY_OBSERVATION -> MemoryFreshness.EXPIRES
            else -> MemoryFreshness.STABLE
        },
        lifecycleState = MemoryLifecycleState.ACTIVE,
        refreshAfterDays = when (memoryClass) {
            MemoryClass.ACTIVE_PROJECT -> 30
            MemoryClass.TEMPORARY_OBSERVATION -> 7
            else -> null
        }
    )

    private fun MemoryBatchOperation.toCanonicalCandidate(chatId: Int): CanonicalMemoryCandidate =
        CanonicalMemoryCandidate(
            targetMemoryId = targetMemoryId,
            chatId = chatId,
            text = text,
            type = type,
            sensitivity = sensitivity,
            source = source,
            canonicalKey = requireNotNull(canonicalKey),
            scope = requireNotNull(scope),
            evidenceAt = requireNotNull(evidenceAt),
            recallState = requireNotNull(recallState),
            evidenceRefs = evidenceTurnKeys
        )

    private fun validateNoNewExactTextDuplicates(
        originalMarkdown: String,
        renderedMarkdown: String
    ) {
        val originalCounts = exactTextCounts(originalMarkdown)
        exactTextCounts(renderedMarkdown).forEach { (normalizedText, renderedCount) ->
            val allowedCount = maxOf(originalCounts[normalizedText] ?: 0, 1)
            check(renderedCount <= allowedCount) { "duplicate_exact_memory_text" }
        }
    }

    private fun exactTextCounts(markdown: String): Map<String, Int> = parseEntriesOrThrow(markdown)
        .entries
        .groupingBy { entry -> normalizeExactMemoryText(entry.text) }
        .eachCount()

    private fun parseEntriesOrThrow(markdown: String): MarkdownMemoryParseResult = markdownMemoryCodec
        .parse(markdown)
        .also { parsed ->
            check(parsed.skippedEntries.isEmpty()) { "unsafe_memory_metadata" }
        }

    private fun pathForDestination(destination: String, todayPath: String): String =
        if (destination == MemoryBatchDestination.LONG_TERM) MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME else todayPath

    private fun proposalPathForDestination(destination: String): String = pathForDestination(
        destination = destination,
        todayPath = "${MemoryFilePaths.DAILY_MEMORY_DIRECTORY_NAME}/${LocalDate.now(clock)}.md"
    )

    private fun destinationFor(sourcePath: String): String =
        if (sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) {
            MemoryBatchDestination.LONG_TERM
        } else {
            MemoryBatchDestination.DAILY
        }

    private fun generatedEntryId(batchId: String, operationIndex: Int, destination: String): String {
        val prefix = if (destination == MemoryBatchDestination.LONG_TERM) "mem" else "day"
        return "${prefix}_${sha256("$batchId|$operationIndex|$destination").take(24)}"
    }

    private fun PlatformV2?.activityData(
        inputCount: Int? = null,
        operationCount: Int? = null,
        errorCode: String? = null,
        errorDetail: String? = null
    ): MemoryActivityRunData = this?.toMemoryActivityData(
        inputCount = inputCount,
        operationCount = operationCount,
        errorCode = errorCode,
        errorDetail = errorDetail
    ) ?: MemoryActivityRunData(
        inputCount = inputCount,
        operationCount = operationCount,
        errorCode = errorCode,
        errorDetail = errorDetail
    )

    private suspend fun retryable(
        job: MemoryMaintenanceJob,
        request: MemoryBatchConsolidationRequest,
        reason: String,
        startedAt: Long,
        proposalCount: Int?,
        rawResponseJson: String? = null
    ): MemoryBatchProcessResult {
        recordFailureResponse(job, reason, rawResponseJson)
        val failedJob = maintenanceScheduler.markFailedRetryable(job, reason)
        val status = if (failedJob.status == MemoryMaintenanceJobStatus.FAILED_TERMINAL) {
            MemoryBatchProcessResult.STATUS_TERMINAL
        } else {
            MemoryBatchProcessResult.STATUS_RETRYABLE
        }
        logBatch(job, request, status, proposalCount, System.currentTimeMillis() - startedAt)
        turnBatchScheduler.scheduleNextWake()
        return MemoryBatchProcessResult(status, job.jobId, reason = reason)
    }

    private fun recordFailureResponse(
        job: MemoryMaintenanceJob,
        reason: String,
        rawResponseJson: String?
    ) {
        rawResponseJson?.let { response ->
            runCatching { failureResponseStore.record(job, reason, response) }
        }
    }

    private suspend fun terminal(
        job: MemoryMaintenanceJob,
        reason: String,
        dismiss: Boolean = false
    ): MemoryBatchProcessResult {
        if (dismiss) {
            maintenanceScheduler.markDismissed(job)
        } else {
            maintenanceScheduler.markFailedTerminal(job, reason)
        }
        return MemoryBatchProcessResult(MemoryBatchProcessResult.STATUS_TERMINAL, job.jobId, reason = reason)
    }

    private fun logBatch(
        job: MemoryMaintenanceJob,
        request: MemoryBatchConsolidationRequest,
        status: String,
        proposalCount: Int?,
        elapsedMs: Long?
    ) {
        runCatching {
            Log.i(
                TAG,
                "Memory batch id=${request.batchId}, jobId=${job.jobId}, trigger=${request.triggerReason}, " +
                    "turns=${request.turns.size}, attempt=${job.attempts}, proposals=${proposalCount ?: -1}, " +
                    "status=$status, elapsedMs=${elapsedMs ?: -1}"
            )
        }
    }

    private fun rethrowCommitInterruption(throwable: Throwable) {
        if (
            throwable is CancellationException ||
            throwable is MemoryMaintenanceLeaseLostException ||
            throwable is MemoryBatchCommitInterruptedException
        ) {
            throw throwable
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun now(): Long = clock.instant().epochSecond

    companion object {
        private const val TAG = "MemoryBatch"
        private const val MAX_RETRIEVAL_QUERY_CHARS = 8_000
        private const val MAX_LEGACY_MESSAGE_CHARS = 12_000
        private const val MAX_LEGACY_TITLE_CHARS = 200
        private const val LEGACY_PLATFORM_UID = "legacy-memory-job"
        private const val MAX_EXISTING_MEMORIES = 24
        private const val MAX_EXISTING_CANDIDATES = 200
        private const val MAX_EXISTING_MEMORY_TOKEN_BUDGET = 2_400
        private const val MIN_INFERRED_LONG_TERM_EVIDENCE = 2
        private const val MIN_DISTINCT_OBSERVATION_DAYS = 2
        private val PROPOSAL_REF_PATTERN = Regex("P\\d{2}")
        private val VALID_TRIGGER_REASONS = setOf(
            MemoryTurnBatchTriggerReason.THRESHOLD,
            MemoryTurnBatchTriggerReason.IDLE,
            MemoryTurnBatchTriggerReason.CONTEXT_COMPACTION,
            MemoryTurnBatchTriggerReason.MANUAL_RETRY,
            MemoryTurnBatchTriggerReason.EXPLICIT_REQUEST
        )
        private val VALID_DESTINATIONS = setOf(MemoryBatchDestination.DAILY, MemoryBatchDestination.LONG_TERM)
        private val ACTIVE_RECALL_STATES = setOf(MemoryRecallState.CORE, MemoryRecallState.QUERY)
        private val VALID_ACTIONS = setOf(
            MemoryBatchAction.CREATE,
            MemoryBatchAction.REPLACE,
            MemoryBatchAction.REMOVE,
            MemoryBatchAction.IGNORE
        )
    }
}

private data class LegacyMemoryJobContent(
    val chatId: Int,
    val chatTitle: String,
    val platformUid: String,
    val messages: List<MemoryConversationMessage>,
    val createdAt: Long,
    val triggerReason: String
)

private data class RenderedMemoryBatch(
    val targets: List<MemoryMutationTarget>,
    val dailyWriteCount: Int,
    val longTermWriteCount: Int
)

private data class DailyMemoryLocation(
    val identity: String,
    val sourcePath: String,
    val entry: MarkdownMemoryEntry
)

private data class MemoryBatchOperationValidationResult(
    val operations: List<MemoryBatchOperation>,
    val diagnostics: List<MemoryOperationDiagnostic>
)

data class MemoryOperationDiagnostic(
    val index: Int,
    val code: String
) {
    init {
        require(index >= 0)
        require(SAFE_OPERATION_DIAGNOSTIC_CODE.matches(code))
    }
}

internal fun Throwable.safeValidationCode(): String = message
    ?.takeIf { SAFE_OPERATION_DIAGNOSTIC_CODE.matches(it) }
    ?: "invalid_operation"

internal fun List<MemoryOperationDiagnostic>.toActivityDetail(): String? =
    take(MAX_OPERATION_DIAGNOSTICS).takeIf(List<MemoryOperationDiagnostic>::isNotEmpty)?.joinToString(",") { diagnostic ->
        "${diagnostic.index}:${diagnostic.code}"
    }

private const val MAX_OPERATION_DIAGNOSTICS = 16
private val SAFE_OPERATION_DIAGNOSTIC_CODE = Regex("[a-z0-9][a-z0-9._:-]{0,99}")
