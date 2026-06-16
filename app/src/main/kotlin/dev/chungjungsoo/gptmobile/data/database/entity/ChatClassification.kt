package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_classification")
data class ChatClassification(
    @PrimaryKey
    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "mode")
    val mode: String,

    @ColumnInfo(name = "domains")
    val domains: List<String> = emptyList(),

    @ColumnInfo(name = "memory_needs")
    val memoryNeeds: List<String> = emptyList(),

    @ColumnInfo(name = "entities")
    val entities: List<String> = emptyList(),

    @ColumnInfo(name = "sensitivity")
    val sensitivity: String = MemorySensitivity.NORMAL,

    @ColumnInfo(name = "confidence")
    val confidence: Float = 0.75f,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000
)
