package dev.chungjungsoo.gptmobile.data.memory

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        assertTrue(root.resolve(MemoryFilePaths.STAGING_DIRECTORY_NAME).isDirectory)
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
    fun `long term revision emits immediately and ignores daily only writes`() = runBlocking {
        val root = createTempRoot()
        val store = createStore(root)
        store.ensureStore().getOrThrow()

        val initialRevision = store.longTermRevision.first()
        store.appendDailyNote("- Daily-only note").getOrThrow()

        assertEquals(initialRevision, store.longTermRevision.value)

        store.replaceLongTermMemory("# ChatWithChat Memory\n\n- Replaced target").getOrThrow()
        assertEquals(initialRevision + 1, store.longTermRevision.value)

        val staged = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- Staged target",
            stagingId = "receipt-revision"
        ).getOrThrow()
        store.commitStagedMemoryFile(staged).getOrThrow()

        assertEquals(initialRevision + 2, store.longTermRevision.value)
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
    fun `stage memory file persists exact normalized target and hashes`() {
        val root = createTempRoot()
        val store = createStore(root)
        store.ensureStore().getOrThrow()

        val staged = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n## Preferences\n- 先审计再实现  \n\n",
            stagingId = "receipt-123"
        ).getOrThrow()

        val expectedTarget = "# ChatWithChat Memory\n\n## Preferences\n- 先审计再实现\n"
        assertEquals(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, staged.sourcePath)
        assertEquals(".staging/receipt-123.target", staged.stagedTargetPath)
        assertEquals(sha256("# ChatWithChat Memory\n\n"), staged.baseSourceHash)
        assertEquals(staged.baseSourceHash, store.currentMemoryFileHash(staged.sourcePath).getOrThrow())
        assertEquals(sha256(expectedTarget), staged.targetSourceHash)
        assertEquals(expectedTarget, root.resolve(staged.stagedTargetPath).readUtf8())
    }

    @Test
    fun `staging ID is idempotent only for the same exact target`() {
        val root = createTempRoot()
        val store = createStore(root)
        store.ensureStore().getOrThrow()
        val original = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- First",
            stagingId = "receipt-idempotent"
        ).getOrThrow()

        val repeated = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- First\n\n",
            stagingId = "receipt-idempotent"
        ).getOrThrow()
        val conflicting = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- Different",
            stagingId = "receipt-idempotent"
        )

        assertEquals(original, repeated)
        assertFalse(conflicting.isSuccess)
        assertEquals("# ChatWithChat Memory\n\n- First\n", root.resolve(original.stagedTargetPath).readUtf8())
    }

    @Test
    fun `commit staged memory survives store recreation and preserves backup`() {
        val root = createTempRoot()
        val firstStore = createStore(root)
        firstStore.ensureStore().getOrThrow()
        val staged = firstStore.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n## Projects\n- Recoverable target",
            stagingId = "receipt-restart"
        ).getOrThrow()

        val outcome = createStore(root).commitStagedMemoryFile(staged).getOrThrow()

        assertTrue(outcome is MemoryFileCommitOutcome.Committed)
        outcome as MemoryFileCommitOutcome.Committed
        assertEquals(staged.targetSourceHash, outcome.currentSourceHash)
        assertEquals("# ChatWithChat Memory\n\n", outcome.backupFile.readUtf8())
        assertEquals(
            "# ChatWithChat Memory\n\n## Projects\n- Recoverable target\n",
            root.resolve(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).readUtf8()
        )
        assertTrue(root.resolve(staged.stagedTargetPath).isFile)
    }

    @Test
    fun `commit returns already committed even after staged target cleanup`() {
        val root = createTempRoot()
        val store = createStore(root)
        store.ensureStore().getOrThrow()
        val staged = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- Idempotent target",
            stagingId = "receipt-already-committed"
        ).getOrThrow()
        store.commitStagedMemoryFile(staged).getOrThrow()
        assertTrue(store.cleanupStagedTarget(staged.stagedTargetPath).getOrThrow())

        val repeated = store.commitStagedMemoryFile(staged).getOrThrow()
        val invalidPathReplay = store.commitStagedMemoryFile(
            sourcePath = staged.sourcePath,
            stagedTargetPath = "../invalid.target",
            baseSourceHash = staged.baseSourceHash,
            targetSourceHash = staged.targetSourceHash
        ).getOrThrow()

        assertTrue(repeated is MemoryFileCommitOutcome.AlreadyCommitted)
        assertTrue(invalidPathReplay is MemoryFileCommitOutcome.AlreadyCommitted)
        assertEquals(staged.targetSourceHash, repeated.currentSourceHash)
        assertEquals(1, root.resolve(MemoryFilePaths.BACKUP_DIRECTORY_NAME).listFiles().orEmpty().size)
    }

    @Test
    fun `commit reports conflict without overwriting newer canonical content`() {
        val root = createTempRoot()
        val store = createStore(root)
        val snapshot = store.ensureStore().getOrThrow()
        val staged = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- Approved target",
            stagingId = "receipt-conflict"
        ).getOrThrow()
        val newerContent = "# ChatWithChat Memory\n\n- Newer canonical content\n"
        snapshot.longTermMemoryFile.writeText(newerContent, StandardCharsets.UTF_8)

        val outcome = store.commitStagedMemoryFile(
            sourcePath = staged.sourcePath,
            stagedTargetPath = "../invalid.target",
            baseSourceHash = staged.baseSourceHash,
            targetSourceHash = staged.targetSourceHash
        ).getOrThrow()

        assertTrue(outcome is MemoryFileCommitOutcome.Conflict)
        outcome as MemoryFileCommitOutcome.Conflict
        assertEquals(sha256(newerContent), outcome.currentSourceHash)
        assertEquals(staged.baseSourceHash, outcome.expectedBaseSourceHash)
        assertEquals(staged.targetSourceHash, outcome.targetSourceHash)
        assertEquals(newerContent, snapshot.longTermMemoryFile.readUtf8())
        assertTrue(root.resolve(staged.stagedTargetPath).isFile)
        assertEquals(0, root.resolve(MemoryFilePaths.BACKUP_DIRECTORY_NAME).listFiles().orEmpty().size)
    }

    @Test
    fun `commit makes a modified staged target unrecoverable before backup or replacement`() {
        val root = createTempRoot()
        val store = createStore(root)
        val snapshot = store.ensureStore().getOrThrow()
        val staged = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- Approved target",
            stagingId = "receipt-modified"
        ).getOrThrow()
        root.resolve(staged.stagedTargetPath).writeText("modified\n", StandardCharsets.UTF_8)

        val outcome = store.commitStagedMemoryFile(staged).getOrThrow()

        assertTrue(outcome is MemoryFileCommitOutcome.UnrecoverableStaging)
        assertEquals(
            MEMORY_MUTATION_UNRECOVERABLE_STAGING_HASH_MISMATCH,
            (outcome as MemoryFileCommitOutcome.UnrecoverableStaging).reason
        )
        assertEquals("# ChatWithChat Memory\n\n", snapshot.longTermMemoryFile.readUtf8())
        assertEquals(0, root.resolve(MemoryFilePaths.BACKUP_DIRECTORY_NAME).listFiles().orEmpty().size)
    }

    @Test
    fun `commit makes a missing staged target unrecoverable while canonical is base`() {
        val root = createTempRoot()
        val store = createStore(root)
        val snapshot = store.ensureStore().getOrThrow()
        val staged = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- Missing target",
            stagingId = "receipt-missing"
        ).getOrThrow()
        Files.delete(root.resolve(staged.stagedTargetPath).toPath())

        val outcome = store.commitStagedMemoryFile(staged).getOrThrow()

        assertTrue(outcome is MemoryFileCommitOutcome.UnrecoverableStaging)
        assertEquals(
            MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING,
            (outcome as MemoryFileCommitOutcome.UnrecoverableStaging).reason
        )
        assertEquals("# ChatWithChat Memory\n\n", snapshot.longTermMemoryFile.readUtf8())
        assertTrue(root.resolve(MemoryFilePaths.BACKUP_DIRECTORY_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `commit makes a staged directory unrecoverable while canonical is base`() {
        val root = createTempRoot()
        val store = createStore(root)
        val snapshot = store.ensureStore().getOrThrow()
        val staged = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- Directory target",
            stagingId = "receipt-directory"
        ).getOrThrow()
        val stagedPath = root.resolve(staged.stagedTargetPath).toPath()
        Files.delete(stagedPath)
        Files.createDirectory(stagedPath)

        val outcome = store.commitStagedMemoryFile(staged).getOrThrow()

        assertTrue(outcome is MemoryFileCommitOutcome.UnrecoverableStaging)
        assertEquals(
            MEMORY_MUTATION_UNRECOVERABLE_STAGING_INVALID,
            (outcome as MemoryFileCommitOutcome.UnrecoverableStaging).reason
        )
        assertEquals("# ChatWithChat Memory\n\n", snapshot.longTermMemoryFile.readUtf8())
        assertTrue(root.resolve(MemoryFilePaths.BACKUP_DIRECTORY_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cleanup staged target is safe and idempotent`() {
        val root = createTempRoot()
        val store = createStore(root)
        store.ensureStore().getOrThrow()
        val staged = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- Temporary target",
            stagingId = "receipt-cleanup"
        ).getOrThrow()

        assertTrue(store.cleanupStagedTarget(staged.stagedTargetPath).getOrThrow())
        assertFalse(store.cleanupStagedTarget(staged.stagedTargetPath).getOrThrow())
    }

    @Test
    fun `staging and commit reject path traversal`() {
        val root = createTempRoot()
        val store = createStore(root)
        store.ensureStore().getOrThrow()
        val staged = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- Safe target",
            stagingId = "receipt-safe"
        ).getOrThrow()

        assertFalse(
            store.stageMemoryFile(
                sourcePath = "../MEMORY.md",
                content = "outside",
                stagingId = "receipt-source-traversal"
            ).isSuccess
        )
        assertFalse(
            store.stageMemoryFile(
                sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                content = "outside",
                stagingId = "../receipt-target-traversal"
            ).isSuccess
        )
        val invalidStaging = store.commitStagedMemoryFile(
            sourcePath = staged.sourcePath,
            stagedTargetPath = ".staging/../receipt-safe.target",
            baseSourceHash = staged.baseSourceHash,
            targetSourceHash = staged.targetSourceHash
        ).getOrThrow()
        assertEquals(
            MEMORY_MUTATION_UNRECOVERABLE_STAGING_INVALID,
            (invalidStaging as MemoryFileCommitOutcome.UnrecoverableStaging).reason
        )
        assertFalse(store.cleanupStagedTarget("../receipt-safe.target").isSuccess)
        assertFalse(store.currentMemoryFileHash("../MEMORY.md").isSuccess)
        assertEquals("# ChatWithChat Memory\n\n", root.resolve(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).readUtf8())
    }

    @Test
    fun `io failures are returned as result failures`() {
        val rootFile = Files.createTempFile("memory-store-file", ".tmp").toFile()
        val store = createStore(rootFile)

        val result = store.ensureStore()

        assertFalse(result.isSuccess)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `staging parent io failure remains retryable instead of becoming terminal`() {
        val root = createTempRoot()
        val paths = MemoryFilePaths(root)
        val store = MemoryFileStore(paths, Clock.fixed(Instant.parse("2026-07-09T10:20:30Z"), ZoneOffset.UTC))
        val canonicalBefore = store.readLongTermMemory().getOrThrow()
        val staged = store.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = "# ChatWithChat Memory\n\n- Retry after transient I/O",
            stagingId = "receipt-transient-io"
        ).getOrThrow()
        Files.delete(root.resolve(staged.stagedTargetPath).toPath())
        Files.delete(paths.stagingDirectory.toPath())
        paths.stagingDirectory.writeText("temporarily not a directory", StandardCharsets.UTF_8)

        val result = store.commitStagedMemoryFile(staged)

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(canonicalBefore, paths.longTermMemoryFile.readUtf8())
        assertTrue(paths.backupDirectory.listFiles().orEmpty().isEmpty())
    }

    private fun createStore(root: File): MemoryFileStore =
        MemoryFileStore(
            paths = MemoryFilePaths(root),
            clock = Clock.fixed(Instant.parse("2026-07-09T10:20:30Z"), ZoneOffset.UTC)
        )

    private fun createTempRoot(): File =
        Files.createTempDirectory("memory-file-store-test").toFile()

    private fun File.readUtf8(): String = readText(StandardCharsets.UTF_8)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
