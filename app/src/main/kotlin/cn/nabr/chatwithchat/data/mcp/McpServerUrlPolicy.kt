package cn.nabr.chatwithchat.data.mcp

import cn.nabr.chatwithchat.data.websearch.WebFetchRequestPolicy
import cn.nabr.chatwithchat.data.websearch.WebFetchUrlValidator
import javax.inject.Inject
import javax.inject.Singleton

/** Stable reason code for a rejected MCP server URL, surfaced when adding or editing a server. */
enum class McpUrlRejection(val code: String) {
    INVALID_URL("mcp_invalid_url"),
    INVALID_SCHEME("mcp_invalid_url_scheme"),
    CREDENTIALS_IN_URL("mcp_url_credentials_rejected"),
    PRIVATE_ADDRESS("mcp_private_address_rejected")
}

class McpUrlRejectedException(val rejection: McpUrlRejection) : Exception(rejection.code)

/**
 * Validates a user-entered MCP server URL.
 *
 * Unlike `fetch_url`, the URL here is typed by the user rather than produced by a model, so this is not
 * an SSRF boundary in the same sense — there is no untrusted party choosing the target. It still earns
 * its place: it rejects `http://` credentials and typos that would otherwise quietly point the app at a
 * router or link-local address, and it makes reaching a LAN server a deliberate per-server choice
 * ([McpServerConfig.allowPrivateNetwork]) instead of a silent default.
 *
 * The address checks reuse [WebFetchUrlValidator] so both paths agree on what "publicly routable" means
 * rather than growing a second, drifting definition.
 */
@Singleton
class McpServerUrlPolicy @Inject constructor() {
    private val validator = WebFetchUrlValidator()

    fun validate(rawUrl: String, allowPrivateNetwork: Boolean) {
        try {
            validator.validate(rawUrl, WebFetchRequestPolicy(allowPrivateNetwork = allowPrivateNetwork))
        } catch (failure: IllegalArgumentException) {
            throw McpUrlRejectedException(failure.toRejection())
        }
    }

    fun validateOrNull(rawUrl: String, allowPrivateNetwork: Boolean): McpUrlRejection? =
        runCatching { validate(rawUrl, allowPrivateNetwork) }
            .exceptionOrNull()
            .let { failure -> (failure as? McpUrlRejectedException)?.rejection }

    private fun IllegalArgumentException.toRejection(): McpUrlRejection = when (message) {
        "invalid_url_scheme" -> McpUrlRejection.INVALID_SCHEME
        "invalid_url_user_info" -> McpUrlRejection.CREDENTIALS_IN_URL
        "private_url_rejected" -> McpUrlRejection.PRIVATE_ADDRESS
        else -> McpUrlRejection.INVALID_URL
    }
}
