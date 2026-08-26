package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMaintenanceFailureResponseStoreTest {
    @Test
    fun `record preserves raw model response bytes`() {
        val root = Files.createTempDirectory("memory-maintenance-failure-response").toFile()
        try {
            val paths = MemoryFilePaths(root)
            val store = FileMemoryMaintenanceFailureResponseStore(paths, fixedClock())
            val rawResponse = "{\n  \"operations\": [],\n  \"extra\": true\n}\n"

            store.record(job(), "invalid_model_json", rawResponse)

            val saved = checkNotNull(paths.maintenanceFailureResponseDirectory.listFiles()).single()
            assertTrue(saved.name.startsWith("maintenance-failure-"))
            assertArrayEquals(rawResponse.toByteArray(StandardCharsets.UTF_8), saved.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `read responses returns the exact retained model response`() {
        val root = Files.createTempDirectory("memory-maintenance-failure-response").toFile()
        try {
            val paths = MemoryFilePaths(root)
            val store = FileMemoryMaintenanceFailureResponseStore(paths, fixedClock())
            val rawResponse = "{\n  \"operations\": [],\n  \"extra\": true\n}\n"

            store.record(job(), "invalid_model_json", rawResponse)

            val response = store.readResponses().single()
            assertTrue(response.fileName.startsWith("maintenance-failure-"))
            assertEquals(1_000_000L, response.capturedAtEpochSeconds)
            assertEquals(rawResponse, response.rawResponseJson)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `prune removes only expired diagnostic responses`() {
        val now = 1_000_000L
        val root = Files.createTempDirectory("memory-maintenance-failure-response").toFile()
        try {
            val paths = MemoryFilePaths(root)
            val store = FileMemoryMaintenanceFailureResponseStore(
                paths,
                Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC)
            )
            val directory = paths.maintenanceFailureResponseDirectory
            directory.mkdirs()
            val expired = File(directory, "maintenance-failure-${now - RETENTION_SECONDS - 1}-old.json")
            val boundary = File(directory, "maintenance-failure-${now - RETENTION_SECONDS}-boundary.json")
            val unrelated = File(directory, "unrelated-old.json")
            expired.writeText("expired", StandardCharsets.UTF_8)
            boundary.writeText("boundary", StandardCharsets.UTF_8)
            unrelated.writeText("unrelated", StandardCharsets.UTF_8)

            store.pruneExpired()

            assertFalse(expired.exists())
            assertTrue(boundary.exists())
            assertTrue(unrelated.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun job() = MemoryMaintenanceJob(
        jobId = "memory-batch-test",
        type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
        status = MemoryMaintenanceJobStatus.RUNNING,
        idempotencyKey = "memory-batch-test-key",
        payloadJson = "{}",
        attempts = 3,
        lastError = null,
        createdAt = 1L,
        startedAt = 2L,
        updatedAt = 3L,
        nextRunAt = null
    )

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochSecond(1_000_000L), ZoneOffset.UTC)

    private companion object {
        const val RETENTION_SECONDS = 7 * 24 * 60 * 60L
    }
}
