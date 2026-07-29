package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryActivityRunTest {
    @Test
    fun `same attempt replay has deterministic run identity`() {
        val first = MemoryActivityRunKey(jobId = "job-1", retryCycle = 0, attempt = 1)
        val replay = MemoryActivityRunKey(jobId = "job-1", retryCycle = 0, attempt = 1)

        assertEquals(first.activityRunId, replay.activityRunId)
    }

    @Test
    fun `automatic retry and manual retry cycle have distinct run identities`() {
        val first = MemoryActivityRunKey(jobId = "job-1", retryCycle = 0, attempt = 1)
        val automaticRetry = MemoryActivityRunKey(jobId = "job-1", retryCycle = 0, attempt = 2)
        val manualRetry = MemoryActivityRunKey(jobId = "job-1", retryCycle = 1, attempt = 1)

        assertNotEquals(first.activityRunId, automaticRetry.activityRunId)
        assertNotEquals(first.activityRunId, manualRetry.activityRunId)
        assertNotEquals(automaticRetry.activityRunId, manualRetry.activityRunId)
    }

    @Test
    fun `phase history round trips only bounded structured metadata`() {
        val history = MemoryActivityPhaseHistory(
            phases = listOf(
                MemoryActivityPhaseSummary(
                    phase = MemoryActivityPhase.MODEL_CALL,
                    status = MemoryActivityStatus.SUCCEEDED,
                    startedAt = 10,
                    completedAt = 12,
                    inputCount = 5,
                    operationCount = 2,
                    cursor = 1,
                    hashPrefix = "abcdef12"
                )
            )
        )

        val encoded = history.encode()

        assertEquals(history, MemoryActivityPhaseHistory.decode(encoded))
        assertTrue("prompt" !in encoded)
        assertTrue("memoryText" !in encoded)
    }

    @Test
    fun `phase history rejects unknown fields and unbounded error text`() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryActivityRunKey(jobId = "memory body must not be an id", retryCycle = 0, attempt = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryActivityPhaseSummary(
                phase = MemoryActivityPhase.MODEL_CALL,
                status = MemoryActivityStatus.FAILED,
                startedAt = 10,
                errorCode = "memory text with spaces"
            )
        }
        assertThrows(Exception::class.java) {
            MemoryActivityPhaseHistory.decode(
                """{"version":1,"phases":[],"prompt":"must-not-be-accepted"}"""
            )
        }
    }

    @Test
    fun `phase progression cannot regress`() {
        assertTrue(MemoryActivityPhase.canAdvance(MemoryActivityPhase.MODEL_RESOLUTION, MemoryActivityPhase.MODEL_CALL))
        assertTrue(!MemoryActivityPhase.canAdvance(MemoryActivityPhase.ORGANIZATION, MemoryActivityPhase.GENERATION))
        assertTrue(!MemoryActivityPhase.canAdvance(MemoryActivityPhase.MODEL_CALL, MemoryActivityPhase.MODEL_CALL))
    }
}
