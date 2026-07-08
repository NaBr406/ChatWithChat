package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_maintenance_job",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["status"]),
        Index(value = ["type"]),
        Index(value = ["next_run_at"])
    ]
)
data class MemoryMaintenanceJob(
    @PrimaryKey
    @ColumnInfo(name = "job_id")
    val jobId: String,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    @ColumnInfo(name = "attempts")
    val attempts: Int,

    @ColumnInfo(name = "last_error")
    val lastError: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "started_at")
    val startedAt: Long?,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "next_run_at")
    val nextRunAt: Long?
)
