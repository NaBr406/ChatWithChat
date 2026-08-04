package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.database.InMemoryMemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.debug.PromptTraceStore
import cn.nabr.chatwithchat.data.memory.MarkdownLexicalRetriever
import cn.nabr.chatwithchat.data.memory.MarkdownMemoryCodec
import cn.nabr.chatwithchat.data.memory.MarkdownMemoryEntry
import cn.nabr.chatwithchat.data.memory.MemoryChunker
import cn.nabr.chatwithchat.data.memory.MemoryCompletedTurnInput
import cn.nabr.chatwithchat.data.memory.MemoryCorpus
import cn.nabr.chatwithchat.data.memory.MemoryCorpusSnapshotter
import cn.nabr.chatwithchat.data.memory.MemoryFilePaths
import cn.nabr.chatwithchat.data.memory.MemoryFileStore
import cn.nabr.chatwithchat.data.memory.MemoryProjectionDiagnostic
import cn.nabr.chatwithchat.data.memory.MemoryPromptBuilder
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalMode
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalReport
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalRequest
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalResult
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalStrategy
import cn.nabr.chatwithchat.data.memory.MemoryRetriever
import cn.nabr.chatwithchat.data.memory.MemorySensitivity
import cn.nabr.chatwithchat.data.memory.MemorySource
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchCoordinator
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
        assertNull(retriever.lastRequest?.tokenBudget)
        assertTrue(retriever.lastRequest?.includePrivate == true)
        assertEquals(MemoryRetrievalStrategy.HYBRID, retriever.lastRequest?.strategy)
        assertEquals(1, prepared.retrievedMemories.size)
        val prompt = prepared.prompt.orEmpty()
        assertTrue(prompt.contains("implementation before long explanations"))
        assertFalse(prompt.contains("MEMORY.md"))
        assertFalse(prompt.contains("type:"))
        assertFalse(prompt.contains("sensitivity:"))
        assertFalse(prompt.contains("source:"))
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
    fun `greeting invokes recall once and freezes a seeded core only snapshot`() = runBlocking {
        val core = coreRetrievalResult("mem_address", "Call the user Alex.")
        val retriever = FakeMemoryRetriever(
            report = MemoryRetrievalReport(
                results = emptyList(),
                coreResults = listOf(core),
                mode = MemoryRetrievalMode.NONE,
                canonicalRevision = 17,
                canonicalSourceHash = "canonical-hash",
                recallProjectionHash = "projection-hash"
            )
        )
        val prepared = createRepository(retriever).prepareMemoryContext(
            chatRoom(),
            userMessages("你好"),
            listOf(emptyList())
        )

        assertEquals(1, retriever.calls)
        assertEquals(listOf("Call the user Alex."), prepared.snapshot.coreFacts.map { fact -> fact.text })
        assertTrue(prepared.snapshot.queryFacts.isEmpty())
        assertEquals(MemoryRetrievalMode.NONE, prepared.snapshot.mode)
        assertEquals(17L, prepared.snapshot.canonicalRevision)
        assertEquals("canonical-hash", prepared.snapshot.canonicalSourceHash)
        assertEquals("projection-hash", prepared.snapshot.recallProjectionHash)
        assertTrue(prepared.prompt.orEmpty().contains("Call the user Alex."))
    }

    @Test
    fun `greeting without a stored core still invokes recall exactly once`() = runBlocking {
        val retriever = FakeMemoryRetriever()

        val prepared = createRepository(retriever).prepareMemoryContext(
            chatRoom(),
            userMessages("你好"),
            listOf(emptyList())
        )

        assertEquals(1, retriever.calls)
        assertTrue(prepared.snapshot.coreFacts.isEmpty())
        assertTrue(prepared.snapshot.queryFacts.isEmpty())
        assertNull(prepared.prompt)
    }

    @Test
    fun `repository freezes all returned query facts up to the top eight limit`() = runBlocking {
        val results = (1..5).map { index ->
            retrievalResult("Query fact $index.").copy(
                chunkId = "MEMORY.md#mem_$index#0",
                entryId = "mem_$index",
                embeddingContentHash = "hash-$index"
            )
        }
        val retriever = FakeMemoryRetriever(results = results)

        val prepared = createRepository(retriever).prepareMemoryContext(
            chatRoom(),
            userMessages("query fact"),
            listOf(emptyList())
        )

        assertEquals(5, prepared.snapshot.queryFacts.size)
        assertEquals(5, prepared.retrievedMemories.size)
        assertEquals(prepared.prompt, prepared.snapshot.prompt)
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
        val visiblePrompt = visible.prompt.orEmpty()
        assertTrue(visiblePrompt.contains("violet-compass"))
        assertFalse(visiblePrompt.contains("MEMORY.md"))
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
    fun `ordinary prompt drops metadata shaped fact text and keeps safe facts`() = runBlocking {
        val unsafeTexts = listOf(
            "scope: general",
            "jobId: job_42",
            "checkpointId: checkpoint_7",
            "maintenanceHash: abcdef123456",
            "memoryIds: [opaque_1], hitCount: 1"
        )
        val unsafe = unsafeTexts.mapIndexed { index, text ->
            retrievalResult(text).copy(
                chunkId = "MEMORY.md#unsafe_$index#0",
                entryId = "unsafe_$index",
                embeddingContentHash = "unsafe-hash-$index"
            )
        }
        val safe = retrievalResult("The user prefers concise answers.").copy(
            chunkId = "MEMORY.md#safe#0",
            entryId = "safe",
            embeddingContentHash = "safe-hash"
        )
        val prepared = createRepository(FakeMemoryRetriever(results = unsafe + safe)).prepareMemoryContext(
            chatRoom(),
            userMessages("concise answers"),
            listOf(emptyList())
        )

        assertEquals(listOf("safe"), prepared.retrievedMemories.mapNotNull { memory -> memory.entryId })
        unsafeTexts.forEach { text -> assertFalse(prepared.prompt.orEmpty().contains(text)) }
        assertTrue(prepared.prompt.orEmpty().contains("The user prefers concise answers."))
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
    fun `memory recall diagnostics are bound to the current turn`() = runBlocking {
        val store = PromptTraceStore()
        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryRetriever = FakeMemoryRetriever(
                results = listOf(retrievalResult("Bound memory"))
            ),
            promptTraceStore = store
        )

        repository.prepareMemoryContext(
            chatRoom = chatRoom(),
            userMessages = listOf(MessageV2(id = 31, chatId = 1, content = "Recall this", platformType = null)),
            assistantMessages = listOf(emptyList())
        )

        val entry = store.record(
            chatId = 1,
            turnNumber = 1,
            userMessageId = 31,
            platformUid = "platform",
            platformName = "Platform",
            clientType = cn.nabr.chatwithchat.data.model.ClientType.OPENAI,
            model = "model",
            stage = cn.nabr.chatwithchat.data.debug.PromptTraceStage.ANSWER,
            systemPrompt = "system"
        )

        assertEquals(MemoryRetrievalMode.LEXICAL, entry.memoryRecall?.mode)
        assertEquals(1, entry.memoryRecall?.hitCount)
        assertEquals(listOf("mem_1"), entry.memoryRecall?.memoryIds)
        assertEquals(0, entry.memoryRecall?.coreCount)
        assertEquals(1, entry.memoryRecall?.queryCount)
    }

    @Test
    fun `memory recall failure is visible in prompt trace diagnostics`() = runBlocking {
        val store = PromptTraceStore()
        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryRetriever = FakeMemoryRetriever(failure = IllegalStateException("index unavailable")),
            promptTraceStore = store
        )

        repository.prepareMemoryContext(
            chatRoom = chatRoom(),
            userMessages = listOf(MessageV2(id = 32, chatId = 1, content = "Recall this", platformType = null)),
            assistantMessages = listOf(emptyList())
        )

        val entry = store.record(
            chatId = 1,
            turnNumber = 1,
            userMessageId = 32,
            platformUid = "platform",
            platformName = "Platform",
            clientType = cn.nabr.chatwithchat.data.model.ClientType.OPENAI,
            model = "model",
            stage = cn.nabr.chatwithchat.data.debug.PromptTraceStage.ANSWER,
            systemPrompt = "system"
        )

        assertEquals(MemoryRetrievalMode.FAILED, entry.memoryRecall?.mode)
        assertEquals("index unavailable", entry.memoryRecall?.errorMessage)
    }

    @Test
    fun `projection diagnostic codes are bound to the current turn`() = runBlocking {
        val store = PromptTraceStore()
        val diagnostic = MemoryProjectionDiagnostic(
            code = "chat_projection_parse_failed",
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            count = 2
        )
        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryRetriever = FakeMemoryRetriever(
                report = MemoryRetrievalReport(
                    results = emptyList(),
                    mode = MemoryRetrievalMode.FAILED,
                    errorMessage = "chat_projection_parse_failed:2",
                    diagnostics = listOf(diagnostic)
                )
            ),
            promptTraceStore = store
        )

        repository.prepareMemoryContext(
            chatRoom = chatRoom(),
            userMessages = listOf(MessageV2(id = 33, chatId = 1, content = "Recall this", platformType = null)),
            assistantMessages = listOf(emptyList())
        )

        val entry = store.record(
            chatId = 1,
            turnNumber = 1,
            userMessageId = 33,
            platformUid = "platform",
            platformName = "Platform",
            clientType = cn.nabr.chatwithchat.data.model.ClientType.OPENAI,
            model = "model",
            stage = cn.nabr.chatwithchat.data.debug.PromptTraceStage.ANSWER,
            systemPrompt = "system"
        )

        assertEquals(MemoryRetrievalMode.FAILED, entry.memoryRecall?.mode)
        assertEquals(listOf("chat_projection_parse_failed"), entry.memoryRecall?.diagnosticCodes)
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
        embeddingContentHash = "hash",
        lexicalScore = 1f,
        fusedScore = 1f,
        updatedAt = 20L
    )

    private fun coreRetrievalResult(id: String, text: String): MemoryRetrievalResult = retrievalResult(text).copy(
        chunkId = "MEMORY.md#$id#0",
        entryId = id,
        type = "stable_profile",
        canonicalKey = "identity.preferred_address",
        scope = "general",
        validity = "current",
        recallState = "core",
        embeddingContentHash = "$id-hash"
    )
}

private class FakeMemoryRetriever(
    private val results: List<MemoryRetrievalResult> = emptyList(),
    private val failure: Throwable? = null,
    private val report: MemoryRetrievalReport? = null
) : MemoryRetriever {
    var calls = 0
    var lastRequest: MemoryRetrievalRequest? = null

    override suspend fun retrieve(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> {
        calls += 1
        lastRequest = request
        return failure?.let { Result.failure(it) } ?: Result.success(results)
    }

    override suspend fun retrieveWithDiagnostics(request: MemoryRetrievalRequest): Result<MemoryRetrievalReport> {
        if (report == null) return super.retrieveWithDiagnostics(request)
        calls += 1
        lastRequest = request
        return Result.success(report)
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
                    embeddingContentHash = "vector-hash",
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

    override suspend fun retrieveWithDiagnostics(request: MemoryRetrievalRequest): Result<MemoryRetrievalReport> =
        lexicalRetriever.retrieveWithDiagnostics(request.copy(strategy = MemoryRetrievalStrategy.LEXICAL))
}
