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
    var onConsolidate: suspend () -> Unit = {}
) : MemoryIntelligence {
    var lastBatchRequest: MemoryBatchConsolidationRequest? = null
    var lastPreferredPlatform: PlatformV2? = null
    var consolidateCalls = 0

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
