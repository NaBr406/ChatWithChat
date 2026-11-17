package cn.nabr.chatwithchat.data.notification

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import cn.nabr.chatwithchat.R

internal object HyperOsGenerationNotification {
    fun decorate(context: Context, notification: Notification, compactTitle: String): Notification {
        try {
            val protocol = Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
            if (protocol < 3) return notification
            val extras = Bundle(notification.extras)
            // Reuse the exact attempt-scoped PendingIntent; an old island must not stop a retry.
            val stopAction = notification.actions?.firstOrNull()
            extras.putString(
                HyperOsGenerationParams.PARAM_KEY,
                HyperOsGenerationParams.build(
                    title = extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
                    summary = extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
                    compactTitle = compactTitle,
                    canStop = stopAction != null
                )
            )
            extras.putBundle(
                HyperOsGenerationParams.PICS_KEY,
                Bundle().apply {
                    putParcelable(HyperOsGenerationParams.ICON_KEY, Icon.createWithResource(context, R.drawable.ic_generation_island))
                }
            )
            if (stopAction != null) {
                extras.putBundle(
                    HyperOsGenerationParams.ACTIONS_KEY,
                    Bundle().apply {
                        putParcelable(HyperOsGenerationParams.STOP_KEY, stopAction)
                    }
                )
            }
            notification.extras = extras
        } catch (error: RuntimeException) {
            // OEM extension failures must not prevent foreground promotion or ordinary notifications.
            Log.w("ChatGeneration", "Unable to attach HyperOS island notification", error)
        }
        return notification
    }
}
