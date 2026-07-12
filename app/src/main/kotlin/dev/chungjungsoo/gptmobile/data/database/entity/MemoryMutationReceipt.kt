package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_mutation_receipt",
    foreignKeys = [
        ForeignKey(
            entity = MemoryMutationGroup::class,
            parentColumns = ["group_id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["group_id", "source_path"], unique = true),
        Index(value = ["generation"]),
        Index(value = ["state"]),
        Index(value = ["source_path"])
    ]
)
data class MemoryMutationReceipt(
    @PrimaryKey
    @ColumnInfo(name = "receipt_id")
    val receiptId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "generation")
    val generation: Long,

    @ColumnInfo(name = "source_path")
    val sourcePath: String,

    @ColumnInfo(name = "base_source_hash")
    val baseSourceHash: String,

    @ColumnInfo(name = "target_source_hash")
    val targetSourceHash: String,

    @ColumnInfo(name = "staged_target_path")
    val stagedTargetPath: String,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,

    @ColumnInfo(name = "target_index_fingerprint")
    val targetIndexFingerprint: String?,

    @ColumnInfo(name = "attempts")
    val attempts: Int,

    @ColumnInfo(name = "last_error")
    val lastError: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "file_committed_at")
    val fileCommittedAt: Long?,

    @ColumnInfo(name = "indexed_at")
    val indexedAt: Long?,

    @ColumnInfo(name = "row_version", defaultValue = "0")
    val rowVersion: Long = 0
)
