package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryChunkerTest {

    @Test
    fun `embedding text and hash depend only on normalized natural language`() {
        val chunker = MemoryChunker()
        val entry = MarkdownMemoryEntry(
            id = "mem_project",
            text = "The user is building a local memory index.",
            type = "project_context",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 10L,
            updatedAt = 20L
        )
        val markdown = MarkdownMemoryCodec().renderLongTerm(listOf(entry))

        val firstResult = chunker.chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown,
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        )
        val first = firstResult.chunks
        val second = chunker.chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown,
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        ).chunks
        val changed = chunker.chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown.replace("local memory index", "current Markdown snapshot"),
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        ).chunks
        val metadataChangedResult = chunker.chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            MarkdownMemoryCodec().renderLongTerm(
                listOf(
                    entry.copy(
                        id = "mem_renamed",
                        createdAt = 11L,
                        updatedAt = 21L,
                        lastObservedAt = 22L,
                        evidenceRefs = listOf("chat:1:user:2")
                    )
                )
            ),
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        )
        val metadataChanged = metadataChangedResult.chunks
        val sourceChangedResult = chunker.chunksFor(
            "another-source.md",
            markdown,
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        )
        val sourceChanged = sourceChangedResult.chunks

        assertEquals(first, second)
        assertEquals("MEMORY.md#mem_project#0", first.single().chunkId)
        assertEquals("The user is building a local memory index.", first.single().embeddingText)
        assertEquals(64, first.single().embeddingContentHash.length)
        assertNotEquals(first.single().embeddingContentHash, changed.single().embeddingContentHash)
        assertEquals(first.single().embeddingContentHash, metadataChanged.single().embeddingContentHash)
        assertEquals(first.single().embeddingContentHash, sourceChanged.single().embeddingContentHash)
        assertEquals(first.single().rankingHash, metadataChanged.single().rankingHash)
        assertEquals(first.single().rankingHash, sourceChanged.single().rankingHash)
        assertNotEquals(firstResult.projectionHash, metadataChangedResult.projectionHash)
        assertNotEquals(firstResult.projectionHash, sourceChangedResult.projectionHash)
    }

    @Test
    fun `fallback chunks preserve section metadata`() {
        val chunks = MemoryChunker().chunksFor(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown = """
                # ChatWithChat Memory

                ## Projects

                Handwritten Markdown remains searchable without metadata comments.
            """.trimIndent(),
            projectionPolicy = MemoryProjectionPolicy.MAINTENANCE_FULL
        ).chunks

        assertEquals(1, chunks.size)
        assertEquals("Projects", chunks.single().heading)
        assertEquals(null, chunks.single().entryId)
        assertTrue(chunks.single().text.contains("Handwritten Markdown"))
    }

    @Test
    fun `malformed metadata only document does not fall back to raw markdown chunks`() {
        val result = MemoryChunker().chunksFor(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown = """
                # ChatWithChat Memory

                ## Projects

                <!-- memory:id=mem_malformed type=project_context sensitivity=normal source=assistant_inferred created=not-a-time -->
                - This malformed entry body must never reach the searchable corpus.
            """.trimIndent(),
            projectionPolicy = MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        )

        assertEquals(emptyList<MemoryCorpusChunk>(), result.chunks)
        assertEquals("chat_projection_parse_failed", result.diagnostics.single().code)
    }

    @Test
    fun `hard splitting never separates a surrogate pair`() {
        val chunks = MemoryChunker(maxChunkChars = 4).chunksFor(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown = """
                # ChatWithChat Memory

                ## Notes

                abc😀def
            """.trimIndent(),
            projectionPolicy = MemoryProjectionPolicy.MAINTENANCE_FULL
        ).chunks

        assertEquals("abc😀def", chunks.joinToString(separator = "") { chunk -> chunk.text })
        assertFalse(chunks.any { chunk -> chunk.text.hasUnpairedSurrogate() })
    }

    @Test
    fun `non positive chunk size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryChunker(maxChunkChars = 0)
        }
    }

    @Test
    fun `chat projection filters inactive entries before chunking while maintenance keeps all`() {
        val active = MarkdownMemoryEntry(
            id = "active",
            text = "Active preference.",
            type = "communication_style",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.USER_CONFIRMED,
            canonicalKey = "communication.response_style",
            recallState = MemoryRecallState.CORE
        )
        val hidden = active.copy(
            id = "hidden",
            text = "Retired preference.",
            validity = MemoryValidity.OBSOLETE,
            supersededBy = active.id,
            recallState = MemoryRecallState.MAINTENANCE_ONLY
        )
        val markdown = MarkdownMemoryCodec().renderLongTerm(listOf(active, hidden))

        val chat = MemoryChunker().chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown,
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        )
        val maintenance = MemoryChunker().chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown,
            MemoryProjectionPolicy.MAINTENANCE_FULL
        )

        assertEquals(listOf("active"), chat.chunks.map { chunk -> chunk.entryId })
        assertEquals(listOf("active", "hidden"), maintenance.chunks.map { chunk -> chunk.entryId })
        assertTrue(maintenance.chunks.any { chunk -> chunk.validity == MemoryValidity.OBSOLETE })
    }

    private fun String.hasUnpairedSurrogate(): Boolean {
        var index = 0
        while (index < length) {
            val character = this[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return true
                    index += 2
                }
                Character.isLowSurrogate(character) -> return true
                else -> index += 1
            }
        }
        return false
    }
}
