package cn.nabr.chatwithchat.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.dao.MemoryLongTermConsolidationDao
import cn.nabr.chatwithchat.data.database.entity.MemoryLongTermConsolidationCheckpoint
import cn.nabr.chatwithchat.data.memory.MemoryLongTermCheckpointStatus
import cn.nabr.chatwithchat.data.memory.MemoryLongTermTriggerReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryLongTermConsolidationDaoInstrumentedTest {

    private lateinit var database: ChatDatabaseV2
    private lateinit var dao: MemoryLongTermConsolidationDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ChatDatabaseV2::class.java
        ).build()
        dao = database.memoryLongTermConsolidationDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun checkpointCasChain_andActiveKeyUniqueness_useRealRoomSql() = runBlocking {
        val first = checkpoint(id = "first", jobId = "job-first")
        val second = checkpoint(id = "second", jobId = "job-second")

        assertTrue(dao.insertIgnore(first) > 0L)
        assertEquals(-1L, dao.insertIgnore(second))
        assertEquals(first.checkpointId, dao.getActive(ACTIVE_KEY, MemoryLongTermCheckpointStatus.ACTIVE)?.checkpointId)

        assertEquals(
            1,
            dao.advancePartitionCas(
                checkpointId = first.checkpointId,
                expectedStatus = MemoryLongTermCheckpointStatus.PENDING,
                expectedRowVersion = 0,
                expectedBaseSourceHash = BASE_HASH,
                expectedOrderedSnapshotHash = SNAPSHOT_HASH,
                expectedPartitionCursor = 0,
                expectedProposalHash = null,
                expectedProposalJson = null,
                newPartitionCursor = 1,
                newProposalHash = PROPOSAL_HASH,
                newProposalJson = PROPOSAL_JSON,
                updatedAt = 11
            )
        )
        assertEquals(
            0,
            dao.advancePartitionCas(
                checkpointId = first.checkpointId,
                expectedStatus = MemoryLongTermCheckpointStatus.PENDING,
                expectedRowVersion = 0,
                expectedBaseSourceHash = BASE_HASH,
                expectedOrderedSnapshotHash = SNAPSHOT_HASH,
                expectedPartitionCursor = 0,
                expectedProposalHash = null,
                expectedProposalJson = null,
                newPartitionCursor = 1,
                newProposalHash = PROPOSAL_HASH,
                newProposalJson = PROPOSAL_JSON,
                updatedAt = 11
            )
        )
        assertEquals(
            1,
            dao.setContinuationRequiredCas(
                checkpointId = first.checkpointId,
                expectedStatus = MemoryLongTermCheckpointStatus.PENDING,
                expectedRowVersion = 1,
                expectedContinuationRequired = false,
                continuationRequired = true,
                updatedAt = 12
            )
        )
        assertEquals(
            1,
            dao.bindResolvedModelCas(
                checkpointId = first.checkpointId,
                expectedStatus = MemoryLongTermCheckpointStatus.PENDING,
                expectedRowVersion = 2,
                platformUid = "platform",
                modelId = "model",
                resolvedAt = 13,
                updatedAt = 13
            )
        )
        assertEquals(
            1,
            dao.recordAttemptCas(
                checkpointId = first.checkpointId,
                expectedStatus = MemoryLongTermCheckpointStatus.PENDING,
                expectedRowVersion = 3,
                attempt = 1,
                updatedAt = 14
            )
        )
        assertEquals(
            1,
            dao.recordErrorCas(
                checkpointId = first.checkpointId,
                expectedStatus = MemoryLongTermCheckpointStatus.PENDING,
                expectedRowVersion = 4,
                lastError = "temporary",
                updatedAt = 15
            )
        )
        assertEquals(
            1,
            dao.transitionCas(
                checkpointId = first.checkpointId,
                expectedStatus = MemoryLongTermCheckpointStatus.PENDING,
                expectedRowVersion = 5,
                expectedResultSourceHash = BASE_HASH,
                expectedMutationGroupId = null,
                newStatus = MemoryLongTermCheckpointStatus.PREPARED,
                newActiveKey = ACTIVE_KEY,
                newResultSourceHash = RESULT_HASH,
                newCompletedGeneration = null,
                newMutationGroupId = "mutation",
                lastError = null,
                completedAt = null,
                updatedAt = 16
            )
        )
        assertEquals(
            1,
            dao.transitionCas(
                checkpointId = first.checkpointId,
                expectedStatus = MemoryLongTermCheckpointStatus.PREPARED,
                expectedRowVersion = 6,
                expectedResultSourceHash = RESULT_HASH,
                expectedMutationGroupId = "mutation",
                newStatus = MemoryLongTermCheckpointStatus.COMPLETED,
                newActiveKey = null,
                newResultSourceHash = RESULT_HASH,
                newCompletedGeneration = 1,
                newMutationGroupId = "mutation",
                lastError = null,
                completedAt = 17,
                updatedAt = 17
            )
        )

        val completed = checkNotNull(dao.getById(first.checkpointId))
        assertEquals(MemoryLongTermCheckpointStatus.COMPLETED, completed.status)
        assertEquals(true, completed.continuationRequired)
        assertEquals("platform", completed.resolvedPlatformUid)
        assertEquals("model", completed.resolvedModelId)
        assertEquals(1, completed.attempt)
        assertEquals(PROPOSAL_HASH, completed.proposalHash)
        assertEquals(7L, completed.rowVersion)
        assertNull(completed.activeKey)
        assertTrue(dao.insertIgnore(second.copy(createdAt = 18, updatedAt = 18)) > 0L)
    }

    private fun checkpoint(
        id: String,
        jobId: String
    ) = MemoryLongTermConsolidationCheckpoint(
        checkpointId = id,
        jobId = jobId,
        activeKey = ACTIVE_KEY,
        triggerReason = MemoryLongTermTriggerReason.WEEKLY_DUE,
        sourcePath = "MEMORY.md",
        baseSourceHash = BASE_HASH,
        resultSourceHash = BASE_HASH,
        baseGeneration = 0,
        recallProjectionHash = RECALL_HASH,
        entryCount = 1,
        orderedSnapshotHash = SNAPSHOT_HASH,
        orderedEntryIdsJson = "[\"entry\"]",
        status = MemoryLongTermCheckpointStatus.PENDING,
        createdAt = 10,
        updatedAt = 10
    )

    private companion object {
        const val ACTIVE_KEY = "memory-long-term-consolidation:active:v1"
        const val BASE_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val RESULT_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val RECALL_HASH = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SNAPSHOT_HASH = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val PROPOSAL_HASH = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val PROPOSAL_JSON = "{\"decisions\":[]}"
    }
}
