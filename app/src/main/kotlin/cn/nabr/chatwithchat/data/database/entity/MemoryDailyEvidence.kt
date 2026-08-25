package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "memory_daily_evidence",
    primaryKeys = ["evidence_key", "content_hash"],
    indices = [
        Index(value = ["decision"]),
        Index(value = ["last_window_id"]),
        Index(value = ["last_checkpoint_id"]),
        Index(value = ["mutation_group_id"]),
        Index(value = ["target_entry_id"]),
        Index(value = ["source_date"])
    ]
)
data class MemoryDailyEvidence(
    @ColumnInfo(name = "evidence_key")
    val evidenceKey: String,

    @ColumnInfo(name = "content_hash")
    val contentHash: String,

    @ColumnInfo(name = "source_path")
    val sourcePath: String,

    @ColumnInfo(name = "source_date")
    val sourceDate: String,

    @ColumnInfo(name = "decision")
    val decision: String,

    @ColumnInfo(name = "last_window_id")
    val lastWindowId: String?,

    @ColumnInfo(name = "last_checkpoint_id")
    val lastCheckpointId: String?,

    @ColumnInfo(name = "pending_decision")
    val pendingDecision: String?,

    @ColumnInfo(name = "mutation_group_id")
    val mutationGroupId: String?,

    @ColumnInfo(name = "target_entry_id")
    val targetEntryId: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "decided_at")
    val decidedAt: Long?
)

object MemoryDailyEvidenceDecision {
    const val PENDING = "pending"
    const val PROMOTED = "promoted"
    const val IGNORED = "ignored"

    val FINAL = setOf(PROMOTED, IGNORED)
}
