package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.PlatformV2

class FakeMemoryIntelligence(
    var batchProposal: MemoryBatchConsolidationProposal? = null,
    var distillationProposal: MemoryDailyDistillationProposal? = null,
    var longTermProposal: MemoryLongTermConsolidationProposal? = null,
    var onConsolidate: suspend () -> Unit = {}
) : MemoryIntelligence {
    var lastBatchRequest: MemoryBatchConsolidationRequest? = null
    var lastLongTermRequest: MemoryLongTermConsolidationPartitionRequest? = null
    var lastPreferredPlatform: PlatformV2? = null
    var lastResolvedPlatform: PlatformV2? = null
    var consolidateCalls = 0
    var distillationCalls = 0
    var longTermConsolidationCalls = 0

    override suspend fun consolidateMemoryBatch(
        request: MemoryBatchConsolidationRequest,
        preferredPlatform: PlatformV2?
    ): MemoryBatchConsolidationProposal? {
        consolidateCalls += 1
        lastBatchRequest = request
        lastPreferredPlatform = preferredPlatform
        onConsolidate()
        return batchProposal
    }

    override suspend fun distillDailyMemory(
        request: MemoryDailyDistillationFrozenInput,
        preferredPlatform: PlatformV2?
    ): MemoryDailyDistillationProposal? {
        distillationCalls += 1
        lastPreferredPlatform = preferredPlatform
        return distillationProposal
    }

    override suspend fun consolidateLongTermMemory(
        request: MemoryLongTermConsolidationPartitionRequest,
        resolvedPlatform: PlatformV2
    ): MemoryLongTermConsolidationProposal? {
        longTermConsolidationCalls += 1
        lastLongTermRequest = request
        lastResolvedPlatform = resolvedPlatform
        onConsolidate()
        return longTermProposal
    }
}
