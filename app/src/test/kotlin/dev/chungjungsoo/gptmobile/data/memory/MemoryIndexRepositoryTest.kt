package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryIndexDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryChunk
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDocument
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryIndexRepositoryTest {

    @Test
    fun `rebuild all indexes long term and daily markdown files`() = runBlocking {
        val root = createTempRoot()
        val fileStore = createFileStore(root)
        val dao = InMemoryMemoryIndexDao()
        val repository = createRepository(fileStore, dao)
        fileStore.replaceLongTermMemory(
            """
            # ChatWithChat Memory

            ## Projects

            <!-- memory:id=mem_project type=project_context sensitivity=normal source=assistant_inferred created=10 updated=20 -->
            - ChatWithChat should preserve attachments and tool search during memory refactors.
            """.trimIndent()
        ).getOrThrow()
        fileStore.appendDailyNote(
            """
            ## Conversation Notes

            <!-- memory:id=day_1 type=project_context sensitivity=normal source=explicit_user_statement chat=123 created=30 updated=30 -->
            - User asked for a Markdown-first memory index.
            """.trimIndent()
        ).getOrThrow()

        val result = repository.rebuildAll().getOrThrow()

        assertEquals(2, result.indexedDocuments)
        assertEquals(2, result.indexedChunks)
        assertEquals(setOf("MEMORY.md", "memory/2026-07-09.md"), dao.documents.keys)
        assertEquals("long_term", dao.documents.getValue("MEMORY.md").scope)
        assertEquals("daily", dao.documents.getValue("memory/2026-07-09.md").scope)
        assertEquals("mem_project", dao.chunks.getValue("MEMORY.md#mem_project#0").entryId)
        assertEquals(123, dao.chunks.getValue("memory/2026-07-09.md#day_1#0").chatId)
    }

    @Test
    fun `search returns source metadata and filters private memories when requested`() = runBlocking {
        val root = createTempRoot()
        val fileStore = createFileStore(root)
        val dao = InMemoryMemoryIndexDao()
        val repository = createRepository(fileStore, dao)
        fileStore.replaceLongTermMemory(
            """
            # ChatWithChat Memory

            ## Stable Preferences

            <!-- memory:id=mem_public type=communication_style sensitivity=normal source=explicit_user_statement created=10 updated=20 -->
            - The user prefers concrete implementation steps.

            ## Projects

            <!-- memory:id=mem_private type=project_context sensitivity=private source=assistant_inferred created=11 updated=21 -->
            - Private project context mentions concrete implementation risk.
            """.trimIndent()
        ).getOrThrow()
        repository.rebuildAll().getOrThrow()

        val publicOnly = repository.search(
            MemoryIndexSearchRequest(query = "concrete implementation", includePrivate = false)
        ).getOrThrow()
        val withPrivate = repository.search(
            MemoryIndexSearchRequest(query = "concrete implementation", includePrivate = true)
        ).getOrThrow()

        assertEquals(1, publicOnly.size)
        assertEquals("mem_public", publicOnly.single().entryId)
        assertEquals("MEMORY.md", publicOnly.single().sourcePath)
        assertEquals("communication_style", publicOnly.single().type)
        assertEquals(MemorySensitivity.NORMAL, publicOnly.single().sensitivity)
        assertTrue(withPrivate.any { it.entryId == "mem_private" && it.sensitivity == MemorySensitivity.PRIVATE })
    }

    @Test
    fun `rebuild all falls back to section chunks for ordinary markdown`() = runBlocking {
        val root = createTempRoot()
        val fileStore = createFileStore(root)
        val dao = InMemoryMemoryIndexDao()
        val repository = createRepository(fileStore, dao)
        fileStore.replaceLongTermMemory(
            """
            # ChatWithChat Memory

            ## Projects

            This handwritten section has no parseable metadata but should stay searchable.
            """.trimIndent()
        ).getOrThrow()

        val result = repository.rebuildAll().getOrThrow()
        val searchResults = repository.search(MemoryIndexSearchRequest(query = "handwritten searchable")).getOrThrow()

        assertEquals(1, result.indexedDocuments)
        assertEquals(1, result.indexedChunks)
        assertEquals(null, searchResults.single().entryId)
        assertEquals("Projects", searchResults.single().heading)
        assertTrue(searchResults.single().text.contains("handwritten section"))
    }

    @Test
    fun `rebuild deleted file removes derived rows`() = runBlocking {
        val root = createTempRoot()
        val fileStore = createFileStore(root)
        val dao = InMemoryMemoryIndexDao()
        val repository = createRepository(fileStore, dao)
        val dailyFile = fileStore.appendDailyNote("- Temporary daily note").getOrThrow()
        repository.rebuildAll().getOrThrow()

        assertTrue(dao.documents.containsKey("memory/2026-07-09.md"))

        assertTrue(dailyFile.delete())
        val result = repository.rebuildFile(dailyFile).getOrThrow()

        assertEquals(0, result.indexedDocuments)
        assertFalse(dao.documents.containsKey("memory/2026-07-09.md"))
        assertFalse(dao.chunks.values.any { it.sourcePath == "memory/2026-07-09.md" })
    }

    @Test
    fun `empty query returns no search results`() = runBlocking {
        val root = createTempRoot()
        val fileStore = createFileStore(root)
        val repository = createRepository(fileStore, InMemoryMemoryIndexDao())

        val results = repository.search(MemoryIndexSearchRequest(query = " ")).getOrThrow()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `chinese partial query recalls memory through two and three character grams`() = runBlocking {
        val fileStore = createFileStore(createTempRoot())
        val repository = createRepository(fileStore, InMemoryMemoryIndexDao())
        fileStore.replaceLongTermMemory(
            """
            # ChatWithChat Memory

            ## Stable Preferences

            <!-- memory:id=mem_zh type=communication_style sensitivity=normal source=explicit_user_statement -->
            - 用户喜欢直接、具体的回答方式，不需要冗长铺垫。
            """.trimIndent()
        ).getOrThrow()
        repository.rebuildAll().getOrThrow()

        val results = repository.retrieve(
            MemoryRetrievalRequest(query = "请直接回答就好", tokenBudget = 200)
        ).getOrThrow()

        assertEquals("mem_zh", results.single().entryId)
        assertTrue(results.single().lexicalScore!! > 0f)
        assertEquals(results.single().lexicalScore, results.single().fusedScore)
        assertEquals(64, results.single().contentHash.length)
    }

    @Test
    fun `retrieval deduplicates entries and respects token budget`() = runBlocking {
        val fileStore = createFileStore(createTempRoot())
        val repository = createRepository(fileStore, InMemoryMemoryIndexDao())
        val entries = (1..8).map { index ->
            MarkdownMemoryEntry(
                id = "mem_$index",
                text = "The user prefers a concrete answer for topic $index.",
                type = "communication_style",
                sensitivity = MemorySensitivity.NORMAL,
                source = MemorySource.EXPLICIT_USER_STATEMENT
            )
        }
        fileStore.replaceLongTermMemory(MarkdownMemoryCodec().renderLongTerm(entries)).getOrThrow()
        repository.rebuildAll().getOrThrow()

        val results = repository.retrieve(
            MemoryRetrievalRequest(
                query = "concrete answer",
                limit = 8,
                candidateLimit = 20,
                tokenBudget = 70
            )
        ).getOrThrow()

        assertTrue(results.size in 1..2)
        assertEquals(results.size, results.mapNotNull { it.entryId }.distinct().size)
    }

    @Test
    fun `rebuild reports stable changed chunk identities`() = runBlocking {
        val fileStore = createFileStore(createTempRoot())
        val repository = createRepository(fileStore, InMemoryMemoryIndexDao())
        fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(
                listOf(
                    MarkdownMemoryEntry(
                        id = "mem_changed",
                        text = "A changed memory chunk.",
                        type = "project_context",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT
                    )
                )
            )
        ).getOrThrow()

        val result = repository.rebuildAll().getOrThrow()

        assertEquals(setOf("MEMORY.md#mem_changed#0"), result.changedChunkIds)
    }

    private fun createRepository(
        fileStore: MemoryFileStore,
        dao: InMemoryMemoryIndexDao
    ): MemoryIndexRepository = MemoryIndexRepository(
        memoryFileStore = fileStore,
        memoryIndexDao = dao,
        memoryChunker = MemoryChunker(),
        clock = Clock.fixed(Instant.parse("2026-07-09T10:20:30Z"), ZoneOffset.UTC)
    )

    private fun createFileStore(root: File): MemoryFileStore =
        MemoryFileStore(
            paths = MemoryFilePaths(root),
            clock = Clock.fixed(Instant.parse("2026-07-09T10:20:30Z"), ZoneOffset.UTC)
        )

    private fun createTempRoot(): File =
        Files.createTempDirectory("memory-index-repository-test").toFile()
}

private class InMemoryMemoryIndexDao : MemoryIndexDao {
    val documents = linkedMapOf<String, MemoryDocument>()
    val chunks = linkedMapOf<String, MemoryChunk>()

    override suspend fun getDocuments(): List<MemoryDocument> =
        documents.values.sortedWith(compareBy<MemoryDocument> { it.scope }.thenBy { it.sourcePath.lowercase() })

    override suspend fun getDocument(sourcePath: String): MemoryDocument? =
        documents[sourcePath]

    override suspend fun getChunksForSource(sourcePath: String): List<MemoryChunk> =
        chunks.values.filter { it.sourcePath == sourcePath }.sortedBy { it.chunkIndex }

    override suspend fun getSearchCandidates(
        sourcePath: String?,
        includePrivate: Boolean,
        limit: Int
    ): List<MemoryChunk> = chunks.values
        .asSequence()
        .filter { chunk -> sourcePath == null || chunk.sourcePath == sourcePath }
        .filter { chunk ->
            includePrivate || chunk.sensitivity == null || chunk.sensitivity !in setOf(MemorySensitivity.PRIVATE, MemorySensitivity.SENSITIVE)
        }
        .sortedWith(
            compareBy<MemoryChunk> { if (it.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) 0 else 1 }
                .thenByDescending { it.updatedAt }
                .thenByDescending { it.indexedAt }
                .thenBy { it.chunkIndex }
        )
        .take(limit)
        .toList()

    override suspend fun upsertDocument(document: MemoryDocument) {
        documents[document.sourcePath] = document
    }

    override suspend fun upsertDocuments(documents: List<MemoryDocument>) {
        documents.forEach { document -> upsertDocument(document) }
    }

    override suspend fun insertChunks(chunks: List<MemoryChunk>) {
        chunks.forEach { chunk -> this.chunks[chunk.chunkId] = chunk }
    }

    override suspend fun deleteChunksForSource(sourcePath: String) {
        chunks.values.removeAll { it.sourcePath == sourcePath }
    }

    override suspend fun deleteDocument(sourcePath: String) {
        documents.remove(sourcePath)
    }

    override suspend fun clearChunks() {
        chunks.clear()
    }

    override suspend fun clearDocuments() {
        documents.clear()
    }

    override suspend fun replaceDocument(document: MemoryDocument, chunks: List<MemoryChunk>) {
        deleteChunksForSource(document.sourcePath)
        upsertDocument(document)
        insertChunks(chunks)
    }

    override suspend fun removeDocument(sourcePath: String) {
        deleteChunksForSource(sourcePath)
        deleteDocument(sourcePath)
    }

    override suspend fun replaceAll(documents: List<MemoryDocument>, chunks: List<MemoryChunk>) {
        clearChunks()
        clearDocuments()
        upsertDocuments(documents)
        insertChunks(chunks)
    }
}
