package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMaintenanceNotificationPolicyTest {
    private val policy = MemoryMaintenanceNotificationPolicy()

    @Test
    fun `heavy job start can show started notification`() {
        val decision = policy.decide(
            event = event(job(type = MemoryMaintenanceJobType.DISTILL_DAILY_NOTES, status = MemoryMaintenanceJobStatus.RUNNING)),
            preferenceEnabled = true,
            systemPermissionGranted = true
        )

        assertEquals(MemoryMaintenanceNotificationDecision.ShowStarted("key-1"), decision)
    }

    @Test
    fun `routine append daily note start does not notify`() {
        val decision = policy.decide(
            event = event(job(type = MemoryMaintenanceJobType.APPEND_DAILY_NOTE, status = MemoryMaintenanceJobStatus.RUNNING)),
            preferenceEnabled = true,
            systemPermissionGranted = true
        )

        assertEquals(MemoryMaintenanceNotificationDecision.None, decision)
    }

    @Test
    fun `retryable failure shows failure notification`() {
        val decision = policy.decide(
            event = event(job(status = MemoryMaintenanceJobStatus.FAILED_RETRYABLE)),
            preferenceEnabled = true,
            systemPermissionGranted = true
        )

        assertEquals(MemoryMaintenanceNotificationDecision.ShowFailed("key-1", terminal = false), decision)
    }

    @Test
    fun `terminal failure shows terminal failure notification`() {
        val decision = policy.decide(
            event = event(job(status = MemoryMaintenanceJobStatus.FAILED_TERMINAL)),
            preferenceEnabled = true,
            systemPermissionGranted = true
        )

        assertEquals(MemoryMaintenanceNotificationDecision.ShowFailed("key-1", terminal = true), decision)
    }

    @Test
    fun `preference disabled prevents show notifications`() {
        val decision = policy.decide(
            event = event(job(status = MemoryMaintenanceJobStatus.FAILED_RETRYABLE)),
            preferenceEnabled = false,
            systemPermissionGranted = true
        )

        assertEquals(MemoryMaintenanceNotificationDecision.None, decision)
    }

    @Test
    fun `permission denied prevents show notifications`() {
        val decision = policy.decide(
            event = event(job(status = MemoryMaintenanceJobStatus.FAILED_RETRYABLE)),
            preferenceEnabled = true,
            systemPermissionGranted = false
        )

        assertEquals(MemoryMaintenanceNotificationDecision.None, decision)
    }

    @Test
    fun `success cancels existing notification`() {
        val decision = policy.decide(
            event = event(job(status = MemoryMaintenanceJobStatus.SUCCEEDED)),
            preferenceEnabled = false,
            systemPermissionGranted = false
        )

        assertEquals(MemoryMaintenanceNotificationDecision.Cancel("key-1"), decision)
    }

    @Test
    fun `manual retry cancels the terminal failure notification`() {
        val terminal = job(status = MemoryMaintenanceJobStatus.FAILED_TERMINAL)
        val pending = terminal.copy(
            status = MemoryMaintenanceJobStatus.PENDING,
            attempts = 0,
            lastError = null
        )
        val decision = policy.decide(
            event = MemoryMaintenanceStatusChangedEvent(
                oldJob = terminal,
                newJob = pending,
                oldStatus = terminal.status,
                newStatus = pending.status,
                occurredAt = 101L
            ),
            preferenceEnabled = false,
            systemPermissionGranted = false
        )

        assertEquals(MemoryMaintenanceNotificationDecision.Cancel("key-1"), decision)
    }

    @Test
    fun `duplicate attempts keep same notification identity`() {
        val first = policy.decide(
            event = event(job(jobId = "job-1", attempts = 1, status = MemoryMaintenanceJobStatus.FAILED_RETRYABLE)),
            preferenceEnabled = true,
            systemPermissionGranted = true
        )
        val second = policy.decide(
            event = event(job(jobId = "job-1", attempts = 2, status = MemoryMaintenanceJobStatus.FAILED_RETRYABLE)),
            preferenceEnabled = true,
            systemPermissionGranted = true
        )

        assertTrue(first is MemoryMaintenanceNotificationDecision.ShowFailed)
        assertEquals(first, second)
    }

    private fun event(job: MemoryMaintenanceJob): MemoryMaintenanceStatusChangedEvent =
        MemoryMaintenanceStatusChangedEvent(
            oldJob = null,
            newJob = job,
            oldStatus = null,
            newStatus = job.status,
            occurredAt = 100L
        )

    private fun job(
        jobId: String = "job-1",
        type: String = MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
        attempts: Int = 1,
        status: String
    ): MemoryMaintenanceJob = MemoryMaintenanceJob(
        jobId = jobId,
        type = type,
        status = status,
        idempotencyKey = "key-1",
        payloadJson = "{}",
        attempts = attempts,
        lastError = null,
        createdAt = 1L,
        startedAt = 10L,
        updatedAt = 100L,
        nextRunAt = null
    )
}
