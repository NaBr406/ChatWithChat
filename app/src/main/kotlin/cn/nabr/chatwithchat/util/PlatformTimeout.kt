package cn.nabr.chatwithchat.util

import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder

internal fun platformTimeoutSecondsToSocketTimeoutMillis(timeoutSeconds: Int): Long? = when {
    timeoutSeconds <= 0 -> null
    else -> timeoutSeconds * 1_000L
}

internal fun HttpRequestBuilder.applyPlatformStreamingTimeout(timeoutSeconds: Int) {
    platformTimeoutSecondsToSocketTimeoutMillis(timeoutSeconds)?.let { socketTimeoutMillis ->
        timeout {
            this.socketTimeoutMillis = socketTimeoutMillis
        }
    }
}

internal fun formatPlatformTimeout(timeoutSeconds: Int, offLabel: String): String = when {
    timeoutSeconds <= 0 -> offLabel
    else -> "$timeoutSeconds 秒"
}
