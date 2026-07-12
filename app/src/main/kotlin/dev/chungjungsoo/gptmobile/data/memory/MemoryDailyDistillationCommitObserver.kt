package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDistillationCheckpoint

interface MemoryDailyDistillationCommitObserver {
    suspend fun afterPrepared(mutation: MemoryPreparedMutation) = Unit

    suspend fun afterCanonicalFileCommit(mutation: MemoryPreparedMutation) = Unit

    suspend fun afterCheckpointCompletion(checkpoint: MemoryDistillationCheckpoint) = Unit

    data object None : MemoryDailyDistillationCommitObserver
}
