package cn.nabr.chatwithchat.data.memory

import android.util.Log
import cn.nabr.chatwithchat.data.database.dao.MemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexDefaults
import cn.nabr.chatwithchat.data.repository.SettingRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class MemoryBatchConsolidationService(
    private val turnBatchDao: MemoryTurnBatchDao,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val turnBatchScheduler: MemoryTurnBatchScheduler,
    private val settingRepository: SettingRepository,
    private val modelResolver: MemoryModelResolver,
    private val memoryIntelligence: MemoryIntelligence,
    private val memoryFileStore: MemoryFileStore,
    private val markdownMemoryCodec: MarkdownMemoryCodec,
    private val memoryMaintenanceCorpusReader: MemoryMaintenanceCorpusReader,
    private val memoryMutationCoordinator: MemoryMutationCoordinator,
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
        val request = MemoryBatchConsolidationRequest(
            batchId = payload.batchId,
            chatId = payload.chatId,
            chatTitle = turns.firstNotNullOfOrNull { turn -> turn.chatTitle.takeIf(String::isNotBlank) }.orEmpty(),
            triggerReason = payload.triggerReason,
            turns = turns,
            existingMemories = existingMemories
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
        val request = requestWithoutMemories.copy(
            existingMemories = if (existingMutation == null) {
                retrieveExistingMemories(requestWithoutMemories.turns)
            } else {
                emptyList()
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
        var activityPlatform: PlatformV2? = null
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
            val proposal = memoryIntelligence.consolidateMemoryBatch(request, resolvedPlatform, activityRunId)
            runningJob = maintenanceScheduler.renewClaimedLease(runningJob)
            if (proposal == null) {
                activityLogger.finishRunSafely(
                    activityRunId,
                    MemoryActivityStatus.FAILED,
                    resolvedPlatform.toMemoryActivityData(
                        inputCount = request.turns.size,
                        errorCode = "consolidation_unavailable_or_invalid"
                    ),
                    expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION
                )
                return retryable(runningJob, request, "consolidation_unavailable_or_invalid", startedAt, null)
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
            val validatedOperations = runCatching { validateOperations(request, proposal.operations) }
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
                        proposal.operations.size
                    )
                }
            val renderedBatch = runCatching {
                renderOperations(
                    batchId = request.batchId,
                    chatId = request.chatId,
                    existingMemories = request.existingMemories,
                    operations = validatedOperations
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
                    proposal.operations.size
                )
            }
            operationCount = proposal.operations.size
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
                    proposal.operations.size
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
                    operationCount.takeIf { it > 0 }
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
                        operationCount.takeIf { it > 0 }
                    )
                }
                if (!conflictBatchCompleted) {
                    return retryable(
                        runningJob,
                        request,
                        "conflicted_batch_completion_failed",
                        startedAt,
                        operationCount.takeIf { it > 0 }
                    )
                }
                commitObserver.afterBatchCompletion(runningJob.jobId)
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
                operationCount.takeIf { it > 0 }
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
                operationCount.takeIf { it > 0 }
            )
        }
        commitObserver.afterBatchCompletion(runningJob.jobId)
        maintenanceScheduler.markSucceeded(runningJob)
        commitObserver.afterSourceJobCompletion(runningJob.jobId)
        preparedMutation?.let { mutation ->
            memoryMutationCoordinator.acknowledgeSemanticCompletion(mutation.group.groupId)
        }
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
                operationCount = operationCount
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
        check(payload.batchId == job.idempotencyKey)
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
        return results
            .filter { result ->
                !result.entryId.isNullOrBlank() &&
                    !result.type.isNullOrBlank() &&
                    !result.sensitivity.isNullOrBlank() &&
                    !result.source.isNullOrBlank()
            }
            .groupBy { it.entryId!! }
            .filterValues { sameIdResults -> sameIdResults.map { it.sourcePath }.distinct().size == 1 }
            .map { (entryId, sameIdResults) ->
                val result = sameIdResults.maxBy { it.fusedScore }
                val entry = entriesBySourceAndId[result.sourcePath to entryId]
                MemoryBatchExistingMemory(
                    id = entryId,
                    sourcePath = result.sourcePath,
                    text = entry?.text ?: result.text,
                    type = entry?.type ?: result.type!!,
                    sensitivity = entry?.sensitivity ?: result.sensitivity!!,
                    source = entry?.source ?: result.source!!,
                    updatedAt = entry?.updatedAt ?: result.updatedAt,
                    createdAt = entry?.createdAt ?: 0L,
                    canonicalKey = entry?.canonicalKey,
                    scope = entry?.scope ?: MemoryScope.GENERAL,
                    lastObservedAt = entry?.lastObservedAt ?: result.updatedAt,
                    validity = entry?.validity ?: MemoryValidity.CURRENT,
                    supersededBy = entry?.supersededBy,
                    recallState = entry?.recallState ?: MemoryRecallState.QUERY,
                    evidenceRefs = entry?.evidenceRefs.orEmpty()
                )
            }
            .sortedByDescending { it.updatedAt }
            .take(MAX_EXISTING_MEMORIES)
    }

    private fun validateOperations(
        request: MemoryBatchConsolidationRequest,
        operations: List<MemoryBatchOperation>
    ): List<MemoryBatchOperation> {
        check(operations.size <= MemoryControlledOperationPolicy.MAX_OPERATIONS)
        val existingById = request.existingMemories.associateBy { it.id }
        val turnsByKey = request.turns.associateBy { it.turnKey }
        val targetedIds = mutableSetOf<String>()
        val normalizedWriteTextsByTarget = mutableMapOf<String, MutableSet<String>>()

        val validated = operations.map { operation ->
            check(operation.destination in VALID_DESTINATIONS)
            check(operation.action in VALID_ACTIONS)
            check(operation.type in MemoryControlledOperationPolicy.validTypes)
            check(operation.sensitivity in MemoryControlledOperationPolicy.validSensitivities)
            check(operation.source in MemoryControlledOperationPolicy.validSources)
            check(operation.reason.length <= MemoryControlledOperationPolicy.MAX_REASON_CHARS)
            check(operation.evidenceTurnKeys.size <= MemoryControlledOperationPolicy.MAX_EVIDENCE_KEYS)
            check(operation.evidenceTurnKeys.distinct().size == operation.evidenceTurnKeys.size)
            check(operation.evidenceTurnKeys.all(turnsByKey::containsKey))

            when (operation.action) {
                MemoryBatchAction.IGNORE -> {
                    check(operation.targetMemoryId.isNullOrBlank())
                    check(operation.text.isBlank())
                    check(operation.canonicalKey == null)
                    check(operation.scope == null)
                    check(operation.evidenceAt == null)
                    check(operation.recallState == null)
                    operation.copy(reason = operation.reason.trim())
                }
                MemoryBatchAction.CREATE -> {
                    check(operation.targetMemoryId.isNullOrBlank())
                    validateWriteText(operation.text)
                    if (operation.destination == MemoryBatchDestination.DAILY) {
                        registerExactWrite(
                            normalizedWriteTextsByTarget = normalizedWriteTextsByTarget,
                            sourcePath = proposalPathForDestination(operation.destination),
                            text = operation.text
                        )
                    }
                    check(operation.evidenceTurnKeys.isNotEmpty())
                    check(
                        operation.destination != MemoryBatchDestination.LONG_TERM ||
                            operation.source != MemorySource.ASSISTANT_INFERRED ||
                            operation.evidenceTurnKeys.size >= MIN_INFERRED_LONG_TERM_EVIDENCE
                    ) { "assistant_inferred_long_term_create_requires_repeated_evidence" }
                    validatedCanonicalWrite(operation, null, turnsByKey)
                }
                MemoryBatchAction.REPLACE -> {
                    val targetId = checkNotNull(operation.targetMemoryId?.takeIf { it.isNotBlank() })
                    val existing = checkNotNull(existingById[targetId])
                    check(targetedIds.add(targetId))
                    check(operation.destination == destinationFor(existing.sourcePath))
                    validateWriteText(operation.text)
                    if (existing.sourcePath != MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) {
                        registerExactWrite(
                            normalizedWriteTextsByTarget = normalizedWriteTextsByTarget,
                            sourcePath = existing.sourcePath,
                            text = operation.text
                        )
                    }
                    check(operation.evidenceTurnKeys.isNotEmpty())
                    validatedCanonicalWrite(operation, existing, turnsByKey).copy(targetMemoryId = targetId)
                }
                MemoryBatchAction.REMOVE -> {
                    val targetId = checkNotNull(operation.targetMemoryId?.takeIf { it.isNotBlank() })
                    val existing = checkNotNull(existingById[targetId])
                    check(targetedIds.add(targetId))
                    check(operation.destination == destinationFor(existing.sourcePath))
                    check(operation.text.isBlank())
                    check(operation.evidenceTurnKeys.isNotEmpty())
                    check(operation.canonicalKey == null)
                    check(operation.scope == null)
                    check(operation.evidenceAt == null)
                    check(operation.recallState == null)
                    operation.copy(
                        targetMemoryId = targetId,
                        evidenceTurnKeys = operation.evidenceTurnKeys.sorted(),
                        reason = operation.reason.trim()
                    )
                }
                else -> error("Unsupported memory batch action")
            }
        }

        validated
            .filter { operation -> operation.action in setOf(MemoryBatchAction.CREATE, MemoryBatchAction.REPLACE) }
            .groupBy { operation -> normalizeExactMemoryText(operation.text) }
            .values
            .forEach { sameTextOperations ->
                check(sameTextOperations.map { operation -> operation.destination }.distinct().size == 1) {
                    "cross_destination_exact_memory_text"
                }
            }

        return validated
    }

    private fun validatedCanonicalWrite(
        operation: MemoryBatchOperation,
        target: MemoryBatchExistingMemory?,
        turnsByKey: Map<String, MemoryCompletedTurnSnapshot>
    ): MemoryBatchOperation {
        val canonicalKey = checkNotNull(operation.canonicalKey)
        val scope = checkNotNull(operation.scope)
        val recallState = checkNotNull(operation.recallState)
        val evidenceAt = operation.evidenceTurnKeys.maxOf { key -> turnsByKey.getValue(key).completedAt }
        check(MarkdownMemoryMetadataPolicy.isCanonicalKey(canonicalKey))
        check(MarkdownMemoryMetadataPolicy.isScope(scope))
        check(operation.evidenceAt == evidenceAt)
        check(evidenceAt >= 0L)
        check(recallState in ACTIVE_RECALL_STATES)
        target?.let { existing ->
            check(existing.type == operation.type)
            check(existing.canonicalKey == null || existing.canonicalKey == canonicalKey)
            check(existing.canonicalKey == null || existing.scope == scope)
        }
        return operation.copy(
            text = operation.text.trim(),
            evidenceTurnKeys = operation.evidenceTurnKeys.sorted(),
            canonicalKey = canonicalKey,
            scope = scope,
            evidenceAt = evidenceAt,
            recallState = recallState,
            reason = operation.reason.trim()
        )
    }

    private fun registerExactWrite(
        normalizedWriteTextsByTarget: MutableMap<String, MutableSet<String>>,
        sourcePath: String,
        text: String
    ) {
        check(
            normalizedWriteTextsByTarget
                .getOrPut(sourcePath) { mutableSetOf() }
                .add(normalizeExactMemoryText(text))
        ) { "duplicate_exact_memory_text" }
    }

    private fun validateWriteText(text: String) {
        val normalized = text.trim()
        check(normalized.isNotBlank())
        check(normalized.length <= MemoryControlledOperationPolicy.MAX_MEMORY_TEXT_CHARS)
        check(!normalized.startsWith("The user said:", ignoreCase = true))
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
        val requiredPaths = buildSet {
            operations.forEach { operation ->
                when (operation.action) {
                    MemoryBatchAction.CREATE -> add(pathForDestination(operation.destination, todayPath))
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
            .filter { indexedOperation -> indexedOperation.value.action != MemoryBatchAction.IGNORE }
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
                        val removal = markdownMemoryCodec.removeEntriesById(
                            markdown = currentMarkdown,
                            entryIds = setOf(checkNotNull(indexedOperation.value.targetMemoryId))
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
        evidenceRefs = evidenceTurnKeys.sorted()
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
        errorCode: String? = null
    ): MemoryActivityRunData = this?.toMemoryActivityData(
        inputCount = inputCount,
        operationCount = operationCount,
        errorCode = errorCode
    ) ?: MemoryActivityRunData(
        inputCount = inputCount,
        operationCount = operationCount,
        errorCode = errorCode
    )

    private suspend fun retryable(
        job: MemoryMaintenanceJob,
        request: MemoryBatchConsolidationRequest,
        reason: String,
        startedAt: Long,
        proposalCount: Int?
    ): MemoryBatchProcessResult {
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
        private val VALID_TRIGGER_REASONS = setOf(
            MemoryTurnBatchTriggerReason.THRESHOLD,
            MemoryTurnBatchTriggerReason.IDLE,
            MemoryTurnBatchTriggerReason.CONTEXT_COMPACTION,
            MemoryTurnBatchTriggerReason.MANUAL_RETRY
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
