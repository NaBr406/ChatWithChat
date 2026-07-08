package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

interface MemoryIntelligence {
    suspend fun classifyConversation(
        request: ConversationClassificationRequest,
        preferredPlatform: PlatformV2? = null
    ): ConversationClassificationResult?

    suspend fun selectMemories(
        request: MemorySelectionRequest,
        preferredPlatform: PlatformV2? = null
    ): MemorySelectionResult?

    suspend fun extractMemoryCandidates(
        request: MemoryExtractionRequest,
        preferredPlatform: PlatformV2? = null
    ): List<MemoryCandidate>

    suspend fun planMemoryUpdates(
        request: MemoryUpdatePlanningRequest,
        preferredPlatform: PlatformV2? = null
    ): MemoryUpdatePlan?

    suspend fun proposeMarkdownMemoryWrites(
        request: MarkdownMemoryLearningRequest,
        preferredPlatform: PlatformV2? = null
    ): MarkdownMemoryLearningProposal?
}
