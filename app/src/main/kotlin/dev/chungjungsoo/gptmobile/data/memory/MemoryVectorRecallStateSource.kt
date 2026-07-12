package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexConfiguration
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexIdentity

interface MemoryVectorRecallStateSource {
    suspend fun expectedIdentity(
        snapshot: MemoryCorpusSnapshot,
        configuration: MemoryVectorIndexConfiguration
    ): MemoryVectorIndexIdentity?
}

class RoomMemoryVectorRecallStateSource(
    private val recoveryDao: MemoryRecoveryDao
) : MemoryVectorRecallStateSource {
    override suspend fun expectedIdentity(
        snapshot: MemoryCorpusSnapshot,
        configuration: MemoryVectorIndexConfiguration
    ): MemoryVectorIndexIdentity? {
        if (
            snapshot.corpus != MemoryCorpus.CHAT_RECALL_LONG_TERM ||
            snapshot.sourcePath != MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME ||
            configuration.corpus != snapshot.corpus
        ) {
            return null
        }
        val fingerprint = configuration.fingerprint()
        val state = recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY) ?: return null
        if (
            state.sourcePath != snapshot.sourcePath ||
            state.sourceHash != snapshot.sourceHash ||
            state.targetIndexFingerprint != fingerprint ||
            state.indexStatus != MemoryCorpusIndexStatus.READY ||
            state.indexedGeneration != state.generation ||
            state.indexedSourceHash != snapshot.sourceHash ||
            state.indexedFingerprint != fingerprint
        ) {
            return null
        }
        return configuration.identity(
            sourcePath = snapshot.sourcePath,
            sourceHash = snapshot.sourceHash,
            corpusGeneration = state.generation
        )
    }

    private companion object {
        const val CHAT_RECALL_CORPUS_KEY = "chat_recall_long_term"
    }
}
