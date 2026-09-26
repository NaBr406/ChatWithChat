package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryQueryFreshnessTest {
    private val codec = MarkdownMemoryCodec()
    private val chunker = MemoryChunker()
    private val nowSeconds = 1_790_306_209L

    @Test
    fun `expired and completed project facts are absent from chat candidates`() {
        val entries = listOf(
            project("expired", "ChatWithChat test result expired", nowSeconds - 8L * 86_400L),
            project("completed", "ChatWithChat completed project result", nowSeconds, MemoryLifecycleState.COMPLETED),
            project("active", "ChatWithChat active project result", nowSeconds)
        )
        val markdown = codec.renderLongTerm(entries)
        val chatChunks = chunker.chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown,
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        ).chunks
        val snapshot = MemoryCorpusSnapshot(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            canonicalSourceHash = "source",
            recallProjectionHash = "projection",
            generation = 1L,
            chunks = chatChunks
        )
        val retriever = MarkdownLexicalRetriever(snapshotSource = unusedSnapshotSource(), nowMillis = { nowSeconds * 1_000L })
        val request = MemoryRetrievalRequest(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            query = "ChatWithChat project result",
            strategy = MemoryRetrievalStrategy.LEXICAL
        )

        assertEquals(listOf("active"), retriever.rankCandidates(request, request.query, listOf(snapshot)).map { it.entryId })
        assertTrue(chunker.chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown,
            MemoryProjectionPolicy.MAINTENANCE_FULL
        ).chunks.mapNotNull { it.entryId }.containsAll(listOf("expired", "completed", "active")))
    }

    private fun unusedSnapshotSource(): MemoryCorpusSnapshotSource = object : MemoryCorpusSnapshotSource {
        override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> =
            error("rankCandidates does not load snapshots")

        override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> =
            error("rankCandidates does not check revision")

        override suspend fun isProjectionCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> =
            error("rankCandidates does not check projection")
    }

    private fun project(id: String, text: String, updatedAt: Long, lifecycle: String = MemoryLifecycleState.ACTIVE) =
        MarkdownMemoryEntry(
            id = id,
            text = text,
            type = "project_context",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = updatedAt,
            updatedAt = updatedAt,
            canonicalKey = "project.chatwithchat.test_result_$id",
            scope = "project:chatwithchat",
            validity = MemoryValidity.CURRENT,
            recallState = MemoryRecallState.QUERY,
            lifecycleState = lifecycle
        )
}
