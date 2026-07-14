package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

class FakeMemoryIntelligence(
    var batchProposal: MemoryBatchConsolidationProposal? = null,
    var distillationProposal: MemoryDailyDistillationProposal? = null,
    var onConsolidate: suspend () -> Unit = {}
) : MemoryIntelligence {
    var lastBatchRequest: MemoryBatchConsolidationRequest? = null
    var lastPreferredPlatform: PlatformV2? = null
    var consolidateCalls = 0
    var distillationCalls = 0

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
}
