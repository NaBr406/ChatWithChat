package cn.nabr.chatwithchat.data.mcp

import cn.nabr.chatwithchat.data.tool.ToolApprovalPolicy
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolCategory
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolDiscoveryMetadata
import cn.nabr.chatwithchat.data.tool.ToolEffect
import cn.nabr.chatwithchat.data.tool.ToolEnablementGroup
import cn.nabr.chatwithchat.data.tool.ToolExposure
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolPolicy
import cn.nabr.chatwithchat.data.tool.ToolProvider
import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.ToolSecurityPolicy
import cn.nabr.chatwithchat.data.tool.ToolSettingsMetadata
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The one operation [McpToolProvider] needs from the transport layer.
 *
 * Narrow on purpose: it keeps the provider's policy, argument, and result-mapping behaviour unit-testable
 * without standing up a session or a socket.
 */
interface McpToolInvoker {
    suspend fun callTool(
        server: McpServerConfig,
        toolName: String,
        arguments: Map<String, Any?>
    ): CallToolResult?
}

/**
 * Bridges one MCP tool into the app's tool platform.
 *
 * Every instance is write-capable and requires per-call approval, regardless of what the server said
 * about it. That is the whole trust model: [McpToolAnnotations] arrive from an unverified third party, the
 * MCP spec says a client must not make security decisions from them, and this app does not. The hints are
 * carried for display only.
 *
 * Two consequences fall out of that policy for free:
 * `ToolEnablementResolver.isSafeForDefaultEnablement` refuses to default-enable a write-capable tool, so
 * an MCP tool stays off until the user explicitly turns it on; and `ToolExecutor` routes every call
 * through `ToolApprovalBroker`, so the user confirms each invocation.
 */
class McpToolProvider(
    private val server: McpServerConfig,
    private val descriptor: McpToolDescriptor,
    parameters: ToolDefinition.Parameters,
    private val invoker: McpToolInvoker
) : ToolProvider {

    override val definition: ToolDefinition = ToolDefinition(
        name = descriptor.namespacedName,
        // Prefixed with the server name so the model can tell two servers' similar tools apart.
        description = "[${server.displayName}] " +
            descriptor.description.ifBlank { "MCP tool ${descriptor.toolName}." },
        parameters = parameters
    )

    override val settingsMetadata: ToolSettingsMetadata = ToolSettingsMetadata(
        userVisible = true,
        category = ToolCategory.Mcp,
        // Stated explicitly even though the resolver would refuse to honour `true` for a write tool.
        defaultEnabled = false,
        isSensitive = true,
        presentationKey = MCP_PRESENTATION_KEY,
        iconKey = MCP_ICON_KEY,
        enablementGroup = ToolEnablementGroup.McpServer
    )

    /**
     * Resident, not on-demand.
     *
     * A configured MCP server is only useful as a set: the model cannot price a basket if it can see
     * `query-meals` but not `calculate-price`. Hiding the set behind `discover_tools` also leaned on the
     * intent matcher, which cannot help a CJK query — `ToolScopePlanner.discoveryWords` tokenizes on word
     * boundaries, so a Chinese description collapses to a single long token that no short query contains.
     *
     * The advertisement budget was raised to fit a whole server. A server larger than the budget still
     * degrades safely: `shouldReserveDiscovery` keeps a `discover_tools` slot, so the tools that did not
     * fit stay reachable — just alphabetically rather than by relevance.
     */
    override val discoveryMetadata: ToolDiscoveryMetadata = ToolDiscoveryMetadata(
        exposure = ToolExposure.Resident,
        intentTags = descriptor.intentTags(),
        // A configured server is first-party data the open web does not have. Without this the pre-flight
        // web-search shortcut answered the turn before the tool loop ever ran, and a connected server looked
        // installed but never got called.
        supersedesWebSearch = true
    )

    /**
     * Fixed, and deliberately not derived from `readOnlyHint`. A server claiming to be read-only is not
     * evidence; treating it as evidence is exactly the escalation this boundary exists to prevent.
     */
    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy(
        effect = ToolEffect.EXTERNAL_WRITE,
        approvalPolicy = ToolApprovalPolicy.REQUIRE_EACH_CALL
    )

    /**
     * Narrowed to one server *and* one tool on purpose.
     *
     * A server-wide grant would mean approving a menu lookup also authorizes placing an order, since both
     * live on the same server. Per tool, a full ordering flow costs one confirmation per distinct capability
     * and none for repeats — a basket of three items queries the detail tool three times, asking once.
     */
    override val sessionApprovalScope: String =
        "$MCP_SESSION_SCOPE_PREFIX${server.serverId}:${descriptor.toolName}"

    override val policy: ToolPolicy = ToolPolicy(
        timeoutSeconds = MCP_CALL_TIMEOUT_SECONDS,
        maxResultChars = MCP_MAX_RESULT_CHARS
    )

    override fun progressLabel(call: ToolCall): String =
        descriptor.annotations.title?.takeIf { title -> title.isNotBlank() }
            ?: "${server.displayName}: ${descriptor.toolName}"

    /**
     * A human-readable, non-JSON summary of the arguments.
     *
     * `ToolApprovalPreview` rejects a raw-JSON summary outright, and rightly so: nobody can judge
     * `{"path":"/etc","recursive":true}` at a glance in a confirmation dialog. MCP arguments are
     * arbitrary, so this flattens the top level into bounded `key: value` pairs.
     */
    override fun approvalArgumentSummary(call: ToolCall): String {
        val header = "${server.displayName} · ${descriptor.toolName}"
        val arguments = call.argumentsObjectOrNull() ?: return header
        if (arguments.isEmpty()) return "$header (no arguments)"

        val rendered = arguments.entries
            .sortedBy { entry -> entry.key }
            .take(MAX_SUMMARY_ARGUMENTS)
            .joinToString(separator = ", ") { (key, value) ->
                "${key.take(MAX_SUMMARY_KEY_CHARS)}: ${value.renderForHuman()}"
            }
        val omitted = (arguments.size - MAX_SUMMARY_ARGUMENTS).coerceAtLeast(0)
        return "$header — $rendered" + if (omitted > 0) ", +$omitted more" else ""
    }

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult = try {
        val arguments = call.argumentsObjectOrNull() ?: return call.failure("mcp_invalid_arguments")
        val result = invoker.callTool(server, descriptor.toolName, arguments.toPlainMap())
        result?.toToolResult(call) ?: call.failure("mcp_empty_response")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        call.failure(failure.reasonCode())
    }

    /**
     * Maps an MCP result onto [ToolResult].
     *
     * Text crosses over, and so does the text of an embedded resource. A `resource` block is how a server
     * hands back a document it just read, not an attachment it wants rendered: github-mcp-server returns
     * every file body that way — a [TextResourceContents] sitting beside a one-line "successfully
     * downloaded text file" note — so filtering to [TextContent] told the model the read had succeeded and
     * then handed it none of the file. That is worse than an outright failure, because the result is not
     * even marked as an error: the model sees a successful read of nothing, and retries the same call in
     * different shapes until the round budget is gone. A [ResourceLink] is forwarded as its own
     * description, since there the link *is* the payload.
     *
     * Bytes still have nowhere to go: [ToolResult] carries text plus structured JSON, so images, audio and
     * blob resources are counted and announced rather than dropped in silence, and the model can say the
     * content exists instead of acting as though the tool returned nothing. `structuredContent` maps
     * across directly.
     *
     * Nothing is clipped here. `ToolExecutor` bounds the result against [MCP_MAX_RESULT_CHARS] and attaches
     * a truncation notice; a silent cut here is what would make a large file unreadable rather than short.
     */
    private fun CallToolResult.toToolResult(call: ToolCall): ToolResult {
        val forwarded = content.map { block -> block.textForModelOrNull() }
        val nonTextBlocks = forwarded.count { text -> text == null }
        val body = buildList {
            addAll(forwarded.filterNotNull().filter { text -> text.isNotBlank() })
            if (nonTextBlocks > 0) {
                add("($nonTextBlocks non-text content block(s) omitted: this app forwards text only)")
            }
        }
        return ToolResult(
            callId = call.id,
            name = call.name,
            content = body.joinToString(separator = "\n").ifBlank { EMPTY_RESULT_TEXT },
            isError = isError == true,
            structuredContent = structuredContent,
            metadata = buildMap {
                put("mcp_server", server.alias)
                put("mcp_tool", descriptor.toolName)
                if (nonTextBlocks > 0) put("mcp_omitted_content_blocks", nonTextBlocks.toString())
            }
        )
    }

    /**
     * The block as text the model can read, or null when it carries bytes that cannot cross over.
     *
     * A text resource is prefixed with its uri because a tool may return several of them in one result —
     * "read these four files" is one call — and an unlabelled run of bodies leaves the model guessing which
     * is which. One short line per resource is cheaper than that ambiguity.
     */
    private fun ContentBlock.textForModelOrNull(): String? = when (this) {
        is TextContent -> text
        is EmbeddedResource -> when (val contents = resource) {
            is TextResourceContents -> contents.uri
                .takeIf { uri -> uri.isNotBlank() }
                ?.let { uri -> "[resource $uri]\n${contents.text}" }
                ?: contents.text
            // A blob is base64 bytes: forwarding it would spend the whole result budget on something the
            // model cannot read, so it is counted as omitted like an image.
            else -> null
        }
        // The link is the payload here — a server sends one instead of the content, typically because the
        // content is too large to embed — so describing it loses nothing.
        is ResourceLink -> describeForModel()
        else -> null
    }

    private fun ResourceLink.describeForModel(): String = buildString {
        append("(resource link: ")
        append(title?.takeIf { value -> value.isNotBlank() } ?: name)
        append(" — ").append(uri)
        mimeType?.takeIf { value -> value.isNotBlank() }?.let { value -> append(", ").append(value) }
        size?.let { bytes -> append(", ").append(bytes).append(" bytes") }
        description?.takeIf { value -> value.isNotBlank() }?.let { value -> append("; ").append(value) }
        append(")")
    }

    private fun ToolCall.argumentsObjectOrNull(): JsonObject? = runCatching {
        argumentJson.parseToJsonElement(arguments.ifBlank { EMPTY_ARGUMENTS }).jsonObject
    }.getOrNull()

    private fun ToolCall.failure(errorCode: String): ToolResult = ToolResult(
        callId = id,
        name = name,
        content = errorCode,
        isError = true,
        metadata = mapOf(
            "error_code" to errorCode,
            "mcp_server" to server.alias,
            "mcp_tool" to descriptor.toolName
        )
    )

    private companion object {
        private const val MCP_PRESENTATION_KEY = "mcp_tool"
        private const val MCP_ICON_KEY = "mcp"
        private const val MCP_CALL_TIMEOUT_SECONDS = 60L

        /**
         * Sized against a measured catalog response rather than guessed.
         *
         * `query-meals` on a real ordering server returns 29,418 chars and accepts no narrowing parameter,
         * so the menu cannot be requested in pieces. At the previous 4,000 the model received 14% of a menu,
         * cut mid-JSON, and then could not name a single product code — which is what made a large server
         * look connected but unusable. `ToolLoopConfig.maxTotalToolResultChars` still caps the conversation.
         */
        private const val MCP_MAX_RESULT_CHARS = 32_000
        private const val MAX_SUMMARY_ARGUMENTS = 4
        private const val MAX_SUMMARY_KEY_CHARS = 32
        private const val MAX_SUMMARY_VALUE_CHARS = 48
        private const val EMPTY_ARGUMENTS = "{}"
        private const val EMPTY_RESULT_TEXT = "(the tool returned no text content)"
        private val argumentJson = Json {
            isLenient = true
            ignoreUnknownKeys = true
        }
        private val WHITESPACE = Regex("\\s+")

        private fun JsonElement.renderForHuman(): String {
            val raw = when (this) {
                is JsonPrimitive -> content
                is JsonObject -> "{$size field${if (size == 1) "" else "s"}}"
                is JsonArray -> "[$size item${if (size == 1) "" else "s"}]"
            }
            return raw.replace(WHITESPACE, " ").trim().take(MAX_SUMMARY_VALUE_CHARS).ifBlank { "-" }
        }
    }
}

/**
 * Discovery terms drawn from the tool's name, title, and description.
 *
 * Description terms are included so that a server whose tool names are terse still has something to match
 * on. They are capped because a verbose description would otherwise swamp the name-derived terms, and only
 * word-shaped tokens are kept.
 *
 * This helps Latin-script queries. It does little for CJK, where `ToolScopePlanner.discoveryWords`
 * tokenizes on word boundaries and a Chinese phrase becomes one long token — which is why MCP tools are
 * advertised as [ToolExposure.Resident] rather than relying on this matching at all.
 */
private fun McpToolDescriptor.intentTags(): Set<String> {
    val fromName = toolName.split('_', '-', '.', '/')
    val fromTitle = annotations.title?.split(' ', '_', '-').orEmpty()
    val fromDescription = TAG_SPLIT
        .split(description)
        .filter { part -> part.length in MIN_TAG_CHARS..MAX_TAG_CHARS }
        .take(MAX_DESCRIPTION_TAGS)
    return (fromName + fromTitle + fromDescription)
        .map { part -> part.lowercase().trim() }
        .filter { part -> part.length >= MIN_TAG_CHARS }
        .toSet()
}

private val TAG_SPLIT = Regex("""[^\p{L}\p{N}]+""")
private const val MAX_TAG_CHARS = 24
private const val MAX_DESCRIPTION_TAGS = 12

private fun JsonObject.toPlainMap(): Map<String, Any?> = mapValues { (_, value) -> value.toPlainValue() }

/** MCP's `callTool` takes plain Kotlin values, so the parsed JSON tree is unwrapped. */
private fun JsonElement.toPlainValue(): Any? = when (this) {
    is JsonPrimitive -> when {
        isString -> content
        this is kotlinx.serialization.json.JsonNull -> null
        else -> booleanOrNull ?: content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
    }
    is JsonObject -> mapValues { (_, value) -> value.toPlainValue() }
    is JsonArray -> map { element -> element.toPlainValue() }
}

private const val MIN_TAG_CHARS = 3
