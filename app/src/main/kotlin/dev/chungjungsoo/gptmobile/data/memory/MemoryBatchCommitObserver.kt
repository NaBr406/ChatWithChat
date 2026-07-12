package dev.chungjungsoo.gptmobile.data.memory

interface MemoryBatchCommitObserver {
    suspend fun afterPrepared(mutation: MemoryPreparedMutation) = Unit

    suspend fun afterCanonicalFileCommit(mutation: MemoryPreparedMutation) = Unit

    suspend fun afterBatchCompletion(jobId: String) = Unit

    data object None : MemoryBatchCommitObserver
}

class MemoryBatchCommitInterruptedException(message: String) : RuntimeException(message)
