package cn.nabr.chatwithchat.data.websearch

import java.net.URI
import java.util.Locale

/** 各后端返回的 url 都要过这一道：拿不出 http(s) scheme 和 host 的一律丢掉。 */
internal fun String.takeIfHttpUrl(): String? {
    val trimmed = trim()
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.US)
    val host = uri.host?.trim().orEmpty()
    return trimmed.takeIf { scheme in HTTP_URL_SCHEMES && host.isNotBlank() }
}

private val HTTP_URL_SCHEMES = setOf("http", "https")
