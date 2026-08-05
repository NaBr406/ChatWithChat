package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.nabr.chatwithchat.data.history.ChatHistorySnippet
import cn.nabr.chatwithchat.data.history.HistoryRecallMode
import cn.nabr.chatwithchat.data.history.HistoryRecallSnapshot
import cn.nabr.chatwithchat.data.memory.PreparedMemoryContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryPromptSnapshotInstrumentedTest {
    @Test
    fun historySnapshotIsFrozenAcrossProvidersToolRoundsAndRetry() = runBlocking {
        val historySnapshot = HistoryRecallSnapshot(
            projectionGeneration = 7L,
            projectionHash = "projection-hash",
            vectorGeneration = 7L,
            vectorHash = "vector-hash",
            snippets = listOf(
                ChatHistorySnippet(
                    turnKey = "chat:1:user:2",
                    chatId = 1,
                    userMessageId = 2,
                    assistantMessageId = 3,
                    title = "Old chat",
                    createdAt = 1L,
                    userContent = "project schedule",
                    assistantContent = "The schedule is ready"
                )
            ),
            mode = HistoryRecallMode.HYBRID,
            prompt = "[Relevant previous conversations]\nThe schedule is ready"
        )
        val prepared = PreparedMemoryContext(historySnapshot = historySnapshot)
        val key = MemoryTurnSnapshotKey(
            chatId = 91,
            turnIndex = 0,
            createdAt = 100L,
            content = "new question",
            attachmentSemantics = emptyList()
        )
        val cache = TurnMemoryContextCache()
        var retrievalCalls = 0

        val first = cache.getOrPrepare(key) {
            retrievalCalls++
            prepared
        }
        val retriesAndRounds = buildList {
            repeat(4) {
                add(cache.getOrPrepare(key) {
                    retrievalCalls++
                    PreparedMemoryContext()
                })
            }
        }

        assertEquals(1, retrievalCalls)
        retriesAndRounds.forEach { context ->
            assertSame(first, context)
            assertSame(historySnapshot, context.historySnapshot)
            assertEquals("[Relevant previous conversations]\nThe schedule is ready", context.prompt)
        }
        assertEquals(
            1,
            listOf("openai", "anthropic", "google", "openai-compatible")
                .map { first.prompt }
                .distinct()
                .size
        )
    }
}
