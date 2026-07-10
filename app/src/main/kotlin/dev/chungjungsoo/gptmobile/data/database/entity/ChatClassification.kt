package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Legacy classification data retained for non-destructive database upgrades only. */
@Entity(tableName = "chat_classification")
data class ChatClassification(
    @PrimaryKey
    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "mode")
    val mode: String,

    @ColumnInfo(name = "intent")
    val intent: String,

    @ColumnInfo(name = "memory_needs")
    val memoryNeeds: List<String> = emptyList(),

    @ColumnInfo(name = "domains")
    val domains: List<String> = emptyList(),

    @ColumnInfo(name = "entities")
    val entities: List<String> = emptyList(),

    @ColumnInfo(name = "emotional_tone")
    val emotionalTone: String? = null,

    @ColumnInfo(name = "should_use_memories")
    val shouldUseMemories: Boolean,

    @ColumnInfo(name = "should_learn_memories")
    val shouldLearnMemories: Boolean,

    @ColumnInfo(name = "sensitivity")
    val sensitivity: String,

    @ColumnInfo(name = "confidence")
    val confidence: Float,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "raw_model_json")
    val rawModelJson: String? = null
)
