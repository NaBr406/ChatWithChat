package cn.nabr.chatwithchat.data.repository

import android.util.Log
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.debug.MemoryRecallTrace
import cn.nabr.chatwithchat.data.debug.PromptTraceStore
import cn.nabr.chatwithchat.data.history.ChatHistoryIndexCoordinator
import cn.nabr.chatwithchat.data.history.ChatHistoryRetrievalRequest
import cn.nabr.chatwithchat.data.history.ChatHistoryRetriever
import cn.nabr.chatwithchat.data.history.HistoryRecallSnapshot
import cn.nabr.chatwithchat.data.memory.MemoryActivityLogger
import cn.nabr.chatwithchat.data.memory.MemoryActivityStatus
import cn.nabr.chatwithchat.data.memory.MemoryCompletedTurnInput
import cn.nabr.chatwithchat.data.memory.MemoryCorpus
import cn.nabr.chatwithchat.data.memory.MemoryDailyDistillationScheduler
import cn.nabr.chatwithchat.data.memory.MemoryFilePaths
import cn.nabr.chatwithchat.data.memory.MemoryFileStore
import cn.nabr.chatwithchat.data.memory.MemoryLongTermConsolidationScheduler
import cn.nabr.chatwithchat.data.memory.MemoryPromptBuilder
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalMode
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalReport
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalRequest
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalResult
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalStrategy
import cn.nabr.chatwithchat.data.memory.MemoryRetriever
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchCoordinator
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchScheduler
import cn.nabr.chatwithchat.data.memory.MemoryTurnRecordingResult
import cn.nabr.chatwithchat.data.memory.ModelVisibleMemoryFact
import cn.nabr.chatwithchat.data.memory.PreparedMemoryContext
import cn.nabr.chatwithchat.data.memory.TurnRecallSnapshot
import cn.nabr.chatwithchat.data.memory.buildMemoryMessages
import cn.nabr.chatwithchat.data.memory.deduplicationKey
import cn.nabr.chatwithchat.data.memory.normalizeExactMemoryText
import cn.nabr.chatwithchat.data.memory.recordStandalonePlanningResult
import cn.nabr.chatwithchat.data.memory.toModelVisibleMemoryFactOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class MemoryRepositoryImpl(
    private val memoryPromptBuilder: MemoryPromptBuilder,
    private val memoryRetriever: MemoryRetriever? = null,
    private val memoryFileStore: MemoryFileStore? = null,
    private val memoryTurnBatchCoordinator: MemoryTurnBatchCoordinator? = null,
    private val memoryTurnBatchScheduler: MemoryTurnBatchScheduler? = null,
    private val memoryDailyDistillationScheduler: MemoryDailyDistillationScheduler? = null,
    private val memoryLongTermConsolidationScheduler: MemoryLongTermConsolidationScheduler? = null,
    private val promptTraceStore: PromptTraceStore? = null,
    private val activityLogger: MemoryActivityLogger = MemoryActivityLogger.None,
    private val chatHistoryRetriever: ChatHistoryRetriever? = null,
    private val chatHistoryIndexCoordinator: ChatHistoryIndexCoordinator? = null
) : MemoryRepository {

    override suspend fun onMemoryEnabledChanged(enabled: Boolean) {
        runMemoryTransitionStep("history_toggle_failed") {
            if (enabled) chatHistoryIndexCoordinator?.onMemoryEnabledChanged(true)
        }
        runMemoryTransitionStep("memory_toggle_turn_batch_failed") {
            memoryTurnBatchScheduler?.onMemoryEnabledChanged(enabled)
        }
        if (enabled) {
            runMemoryTransitionStep("memory_toggle_daily_planning_failed") {
                memoryDailyDistillationScheduler?.ensurePlanningJobs()
            }
            runMemoryTransitionStep("memory_toggle_long_term_planning_failed") {
                memoryLongTermConsolidationScheduler?.ensureScheduled()
            }
        } else {
            activityLogger.recordStandalonePlanningResult(
                jobType = null,
                triggerReason = "memory_toggle",
                status = MemoryActivityStatus.SKIPPED,
                outcomeCode = "memory_disabled"
            )
        }
    }

    private suspend fun runMemoryTransitionStep(
        errorCode: String,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            activityLogger.recordStandalonePlanningResult(
                jobType = null,
                triggerReason = "memory_toggle",
                status = MemoryActivityStatus.FAILED,
                outcomeCode = errorCode
            )
        }
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
        val recallKey = memoryRecallKey(chatRoom.id, userMessages)
        val query = buildLocalRecallQuery(userMessages.lastOrNull())
        val recentContext = buildLocalRecentContext(chatRoom, userMessages, assistantMessages)
        val historySnapshot = retrieveHistory(
            chatRoom = chatRoom,
            query = query,
            recentContext = recentContext
        )
        chatHistoryIndexCoordinator?.let { coordinator ->
            runMemoryTransitionStep("history_reconcile_schedule_failed") {
                coordinator.ensureReconciliationScheduled()
            }
        }
        val retriever = memoryRetriever ?: run {
            recordMemoryRecall(recallKey, MemoryRecallTrace(MemoryRetrievalMode.NONE, 0, emptyList()))
            return PreparedMemoryContext(historySnapshot = historySnapshot)
        }
        if (query.isBlank()) {
            recordMemoryRecall(recallKey, MemoryRecallTrace(MemoryRetrievalMode.NONE, 0, emptyList()))
            return PreparedMemoryContext(historySnapshot = historySnapshot)
        }
        val retrievalRequest = MemoryRetrievalRequest(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            query = query,
            recentContext = recentContext,
            limit = MAX_QUERY_MEMORIES,
            candidateLimit = MAX_CANDIDATE_MEMORIES,
            tokenBudget = QUERY_RECALL_TOKEN_BUDGET,
            includePrivate = true,
            strategy = MemoryRetrievalStrategy.HYBRID
        )
        val retrievalReport = retriever.retrieveWithDiagnostics(retrievalRequest).getOrElse { throwable ->
            logWarning("Local memory retrieval failed; continuing without memory: ${throwable.message}", throwable)
            MemoryRetrievalReport(
                results = emptyList(),
                mode = MemoryRetrievalMode.FAILED,
                errorMessage = throwable.message
            )
        }
        val coreResults = retrievalReport.coreResults
            .filter { memory -> memory.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME }
            .filter { memory -> memory.text.toModelVisibleMemoryFactOrNull() != null }
        val coreKeys = coreResults.mapTo(mutableSetOf(), MemoryRetrievalResult::deduplicationKey)
        val queryResults = retrievalReport.results
            .filter { memory -> memory.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME }
            .filterNot { memory -> memory.deduplicationKey() in coreKeys }
            .filter { memory -> memory.text.toModelVisibleMemoryFactOrNull() != null }
            .take(MAX_QUERY_MEMORIES)
        val renderedPrompt = memoryPromptBuilder.build(
            coreFacts = coreResults.mapNotNull { memory -> memory.text.toModelVisibleMemoryFactOrNull() },
            queryFacts = queryResults.mapNotNull { memory -> memory.text.toModelVisibleMemoryFactOrNull() }
        )
        val selectedCoreResults = coreResults.selectedBy(renderedPrompt.coreFacts)
        val selectedQueryResults = queryResults.selectedBy(renderedPrompt.queryFacts)
        val retrievedMemories = selectedCoreResults + selectedQueryResults
        val turnSnapshot = TurnRecallSnapshot(
            canonicalRevision = retrievalReport.canonicalRevision,
            canonicalSourceHash = retrievalReport.canonicalSourceHash,
            recallProjectionHash = retrievalReport.recallProjectionHash,
            coreFacts = renderedPrompt.coreFacts,
            queryFacts = renderedPrompt.queryFacts,
            mode = retrievalReport.mode,
            errorMessage = retrievalReport.errorMessage,
            diagnostics = retrievalReport.diagnostics,
            prompt = renderedPrompt.prompt,
            estimatedTokens = renderedPrompt.estimatedTokens
        )
        recordMemoryRecall(
            key = recallKey,
            recall = MemoryRecallTrace(
                mode = retrievalReport.mode,
                hitCount = retrievedMemories.size,
                memoryIds = retrievedMemories.map { memory -> memory.entryId ?: memory.chunkId }.distinct(),
                errorMessage = retrievalReport.errorMessage,
                diagnosticCodes = retrievalReport.diagnostics.map { diagnostic -> diagnostic.code }.distinct(),
                coreCount = selectedCoreResults.size,
                queryCount = selectedQueryResults.size,
                canonicalRevision = retrievalReport.canonicalRevision,
                canonicalSourceHash = retrievalReport.canonicalSourceHash,
                recallProjectionHash = retrievalReport.recallProjectionHash,
                promptEstimatedTokens = renderedPrompt.estimatedTokens
            )
        )
        return PreparedMemoryContext(
            retrievedMemories = retrievedMemories,
            snapshot = turnSnapshot,
            historySnapshot = historySnapshot
        )
    }

    private suspend fun retrieveHistory(
        chatRoom: ChatRoomV2,
        query: String,
        recentContext: String?
    ): HistoryRecallSnapshot {
        val retriever = chatHistoryRetriever ?: return HistoryRecallSnapshot()
        if (query.isBlank()) return HistoryRecallSnapshot()
        return runCatching {
            retriever.retrieve(
                ChatHistoryRetrievalRequest(
                    currentChatId = chatRoom.id,
                    query = query,
                    recentContext = recentContext
                )
            )
        }.getOrElse {
            HistoryRecallSnapshot(
                mode = cn.nabr.chatwithchat.data.history.HistoryRecallMode.FAILED,
                errorCode = "history_retrieval_failed",
                diagnostics = listOf(cn.nabr.chatwithchat.data.history.HistoryRecallDiagnostic("history_retrieval_failed"))
            )
        }
    }

    override suspend fun getLongTermMarkdown(): String =
        memoryFileStore?.readLongTermMemory()?.getOrDefault("").orEmpty()

    override fun observeLongTermMarkdown(): Flow<String> {
        val fileStore = memoryFileStore ?: return flowOf("")
        return fileStore.longTermRevision
            .map { getLongTermMarkdown() }
            .distinctUntilChanged()
    }

    private fun buildLocalRecallQuery(latestUserMessage: MessageV2?): String = buildString {
        appendLine(latestUserMessage?.content.orEmpty().trimForMemoryContext())
        latestUserMessage?.attachments.orEmpty().forEach { attachment ->
            appendLine("${attachment.resolvedDisplayName} ${attachment.mimeType}".trim())
        }
    }.trim().take(MAX_LOCAL_RECALL_QUERY_LENGTH)

    private fun buildLocalRecentContext(
        chatRoom: ChatRoomV2,
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>
    ): String? = buildMemoryMessages(chatRoom, userMessages, assistantMessages)
        .dropLast(1)
        .takeLast(LOCAL_RECALL_RECENT_MESSAGE_COUNT)
        .joinToString(separator = "\n") { message ->
            "${message.role}: ${message.content.trimForMemoryContext().take(MAX_LOCAL_RECENT_MESSAGE_LENGTH)}"
        }
        .trim()
        .takeIf { it.isNotBlank() }

    private fun String.trimForMemoryContext(): String = trim().take(MAX_CONTEXT_MESSAGE_LENGTH)

    private fun List<MemoryRetrievalResult>.selectedBy(
        facts: List<ModelVisibleMemoryFact>
    ): List<MemoryRetrievalResult> {
        val remaining = toMutableList()
        return facts.mapNotNull { fact ->
            val normalized = normalizeExactMemoryText(fact.text)
            val index = remaining.indexOfFirst { result -> normalizeExactMemoryText(result.text) == normalized }
            if (index < 0) null else remaining.removeAt(index)
        }
    }

    private fun memoryRecallKey(chatId: Int, userMessages: List<MessageV2>): MemoryRecallKey = MemoryRecallKey(
        chatId = userMessages.lastOrNull()?.chatId?.takeIf { it > 0 } ?: chatId,
        turnNumber = userMessages.size,
        userMessageId = userMessages.lastOrNull()?.id?.takeIf { it > 0 }
    )

    private fun recordMemoryRecall(key: MemoryRecallKey, recall: MemoryRecallTrace) {
        promptTraceStore?.recordMemoryRecall(
            chatId = key.chatId,
            turnNumber = key.turnNumber,
            userMessageId = key.userMessageId,
            recall = recall
        )
    }

    private fun logWarning(message: String, throwable: Throwable) {
        runCatching { Log.w(TAG, message, throwable) }
    }

    companion object {
        private const val TAG = "MemoryRepository"
        private const val MAX_CANDIDATE_MEMORIES = 24
        private const val MAX_QUERY_MEMORIES = 3
        private const val QUERY_RECALL_TOKEN_BUDGET = 300
        private const val MAX_CONTEXT_MESSAGE_LENGTH = 1200
        private const val LOCAL_RECALL_RECENT_MESSAGE_COUNT = 6
        private const val MAX_LOCAL_RECALL_QUERY_LENGTH = 2_000
        private const val MAX_LOCAL_RECENT_MESSAGE_LENGTH = 600

        private data class MemoryRecallKey(
            val chatId: Int,
            val turnNumber: Int,
            val userMessageId: Int?
        )
    }
}
