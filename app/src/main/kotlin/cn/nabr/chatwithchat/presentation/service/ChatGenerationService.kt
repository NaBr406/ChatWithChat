package cn.nabr.chatwithchat.presentation.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.generation.ChatGenerationState
import cn.nabr.chatwithchat.data.generation.reportGenerationKeepAliveFailure
import cn.nabr.chatwithchat.data.notification.AppNotificationChannels
import cn.nabr.chatwithchat.data.notification.AppNotificationManager
import cn.nabr.chatwithchat.data.notification.HyperOsGenerationNotification
import cn.nabr.chatwithchat.presentation.ui.chat.ChatTurnCoordinator
import cn.nabr.chatwithchat.presentation.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Protects the existing in-process turns; restarting this service must never replay a tool call. */
@AndroidEntryPoint
class ChatGenerationService : Service() {
    @Inject lateinit var coordinator: ChatTurnCoordinator

    @Inject lateinit var notifications: AppNotificationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var wakeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            coordinator.stopGenerationAttempts(intent.getStringArrayListExtra(EXTRA_ATTEMPT_IDS).orEmpty())
        }
        // Promote even if the turn completed while the start command was queued. Android still
        // requires startForeground after startForegroundService, before we can shut down cleanly.
        if (!promote(coordinator.currentGenerationState())) return START_NOT_STICKY
        if (coordinator.currentGenerationState().activeCount == 0) {
            if (stopSelfResult(startId)) releaseWakeLock()
            return START_NOT_STICKY
        }
        holdWakeLock()
        if (stateJob == null) {
            stateJob = serviceScope.launch {
                coordinator.generationState.collect { state ->
                    if (state.activeCount == 0) {
                        // A newer start command may already be queued; do not stop that generation.
                        if (stopSelfResult(lastStartId)) releaseWakeLock()
                    } else {
                        if (promote(state)) holdWakeLock()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promote(state: ChatGenerationState): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        true
    } catch (error: RuntimeException) {
        reportGenerationKeepAliveFailure(this, error)
        releaseWakeLock()
        stopSelf()
        false
    }

    private fun buildNotification(state: ChatGenerationState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isSaving = state.activeCount > 0 && state.generatingAttemptIds.isEmpty()
        val builder = NotificationCompat.Builder(this, AppNotificationChannels.CHAT_GENERATION)
            .setSmallIcon(R.drawable.ic_rounded_chat)
            .setContentTitle(getString(if (isSaving) R.string.chat_generation_saving else R.string.chat_generation_title))
            .setContentText(getString(R.string.chat_generation_active_count, state.activeCount.coerceAtLeast(1)))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state.generatingAttemptIds.isNotEmpty()) {
            val stop = PendingIntent.getService(
                this,
                NOTIFICATION_ID,
                Intent(this, ChatGenerationService::class.java)
                    .setAction(ACTION_STOP)
                    // Keep old notification actions tied to their attempts, never to a later retry.
                    .setData(android.net.Uri.parse("chatwithchat://generation/${state.generatingAttemptIds.joinToString(",")}"))
                    .putStringArrayListExtra(EXTRA_ATTEMPT_IDS, ArrayList(state.generatingAttemptIds)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.chat_generation_stop), stop)
        }
        return HyperOsGenerationNotification.decorate(
            this,
            builder.build(),
            getString(if (isSaving) R.string.chat_generation_island_saving else R.string.chat_generation_island_generating)
        )
    }

    private fun holdWakeLock() {
        if (wakeJob?.isActive == true) return
        val lock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ChatWithChat:ChatGeneration"
        ).apply { setReferenceCounted(false) }
        wakeLock = lock
        wakeJob = serviceScope.launch {
            try {
                while (isActive) {
                    lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
                    delay(WAKE_LOCK_TIMEOUT_MILLIS / 2)
                }
            } finally {
                if (lock.isHeld) lock.release()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeJob?.cancel()
        wakeJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15's dataSync quota requires immediate service shutdown. Cancellation uses the
        // coordinator's existing partial-answer save, which lives outside this service's scope.
        serviceScope.cancel()
        coordinator.stopGenerationAttempts(coordinator.currentGenerationState().generatingAttemptIds)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_STOP = "cn.nabr.chatwithchat.action.STOP_GENERATION"
        internal const val EXTRA_ATTEMPT_IDS = "attempt_ids"
        internal const val NOTIFICATION_ID = 31001
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1000L
    }
}
