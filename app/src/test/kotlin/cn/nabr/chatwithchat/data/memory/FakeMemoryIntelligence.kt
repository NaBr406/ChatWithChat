package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.PlatformV2

class FakeMemoryIntelligence(
    var batchProposal: MemoryBatchConsolidationProposal? = null,
    var distillationProposal: MemoryDailyDistillationProposal? = null,
    var longTermProposal: MemoryLongTermConsolidationProposal? = null,
    var importProposal: MemoryImportProposal? = null,
    var onConsolidate: suspend () -> Unit = {}
) : MemoryIntelligence {
    var batchRawResponseJson: String? = null
    var distillationRawResponseJson: String? = null
    var longTermRawResponseJson: String? = null
    var lastBatchRequest: MemoryBatchConsolidationRequest? = null
    var lastLongTermRequest: MemoryLongTermConsolidationPartitionRequest? = null
    var lastPreferredPlatform: PlatformV2? = null
    var lastResolvedPlatform: PlatformV2? = null
    var lastImportRequest: MemoryImportRequest? = null
    var consolidateCalls = 0
    var distillationCalls = 0
    var longTermConsolidationCalls = 0

    override suspend fun rewriteImportedMemory(
        request: MemoryImportRequest,
        resolvedPlatform: PlatformV2
    ): MemoryImportProposal? {
        lastImportRequest = request
        lastResolvedPlatform = resolvedPlatform
        return importProposal
    }

    override suspend fun consolidateMemoryBatch(
        request: MemoryBatchConsolidationRequest,
        resolvedPlatform: PlatformV2
    ): MemoryBatchConsolidationProposal? {
        consolidateCalls += 1
        lastBatchRequest = request
        lastPreferredPlatform = resolvedPlatform
        onConsolidate()
        return batchProposal
    }

    override suspend fun consolidateMemoryBatchWithRawResponse(
        request: MemoryBatchConsolidationRequest,
        resolvedPlatform: PlatformV2,
        activityRunId: String
    ): MemoryModelProposalResponse<MemoryBatchConsolidationProposal> = MemoryModelProposalResponse(
        proposal = consolidateMemoryBatch(request, resolvedPlatform),
        rawResponseJson = batchRawResponseJson
    )

    override suspend fun distillDailyMemory(
        request: MemoryDailyDistillationFrozenInput,
        resolvedPlatform: PlatformV2
    ): MemoryDailyDistillationProposal? {
        distillationCalls += 1
        lastPreferredPlatform = resolvedPlatform
        return distillationProposal
    }

    override suspend fun distillDailyMemoryWithRawResponse(
        request: MemoryDailyDistillationFrozenInput,
        resolvedPlatform: PlatformV2,
        activityRunId: String
    ): MemoryModelProposalResponse<MemoryDailyDistillationProposal> = MemoryModelProposalResponse(
        proposal = distillDailyMemory(request, resolvedPlatform),
        rawResponseJson = distillationRawResponseJson
    )

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

    override suspend fun consolidateLongTermMemoryWithRawResponse(
        request: MemoryLongTermConsolidationPartitionRequest,
        resolvedPlatform: PlatformV2,
        activityRunId: String
    ): MemoryModelProposalResponse<MemoryLongTermConsolidationProposal> = MemoryModelProposalResponse(
        proposal = consolidateLongTermMemory(request, resolvedPlatform),
        rawResponseJson = longTermRawResponseJson
    )
}
