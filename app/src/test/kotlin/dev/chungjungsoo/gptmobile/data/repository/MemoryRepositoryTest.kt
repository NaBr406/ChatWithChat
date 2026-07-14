package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.memory.MarkdownLexicalRetriever
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryCodec
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryEntry
import dev.chungjungsoo.gptmobile.data.memory.MemoryChunker
import dev.chungjungsoo.gptmobile.data.memory.MemoryCompletedTurnInput
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpus
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpusSnapshotter
import dev.chungjungsoo.gptmobile.data.memory.MemoryFilePaths
import dev.chungjungsoo.gptmobile.data.memory.MemoryFileStore
import dev.chungjungsoo.gptmobile.data.memory.MemoryPromptBuilder
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetrievalRequest
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetrievalResult
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetrievalStrategy
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetriever
import dev.chungjungsoo.gptmobile.data.memory.MemorySensitivity
import dev.chungjungsoo.gptmobile.data.memory.MemorySource
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchCoordinator
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest {
    @Test
    fun `local markdown retrieval builds prompt with zero intelligence calls`() = runBlocking {
        val retriever = FakeMemoryRetriever(
            results = listOf(retrievalResult(text = "The user prefers implementation before long explanations."))
        )
        val repository = createRepository(retriever)

        val prepared = repository.prepareMemoryContext(
            chatRoom = chatRoom(),
            userMessages = userMessages("Please implement this directly."),
            assistantMessages = listOf(emptyList())
        )

        assertEquals(1, retriever.calls)
        assertEquals("Please implement this directly.", retriever.lastRequest?.query)
        assertEquals(MemoryCorpus.CHAT_RECALL_LONG_TERM, retriever.lastRequest?.corpus)
        assertEquals(900, retriever.lastRequest?.tokenBudget)
        assertTrue(retriever.lastRequest?.includePrivate == true)
        assertEquals(MemoryRetrievalStrategy.HYBRID, retriever.lastRequest?.strategy)
        assertEquals(1, prepared.retrievedMemories.size)
        assertTrue(prepared.prompt!!.contains("implementation before long explanations"))
        assertTrue(prepared.prompt.contains("path: MEMORY.md"))
    }

    @Test
    fun `irrelevant or absent local memory is omitted`() = runBlocking {
        val repository = createRepository(FakeMemoryRetriever())

        val prepared = repository.prepareMemoryContext(
            chatRoom(),
            userMessages("What is the weather?"),
            listOf(emptyList())
        )

        assertTrue(prepared.retrievedMemories.isEmpty())
        assertNull(prepared.prompt)
    }

    @Test
    fun `local search failure degrades to no memory`() = runBlocking {
        val repository = createRepository(
            FakeMemoryRetriever(failure = IllegalStateException("index unavailable"))
        )

        val prepared = repository.prepareMemoryContext(
            chatRoom(),
            userMessages("Continue the project."),
            listOf(emptyList())
        )

        assertTrue(prepared.retrievedMemories.isEmpty())
        assertNull(prepared.prompt)
    }

    @Test
    fun `daily only preference cannot enter ordinary prompt`() = runBlocking {
        val fileStore = MemoryFileStore(MemoryFilePaths(Files.createTempDirectory("memory-repository-scope-test").toFile()))
        val hiddenEntry = MarkdownMemoryEntry(
            id = "day_hidden",
            text = "The user prefers the violet-compass response style.",
            type = "communication_style",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 1L,
            updatedAt = 2L
        )
        fileStore.appendDailyNote(MarkdownMemoryCodec().renderDailyAppend(listOf(hiddenEntry))).getOrThrow()
        val repository = createRepository(
            LexicalFallbackMemoryRetriever(
                MarkdownLexicalRetriever(MemoryCorpusSnapshotter(fileStore, MemoryChunker()))
            )
        )

        val hidden = repository.prepareMemoryContext(
            chatRoom(),
            userMessages("violet-compass"),
            listOf(emptyList())
        )
        fileStore.replaceLongTermMemory(MarkdownMemoryCodec().renderLongTerm(listOf(hiddenEntry))).getOrThrow()
        val visible = repository.prepareMemoryContext(
            chatRoom(),
            userMessages("violet-compass"),
            listOf(emptyList())
        )

        assertTrue(hidden.retrievedMemories.isEmpty())
        assertNull(hidden.prompt)
        assertEquals("day_hidden", visible.retrievedMemories.single().entryId)
        assertTrue(visible.prompt!!.contains("violet-compass"))
        assertTrue(visible.prompt.contains("path: MEMORY.md"))
    }

    @Test
    fun `ordinary prompt drops a non long term result from a faulty retriever`() = runBlocking {
        val repository = createRepository(
            FakeMemoryRetriever(
                results = listOf(
                    retrievalResult("Hidden daily content").copy(sourcePath = "memory/2026-07-12.md")
                )
            )
        )

        val prepared = repository.prepareMemoryContext(
            chatRoom(),
            userMessages("Hidden daily content"),
            listOf(emptyList())
        )

        assertTrue(prepared.retrievedMemories.isEmpty())
        assertNull(prepared.prompt)
    }

    @Test
    fun `bounded recent context is passed to local retriever`() = runBlocking {
        val retriever = FakeMemoryRetriever()
        val repository = createRepository(retriever)
        val users = listOf(
            MessageV2(chatId = 1, content = "Earlier project context", platformType = null),
            MessageV2(chatId = 1, content = "Latest question", platformType = null)
        )
        val assistants = listOf(
            listOf(MessageV2(chatId = 1, content = "Earlier answer", platformType = "platform")),
            emptyList()
        )

        repository.prepareMemoryContext(chatRoom(), users, assistants)

        assertTrue(retriever.lastRequest?.recentContext.orEmpty().contains("Earlier project context"))
        assertTrue(retriever.lastRequest?.recentContext.orEmpty().contains("Earlier answer"))
        assertFalse(retriever.lastRequest?.recentContext.orEmpty().contains("Latest question"))
    }

    @Test
    fun `fake vector retriever substitutes without repository api changes`() = runBlocking {
        val vectorRetriever = FakeVectorMemoryRetriever()
        val repository = createRepository(vectorRetriever)

        val prepared = repository.prepareMemoryContext(
            chatRoom(),
            userMessages("Recall vector-ready context"),
            listOf(emptyList())
        )

        assertEquals(1, vectorRetriever.calls)
        assertEquals(0.95f, prepared.retrievedMemories.single().vectorScore)
        assertTrue(prepared.prompt!!.contains("Vector supplied memory"))
    }

    @Test
    fun `markdown observation reads existing canonical content after store recreation`() = runBlocking {
        val paths = MemoryFilePaths(Files.createTempDirectory("memory-repository-observe-restart").toFile())
        val writer = MemoryFileStore(paths)
        val expected = "# ChatWithChat Memory\n\n- Existing canonical content"
        writer.replaceLongTermMemory(expected).getOrThrow()
        val restartedStore = MemoryFileStore(paths)
        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryFileStore = restartedStore
        )

        val observed = repository.observeLongTermMarkdown().first()

        assertEquals(expected + "\n", observed)
        assertEquals(0L, restartedStore.longTermRevision.value)
    }

    @Test
    fun `markdown observation ignores daily and duplicate content then emits staged commit`() = runBlocking {
        val fileStore = MemoryFileStore(
            MemoryFilePaths(Files.createTempDirectory("memory-repository-observe-live").toFile())
        )
        val initial = "# ChatWithChat Memory\n\n- Initial canonical content"
        fileStore.replaceLongTermMemory(initial).getOrThrow()
        val initialCanonical = fileStore.readLongTermMemory().getOrThrow()
        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryFileStore = fileStore
        )
        val observed = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.observeLongTermMarkdown().take(2).toList(observed)
        }

        assertEquals(listOf(initialCanonical), observed)

        fileStore.appendDailyNote("- Daily-only evidence").getOrThrow()
        fileStore.replaceLongTermMemory(initialCanonical).getOrThrow()
        yield()

        assertEquals(listOf(initialCanonical), observed)

        val target = "# ChatWithChat Memory\n\n- Background staged commit"
        val staged = fileStore.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = target,
            stagingId = "repository-live-observer"
        ).getOrThrow()
        fileStore.commitStagedMemoryFile(staged).getOrThrow()
        collector.join()

        assertEquals(listOf(initialCanonical, target + "\n"), observed)
    }

    @Test
    fun `new completed turns write batch state`() = runBlocking {
        val turnDao = InMemoryMemoryTurnBatchDao()
        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryTurnBatchCoordinator = MemoryTurnBatchCoordinator(turnDao)
        )

        val result = repository.recordCompletedTurn(
            MemoryCompletedTurnInput(
                chatRoom = chatRoom(),
                userMessage = MessageV2(
                    id = 10,
                    chatId = 1,
                    content = "Remember this through the batch path.",
                    platformType = null,
                    createdAt = 100L
                ),
                assistantMessages = listOf(
                    MessageV2(
                        id = 11,
                        chatId = 1,
                        content = "Recorded locally.",
                        platformType = "platform"
                    )
                ),
                preferredPlatformUid = "platform",
                stablePlatformOrder = listOf("platform"),
                completedAt = 101L
            )
        )

        assertTrue(result.recorded)
        assertEquals(1, turnDao.getPendingTurnsForChat(1).size)
    }

    private fun createRepository(retriever: MemoryRetriever): MemoryRepositoryImpl = MemoryRepositoryImpl(
        memoryPromptBuilder = MemoryPromptBuilder(),
        memoryRetriever = retriever
    )

    private fun chatRoom() = ChatRoomV2(id = 1, title = "Chat", enabledPlatform = listOf("platform"))

    private fun userMessages(content: String) = listOf(MessageV2(chatId = 1, content = content, platformType = null))

    private fun retrievalResult(text: String): MemoryRetrievalResult = MemoryRetrievalResult(
        chunkId = "MEMORY.md#mem_1#0",
        entryId = "mem_1",
        sourcePath = "MEMORY.md",
        text = text,
        type = "communication_style",
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        contentHash = "hash",
        lexicalScore = 1f,
        fusedScore = 1f,
        updatedAt = 20L
    )
}

private class FakeMemoryRetriever(
    private val results: List<MemoryRetrievalResult> = emptyList(),
    private val failure: Throwable? = null
) : MemoryRetriever {
    var calls = 0
    var lastRequest: MemoryRetrievalRequest? = null

    override suspend fun retrieve(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> {
        calls += 1
        lastRequest = request
        return failure?.let { Result.failure(it) } ?: Result.success(results)
    }
}

private class FakeVectorMemoryRetriever : MemoryRetriever {
    var calls = 0

    override suspend fun retrieve(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> {
        calls += 1
        return Result.success(
            listOf(
                MemoryRetrievalResult(
                    chunkId = "vector#1",
                    entryId = "mem_vector",
                    sourcePath = "MEMORY.md",
                    text = "Vector supplied memory",
                    type = "project_context",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.USER_CONFIRMED,
                    contentHash = "vector-hash",
                    lexicalScore = null,
                    vectorScore = 0.95f,
                    fusedScore = 0.95f,
                    updatedAt = 100L
                )
            )
        )
    }
}

private class LexicalFallbackMemoryRetriever(
    private val lexicalRetriever: MarkdownLexicalRetriever
) : MemoryRetriever {
    override suspend fun retrieve(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> =
        lexicalRetriever.retrieve(request.copy(strategy = MemoryRetrievalStrategy.LEXICAL))
}
