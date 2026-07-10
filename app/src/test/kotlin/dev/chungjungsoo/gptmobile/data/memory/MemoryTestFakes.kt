package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.ChatClassificationDao
import dev.chungjungsoo.gptmobile.data.database.dao.PersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatClassification
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

class InMemoryPersonalMemoryDao(
    initialMemories: List<PersonalMemory> = emptyList()
) : PersonalMemoryDao {
    val memories = initialMemories.toMutableList()
    private var nextId = (initialMemories.maxOfOrNull { it.id } ?: 0) + 1

    override suspend fun getAll(): List<PersonalMemory> = memories.sortedWith(
        compareBy<PersonalMemory> { it.status }.thenByDescending { it.importance }.thenByDescending { it.updatedAt }
    )

    override suspend fun getRecallCandidates(): List<PersonalMemory> = memories
        .filter { it.status !in setOf(MemoryStatus.ARCHIVED, MemoryStatus.SUPERSEDED) }
        .sortedWith(compareByDescending<PersonalMemory> { it.importance }.thenByDescending { it.updatedAt })

    override suspend fun getByIds(ids: List<Int>): List<PersonalMemory> = memories.filter { it.id in ids }

    override suspend fun insert(memory: PersonalMemory): Long {
        val saved = if (memory.id == 0) memory.copy(id = nextId++) else memory
        memories += saved
        return saved.id.toLong()
    }

    override suspend fun upsert(memory: PersonalMemory) {
        val index = memories.indexOfFirst { it.id == memory.id }
        if (index == -1) {
            insert(memory)
        } else {
            memories[index] = memory
        }
    }

    override suspend fun update(memory: PersonalMemory) {
        val index = memories.indexOfFirst { it.id == memory.id }
        if (index != -1) {
            memories[index] = memory
        }
    }

    override suspend fun delete(memory: PersonalMemory) {
        memories.removeAll { it.id == memory.id }
    }
}

class InMemoryChatClassificationDao : ChatClassificationDao {
    val classifications = mutableMapOf<Int, ChatClassification>()

    override suspend fun getByChatId(chatId: Int): ChatClassification? = classifications[chatId]

    override suspend fun upsert(classification: ChatClassification) {
        classifications[classification.chatId] = classification
    }
}

class FakeMemoryIntelligence(
    var batchProposal: MemoryBatchConsolidationProposal? = null,
    var classification: ConversationClassificationResult? = null,
    var selection: MemorySelectionResult? = null,
    var candidates: List<MemoryCandidate> = emptyList(),
    var updatePlan: MemoryUpdatePlan? = null,
    var markdownProposal: MarkdownMemoryLearningProposal? = null
) : MemoryIntelligence {
    var lastBatchRequest: MemoryBatchConsolidationRequest? = null
    var lastSelectionRequest: MemorySelectionRequest? = null
    var lastExtractionRequest: MemoryExtractionRequest? = null
    var lastMarkdownLearningRequest: MarkdownMemoryLearningRequest? = null
    var lastPreferredPlatform: PlatformV2? = null
    var classifyCalls = 0
    var selectCalls = 0
    var extractCalls = 0
    var planCalls = 0
    var markdownProposalCalls = 0
    var consolidateCalls = 0

    override suspend fun consolidateMemoryBatch(
        request: MemoryBatchConsolidationRequest,
        preferredPlatform: PlatformV2?
    ): MemoryBatchConsolidationProposal? {
        consolidateCalls += 1
        lastBatchRequest = request
        lastPreferredPlatform = preferredPlatform
        return batchProposal
    }

    override suspend fun classifyConversation(
        request: ConversationClassificationRequest,
        preferredPlatform: PlatformV2?
    ): ConversationClassificationResult? {
        classifyCalls += 1
        lastPreferredPlatform = preferredPlatform
        return classification
    }

    override suspend fun selectMemories(
        request: MemorySelectionRequest,
        preferredPlatform: PlatformV2?
    ): MemorySelectionResult? {
        selectCalls += 1
        lastSelectionRequest = request
        lastPreferredPlatform = preferredPlatform
        return selection
    }

    override suspend fun extractMemoryCandidates(
        request: MemoryExtractionRequest,
        preferredPlatform: PlatformV2?
    ): List<MemoryCandidate> {
        extractCalls += 1
        lastExtractionRequest = request
        lastPreferredPlatform = preferredPlatform
        return candidates
    }

    override suspend fun planMemoryUpdates(
        request: MemoryUpdatePlanningRequest,
        preferredPlatform: PlatformV2?
    ): MemoryUpdatePlan? {
        planCalls += 1
        lastPreferredPlatform = preferredPlatform
        return updatePlan
    }

    override suspend fun proposeMarkdownMemoryWrites(
        request: MarkdownMemoryLearningRequest,
        preferredPlatform: PlatformV2?
    ): MarkdownMemoryLearningProposal? {
        markdownProposalCalls += 1
        lastMarkdownLearningRequest = request
        lastPreferredPlatform = preferredPlatform
        return markdownProposal
    }
}

fun testMemory(
    id: Int,
    recallText: String,
    type: String = "communication_style",
    status: String = MemoryStatus.ACTIVE,
    source: String = MemorySource.USER_CONFIRMED,
    sensitivity: String = MemorySensitivity.NORMAL,
    importance: Float = 0.8f,
    confidence: Float = 0.9f,
    updatedAt: Long = 100L
): PersonalMemory = PersonalMemory(
    id = id,
    summary = recallText,
    recallText = recallText,
    type = type,
    scope = "personal",
    importance = importance,
    confidence = confidence,
    source = source,
    sensitivity = sensitivity,
    status = status,
    createdAt = updatedAt,
    updatedAt = updatedAt
)

fun testClassification(
    mode: String = "emotional_support",
    memoryNeeds: List<String> = listOf("communication_style"),
    shouldUseMemories: Boolean = true,
    shouldLearnMemories: Boolean = true,
    sensitivity: String = MemorySensitivity.NORMAL,
    confidence: Float = 0.9f
): ConversationClassificationResult = ConversationClassificationResult(
    mode = mode,
    intent = "sharing",
    memoryNeeds = memoryNeeds,
    shouldUseMemories = shouldUseMemories,
    shouldLearnMemories = shouldLearnMemories,
    sensitivity = sensitivity,
    confidence = confidence
)

fun testCandidate(
    summary: String,
    recallText: String = summary,
    type: String = "communication_style",
    source: String = MemorySource.EXPLICIT_USER_STATEMENT,
    sensitivity: String = MemorySensitivity.NORMAL,
    suggestedStatus: String = MemoryStatus.ACTIVE,
    requiresConfirmation: Boolean = false
): MemoryCandidate = MemoryCandidate(
    summary = summary,
    recallText = recallText,
    type = type,
    scope = "personal",
    importance = 0.8f,
    confidence = 0.9f,
    source = source,
    sensitivity = sensitivity,
    suggestedStatus = suggestedStatus,
    requiresConfirmation = requiresConfirmation,
    reason = "Stable preference"
)
