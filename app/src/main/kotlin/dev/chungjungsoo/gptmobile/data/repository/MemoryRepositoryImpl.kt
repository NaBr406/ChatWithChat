package dev.chungjungsoo.gptmobile.data.repository

import android.util.Log
import dev.chungjungsoo.gptmobile.data.database.dao.PersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryCodec
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryEntry
import dev.chungjungsoo.gptmobile.data.memory.MemoryCompletedTurnInput
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpus
import dev.chungjungsoo.gptmobile.data.memory.MemoryFilePaths
import dev.chungjungsoo.gptmobile.data.memory.MemoryFileStore
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexRebuilder
import dev.chungjungsoo.gptmobile.data.memory.MemoryPromptBuilder
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetrievalRequest
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetriever
import dev.chungjungsoo.gptmobile.data.memory.MemoryStatus
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchCoordinator
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnRecordingResult
import dev.chungjungsoo.gptmobile.data.memory.PreparedMemoryContext
import dev.chungjungsoo.gptmobile.data.memory.buildMemoryMessages
import dev.chungjungsoo.gptmobile.data.memory.toMarkdownMemoryEntry
import java.io.File

class MemoryRepositoryImpl(
    // Upgrade-only source. Runtime learning and recall never write or search this table.
    private val personalMemoryDao: PersonalMemoryDao,
    private val memoryPromptBuilder: MemoryPromptBuilder,
    private val memoryRetriever: MemoryRetriever? = null,
    private val memoryFileStore: MemoryFileStore? = null,
    private val markdownMemoryCodec: MarkdownMemoryCodec? = null,
    private val memoryIndexRebuilder: MemoryIndexRebuilder? = null,
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
                includePrivate = true
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

    override suspend fun migrateActiveMemoriesToMarkdown(): Int {
        val fileStore = memoryFileStore ?: return 0
        val codec = markdownMemoryCodec ?: return 0

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
                filesToRebuild += fileStore
                    .replaceLongTermMemory(codec.renderLongTerm(repairedExistingEntries))
                    .getOrThrow()
                    .file
            }

            val existingIds = repairedExistingEntries.map { it.id }.toSet()
            val existingKeys = repairedExistingEntries.map { it.memoryDuplicateKey() }.toSet()
            val entriesToAppend = activeMemories
                .mapNotNull { memory -> memory.toMigratedMarkdownMemoryEntryOrNull() }
                .deduplicatedMarkdownEntries()
                .filterNot { entry -> entry.id in existingIds || entry.memoryDuplicateKey() in existingKeys }

            if (entriesToAppend.isNotEmpty()) {
                filesToRebuild += fileStore
                    .appendLongTermMemory(codec.renderLongTermAppend(entriesToAppend))
                    .getOrThrow()
            }
            filesToRebuild.forEach { file ->
                memoryIndexRebuilder?.rebuildFile(file)?.onFailure { throwable ->
                    logWarning("Markdown memory migration index rebuild failed: ${throwable.message}", throwable)
                }
            }
            entriesToAppend.size
        }.getOrElse { throwable ->
            logWarning("Markdown memory migration failed: ${throwable.message}", throwable)
            0
        }
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

    private fun PersonalMemory.toMigratedMarkdownMemoryEntryOrNull(): MarkdownMemoryEntry? {
        if (isClassifierFallbackMemory()) return null
        val text = markdownMigrationTextOrNull() ?: return null
        return toMarkdownMemoryEntry(text = text)
    }

    private fun PersonalMemory.markdownMigrationTextOrNull(): String? {
        val candidates = listOf(summary, recallText).mapNotNull { text -> text.cleanMarkdownMemoryTextOrNull() }
        return candidates.firstOrNull { text ->
            !text.hasRawUserStatementPrefix() && text.length <= MAX_MIGRATED_MARKDOWN_TEXT_LENGTH
        } ?: candidates.firstOrNull { text -> text.length <= MAX_MIGRATED_MARKDOWN_TEXT_LENGTH }
    }

    private fun PersonalMemory.isClassifierFallbackMemory(): Boolean =
        tags.any { tag -> tag.equals("classifier_fallback", ignoreCase = true) }

    private fun MarkdownMemoryEntry.repairedLongTermEntryOrNull(
        classifierFallbackMarkdownIds: Set<String>
    ): MarkdownMemoryEntry? {
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
            usedIds.add(entry.id) && usedKeys.add(key)
        }
    }

    private fun MarkdownMemoryEntry.memoryDuplicateKey(): String =
        "${type.normalizedMemoryType()}|${text.normalizedMemoryKey()}"

    private fun String.normalizedMemoryKey(): String = trim().lowercase().replace(Regex("\\s+"), " ")

    private fun String.normalizedMemoryType(): String {
        val token = trim().lowercase().replace('-', '_').replace(' ', '_')
        return when (token) {
            "communication", "communication_preference", "communication_preferences", "style", "tone",
            "tone_preference", "preference", "preferences", "user_preference" -> "communication_style"
            "interests" -> "interest"
            "important_events", "event", "life_event" -> "important_event"
            "important_people", "person", "people", "relationship" -> "important_person"
            "emotional_patterns", "emotion", "emotional" -> "emotional_pattern"
            "boundaries", "limit", "limits" -> "boundary"
            "life", "context", "background" -> "life_context"
            "recurring_themes", "theme" -> "recurring_theme"
            "productivity", "productivity_preference", "task_preference", "workflow_preference" ->
                "light_productivity_preference"
            "profile", "user_profile", "personal_profile" -> "stable_profile"
            else -> token.takeIf { it in ALLOWED_TYPES } ?: "stable_profile"
        }
    }

    private fun String.trimForMemoryContext(): String = trim().take(MAX_CONTEXT_MESSAGE_LENGTH)

    private fun String.cleanMarkdownMemoryTextOrNull(): String? {
        val cleaned = trim()
            .replace(Regex("^${Regex.escape(RAW_USER_STATEMENT_PREFIX)}\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun String.hasRawUserStatementPrefix(): Boolean =
        trimStart().startsWith(RAW_USER_STATEMENT_PREFIX, ignoreCase = true)

    private fun logWarning(message: String, throwable: Throwable) {
        runCatching { Log.w(TAG, message, throwable) }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    companion object {
        private const val TAG = "MemoryRepository"
        private const val MAX_CANDIDATE_MEMORIES = 24
        private const val MAX_SELECTED_MEMORIES = 8
        private const val MEMORY_RECALL_TOKEN_BUDGET = 900
        private const val MAX_CONTEXT_MESSAGE_LENGTH = 1200
        private const val MAX_MIGRATED_MARKDOWN_TEXT_LENGTH = 360
        private const val LOCAL_RECALL_RECENT_MESSAGE_COUNT = 6
        private const val MAX_LOCAL_RECALL_QUERY_LENGTH = 2_000
        private const val MAX_LOCAL_RECENT_MESSAGE_LENGTH = 600
        private const val RAW_USER_STATEMENT_PREFIX = "The user said:"

        private val ALLOWED_TYPES = setOf(
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
    }
}
