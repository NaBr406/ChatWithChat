package dev.chungjungsoo.gptmobile.data.repository

import android.util.Log
import dev.chungjungsoo.gptmobile.data.database.dao.ChatClassificationDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMaintenanceJobDao
import dev.chungjungsoo.gptmobile.data.database.dao.PersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatClassification
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.memory.ConversationClassificationRequest
import dev.chungjungsoo.gptmobile.data.memory.ConversationClassificationResult
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryCodec
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryEntry
import dev.chungjungsoo.gptmobile.data.memory.MemoryAction
import dev.chungjungsoo.gptmobile.data.memory.MemoryCandidate
import dev.chungjungsoo.gptmobile.data.memory.MemoryCompletedTurnInput
import dev.chungjungsoo.gptmobile.data.memory.MemoryConversationMessage
import dev.chungjungsoo.gptmobile.data.memory.MemoryFileStore
import dev.chungjungsoo.gptmobile.data.memory.MemoryExtractionRequest
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexRebuilder
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexSearchRequest
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexSearchResult
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexSearcher
import dev.chungjungsoo.gptmobile.data.memory.MemoryIntelligence
import dev.chungjungsoo.gptmobile.data.memory.MemoryLearningResult
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceJobStatus
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceJobType
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceWorkEnqueuer
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryLearningService
import dev.chungjungsoo.gptmobile.data.memory.MemoryMarkdownCodec
import dev.chungjungsoo.gptmobile.data.memory.MemoryPromptBuilder
import dev.chungjungsoo.gptmobile.data.memory.MemorySelectionCandidate
import dev.chungjungsoo.gptmobile.data.memory.MemorySelectionRequest
import dev.chungjungsoo.gptmobile.data.memory.MemorySensitivity
import dev.chungjungsoo.gptmobile.data.memory.MemorySource
import dev.chungjungsoo.gptmobile.data.memory.MemoryStatus
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchCoordinator
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnRecordingResult
import dev.chungjungsoo.gptmobile.data.memory.MemoryUpdatePlanningRequest
import dev.chungjungsoo.gptmobile.data.memory.PreparedMemoryContext
import dev.chungjungsoo.gptmobile.data.memory.SelectedPersonalMemory
import dev.chungjungsoo.gptmobile.data.memory.buildMemoryMessages
import dev.chungjungsoo.gptmobile.data.memory.toMarkdownMemoryEntry
import dev.chungjungsoo.gptmobile.data.memory.toPersonalMemory
import dev.chungjungsoo.gptmobile.data.memory.toSelectionCandidate
import java.io.File

class MemoryRepositoryImpl(
    private val personalMemoryDao: PersonalMemoryDao,
    private val chatClassificationDao: ChatClassificationDao,
    private val memoryIntelligence: MemoryIntelligence,
    private val memoryPromptBuilder: MemoryPromptBuilder,
    private val memoryMarkdownCodec: MemoryMarkdownCodec,
    private val memoryIndexSearcher: MemoryIndexSearcher? = null,
    private val markdownMemoryLearningService: MarkdownMemoryLearningService? = null,
    private val memoryFileStore: MemoryFileStore? = null,
    private val structuredMarkdownMemoryCodec: MarkdownMemoryCodec? = null,
    private val memoryIndexRebuilder: MemoryIndexRebuilder? = null,
    private val memoryMaintenanceJobDao: MemoryMaintenanceJobDao? = null,
    private val memoryMaintenanceScheduler: MemoryMaintenanceScheduler? = null,
    private val memoryMaintenanceWorkScheduler: MemoryMaintenanceWorkEnqueuer? = null,
    private val memoryTurnBatchCoordinator: MemoryTurnBatchCoordinator? = null,
    private val memoryTurnBatchScheduler: MemoryTurnBatchScheduler? = null
) : MemoryRepository {

    override suspend fun onMemoryEnabledChanged(enabled: Boolean) {
        memoryTurnBatchScheduler?.onMemoryEnabledChanged(enabled)
    }

    override suspend fun recordUserActivity(chatId: Int, activityAt: Long) {
        memoryTurnBatchCoordinator?.recordUserActivity(chatId, activityAt)
    }

    override suspend fun recordCompletedTurn(input: MemoryCompletedTurnInput): MemoryTurnRecordingResult =
        memoryTurnBatchCoordinator?.recordCompletedTurn(input)
            ?: MemoryTurnRecordingResult.skipped("turn_batch_storage_unavailable")

    override suspend fun prepareMemoryContext(
        chatRoom: ChatRoomV2,
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        memoryPlatform: PlatformV2?
    ): PreparedMemoryContext {
        val recentMessages = buildMemoryMessages(chatRoom, userMessages, assistantMessages)
        if (recentMessages.isEmpty()) return PreparedMemoryContext()
        val classificationMessages = recentMessages.forMemoryClassification()

        val classification = memoryIntelligence.classifyConversation(
            ConversationClassificationRequest(
                chatTitle = chatRoom.title,
                recentMessages = classificationMessages
            ),
            preferredPlatform = memoryPlatform
        ) ?: return PreparedMemoryContext()

        persistClassification(chatRoom.id, classification)
        if (!classification.shouldUseMemories) {
            return PreparedMemoryContext(classification = classification)
        }

        val markdownMemories = searchMarkdownMemories(recentMessages, classification)
        if (markdownMemories.isNotEmpty()) {
            return PreparedMemoryContext(
                classification = classification,
                selectedMarkdownMemories = markdownMemories,
                prompt = memoryPromptBuilder.buildMarkdown(markdownMemories)
            )
        }

        val safeCandidates = personalMemoryDao.getRecallCandidates()
            .filter { memory -> memory.isSafeCandidateFor(classification) }
            .take(MAX_CANDIDATE_MEMORIES)

        if (safeCandidates.isEmpty()) {
            return PreparedMemoryContext(classification = classification)
        }

        val selection = memoryIntelligence.selectMemories(
            MemorySelectionRequest(
                classification = classification,
                currentUserMessage = userMessages.lastOrNull()?.content.orEmpty().trimForMemoryContext(),
                candidateMemories = safeCandidates.map { it.toSelectionCandidate() }
            ),
            preferredPlatform = memoryPlatform
        ) ?: return PreparedMemoryContext(classification = classification)

        val candidatesById = safeCandidates.associateBy { it.id }
        val selectedMemories = selection.selected
            .mapNotNull { selected ->
                val memory = candidatesById[selected.memoryId] ?: return@mapNotNull null
                SelectedPersonalMemory(
                    memory = memory,
                    usage = selected.usage,
                    relevance = selected.relevance.coerceIn(0f, 1f),
                    reason = selected.reason
                )
            }
            .take(MAX_SELECTED_MEMORIES)

        touchSelectedMemories(selectedMemories)

        return PreparedMemoryContext(
            classification = classification,
            selectedMemories = selectedMemories,
            prompt = memoryPromptBuilder.build(selectedMemories)
        )
    }

    override suspend fun learnFromChat(
        chatRoom: ChatRoomV2,
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        memoryPlatform: PlatformV2?,
        learningIdempotencyKey: String?
    ): MemoryLearningResult {
        return runCatching {
            val recentMessages = buildMemoryMessages(chatRoom, userMessages, assistantMessages)
            if (recentMessages.isEmpty()) {
                return@runCatching logLearningResult(
                    MemoryLearningResult.skipped(MemoryLearningResult.STATUS_SKIPPED_NO_MESSAGES)
                )
            }
            val classificationMessages = recentMessages.forMemoryClassification()

            val classification = memoryIntelligence.classifyConversation(
                ConversationClassificationRequest(
                    chatTitle = chatRoom.title,
                    recentMessages = classificationMessages
                ),
                preferredPlatform = memoryPlatform
            )
            if (classification == null) {
                logInfo("Memory learning classification unavailable; continuing to extraction")
            } else {
                persistClassification(chatRoom.id, classification)
                logInfo(
                    "Memory learning classified chatId=${chatRoom.id}, shouldLearn=${classification.shouldLearnMemories}, " +
                        "mode=${classification.mode}, sensitivity=${classification.sensitivity}, confidence=${classification.confidence}"
                )
                if (!classification.shouldLearnMemories) {
                    logInfo("Memory learning will still extract candidates because classifier is advisory for learning")
                }
            }

            val rawCandidates = memoryIntelligence.extractMemoryCandidates(
                MemoryExtractionRequest(
                    chatTitle = chatRoom.title,
                    recentMessages = recentMessages.forMemoryExtraction()
                ),
                preferredPlatform = memoryPlatform
            )
            val candidates = rawCandidates.mapNotNull { it.normalizedOrNull() }
            logInfo("Memory learning extracted rawCandidates=${rawCandidates.size}, validCandidates=${candidates.size}")

            if (candidates.isEmpty()) {
                val existingMemories = personalMemoryDao.getRecallCandidates()
                    .take(MAX_CANDIDATE_MEMORIES)
                    .map { it.toSelectionCandidate() }
                val fallbackCreatedCount = createClassifierFallbackMemory(
                    classification = classification,
                    recentMessages = recentMessages,
                    existingMemories = existingMemories
                )
                if (fallbackCreatedCount > 0) {
                    return@runCatching finishLearningResult(
                        MemoryLearningResult.applied(
                            createdCount = fallbackCreatedCount,
                            updatedCount = 0,
                            statusChangedCount = 0,
                            candidateCount = fallbackCreatedCount,
                            operationCount = 0,
                            directFallback = true
                        ),
                        chatRoom = chatRoom,
                        recentMessages = recentMessages,
                        memoryPlatform = memoryPlatform,
                        learningIdempotencyKey = learningIdempotencyKey
                    )
                }

                return@runCatching finishLearningResult(
                    MemoryLearningResult.failed(
                        status = MemoryLearningResult.STATUS_FAILED_EXTRACTION_UNAVAILABLE,
                        reason = "classification requested learning but extraction returned no valid candidates"
                    ),
                    chatRoom = chatRoom,
                    recentMessages = recentMessages,
                    memoryPlatform = memoryPlatform,
                    learningIdempotencyKey = learningIdempotencyKey
                )
            }

            val existingMemories = personalMemoryDao.getRecallCandidates()
                .take(MAX_CANDIDATE_MEMORIES)
                .map { it.toSelectionCandidate() }

            val plan = memoryIntelligence.planMemoryUpdates(
                MemoryUpdatePlanningRequest(
                    candidates = candidates,
                    existingMemories = existingMemories
                ),
                preferredPlatform = memoryPlatform
            ) ?: run {
                val createdCount = createDirectMemories(candidates, existingMemories)
                return@runCatching finishLearningResult(
                    if (createdCount > 0) {
                        MemoryLearningResult.applied(
                            createdCount = createdCount,
                            updatedCount = 0,
                            statusChangedCount = 0,
                            candidateCount = candidates.size,
                            operationCount = 0,
                            directFallback = true
                        )
                    } else {
                        MemoryLearningResult.failed(
                            status = MemoryLearningResult.STATUS_FAILED_PLAN_UNAVAILABLE,
                            candidateCount = candidates.size
                        )
                    },
                    chatRoom = chatRoom,
                    recentMessages = recentMessages,
                    memoryPlatform = memoryPlatform,
                    learningIdempotencyKey = learningIdempotencyKey
                )
            }

            if (plan.operations.isEmpty()) {
                val createdCount = createDirectMemories(candidates, existingMemories)
                return@runCatching finishLearningResult(
                    if (createdCount > 0) {
                        MemoryLearningResult.applied(
                            createdCount = createdCount,
                            updatedCount = 0,
                            statusChangedCount = 0,
                            candidateCount = candidates.size,
                            operationCount = 0,
                            directFallback = true
                        )
                    } else {
                        MemoryLearningResult.skipped(
                            status = MemoryLearningResult.STATUS_SKIPPED_NO_OPERATIONS,
                            candidateCount = candidates.size,
                            reason = "planner returned no operations"
                        )
                    },
                    chatRoom = chatRoom,
                    recentMessages = recentMessages,
                    memoryPlatform = memoryPlatform,
                    learningIdempotencyKey = learningIdempotencyKey
                )
            }

            var createdCount = 0
            var updatedCount = 0
            var statusChangedCount = 0

            plan.operations.forEach { operation ->
                when (operation.action) {
                    MemoryAction.CREATE -> {
                        val candidate = operation.result ?: operation.candidateIndex?.let { candidates.getOrNull(it) }
                        if (candidate?.normalizedOrNull()?.let { createMemory(it) } == true) {
                            createdCount += 1
                        }
                    }

                    MemoryAction.UPDATE, MemoryAction.MERGE -> {
                        val candidate = operation.result ?: operation.candidateIndex?.let { candidates.getOrNull(it) }
                        if (candidate != null) {
                            updatedCount += updateTargets(
                                targetMemoryIds = operation.targetMemoryIds,
                                candidate = candidate.normalizedOrNull(),
                                merge = operation.action == MemoryAction.MERGE
                            )
                        }
                    }

                    MemoryAction.MARK_RESOLVED -> statusChangedCount += updateTargetStatus(operation.targetMemoryIds, MemoryStatus.RESOLVED)
                    MemoryAction.ARCHIVE -> statusChangedCount += updateTargetStatus(operation.targetMemoryIds, MemoryStatus.ARCHIVED)
                    MemoryAction.IGNORE -> Unit
                }
            }

            val changedCount = createdCount + updatedCount + statusChangedCount
            if (changedCount == 0) {
                val fallbackCreatedCount = createDirectMemories(candidates, existingMemories)
                if (fallbackCreatedCount > 0) {
                    return@runCatching finishLearningResult(
                        MemoryLearningResult.applied(
                            createdCount = fallbackCreatedCount,
                            updatedCount = 0,
                            statusChangedCount = 0,
                            candidateCount = candidates.size,
                            operationCount = plan.operations.size,
                            directFallback = true
                        ),
                        chatRoom = chatRoom,
                        recentMessages = recentMessages,
                        memoryPlatform = memoryPlatform,
                        learningIdempotencyKey = learningIdempotencyKey
                    )
                }
            }

            finishLearningResult(
                if (changedCount > 0) {
                    MemoryLearningResult.applied(
                        createdCount = createdCount,
                        updatedCount = updatedCount,
                        statusChangedCount = statusChangedCount,
                        candidateCount = candidates.size,
                        operationCount = plan.operations.size
                    )
                } else {
                    MemoryLearningResult.skipped(
                        status = MemoryLearningResult.STATUS_SKIPPED_NO_OPERATIONS,
                        candidateCount = candidates.size,
                        operationCount = plan.operations.size,
                        reason = "planner produced no memory changes"
                    )
                },
                chatRoom = chatRoom,
                recentMessages = recentMessages,
                memoryPlatform = memoryPlatform,
                learningIdempotencyKey = learningIdempotencyKey
            )
        }.getOrElse { throwable ->
            val result = MemoryLearningResult.failedException(throwable)
            logWarning("Memory learning failed with exception: ${result.reason}", throwable)
            result
        }
    }

    suspend fun learnFromChat(
        chatRoom: ChatRoomV2,
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        memoryPlatform: PlatformV2? = null
    ): MemoryLearningResult = learnFromChat(
        chatRoom = chatRoom,
        userMessages = userMessages,
        assistantMessages = assistantMessages,
        memoryPlatform = memoryPlatform,
        learningIdempotencyKey = null
    )

    override suspend fun getMemories(): List<PersonalMemory> = personalMemoryDao.getAll()

    override suspend fun updateMemory(memory: PersonalMemory) {
        personalMemoryDao.update(memory.copy(updatedAt = now()))
    }

    override suspend fun deleteMemory(memory: PersonalMemory) {
        personalMemoryDao.delete(memory)
    }

    override suspend fun confirmMemory(memory: PersonalMemory) {
        personalMemoryDao.update(
            memory.copy(
                source = MemorySource.USER_CONFIRMED,
                status = MemoryStatus.ACTIVE,
                updatedAt = now()
            )
        )
    }

    override suspend fun rejectMemory(memory: PersonalMemory) {
        personalMemoryDao.delete(memory)
    }

    override suspend fun markResolved(memory: PersonalMemory) {
        personalMemoryDao.update(memory.copy(status = MemoryStatus.RESOLVED, updatedAt = now()))
    }

    override suspend fun archiveMemory(memory: PersonalMemory) {
        personalMemoryDao.update(memory.copy(status = MemoryStatus.ARCHIVED, updatedAt = now()))
    }

    override suspend fun exportMarkdown(): String = memoryMarkdownCodec.encode(personalMemoryDao.getAll())

    override suspend fun getLongTermMarkdown(): String =
        memoryFileStore
            ?.readLongTermMemory()
            ?.getOrDefault("")
            ?: memoryMarkdownCodec.encode(personalMemoryDao.getAll())

    override suspend fun migrateActiveMemoriesToMarkdown(): Int {
        val fileStore = memoryFileStore ?: return 0
        val codec = structuredMarkdownMemoryCodec ?: return 0

        return runCatching {
            val activeMemories = personalMemoryDao.getAll()
                .filter { memory -> memory.status == MemoryStatus.ACTIVE }
            val classifierFallbackMarkdownIds = activeMemories
                .filter { memory -> memory.isClassifierFallbackMemory() }
                .map { memory -> "personal_${memory.id}" }
                .toSet()
            val existingEntries = codec.parse(fileStore.readLongTermMemory().getOrThrow()).entries
            val repairedExistingEntries = existingEntries
                .mapNotNull { entry -> entry.repairedLongTermEntryOrNull(classifierFallbackMarkdownIds) }
                .deduplicatedMarkdownEntries()
            val filesToRebuild = mutableSetOf<File>()
            if (repairedExistingEntries != existingEntries) {
                val replacement = fileStore.replaceLongTermMemory(codec.renderLongTerm(repairedExistingEntries)).getOrThrow()
                filesToRebuild += replacement.file
            }

            val existingIds = repairedExistingEntries.map { it.id }.toSet()
            val existingKeys = repairedExistingEntries.map { it.memoryDuplicateKey() }.toSet()
            val entriesToAppend = activeMemories
                .mapNotNull { memory -> memory.toMigratedMarkdownMemoryEntryOrNull() }
                .deduplicatedMarkdownEntries()
                .filterNot { entry -> entry.id in existingIds || entry.memoryDuplicateKey() in existingKeys }

            if (entriesToAppend.isNotEmpty()) {
                val file = fileStore.appendLongTermMemory(codec.renderLongTermAppend(entriesToAppend)).getOrThrow()
                filesToRebuild += file
            }

            filesToRebuild.forEach { file ->
                memoryIndexRebuilder
                    ?.rebuildFile(file)
                    ?.onFailure { throwable ->
                        logWarning("Markdown memory migration index rebuild failed: ${throwable.message}", throwable)
                    }
            }
            entriesToAppend.size
        }.getOrElse { throwable ->
            logWarning("Markdown memory migration failed: ${throwable.message}", throwable)
            0
        }
    }

    override suspend fun getMaintenanceJobs(): List<MemoryMaintenanceJob> =
        memoryMaintenanceJobDao?.getVisibleJobs().orEmpty()

    override suspend fun retryMaintenanceJob(jobId: String) {
        val dao = memoryMaintenanceJobDao ?: return
        val job = dao.getById(jobId) ?: return
        dao.update(
            job.copy(
                status = MemoryMaintenanceJobStatus.PENDING,
                attempts = 0,
                lastError = null,
                updatedAt = now(),
                nextRunAt = now()
            )
        )
        memoryMaintenanceWorkScheduler?.enqueueRepairWork()
    }

    override suspend fun dismissMaintenanceJob(jobId: String) {
        val dao = memoryMaintenanceJobDao ?: return
        val job = dao.getById(jobId) ?: return
        if (job.type == MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH) {
            memoryTurnBatchScheduler?.dismissBatch(job)?.let { return }
        }
        memoryMaintenanceScheduler?.markDismissed(job)?.let { return }
        dao.update(
            job.copy(
                status = MemoryMaintenanceJobStatus.DISMISSED,
                updatedAt = now(),
                nextRunAt = null
            )
        )
    }

    private suspend fun persistClassification(
        chatId: Int,
        classification: ConversationClassificationResult
    ) {
        if (chatId <= 0) return
        chatClassificationDao.upsert(
            ChatClassification(
                chatId = chatId,
                mode = classification.mode,
                intent = classification.intent,
                memoryNeeds = classification.memoryNeeds,
                domains = classification.domains,
                entities = classification.entities,
                emotionalTone = classification.emotionalTone,
                shouldUseMemories = classification.shouldUseMemories,
                shouldLearnMemories = classification.shouldLearnMemories,
                sensitivity = classification.sensitivity,
                confidence = classification.confidence.coerceIn(0f, 1f),
                updatedAt = now()
            )
        )
    }

    private fun PersonalMemory.isSafeCandidateFor(
        classification: ConversationClassificationResult
    ): Boolean {
        if (status in setOf(MemoryStatus.ARCHIVED, MemoryStatus.SUPERSEDED, MemoryStatus.RESOLVED, MemoryStatus.PENDING_CONFIRMATION)) return false
        if (sensitivity == MemorySensitivity.SENSITIVE) {
            return source == MemorySource.USER_CONFIRMED && classification.confidence >= SENSITIVE_RECALL_CONFIDENCE
        }
        return true
    }

    private suspend fun searchMarkdownMemories(
        recentMessages: List<MemoryConversationMessage>,
        classification: ConversationClassificationResult
    ): List<MemoryIndexSearchResult> {
        val searcher = memoryIndexSearcher ?: return emptyList()
        val query = buildMarkdownRecallQuery(recentMessages, classification)
        if (query.isBlank()) return emptyList()

        return searcher.search(
            MemoryIndexSearchRequest(
                query = query,
                includePrivate = true,
                limit = MAX_SELECTED_MEMORIES,
                candidateLimit = MAX_CANDIDATE_MEMORIES
            )
        ).getOrElse { throwable ->
            logWarning("Markdown memory search failed; falling back to Room memories: ${throwable.message}", throwable)
            emptyList()
        }
    }

    private fun buildMarkdownRecallQuery(
        recentMessages: List<MemoryConversationMessage>,
        classification: ConversationClassificationResult
    ): String = buildString {
        appendLine(recentMessages.lastOrNull { it.role == "user" }?.content.orEmpty().trimForMemoryContext())
        appendLine(classification.intent)
        appendLine(classification.memoryNeeds.joinToString(" "))
        appendLine(classification.domains.joinToString(" "))
        appendLine(classification.entities.joinToString(" "))
        recentMessages
            .takeLast(MARKDOWN_RECALL_RECENT_MESSAGE_COUNT)
            .forEach { message ->
                appendLine(message.content.trimForMemoryContext())
            }
    }.trim()

    private suspend fun touchSelectedMemories(selectedMemories: List<SelectedPersonalMemory>) {
        val timestamp = now()
        selectedMemories.forEach { selectedMemory ->
            personalMemoryDao.update(
                selectedMemory.memory.copy(
                    lastAccessedAt = timestamp,
                    updatedAt = selectedMemory.memory.updatedAt
                )
            )
        }
    }

    private suspend fun createMemory(candidate: MemoryCandidate): Boolean {
        personalMemoryDao.insert(candidate.toPersonalMemory(now = now(), status = candidate.safeInitialStatus()))
        return true
    }

    private suspend fun updateTargets(
        targetMemoryIds: List<Int>,
        candidate: MemoryCandidate?,
        merge: Boolean
    ): Int {
        candidate ?: return 0
        val targets = personalMemoryDao.getByIds(targetMemoryIds)
        val primary = targets.firstOrNull() ?: return 0
        val timestamp = now()
        personalMemoryDao.update(
            candidate.toPersonalMemory(
                id = primary.id,
                now = timestamp,
                status = candidate.safeInitialStatus()
            ).copy(createdAt = primary.createdAt)
        )
        var updatedCount = 1
        if (merge) {
            targets.drop(1).forEach { memory ->
                personalMemoryDao.update(memory.copy(status = MemoryStatus.SUPERSEDED, updatedAt = timestamp))
                updatedCount += 1
            }
        }
        return updatedCount
    }

    private suspend fun updateTargetStatus(targetMemoryIds: List<Int>, status: String): Int {
        val timestamp = now()
        val targets = personalMemoryDao.getByIds(targetMemoryIds)
        targets.forEach { memory ->
            personalMemoryDao.update(memory.copy(status = status, updatedAt = timestamp))
        }
        return targets.size
    }

    private suspend fun createDirectMemories(
        candidates: List<MemoryCandidate>,
        existingMemories: List<MemorySelectionCandidate>
    ): Int {
        var createdCount = 0
        candidates
            .filter { it.isDirectLearningCandidate() }
            .filterNot { it.isAlreadyRepresentedBy(existingMemories) }
            .forEach { candidate ->
                if (createMemory(candidate)) {
                    createdCount += 1
                }
            }
        return createdCount
    }

    private suspend fun createClassifierFallbackMemory(
        classification: ConversationClassificationResult?,
        recentMessages: List<MemoryConversationMessage>,
        existingMemories: List<MemorySelectionCandidate>
    ): Int {
        if (classification == null || !classification.shouldLearnMemories || classification.confidence < CLASSIFIER_FALLBACK_MIN_CONFIDENCE) {
            return 0
        }
        val latestUserStatement = recentMessages.lastOrNull { message -> message.role == "user" }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return 0

        val source = if (classification.sensitivity == MemorySensitivity.SENSITIVE) {
            MemorySource.EXPLICIT_USER_STATEMENT
        } else {
            MemorySource.USER_CONFIRMED
        }
        val fallbackText = latestUserStatement.toClassifierFallbackMemoryTextOrNull() ?: run {
            logInfo("Memory learning skipped classifier fallback because latest user statement is too raw or long")
            return 0
        }

        val candidate = MemoryCandidate(
            summary = fallbackText,
            recallText = fallbackText,
            type = classification.memoryNeeds.firstOrNull { it.isNotBlank() } ?: "stable_profile",
            scope = "personal",
            domains = classification.domains,
            entities = classification.entities,
            tags = listOf("classifier_fallback"),
            applicableModes = listOf(classification.mode),
            importance = classification.confidence.coerceIn(0f, 0.85f),
            confidence = classification.confidence.coerceIn(0f, 1f),
            source = source,
            sensitivity = classification.sensitivity,
            suggestedStatus = if (classification.sensitivity == MemorySensitivity.SENSITIVE) {
                MemoryStatus.PENDING_CONFIRMATION
            } else {
                MemoryStatus.ACTIVE
            },
            evidence = "Created from the latest user statement after memory extraction returned no candidates.",
            requiresConfirmation = classification.sensitivity == MemorySensitivity.SENSITIVE,
            reason = "High-confidence classifier fallback"
        ).normalizedOrNull() ?: return 0

        if (!candidate.isDirectLearningCandidate() || candidate.isAlreadyRepresentedBy(existingMemories)) {
            return 0
        }

        logInfo("Memory learning creating classifier fallback memory because extraction returned no valid candidates")
        return if (createMemory(candidate)) 1 else 0
    }

    private fun MemoryCandidate.isDirectLearningCandidate(): Boolean =
        suggestedStatus == MemoryStatus.ACTIVE &&
            (
                (source == MemorySource.USER_CONFIRMED && sensitivity != MemorySensitivity.SENSITIVE) ||
                    (source == MemorySource.EXPLICIT_USER_STATEMENT && sensitivity == MemorySensitivity.NORMAL && !requiresConfirmation)
            )

    private fun MemoryCandidate.isAlreadyRepresentedBy(existingMemories: List<MemorySelectionCandidate>): Boolean {
        val summaryKey = summary.normalizedMemoryKey()
        val recallKey = recallText.normalizedMemoryKey()
        return existingMemories.any { memory ->
            memory.summary.normalizedMemoryKey() == summaryKey ||
                memory.recallText.normalizedMemoryKey() == recallKey
        }
    }

    private fun PersonalMemory.toMigratedMarkdownMemoryEntryOrNull(): MarkdownMemoryEntry? {
        if (isClassifierFallbackMemory()) return null
        val text = markdownMigrationTextOrNull() ?: return null
        return toMarkdownMemoryEntry(text = text)
    }

    private fun PersonalMemory.markdownMigrationTextOrNull(): String? {
        val candidates = listOf(summary, recallText)
        return candidates
            .mapNotNull { text -> text.cleanMarkdownMemoryTextOrNull() }
            .firstOrNull { text -> !text.hasRawUserStatementPrefix() && text.length <= MAX_MIGRATED_MARKDOWN_TEXT_LENGTH }
            ?: candidates
                .mapNotNull { text -> text.cleanMarkdownMemoryTextOrNull() }
                .firstOrNull { text -> text.length <= MAX_MIGRATED_MARKDOWN_TEXT_LENGTH }
    }

    private fun PersonalMemory.isClassifierFallbackMemory(): Boolean =
        tags.any { tag -> tag.equals("classifier_fallback", ignoreCase = true) }

    private fun MarkdownMemoryEntry.repairedLongTermEntryOrNull(classifierFallbackMarkdownIds: Set<String>): MarkdownMemoryEntry? {
        if (id in classifierFallbackMarkdownIds) return null
        val cleanedText = text.cleanMarkdownMemoryTextOrNull() ?: return null
        if (text.hasRawUserStatementPrefix() && cleanedText.length > MAX_MIGRATED_MARKDOWN_TEXT_LENGTH) return null
        return if (cleanedText == text) this else copy(text = cleanedText, updatedAt = now())
    }

    private fun List<MarkdownMemoryEntry>.deduplicatedMarkdownEntries(): List<MarkdownMemoryEntry> {
        val usedIds = mutableSetOf<String>()
        val usedKeys = mutableSetOf<String>()
        return filter { entry ->
            val key = entry.memoryDuplicateKey()
            val duplicate = entry.id in usedIds || key in usedKeys
            if (!duplicate) {
                usedIds += entry.id
                usedKeys += key
            }
            !duplicate
        }
    }

    private fun MarkdownMemoryEntry.memoryDuplicateKey(): String =
        "${type.normalizedMemoryType()}|${text.normalizedMemoryKey()}"

    private fun MemoryCandidate.normalizedOrNull(): MemoryCandidate? {
        val normalizedSummary = summary.trim()
        val normalizedRecallText = recallText.trim().ifBlank { normalizedSummary }
        if (normalizedSummary.isBlank() || normalizedRecallText.isBlank()) return null

        val normalizedType = type.normalizedMemoryType()
        val normalizedSource = source.normalizedMemorySource()
        val normalizedSensitivity = sensitivity.normalizedMemorySensitivity()
        val normalizedStatus = suggestedStatus.normalizedMemoryStatus()
        val normalizedScope = scope.normalizedMemoryScope()

        return copy(
            summary = normalizedSummary.take(MAX_FIELD_LENGTH),
            details = details?.trim()?.take(MAX_DETAILS_LENGTH),
            recallText = normalizedRecallText.take(MAX_FIELD_LENGTH),
            type = normalizedType,
            scope = normalizedScope,
            domains = domains.sanitizedList(),
            entities = entities.sanitizedList(),
            tags = tags.sanitizedList(),
            applicableModes = applicableModes.sanitizedList(),
            avoidModes = avoidModes.sanitizedList(),
            importance = importance.coerceIn(0f, 1f),
            confidence = confidence.coerceIn(0f, 1f),
            source = normalizedSource,
            sensitivity = normalizedSensitivity,
            suggestedStatus = normalizedStatus,
            evidence = evidence?.take(MAX_FIELD_LENGTH),
            reason = reason.take(MAX_FIELD_LENGTH)
        )
    }

    private fun MemoryCandidate.safeInitialStatus(): String = when {
        sensitivity == MemorySensitivity.SENSITIVE && source != MemorySource.USER_CONFIRMED -> MemoryStatus.PENDING_CONFIRMATION
        sensitivity == MemorySensitivity.PRIVATE && source != MemorySource.USER_CONFIRMED -> MemoryStatus.PENDING_CONFIRMATION
        suggestedStatus == MemoryStatus.ARCHIVED || suggestedStatus == MemoryStatus.RESOLVED -> suggestedStatus
        source == MemorySource.USER_CONFIRMED -> MemoryStatus.ACTIVE
        requiresConfirmation -> MemoryStatus.PENDING_CONFIRMATION
        source == MemorySource.EXPLICIT_USER_STATEMENT && sensitivity == MemorySensitivity.NORMAL && suggestedStatus == MemoryStatus.ACTIVE -> MemoryStatus.ACTIVE
        else -> MemoryStatus.PENDING_CONFIRMATION
    }

    private fun List<String>.sanitizedList(): List<String> = mapNotNull { value ->
        value.trim().take(MAX_FIELD_LENGTH).takeIf { it.isNotBlank() }
    }.take(MAX_LIST_ITEMS)

    private fun String.normalizedMemoryKey(): String = trim().lowercase()

    private fun String.normalizedMemoryType(): String {
        val token = normalizedEnumToken()
        return when (token) {
            "communication", "communication_preference", "communication_preferences", "style", "tone", "tone_preference", "preference", "preferences", "user_preference" -> "communication_style"
            "interests" -> "interest"
            "important_events", "event", "life_event" -> "important_event"
            "important_people", "person", "people", "relationship" -> "important_person"
            "emotional_patterns", "emotion", "emotional" -> "emotional_pattern"
            "boundaries", "limit", "limits" -> "boundary"
            "life", "context", "background" -> "life_context"
            "recurring_themes", "theme" -> "recurring_theme"
            "productivity", "productivity_preference", "task_preference", "workflow_preference" -> "light_productivity_preference"
            "profile", "user_profile", "personal_profile" -> "stable_profile"
            else -> token.takeIf { it in ALLOWED_TYPES } ?: "stable_profile"
        }
    }

    private fun String.normalizedMemorySource(): String {
        val token = normalizedEnumToken()
        return when (token) {
            "user", "explicit", "explicit_statement", "explicit_user", "user_statement", "direct_user_statement" -> MemorySource.EXPLICIT_USER_STATEMENT
            "confirmed", "user_confirmed", "explicit_confirmed", "explicit_user_confirmed" -> MemorySource.USER_CONFIRMED
            "assistant", "inferred", "assistant_inferred", "model_inferred" -> MemorySource.ASSISTANT_INFERRED
            else -> token.takeIf { it in ALLOWED_SOURCES } ?: MemorySource.ASSISTANT_INFERRED
        }
    }

    private fun String.normalizedMemorySensitivity(): String {
        val token = normalizedEnumToken()
        return when (token) {
            "low", "safe", "public", "non_sensitive", "nonprivate" -> MemorySensitivity.NORMAL
            "personal", "private" -> MemorySensitivity.PRIVATE
            "high", "sensitive" -> MemorySensitivity.SENSITIVE
            else -> token.takeIf { it in ALLOWED_SENSITIVITIES } ?: MemorySensitivity.NORMAL
        }
    }

    private fun String.normalizedMemoryStatus(): String {
        val token = normalizedEnumToken()
        return when (token) {
            "active", "save", "create", "remember" -> MemoryStatus.ACTIVE
            "pending", "pending_confirmation", "needs_confirmation", "confirm" -> MemoryStatus.PENDING_CONFIRMATION
            "resolved", "done" -> MemoryStatus.RESOLVED
            "archived", "archive" -> MemoryStatus.ARCHIVED
            else -> token.takeIf { it in ALLOWED_STATUSES } ?: MemoryStatus.ACTIVE
        }
    }

    private fun String.normalizedMemoryScope(): String {
        val token = normalizedEnumToken()
        return when (token) {
            "all", "always", "general", "global" -> "global"
            "personal", "user" -> "personal"
            "domain", "topic" -> "domain"
            "chat", "conversation" -> "chat"
            else -> token.takeIf { it in ALLOWED_SCOPES } ?: "personal"
        }
    }

    private fun String.normalizedEnumToken(): String = trim()
        .lowercase()
        .replace('-', '_')
        .replace(' ', '_')

    private fun List<MemoryConversationMessage>.forMemoryExtraction(): List<MemoryConversationMessage> {
        val latestUserMessage = lastOrNull { message -> message.role == "user" }
        if (latestUserMessage != null) {
            return listOf(latestUserMessage.copy(content = latestUserMessage.content.trimForMemoryExtraction()))
        }

        return takeLast(2).map { message ->
            message.copy(content = message.content.trimForMemoryExtraction())
        }
    }

    private fun List<MemoryConversationMessage>.forMemoryClassification(): List<MemoryConversationMessage> =
        map { message ->
            message.copy(content = message.content.trimForMemoryContext())
        }

    private fun String.trimForMemoryExtraction(): String = trim()
        .take(MAX_EXTRACTION_MESSAGE_LENGTH)

    private fun String.trimForMemoryContext(): String = trim()
        .take(MAX_CONTEXT_MESSAGE_LENGTH)

    private fun String.toClassifierFallbackMemoryTextOrNull(): String? =
        cleanMarkdownMemoryTextOrNull()
            ?.takeIf { text -> text.length <= MAX_CLASSIFIER_FALLBACK_STATEMENT_LENGTH }

    private fun String.cleanMarkdownMemoryTextOrNull(): String? {
        val cleaned = removeRawUserStatementPrefix()
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun String.hasRawUserStatementPrefix(): Boolean =
        trimStart().startsWith(RAW_USER_STATEMENT_PREFIX, ignoreCase = true)

    private fun String.removeRawUserStatementPrefix(): String =
        trim().replace(Regex("^${Regex.escape(RAW_USER_STATEMENT_PREFIX)}\\s*", RegexOption.IGNORE_CASE), "")

    private fun logLearningResult(result: MemoryLearningResult): MemoryLearningResult {
        logInfo(
            "Memory learning result=${result.status}, changed=${result.changedCount}, " +
                "created=${result.createdCount}, updated=${result.updatedCount}, statusChanged=${result.statusChangedCount}, " +
                "candidates=${result.candidateCount}, operations=${result.operationCount}, reason=${result.reason.orEmpty()}"
        )
        return result
    }

    private suspend fun finishLearningResult(
        result: MemoryLearningResult,
        chatRoom: ChatRoomV2,
        recentMessages: List<MemoryConversationMessage>,
        memoryPlatform: PlatformV2?,
        learningIdempotencyKey: String?
    ): MemoryLearningResult {
        val loggedResult = logLearningResult(result)
        runMarkdownMemoryLearning(
            chatRoom = chatRoom,
            recentMessages = recentMessages,
            memoryPlatform = memoryPlatform,
            learningIdempotencyKey = learningIdempotencyKey
        )
        return loggedResult
    }

    private suspend fun runMarkdownMemoryLearning(
        chatRoom: ChatRoomV2,
        recentMessages: List<MemoryConversationMessage>,
        memoryPlatform: PlatformV2?,
        learningIdempotencyKey: String?
    ) {
        val learningService = markdownMemoryLearningService ?: return
        runCatching {
            learningService.learnFromTurn(
                chatRoom = chatRoom,
                recentMessages = recentMessages,
                existingRoomMemories = personalMemoryDao.getRecallCandidates().take(MAX_CANDIDATE_MEMORIES),
                memoryIntelligence = memoryIntelligence,
                preferredPlatform = memoryPlatform,
                learningIdempotencyKey = learningIdempotencyKey
            )
        }.onSuccess { result ->
            logInfo(
                "Markdown memory learning result=${result.status}, changed=${result.changedCount}, " +
                    "daily=${result.dailyNotesWritten}, longTerm=${result.longTermUpdatesWritten}, " +
                    "duplicates=${result.duplicateCount}, job=${result.jobId.orEmpty()}, reason=${result.reason.orEmpty()}"
            )
        }.onFailure { throwable ->
            logWarning("Markdown memory learning failed before job completion: ${throwable.message}", throwable)
        }
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logWarning(message: String, throwable: Throwable) {
        runCatching { Log.w(TAG, message, throwable) }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    companion object {
        private const val TAG = "MemoryRepository"
        private const val MAX_CANDIDATE_MEMORIES = 24
        private const val MAX_SELECTED_MEMORIES = 8
        private const val MAX_FIELD_LENGTH = 500
        private const val MAX_DETAILS_LENGTH = 2000
        private const val MAX_LIST_ITEMS = 20
        private const val MAX_EXTRACTION_MESSAGE_LENGTH = 1200
        private const val MAX_CONTEXT_MESSAGE_LENGTH = 1200
        private const val MAX_CLASSIFIER_FALLBACK_STATEMENT_LENGTH = 220
        private const val MAX_MIGRATED_MARKDOWN_TEXT_LENGTH = 360
        private const val MARKDOWN_RECALL_RECENT_MESSAGE_COUNT = 4
        private const val SENSITIVE_RECALL_CONFIDENCE = 0.8f
        private const val CLASSIFIER_FALLBACK_MIN_CONFIDENCE = 0.8f
        private const val RAW_USER_STATEMENT_PREFIX = "The user said:"

        private val ALLOWED_TYPES = setOf(
            "stable_profile",
            "communication_style",
            "interest",
            "important_event",
            "important_person",
            "emotional_pattern",
            "boundary",
            "life_context",
            "recurring_theme",
            "light_productivity_preference"
        )
        private val ALLOWED_SCOPES = setOf("global", "personal", "domain", "chat")
        private val ALLOWED_SOURCES = setOf(
            MemorySource.EXPLICIT_USER_STATEMENT,
            MemorySource.ASSISTANT_INFERRED,
            MemorySource.USER_CONFIRMED
        )
        private val ALLOWED_SENSITIVITIES = setOf(
            MemorySensitivity.NORMAL,
            MemorySensitivity.PRIVATE,
            MemorySensitivity.SENSITIVE
        )
        private val ALLOWED_STATUSES = setOf(
            MemoryStatus.ACTIVE,
            MemoryStatus.PENDING_CONFIRMATION,
            MemoryStatus.RESOLVED,
            MemoryStatus.ARCHIVED
        )
    }
}
