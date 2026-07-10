package dev.chungjungsoo.gptmobile.data.database

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryChatCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryPendingTurn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTurnBatchDaoTest {
    @Test
    fun `upserting the same chat and user message replaces one durable snapshot`() = runBlocking {
        val dao = InMemoryMemoryTurnBatchDao()

        dao.upsertPendingTurn(turn(chatId = 1, userMessageId = 10, payloadJson = "old"))
        dao.upsertPendingTurn(turn(chatId = 1, userMessageId = 10, payloadJson = "new"))

        assertEquals(1, dao.countUnclaimedTurns(1))
        assertEquals("new", dao.getPendingTurnsForChat(1).single().payloadJson)
    }

    @Test
    fun `claim keeps chat counters independent and takes at most five oldest turns`() = runBlocking {
        val dao = InMemoryMemoryTurnBatchDao()
        (1..6).forEach { messageId -> dao.upsertPendingTurn(turn(1, messageId)) }
        dao.upsertPendingTurn(turn(2, 1))

        val claimed = dao.claimOldestPendingTurns(chatId = 1, jobId = "job-1", limit = 5, updatedAt = 100L)

        assertEquals(listOf(1, 2, 3, 4, 5), claimed.map { it.userMessageId })
        assertEquals(1, dao.countUnclaimedTurns(1))
        assertEquals(1, dao.countUnclaimedTurns(2))
        assertTrue(dao.claimOldestPendingTurns(1, "job-2", 5, 101L).isEmpty())
        assertEquals(5, dao.releaseClaim("job-1", 102L))
        assertEquals(6, dao.countUnclaimedTurns(1))
    }

    @Test
    fun `successful batch deletes claimed rows and advances checkpoint`() = runBlocking {
        val dao = InMemoryMemoryTurnBatchDao()
        dao.upsertCheckpoint(checkpoint(chatId = 1, pendingSince = 10L, idleDueAt = 1_800L))
        (1..6).forEach { messageId -> dao.upsertPendingTurn(turn(1, messageId)) }
        dao.claimOldestPendingTurns(chatId = 1, jobId = "job-1", limit = 5, updatedAt = 100L)

        assertTrue(dao.completeClaimedBatch(jobId = "job-1", updatedAt = 200L))

        val savedCheckpoint = dao.getCheckpoint(1)!!
        assertEquals(5, savedCheckpoint.lastProcessedUserMessageId)
        assertEquals(6, dao.getPendingTurnsForChat(1).single().userMessageId)
        assertEquals(6L, savedCheckpoint.pendingSince)
        assertEquals(1_800L, savedCheckpoint.idleDueAt)
        assertFalse(dao.completeClaimedBatch(jobId = "job-1", updatedAt = 300L))
    }

    @Test
    fun `idle query returns only due chats with unclaimed turns`() = runBlocking {
        val dao = InMemoryMemoryTurnBatchDao()
        dao.upsertCheckpoint(checkpoint(chatId = 1, idleDueAt = 100L))
        dao.upsertCheckpoint(checkpoint(chatId = 2, idleDueAt = 200L))
        dao.upsertCheckpoint(checkpoint(chatId = 3, idleDueAt = 50L))
        dao.upsertPendingTurn(turn(1, 1))
        dao.upsertPendingTurn(turn(2, 1))

        assertEquals(listOf(1), dao.getDueIdleCheckpoints(now = 150L, limit = 10).map { it.chatId })
        assertEquals(100L, dao.getEarliestIdleDueAt())

        dao.claimOldestPendingTurns(1, "job-1", 5, 151L)
        assertEquals(200L, dao.getEarliestIdleDueAt())
        assertNull(dao.getCheckpoint(99))
    }

    private fun checkpoint(
        chatId: Int,
        pendingSince: Long? = null,
        idleDueAt: Long? = null
    ): MemoryChatCheckpoint = MemoryChatCheckpoint(
        chatId = chatId,
        pendingSince = pendingSince,
        idleDueAt = idleDueAt,
        updatedAt = 1L
    )

    private fun turn(
        chatId: Int,
        userMessageId: Int,
        payloadJson: String = "payload-$chatId-$userMessageId"
    ): MemoryPendingTurn = MemoryPendingTurn(
        turnKey = "$chatId:$userMessageId",
        chatId = chatId,
        userMessageId = userMessageId,
        payloadJson = payloadJson,
        contentHash = "hash-$chatId-$userMessageId",
        completedAt = userMessageId.toLong(),
        createdAt = userMessageId.toLong(),
        updatedAt = userMessageId.toLong()
    )
}

internal class InMemoryMemoryTurnBatchDao : MemoryTurnBatchDao {
    private val checkpoints = mutableMapOf<Int, MemoryChatCheckpoint>()
    private val turns = linkedMapOf<String, MemoryPendingTurn>()

    override suspend fun getCheckpoint(chatId: Int): MemoryChatCheckpoint? = checkpoints[chatId]

    override suspend fun upsertCheckpoint(checkpoint: MemoryChatCheckpoint) {
        checkpoints[checkpoint.chatId] = checkpoint
    }

    override suspend fun upsertPendingTurn(turn: MemoryPendingTurn) {
        turns.values
            .firstOrNull { it.chatId == turn.chatId && it.userMessageId == turn.userMessageId }
            ?.let { turns.remove(it.turnKey) }
        turns[turn.turnKey] = turn
    }

    override suspend fun getPendingTurn(chatId: Int, userMessageId: Int): MemoryPendingTurn? = turns.values
        .firstOrNull { it.chatId == chatId && it.userMessageId == userMessageId }

    override suspend fun getPendingTurnsForChat(chatId: Int): List<MemoryPendingTurn> = sortedTurns()
        .filter { it.chatId == chatId }

    override suspend fun getOldestUnclaimedTurns(chatId: Int, limit: Int): List<MemoryPendingTurn> = sortedTurns()
        .filter { it.chatId == chatId && it.claimedJobId == null }
        .take(limit)

    override suspend fun getTurnsClaimedByJob(jobId: String): List<MemoryPendingTurn> = sortedTurns()
        .filter { it.claimedJobId == jobId }

    override suspend fun getClaimedJobIds(): List<String> = turns.values.mapNotNull { it.claimedJobId }.distinct().sorted()

    override suspend fun getChatIdsWithUnclaimedTurns(): List<Int> = turns.values
        .filter { it.claimedJobId == null }
        .map { it.chatId }
        .distinct()
        .sorted()

    override suspend fun countUnclaimedTurns(chatId: Int): Int = turns.values.count {
        it.chatId == chatId && it.claimedJobId == null
    }

    override suspend fun countClaimedTurns(chatId: Int): Int = turns.values.count {
        it.chatId == chatId && it.claimedJobId != null
    }

    override suspend fun claimTurns(turnKeys: List<String>, jobId: String, updatedAt: Long): Int {
        var claimed = 0
        turnKeys.forEach { turnKey ->
            val turn = turns[turnKey]
            if (turn != null && turn.claimedJobId == null) {
                turns[turnKey] = turn.copy(claimedJobId = jobId, updatedAt = updatedAt)
                claimed += 1
            }
        }
        return claimed
    }

    override suspend fun releaseClaim(jobId: String, updatedAt: Long): Int {
        var released = 0
        turns.replaceAll { _, turn ->
            if (turn.claimedJobId == jobId) {
                released += 1
                turn.copy(claimedJobId = null, updatedAt = updatedAt)
            } else {
                turn
            }
        }
        return released
    }

    override suspend fun deleteClaimedTurns(jobId: String): Int {
        val keys = turns.filterValues { it.claimedJobId == jobId }.keys
        keys.forEach(turns::remove)
        return keys.size
    }

    override suspend fun deleteAllPendingTurns(): Int {
        val count = turns.size
        turns.clear()
        return count
    }

    override suspend fun advanceAllCheckpointsToObserved(updatedAt: Long): Int {
        checkpoints.replaceAll { _, checkpoint ->
            checkpoint.copy(
                lastProcessedUserMessageId = checkpoint.lastObservedUserMessageId,
                pendingSince = null,
                idleDueAt = null,
                updatedAt = updatedAt
            )
        }
        return checkpoints.size
    }

    override suspend fun getDueIdleCheckpoints(now: Long, limit: Int): List<MemoryChatCheckpoint> = checkpoints.values
        .filter { checkpoint ->
            checkpoint.idleDueAt != null &&
                checkpoint.idleDueAt <= now &&
                turns.values.any { it.chatId == checkpoint.chatId && it.claimedJobId == null }
        }
        .sortedWith(compareBy<MemoryChatCheckpoint> { it.idleDueAt }.thenBy { it.chatId })
        .take(limit)

    override suspend fun getEarliestIdleDueAt(): Long? = checkpoints.values
        .filter { checkpoint ->
            checkpoint.idleDueAt != null &&
                turns.values.any { it.chatId == checkpoint.chatId && it.claimedJobId == null }
        }
        .minOfOrNull { it.idleDueAt!! }

    override suspend fun getUnclaimedTurnsThrough(chatId: Int, lastUserMessageId: Int): List<MemoryPendingTurn> = sortedTurns()
        .filter { it.chatId == chatId && it.userMessageId <= lastUserMessageId && it.claimedJobId == null }

    private fun sortedTurns(): List<MemoryPendingTurn> = turns.values.sortedWith(
        compareBy<MemoryPendingTurn> { it.completedAt }.thenBy { it.userMessageId }
    )
}
