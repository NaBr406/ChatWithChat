package cn.nabr.chatwithchat.data.debug

import cn.nabr.chatwithchat.data.memory.MemoryRetrievalMode
import cn.nabr.chatwithchat.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTraceStoreTest {

    @Test
    fun `record keeps exact prompt and orders newest first`() {
        val store = PromptTraceStore()
        val exactPrompt = "System line 1\n\nMemory: <keep exactly>\nTool instructions"

        val first = store.record(
            chatId = 42,
            turnNumber = 3,
            userMessageId = 9,
            platformUid = "provider-one",
            platformName = "Provider One",
            clientType = ClientType.OPENAI,
            model = "model-one",
            stage = PromptTraceStage.ANSWER,
            systemPrompt = exactPrompt
        )
        val second = store.record(
            chatId = 42,
            turnNumber = 4,
            userMessageId = 10,
            platformUid = "provider-one",
            platformName = "Provider One",
            clientType = ClientType.OPENAI,
            model = "model-one",
            stage = PromptTraceStage.TOOL_FINAL_ANSWER,
            systemPrompt = "second prompt"
        )

        assertEquals(listOf(second.traceId, first.traceId), store.entries.value.map { it.traceId })
        assertEquals(exactPrompt, store.entries.value[1].systemPrompt)
        assertEquals(9, store.entries.value[1].userMessageId)
    }

    @Test
    fun `search decision stage keeps the exact decision prompt`() {
        val store = PromptTraceStore()
        val prompt = "用户最新消息：需要最新资料吗？"

        val entry = store.record(
            chatId = 8,
            turnNumber = 2,
            userMessageId = 12,
            platformUid = "provider",
            platformName = "Provider",
            clientType = ClientType.CUSTOM,
            model = "model",
            stage = PromptTraceStage.SEARCH_DECISION,
            systemPrompt = prompt
        )

        assertEquals(PromptTraceStage.SEARCH_DECISION, entry.stage)
        assertEquals(prompt, entry.systemPrompt)
    }

    @Test
    fun `record retains only latest two hundred entries`() {
        val store = PromptTraceStore()

        repeat(205) { index ->
            store.record(
                chatId = 1,
                turnNumber = index + 1,
                userMessageId = index + 1,
                platformUid = "provider",
                platformName = "Provider",
                clientType = ClientType.CUSTOM,
                model = "model",
                stage = PromptTraceStage.ANSWER,
                systemPrompt = "prompt-$index"
            )
        }

        assertEquals(200, store.entries.value.size)
        assertEquals("prompt-204", store.entries.value.first().systemPrompt)
        assertEquals("prompt-5", store.entries.value.last().systemPrompt)
    }

    @Test
    fun `clear removes all entries`() {
        val store = PromptTraceStore()
        store.record(
            chatId = 1,
            turnNumber = 1,
            userMessageId = null,
            platformUid = "provider",
            platformName = "Provider",
            clientType = ClientType.GOOGLE,
            model = "model",
            stage = PromptTraceStage.ANSWER,
            systemPrompt = null
        )

        store.clear()

        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun `record attaches recall diagnostics for the same conversation turn`() {
        val store = PromptTraceStore()
        val recall = MemoryRecallTrace(
            mode = MemoryRetrievalMode.HYBRID,
            hitCount = 2,
            memoryIds = listOf("mem_server", "mem_address")
        )

        store.recordMemoryRecall(
            chatId = 7,
            turnNumber = 2,
            userMessageId = 21,
            recall = recall
        )
        val entry = store.record(
            chatId = 7,
            turnNumber = 2,
            userMessageId = 21,
            platformUid = "provider",
            platformName = "Provider",
            clientType = ClientType.OPENAI,
            model = "model",
            stage = PromptTraceStage.ANSWER,
            systemPrompt = "system"
        )

        assertEquals(recall, entry.memoryRecall)
    }
}
