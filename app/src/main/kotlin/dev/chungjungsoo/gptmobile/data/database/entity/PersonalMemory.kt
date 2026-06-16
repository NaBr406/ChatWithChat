package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "personal_memory",
    indices = [
        Index(value = ["status"]),
        Index(value = ["type"]),
        Index(value = ["sensitivity"])
    ]
)
data class PersonalMemory(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "memory_id")
    val id: Int = 0,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "scope")
    val scope: String = MemoryScope.GLOBAL,

    @ColumnInfo(name = "tags")
    val tags: List<String> = emptyList(),

    @ColumnInfo(name = "importance")
    val importance: Float = 0.5f,

    @ColumnInfo(name = "confidence")
    val confidence: Float = 0.7f,

    @ColumnInfo(name = "source")
    val source: String = MemorySource.INFERRED,

    @ColumnInfo(name = "sensitivity")
    val sensitivity: String = MemorySensitivity.NORMAL,

    @ColumnInfo(name = "status")
    val status: String = MemoryStatus.ACTIVE,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "last_accessed_at")
    val lastAccessedAt: Long? = null,

    @ColumnInfo(name = "expires_at")
    val expiresAt: Long? = null
)
