package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.PlatformV2

interface MemoryIntelligence {
    suspend fun consolidateMemoryBatch(
        request: MemoryBatchConsolidationRequest,
        preferredPlatform: PlatformV2? = null
    ): MemoryBatchConsolidationProposal?

    suspend fun distillDailyMemory(
        request: MemoryDailyDistillationFrozenInput,
        preferredPlatform: PlatformV2? = null
    ): MemoryDailyDistillationProposal?
}
