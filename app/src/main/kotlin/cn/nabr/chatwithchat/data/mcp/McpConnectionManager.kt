package cn.nabr.chatwithchat.data.mcp

import cn.nabr.chatwithchat.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Distinguishes "the server is unreachable" from "the server answered with an error". */
class McpConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Owns one MCP session per configured server.
 *
 * MCP sessions are stateful — the server issues a session id during `initialize` — so the [Client] is
 * cached rather than rebuilt per request, and dropped once idle so a backgrounded app is not holding
 * sockets open indefinitely.
 *
 * Access is serialized per server. That costs nothing today because `ToolExecutor.executeAll` already
 * runs a round's calls sequentially, and it removes any question about two coroutines racing to connect
 * the same server or sharing one session mid-handshake.
 *
 * [StreamableHttpClientTransport] is tried first as the current spec transport, falling back to
 * [SseClientTransport] because a large share of deployed servers still only offer the older one.
 */
@Singleton
class McpConnectionManager @Inject constructor(
    private val httpClient: HttpClient,
    private val urlPolicy: McpServerUrlPolicy,
    private val clock: () -> Long = System::currentTimeMillis
) : McpToolInvoker {
    private class Session(
        val client: Client,
        val transport: McpTransport,
        val configFingerprint: String,
        var lastUsedAt: Long
    )

    /** Guards [sessions] and [locks]. Never held while acquiring a per-server lock. */
    private val stateGuard = Mutex()
    private val sessions = mutableMapOf<String, Session>()
    private val locks = mutableMapOf<String, Mutex>()

    /**
     * Runs [block] against a live session for [config], connecting first if needed.
     *
     * A failure inside [block] tears the session down: a half-broken stateful session is worth less than
     * a clean reconnect on the next call.
     */
    suspend fun <T> withSession(
        config: McpServerConfig,
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        block: suspend (Client) -> T
    ): T = lockFor(config.serverId).withLock {
        val session = obtainSession(config, connectTimeoutMillis)
        try {
            block(session.client).also { session.lastUsedAt = clock() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            closeSession(config.serverId)
            throw failure
        }
    }

    override suspend fun callTool(
        server: McpServerConfig,
        toolName: String,
        arguments: Map<String, Any?>
    ): CallToolResult? = withSession(server) { client -> client.callTool(toolName, arguments) }

    /** Reports whether a server is reachable and speaks MCP, without caching anything long-term. */
    suspend fun probe(config: McpServerConfig): McpProbeResult = try {
        withSession(config) { client ->
            McpProbeResult.Reachable(
                serverName = client.serverVersion?.name.orEmpty(),
                serverVersion = client.serverVersion?.version.orEmpty(),
                transport = sessionTransport(config.serverId),
                advertisesTools = client.serverCapabilities?.tools != null
            )
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        McpProbeResult.Unreachable(failure.reasonCode())
    }

    suspend fun disconnect(serverId: String) {
        lockFor(serverId).withLock { closeSession(serverId) }
    }

    /** Drops sessions unused for longer than [idleTimeoutMillis]. Safe to call periodically. */
    suspend fun evictIdleSessions(idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS) {
        val now = clock()
        val staleIds = stateGuard.withLock {
            sessions.filterValues { session -> now - session.lastUsedAt >= idleTimeoutMillis }.keys.toList()
        }
        staleIds.forEach { serverId -> disconnect(serverId) }
    }

    private suspend fun obtainSession(config: McpServerConfig, connectTimeoutMillis: Long): Session {
        val fingerprint = config.connectionFingerprint()
        val cached = stateGuard.withLock { sessions[config.serverId] }
        if (cached != null) {
            // Editing the URL, transport, or credentials must not keep reusing the old session.
            if (cached.configFingerprint == fingerprint) return cached
            closeSession(config.serverId)
        }

        urlPolicy.validate(config.url, config.allowPrivateNetwork)

        val fallback = when (config.transport) {
            McpTransport.STREAMABLE_HTTP -> McpTransport.SSE
            McpTransport.SSE -> null
        }
        val session = connect(config, config.transport, connectTimeoutMillis, fingerprint)
            ?: fallback?.let { transport -> connect(config, transport, connectTimeoutMillis, fingerprint) }
            ?: throw McpConnectionException("mcp_connect_failed")

        stateGuard.withLock { sessions[config.serverId] = session }
        return session
    }

    private suspend fun connect(
        config: McpServerConfig,
        transport: McpTransport,
        connectTimeoutMillis: Long,
        fingerprint: String
    ): Session? {
        val client = Client(Implementation(CLIENT_NAME, BuildConfig.VERSION_NAME), ClientOptions())
        return try {
            withTimeout(connectTimeoutMillis) { client.connect(config.newTransport(transport)) }
            Session(client, transport, fingerprint, clock())
        } catch (cancellation: CancellationException) {
            runCatching { client.close() }
            throw cancellation
        } catch (_: Throwable) {
            // Swallowed so the caller can try the fallback transport. An exhausted chain surfaces as
            // McpConnectionException from obtainSession, so a real failure is never lost.
            runCatching { client.close() }
            null
        }
    }

    private suspend fun closeSession(serverId: String) {
        val session = stateGuard.withLock { sessions.remove(serverId) } ?: return
        // Best effort: a server that already dropped the connection must not turn teardown into the
        // error the caller sees instead of the real one.
        runCatching { session.client.close() }
    }

    private suspend fun sessionTransport(serverId: String): McpTransport? =
        stateGuard.withLock { sessions[serverId]?.transport }

    private fun McpServerConfig.newTransport(transport: McpTransport): Transport {
        val applyHeaders: HttpRequestBuilder.() -> Unit = {
            authToken?.takeIf { token -> token.isNotBlank() }?.let { token ->
                header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$token")
            }
            extraHeaders.forEach { (name, value) ->
                // A custom header must not be able to overwrite the bearer credential.
                if (name.isNotBlank() && !name.equals(AUTHORIZATION_HEADER, ignoreCase = true)) {
                    header(name, value)
                }
            }
        }
        return when (transport) {
            McpTransport.STREAMABLE_HTTP -> StreamableHttpClientTransport(
                client = httpClient,
                url = url,
                requestBuilder = applyHeaders
            )
            McpTransport.SSE -> SseClientTransport(
                client = httpClient,
                urlString = url,
                requestBuilder = applyHeaders
            )
        }
    }

    /** Everything that would invalidate a live session if the user changed it. */
    private fun McpServerConfig.connectionFingerprint(): String = listOf(
        url,
        transport.name,
        authToken.orEmpty(),
        allowPrivateNetwork.toString(),
        extraHeaders.entries
            .sortedBy { entry -> entry.key }
            .joinToString { entry -> "${entry.key}=${entry.value}" }
    ).joinToString(separator = " ")

    private suspend fun lockFor(serverId: String): Mutex = stateGuard.withLock {
        locks.getOrPut(serverId) { Mutex() }
    }

    private companion object {
        private const val CLIENT_NAME = "ChatWithChat"
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val DEFAULT_IDLE_TIMEOUT_MILLIS = 5 * 60_000L
    }
}

sealed interface McpProbeResult {
    data class Reachable(
        val serverName: String,
        val serverVersion: String,
        val transport: McpTransport?,
        val advertisesTools: Boolean
    ) : McpProbeResult

    data class Unreachable(val reasonCode: String) : McpProbeResult
}

/** Maps a failure onto a stable code, keeping server-supplied text out of persisted state. */
internal fun Throwable.reasonCode(): String = when (this) {
    is McpUrlRejectedException -> rejection.code
    is McpConnectionException -> message ?: "mcp_connect_failed"
    else -> "mcp_request_failed"
}
