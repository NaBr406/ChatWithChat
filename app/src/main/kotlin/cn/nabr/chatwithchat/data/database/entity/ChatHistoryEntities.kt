package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_history_projection",
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["turn_key"], unique = true),
        Index(value = ["chat_id", "user_message_id"], unique = true),
        Index(value = ["eligibility_state"]),
        Index(value = ["updated_at"])
    ]
)
data class ChatHistoryProjectionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "projection_id")
    val projectionId: Long = 0,
    @ColumnInfo(name = "turn_key")
    val turnKey: String,
    @ColumnInfo(name = "chat_id")
    val chatId: Int,
    @ColumnInfo(name = "user_message_id")
    val userMessageId: Int,
    @ColumnInfo(name = "assistant_message_id")
    val assistantMessageId: Int,
    @ColumnInfo(name = "assistant_platform_uid")
    val assistantPlatformUid: String,
    val title: String,
    @ColumnInfo(name = "user_content")
    val userContent: String,
    @ColumnInfo(name = "assistant_content")
    val assistantContent: String,
    @ColumnInfo(name = "search_terms")
    val searchTerms: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "projection_version")
    val projectionVersion: Int,
    @ColumnInfo(name = "eligibility_state")
    val eligibilityState: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "chat_history_index_queue",
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["chat_id"]), Index(value = ["requested_at"])]
)
data class ChatHistoryIndexQueueEntity(
    @PrimaryKey
    @ColumnInfo(name = "turn_key")
    val turnKey: String,
    @ColumnInfo(name = "chat_id")
    val chatId: Int,
    @ColumnInfo(name = "user_message_id")
    val userMessageId: Int,
    @ColumnInfo(name = "operation_hint")
    val operationHint: String,
    @ColumnInfo(name = "requested_at")
    val requestedAt: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0
)

@Entity(tableName = "chat_history_backfill_checkpoint")
data class ChatHistoryBackfillCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "checkpoint_id")
    val checkpointId: String,
    @ColumnInfo(name = "last_chat_id")
    val lastChatId: Int? = null,
    @ColumnInfo(name = "last_user_message_id")
    val lastUserMessageId: Int? = null,
    @ColumnInfo(name = "projection_version")
    val projectionVersion: Int,
    val status: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(tableName = "chat_history_index_state")
data class ChatHistoryIndexStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "state_id")
    val stateId: String,
    @ColumnInfo(name = "projection_generation")
    val projectionGeneration: Long,
    @ColumnInfo(name = "projection_hash")
    val projectionHash: String? = null,
    @ColumnInfo(name = "vector_published_generation")
    val vectorPublishedGeneration: Long? = null,
    @ColumnInfo(name = "vector_status")
    val vectorStatus: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "chat_history_embedding_cache",
    primaryKeys = ["turn_key", "content_hash", "descriptor_hash"],
    indices = [Index(value = ["content_hash"]), Index(value = ["descriptor_hash"])]
)
data class ChatHistoryEmbeddingCacheEntity(
    @ColumnInfo(name = "turn_key")
    val turnKey: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "descriptor_hash")
    val descriptorHash: String,
    val embedding: ByteArray,
    val dimension: Int,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(tableName = "chat_history_vector_snapshot")
data class ChatHistoryVectorSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String,
    val generation: Long,
    @ColumnInfo(name = "projection_hash")
    val projectionHash: String,
    @ColumnInfo(name = "descriptor_hash")
    val descriptorHash: String,
    val dimension: Int,
    @ColumnInfo(name = "chunker_version")
    val chunkerVersion: String,
    @ColumnInfo(name = "index_schema_version")
    val indexSchemaVersion: Int,
    @ColumnInfo(name = "expected_count")
    val expectedCount: Int,
    @ColumnInfo(name = "published_at")
    val publishedAt: Long
)

@Entity(
    tableName = "chat_history_vector_entry",
    primaryKeys = ["snapshot_id", "turn_key"],
    indices = [Index(value = ["turn_key"])]
)
data class ChatHistoryVectorEntryEntity(
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String,
    @ColumnInfo(name = "turn_key")
    val turnKey: String,
    val generation: Long,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "descriptor_hash")
    val descriptorHash: String,
    val dimension: Int,
    val embedding: ByteArray
)
