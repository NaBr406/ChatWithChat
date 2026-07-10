package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

interface MemoryIntelligence {
    suspend fun consolidateMemoryBatch(
        request: MemoryBatchConsolidationRequest,
        preferredPlatform: PlatformV2? = null
    ): MemoryBatchConsolidationProposal?
}
