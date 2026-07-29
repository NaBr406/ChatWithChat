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

        val chatReport = retriever.retrieveWithDiagnostics(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "violet-compass")
        ).getOrThrow()
        val maintenanceResults = retriever.retrieveWorkingSet(
            request(MemoryCorpus.MAINTENANCE_WORKING_SET, "violet-compass")
        ).getOrThrow()

        assertTrue(chatReport.results.isEmpty())
        assertEquals("mem_visible", chatReport.coreResults.single().entryId)
        assertEquals("day_hidden", maintenanceResults.single().entryId)
        assertTrue(maintenanceResults.single().sourcePath.startsWith("memory/"))
    }

    @Test
    fun `ordinary recall excludes lifecycle history while maintenance can search it`() = runBlocking {
        val fileStore = createFileStore()
        val active = memoryEntry("mem_active", "Visible current response preference.").copy(
            canonicalKey = "communication.response_style"
        )
        val hiddenEntries = listOf(
            memoryEntry("mem_obsolete", "Obsolete-only-marker response preference.").copy(
                canonicalKey = active.canonicalKey,
                validity = MemoryValidity.OBSOLETE,
                supersededBy = active.id,
                recallState = MemoryRecallState.MAINTENANCE_ONLY
            ),
            memoryEntry("mem_contested", "Contested-only-marker response preference.").copy(
                canonicalKey = "communication.contested_style",
                validity = MemoryValidity.CONTESTED,
                recallState = MemoryRecallState.MAINTENANCE_ONLY
            ),
            memoryEntry("mem_maintenance", "Maintenance-only-marker note.").copy(
                canonicalKey = "communication.maintenance_note",
                recallState = MemoryRecallState.MAINTENANCE_ONLY
            )
        )
        fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(active) + hiddenEntries)
        ).getOrThrow()
        val retriever = createRetriever(fileStore)

        hiddenEntries.forEach { hidden ->
            val lifecycleQuery = hidden.text.substringBefore(' ')
            val chatResults = retriever.retrieve(
                request(MemoryCorpus.CHAT_RECALL_LONG_TERM, lifecycleQuery)
            ).getOrThrow()
            val maintenanceResults = retriever.retrieveWorkingSet(
                request(MemoryCorpus.MAINTENANCE_WORKING_SET, lifecycleQuery)
            ).getOrThrow()

            assertTrue(chatResults.isEmpty())
            assertEquals(hidden.id, maintenanceResults.first().entryId)
            assertEquals(MemoryRecallState.MAINTENANCE_ONLY, maintenanceResults.first().recallState)
        }
    }

    @Test
    fun `maintenance retrieval preserves complete managed metadata`() = runBlocking {
        val fileStore = createFileStore()
        val active = memoryEntry("mem_active_metadata", "Current response preference.").copy(
            canonicalKey = "communication.response_style",
            scope = MemoryScope.WORK
        )
        val history = memoryEntry("mem_history_metadata", "Archived-metadata-marker response preference.").copy(
            chatId = 7,
            section = "Archived",
            canonicalKey = active.canonicalKey,
            scope = active.scope,
            lastObservedAt = 12L,
            validity = MemoryValidity.OBSOLETE,
            supersededBy = active.id,
            recallState = MemoryRecallState.MAINTENANCE_ONLY,
            evidenceRefs = listOf("chat:7:user:3"),
            extraMetadata = mapOf("legacy_flag" to "kept")
        )
        fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(active, history))
        ).getOrThrow()

        val result = createRetriever(fileStore).retrieveWorkingSet(
            request(MemoryCorpus.MAINTENANCE_WORKING_SET, "Archived-metadata-marker")
        ).getOrThrow().single()

        assertEquals(history.id, result.entryId)
        assertEquals(history.chatId, result.chatId)
        assertEquals(history.createdAt, result.createdAt)
        assertEquals(history.updatedAt, result.updatedAt)
        assertEquals(history.section, result.section)
        assertEquals(history.canonicalKey, result.canonicalKey)
        assertEquals(history.scope, result.scope)
        assertEquals(history.lastObservedAt, result.lastObservedAt)
        assertEquals(history.validity, result.validity)
        assertEquals(history.supersededBy, result.supersededBy)
        assertEquals(history.recallState, result.recallState)
        assertEquals(history.evidenceRefs, result.evidenceRefs)
        assertEquals(history.extraMetadata, result.extraMetadata)
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

        val chinese = retriever.retrieveWithDiagnostics(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "请直接回答就好")
        ).getOrThrow()
        val english = retriever.retrieveWithDiagnostics(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "implementation steps")
        ).getOrThrow()

        assertEquals("mem_zh", chinese.coreResults.single().entryId)
        assertTrue(chinese.results.isEmpty())
        assertEquals("mem_zh", english.coreResults.single().entryId)
        assertEquals("mem_en", english.results.single().entryId)
        assertEquals(english.results.single().lexicalScore, english.results.single().fusedScore)
    }

    @Test
    fun `chat recall admits a meaningful latin token at the absolute score floor`() = runBlocking {
        val chunk = corpusChunk(
            chunkId = "MEMORY.md#mem_floor#0",
            entryId = "mem_floor",
            text = "Alpha preference."
        )
        val retriever = MarkdownLexicalRetriever(StaticSnapshotSource(snapshot(1L, listOf(chunk))))

        val result = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "alpha missing")
        ).getOrThrow().single()

        assertEquals(1.25f, result.lexicalScore!!, 0.000001f)
    }

    @Test
    fun `chat recall admits a meaningful CJK bigram`() = runBlocking {
        val chunk = corpusChunk(
            chunkId = "MEMORY.md#mem_bigram#0",
            entryId = "mem_bigram",
            text = "用户喜欢蓝色。"
        )
        val retriever = MarkdownLexicalRetriever(StaticSnapshotSource(snapshot(1L, listOf(chunk))))

        val result = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "蓝色 未知")
        ).getOrThrow().single()

        assertTrue(result.lexicalScore!! >= 1.25f)
    }

    @Test
    fun `chat recall rejects an exact single CJK character`() = runBlocking {
        val chunk = corpusChunk(
            chunkId = "MEMORY.md#mem_single#0",
            entryId = "mem_single",
            text = "读"
        )
        val retriever = MarkdownLexicalRetriever(StaticSnapshotSource(snapshot(1L, listOf(chunk))))

        val results = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "读")
        ).getOrThrow()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `chat recall rejects multiple isolated weak CJK characters`() = runBlocking {
        val chunk = corpusChunk(
            chunkId = "MEMORY.md#mem_weak#0",
            entryId = "mem_weak",
            text = "甲。乙。丙。"
        )
        val retriever = MarkdownLexicalRetriever(StaticSnapshotSource(snapshot(1L, listOf(chunk))))

        val results = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "甲 乙 丙")
        ).getOrThrow()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `heading and type metadata cannot satisfy chat relevance`() = runBlocking {
        val chunk = corpusChunk(
            chunkId = "MEMORY.md#mem_metadata#0",
            entryId = "mem_metadata",
            text = "Natural language about concise replies."
        ).copy(
            heading = "metadata-heading-needle",
            type = "metadata_type_needle"
        )
        val retriever = MarkdownLexicalRetriever(StaticSnapshotSource(snapshot(1L, listOf(chunk))))

        val headingResults = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "metadata-heading-needle")
        ).getOrThrow()
        val typeResults = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "metadata_type_needle")
        ).getOrThrow()

        assertTrue(headingResults.isEmpty())
        assertTrue(typeResults.isEmpty())
    }

    @Test
    fun `maintenance retrieval keeps positive weak matches below the chat floor`() = runBlocking {
        val chunk = corpusChunk(
            chunkId = "MEMORY.md#mem_maintenance_weak#0",
            entryId = "mem_maintenance_weak",
            text = "用户正在读书。"
        )
        val maintenanceSnapshot = snapshot(1L, listOf(chunk)).copy(
            corpus = MemoryCorpus.MAINTENANCE_WORKING_SET
        )
        val retriever = MarkdownLexicalRetriever(StaticSnapshotSource(maintenanceSnapshot))

        val result = retriever.retrieveWorkingSet(
            request(MemoryCorpus.MAINTENANCE_WORKING_SET, "读 甲")
        ).getOrThrow().single()

        val score = result.lexicalScore!!
        assertTrue(score > 0f)
        assertTrue(score < 1.25f)
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
    fun `chat query defaults to general scope while explicit work scope stays selectable`() = runBlocking {
        val general = corpusChunk(
            "MEMORY.md#mem_general#0",
            "mem_general",
            "Preferred address is Alex."
        ).copy(scope = MemoryScope.GENERAL)
        val work = corpusChunk(
            "MEMORY.md#mem_work#0",
            "mem_work",
            "Preferred work address is Director."
        ).copy(scope = MemoryScope.WORK)
        val retriever = MarkdownLexicalRetriever(StaticSnapshotSource(snapshot(1L, listOf(general, work))))

        val generalResults = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "preferred address")
        ).getOrThrow()
        val workResults = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "preferred address").copy(recallScope = MemoryScope.WORK)
        ).getOrThrow()

        assertEquals(listOf("mem_general"), generalResults.map { result -> result.entryId })
        assertEquals(listOf("mem_work"), workResults.map { result -> result.entryId })
    }

    @Test
    fun `latin compound components satisfy the meaningful match gate`() = runBlocking {
        val retriever = MarkdownLexicalRetriever(
            StaticSnapshotSource(
                snapshot(
                    1L,
                    listOf(corpusChunk("MEMORY.md#mem_server#0", "mem_server", "The minecraft-server uses Paper."))
                )
            )
        )

        val results = retriever.retrieve(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "server")
        ).getOrThrow()

        assertEquals(listOf("mem_server"), results.map { result -> result.entryId })
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
    fun `repeated projection changes fail with an observable diagnostic`() = runBlocking {
        val source = SequencedSnapshotSource(
            snapshots = listOf(
                snapshot(1L, listOf(corpusChunk("MEMORY.md#old#0", "old", "Old revision phrase."))),
                snapshot(2L, listOf(corpusChunk("MEMORY.md#middle#0", "middle", "Middle revision phrase.")))
            ),
            currentGeneration = 3L
        )
        val report = MarkdownLexicalRetriever(source).retrieveWithDiagnostics(
            request(MemoryCorpus.CHAT_RECALL_LONG_TERM, "revision phrase")
        ).getOrThrow()

        assertEquals(MemoryRetrievalMode.FAILED, report.mode)
        assertEquals("recall_snapshot_changed_during_retrieval:2", report.errorMessage)
        assertEquals(
            listOf("recall_snapshot_changed_during_retrieval"),
            report.diagnostics.map(MemoryProjectionDiagnostic::code)
        )
        assertEquals(2, source.snapshotCalls)
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
            canonicalSourceHash = "source-$generation",
            recallProjectionHash = "source-$generation",
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
            embeddingContentHash = text.sha256Utf8()
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
