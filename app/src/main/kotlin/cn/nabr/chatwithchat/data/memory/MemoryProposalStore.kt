package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryProposalDao
import cn.nabr.chatwithchat.data.database.entity.MemoryProposal
import cn.nabr.chatwithchat.data.database.entity.MemoryProposalState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MemoryProposalInput(
    val proposalId: String,
    val chatId: Int? = null,
    val turnKey: String? = null,
    val intent: String,
    val targetRef: String?,
    val text: String,
    val normalizedText: String,
    val reason: String,
    val requestedEvidenceStrength: String,
    val idempotencyKey: String,
    val createdAt: Long,
    val expiresAt: Long? = null
)

interface MemoryProposalStore {
    suspend fun createOrGet(input: MemoryProposalInput): MemoryProposal
    suspend fun bindApprovedToTurn(turnKey: String, now: Long): List<MemoryProposal>
    suspend fun getBoundForTurns(turnKeys: List<String>): List<MemoryProposal>
    suspend fun markConsumed(proposalIds: List<String>, mutationGroupId: String?, now: Long)
}

class RoomMemoryProposalStore(
    private val dao: MemoryProposalDao
) : MemoryProposalStore {
    override suspend fun createOrGet(input: MemoryProposalInput): MemoryProposal {
        val existing = dao.getByIdempotencyKey(input.idempotencyKey)
        if (existing != null) return existing
        val proposal = MemoryProposal(
            proposalId = input.proposalId,
            chatId = input.chatId,
            turnKey = input.turnKey,
            intent = input.intent,
            targetRef = input.targetRef,
            text = input.text,
            normalizedText = input.normalizedText,
            reason = input.reason,
            requestedEvidenceStrength = input.requestedEvidenceStrength,
            effectiveEvidenceStrength = input.requestedEvidenceStrength,
            state = MemoryProposalState.APPROVED_PENDING_BIND,
            idempotencyKey = input.idempotencyKey,
            createdAt = input.createdAt,
            updatedAt = input.createdAt,
            expiresAt = input.expiresAt
        )
        dao.insertIgnore(proposal)
        return dao.getByIdempotencyKey(input.idempotencyKey) ?: proposal
    }

    override suspend fun bindApprovedToTurn(turnKey: String, now: Long): List<MemoryProposal> {
        dao.getApprovedPendingBind(turnKey).forEach { proposal ->
            dao.bindToTurn(proposal.proposalId, turnKey, now)
        }
        return dao.getBoundForTurns(listOf(turnKey))
    }

    override suspend fun getBoundForTurns(turnKeys: List<String>): List<MemoryProposal> =
        if (turnKeys.isEmpty()) emptyList() else dao.getBoundForTurns(turnKeys)

    override suspend fun markConsumed(proposalIds: List<String>, mutationGroupId: String?, now: Long) {
        proposalIds.distinct().forEach { proposalId -> dao.markConsumed(proposalId, mutationGroupId, now) }
    }
}

class InMemoryMemoryProposalStore : MemoryProposalStore {
    private val mutex = Mutex()
    private val proposals = linkedMapOf<String, MemoryProposal>()

    override suspend fun createOrGet(input: MemoryProposalInput): MemoryProposal = mutex.withLock {
        proposals[input.idempotencyKey] ?: MemoryProposal(
            proposalId = input.proposalId,
            chatId = input.chatId,
            turnKey = input.turnKey,
            intent = input.intent,
            targetRef = input.targetRef,
            text = input.text,
            normalizedText = input.normalizedText,
            reason = input.reason,
            requestedEvidenceStrength = input.requestedEvidenceStrength,
            effectiveEvidenceStrength = input.requestedEvidenceStrength,
            state = MemoryProposalState.APPROVED_PENDING_BIND,
            idempotencyKey = input.idempotencyKey,
            createdAt = input.createdAt,
            updatedAt = input.createdAt,
            expiresAt = input.expiresAt
        ).also { proposal -> proposals[input.idempotencyKey] = proposal }
    }

    override suspend fun bindApprovedToTurn(turnKey: String, now: Long): List<MemoryProposal> = mutex.withLock {
        proposals.values.filter { proposal ->
            proposal.state == MemoryProposalState.APPROVED_PENDING_BIND &&
                (proposal.turnKey == null || proposal.turnKey == turnKey)
        }.map { proposal ->
            proposal.copy(
                state = MemoryProposalState.BOUND_TO_TURN,
                boundTurnKey = turnKey,
                updatedAt = now
            ).also { updated -> proposals[updated.idempotencyKey] = updated }
        }
    }

    override suspend fun getBoundForTurns(turnKeys: List<String>): List<MemoryProposal> = mutex.withLock {
        proposals.values.filter { proposal ->
            proposal.state == MemoryProposalState.BOUND_TO_TURN && proposal.boundTurnKey in turnKeys
        }
    }

    override suspend fun markConsumed(proposalIds: List<String>, mutationGroupId: String?, now: Long) = mutex.withLock {
        proposalIds.forEach { proposalId ->
            proposals.entries.firstOrNull { (_, proposal) -> proposal.proposalId == proposalId }
                ?.let { (key, proposal) ->
                    proposals[key] = proposal.copy(
                        state = MemoryProposalState.CONSUMED,
                        consumedAt = now,
                        mutationGroupId = mutationGroupId,
                        updatedAt = now
                    )
                }
        }
    }
}

object MemoryEvidenceStrength {
    const val USER_EXPLICIT = "user_explicit"
    const val USER_CONFIRMED = "user_confirmed"
    const val AGENT_INFERRED = "agent_inferred"
}
