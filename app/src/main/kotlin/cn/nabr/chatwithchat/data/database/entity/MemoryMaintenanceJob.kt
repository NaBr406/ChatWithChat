package cn.nabr.chatwithchat.data.database.entity

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
        Index(value = ["next_run_at"]),
        Index(value = ["family", "status", "next_run_at", "created_at"]),
        Index(value = ["family", "status", "lease_expires_at"]),
        Index(value = ["generation", "status"])
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
    val nextRunAt: Long?,

    @ColumnInfo(name = "family", defaultValue = "'semantic'")
    val family: String = "semantic",

    @ColumnInfo(name = "generation", defaultValue = "0")
    val generation: Long = 0,

    @ColumnInfo(name = "row_version", defaultValue = "0")
    val rowVersion: Long = 0,

    @ColumnInfo(name = "lease_owner")
    val leaseOwner: String? = null,

    @ColumnInfo(name = "lease_expires_at")
    val leaseExpiresAt: Long? = null,

    @ColumnInfo(name = "retry_cycle", defaultValue = "0")
    val retryCycle: Int = 0,

    @ColumnInfo(name = "blocked_reason")
    val blockedReason: String? = null
)
