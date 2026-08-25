package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_proposal",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["chat_id", "state"]),
        Index(value = ["bound_turn_key"]),
        Index(value = ["expires_at"])
    ]
)
data class MemoryProposal(
    @PrimaryKey
    @ColumnInfo(name = "proposal_id")
    val proposalId: String,
    @ColumnInfo(name = "chat_id")
    val chatId: Int?,
    @ColumnInfo(name = "turn_key")
    val turnKey: String?,
    @ColumnInfo(name = "intent")
    val intent: String,
    @ColumnInfo(name = "target_ref")
    val targetRef: String?,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "normalized_text")
    val normalizedText: String,
    @ColumnInfo(name = "reason")
    val reason: String,
    @ColumnInfo(name = "requested_evidence_strength")
    val requestedEvidenceStrength: String,
    @ColumnInfo(name = "effective_evidence_strength")
    val effectiveEvidenceStrength: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long?,
    @ColumnInfo(name = "bound_turn_key")
    val boundTurnKey: String? = null,
    @ColumnInfo(name = "mutation_group_id")
    val mutationGroupId: String? = null,
    @ColumnInfo(name = "consumed_at")
    val consumedAt: Long? = null
)

object MemoryProposalState {
    const val AWAITING_APPROVAL = "awaiting_approval"
    const val APPROVED_PENDING_BIND = "approved_pending_bind"
    const val BOUND_TO_TURN = "bound_to_turn"
    const val CONSUMED = "consumed"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"
}
