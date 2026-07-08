package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "memory_document",
    primaryKeys = ["source_path"],
    indices = [
        Index(value = ["scope"]),
        Index(value = ["indexed_at"])
    ]
)
data class MemoryDocument(
    @ColumnInfo(name = "source_path")
    val sourcePath: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "scope")
    val scope: String,

    @ColumnInfo(name = "content_hash")
    val contentHash: String,

    @ColumnInfo(name = "last_modified_at")
    val lastModifiedAt: Long,

    @ColumnInfo(name = "indexed_at")
    val indexedAt: Long
)
