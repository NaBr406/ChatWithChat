package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryChunkerTest {

    @Test
    fun `parsed entries produce storage neutral chunks with stable hashes`() {
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

        val first = chunker.chunksFor(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, markdown)
        val second = chunker.chunksFor(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, markdown)
        val changed = chunker.chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown.replace("local memory index", "current Markdown snapshot")
        )
        val metadataChanged = chunker.chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            MarkdownMemoryCodec().renderLongTerm(listOf(entry.copy(updatedAt = 21L)))
        )
        val sourceChanged = chunker.chunksFor("memory/2026-07-12.md", markdown)

        assertEquals(first, second)
        assertEquals("MEMORY.md#mem_project#0", first.single().chunkId)
        assertEquals(64, first.single().contentHash.length)
        assertNotEquals(first.single().contentHash, changed.single().contentHash)
        assertNotEquals(first.single().contentHash, metadataChanged.single().contentHash)
        assertNotEquals(first.single().contentHash, sourceChanged.single().contentHash)
    }

    @Test
    fun `fallback chunks preserve section metadata`() {
        val chunks = MemoryChunker().chunksFor(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown = """
                # ChatWithChat Memory

                ## Projects

                Handwritten Markdown remains searchable without metadata comments.
            """.trimIndent()
        )

        assertEquals(1, chunks.size)
        assertEquals("Projects", chunks.single().heading)
        assertEquals(null, chunks.single().entryId)
        assertTrue(chunks.single().text.contains("Handwritten Markdown"))
    }

    @Test
    fun `hard splitting never separates a surrogate pair`() {
        val chunks = MemoryChunker(maxChunkChars = 4).chunksFor(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown = """
                # ChatWithChat Memory

                ## Notes

                abc😀def
            """.trimIndent()
        )

        assertEquals("abc😀def", chunks.joinToString(separator = "") { chunk -> chunk.text })
        assertFalse(chunks.any { chunk -> chunk.text.hasUnpairedSurrogate() })
    }

    @Test
    fun `non positive chunk size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryChunker(maxChunkChars = 0)
        }
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
