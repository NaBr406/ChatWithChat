package cn.nabr.chatwithchat.data.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Seam for pushing a refreshed MCP catalog into the tool registry.
 *
 * Declared here so the sync layer does not depend on the bridge; the registry-facing implementation
 * lives alongside the providers it builds.
 */
fun interface McpToolRegistrar {
    suspend fun refreshInstalledTools()

    companion object {
        val NoOp = McpToolRegistrar {}
    }
}

sealed interface McpSyncResult {
    data class Synced(
        val usableToolCount: Int,
        val rejectedToolCount: Int,
        val truncated: Boolean
    ) : McpSyncResult

    data class Failed(val reasonCode: String) : McpSyncResult
}

/**
 * Refreshes one server's cached `tools/list` and hands the result to the registry.
 *
 * A failed sync deliberately leaves the previous catalog in place: a subway-tunnel network drop should
 * not silently strip tools the user already enabled and approved.
 */
@Singleton
class McpToolSync @Inject constructor(
    private val repository: McpServerRepository,
    private val connectionManager: McpConnectionManager,
    private val registrar: McpToolRegistrar,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun syncAll(): Map<String, McpSyncResult> = repository.loadServers()
        .filter(McpServerConfig::enabled)
        .associate { server -> server.serverId to sync(server) }

    suspend fun sync(config: McpServerConfig): McpSyncResult = try {
        val page = connectionManager.withSession(config) { client -> client.listAllTools() }
        val descriptors = page.tools.toDescriptors(config)
        repository.replaceTools(config.serverId, descriptors, clock())
        if (page.truncated) {
            // Never let a cap read as "we saw everything": the user is told the list was cut short.
            repository.recordSyncFailure(config.serverId, MCP_TOOL_LIST_TRUNCATED)
        }
        registrar.refreshInstalledTools()
        McpSyncResult.Synced(
            usableToolCount = descriptors.count(McpToolDescriptor::isUsable),
            rejectedToolCount = descriptors.count { descriptor -> !descriptor.isUsable },
            truncated = page.truncated
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        val reasonCode = failure.reasonCode()
        repository.recordSyncFailure(config.serverId, reasonCode)
        McpSyncResult.Failed(reasonCode)
    }

    private suspend fun Client.listAllTools(): ToolPage {
        val collected = mutableListOf<Tool>()
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_PAGES && collected.size < MAX_TOOLS) {
            val result = if (cursor == null) {
                listTools()
            } else {
                listTools(ListToolsRequest(PaginatedRequestParams(cursor = cursor)))
            }
            collected += result.tools
            pages += 1
            cursor = result.nextCursor?.takeIf { next -> next.isNotBlank() } ?: break
        }
        val truncated = cursor != null || collected.size > MAX_TOOLS
        return ToolPage(collected.take(MAX_TOOLS), truncated)
    }

    /**
     * Translates a server's tool list, resolving app-side names and recording a verdict per tool.
     *
     * Sorted by server-side name so that name assignment — and therefore which tool loses a collision —
     * is deterministic across syncs rather than dependent on the server's ordering.
     */
    private fun List<Tool>.toDescriptors(config: McpServerConfig): List<McpToolDescriptor> {
        val claimedNames = mutableSetOf<String>()
        return sortedBy(Tool::name).map { tool ->
            val namespacedName = McpToolNamespace.namespacedName(
                serverAlias = config.alias,
                serverId = config.serverId,
                toolName = tool.name
            )
            val schema = tool.inputSchema
            val translation = McpSchemaTranslator.translate(
                properties = schema?.properties,
                required = schema?.required.orEmpty(),
                rawSchema = schema?.toRawJson()
            )
            val rejection = when {
                // A duplicate app-side name is a per-tool verdict, not a catalog-wide install failure.
                !claimedNames.add(namespacedName) -> MCP_TOOL_NAME_COLLISION to null
                translation is McpSchemaTranslation.Rejected ->
                    translation.reason.code to translation.path
                else -> null
            }
            McpToolDescriptor(
                serverId = config.serverId,
                toolName = tool.name,
                namespacedName = namespacedName,
                description = McpSchemaTranslator.boundedDescription(tool.description),
                inputSchemaJson = schema?.toRawJson()?.toString() ?: EMPTY_SCHEMA_JSON,
                annotations = McpToolAnnotations(
                    title = tool.annotations?.title,
                    readOnlyHint = tool.annotations?.readOnlyHint,
                    destructiveHint = tool.annotations?.destructiveHint,
                    idempotentHint = tool.annotations?.idempotentHint,
                    openWorldHint = tool.annotations?.openWorldHint
                ),
                rejectionCode = rejection?.first,
                rejectionPath = rejection?.second,
                syncedAt = clock()
            )
        }
    }

    private class ToolPage(val tools: List<Tool>, val truncated: Boolean)

    private companion object {
        private const val MAX_PAGES = 20
        private const val MAX_TOOLS = 500
        private const val EMPTY_SCHEMA_JSON = """{"type":"object","properties":{}}"""
    }
}

const val MCP_TOOL_LIST_TRUNCATED = "mcp_tool_list_truncated"

/**
 * Rebuilds the wire form of a [ToolSchema].
 *
 * The SDK's typed schema exposes `properties`, `required` and `$defs` but drops root-level
 * `additionalProperties`, so this is a reconstruction rather than the server's literal bytes. Caching it
 * gives a future app version with a wider schema subset something to re-translate without needing the
 * server to be reachable again.
 */
private fun ToolSchema.toRawJson(): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    properties?.let { value -> put("properties", value) }
    required?.takeIf { names -> names.isNotEmpty() }?.let { names ->
        put("required", JsonArray(names.map(::JsonPrimitive)))
    }
    defs?.takeIf { value -> value.isNotEmpty() }?.let { value -> put("\$defs", value) }
}
