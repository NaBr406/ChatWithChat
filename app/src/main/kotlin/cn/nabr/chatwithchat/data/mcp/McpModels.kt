package cn.nabr.chatwithchat.data.mcp

/** Remote transports this app speaks. Local stdio servers are out of scope: Android cannot spawn them. */
enum class McpTransport {
    /** Current spec transport. Tried first. */
    STREAMABLE_HTTP,

    /** Deprecated HTTP+SSE transport, kept because a lot of deployed servers still only offer it. */
    SSE
}

/**
 * A user-configured MCP server.
 *
 * [alias] is derived from [displayName] and kept unique, because it is the disambiguating segment of
 * every bridged tool name (see [McpToolNamespace]). Renaming a server would therefore rename its tools
 * and orphan their enablement overrides, so the alias is assigned once at creation and never rewritten.
 *
 * [authToken] is stored as plain text in the database, matching how provider API keys already live in
 * `platform_v2.token`. Encrypting only MCP credentials would imply a protection the rest of the app does
 * not offer.
 */
data class McpServerConfig(
    val serverId: String,
    val alias: String,
    val displayName: String,
    val url: String,
    val transport: McpTransport = McpTransport.STREAMABLE_HTTP,
    val authToken: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val enabled: Boolean = false,
    /** Opt-in per server, so a self-hosted LAN endpoint can be reached without loosening the default. */
    val allowPrivateNetwork: Boolean = false,
    val lastSyncedAt: Long? = null,
    val lastSyncError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Hints a server volunteers about one of its tools.
 *
 * Recorded for display only. The MCP spec is explicit that a client must not make security decisions
 * from these, and this app does not: every bridged tool gets the same write-capable policy regardless of
 * what the server claims. Showing them still helps a user judge a server before enabling its tools.
 */
data class McpToolAnnotations(
    val title: String? = null,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null
)

/**
 * One tool as last seen on a server, plus this app's verdict on whether it can be exposed.
 *
 * The raw [inputSchemaJson] is retained so a later app version with a wider schema subset can re-run
 * translation from cache instead of requiring the server to be reachable again.
 */
data class McpToolDescriptor(
    val serverId: String,
    val toolName: String,
    val namespacedName: String,
    val description: String,
    val inputSchemaJson: String,
    val annotations: McpToolAnnotations = McpToolAnnotations(),
    /** `null` when the tool is usable; otherwise a stable code the settings screen explains. */
    val rejectionCode: String? = null,
    val rejectionPath: String? = null,
    val syncedAt: Long = System.currentTimeMillis()
) {
    val isUsable: Boolean
        get() = rejectionCode == null
}

/**
 * Two tools on the same server whose names collapse to one app-side name after sanitizing and
 * truncation. Rare, but it must be a visible verdict rather than a registry-wide install failure.
 */
const val MCP_TOOL_NAME_COLLISION = "tool_name_collision"

/** A server's tools alongside the server itself, which is the shape both the UI and the sync layer want. */
data class McpServerCatalogEntry(
    val server: McpServerConfig,
    val tools: List<McpToolDescriptor>
) {
    val usableTools: List<McpToolDescriptor>
        get() = tools.filter(McpToolDescriptor::isUsable)

    val rejectedTools: List<McpToolDescriptor>
        get() = tools.filterNot(McpToolDescriptor::isUsable)
}

/** Prefix of every MCP approval-grant scope key. Shared so granting and revoking cannot drift apart. */
const val MCP_SESSION_SCOPE_PREFIX = "mcp:"

/** Scope prefix covering every tool on one server, for revoking in bulk. */
fun mcpServerScopePrefix(serverId: String): String = "$MCP_SESSION_SCOPE_PREFIX$serverId:"

/** Scope key for exactly one tool on one server. */
fun mcpToolScopeKey(serverId: String, toolName: String): String =
    "$MCP_SESSION_SCOPE_PREFIX$serverId:$toolName"
