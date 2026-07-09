package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob

interface MemoryMaintenanceEventSink {
    suspend fun onStatusChanged(event: MemoryMaintenanceStatusChangedEvent)

    data object None : MemoryMaintenanceEventSink {
        override suspend fun onStatusChanged(event: MemoryMaintenanceStatusChangedEvent) = Unit
    }
}

data class MemoryMaintenanceStatusChangedEvent(
    val oldJob: MemoryMaintenanceJob?,
    val newJob: MemoryMaintenanceJob,
    val oldStatus: String?,
    val newStatus: String,
    val occurredAt: Long
)
