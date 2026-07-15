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
        Index(value = ["started_at"])
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
    val updatedAt: Long
)
