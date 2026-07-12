package dev.chungjungsoo.gptmobile.data.database

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDistillationCheckpoint
import dev.chungjungsoo.gptmobile.data.memory.MemoryDistillationCheckpointStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryDistillationCheckpointDaoTest {

    @Test
    fun `semantic job lookup is deterministic`() = runBlocking {
        val dao = InMemoryMemoryRecoveryDao()
        val later = checkpoint(
            checkpointId = "checkpoint-later",
            dailySourcePath = "memory/2026-07-12.md",
            dailyDate = "2026-07-12",
            batchKey = "batch-2"
        )
        val earlier = checkpoint(
            checkpointId = "checkpoint-earlier",
            dailySourcePath = "memory/2026-07-11.md",
            dailyDate = "2026-07-11",
            batchKey = "batch-1"
        )

        dao.insertDistillationCheckpointIgnore(later)
        dao.insertDistillationCheckpointIgnore(earlier)

        assertEquals(earlier, dao.getDistillationCheckpointBySemanticJobId(SEMANTIC_JOB_ID))
        assertNull(dao.getDistillationCheckpointBySemanticJobId("missing-job"))
    }

    @Test
    fun `cas transition persists mutation identity and processed cursor once`() = runBlocking {
        val dao = InMemoryMemoryRecoveryDao()
        val initial = checkpoint()
        dao.insertDistillationCheckpointIgnore(initial)

        assertEquals(
            1,
            transition(
                dao = dao,
                expected = initial,
                newStatus = MemoryDistillationCheckpointStatus.PREPARED,
                newTargetSourceHash = "prepared-target-hash",
                mutationGroupId = MUTATION_GROUP_ID,
                updatedAt = 20
            )
        )
        val prepared = checkNotNull(
            dao.getDistillationCheckpoint(initial.dailySourcePath, initial.dailySourceHash, initial.batchKey)
        )
        assertEquals(MemoryDistillationCheckpointStatus.PREPARED, prepared.status)
        assertEquals("prepared-target-hash", prepared.targetSourceHash)
        assertEquals(MUTATION_GROUP_ID, prepared.mutationGroupId)
        assertEquals(1L, prepared.rowVersion)
        assertNull(prepared.processedAt)

        assertEquals(
            1,
            transition(
                dao = dao,
                expected = prepared,
                newStatus = MemoryDistillationCheckpointStatus.COMPLETED,
                mutationGroupId = null,
                updatedAt = 30,
                processedAt = 30
            )
        )
        val processed = checkNotNull(dao.getDistillationCheckpointBySemanticJobId(SEMANTIC_JOB_ID))
        assertEquals(MemoryDistillationCheckpointStatus.COMPLETED, processed.status)
        assertEquals(MUTATION_GROUP_ID, processed.mutationGroupId)
        assertEquals(30L, processed.processedAt)
        assertEquals(2L, processed.rowVersion)

        assertEquals(
            0,
            transition(
                dao = dao,
                expected = prepared,
                newStatus = MemoryDistillationCheckpointStatus.COMPLETED,
                mutationGroupId = null,
                updatedAt = 40,
                processedAt = 40
            )
        )
        assertEquals(processed, dao.getDistillationCheckpointBySemanticJobId(SEMANTIC_JOB_ID))
    }

    @Test
    fun `cas transition rejects every stale or conflicting identity`() = runBlocking {
        val mismatches = listOf<(MemoryDistillationCheckpoint) -> MemoryDistillationCheckpoint>(
            { checkpoint -> checkpoint.copy(status = "stale-status") },
            { checkpoint -> checkpoint.copy(rowVersion = checkpoint.rowVersion + 1) },
            { checkpoint -> checkpoint.copy(dailySourcePath = "memory/other.md") },
            { checkpoint -> checkpoint.copy(dailySourceHash = "other-daily-hash") },
            { checkpoint -> checkpoint.copy(batchKey = "other-batch") },
            { checkpoint -> checkpoint.copy(semanticJobId = "other-job") },
            { checkpoint -> checkpoint.copy(targetSourcePath = "OTHER.md") },
            { checkpoint -> checkpoint.copy(targetBaseHash = "other-base-hash") },
            { checkpoint -> checkpoint.copy(targetSourceHash = "other-target-hash") },
            { checkpoint -> checkpoint.copy(mutationGroupId = "other-group") }
        )

        mismatches.forEachIndexed { index, mismatch ->
            val dao = InMemoryMemoryRecoveryDao()
            val initial = checkpoint(checkpointId = "checkpoint-$index")
            dao.insertDistillationCheckpointIgnore(initial)

            assertEquals(
                "Mismatch $index must fail closed",
                0,
                transition(
                    dao = dao,
                    expected = mismatch(initial),
                    newStatus = MemoryDistillationCheckpointStatus.PREPARED,
                    mutationGroupId = MUTATION_GROUP_ID,
                    updatedAt = 20
                )
            )
            assertEquals(
                initial,
                dao.getDistillationCheckpoint(initial.dailySourcePath, initial.dailySourceHash, initial.batchKey)
            )
        }
    }

    @Test
    fun `cas transition rejects time rollback and invalid processed time`() = runBlocking {
        val dao = InMemoryMemoryRecoveryDao()
        val initial = checkpoint()
        dao.insertDistillationCheckpointIgnore(initial)

        assertEquals(
            0,
            transition(
                dao = dao,
                expected = initial,
                newStatus = MemoryDistillationCheckpointStatus.COMPLETED,
                mutationGroupId = MUTATION_GROUP_ID,
                updatedAt = initial.updatedAt - 1,
                processedAt = initial.updatedAt
            )
        )
        assertEquals(
            0,
            transition(
                dao = dao,
                expected = initial,
                newStatus = MemoryDistillationCheckpointStatus.COMPLETED,
                mutationGroupId = MUTATION_GROUP_ID,
                updatedAt = initial.updatedAt,
                processedAt = -1
            )
        )
        assertEquals(initial, dao.getDistillationCheckpointBySemanticJobId(SEMANTIC_JOB_ID))
    }

    private suspend fun transition(
        dao: InMemoryMemoryRecoveryDao,
        expected: MemoryDistillationCheckpoint,
        newStatus: String,
        newTargetSourceHash: String = expected.targetSourceHash,
        mutationGroupId: String?,
        updatedAt: Long,
        processedAt: Long? = null
    ): Int = dao.transitionDistillationCheckpointCas(
        checkpointId = expected.checkpointId,
        expectedStatus = expected.status,
        expectedRowVersion = expected.rowVersion,
        expectedDailySourcePath = expected.dailySourcePath,
        expectedDailySourceHash = expected.dailySourceHash,
        expectedBatchKey = expected.batchKey,
        expectedSemanticJobId = expected.semanticJobId,
        expectedTargetSourcePath = expected.targetSourcePath,
        expectedTargetBaseHash = expected.targetBaseHash,
        expectedTargetSourceHash = expected.targetSourceHash,
        expectedMutationGroupId = expected.mutationGroupId,
        newStatus = newStatus,
        newTargetSourceHash = newTargetSourceHash,
        mutationGroupId = mutationGroupId,
        updatedAt = updatedAt,
        processedAt = processedAt
    )

    private fun checkpoint(
        checkpointId: String = CHECKPOINT_ID,
        dailySourcePath: String = DAILY_SOURCE_PATH,
        dailyDate: String = DAILY_DATE,
        batchKey: String = BATCH_KEY
    ): MemoryDistillationCheckpoint = MemoryDistillationCheckpoint(
        checkpointId = checkpointId,
        dailySourcePath = dailySourcePath,
        dailySourceHash = DAILY_SOURCE_HASH,
        batchKey = batchKey,
        dailyDate = dailyDate,
        semanticJobId = SEMANTIC_JOB_ID,
        targetSourcePath = TARGET_SOURCE_PATH,
        targetBaseHash = TARGET_BASE_HASH,
        targetSourceHash = TARGET_SOURCE_HASH,
        mutationGroupId = null,
        status = MemoryDistillationCheckpointStatus.PENDING,
        createdAt = 10,
        updatedAt = 10,
        processedAt = null
    )

    private companion object {
        const val CHECKPOINT_ID = "checkpoint-1"
        const val DAILY_SOURCE_PATH = "memory/2026-07-11.md"
        const val DAILY_SOURCE_HASH = "daily-hash"
        const val DAILY_DATE = "2026-07-11"
        const val BATCH_KEY = "batch-1"
        const val SEMANTIC_JOB_ID = "semantic-job-1"
        const val TARGET_SOURCE_PATH = "MEMORY.md"
        const val TARGET_BASE_HASH = "base-hash"
        const val TARGET_SOURCE_HASH = "target-hash"
        const val MUTATION_GROUP_ID = "mutation-group-1"
    }
}
