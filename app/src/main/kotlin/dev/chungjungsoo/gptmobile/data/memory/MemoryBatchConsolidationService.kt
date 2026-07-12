package dev.chungjungsoo.gptmobile.data.memory

import android.util.Log
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexDefaults
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.security.MessageDigest
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class MemoryBatchConsolidationService(
    private val turnBatchDao: MemoryTurnBatchDao,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val turnBatchScheduler: MemoryTurnBatchScheduler,
    private val settingRepository: SettingRepository,
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
    suspend fun process(job: MemoryMaintenanceJob): MemoryBatchProcessResult {
        terminalResultOrNull(job)?.let { return it }
        val payload = decodePayload(job) ?: return terminal(job, "invalid_batch_payload")
        val turns = payload.turns.map { turn -> json.decodeFromString<MemoryCompletedTurnSnapshot>(turn.payloadJson) }
        val existingMutation = memoryMutationCoordinator.findBySemanticJobId(job.jobId)
        val batchAlreadyComplete = existingMutation == null && isClaimedBatchComplete(job.jobId, payload)
        if (existingMutation == null && !batchAlreadyComplete && !validateClaimedTurns(job, payload)) {
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
            complete = { turnBatchDao.completeClaimedBatch(job.jobId, now()) },
            isComplete = { isClaimedBatchComplete(job.jobId, payload) }
        )
    }

    suspend fun processLegacy(job: MemoryMaintenanceJob): MemoryBatchProcessResult {
        terminalResultOrNull(job)?.let { return it }
        val requestWithoutMemories = decodeLegacyRequest(job) ?: return terminal(job, "invalid_legacy_memory_payload")
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
            complete = { true },
            isComplete = { true }
        )
    }

    private suspend fun execute(
        job: MemoryMaintenanceJob,
        request: MemoryBatchConsolidationRequest,
        existingMutation: MemoryPreparedMutation?,
        batchAlreadyComplete: Boolean,
        complete: suspend () -> Boolean,
        isComplete: suspend () -> Boolean
    ): MemoryBatchProcessResult {
        if (existingMutation == null && !batchAlreadyComplete && !settingRepository.fetchMemoryEnabled()) {
            turnBatchScheduler.onMemoryEnabledChanged(false)
            return terminal(job, "memory_disabled", dismiss = true)
        }

        check(job.status == MemoryMaintenanceJobStatus.RUNNING) { "memory_job_not_claimed" }
        check(!job.leaseOwner.isNullOrBlank()) { "memory_job_missing_lease" }
        val preferredPlatform = preferredMemoryPlatform()
        val runningJob = job
        maintenanceScheduler.renewClaimedLease(runningJob)
        val startedAt = System.currentTimeMillis()
        logBatch(runningJob, request, "started", proposalCount = null, elapsedMs = null)

        var operationCount = 0
        var dailyWriteCount = 0
        var longTermWriteCount = 0
        val organizationLogId: String
        val preparedMutation: MemoryPreparedMutation?

        if (existingMutation != null || batchAlreadyComplete) {
            organizationLogId = startOrganizationActivity(runningJob, request, preferredPlatform)
            preparedMutation = existingMutation
        } else {
            val proposal = memoryIntelligence.consolidateMemoryBatch(request, preferredPlatform)
            maintenanceScheduler.renewClaimedLease(runningJob)
            if (proposal == null) {
                val failedLogId = startOrganizationActivity(runningJob, request, preferredPlatform)
                finishOrganizationActivity(
                    failedLogId,
                    MemoryActivityStatus.FAILED,
                    "记忆生成失败，未能开始整理"
                )
                return retryable(runningJob, request, "consolidation_unavailable_or_invalid", startedAt, null)
            }
            organizationLogId = startOrganizationActivity(runningJob, request, preferredPlatform)
            val validatedOperations = runCatching { validateOperations(request, proposal.operations) }
                .getOrElse { throwable ->
                    finishOrganizationActivity(
                        organizationLogId,
                        MemoryActivityStatus.FAILED,
                        "记忆整理方案校验失败：${throwable.message}",
                        proposal.operations.size
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
                finishOrganizationActivity(
                    organizationLogId,
                    MemoryActivityStatus.FAILED,
                    "渲染记忆失败：${throwable.message}",
                    proposal.operations.size
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
                maintenanceScheduler.renewClaimedLease(runningJob)
                memoryMutationCoordinator.prepare(
                    semanticJobId = runningJob.jobId,
                    semanticBatchId = request.batchId,
                    targets = renderedBatch.targets
                ).also { mutation -> commitObserver.afterPrepared(mutation) }
            }.getOrElse { throwable ->
                rethrowCommitInterruption(throwable)
                finishOrganizationActivity(
                    organizationLogId,
                    MemoryActivityStatus.FAILED,
                    "准备记忆提交失败：${throwable.message}",
                    proposal.operations.size
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
                maintenanceScheduler.renewClaimedLease(runningJob)
                memoryMutationCoordinator.reconcile(preparedMutation).also { result ->
                    if (result is MemoryMutationCommitResult.CanonicalCommitted) {
                        commitObserver.afterCanonicalFileCommit(result.mutation)
                    }
                }
            }.getOrElse { throwable ->
                rethrowCommitInterruption(throwable)
                finishOrganizationActivity(
                    organizationLogId,
                    MemoryActivityStatus.FAILED,
                    "提交记忆失败：${throwable.message}",
                    operationCount.takeIf { it > 0 }
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
                finishOrganizationActivity(
                    organizationLogId,
                    MemoryActivityStatus.FAILED,
                    "记忆文件发生并发冲突：${commitResult.sourcePath}",
                    operationCount.takeIf { it > 0 }
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
                return terminal(runningJob, "memory_mutation_conflict:${commitResult.sourcePath}")
            }
        }

        maintenanceScheduler.renewClaimedLease(runningJob)
        val batchCompleted = runCatching { complete() || isComplete() }.getOrElse { throwable ->
            rethrowCommitInterruption(throwable)
            finishOrganizationActivity(
                organizationLogId,
                MemoryActivityStatus.FAILED,
                "记忆批次完成状态写入失败，提交内容保持不变",
                operationCount.takeIf { it > 0 }
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
            finishOrganizationActivity(
                organizationLogId,
                MemoryActivityStatus.FAILED,
                "记忆批次完成状态写入失败，提交内容保持不变",
                operationCount.takeIf { it > 0 }
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
        preparedMutation?.let { mutation ->
            memoryMutationCoordinator.acknowledgeSemanticCompletion(mutation.group.groupId)
        }
        maintenanceScheduler.markSucceeded(runningJob)
        turnBatchScheduler.repairAndSchedule()
        finishOrganizationActivity(
            organizationLogId,
            MemoryActivityStatus.SUCCEEDED,
            "长期记忆 $longTermWriteCount 条，每日记忆 $dailyWriteCount 条",
            operationCount.takeIf { it > 0 }
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
                MemoryBatchExistingMemory(
                    id = entryId,
                    sourcePath = result.sourcePath,
                    text = result.text,
                    type = result.type!!,
                    sensitivity = result.sensitivity!!,
                    source = result.source!!,
                    updatedAt = result.updatedAt
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
        val turnKeys = request.turns.map { it.turnKey }.toSet()
        val targetedIds = mutableSetOf<String>()

        operations.forEach { operation ->
            check(operation.destination in VALID_DESTINATIONS)
            check(operation.action in VALID_ACTIONS)
            check(operation.type in MemoryControlledOperationPolicy.validTypes)
            check(operation.sensitivity in MemoryControlledOperationPolicy.validSensitivities)
            check(operation.source in MemoryControlledOperationPolicy.validSources)
            check(operation.evidenceTurnKeys.all { it in turnKeys })

            when (operation.action) {
                MemoryBatchAction.IGNORE -> {
                    check(operation.targetMemoryId.isNullOrBlank())
                    check(operation.text.isBlank())
                }
                MemoryBatchAction.CREATE -> {
                    check(operation.targetMemoryId.isNullOrBlank())
                    validateWriteText(operation.text)
                    check(operation.evidenceTurnKeys.isNotEmpty())
                }
                MemoryBatchAction.REPLACE -> {
                    val targetId = checkNotNull(operation.targetMemoryId?.takeIf { it.isNotBlank() })
                    val existing = checkNotNull(existingById[targetId])
                    check(targetedIds.add(targetId))
                    check(operation.destination == destinationFor(existing.sourcePath))
                    validateWriteText(operation.text)
                    check(operation.evidenceTurnKeys.isNotEmpty())
                }
                MemoryBatchAction.REMOVE -> {
                    val targetId = checkNotNull(operation.targetMemoryId?.takeIf { it.isNotBlank() })
                    val existing = checkNotNull(existingById[targetId])
                    check(targetedIds.add(targetId))
                    check(operation.destination == destinationFor(existing.sourcePath))
                    check(operation.text.isBlank())
                    check(operation.evidenceTurnKeys.isNotEmpty())
                }
            }
        }

        operations
            .filter { it.action in setOf(MemoryBatchAction.CREATE, MemoryBatchAction.REPLACE) }
            .groupBy { normalizeMemoryText(it.text) }
            .values
            .forEach { sameTextOperations ->
                check(sameTextOperations.map { it.destination }.distinct().size == 1)
            }
        return operations
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
        var dailyWriteCount = 0
        var longTermWriteCount = 0

        operations.forEachIndexed { operationIndex, operation ->
            if (operation.action == MemoryBatchAction.IGNORE) return@forEachIndexed
            val sourcePath = when (operation.action) {
                MemoryBatchAction.CREATE -> pathForDestination(operation.destination, todayPath)
                else -> checkNotNull(existingById[operation.targetMemoryId]?.sourcePath)
            }
            val currentMarkdown = checkNotNull(editedMarkdown[sourcePath])
            val currentEntries = markdownMemoryCodec.parse(currentMarkdown).entries.associateBy { it.id }
            val updatedMarkdown = when (operation.action) {
                MemoryBatchAction.CREATE -> {
                    val generatedId = generatedEntryId(batchId, operationIndex, operation.destination)
                    if (generatedId in currentEntries) return@forEachIndexed
                    val entry = operation.toEntry(
                        id = generatedId,
                        chatId = chatId,
                        createdAt = now()
                    )
                    val append = if (operation.destination == MemoryBatchDestination.LONG_TERM) {
                        markdownMemoryCodec.renderLongTermAppend(listOf(entry))
                    } else {
                        markdownMemoryCodec.renderDailyAppend(listOf(entry))
                    }
                    currentMarkdown.trimEnd() + "\n\n" + append.trim() + "\n"
                }
                MemoryBatchAction.REPLACE -> {
                    val targetId = checkNotNull(operation.targetMemoryId)
                    val existingEntry = checkNotNull(currentEntries[targetId])
                    val replacement = markdownMemoryCodec.replaceEntriesById(
                        currentMarkdown,
                        listOf(
                            operation.toEntry(
                                id = targetId,
                                chatId = existingEntry.chatId,
                                createdAt = existingEntry.createdAt,
                                section = existingEntry.section
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
            if (operation.destination == MemoryBatchDestination.LONG_TERM) {
                longTermWriteCount += 1
            } else {
                dailyWriteCount += 1
            }
        }

        val changedMarkdown = editedMarkdown.filter { (sourcePath, markdown) -> markdown != originalMarkdown[sourcePath] }
        return RenderedMemoryBatch(
            targets = changedMarkdown.toSortedMap().map { (sourcePath, markdown) ->
                MemoryMutationTarget(
                    sourcePath = sourcePath,
                    baseContent = checkNotNull(originalMarkdown[sourcePath]),
                    targetContent = markdown,
                    targetIndexFingerprint = targetIndexFingerprint.takeIf {
                        sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
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
        section = section
    )

    private fun pathForDestination(destination: String, todayPath: String): String =
        if (destination == MemoryBatchDestination.LONG_TERM) MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME else todayPath

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

    private suspend fun preferredMemoryPlatform(): PlatformV2? = settingRepository.fetchPlatformV2s()
        .firstOrNull { platform -> platform.enabled && platform.model.isNotBlank() }

    private suspend fun startOrganizationActivity(
        job: MemoryMaintenanceJob,
        request: MemoryBatchConsolidationRequest,
        platform: PlatformV2?
    ): String = runCatching {
        activityLogger.start(
            batchId = request.batchId,
            category = MemoryActivityCategory.MEMORY_ORGANIZATION,
            platformName = platform?.name,
            modelName = platform?.model,
            attempt = job.attempts,
            turnCount = request.turns.size
        )
    }.getOrDefault("")

    private suspend fun finishOrganizationActivity(
        logId: String,
        status: String,
        detail: String? = null,
        operationCount: Int? = null
    ) {
        runCatching { activityLogger.finish(logId, status, detail, operationCount) }
    }

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

    private fun normalizeMemoryText(text: String): String = text
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")

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
        private val VALID_TRIGGER_REASONS = setOf(
            MemoryTurnBatchTriggerReason.THRESHOLD,
            MemoryTurnBatchTriggerReason.IDLE,
            MemoryTurnBatchTriggerReason.CONTEXT_COMPACTION,
            MemoryTurnBatchTriggerReason.MANUAL_RETRY
        )
        private val VALID_DESTINATIONS = setOf(MemoryBatchDestination.DAILY, MemoryBatchDestination.LONG_TERM)
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
