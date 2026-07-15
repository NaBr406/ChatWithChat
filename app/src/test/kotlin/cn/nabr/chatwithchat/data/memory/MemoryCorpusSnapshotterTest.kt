package cn.nabr.chatwithchat.data.memory

import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCorpusSnapshotterTest {

    @Test
    fun `snapshot hashes exact committed utf8 bytes`() = runBlocking {
        val fileStore = createFileStore()
        val replacement = fileStore.replaceLongTermMemory(
            "# ChatWithChat Memory\r\n\r\n## Notes\r\n\r\nUTF-8 内容 stays exact."
        ).getOrThrow()
        val snapshotter = MemoryCorpusSnapshotter(fileStore, MemoryChunker())

        val snapshot = snapshotter.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow().single()
        val exactBytes = replacement.file.readBytes()

        assertEquals(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, snapshot.sourcePath)
        assertEquals(sha256(exactBytes), snapshot.sourceHash)
        assertTrue(snapshotter.isCurrent(listOf(snapshot)).getOrThrow())
    }

    @Test
    fun `chat corpus excludes daily while maintenance corpus includes it`() = runBlocking {
        val fileStore = createFileStore()
        fileStore.ensureStore().getOrThrow()
        fileStore.appendDailyNote("- Hidden daily observation").getOrThrow()
        val snapshotter = MemoryCorpusSnapshotter(fileStore, MemoryChunker())

        val chatSources = snapshotter.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow()
            .map { snapshot -> snapshot.sourcePath }
        val maintenanceSources = snapshotter.snapshots(MemoryCorpus.MAINTENANCE_WORKING_SET).getOrThrow()
            .map { snapshot -> snapshot.sourcePath }

        assertEquals(listOf(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME), chatSources)
        assertTrue(maintenanceSources.contains(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME))
        assertTrue(maintenanceSources.any { sourcePath -> sourcePath.startsWith("memory/") })
    }

    @Test
    fun `revision detects changes even when file returns to an earlier hash`() = runBlocking {
        val fileStore = createFileStore()
        val snapshotter = MemoryCorpusSnapshotter(fileStore, MemoryChunker())
        fileStore.replaceLongTermMemory("# ChatWithChat Memory\n\nA").getOrThrow()
        val firstA = snapshotter.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow().single()

        fileStore.replaceLongTermMemory("# ChatWithChat Memory\n\nB").getOrThrow()
        assertFalse(snapshotter.isCurrent(listOf(firstA)).getOrThrow())
        fileStore.replaceLongTermMemory("# ChatWithChat Memory\n\nA").getOrThrow()
        val secondA = snapshotter.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow().single()

        assertEquals(firstA.sourceHash, secondA.sourceHash)
        assertNotEquals(firstA.generation, secondA.generation)
        assertFalse(snapshotter.isCurrent(listOf(firstA)).getOrThrow())
        assertTrue(snapshotter.isCurrent(listOf(secondA)).getOrThrow())
    }

    @Test
    fun `empty long term file produces a current snapshot with no chunks`() = runBlocking {
        val fileStore = createFileStore()
        val longTermFile = fileStore.ensureStore().getOrThrow().longTermMemoryFile
        Files.write(longTermFile.toPath(), byteArrayOf())
        val snapshotter = MemoryCorpusSnapshotter(fileStore, MemoryChunker())

        val snapshot = snapshotter.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow().single()

        assertEquals(sha256(byteArrayOf()), snapshot.sourceHash)
        assertTrue(snapshot.chunks.isEmpty())
        assertTrue(snapshotter.isCurrent(listOf(snapshot)).getOrThrow())
    }

    @Test
    fun `exact byte comparison detects writes outside the file store gate`() = runBlocking {
        val fileStore = createFileStore()
        val replacement = fileStore.replaceLongTermMemory("# ChatWithChat Memory\n\nA").getOrThrow()
        val snapshotter = MemoryCorpusSnapshotter(fileStore, MemoryChunker())
        val snapshot = snapshotter.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow().single()

        Files.write(replacement.file.toPath(), "# ChatWithChat Memory\n\nB\n".toByteArray(Charsets.UTF_8))

        assertFalse(snapshotter.isCurrent(listOf(snapshot)).getOrThrow())
    }

    @Test
    fun `canonical writers advance the process local generation`() = runBlocking {
        val fileStore = createFileStore()
        val initialStore = fileStore.ensureStore().getOrThrow()
        val snapshotter = MemoryCorpusSnapshotter(fileStore, MemoryChunker())
        var generation = snapshotter.snapshots(MemoryCorpus.MAINTENANCE_WORKING_SET)
            .getOrThrow()
            .first()
            .generation

        fileStore.appendLongTermMemory("Long-term append").getOrThrow()
        generation = assertNextGeneration(snapshotter, generation)

        val longTermReplacement = fileStore.replaceLongTermMemory("# ChatWithChat Memory\n\nReplacement").getOrThrow()
        generation = assertNextGeneration(snapshotter, generation)

        fileStore.appendDailyNote("Daily append").getOrThrow()
        generation = assertNextGeneration(snapshotter, generation)

        fileStore.replaceMemoryFile(initialStore.todayMemoryFile, "# 2026-07-13\n\nDaily replacement").getOrThrow()
        generation = assertNextGeneration(snapshotter, generation)

        fileStore.restoreMemoryFile(longTermReplacement).getOrThrow()
        generation = assertNextGeneration(snapshotter, generation)
        assertTrue(generation > 0L)
    }

    @Test
    fun `daily writes do not invalidate the long term generation`() = runBlocking {
        val fileStore = createFileStore()
        val snapshotter = MemoryCorpusSnapshotter(fileStore, MemoryChunker())
        val before = snapshotter.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow().single()
        val maintenanceBefore = snapshotter.snapshots(MemoryCorpus.MAINTENANCE_WORKING_SET).getOrThrow()

        fileStore.appendDailyNote("Daily-only evidence").getOrThrow()

        val after = snapshotter.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow().single()
        assertEquals(before.generation, after.generation)
        assertEquals(before.sourceHash, after.sourceHash)
        assertTrue(snapshotter.isCurrent(listOf(before)).getOrThrow())
        assertFalse(snapshotter.isCurrent(maintenanceBefore).getOrThrow())
    }

    @Test
    fun `recreating an identical canonical file advances its generation`() = runBlocking {
        val fileStore = createFileStore()
        val longTermFile = fileStore.ensureStore().getOrThrow().longTermMemoryFile
        val snapshotter = MemoryCorpusSnapshotter(fileStore, MemoryChunker())
        val before = snapshotter.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow().single()

        Files.delete(longTermFile.toPath())

        val after = snapshotter.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow().single()
        assertEquals(before.sourceHash, after.sourceHash)
        assertNotEquals(before.generation, after.generation)
        assertFalse(snapshotter.isCurrent(listOf(before)).getOrThrow())
    }

    private fun createFileStore(): MemoryFileStore = MemoryFileStore(
        paths = MemoryFilePaths(Files.createTempDirectory("memory-corpus-snapshotter").toFile()),
        clock = Clock.fixed(Instant.parse("2026-07-13T08:00:00Z"), ZoneOffset.UTC)
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private suspend fun assertNextGeneration(
        snapshotter: MemoryCorpusSnapshotter,
        previousGeneration: Long
    ): Long {
        val currentGeneration = snapshotter.snapshots(MemoryCorpus.MAINTENANCE_WORKING_SET)
            .getOrThrow()
            .first()
            .generation
        assertEquals(previousGeneration + 1, currentGeneration)
        return currentGeneration
    }
}
