package cn.nabr.chatwithchat.data.mcp

import cn.nabr.chatwithchat.data.database.dao.McpDao
import cn.nabr.chatwithchat.data.database.entity.McpServerEntity
import cn.nabr.chatwithchat.data.database.entity.McpToolEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json

interface McpServerRepository {
    fun observeCatalog(): Flow<List<McpServerCatalogEntry>>

    suspend fun loadServers(): List<McpServerConfig>

    suspend fun loadServer(serverId: String): McpServerConfig?

    /** Creates a server, deriving a unique [McpServerConfig.alias] from [displayName]. */
    suspend fun createServer(
        displayName: String,
        url: String,
        transport: McpTransport = McpTransport.STREAMABLE_HTTP,
        authToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        allowPrivateNetwork: Boolean = false
    ): McpServerConfig

    /** Updates the mutable fields of an existing server. The alias is never rewritten. */
    suspend fun updateServer(config: McpServerConfig)

    suspend fun deleteServer(serverId: String)

    suspend fun setServerEnabled(serverId: String, enabled: Boolean)

    suspend fun loadTools(serverId: String): List<McpToolDescriptor>

    /** Usable tools on enabled servers: the exact set the tool registry should hold. */
    suspend fun loadInstallableTools(): List<McpToolDescriptor>

    /** Replaces a server's cached tools wholesale and records the sync outcome. */
    suspend fun replaceTools(serverId: String, tools: List<McpToolDescriptor>, syncedAt: Long)

    suspend fun recordSyncFailure(serverId: String, error: String)
}

@Singleton
class McpServerRepositoryImpl @Inject constructor(
    private val dao: McpDao
) : McpServerRepository {

    override fun observeCatalog(): Flow<List<McpServerCatalogEntry>> =
        combine(dao.observeServers(), dao.observeTools()) { servers, tools ->
            val toolsByServer = tools.groupBy(McpToolEntity::serverId)
            servers.map { server ->
                McpServerCatalogEntry(
                    server = server.toDomain(),
                    tools = toolsByServer[server.serverId].orEmpty().map(McpToolEntity::toDomain)
                )
            }
        }

    override suspend fun loadServers(): List<McpServerConfig> =
        dao.loadServers().map(McpServerEntity::toDomain)

    override suspend fun loadServer(serverId: String): McpServerConfig? =
        dao.loadServer(serverId)?.toDomain()

    override suspend fun createServer(
        displayName: String,
        url: String,
        transport: McpTransport,
        authToken: String?,
        extraHeaders: Map<String, String>,
        allowPrivateNetwork: Boolean
    ): McpServerConfig {
        val config = McpServerConfig(
            serverId = UUID.randomUUID().toString(),
            alias = uniqueAlias(displayName),
            displayName = displayName.trim().ifBlank { DEFAULT_DISPLAY_NAME },
            url = url.trim(),
            transport = transport,
            authToken = authToken?.takeIf { token -> token.isNotBlank() },
            extraHeaders = extraHeaders,
            // A brand new server starts disabled: its tools are write-capable by policy, so exposing
            // them may not be a side effect of pasting a URL.
            enabled = false,
            allowPrivateNetwork = allowPrivateNetwork
        )
        dao.upsertServer(config.toEntity())
        return config
    }

    override suspend fun updateServer(config: McpServerConfig) {
        val existing = dao.loadServer(config.serverId) ?: return
        dao.upsertServer(
            config.copy(
                // Renaming a server must not rename its tools, which would orphan every enablement
                // override and approval binding keyed by the namespaced name.
                alias = existing.alias,
                createdAt = existing.createdAt,
                updatedAt = System.currentTimeMillis()
            ).toEntity()
        )
    }

    override suspend fun deleteServer(serverId: String) {
        // mcp_tools cascades on the foreign key, so the cached catalog goes with it.
        dao.deleteServer(serverId)
    }

    override suspend fun setServerEnabled(serverId: String, enabled: Boolean) {
        dao.setServerEnabled(serverId, enabled)
    }

    override suspend fun loadTools(serverId: String): List<McpToolDescriptor> =
        dao.loadTools(serverId).map(McpToolEntity::toDomain)

    override suspend fun loadInstallableTools(): List<McpToolDescriptor> =
        dao.loadInstallableTools().map(McpToolEntity::toDomain)

    override suspend fun replaceTools(
        serverId: String,
        tools: List<McpToolDescriptor>,
        syncedAt: Long
    ) {
        dao.replaceTools(serverId, tools.map { tool -> tool.toEntity(syncedAt) })
        dao.recordSyncResult(serverId = serverId, syncedAt = syncedAt, error = null)
    }

    override suspend fun recordSyncFailure(serverId: String, error: String) {
        // The previous catalog is intentionally left in place: a transient network failure should not
        // silently strip tools the user already enabled.
        dao.recordSyncResult(
            serverId = serverId,
            syncedAt = dao.loadServer(serverId)?.lastSyncedAt,
            error = error.take(MAX_SYNC_ERROR_CHARS)
        )
    }

    private suspend fun uniqueAlias(displayName: String): String {
        val taken = dao.loadAliases().toSet()
        val base = displayName.aliasBase()
        if (base !in taken) return base
        // Suffix rather than fail: the alias is an internal disambiguator, not something to make the
        // user negotiate while adding a server.
        var suffix = 2
        while ("$base$suffix" in taken) suffix += 1
        return "$base$suffix"
    }

    private fun String.aliasBase(): String = lowercase()
        .map { character ->
            if (character in 'a'..'z' || character in '0'..'9') character else '_'
        }
        .joinToString(separator = "")
        .replace(UNDERSCORE_RUN, "_")
        .trim('_')
        .take(MAX_ALIAS_CHARS)
        .trim('_')
        .ifBlank { DEFAULT_ALIAS }

    private companion object {
        private const val DEFAULT_ALIAS = "server"
        private const val DEFAULT_DISPLAY_NAME = "MCP server"
        private const val MAX_ALIAS_CHARS = 14
        private const val MAX_SYNC_ERROR_CHARS = 300
        private val UNDERSCORE_RUN = Regex("_+")
    }
}

private val headerJson = Json { ignoreUnknownKeys = true }

private fun McpServerEntity.toDomain(): McpServerConfig = McpServerConfig(
    serverId = serverId,
    alias = alias,
    displayName = displayName,
    url = url,
    transport = runCatching { McpTransport.valueOf(transport) }.getOrDefault(McpTransport.STREAMABLE_HTTP),
    authToken = authToken,
    extraHeaders = runCatching {
        headerJson.decodeFromString<Map<String, String>>(extraHeadersJson)
    }.getOrDefault(emptyMap()),
    enabled = enabled,
    allowPrivateNetwork = allowPrivateNetwork,
    lastSyncedAt = lastSyncedAt,
    lastSyncError = lastSyncError,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun McpServerConfig.toEntity(): McpServerEntity = McpServerEntity(
    serverId = serverId,
    alias = alias,
    displayName = displayName,
    url = url,
    transport = transport.name,
    authToken = authToken,
    extraHeadersJson = headerJson.encodeToString(extraHeaders),
    enabled = enabled,
    allowPrivateNetwork = allowPrivateNetwork,
    lastSyncedAt = lastSyncedAt,
    lastSyncError = lastSyncError,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun McpToolEntity.toDomain(): McpToolDescriptor = McpToolDescriptor(
    serverId = serverId,
    toolName = toolName,
    namespacedName = namespacedName,
    description = description,
    inputSchemaJson = inputSchemaJson,
    annotations = McpToolAnnotations(
        title = annotationTitle,
        readOnlyHint = readOnlyHint,
        destructiveHint = destructiveHint,
        idempotentHint = idempotentHint,
        openWorldHint = openWorldHint
    ),
    rejectionCode = rejectionCode,
    rejectionPath = rejectionPath,
    syncedAt = syncedAt
)

private fun McpToolDescriptor.toEntity(syncedAt: Long): McpToolEntity = McpToolEntity(
    serverId = serverId,
    toolName = toolName,
    namespacedName = namespacedName,
    description = description,
    inputSchemaJson = inputSchemaJson,
    annotationTitle = annotations.title,
    readOnlyHint = annotations.readOnlyHint,
    destructiveHint = annotations.destructiveHint,
    idempotentHint = annotations.idempotentHint,
    openWorldHint = annotations.openWorldHint,
    rejectionCode = rejectionCode,
    rejectionPath = rejectionPath,
    syncedAt = syncedAt
)
