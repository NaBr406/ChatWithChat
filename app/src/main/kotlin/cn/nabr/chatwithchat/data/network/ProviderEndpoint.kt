package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType

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
    val apiPathPrefix: String = "v1"
)

fun PlatformV2.providerEndpoint(): ProviderEndpoint = ProviderEndpoint(
    token = token,
    apiUrl = apiUrl,
    apiPathPrefix = "v1".takeUnless { compatibleType == ClientType.GLM }.orEmpty()
)

fun ProviderEndpoint.apiPath(path: String): String = listOf(apiPathPrefix.trim('/'), path.trim('/'))
    .filter { it.isNotBlank() }
    .joinToString("/")
