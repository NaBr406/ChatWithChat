package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_activity_log",
    indices = [
        Index(value = ["batch_id"]),
        Index(value = ["category"]),
        Index(value = ["status"]),
        Index(value = ["started_at"]),
        Index(value = ["job_id", "retry_cycle", "attempt"], unique = true)
    ]
)
data class MemoryActivityLog(
    @PrimaryKey
    @ColumnInfo(name = "log_id")
    val logId: String,
    @ColumnInfo(name = "batch_id")
    val batchId: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "platform_name")
    val platformName: String?,
    @ColumnInfo(name = "model_name")
    val modelName: String?,
    @ColumnInfo(name = "attempt")
    val attempt: Int?,
    @ColumnInfo(name = "retry_cycle", defaultValue = "0")
    val retryCycle: Int = 0,
    @ColumnInfo(name = "turn_count")
    val turnCount: Int?,
    @ColumnInfo(name = "operation_count")
    val operationCount: Int?,
    @ColumnInfo(name = "detail")
    val detail: String?,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "job_id")
    val jobId: String? = null,
    @ColumnInfo(name = "job_type")
    val jobType: String? = null,
    @ColumnInfo(name = "phase")
    val phase: String? = null,
    @ColumnInfo(name = "trigger_reason")
    val triggerReason: String? = null,
    @ColumnInfo(name = "platform_uid")
    val platformUid: String? = null,
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,
    @ColumnInfo(name = "input_count")
    val inputCount: Int? = null,
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,
    @ColumnInfo(name = "phase_summary_json")
    val phaseSummaryJson: String? = null,
    @ColumnInfo(name = "row_version", defaultValue = "0")
    val rowVersion: Long = 0
)
