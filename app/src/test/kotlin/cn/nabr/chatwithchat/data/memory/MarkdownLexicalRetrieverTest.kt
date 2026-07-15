package cn.nabr.chatwithchat.data.memory

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLexicalRetrieverTest {

    @Test
    fun `ordinary recall excludes daily content while maintenance can search it`() = runBlocking {
        val fileStore = createFileStore()
        fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(
                listOf(memoryEntry("mem_visible", "Visible preference uses the silver-lantern phrase."))
            )
        ).getOrThrow()
        fileStore.appendDailyNote(
            MarkdownMemoryCodec().renderDailyAppend(
                listOf(memoryEntry("day_hidden", "Hidden observation uses the violet-compass phrase."))
            )
        ).getOrThrow()
        val retriever = createRetriever(fileStore)

        val chatResults = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "violet-compass")
        ).getOrThrow()
        val maintenanceResults = retriever.retrieveWorkingSet(
            request(MemoryCorpus.MAINTENANCE_WORKING_SET, "violet-compass")
        ).getOrThrow()

        assertTrue(chatResults.isEmpty())
        assertEquals("day_hidden", maintenanceResults.single().entryId)
        assertTrue(maintenanceResults.single().sourcePath.startsWith("memory/"))
    }

    @Test
    fun `deleting or replacing memory text changes recall immediately`() = runBlocking {
        val fileStore = createFileStore()
        val retriever = createRetriever(fileStore)
        fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(
                listOf(memoryEntry("mem_preference", "Use the cobalt-kestrel response style."))
            )
        ).getOrThrow()

        assertEquals(
            "mem_preference",
            retriever.retrieve(request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "cobalt-kestrel"))
                .getOrThrow()
                .single()
                .entryId
        )

        fileStore.replaceLongTermMemory(MemoryFileStore.LONG_TERM_MEMORY_HEADER).getOrThrow()

        assertTrue(
            retriever.retrieve(request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "cobalt-kestrel"))
                .getOrThrow()
                .isEmpty()
        )

        fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(
                listOf(memoryEntry("mem_preference", "Use the amber-orchid response style."))
            )
        ).getOrThrow()

        assertEquals(
            "mem_preference",
            retriever.retrieve(request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "amber-orchid"))
                .getOrThrow()
                .single()
                .entryId
        )
    }

    @Test
    fun `chinese grams and english tokens retrieve current markdown`() = runBlocking {
        val fileStore = createFileStore()
        fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(
                listOf(
                    memoryEntry("mem_zh", "用户喜欢直接、具体的回答方式，不需要冗长铺垫。", updatedAt = 20L),
                    memoryEntry("mem_en", "The user prefers concrete implementation steps.", updatedAt = 10L)
                )
            )
        ).getOrThrow()
        val retriever = createRetriever(fileStore)

        val chinese = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "请直接回答就好")
        ).getOrThrow()
        val english = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "implementation steps")
        ).getOrThrow()

        assertEquals("mem_zh", chinese.first().entryId)
        assertTrue(chinese.first().lexicalScore!! > 0f)
        assertEquals("mem_en", english.first().entryId)
        assertEquals(english.first().lexicalScore, english.first().fusedScore)
    }

    @Test
    fun `retrieval deduplicates entries before packing token budget`() = runBlocking {
        val duplicateEntryChunks = listOf(
            corpusChunk("MEMORY.md#mem_duplicate#0", "mem_duplicate", "Concrete answer context part one."),
            corpusChunk("MEMORY.md#mem_duplicate#1", "mem_duplicate", "Concrete answer context part two."),
            corpusChunk("MEMORY.md#mem_other#0", "mem_other", "Concrete answer for another topic.")
        )
        val source = StaticSnapshotSource(snapshot(1L, duplicateEntryChunks))
        val retriever = MarkdownLexicalRetriever(source)

        val results = retriever.retrieve(
            MemoryRetrievalRequest(
                corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
                query = "concrete answer",
                limit = 8,
                candidateLimit = 20,
                tokenBudget = 70
            )
        ).getOrThrow()

        assertEquals(results.size, results.mapNotNull { result -> result.entryId }.distinct().size)
        assertTrue(results.size in 1..2)
    }

    @Test
    fun `exact text duplicates do not consume the lexical candidate limit`() = runBlocking {
        val chunks = listOf(
            corpusChunk("MEMORY.md#mem_duplicate_a#0", "mem_duplicate_a", "Concrete answer memory."),
            corpusChunk("MEMORY.md#mem_duplicate_b#0", "mem_duplicate_b", "\u00a0CONCRETE\u3000ANSWER MEMORY.  "),
            corpusChunk("MEMORY.md#mem_unique#0", "mem_unique", "Concrete answer unique.")
        )
        val retriever = MarkdownLexicalRetriever(StaticSnapshotSource(snapshot(1L, chunks)))

        val results = retriever.retrieve(
            MemoryRetrievalRequest(
                corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
                query = "concrete answer",
                limit = 2,
                candidateLimit = 2,
                tokenBudget = 300
            )
        ).getOrThrow()

        assertEquals(listOf("mem_duplicate_a", "mem_unique"), results.map { result -> result.entryId })
    }

    @Test
    fun `exact text duplicates do not consume the lexical token budget`() = runBlocking {
        val chunks = listOf(
            corpusChunk("MEMORY.md#mem_duplicate_a#0", "mem_duplicate_a", "Shared duplicate."),
            corpusChunk("MEMORY.md#mem_duplicate_b#0", "mem_duplicate_b", "  SHARED   DUPLICATE.  "),
            corpusChunk("MEMORY.md#mem_unique#0", "mem_unique", "Shared unique.")
        )
        val retriever = MarkdownLexicalRetriever(StaticSnapshotSource(snapshot(1L, chunks)))

        val results = retriever.retrieve(
            MemoryRetrievalRequest(
                corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
                query = "shared",
                limit = 3,
                candidateLimit = 3,
                tokenBudget = 60
            )
        ).getOrThrow()

        assertEquals(listOf("mem_duplicate_a", "mem_unique"), results.map { result -> result.entryId })
    }

    @Test
    fun `retrieval retries once when corpus revision changes`() = runBlocking {
        val source = SequencedSnapshotSource(
            snapshots = listOf(
                snapshot(1L, listOf(corpusChunk("MEMORY.md#old#0", "old", "Old revision phrase."))),
                snapshot(2L, listOf(corpusChunk("MEMORY.md#new#0", "new", "New revision phrase.")))
            ),
            currentGeneration = 2L
        )
        val retriever = MarkdownLexicalRetriever(source)

        val results = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "revision phrase")
        ).getOrThrow()

        assertEquals(2, source.snapshotCalls)
        assertEquals("new", results.single().entryId)
    }

    @Test
    fun `private metadata filter is applied to markdown chunks`() = runBlocking {
        val fileStore = createFileStore()
        fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(
                listOf(
                    memoryEntry("mem_public", "Concrete implementation preference."),
                    memoryEntry("mem_private", "Private concrete implementation context.").copy(
                        sensitivity = MemorySensitivity.PRIVATE
                    )
                )
            )
        ).getOrThrow()
        val retriever = createRetriever(fileStore)

        val results = retriever.retrieve(
            MemoryRetrievalRequest(
                corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
                query = "concrete implementation",
                includePrivate = false
            )
        ).getOrThrow()

        assertEquals(listOf("mem_public"), results.map { result -> result.entryId })
    }

    @Test
    fun `ordinary and maintenance interfaces reject the other corpus`() = runBlocking {
        val retriever = createRetriever(createFileStore())

        val ordinaryResult = retriever.retrieve(
            request(MemoryCorpus.MAINTENANCE_WORKING_SET, "query")
        )
        val maintenanceResult = retriever.retrieveWorkingSet(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "query")
        )

        assertTrue(ordinaryResult.isFailure)
        assertTrue(maintenanceResult.isFailure)
    }

    private fun createRetriever(fileStore: MemoryFileStore): MarkdownLexicalRetriever =
        MarkdownLexicalRetriever(MemoryCorpusSnapshotter(fileStore, MemoryChunker()))

    private fun createFileStore(): MemoryFileStore = MemoryFileStore(
        paths = MemoryFilePaths(Files.createTempDirectory("markdown-lexical-retriever").toFile()),
        clock = Clock.fixed(Instant.parse("2026-07-13T08:00:00Z"), ZoneOffset.UTC)
    )

    private fun request(corpus: MemoryCorpus, query: String): MemoryRetrievalRequest = MemoryRetrievalRequest(
        corpus = corpus,
        query = query,
        tokenBudget = 300
    )

    private fun memoryEntry(id: String, text: String, updatedAt: Long = 10L): MarkdownMemoryEntry =
        MarkdownMemoryEntry(
            id = id,
            text = text,
            type = "communication_style",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 1L,
            updatedAt = updatedAt
        )

    private fun snapshot(generation: Long, chunks: List<MemoryCorpusChunk>): MemoryCorpusSnapshot =
        MemoryCorpusSnapshot(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            sourceHash = "source-$generation",
            generation = generation,
            chunks = chunks
        )

    private fun corpusChunk(chunkId: String, entryId: String, text: String): MemoryCorpusChunk =
        MemoryCorpusChunk(
            chunkId = chunkId,
            entryId = entryId,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            chunkIndex = chunkId.substringAfterLast('#').toInt(),
            heading = "Stable Preferences",
            text = text,
            type = "communication_style",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            chatId = null,
            createdAt = 1L,
            updatedAt = 2L,
            contentHash = text.sha256Utf8()
        )
}

private class StaticSnapshotSource(
    private val snapshot: MemoryCorpusSnapshot
) : MemoryCorpusSnapshotSource {
    override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> =
        Result.success(listOf(snapshot))

    override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> = Result.success(true)
}

private class SequencedSnapshotSource(
    private val snapshots: List<MemoryCorpusSnapshot>,
    private val currentGeneration: Long
) : MemoryCorpusSnapshotSource {
    var snapshotCalls = 0

    override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> {
        val snapshot = snapshots[snapshotCalls.coerceAtMost(snapshots.lastIndex)]
        snapshotCalls += 1
        return Result.success(listOf(snapshot))
    }

    override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> =
        Result.success(snapshots.single().generation == currentGeneration)
}
