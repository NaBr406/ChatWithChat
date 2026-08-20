package cn.nabr.chatwithchat.data.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Gate for the MCP dependency: proves `io.modelcontextprotocol:kotlin-sdk-client` actually loads and
 * runs under ART, not just that it resolves on the JVM classpath.
 *
 * The SDK is pre-1.0 and never claims Android support, and the version is pinned to 0.11.1 to keep
 * kotlinx-serialization on this project's 1.10.0 (see `gradle/libs.versions.toml`). This test is what
 * catches a bad bump: dexing, R8 stripping of the reflective `@Serializable` machinery, and the
 * `kotlin-logging` Android variant all fail here rather than at the first real MCP request.
 *
 * Deliberately offline. Networked MCP behaviour belongs in the connection-manager tests.
 */
@RunWith(AndroidJUnit4::class)
class McpSdkBuildCanaryInstrumentedTest {

    @Test
    fun sdkClient_completesHandshakeThenListsAndCallsToolOverLoopbackTransport() = runBlocking {
        val transport = LoopbackMcpTransport(::respondAsCanaryServer)
        val client = Client(
            Implementation("ChatWithChat", "canary"),
            ClientOptions()
        )

        try {
            client.connect(transport)

            assertEquals("mcp-canary-server", client.serverVersion?.name)
            assertNotNull("tools capability should survive the handshake", client.serverCapabilities?.tools)

            val tools = client.listTools().tools
            assertEquals(listOf(ECHO_TOOL_NAME), tools.map(Tool::name))

            // The Phase 3 schema translator reads `inputSchema.properties` as raw JSON Schema, so the
            // canary pins that the per-property JsonObject survives the round trip untouched.
            // `properties` is nullable on ToolSchema: a parameterless MCP tool omits it entirely.
            val echoSchema = tools.single().inputSchema
            assertEquals(listOf("text"), echoSchema.required)
            assertEquals(
                "string",
                echoSchema.properties?.get("text")?.jsonObject?.get("type")?.jsonPrimitive?.content
            )

            val result = client.callTool(ECHO_TOOL_NAME, mapOf("text" to "canary"))
            assertEquals(false, result?.isError)
            assertEquals("canary", (result?.content?.single() as TextContent).text)
        } finally {
            client.close()
            transport.dispose()
        }
    }

    /**
     * The Phase 2 connection manager builds this transport over the app's existing Ktor CIO engine and
     * injects per-server credentials through the request-builder lambda. Constructing it here keeps that
     * seam compiling against whatever SDK version is pinned; no request is issued.
     */
    @Test
    fun streamableHttpTransport_buildsOverProjectKtorEngineWithInjectedAuthHeader() {
        HttpClient(CIO) { install(SSE) }.use { httpClient ->
            val transport = httpClient.mcpStreamableHttpTransport(
                url = "https://mcp.invalid/mcp"
            ) {
                header("Authorization", "Bearer canary-token")
            }

            assertNull("no session may exist before the first request", transport.sessionId)
            assertTrue(transport is AbstractTransport)
        }
    }

    private fun respondAsCanaryServer(request: JSONRPCRequest): RequestResult = when (request.method) {
        "initialize" -> InitializeResult(
            LATEST_PROTOCOL_VERSION,
            ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
            Implementation("mcp-canary-server", "1.0")
        )

        "tools/list" -> ListToolsResult(
            listOf(
                Tool(
                    name = ECHO_TOOL_NAME,
                    inputSchema = ToolSchema(
                        properties = buildJsonObject {
                            put(
                                "text",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Text echoed back verbatim."))
                                }
                            )
                        },
                        required = listOf("text")
                    ),
                    description = "Echoes the supplied text."
                )
            ),
            null
        )

        "tools/call" -> CallToolResult(
            listOf(TextContent(request.echoedText())),
            false
        )

        else -> throw IllegalStateException("canary server does not implement ${request.method}")
    }

    private fun JSONRPCRequest.echoedText(): String = params
        ?.jsonObject
        ?.get("arguments")
        ?.jsonObject
        ?.get("text")
        ?.jsonPrimitive
        ?.content
        .orEmpty()

    private companion object {
        private const val ECHO_TOOL_NAME = "echo"
    }
}

/**
 * Minimum viable MCP server: answers every request in-process so the canary exercises the SDK's
 * JSON-RPC framing, serializers, and coroutine plumbing without a socket.
 *
 * Replies are dispatched on a separate coroutine because [Client] awaits the response while still
 * suspended inside [send]; completing it inline would deadlock the handshake.
 */
private class LoopbackMcpTransport(
    private val handle: (JSONRPCRequest) -> RequestResult
) : AbstractTransport() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override suspend fun start() = Unit

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        // Notifications (initialized, cancelled, …) carry no id and expect no reply.
        val request = message as? JSONRPCRequest ?: return
        scope.launch {
            _onMessage(JSONRPCResponse(request.id, handle(request)))
        }
    }

    override suspend fun close() = Unit

    fun dispose() {
        scope.cancel()
    }
}
