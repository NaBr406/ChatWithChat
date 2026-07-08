package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_chunk",
    foreignKeys = [
        ForeignKey(
            entity = MemoryDocument::class,
            parentColumns = ["source_path"],
            childColumns = ["source_path"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["source_path"]),
        Index(value = ["entry_id"]),
        Index(value = ["type"]),
        Index(value = ["sensitivity"]),
        Index(value = ["indexed_at"])
    ]
)
data class MemoryChunk(
    @PrimaryKey
    @ColumnInfo(name = "chunk_id")
    val chunkId: String,

    @ColumnInfo(name = "source_path")
    val sourcePath: String,

    @ColumnInfo(name = "chunk_index")
    val chunkIndex: Int,

    @ColumnInfo(name = "heading")
    val heading: String?,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "entry_id")
    val entryId: String?,

    @ColumnInfo(name = "type")
    val type: String?,

    @ColumnInfo(name = "sensitivity")
    val sensitivity: String?,

    @ColumnInfo(name = "source")
    val source: String?,

    @ColumnInfo(name = "chat_id")
    val chatId: Int?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "indexed_at")
    val indexedAt: Long
)
