package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.memory.FakeMemoryIntelligence
import dev.chungjungsoo.gptmobile.data.memory.InMemoryChatClassificationDao
import dev.chungjungsoo.gptmobile.data.memory.InMemoryPersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexSearchRequest
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexSearchResult
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexSearcher
import dev.chungjungsoo.gptmobile.data.memory.MemoryMarkdownCodec
import dev.chungjungsoo.gptmobile.data.memory.MemoryPromptBuilder
import dev.chungjungsoo.gptmobile.data.memory.MemorySelectionResult
import dev.chungjungsoo.gptmobile.data.memory.MemorySensitivity
import dev.chungjungsoo.gptmobile.data.memory.MemorySource
import dev.chungjungsoo.gptmobile.data.memory.MemoryStatus
import dev.chungjungsoo.gptmobile.data.memory.MemoryUsage
import dev.chungjungsoo.gptmobile.data.memory.SelectedMemoryReference
import dev.chungjungsoo.gptmobile.data.memory.testClassification
import dev.chungjungsoo.gptmobile.data.memory.testMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest {

    @Test
    fun `emotional support classification selects memories and builds prompt`() = runBlocking {
        val repository = createRepository(
            memories = listOf(
                testMemory(1, "The user prefers natural Chinese conversation."),
                testMemory(2, "The user has an important exam soon.", type = "important_event")
            ),
            intelligence = FakeMemoryIntelligence(
                classification = testClassification(mode = "emotional_support"),
                selection = MemorySelectionResult(
                    selected = listOf(
                        SelectedMemoryReference(1, 0.9f, MemoryUsage.TONE_ONLY, "Tone"),
                        SelectedMemoryReference(2, 0.8f, MemoryUsage.EXPLICIT_IF_NATURAL, "Event")
                    )
                )
            )
        )

        val prepared = repository.prepareMemoryContext(chatRoom(), userMessages(), listOf(emptyList()))

        assertEquals("emotional_support", prepared.classification?.mode)
        assertEquals(2, prepared.selectedMemories.size)
        assertTrue(prepared.prompt!!.contains("Guidance"))
        assertTrue(prepared.prompt.contains("important exam"))
    }

    @Test
    fun `casual chat can use no selected memories`() = runBlocking {
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(mode = "casual_chat"),
            selection = MemorySelectionResult()
        )
        val repository = createRepository(
            memories = listOf(testMemory(1, "The user dislikes preachy replies.")),
            intelligence = intelligence
        )

        val prepared = repository.prepareMemoryContext(chatRoom(), userMessages(), listOf(emptyList()))

        assertEquals("casual_chat", prepared.classification?.mode)
        assertTrue(prepared.selectedMemories.isEmpty())
        assertEquals(null, prepared.prompt)
    }

    @Test
    fun `sensitive memories are filtered unless user confirmed and classification is high confidence`() = runBlocking {
        val sensitiveMemory = testMemory(
            id = 1,
            recallText = "Sensitive private context",
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            sensitivity = MemorySensitivity.SENSITIVE
        )
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(confidence = 0.95f),
            selection = MemorySelectionResult(
                selected = listOf(SelectedMemoryReference(1, 1f, MemoryUsage.IMPLICIT_CONTEXT, "Relevant"))
            )
        )
        val repository = createRepository(memories = listOf(sensitiveMemory), intelligence = intelligence)

        val prepared = repository.prepareMemoryContext(chatRoom(), userMessages(), listOf(emptyList()))

        assertTrue(prepared.selectedMemories.isEmpty())
        assertEquals(0, intelligence.selectCalls)
    }

    @Test
    fun `confirmed sensitive memories can be candidates for high confidence classification`() = runBlocking {
        val sensitiveMemory = testMemory(
            id = 1,
            recallText = "Confirmed sensitive context",
            source = MemorySource.USER_CONFIRMED,
            sensitivity = MemorySensitivity.SENSITIVE
        )
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(confidence = 0.95f),
            selection = MemorySelectionResult(
                selected = listOf(SelectedMemoryReference(1, 1f, MemoryUsage.IMPLICIT_CONTEXT, "Relevant"))
            )
        )
        val repository = createRepository(memories = listOf(sensitiveMemory), intelligence = intelligence)

        val prepared = repository.prepareMemoryContext(chatRoom(), userMessages(), listOf(emptyList()))

        assertEquals(1, prepared.selectedMemories.size)
        assertTrue(prepared.prompt!!.contains("Confirmed sensitive context"))
    }

    @Test
    fun `resolved memory does not enter the prompt`() = runBlocking {
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(),
            selection = MemorySelectionResult(
                selected = listOf(SelectedMemoryReference(1, 1f, MemoryUsage.IMPLICIT_CONTEXT, "Relevant"))
            )
        )
        val repository = createRepository(
            memories = listOf(testMemory(1, "Resolved fact", status = MemoryStatus.RESOLVED)),
            intelligence = intelligence
        )

        val prepared = repository.prepareMemoryContext(chatRoom(), userMessages(), listOf(emptyList()))

        assertFalse(prepared.prompt.orEmpty().contains("Resolved fact"))
    }

    @Test
    fun `old high importance memory remains eligible`() = runBlocking {
        val yearAgo = 100L
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(),
            selection = MemorySelectionResult(
                selected = listOf(SelectedMemoryReference(1, 1f, MemoryUsage.IMPLICIT_CONTEXT, "Relevant"))
            )
        )
        val repository = createRepository(
            memories = listOf(testMemory(1, "Old but important memory", importance = 1f, updatedAt = yearAgo)),
            intelligence = intelligence
        )

        val prepared = repository.prepareMemoryContext(chatRoom(), userMessages(), listOf(emptyList()))

        assertTrue(prepared.prompt!!.contains("Old but important memory"))
    }

    @Test
    fun `memory context passes preferred platform to intelligence`() = runBlocking {
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(shouldUseMemories = false)
        )
        val repository = createRepository(
            memories = emptyList(),
            intelligence = intelligence
        )
        val preferredPlatform = memoryPlatform(model = "current-chat-model")

        repository.prepareMemoryContext(chatRoom(), userMessages(), listOf(emptyList()), memoryPlatform = preferredPlatform)

        assertEquals("current-chat-model", intelligence.lastPreferredPlatform?.model)
    }

    @Test
    fun `markdown memory results are used before room recall`() = runBlocking {
        val markdownSearcher = FakeMemoryIndexSearcher(
            results = listOf(markdownResult(text = "The user prefers implementation before long explanations."))
        )
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(memoryNeeds = listOf("communication_style")),
            selection = MemorySelectionResult(
                selected = listOf(SelectedMemoryReference(1, 1f, MemoryUsage.EXPLICIT_IF_NATURAL, "Room fallback"))
            )
        )
        val repository = createRepository(
            memories = listOf(testMemory(1, "Room memory should not be selected.")),
            intelligence = intelligence,
            memoryIndexSearcher = markdownSearcher
        )

        val prepared = repository.prepareMemoryContext(chatRoom(), userMessages(), listOf(emptyList()))

        assertEquals(1, markdownSearcher.calls)
        val request = markdownSearcher.lastRequest!!
        assertTrue(request.query.contains("I feel tired today"))
        assertTrue(request.query.contains("communication_style"))
        assertTrue(request.includePrivate)
        assertEquals(0, intelligence.selectCalls)
        assertTrue(prepared.selectedMemories.isEmpty())
        assertEquals(1, prepared.selectedMarkdownMemories.size)
        assertTrue(prepared.prompt!!.contains("implementation before long explanations"))
        assertTrue(prepared.prompt.contains("path: MEMORY.md"))
    }

    @Test
    fun `empty markdown memory results fall back to room recall`() = runBlocking {
        val markdownSearcher = FakeMemoryIndexSearcher(results = emptyList())
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(),
            selection = MemorySelectionResult(
                selected = listOf(SelectedMemoryReference(1, 0.9f, MemoryUsage.EXPLICIT_IF_NATURAL, "Room fallback"))
            )
        )
        val repository = createRepository(
            memories = listOf(testMemory(1, "Room fallback memory.")),
            intelligence = intelligence,
            memoryIndexSearcher = markdownSearcher
        )

        val prepared = repository.prepareMemoryContext(chatRoom(), userMessages(), listOf(emptyList()))

        assertEquals(1, markdownSearcher.calls)
        assertEquals(1, intelligence.selectCalls)
        assertEquals(1, prepared.selectedMemories.size)
        assertTrue(prepared.selectedMarkdownMemories.isEmpty())
        assertTrue(prepared.prompt!!.contains("Room fallback memory"))
    }

    @Test
    fun `markdown memory search failure falls back to room recall`() = runBlocking {
        val markdownSearcher = FakeMemoryIndexSearcher(failure = IllegalStateException("index unavailable"))
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(),
            selection = MemorySelectionResult(
                selected = listOf(SelectedMemoryReference(1, 0.9f, MemoryUsage.EXPLICIT_IF_NATURAL, "Room fallback"))
            )
        )
        val repository = createRepository(
            memories = listOf(testMemory(1, "Room fallback after index failure.")),
            intelligence = intelligence,
            memoryIndexSearcher = markdownSearcher
        )

        val prepared = repository.prepareMemoryContext(chatRoom(), userMessages(), listOf(emptyList()))

        assertEquals(1, markdownSearcher.calls)
        assertEquals(1, intelligence.selectCalls)
        assertTrue(prepared.selectedMarkdownMemories.isEmpty())
        assertTrue(prepared.prompt!!.contains("Room fallback after index failure"))
    }

    @Test
    fun `markdown memory search is skipped when classification disables recall`() = runBlocking {
        val markdownSearcher = FakeMemoryIndexSearcher(
            results = listOf(markdownResult(text = "Should not be recalled."))
        )
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(shouldUseMemories = false)
        )
        val repository = createRepository(
            memories = emptyList(),
            intelligence = intelligence,
            memoryIndexSearcher = markdownSearcher
        )

        val prepared = repository.prepareMemoryContext(chatRoom(), userMessages(), listOf(emptyList()))

        assertEquals(0, markdownSearcher.calls)
        assertEquals(null, prepared.prompt)
        assertTrue(prepared.selectedMarkdownMemories.isEmpty())
    }

    private fun createRepository(
        memories: List<dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory>,
        intelligence: FakeMemoryIntelligence,
        memoryIndexSearcher: MemoryIndexSearcher? = null
    ): MemoryRepositoryImpl = MemoryRepositoryImpl(
        personalMemoryDao = InMemoryPersonalMemoryDao(memories),
        chatClassificationDao = InMemoryChatClassificationDao(),
        memoryIntelligence = intelligence,
        memoryPromptBuilder = MemoryPromptBuilder(),
        memoryMarkdownCodec = MemoryMarkdownCodec(),
        memoryIndexSearcher = memoryIndexSearcher
    )

    private fun chatRoom() = ChatRoomV2(id = 1, title = "Chat", enabledPlatform = listOf("platform"))

    private fun userMessages() = listOf(MessageV2(chatId = 1, content = "I feel tired today", platformType = null))

    private fun markdownResult(
        text: String,
        entryId: String = "mem_markdown_1",
        sensitivity: String = MemorySensitivity.NORMAL,
        score: Float = 0.9f
    ): MemoryIndexSearchResult = MemoryIndexSearchResult(
        chunkId = "MEMORY.md#$entryId#0",
        sourcePath = "MEMORY.md",
        chunkIndex = 0,
        heading = "Stable Preferences",
        text = text,
        entryId = entryId,
        type = "communication_style",
        sensitivity = sensitivity,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        chatId = null,
        createdAt = 10L,
        updatedAt = 20L,
        score = score
    )

    private fun memoryPlatform(model: String) = PlatformV2(
        name = "Current",
        compatibleType = ClientType.OPENROUTER,
        enabled = true,
        apiUrl = "https://example.test/",
        token = "token",
        model = model
    )
}

private class FakeMemoryIndexSearcher(
    private val results: List<MemoryIndexSearchResult> = emptyList(),
    private val failure: Throwable? = null
) : MemoryIndexSearcher {
    var calls = 0
    var lastRequest: MemoryIndexSearchRequest? = null

    override suspend fun search(request: MemoryIndexSearchRequest): Result<List<MemoryIndexSearchResult>> {
        calls += 1
        lastRequest = request
        return failure?.let { Result.failure(it) } ?: Result.success(results)
    }
}
