package dev.chungjungsoo.gptmobile.data.repository

import android.util.Log
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.memory.MemoryCompletedTurnInput
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpus
import dev.chungjungsoo.gptmobile.data.memory.MemoryDailyDistillationScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryFilePaths
import dev.chungjungsoo.gptmobile.data.memory.MemoryFileStore
import dev.chungjungsoo.gptmobile.data.memory.MemoryPromptBuilder
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetrievalRequest
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetrievalStrategy
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetriever
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchCoordinator
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnRecordingResult
import dev.chungjungsoo.gptmobile.data.memory.PreparedMemoryContext
import dev.chungjungsoo.gptmobile.data.memory.buildMemoryMessages
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
    private val memoryDailyDistillationScheduler: MemoryDailyDistillationScheduler? = null
) : MemoryRepository {

    override suspend fun onMemoryEnabledChanged(enabled: Boolean) {
        memoryTurnBatchScheduler?.onMemoryEnabledChanged(enabled)
        if (enabled) memoryDailyDistillationScheduler?.ensurePlanningJobs()
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
        val retriever = memoryRetriever ?: return PreparedMemoryContext()
        val query = buildLocalRecallQuery(userMessages.lastOrNull())
        if (query.isBlank()) return PreparedMemoryContext()
        val recentContext = buildLocalRecentContext(chatRoom, userMessages, assistantMessages)
        val retrievedMemories = retriever.retrieve(
            MemoryRetrievalRequest(
                corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
                query = query,
                recentContext = recentContext,
                limit = MAX_SELECTED_MEMORIES,
                candidateLimit = MAX_CANDIDATE_MEMORIES,
                tokenBudget = MEMORY_RECALL_TOKEN_BUDGET,
                includePrivate = true,
                strategy = MemoryRetrievalStrategy.HYBRID
            )
        ).getOrElse { throwable ->
            logWarning("Local memory retrieval failed; continuing without memory: ${throwable.message}", throwable)
            emptyList()
        }.filter { memory -> memory.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME }
        return PreparedMemoryContext(
            retrievedMemories = retrievedMemories,
            prompt = memoryPromptBuilder.buildRetrieved(retrievedMemories)
        )
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

    private fun logWarning(message: String, throwable: Throwable) {
        runCatching { Log.w(TAG, message, throwable) }
    }

    companion object {
        private const val TAG = "MemoryRepository"
        private const val MAX_CANDIDATE_MEMORIES = 24
        private const val MAX_SELECTED_MEMORIES = 8
        private const val MEMORY_RECALL_TOKEN_BUDGET = 900
        private const val MAX_CONTEXT_MESSAGE_LENGTH = 1200
        private const val LOCAL_RECALL_RECENT_MESSAGE_COUNT = 6
        private const val MAX_LOCAL_RECALL_QUERY_LENGTH = 2_000
        private const val MAX_LOCAL_RECENT_MESSAGE_LENGTH = 600
    }
}
