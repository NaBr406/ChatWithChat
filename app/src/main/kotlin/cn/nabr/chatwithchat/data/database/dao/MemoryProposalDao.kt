package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.nabr.chatwithchat.data.database.entity.MemoryProposal

@Dao
interface MemoryProposalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(proposal: MemoryProposal): Long

    @Query("SELECT * FROM memory_proposal WHERE idempotency_key = :idempotencyKey LIMIT 1")
    suspend fun getByIdempotencyKey(idempotencyKey: String): MemoryProposal?

    @Query("SELECT * FROM memory_proposal WHERE proposal_id = :proposalId LIMIT 1")
    suspend fun getById(proposalId: String): MemoryProposal?

    @Query("SELECT * FROM memory_proposal WHERE bound_turn_key IN (:turnKeys) AND state = 'bound_to_turn' ORDER BY created_at ASC")
    suspend fun getBoundForTurns(turnKeys: List<String>): List<MemoryProposal>

    @Query("SELECT * FROM memory_proposal WHERE turn_key = :turnKey AND state = 'approved_pending_bind'")
    suspend fun getApprovedPendingBind(turnKey: String): List<MemoryProposal>

    @Query("UPDATE memory_proposal SET state = 'bound_to_turn', bound_turn_key = :turnKey, updated_at = :updatedAt WHERE proposal_id = :proposalId AND state = 'approved_pending_bind' AND (turn_key = :turnKey OR turn_key IS NULL)")
    suspend fun bindToTurn(proposalId: String, turnKey: String, updatedAt: Long): Int

    @Query("UPDATE memory_proposal SET state = 'consumed', consumed_at = :now, updated_at = :now, mutation_group_id = :mutationGroupId WHERE proposal_id = :proposalId AND state = 'bound_to_turn'")
    suspend fun markConsumed(proposalId: String, mutationGroupId: String?, now: Long): Int
}
