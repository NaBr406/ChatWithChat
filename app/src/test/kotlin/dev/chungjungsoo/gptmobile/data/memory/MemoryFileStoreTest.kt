package dev.chungjungsoo.gptmobile.data.memory

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFileStoreTest {

    @Test
    fun `ensure store creates long term and daily memory files`() {
        val root = createTempRoot()
        val store = createStore(root)

        val snapshot = store.ensureStore().getOrThrow()

        assertTrue(snapshot.rootDirectory.isDirectory)
        assertEquals("# ChatWithChat Memory\n\n", snapshot.longTermMemoryFile.readUtf8())
        assertEquals("# 2026-07-09\n\n", snapshot.todayMemoryFile.readUtf8())
    }

    @Test
    fun `append daily note preserves existing content`() {
        val root = createTempRoot()
        val store = createStore(root)

        store.appendDailyNote("- First note").getOrThrow()
        store.appendDailyNote("- Second note").getOrThrow()

        val dailyText = store.readDailyMemory().getOrThrow()
        assertTrue(dailyText.startsWith("# 2026-07-09\n\n"))
        assertTrue(dailyText.contains("- First note\n"))
        assertTrue(dailyText.contains("- Second note\n"))
    }

    @Test
    fun `read and append use utf8 explicitly`() {
        val root = createTempRoot()
        val store = createStore(root)

        store.appendDailyNote("- User prefers concrete Chinese steps: 先审计再实现").getOrThrow()

        val dailyText = root.resolve("memory").resolve("2026-07-09.md").readUtf8()
        assertTrue(dailyText.contains("先审计再实现"))
    }

    @Test
    fun `replace long term memory creates backup before atomic replacement`() {
        val root = createTempRoot()
        val store = createStore(root)
        store.ensureStore().getOrThrow()

        val replacement = store.replaceLongTermMemory("# ChatWithChat Memory\n\n## Projects\n- New fact").getOrThrow()

        assertEquals("# ChatWithChat Memory\n\n## Projects\n- New fact\n", replacement.file.readUtf8())
        assertTrue(replacement.backupFile.exists())
        assertEquals("# ChatWithChat Memory\n\n", replacement.backupFile.readUtf8())
        assertTrue(replacement.backupFile.name.startsWith("MEMORY.md.20260709-102030"))
    }

    @Test
    fun `io failures are returned as result failures`() {
        val rootFile = Files.createTempFile("memory-store-file", ".tmp").toFile()
        val store = createStore(rootFile)

        val result = store.ensureStore()

        assertFalse(result.isSuccess)
        assertNotNull(result.exceptionOrNull())
    }

    private fun createStore(root: File): MemoryFileStore =
        MemoryFileStore(
            paths = MemoryFilePaths(root),
            clock = Clock.fixed(Instant.parse("2026-07-09T10:20:30Z"), ZoneOffset.UTC)
        )

    private fun createTempRoot(): File =
        Files.createTempDirectory("memory-file-store-test").toFile()

    private fun File.readUtf8(): String = readText(StandardCharsets.UTF_8)
}
