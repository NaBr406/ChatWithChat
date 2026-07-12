package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_mutation_group",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["semantic_job_id"]),
        Index(value = ["semantic_batch_id"]),
        Index(value = ["generation"]),
        Index(value = ["state"])
    ]
)
data class MemoryMutationGroup(
    @PrimaryKey
    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "generation")
    val generation: Long,

    @ColumnInfo(name = "semantic_job_id")
    val semanticJobId: String?,

    @ColumnInfo(name = "semantic_batch_id")
    val semanticBatchId: String?,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,

    @ColumnInfo(name = "last_error")
    val lastError: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,

    @ColumnInfo(name = "expected_receipt_count", defaultValue = "0")
    val expectedReceiptCount: Int = 0,

    @ColumnInfo(name = "row_version", defaultValue = "0")
    val rowVersion: Long = 0
)
