package cn.nabr.chatwithchat.data.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.presentation.common.Route
import cn.nabr.chatwithchat.presentation.ui.main.MainActivity
import cn.nabr.chatwithchat.receiver.MemoryMaintenanceRetryReceiver
import javax.inject.Inject

class AppNotificationManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    AppNotificationChannels.MEMORY_MAINTENANCE,
                    context.getString(R.string.notification_channel_memory_maintenance),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_channel_memory_maintenance_description)
                },
                NotificationChannel(
                    AppNotificationChannels.SCHEDULED_REMINDERS,
                    context.getString(R.string.notification_channel_scheduled_reminders),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_channel_scheduled_reminders_description)
                }
            )
        )
    }

    fun canPostNotifications(): Boolean {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun showMemoryMaintenanceStarted(job: MemoryMaintenanceJob) {
        showMemoryMaintenanceNotification(
            job = job,
            title = context.getString(R.string.notification_memory_maintenance_started_title),
            text = context.getString(R.string.notification_memory_maintenance_started_text),
            ongoing = true
        )
    }

    fun showMemoryMaintenanceFailed(
        job: MemoryMaintenanceJob,
        terminal: Boolean,
        allowRetry: Boolean
    ) {
        val title = if (terminal) {
            context.getString(R.string.notification_memory_maintenance_failed_terminal_title)
        } else {
            context.getString(R.string.notification_memory_maintenance_failed_title)
        }
        val text = when {
            allowRetry -> context.getString(R.string.notification_memory_maintenance_failed_terminal_text)
            terminal -> context.getString(R.string.notification_memory_maintenance_blocked_text)
            else -> context.getString(R.string.notification_memory_maintenance_failed_retryable_text)
        }
        showMemoryMaintenanceNotification(
            job = job,
            title = title,
            text = text,
            ongoing = false,
            allowRetry = allowRetry
        )
    }

    fun cancelMemoryMaintenance(job: MemoryMaintenanceJob) {
        NotificationManagerCompat.from(context).cancel(memoryMaintenanceNotificationId(job))
    }

    fun showScheduledReminder(
        notificationId: Int,
        title: String,
        text: String
    ) {
        if (!canPostNotifications()) return

        val notification = NotificationCompat.Builder(context, AppNotificationChannels.SCHEDULED_REMINDERS)
            .setSmallIcon(R.drawable.ic_rounded_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    private fun showMemoryMaintenanceNotification(
        job: MemoryMaintenanceJob,
        title: String,
        text: String,
        ongoing: Boolean,
        allowRetry: Boolean = false
    ) {
        if (!canPostNotifications()) return

        ensureChannels()
        val notification = NotificationCompat.Builder(context, AppNotificationChannels.MEMORY_MAINTENANCE)
            .setSmallIcon(R.drawable.ic_rounded_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openMemoryPendingIntent())
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (allowRetry) {
            notification.addAction(
                0,
                context.getString(R.string.retry),
                retryMemoryPendingIntent(job)
            )
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(memoryMaintenanceNotificationId(job), notification.build())
        }
    }

    private fun openMemoryPendingIntent(): PendingIntent =
        openAppPendingIntent(Route.MEMORY)

    private fun retryMemoryPendingIntent(job: MemoryMaintenanceJob): PendingIntent {
        val intent = Intent(context, MemoryMaintenanceRetryReceiver::class.java).apply {
            putExtra(MemoryMaintenanceRetryReceiver.EXTRA_JOB_ID, job.jobId)
        }
        return PendingIntent.getBroadcast(
            context,
            job.jobId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppPendingIntent(route: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(MainActivity.EXTRA_START_ROUTE, it) }
        }
        return PendingIntent.getActivity(
            context,
            route?.hashCode() ?: REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun memoryMaintenanceNotificationId(job: MemoryMaintenanceJob): Int =
        ("memory_maintenance:${job.idempotencyKey.ifBlank { job.jobId }}".hashCode() and Int.MAX_VALUE)

    private companion object {
        private const val REQUEST_OPEN_APP = 2001
    }
}
