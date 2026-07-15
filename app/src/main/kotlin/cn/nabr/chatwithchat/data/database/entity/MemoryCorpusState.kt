package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "memory_corpus_state",
    primaryKeys = ["corpus"],
    indices = [
        Index(value = ["source_path"]),
        Index(value = ["generation"]),
        Index(value = ["index_status"])
    ]
)
data class MemoryCorpusState(
    @ColumnInfo(name = "corpus")
    val corpus: String,

    @ColumnInfo(name = "source_path")
    val sourcePath: String,

    @ColumnInfo(name = "source_hash")
    val sourceHash: String,

    @ColumnInfo(name = "generation")
    val generation: Long,

    @ColumnInfo(name = "target_index_fingerprint")
    val targetIndexFingerprint: String?,

    @ColumnInfo(name = "index_status")
    val indexStatus: String,

    @ColumnInfo(name = "indexed_generation")
    val indexedGeneration: Long?,

    @ColumnInfo(name = "indexed_source_hash")
    val indexedSourceHash: String?,

    @ColumnInfo(name = "indexed_fingerprint")
    val indexedFingerprint: String?,

    @ColumnInfo(name = "latest_receipt_id")
    val latestReceiptId: String?,

    @ColumnInfo(name = "last_error")
    val lastError: String?,

    @ColumnInfo(name = "row_version", defaultValue = "0")
    val rowVersion: Long = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
