package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.runtime.staticCompositionLocalOf

fun interface ToolPermissionRequester {
    fun requestToolPermissions(toolName: String, onResult: (Boolean) -> Unit)
}

val LocalToolPermissionRequester = staticCompositionLocalOf<ToolPermissionRequester> {
    ToolPermissionRequester { _, onResult -> onResult(false) }
}
