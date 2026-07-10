package dev.chungjungsoo.gptmobile.data.memory

class MemoryMaintenanceNotificationPolicy {
    fun decide(
        event: MemoryMaintenanceStatusChangedEvent,
        preferenceEnabled: Boolean,
        systemPermissionGranted: Boolean
    ): MemoryMaintenanceNotificationDecision {
        val job = event.newJob
        val notificationKey = notificationKey(jobId = job.jobId, idempotencyKey = job.idempotencyKey)

        if (event.newStatus == MemoryMaintenanceJobStatus.SUCCEEDED || event.newStatus == MemoryMaintenanceJobStatus.DISMISSED) {
            return MemoryMaintenanceNotificationDecision.Cancel(notificationKey)
        }

        if (!preferenceEnabled || !systemPermissionGranted) {
            return MemoryMaintenanceNotificationDecision.None
        }

        return when (event.newStatus) {
            MemoryMaintenanceJobStatus.RUNNING -> {
                if (job.type in START_NOTIFICATION_JOB_TYPES) {
                    MemoryMaintenanceNotificationDecision.ShowStarted(notificationKey)
                } else {
                    MemoryMaintenanceNotificationDecision.None
                }
            }
            MemoryMaintenanceJobStatus.FAILED_RETRYABLE -> {
                MemoryMaintenanceNotificationDecision.ShowFailed(notificationKey, terminal = false)
            }
            MemoryMaintenanceJobStatus.FAILED_TERMINAL -> {
                MemoryMaintenanceNotificationDecision.ShowFailed(notificationKey, terminal = true)
            }
            else -> MemoryMaintenanceNotificationDecision.None
        }
    }

    private fun notificationKey(jobId: String, idempotencyKey: String): String =
        idempotencyKey.ifBlank { jobId }

    private companion object {
        val START_NOTIFICATION_JOB_TYPES = setOf(
            MemoryMaintenanceJobType.DISTILL_DAILY_NOTES,
            MemoryMaintenanceJobType.PROMOTE_LONG_TERM_CANDIDATE,
            MemoryMaintenanceJobType.REPAIR_MARKDOWN_METADATA,
            MemoryMaintenanceJobType.COMPACTION_FLUSH,
            MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH
        )
    }
}

sealed class MemoryMaintenanceNotificationDecision {
    data object None : MemoryMaintenanceNotificationDecision()
    data class ShowStarted(val notificationKey: String) : MemoryMaintenanceNotificationDecision()
    data class ShowFailed(val notificationKey: String, val terminal: Boolean) : MemoryMaintenanceNotificationDecision()
    data class Cancel(val notificationKey: String) : MemoryMaintenanceNotificationDecision()
}
