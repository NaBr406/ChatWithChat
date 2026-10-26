package cn.nabr.chatwithchat.presentation.ui.chat

import java.net.URI

data class OpenUiHostBinding(val identity: String, val revision: String, val interactive: Boolean)

data class OpenUiHostAction(val identity: String, val revision: String, val action: String, val value: String)

/** Restricts the native boundary; all other interactions stay inside the page. */
internal object OpenUiActionPolicy {
    fun accepts(event: OpenUiHostAction): Boolean {
        if (
            event.value.isBlank() ||
            event.value.length > 32768 ||
            event.value.any { it.code < 32 && it !in "\n\r\t" }
        ) {
            return false
        }
        return when (event.action) {
            "ToAssistant", "CopyToClipboard" -> true
            "OpenUrl" -> {
                if (event.value.length > 8192) return false
                val uri = runCatching { URI(event.value) }.getOrNull() ?: return false
                uri.scheme?.lowercase() in setOf("http", "https") &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null
            }
            else -> false
        }
    }
}
