package cn.nabr.chatwithchat.di

import cn.nabr.chatwithchat.data.database.dao.McpDao
import cn.nabr.chatwithchat.data.mcp.McpConnectionManager
import cn.nabr.chatwithchat.data.mcp.McpServerRepository
import cn.nabr.chatwithchat.data.mcp.McpServerRepositoryImpl
import cn.nabr.chatwithchat.data.mcp.McpServerUrlPolicy
import cn.nabr.chatwithchat.data.mcp.McpToolRegistrar
import cn.nabr.chatwithchat.data.mcp.McpToolRegistrarImpl
import cn.nabr.chatwithchat.data.mcp.McpToolSync
import cn.nabr.chatwithchat.data.tool.ToolRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class McpHttpClient

@Module
@InstallIn(SingletonComponent::class)
object McpModule {

    /**
     * A dedicated Ktor client rather than the shared [cn.nabr.chatwithchat.data.network.NetworkClient].
     *
     * The shared client installs `ContentNegotiation` with the app's own `Json` and a `DefaultRequest`
     * that pins `Content-Type: application/json` on every call. The MCP SDK's HTTP transports install
     * their own negotiation with `McpJson` and issue SSE `GET`s that must not carry a JSON content type,
     * so they get a clean client. This mirrors how `fetch_url` already runs on its own client for its own
     * reasons.
     */
    @Provides
    @Singleton
    @McpHttpClient
    fun provideMcpHttpClient(): HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = MCP_REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = MCP_CONNECT_TIMEOUT_MILLIS
        }
    }

    @Provides
    @Singleton
    fun provideMcpServerRepository(mcpDao: McpDao): McpServerRepository = McpServerRepositoryImpl(mcpDao)

    @Provides
    @Singleton
    fun provideMcpConnectionManager(
        @McpHttpClient httpClient: HttpClient,
        urlPolicy: McpServerUrlPolicy
    ): McpConnectionManager = McpConnectionManager(httpClient, urlPolicy)

    @Provides
    @Singleton
    fun provideMcpToolRegistrar(
        repository: McpServerRepository,
        connectionManager: McpConnectionManager,
        toolRegistry: ToolRegistry
    ): McpToolRegistrar = McpToolRegistrarImpl(repository, connectionManager, toolRegistry)

    @Provides
    @Singleton
    fun provideMcpToolSync(
        repository: McpServerRepository,
        connectionManager: McpConnectionManager,
        registrar: McpToolRegistrar
    ): McpToolSync = McpToolSync(repository, connectionManager, registrar)

    private const val MCP_REQUEST_TIMEOUT_MILLIS = 60_000L
    private const val MCP_CONNECT_TIMEOUT_MILLIS = 15_000L
}
