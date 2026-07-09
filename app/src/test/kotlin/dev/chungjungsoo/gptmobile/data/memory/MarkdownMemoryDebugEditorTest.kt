package dev.chungjungsoo.gptmobile.data.memory

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMemoryDebugEditorTest {

    @Test
    fun `delete long term entry removes matching ids and rebuilds index`() = runBlocking {
        val fileStore = createFileStore()
        val indexRebuilder = RecordingMemoryIndexRebuilder()
        val editor = MarkdownMemoryDebugEditor(
            memoryFileStore = fileStore,
            markdownMemoryCodec = MarkdownMemoryCodec(),
            memoryIndexRebuilder = indexRebuilder
        )
        fileStore.replaceLongTermMemory(
            """
            # ChatWithChat Memory

            Intro note should stay.

            ## Stable Preferences

            <!-- memory:id=mem_keep type=communication_style sensitivity=normal source=explicit_user_statement created=10 updated=20 -->
            - Keep this memory.

            <!-- memory:id=mem_delete type=communication_style sensitivity=normal source=explicit_user_statement created=11 updated=21 -->
            - Delete this memory.

            ## Projects

            <!-- memory:id=mem_delete type=project_context sensitivity=normal source=assistant_inferred created=12 updated=22 -->
            - Delete this duplicate memory id too.
            """.trimIndent()
        ).getOrThrow()

        val result = editor.deleteLongTermEntry(" mem_delete ").getOrThrow()
        val markdown = fileStore.readLongTermMemory().getOrThrow()

        assertEquals("mem_delete", result.entryId)
        assertEquals(2, result.deletedCount)
        assertTrue(result.backupFile?.exists() == true)
        assertTrue(result.backupFile!!.readUtf8().contains("Delete this memory."))
        assertEquals(listOf("MEMORY.md"), indexRebuilder.rebuiltFiles.map { it.name })
        assertTrue(markdown.contains("Intro note should stay."))
        assertTrue(markdown.contains("mem_keep"))
        assertFalse(markdown.contains("mem_delete"))
        assertFalse(markdown.contains("Delete this memory."))
        assertFalse(markdown.contains("Delete this duplicate memory id too."))
    }

    @Test
    fun `delete long term entry is a no-op for missing id`() = runBlocking {
        val fileStore = createFileStore()
        val indexRebuilder = RecordingMemoryIndexRebuilder()
        val editor = MarkdownMemoryDebugEditor(
            memoryFileStore = fileStore,
            markdownMemoryCodec = MarkdownMemoryCodec(),
            memoryIndexRebuilder = indexRebuilder
        )
        fileStore.replaceLongTermMemory(
            """
            # ChatWithChat Memory

            ## Stable Preferences

            <!-- memory:id=mem_keep type=communication_style sensitivity=normal source=explicit_user_statement created=10 updated=20 -->
            - Keep this memory.
            """.trimIndent()
        ).getOrThrow()

        val before = fileStore.readLongTermMemory().getOrThrow()
        val result = editor.deleteLongTermEntry("missing").getOrThrow()
        val after = fileStore.readLongTermMemory().getOrThrow()

        assertEquals(0, result.deletedCount)
        assertEquals(before, after)
        assertTrue(indexRebuilder.rebuiltFiles.isEmpty())
    }

    private fun createFileStore(): MemoryFileStore =
        MemoryFileStore(
            paths = MemoryFilePaths(Files.createTempDirectory("markdown-memory-debug-editor-test").toFile()),
            clock = Clock.fixed(Instant.parse("2026-07-09T10:20:30Z"), ZoneOffset.UTC)
        )

    private fun File.readUtf8(): String = readText(StandardCharsets.UTF_8)
}

private class RecordingMemoryIndexRebuilder : MemoryIndexRebuilder {
    val rebuiltFiles = mutableListOf<File>()

    override suspend fun rebuildFile(file: File): Result<MemoryIndexRebuildResult> {
        rebuiltFiles += file
        return Result.success(MemoryIndexRebuildResult(indexedDocuments = 1, indexedChunks = 1))
    }
}
