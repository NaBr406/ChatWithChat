package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import java.net.URI

/**
 * Where a request goes and which key it carries, passed per call.
 *
 * This replaces the mutable `token`/`apiUrl` fields the API implementations used to hold. Those made
 * every `@Singleton` client a shared cell that callers wrote before reading: `setToken` then
 * `setAPIUrl` then the request, with nothing tying the three together. It worked only because of two
 * accidents. The chat path ran one platform at a time, and a turn died with its screen — so two
 * requests could not overlap for long enough to interleave.
 *
 * Neither accident held everywhere. Chat title generation and memory learning already run on the
 * application scope, so they outlive the screen and can land while the user's next message is
 * streaming; `ProviderContextCompactionModelClient` took a mutex to stop its own calls colliding but
 * documented that it could not protect against the chat path. Attachment upload was worse still: it
 * never set the fields at all and sent whichever endpoint the previous request happened to leave
 * behind, so switching between two platforms of the same client type uploaded to the wrong one.
 *
 * Because token and URL were written as a pair, a key never reached a mismatched host — but the
 * conversation did. That failure is invisible from inside the app and nearly impossible to
 * reproduce on demand, which is why it is fixed by construction here rather than by another lock.
 */
data class ProviderEndpoint(
    val token: String?,
    val apiUrl: String,
    /** Path prefix used by OpenAI-compatible endpoints, e.g. `v1` or GLM's empty prefix. */
    val apiPathPrefix: String = "v1",
    val isFullApiUrl: Boolean = false,
    val customHeaders: String = ""
)

fun PlatformV2.providerEndpoint(): ProviderEndpoint = ProviderEndpoint(
    token = token,
    apiUrl = apiUrl,
    apiPathPrefix = "v1".takeUnless { compatibleType == ClientType.GLM }.orEmpty(),
    isFullApiUrl = isFullApiUrl,
    customHeaders = customHeaders
)

fun HttpRequestBuilder.applyCustomHeaders(serializedHeaders: String) {
    serializedHeaders.lineSequence().forEach { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@forEach
        val name = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        val normalizedName = name.lowercase()
        val isManagedHeader = normalizedName in setOf(
            "authorization",
            "content-length",
            "content-type",
            "host"
        ) || normalizedName.startsWith("x-goog-upload-")
        if (name.isNotBlank() && value.isNotBlank() && !isManagedHeader) header(name, value)
    }
}

fun HttpRequestBuilder.applyEndpointHeaders(endpoint: ProviderEndpoint) {
    applyCustomHeaders(endpoint.customHeaders)
}

fun ProviderEndpoint.chatUrl(path: String): String = if (isFullApiUrl) {
    apiUrl.trim()
} else {
    joinApiEndpoint(apiUrl, path)
}

fun ProviderEndpoint.baseApiUrl(): String {
    if (!isFullApiUrl) return apiUrl
    val uri = URI(apiUrl.trim())
    val path = uri.path.orEmpty().trimEnd('/')
    val basePath = when {
        path.endsWith("/chat/completions") -> path.removeSuffix("/chat/completions")
        path.contains("/models/") -> path.substringBeforeLast("/models/").removeSuffix("/v1beta")
        else -> path.substringBeforeLast('/', "")
    }
    return URI(uri.scheme, uri.authority, basePath, null, null).toString()
}

fun ProviderEndpoint.apiPath(path: String): String = listOf(apiPathPrefix.trim('/'), path.trim('/'))
    .filter { it.isNotBlank() }
    .joinToString("/")
