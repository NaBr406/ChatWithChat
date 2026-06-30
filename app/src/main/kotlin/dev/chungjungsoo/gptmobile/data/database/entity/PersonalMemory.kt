package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personal_memory")
data class PersonalMemory(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "memory_id")
    val id: Int = 0,

    @ColumnInfo(name = "summary")
    val summary: String,

    @ColumnInfo(name = "details")
    val details: String? = null,

    @ColumnInfo(name = "recall_text")
    val recallText: String,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "scope")
    val scope: String,

    @ColumnInfo(name = "domains")
    val domains: List<String> = emptyList(),

    @ColumnInfo(name = "entities")
    val entities: List<String> = emptyList(),

    @ColumnInfo(name = "tags")
    val tags: List<String> = emptyList(),

    @ColumnInfo(name = "applicable_modes")
    val applicableModes: List<String> = emptyList(),

    @ColumnInfo(name = "avoid_modes")
    val avoidModes: List<String> = emptyList(),

    @ColumnInfo(name = "importance")
    val importance: Float = 0f,

    @ColumnInfo(name = "confidence")
    val confidence: Float = 0f,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "sensitivity")
    val sensitivity: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "evidence")
    val evidence: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "last_accessed_at")
    val lastAccessedAt: Long? = null,

    @ColumnInfo(name = "expires_at")
    val expiresAt: Long? = null
)
