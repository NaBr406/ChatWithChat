package cn.nabr.chatwithchat.presentation.common

import androidx.compose.runtime.staticCompositionLocalOf

fun interface NotificationPermissionRequester {
    fun requestPostNotificationsPermission()
}

val LocalNotificationPermissionRequester = staticCompositionLocalOf<NotificationPermissionRequester> {
    NotificationPermissionRequester {}
}
