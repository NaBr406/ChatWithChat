package dev.chungjungsoo.gptmobile.data.memory

import android.util.Log
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MarkdownMemoryLearningService(
    private val memoryFileStore: MemoryFileStore,
    private val markdownMemoryCodec: MarkdownMemoryCodec,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val memoryIndexRebuilder: MemoryIndexRebuilder,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun learnFromTurn(
        chatRoom: ChatRoomV2,
        recentMessages: List<MemoryConversationMessage>,
        existingRoomMemories: List<PersonalMemory>,
        memoryIntelligence: MemoryIntelligence,
        preferredPlatform: PlatformV2?,
        learningIdempotencyKey: String?
    ): MarkdownMemoryLearningResult {
        if (recentMessages.isEmpty()) {
            return MarkdownMemoryLearningResult.skipped(
                status = MarkdownMemoryLearningResult.STATUS_SKIPPED_NO_NOTES,
                reason = "No recent messages"
            )
        }

        val learningKey = learningIdempotencyKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackLearningKey(chatRoom.id, recentMessages)
        val payload = MarkdownMemoryLearningJobPayload(
            chatId = chatRoom.id,
            chatTitle = chatRoom.title,
            learningKey = learningKey,
            recentMessages = recentMessages.forMarkdownLearning(),
            createdAt = now()
        )
        val job = maintenanceScheduler.enqueue(
            type = MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
            idempotencyKey = "markdown_learning:$learningKey",
            payloadJson = json.encodeToString(payload)
        )

        when (job.status) {
            MemoryMaintenanceJobStatus.SUCCEEDED -> {
                return MarkdownMemoryLearningResult.skipped(
                    status = MarkdownMemoryLearningResult.STATUS_SKIPPED_DUPLICATE_JOB,
                    jobId = job.jobId,
                    reason = "Learning job already succeeded"
                )
            }
            MemoryMaintenanceJobStatus.RUNNING -> {
                return MarkdownMemoryLearningResult.skipped(
                    status = MarkdownMemoryLearningResult.STATUS_SKIPPED_ALREADY_RUNNING,
                    jobId = job.jobId,
                    reason = "Learning job is already running"
                )
            }
            MemoryMaintenanceJobStatus.FAILED_TERMINAL,
            MemoryMaintenanceJobStatus.DISMISSED -> {
                return MarkdownMemoryLearningResult.skipped(
                    status = MarkdownMemoryLearningResult.STATUS_FAILED_TERMINAL,
                    jobId = job.jobId,
                    reason = "Learning job is ${job.status}"
                )
            }
        }

        val runningJob = maintenanceScheduler.markRunning(job)
        return runCatching {
            executeLearningJob(
                runningJob = runningJob,
                chatRoom = chatRoom,
                learningKey = learningKey,
                recentMessages = payload.recentMessages,
                existingRoomMemories = existingRoomMemories,
                memoryIntelligence = memoryIntelligence,
                preferredPlatform = preferredPlatform
            )
        }.getOrElse { throwable ->
            runCatching { Log.w(TAG, "Markdown memory learning failed", throwable) }
            maintenanceScheduler.markFailedRetryable(
                job = runningJob,
                error = throwable.message ?: throwable.javaClass.simpleName
            )
            MarkdownMemoryLearningResult.skipped(
                status = MarkdownMemoryLearningResult.STATUS_FAILED_RETRYABLE,
                jobId = runningJob.jobId,
                reason = throwable.message ?: throwable.javaClass.simpleName
            )
        }
    }

    suspend fun retryLearningJob(
        job: MemoryMaintenanceJob,
        existingRoomMemories: List<PersonalMemory>,
        memoryIntelligence: MemoryIntelligence,
        preferredPlatform: PlatformV2?
    ): MarkdownMemoryLearningResult {
        val payload = runCatching { json.decodeFromString<MarkdownMemoryLearningJobPayload>(job.payloadJson) }
            .getOrElse { throwable ->
                maintenanceScheduler.markFailedTerminal(job, "Invalid markdown learning payload: ${throwable.message}")
                return MarkdownMemoryLearningResult.skipped(
                    status = MarkdownMemoryLearningResult.STATUS_FAILED_TERMINAL,
                    jobId = job.jobId,
                    reason = "Invalid markdown learning payload"
                )
            }
        return executeRunnableJob(
            job = job,
            chatRoom = ChatRoomV2(id = payload.chatId, title = payload.chatTitle, enabledPlatform = emptyList()),
            learningKey = payload.learningKey,
            recentMessages = payload.recentMessages,
            existingRoomMemories = existingRoomMemories,
            memoryIntelligence = memoryIntelligence,
            preferredPlatform = preferredPlatform
        )
    }

    suspend fun retryCompactionFlushJob(
        job: MemoryMaintenanceJob,
        existingRoomMemories: List<PersonalMemory>,
        memoryIntelligence: MemoryIntelligence,
        preferredPlatform: PlatformV2?
    ): MarkdownMemoryLearningResult {
        val payload = runCatching { json.decodeFromString<MemoryCompactionFlushJobPayload>(job.payloadJson) }
            .getOrElse { throwable ->
                maintenanceScheduler.markFailedTerminal(job, "Invalid compaction flush payload: ${throwable.message}")
                return MarkdownMemoryLearningResult.skipped(
                    status = MarkdownMemoryLearningResult.STATUS_FAILED_TERMINAL,
                    jobId = job.jobId,
                    reason = "Invalid compaction flush payload"
                )
            }
        return executeRunnableJob(
            job = job,
            chatRoom = ChatRoomV2(id = payload.chatId, title = "Compaction flush", enabledPlatform = emptyList()),
            learningKey = "compaction_${job.idempotencyKey}",
            recentMessages = payload.messages,
            existingRoomMemories = existingRoomMemories,
            memoryIntelligence = memoryIntelligence,
            preferredPlatform = preferredPlatform
        )
    }

    private suspend fun executeRunnableJob(
        job: MemoryMaintenanceJob,
        chatRoom: ChatRoomV2,
        learningKey: String,
        recentMessages: List<MemoryConversationMessage>,
        existingRoomMemories: List<PersonalMemory>,
        memoryIntelligence: MemoryIntelligence,
        preferredPlatform: PlatformV2?
    ): MarkdownMemoryLearningResult {
        when (job.status) {
            MemoryMaintenanceJobStatus.SUCCEEDED -> {
                return MarkdownMemoryLearningResult.skipped(
                    status = MarkdownMemoryLearningResult.STATUS_SKIPPED_DUPLICATE_JOB,
                    jobId = job.jobId,
                    reason = "Learning job already succeeded"
                )
            }
            MemoryMaintenanceJobStatus.RUNNING -> {
                return MarkdownMemoryLearningResult.skipped(
                    status = MarkdownMemoryLearningResult.STATUS_SKIPPED_ALREADY_RUNNING,
                    jobId = job.jobId,
                    reason = "Learning job is already running"
                )
            }
            MemoryMaintenanceJobStatus.FAILED_TERMINAL,
            MemoryMaintenanceJobStatus.DISMISSED -> {
                return MarkdownMemoryLearningResult.skipped(
                    status = MarkdownMemoryLearningResult.STATUS_FAILED_TERMINAL,
                    jobId = job.jobId,
                    reason = "Learning job is ${job.status}"
                )
            }
        }

        val runningJob = maintenanceScheduler.markRunning(job)
        return runCatching {
            executeLearningJob(
                runningJob = runningJob,
                chatRoom = chatRoom,
                learningKey = learningKey,
                recentMessages = recentMessages,
                existingRoomMemories = existingRoomMemories,
                memoryIntelligence = memoryIntelligence,
                preferredPlatform = preferredPlatform
            )
        }.getOrElse { throwable ->
            runCatching { Log.w(TAG, "Persisted markdown memory job failed", throwable) }
            maintenanceScheduler.markFailedRetryable(
                job = runningJob,
                error = throwable.message ?: throwable.javaClass.simpleName
            )
            MarkdownMemoryLearningResult.skipped(
                status = MarkdownMemoryLearningResult.STATUS_FAILED_RETRYABLE,
                jobId = runningJob.jobId,
                reason = throwable.message ?: throwable.javaClass.simpleName
            )
        }
    }

    private suspend fun executeLearningJob(
        runningJob: MemoryMaintenanceJob,
        chatRoom: ChatRoomV2,
        learningKey: String,
        recentMessages: List<MemoryConversationMessage>,
        existingRoomMemories: List<PersonalMemory>,
        memoryIntelligence: MemoryIntelligence,
        preferredPlatform: PlatformV2?
    ): MarkdownMemoryLearningResult {
        val snapshot = memoryFileStore.ensureStore().getOrThrow()
        val existingMarkdownEntries = readExistingMarkdownEntries()
        val request = MarkdownMemoryLearningRequest(
            chatId = chatRoom.id,
            chatTitle = chatRoom.title,
            recentMessages = recentMessages.forMarkdownLearning(),
            existingMarkdownMemories = markdownEntriesToExistingMemories(existingMarkdownEntries),
            existingRoomMemories = roomMemoriesToExistingMemories(existingRoomMemories)
        )
        val proposal = memoryIntelligence.proposeMarkdownMemoryWrites(
            request = request,
            preferredPlatform = preferredPlatform
        ) ?: run {
            maintenanceScheduler.markFailedRetryable(
                job = runningJob,
                error = "Markdown memory proposal unavailable or invalid"
            )
            return MarkdownMemoryLearningResult.skipped(
                status = MarkdownMemoryLearningResult.STATUS_SKIPPED_NO_PROPOSAL,
                jobId = runningJob.jobId,
                reason = "Proposal unavailable or invalid"
            )
        }

        val existingKeys = buildExistingKeys(existingMarkdownEntries, existingRoomMemories)
        val dailyEntries = proposal.dailyNotes.normalizedEntries(
            prefix = "day",
            learningKey = learningKey,
            chatId = chatRoom.id,
            defaultSection = DAILY_SECTION,
            existingKeys = existingKeys
        )
        val longTermEntries = proposal.longTermUpdates.normalizedEntries(
            prefix = "mem",
            learningKey = learningKey,
            chatId = null,
            defaultSection = null,
            existingKeys = existingKeys
        )
        val duplicateCount = proposal.dailyNotes.size + proposal.longTermUpdates.size - dailyEntries.size - longTermEntries.size
        val affectedFiles = mutableSetOf<File>()

        if (dailyEntries.isNotEmpty()) {
            val dailyFile = memoryFileStore.appendDailyNote(
                text = markdownMemoryCodec.renderDailyAppend(dailyEntries),
                date = LocalDate.now(clock)
            ).getOrThrow()
            affectedFiles += dailyFile
        }
        if (longTermEntries.isNotEmpty()) {
            val longTermFile = memoryFileStore.appendLongTermMemory(
                markdownMemoryCodec.renderLongTermAppend(longTermEntries)
            ).getOrThrow()
            affectedFiles += longTermFile
        }
        if (affectedFiles.isEmpty() && duplicateCount > 0) {
            affectedFiles += snapshot.longTermMemoryFile
            affectedFiles += snapshot.todayMemoryFile
        }
        affectedFiles.forEach { file -> memoryIndexRebuilder.rebuildFile(file).getOrThrow() }

        maintenanceScheduler.markSucceeded(runningJob)
        return MarkdownMemoryLearningResult(
            status = if (dailyEntries.isNotEmpty() || longTermEntries.isNotEmpty()) {
                MarkdownMemoryLearningResult.STATUS_APPLIED
            } else {
                MarkdownMemoryLearningResult.STATUS_SKIPPED_NO_NOTES
            },
            dailyNotesWritten = dailyEntries.size,
            longTermUpdatesWritten = longTermEntries.size,
            duplicateCount = duplicateCount.coerceAtLeast(0),
            jobId = runningJob.jobId
        )
    }

    private fun readExistingMarkdownEntries(): List<MarkdownMemoryEntry> =
        memoryFileStore.listMemoryFiles().getOrDefault(emptyList())
            .flatMap { file ->
                memoryFileStore.readMemoryFile(file)
                    .map { markdown -> markdownMemoryCodec.parse(markdown).entries }
                    .getOrDefault(emptyList())
            }

    private fun markdownEntriesToExistingMemories(
        entries: List<MarkdownMemoryEntry>
    ): List<MarkdownMemoryLearningExistingMemory> =
        entries.map { entry ->
            MarkdownMemoryLearningExistingMemory(
                text = entry.text,
                type = entry.type,
                sensitivity = entry.sensitivity,
                source = entry.source
            )
        }.take(MAX_EXISTING_MEMORIES)

    private fun roomMemoriesToExistingMemories(
        memories: List<PersonalMemory>
    ): List<MarkdownMemoryLearningExistingMemory> =
        memories.map { memory ->
            MarkdownMemoryLearningExistingMemory(
                text = memory.recallText,
                type = memory.type,
                sensitivity = memory.sensitivity,
                source = memory.source
            )
        }.take(MAX_EXISTING_MEMORIES)

    private fun buildExistingKeys(
        existingMarkdownEntries: List<MarkdownMemoryEntry>,
        existingRoomMemories: List<PersonalMemory>
    ): MutableSet<String> = buildSet {
        existingMarkdownEntries.forEach { entry ->
            add(entry.memoryKey())
            add(entry.text.normalizedTextKey())
        }
        existingRoomMemories.forEach { memory ->
            add(memory.memoryKey())
            add(memory.summary.normalizedTextKey())
            add(memory.recallText.normalizedTextKey())
        }
    }.toMutableSet()

    private fun List<MarkdownMemoryLearningNote>.normalizedEntries(
        prefix: String,
        learningKey: String,
        chatId: Int?,
        defaultSection: String?,
        existingKeys: MutableSet<String>
    ): List<MarkdownMemoryEntry> = mapNotNull { note ->
        note.normalizedOrNull(prefix, learningKey, chatId, defaultSection)
            ?.takeUnless { entry ->
                val keys = listOf(entry.memoryKey(), entry.text.normalizedTextKey())
                val duplicate = keys.any { it in existingKeys }
                if (!duplicate) {
                    existingKeys += keys
                }
                duplicate
            }
    }

    private fun MarkdownMemoryLearningNote.normalizedOrNull(
        prefix: String,
        learningKey: String,
        chatId: Int?,
        defaultSection: String?
    ): MarkdownMemoryEntry? {
        val normalizedText = text
            .trim()
            .removeRawUserStatementPrefix()
            .replace(Regex("\\s+"), " ")
            .take(MAX_NOTE_LENGTH)
        if (normalizedText.isBlank()) return null
        val normalizedType = type.normalizedMemoryType()
        val normalizedSensitivity = sensitivity.normalizedMemorySensitivity()
        val normalizedSource = source.normalizedMemorySource()
        val timestamp = now()
        return MarkdownMemoryEntry(
            id = "${prefix}_${entryHash(learningKey, normalizedType, normalizedText)}",
            text = normalizedText,
            type = normalizedType,
            sensitivity = normalizedSensitivity,
            source = normalizedSource,
            chatId = chatId,
            createdAt = timestamp,
            updatedAt = timestamp,
            section = defaultSection
        )
    }

    private fun MarkdownMemoryEntry.memoryKey(): String =
        "${type.normalizedMemoryType()}|${text.normalizedTextKey()}"

    private fun PersonalMemory.memoryKey(): String =
        "${type.normalizedMemoryType()}|${recallText.normalizedTextKey()}"

    private fun String.normalizedTextKey(): String =
        trim().lowercase().replace(Regex("\\s+"), " ")

    private fun String.normalizedMemoryType(): String {
        val token = normalizedEnumToken()
        return when (token) {
            "communication", "communication_preference", "communication_preferences", "style", "tone", "tone_preference", "preference", "preferences", "user_preference" -> "communication_style"
            "project", "project_context", "work_project" -> "project_context"
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

    private fun String.normalizedEnumToken(): String = trim()
        .lowercase()
        .replace('-', '_')
        .replace(' ', '_')

    private fun String.removeRawUserStatementPrefix(): String =
        replace(Regex("^${Regex.escape(RAW_USER_STATEMENT_PREFIX)}\\s*", RegexOption.IGNORE_CASE), "")

    private fun List<MemoryConversationMessage>.forMarkdownLearning(): List<MemoryConversationMessage> =
        takeLast(MAX_MARKDOWN_MESSAGES).map { message ->
            message.copy(content = message.content.trim().take(MAX_MESSAGE_LENGTH))
        }

    private fun fallbackLearningKey(
        chatId: Int,
        recentMessages: List<MemoryConversationMessage>
    ): String = entryHash(
        chatId.toString(),
        recentMessages.size.toString(),
        recentMessages.joinToString("|") { "${it.role}:${it.content.take(120)}" }
    )

    private fun entryHash(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }.take(ID_HASH_LENGTH)
    }

    private fun now(): Long = clock.instant().epochSecond

    companion object {
        private const val TAG = "MarkdownMemoryLearning"
        private const val DAILY_SECTION = "Conversation Notes"
        private const val ID_HASH_LENGTH = 12
        private const val MAX_MARKDOWN_MESSAGES = 6
        private const val MAX_MESSAGE_LENGTH = 1200
        private const val MAX_NOTE_LENGTH = 500
        private const val MAX_EXISTING_MEMORIES = 60
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
    }
}
