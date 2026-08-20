package cn.nabr.chatwithchat.data.mcp

import cn.nabr.chatwithchat.data.tool.ToolProvider
import cn.nabr.chatwithchat.data.tool.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Keeps the tool registry's dynamic half in step with the cached MCP catalog.
 *
 * Translation runs here rather than being read back from the sync pass, because the registry has to be
 * repopulated on cold start from the database alone — a server may well be unreachable then. The cached
 * raw schema is the input either way, so both paths produce the same result.
 *
 * Writes are serialized: `ToolRegistry.installDynamicProviders` replaces the entire dynamic set, so two
 * concurrent refreshes could otherwise install a stale batch last.
 */
@Singleton
class McpToolRegistrarImpl @Inject constructor(
    private val repository: McpServerRepository,
    private val connectionManager: McpConnectionManager,
    private val toolRegistry: ToolRegistry
) : McpToolRegistrar {
    private val refreshLock = Mutex()

    override suspend fun refreshInstalledTools() {
        refreshLock.withLock {
            val serversById = repository.loadServers().associateBy(McpServerConfig::serverId)
            val providers = repository.loadInstallableTools().mapNotNull { descriptor ->
                val server = serversById[descriptor.serverId] ?: return@mapNotNull null
                descriptor.toProviderOrNull(server)
            }
            // A rejected batch leaves the previous snapshot in place; the failure is not fatal to chat,
            // so it is swallowed here and stays visible through the per-tool verdicts in settings.
            toolRegistry.installDynamicProviders(providers)
        }
    }

    private fun McpToolDescriptor.toProviderOrNull(server: McpServerConfig): ToolProvider? {
        val schema = runCatching {
            schemaJson.parseToJsonElement(inputSchemaJson).jsonObject
        }.getOrNull() ?: return null

        val translation = McpSchemaTranslator.translate(
            properties = schema["properties"] as? JsonObject,
            required = schema.requiredNames(),
            rawSchema = schema
        )
        // Re-translating can disagree with the verdict stored at sync time only if the app's schema subset
        // changed between versions, which is exactly the case this recomputation exists to handle.
        val parameters = (translation as? McpSchemaTranslation.Accepted)?.parameters ?: return null

        return McpToolProvider(
            server = server,
            descriptor = this,
            parameters = parameters,
            invoker = connectionManager
        )
    }

    private fun JsonObject.requiredNames(): List<String> =
        (this["required"] as? JsonArray)
            ?.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
            }
            .orEmpty()

    private companion object {
        private val schemaJson = Json {
            isLenient = true
            ignoreUnknownKeys = true
        }
    }
}
