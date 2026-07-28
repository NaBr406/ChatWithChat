package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_long_term_consolidation_checkpoint",
    indices = [
        Index(value = ["job_id"], unique = true),
        Index(value = ["active_key"], unique = true),
        Index(value = ["status", "completed_at"]),
        Index(value = ["mutation_group_id"]),
        Index(value = ["base_source_hash"])
    ]
)
data class MemoryLongTermConsolidationCheckpoint(
    @PrimaryKey
    @ColumnInfo(name = "checkpoint_id")
    val checkpointId: String,

    @ColumnInfo(name = "job_id")
    val jobId: String,

    @ColumnInfo(name = "active_key")
    val activeKey: String?,

    @ColumnInfo(name = "trigger_reason")
    val triggerReason: String,

    @ColumnInfo(name = "source_path")
    val sourcePath: String,

    @ColumnInfo(name = "base_source_hash")
    val baseSourceHash: String,

    @ColumnInfo(name = "result_source_hash")
    val resultSourceHash: String,

    @ColumnInfo(name = "base_generation")
    val baseGeneration: Long,

    @ColumnInfo(name = "completed_generation")
    val completedGeneration: Long? = null,

    @ColumnInfo(name = "recall_projection_hash")
    val recallProjectionHash: String,

    @ColumnInfo(name = "entry_count")
    val entryCount: Int,

    @ColumnInfo(name = "ordered_snapshot_hash")
    val orderedSnapshotHash: String,

    @ColumnInfo(name = "ordered_entry_ids_json")
    val orderedEntryIdsJson: String,

    @ColumnInfo(name = "partition_cursor", defaultValue = "0")
    val partitionCursor: Int = 0,

    @ColumnInfo(name = "proposal_hash")
    val proposalHash: String? = null,

    @ColumnInfo(name = "proposal_json")
    val proposalJson: String? = null,

    @ColumnInfo(name = "continuation_required", defaultValue = "0")
    val continuationRequired: Boolean = false,

    @ColumnInfo(name = "material_mutation_count_at_start", defaultValue = "0")
    val materialMutationCountAtStart: Int = 0,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "attempt", defaultValue = "0")
    val attempt: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "resolved_platform_uid")
    val resolvedPlatformUid: String? = null,

    @ColumnInfo(name = "resolved_model_id")
    val resolvedModelId: String? = null,

    @ColumnInfo(name = "resolved_at")
    val resolvedAt: Long? = null,

    @ColumnInfo(name = "mutation_group_id")
    val mutationGroupId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,

    @ColumnInfo(name = "row_version", defaultValue = "0")
    val rowVersion: Long = 0
)
