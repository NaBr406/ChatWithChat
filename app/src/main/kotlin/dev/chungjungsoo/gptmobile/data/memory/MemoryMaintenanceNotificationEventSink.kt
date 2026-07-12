package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.notification.AppNotificationManager
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject

class MemoryMaintenanceNotificationEventSink @Inject constructor(
    private val settingRepository: SettingRepository,
    private val appNotificationManager: AppNotificationManager,
    private val notificationPolicy: MemoryMaintenanceNotificationPolicy
) : MemoryMaintenanceEventSink {
    override suspend fun onStatusChanged(event: MemoryMaintenanceStatusChangedEvent) {
        runCatching {
            val decision = notificationPolicy.decide(
                event = event,
                preferenceEnabled = settingRepository.fetchMemoryMaintenanceNotificationsEnabled(),
                systemPermissionGranted = appNotificationManager.canPostNotifications()
            )
            when (decision) {
                MemoryMaintenanceNotificationDecision.None -> Unit
                is MemoryMaintenanceNotificationDecision.ShowStarted -> {
                    appNotificationManager.showMemoryMaintenanceStarted(event.newJob)
                }
                is MemoryMaintenanceNotificationDecision.ShowFailed -> {
                    appNotificationManager.showMemoryMaintenanceFailed(
                        job = event.newJob,
                        terminal = decision.terminal,
                        allowRetry = decision.allowRetry
                    )
                }
                is MemoryMaintenanceNotificationDecision.Cancel -> {
                    appNotificationManager.cancelMemoryMaintenance(event.newJob)
                }
            }
        }
    }
}
