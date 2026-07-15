package cn.nabr.chatwithchat.data.memory

class MemoryMaintenanceNotificationPolicy {
    fun decide(
        event: MemoryMaintenanceStatusChangedEvent,
        preferenceEnabled: Boolean,
        systemPermissionGranted: Boolean
    ): MemoryMaintenanceNotificationDecision {
        val job = event.newJob
        val notificationKey = notificationKey(jobId = job.jobId, idempotencyKey = job.idempotencyKey)

        if (job.type in LEGACY_ROOM_INDEX_JOB_TYPES) {
            return MemoryMaintenanceNotificationDecision.Cancel(notificationKey)
        }

        if (
            event.newStatus == MemoryMaintenanceJobStatus.SUCCEEDED ||
            event.newStatus == MemoryMaintenanceJobStatus.DISMISSED ||
            (
                event.newStatus == MemoryMaintenanceJobStatus.PENDING &&
                    event.oldStatus in FAILURE_NOTIFICATION_STATUSES
                )
        ) {
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
                MemoryMaintenanceNotificationDecision.ShowFailed(
                    notificationKey = notificationKey,
                    terminal = false,
                    allowRetry = false
                )
            }
            MemoryMaintenanceJobStatus.FAILED_TERMINAL -> {
                MemoryMaintenanceNotificationDecision.ShowFailed(
                    notificationKey = notificationKey,
                    terminal = true,
                    allowRetry = job.lastError?.startsWith(MEMORY_MUTATION_UNRECOVERABLE_STAGING_PREFIX) != true
                )
            }
            MemoryMaintenanceJobStatus.WAITING_REPAIR -> {
                MemoryMaintenanceNotificationDecision.ShowFailed(
                    notificationKey = notificationKey,
                    terminal = true,
                    allowRetry = true
                )
            }
            MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY -> {
                MemoryMaintenanceNotificationDecision.ShowFailed(
                    notificationKey = notificationKey,
                    terminal = true,
                    allowRetry = false
                )
            }
            else -> MemoryMaintenanceNotificationDecision.None
        }
    }

    private fun notificationKey(jobId: String, idempotencyKey: String): String =
        idempotencyKey.ifBlank { jobId }

    private companion object {
        val LEGACY_ROOM_INDEX_JOB_TYPES = setOf(
            MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
            MemoryMaintenanceJobType.REPAIR_MARKDOWN_METADATA
        )
        val START_NOTIFICATION_JOB_TYPES = setOf(
            MemoryMaintenanceJobType.DISTILL_DAILY_NOTES,
            MemoryMaintenanceJobType.PROMOTE_LONG_TERM_CANDIDATE,
            MemoryMaintenanceJobType.COMPACTION_FLUSH,
            MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH
        )
        val FAILURE_NOTIFICATION_STATUSES = setOf(
            MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
            MemoryMaintenanceJobStatus.FAILED_TERMINAL,
            MemoryMaintenanceJobStatus.WAITING_REPAIR,
            MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY
        )
    }
}

sealed class MemoryMaintenanceNotificationDecision {
    data object None : MemoryMaintenanceNotificationDecision()
    data class ShowStarted(val notificationKey: String) : MemoryMaintenanceNotificationDecision()
    data class ShowFailed(
        val notificationKey: String,
        val terminal: Boolean,
        val allowRetry: Boolean
    ) : MemoryMaintenanceNotificationDecision()
    data class Cancel(val notificationKey: String) : MemoryMaintenanceNotificationDecision()
}
