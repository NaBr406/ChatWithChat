package cn.nabr.chatwithchat.util

private const val RESPONSE_STOPPED_PREFIX = "\n\n[响应已停止："
private const val LEGACY_RESPONSE_STOPPED_PREFIX = "\n\n[Response stopped: "
private const val ASSISTANT_ERROR_PREFIX = "错误："
private const val LEGACY_ASSISTANT_ERROR_PREFIX = "Error: "

internal fun buildAssistantErrorContent(existingContent: String, error: String): String = when {
    existingContent.isBlank() -> "$ASSISTANT_ERROR_PREFIX$error"
    else -> "$existingContent$RESPONSE_STOPPED_PREFIX$error]"
}

internal fun stripAssistantErrorNote(content: String): String {
    return stripAssistantErrorNote(content, RESPONSE_STOPPED_PREFIX)
        ?: stripAssistantErrorNote(content, LEGACY_RESPONSE_STOPPED_PREFIX)
        ?: content
}

private fun stripAssistantErrorNote(content: String, prefix: String): String? {
    val markerIndex = content.lastIndexOf(prefix)
    return if (markerIndex >= 0 && content.endsWith("]")) {
        content.substring(0, markerIndex)
    } else {
        null
    }
}

internal fun isAssistantErrorMessage(content: String): Boolean {
    val trimmed = content.trimStart()
    return trimmed.startsWith(ASSISTANT_ERROR_PREFIX) || trimmed.startsWith(LEGACY_ASSISTANT_ERROR_PREFIX)
}