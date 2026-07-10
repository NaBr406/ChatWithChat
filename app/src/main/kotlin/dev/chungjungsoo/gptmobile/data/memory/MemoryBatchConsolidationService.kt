package dev.chungjungsoo.gptmobile.data.memory

import android.util.Log
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.security.MessageDigest
import java.time.Clock
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
    private val memoryRetriever: MemoryRetriever,
    private val memoryIndexRebuilder: MemoryIndexRebuilder,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
    }
) {
    suspend fun process(job: MemoryMaintenanceJob): MemoryBatchProcessResult {
        if (job.status == MemoryMaintenanceJobStatus.SUCCEEDED) {
            return MemoryBatchProcessResult(MemoryBatchProcessResult.STATUS_DUPLICATE, job.jobId)
        }
        if (job.status == MemoryMaintenanceJobStatus.FAILED_TERMINAL || job.status == MemoryMaintenanceJobStatus.DISMISSED) {
            return MemoryBatchProcessResult(
                status = MemoryBatchProcessResult.STATUS_TERMINAL,
                jobId = job.jobId,
                reason = "job_${job.status}"
            )
        }
        val payload = decodeAndValidatePayload(job) ?: return terminal(job, "invalid_batch_payload")
        if (!settingRepository.fetchMemoryEnabled()) {
            turnBatchScheduler.onMemoryEnabledChanged(false)
            return terminal(job, "memory_disabled", dismiss = true)
        }

        val existingMemories = retrieveExistingMemories(payload)
        val request = MemoryBatchConsolidationRequest(
            batchId = payload.batchId,
            chatId = payload.chatId,
            chatTitle = payload.turns.firstNotNullOfOrNull { turn ->
                runCatching { json.decodeFromString<MemoryCompletedTurnSnapshot>(turn.payloadJson).chatTitle }.getOrNull()
            }.orEmpty(),
            triggerReason = payload.triggerReason,
            turns = payload.turns.map { turn -> json.decodeFromString(turn.payloadJson) },
            existingMemories = existingMemories
        )
        val preferredPlatform = preferredMemoryPlatform()
        val runningJob = maintenanceScheduler.markRunning(job)
        val startedAt = System.currentTimeMillis()
        logBatch(runningJob, payload, "started", proposalCount = null, elapsedMs = null)

        val proposal = memoryIntelligence.consolidateMemoryBatch(request, preferredPlatform)
            ?: return retryable(runningJob, payload, "consolidation_unavailable_or_invalid", startedAt, null)
        val validatedOperations = runCatching { validateOperations(request, proposal.operations) }
            .getOrElse { throwable ->
                return retryable(
                    runningJob,
                    payload,
                    "invalid_consolidation_operations:${throwable.message}",
                    startedAt,
                    proposal.operations.size
                )
            }

        val applyResult = runCatching {
            applyOperations(
                payload = payload,
                existingMemories = existingMemories,
                operations = validatedOperations
            )
        }.getOrElse { throwable ->
            return retryable(
                runningJob,
                payload,
                "memory_write_failed:${throwable.message}",
                startedAt,
                proposal.operations.size
            )
        }

        if (!turnBatchDao.completeClaimedBatch(job.jobId, now())) {
            applyResult.rollback()
            return retryable(
                runningJob,
                payload,
                "claimed_batch_completion_failed",
                startedAt,
                proposal.operations.size
            )
        }
        maintenanceScheduler.markSucceeded(runningJob)
        turnBatchScheduler.repairAndSchedule()
        logBatch(
            runningJob,
            payload,
            "succeeded",
            proposalCount = proposal.operations.size,
            elapsedMs = System.currentTimeMillis() - startedAt
        )
        return MemoryBatchProcessResult(
            status = MemoryBatchProcessResult.STATUS_SUCCEEDED,
            jobId = job.jobId,
            operationCount = proposal.operations.size,
            dailyWriteCount = applyResult.dailyWriteCount,
            longTermWriteCount = applyResult.longTermWriteCount
        )
    }

    private suspend fun decodeAndValidatePayload(job: MemoryMaintenanceJob): MemoryTurnBatchJobPayload? = runCatching {
        check(job.type == MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH)
        val payload = json.decodeFromString<MemoryTurnBatchJobPayload>(job.payloadJson)
        check(payload.chatId > 0)
        check(payload.turns.size in 1..MemoryTurnBatchCoordinator.MAX_BATCH_TURNS)
        check(payload.triggerReason in VALID_TRIGGER_REASONS)
        check(payload.batchId == job.idempotencyKey)
        check(payload.turns.map { it.turnKey }.distinct().size == payload.turns.size)
        val claimedTurns = turnBatchDao.getTurnsClaimedByJob(job.jobId)
        check(claimedTurns.size == payload.turns.size)
        val claimedByKey = claimedTurns.associateBy { it.turnKey }
        payload.turns.forEach { jobTurn ->
            val claimedTurn = checkNotNull(claimedByKey[jobTurn.turnKey])
            check(claimedTurn.chatId == payload.chatId)
            check(claimedTurn.userMessageId == jobTurn.userMessageId)
            check(claimedTurn.contentHash == jobTurn.contentHash)
            check(claimedTurn.payloadJson == jobTurn.payloadJson)
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

    private suspend fun retrieveExistingMemories(payload: MemoryTurnBatchJobPayload): List<MemoryBatchExistingMemory> {
        val query = payload.turns
            .mapNotNull { turn ->
                runCatching { json.decodeFromString<MemoryCompletedTurnSnapshot>(turn.payloadJson).userContent }.getOrNull()
            }
            .joinToString(separator = "\n")
            .take(MAX_RETRIEVAL_QUERY_CHARS)
        if (query.isBlank()) return emptyList()
        val results = memoryRetriever.retrieve(
            MemoryRetrievalRequest(
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
        check(operations.size <= MAX_OPERATIONS)
        val existingById = request.existingMemories.associateBy { it.id }
        val turnKeys = request.turns.map { it.turnKey }.toSet()
        val targetedIds = mutableSetOf<String>()

        operations.forEach { operation ->
            check(operation.destination in VALID_DESTINATIONS)
            check(operation.action in VALID_ACTIONS)
            check(operation.type in VALID_TYPES)
            check(operation.sensitivity in VALID_SENSITIVITIES)
            check(operation.source in VALID_SOURCES)
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
        check(normalized.length <= MAX_MEMORY_TEXT_CHARS)
        check(!normalized.startsWith("The user said:", ignoreCase = true))
    }

    private suspend fun applyOperations(
        payload: MemoryTurnBatchJobPayload,
        existingMemories: List<MemoryBatchExistingMemory>,
        operations: List<MemoryBatchOperation>
    ): AppliedMemoryBatch {
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
                    val entry = operation.toEntry(
                        id = generatedEntryId(payload.batchId, operationIndex, operation.destination),
                        chatId = payload.chatId,
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
        val replacements = mutableListOf<MemoryFileReplacement>()
        try {
            changedMarkdown.toSortedMap().forEach { (sourcePath, markdown) ->
                val file = checkNotNull(filesByPath[sourcePath])
                replacements += memoryFileStore.replaceMemoryFile(file, markdown).getOrThrow()
            }
            replacements.forEach { replacement -> memoryIndexRebuilder.rebuildFile(replacement.file).getOrThrow() }
        } catch (throwable: Throwable) {
            rollbackReplacements(replacements)
            throw throwable
        }
        return AppliedMemoryBatch(
            replacements = replacements,
            dailyWriteCount = dailyWriteCount,
            longTermWriteCount = longTermWriteCount,
            memoryFileStore = memoryFileStore,
            memoryIndexRebuilder = memoryIndexRebuilder
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

    private suspend fun retryable(
        job: MemoryMaintenanceJob,
        payload: MemoryTurnBatchJobPayload,
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
        logBatch(job, payload, status, proposalCount, System.currentTimeMillis() - startedAt)
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
        payload: MemoryTurnBatchJobPayload,
        status: String,
        proposalCount: Int?,
        elapsedMs: Long?
    ) {
        runCatching {
            Log.i(
                TAG,
                "Memory batch id=${payload.batchId}, jobId=${job.jobId}, trigger=${payload.triggerReason}, " +
                    "turns=${payload.turns.size}, attempt=${job.attempts}, proposals=${proposalCount ?: -1}, " +
                    "status=$status, elapsedMs=${elapsedMs ?: -1}"
            )
        }
    }

    private suspend fun rollbackReplacements(replacements: List<MemoryFileReplacement>) {
        replacements.asReversed().forEach { replacement ->
            memoryFileStore.restoreMemoryFile(replacement)
            memoryIndexRebuilder.rebuildFile(replacement.file)
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
        private const val MAX_EXISTING_MEMORIES = 24
        private const val MAX_EXISTING_CANDIDATES = 200
        private const val MAX_EXISTING_MEMORY_TOKEN_BUDGET = 2_400
        private const val MAX_OPERATIONS = 32
        private const val MAX_MEMORY_TEXT_CHARS = 4_000
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
        private val VALID_TYPES = setOf(
            "stable_profile",
            "communication_style",
            "project_context",
            "interest",
            "important_event",
            "important_person",
            "emotional_pattern",
            "boundary",
            "life_context",
            "recurring_theme",
            "light_productivity_preference"
        )
        private val VALID_SENSITIVITIES = setOf(
            MemorySensitivity.NORMAL,
            MemorySensitivity.PRIVATE,
            MemorySensitivity.SENSITIVE
        )
        private val VALID_SOURCES = setOf(
            MemorySource.EXPLICIT_USER_STATEMENT,
            MemorySource.ASSISTANT_INFERRED,
            MemorySource.USER_CONFIRMED
        )
    }
}

private data class AppliedMemoryBatch(
    val replacements: List<MemoryFileReplacement>,
    val dailyWriteCount: Int,
    val longTermWriteCount: Int,
    val memoryFileStore: MemoryFileStore,
    val memoryIndexRebuilder: MemoryIndexRebuilder
) {
    suspend fun rollback() {
        replacements.asReversed().forEach { replacement ->
            memoryFileStore.restoreMemoryFile(replacement)
            memoryIndexRebuilder.rebuildFile(replacement.file)
        }
    }
}
