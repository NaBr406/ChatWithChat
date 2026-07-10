package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.memory.FakeMemoryIntelligence
import dev.chungjungsoo.gptmobile.data.memory.InMemoryChatClassificationDao
import dev.chungjungsoo.gptmobile.data.memory.InMemoryPersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryCodec
import dev.chungjungsoo.gptmobile.data.memory.MemoryFilePaths
import dev.chungjungsoo.gptmobile.data.memory.MemoryFileStore
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexRebuildResult
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexRebuilder
import dev.chungjungsoo.gptmobile.data.memory.MemoryMarkdownCodec
import dev.chungjungsoo.gptmobile.data.memory.MemoryPromptBuilder
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetrievalRequest
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetrievalResult
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetriever
import dev.chungjungsoo.gptmobile.data.memory.MemorySensitivity
import dev.chungjungsoo.gptmobile.data.memory.MemorySource
import dev.chungjungsoo.gptmobile.data.memory.MemoryStatus
import dev.chungjungsoo.gptmobile.data.memory.testMemory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest {
    @Test
    fun `local markdown retrieval builds prompt with zero intelligence calls`() = runBlocking {
        val intelligence = FakeMemoryIntelligence()
        val retriever = FakeMemoryRetriever(
            results = listOf(retrievalResult(text = "The user prefers implementation before long explanations."))
        )
        val repository = createRepository(intelligence, retriever)

        val prepared = repository.prepareMemoryContext(
            chatRoom = chatRoom(),
            userMessages = userMessages("Please implement this directly."),
            assistantMessages = listOf(emptyList())
        )

        assertEquals(1, retriever.calls)
        assertEquals("Please implement this directly.", retriever.lastRequest?.query)
        assertEquals(900, retriever.lastRequest?.tokenBudget)
        assertTrue(retriever.lastRequest?.includePrivate == true)
        assertEquals(1, prepared.retrievedMemories.size)
        assertTrue(prepared.prompt!!.contains("implementation before long explanations"))
        assertTrue(prepared.prompt.contains("path: MEMORY.md"))
        assertNoIntelligenceCalls(intelligence)
    }

    @Test
    fun `irrelevant or absent local memory is omitted`() = runBlocking {
        val intelligence = FakeMemoryIntelligence()
        val repository = createRepository(intelligence, FakeMemoryRetriever())

        val prepared = repository.prepareMemoryContext(
            chatRoom(),
            userMessages("What is the weather?"),
            listOf(emptyList())
        )

        assertTrue(prepared.retrievedMemories.isEmpty())
        assertNull(prepared.prompt)
        assertNoIntelligenceCalls(intelligence)
    }

    @Test
    fun `local search failure degrades to no memory`() = runBlocking {
        val intelligence = FakeMemoryIntelligence()
        val repository = createRepository(
            intelligence,
            FakeMemoryRetriever(failure = IllegalStateException("index unavailable"))
        )

        val prepared = repository.prepareMemoryContext(
            chatRoom(),
            userMessages("Continue the project."),
            listOf(emptyList())
        )

        assertTrue(prepared.retrievedMemories.isEmpty())
        assertNull(prepared.prompt)
        assertNoIntelligenceCalls(intelligence)
    }

    @Test
    fun `bounded recent context is passed to local retriever`() = runBlocking {
        val retriever = FakeMemoryRetriever()
        val repository = createRepository(FakeMemoryIntelligence(), retriever)
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
        val repository = createRepository(FakeMemoryIntelligence(), vectorRetriever)

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
    fun `active room memories migrate to long term markdown idempotently`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao(
            listOf(
                testMemory(1, "The user prefers natural Chinese explanations."),
                testMemory(2, "Resolved memory should stay legacy only.", status = MemoryStatus.RESOLVED)
            )
        )
        val fileStore = MemoryFileStore(MemoryFilePaths(Files.createTempDirectory("memory-repository-migration-test").toFile()))
        val indexRebuilder = RecordingRepositoryMemoryIndexRebuilder()
        val repository = MemoryRepositoryImpl(
            personalMemoryDao = personalMemoryDao,
            chatClassificationDao = InMemoryChatClassificationDao(),
            memoryIntelligence = FakeMemoryIntelligence(),
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryMarkdownCodec = MemoryMarkdownCodec(),
            memoryFileStore = fileStore,
            structuredMarkdownMemoryCodec = MarkdownMemoryCodec(),
            memoryIndexRebuilder = indexRebuilder
        )

        val firstMigrationCount = repository.migrateActiveMemoriesToMarkdown()
        val secondMigrationCount = repository.migrateActiveMemoriesToMarkdown()
        val markdown = repository.getLongTermMarkdown()

        assertEquals(1, firstMigrationCount)
        assertEquals(0, secondMigrationCount)
        assertTrue(markdown.contains("personal_1"))
        assertFalse(markdown.contains("Resolved memory should stay legacy only."))
        assertEquals(2, personalMemoryDao.memories.size)
        assertEquals(listOf("MEMORY.md"), indexRebuilder.rebuiltFiles.map { it.name })
    }

    @Test
    fun `migration repairs duplicated raw fallback entries`() = runBlocking {
        val rawStatement = "我是一个偏效率主义的人，做事目标明确，不喜欢冗余流程。"
        val personalMemoryDao = InMemoryPersonalMemoryDao(
            listOf(
                testMemory(1, "The user said: $rawStatement", type = "stable_profile").copy(
                    summary = rawStatement,
                    tags = listOf("classifier_fallback")
                ),
                testMemory(2, "The user prefers concise implementation notes.")
            )
        )
        val fileStore = MemoryFileStore(MemoryFilePaths(Files.createTempDirectory("memory-repository-repair-test").toFile()))
        fileStore.replaceLongTermMemory(
            """
            # ChatWithChat Memory

            ## Stable Profile

            <!-- memory:id=personal_1 type=stable_profile sensitivity=normal source=user_confirmed created=10 updated=10 -->
            - The user said: $rawStatement

            <!-- memory:id=personal_1 type=stable_profile sensitivity=normal source=user_confirmed created=10 updated=10 -->
            - The user said: $rawStatement
            """.trimIndent()
        ).getOrThrow()
        val repository = MemoryRepositoryImpl(
            personalMemoryDao = personalMemoryDao,
            chatClassificationDao = InMemoryChatClassificationDao(),
            memoryIntelligence = FakeMemoryIntelligence(),
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryMarkdownCodec = MemoryMarkdownCodec(),
            memoryFileStore = fileStore,
            structuredMarkdownMemoryCodec = MarkdownMemoryCodec(),
            memoryIndexRebuilder = RecordingRepositoryMemoryIndexRebuilder()
        )

        assertEquals(1, repository.migrateActiveMemoriesToMarkdown())
        val markdown = repository.getLongTermMarkdown()
        assertFalse(markdown.contains("The user said:"))
        assertFalse(markdown.contains("personal_1"))
        assertTrue(markdown.contains("personal_2"))
    }

    private fun createRepository(
        intelligence: FakeMemoryIntelligence,
        retriever: MemoryRetriever
    ): MemoryRepositoryImpl = MemoryRepositoryImpl(
        personalMemoryDao = InMemoryPersonalMemoryDao(),
        chatClassificationDao = InMemoryChatClassificationDao(),
        memoryIntelligence = intelligence,
        memoryPromptBuilder = MemoryPromptBuilder(),
        memoryMarkdownCodec = MemoryMarkdownCodec(),
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

    private fun assertNoIntelligenceCalls(intelligence: FakeMemoryIntelligence) {
        assertEquals(0, intelligence.consolidateCalls)
        assertEquals(0, intelligence.classifyCalls)
        assertEquals(0, intelligence.selectCalls)
        assertEquals(0, intelligence.extractCalls)
        assertEquals(0, intelligence.planCalls)
        assertEquals(0, intelligence.markdownProposalCalls)
    }
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

private class RecordingRepositoryMemoryIndexRebuilder : MemoryIndexRebuilder {
    val rebuiltFiles = mutableListOf<File>()

    override suspend fun rebuildFile(file: File): Result<MemoryIndexRebuildResult> {
        rebuiltFiles += file
        return Result.success(MemoryIndexRebuildResult(indexedDocuments = 1, indexedChunks = 1))
    }
}
