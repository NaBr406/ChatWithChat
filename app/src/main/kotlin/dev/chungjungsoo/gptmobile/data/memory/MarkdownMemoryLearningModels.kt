package dev.chungjungsoo.gptmobile.data.memory

import kotlinx.serialization.Serializable

/** Serialized by releases that predate turn batching; retained only to drain upgrade jobs. */
@Serializable
data class MarkdownMemoryLearningJobPayload(
    val chatId: Int,
    val chatTitle: String,
    val learningKey: String,
    val recentMessages: List<MemoryConversationMessage>,
    val createdAt: Long
)

/** Serialized by releases that predate unified compaction scheduling; retained for upgrade recovery. */
@Serializable
data class MemoryCompactionFlushJobPayload(
    val chatId: Int,
    val platformUid: String,
    val omittedTurnCount: Int,
    val messages: List<MemoryConversationMessage>,
    val createdAt: Long
)
