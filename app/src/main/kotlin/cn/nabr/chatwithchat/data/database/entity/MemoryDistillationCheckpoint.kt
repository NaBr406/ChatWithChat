package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_distillation_checkpoint",
    indices = [
        Index(
            name = "index_memory_distillation_checkpoint_source_batch",
            value = ["daily_source_path", "daily_source_hash", "batch_key"],
            unique = true
        ),
        Index(value = ["daily_date"]),
        Index(value = ["semantic_job_id"]),
        Index(value = ["mutation_group_id"]),
        Index(value = ["status"])
    ]
)
data class MemoryDistillationCheckpoint(
    @PrimaryKey
    @ColumnInfo(name = "checkpoint_id")
    val checkpointId: String,

    @ColumnInfo(name = "daily_source_path")
    val dailySourcePath: String,

    @ColumnInfo(name = "daily_source_hash")
    val dailySourceHash: String,

    @ColumnInfo(name = "batch_key")
    val batchKey: String,

    @ColumnInfo(name = "daily_date")
    val dailyDate: String,

    @ColumnInfo(name = "semantic_job_id")
    val semanticJobId: String,

    @ColumnInfo(name = "target_source_path")
    val targetSourcePath: String,

    @ColumnInfo(name = "target_base_hash")
    val targetBaseHash: String,

    @ColumnInfo(name = "target_source_hash")
    val targetSourceHash: String,

    @ColumnInfo(name = "mutation_group_id")
    val mutationGroupId: String?,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "processed_at")
    val processedAt: Long?,

    @ColumnInfo(name = "row_version", defaultValue = "0")
    val rowVersion: Long = 0
)
