package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.runtime.staticCompositionLocalOf

fun interface NotificationPermissionRequester {
    fun requestPostNotificationsPermission()
}

val LocalNotificationPermissionRequester = staticCompositionLocalOf<NotificationPermissionRequester> {
    NotificationPermissionRequester {}
}
