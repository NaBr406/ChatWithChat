package dev.chungjungsoo.gptmobile.data.websearch

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearxngSearchResponse(
    val results: List<SearxngSearchResult> = emptyList(),
    @SerialName("unresponsive_engines")
    val unresponsiveEngines: List<List<String>> = emptyList()
)

@Serializable
data class SearxngSearchResult(
    val title: String? = null,
    val url: String? = null,
    val content: String? = null,
    val engine: String? = null,
    @SerialName("publishedDate")
    val publishedDate: String? = null
) {
    fun toWebSearchResult(): WebSearchResult? {
        val resolvedUrl = url
            ?.trim()
            ?.resolveSearchRedirectUrl()
            .orEmpty()
        if (resolvedUrl.isBlank()) return null

        return WebSearchResult(
            title = title?.trim().orEmpty(),
            url = resolvedUrl,
            snippet = content?.trim().orEmpty(),
            source = engine?.trim()?.takeIf { it.isNotBlank() } ?: "searxng",
            publishedAt = publishedDate?.trim()?.takeIf { it.isNotBlank() }
        )
    }
}

private fun String.resolveSearchRedirectUrl(): String? {
    val candidate = takeIfHttpUrl() ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return candidate
    val host = uri.host
        ?.trim()
        ?.trimEnd('.')
        ?.lowercase(Locale.US)
        .orEmpty()
    if (host.isBlank()) return candidate

    val path = uri.path.orEmpty()
    val isBingRedirect = host.endsWith("bing.com") && path.startsWith("/ck/")
    val isGoogleRedirect = host.endsWith("google.com") && path == "/url"
    val isDuckDuckGoRedirect = host.endsWith("duckduckgo.com") && path.startsWith("/l/")
    if (!isBingRedirect && !isGoogleRedirect && !isDuckDuckGoRedirect) return candidate

    val parameters = uri.rawQuery.orEmpty().queryParameters()
    parameters["url"]?.takeIfHttpUrl()?.let { return it }
    parameters["q"]?.takeIfHttpUrl()?.let { return it }
    parameters["uddg"]?.takeIfHttpUrl()?.let { return it }
    parameters["u"]?.decodeBingRedirectTarget()?.let { return it }

    return null
}

private fun String.queryParameters(): Map<String, String> =
    split('&')
        .mapNotNull { part ->
            val separatorIndex = part.indexOf('=')
            if (separatorIndex <= 0) return@mapNotNull null

            val key = part.substring(0, separatorIndex).urlDecode()
            val value = part.substring(separatorIndex + 1).urlDecode()
            key to value
        }
        .toMap()

private fun String.decodeBingRedirectTarget(): String? {
    val decodedValue = urlDecode()
    val candidates = listOf(
        decodedValue,
        decodedValue.removePrefix("a1"),
        decodedValue.removePrefix("a2")
    ).distinct()

    return candidates.firstNotNullOfOrNull { candidate ->
        candidate
            .decodeBase64Url()
            ?.takeIfHttpUrl()
    }
}

private fun String.decodeBase64Url(): String? {
    val padded = padEnd(length + ((4 - length % 4) % 4), '=')
    val decoders = listOf(Base64.getUrlDecoder(), Base64.getDecoder())
    return decoders.firstNotNullOfOrNull { decoder ->
        runCatching {
            String(decoder.decode(padded), StandardCharsets.UTF_8).trim()
        }.getOrNull()
    }
}

private fun String.urlDecode(): String =
    runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }
        .getOrDefault(this)

private fun String.takeIfHttpUrl(): String? {
    val trimmed = trim()
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.US)
    val host = uri.host?.trim().orEmpty()
    return trimmed.takeIf { scheme in setOf("http", "https") && host.isNotBlank() }
}
