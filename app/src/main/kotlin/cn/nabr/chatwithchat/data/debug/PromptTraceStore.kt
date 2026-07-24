package cn.nabr.chatwithchat.data.debug

import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalMode
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PromptTraceEntry(
    val traceId: Long,
    val createdAtMillis: Long,
    val chatId: Int,
    val turnNumber: Int,
    val userMessageId: Int?,
    val platformUid: String,
    val platformName: String,
    val clientType: ClientType,
    val model: String,
    val stage: String,
    val systemPrompt: String,
    val memoryRecall: MemoryRecallTrace? = null
)

data class MemoryRecallTrace(
    val mode: MemoryRetrievalMode,
    val hitCount: Int,
    val memoryIds: List<String>,
    val errorMessage: String? = null
)

object PromptTraceStage {
    const val ANSWER = "answer"
    const val ANSWER_WITH_EXTRA_INSTRUCTIONS = "answer_with_extra_instructions"
    const val TOOL_FINAL_ANSWER = "tool_final_answer"

    fun toolRequest(roundNumber: Int): String = "tool_request_$roundNumber"
}

@Singleton
class PromptTraceStore @Inject constructor() {
    private val nextTraceId = AtomicLong(0L)
    private val _entries = MutableStateFlow<List<PromptTraceEntry>>(emptyList())
    private val memoryRecalls = LinkedHashMap<MemoryRecallKey, MemoryRecallTrace>()
    val entries = _entries.asStateFlow()

    @Synchronized
    fun recordMemoryRecall(
        chatId: Int,
        turnNumber: Int,
        userMessageId: Int?,
        recall: MemoryRecallTrace
    ) {
        memoryRecalls[MemoryRecallKey(chatId, turnNumber, userMessageId)] = recall
        while (memoryRecalls.size > MAX_ENTRIES) {
            memoryRecalls.remove(memoryRecalls.keys.first())
        }
    }

    fun record(
        chatId: Int,
        turnNumber: Int,
        userMessageId: Int?,
        platformUid: String,
        platformName: String,
        clientType: ClientType,
        model: String,
        stage: String,
        systemPrompt: String?,
        memoryRecall: MemoryRecallTrace? = null
    ): PromptTraceEntry {
        val entry = PromptTraceEntry(
            traceId = nextTraceId.incrementAndGet(),
            createdAtMillis = System.currentTimeMillis(),
            chatId = chatId,
            turnNumber = turnNumber,
            userMessageId = userMessageId,
            platformUid = platformUid,
            platformName = platformName,
            clientType = clientType,
            model = model,
            stage = stage,
            systemPrompt = systemPrompt.orEmpty(),
            memoryRecall = memoryRecall ?: memoryRecallFor(chatId, turnNumber, userMessageId)
        )
        _entries.update { current ->
            buildList(capacity = minOf(current.size + 1, MAX_ENTRIES)) {
                add(entry)
                addAll(current.take(MAX_ENTRIES - 1))
            }
        }
        return entry
    }

    @Synchronized
    fun clear() {
        memoryRecalls.clear()
        _entries.value = emptyList()
    }

    @Synchronized
    private fun memoryRecallFor(chatId: Int, turnNumber: Int, userMessageId: Int?): MemoryRecallTrace? =
        memoryRecalls[MemoryRecallKey(chatId, turnNumber, userMessageId)]

    private data class MemoryRecallKey(
        val chatId: Int,
        val turnNumber: Int,
        val userMessageId: Int?
    )

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
