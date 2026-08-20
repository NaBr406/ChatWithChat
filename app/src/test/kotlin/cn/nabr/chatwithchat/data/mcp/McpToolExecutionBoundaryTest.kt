package cn.nabr.chatwithchat.data.mcp

import cn.nabr.chatwithchat.data.tool.ToolApprovalAuthority
import cn.nabr.chatwithchat.data.tool.ToolApprovalCoordinator
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolExecutionContextFactory
import cn.nabr.chatwithchat.data.tool.ToolExecutor
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolProvider
import cn.nabr.chatwithchat.data.tool.ToolRegistry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The MCP provider driven through the real [ToolExecutor], which is where enablement, approval, and result
 * bounds are actually enforced. Testing the provider alone would prove none of it.
 */
class McpToolExecutionBoundaryTest {

    @Test
    fun callWithoutApproval_isRefusedAndNeverReachesTheServer() = runBlocking {
        val invoker = RecordingInvoker(CallToolResult(listOf(TextContent("done")), false))
        val executor = executorFor(invoker)

        val result = executor.execute(call(), activeToolNames = setOf(TOOL_NAME))

        assertTrue(result.isError)
        assertEquals("tool_approval_required", result.metadata["error_code"])
        assertFalse("an unapproved MCP call must not touch the server", invoker.wasInvoked)
    }

    @Test
    fun callNotInTheActiveSet_isRefusedEvenThoughItIsRegistered() = runBlocking {
        val invoker = RecordingInvoker(CallToolResult(listOf(TextContent("done")), false))
        val executor = executorFor(invoker)

        // This is what keeps a disabled MCP tool unavailable even if a model forges its name.
        val result = executor.execute(call(), activeToolNames = emptySet())

        assertTrue(result.isError)
        assertEquals("tool_unavailable", result.metadata["error_code"])
        assertFalse(invoker.wasInvoked)
    }

    @Test
    fun approvedCall_executesAndBoundsAnOversizedRemoteResponse() = runBlocking {
        val huge = "x".repeat(50_000)
        val invoker = RecordingInvoker(CallToolResult(listOf(TextContent(huge)), false))
        val harness = harnessFor(invoker)
        val executor = harness.executor
        val coordinator = harness.coordinator
        val call = call()

        val request = coordinator.prepare(call).getOrThrow()
        val context = coordinator.approve(call, request).getOrThrow()
        val result = executor.execute(
            call = call,
            activeToolNames = setOf(TOOL_NAME),
            executionContext = context
        )

        assertFalse(result.content, result.isError)
        assertTrue(invoker.wasInvoked)
        // ToolPolicy.maxResultChars on the provider caps a server that returns an unbounded payload. The
        // assertion is that the cap bit and said so — not a copy of its value, which would only ever go
        // stale the next time a real server tells us the limit was set too low.
        assertTrue(
            "expected the payload to be bounded below ${huge.length} but got ${result.content.length}",
            result.content.length < huge.length
        )
        assertTrue(
            "a clipped result must say it was clipped, or the model retries it forever",
            result.content.contains("truncated")
        )
    }

    @Test
    fun deniedCall_isRefusedWithARecoverableCode() = runBlocking {
        val invoker = RecordingInvoker(CallToolResult(listOf(TextContent("done")), false))
        val harness = harnessFor(invoker)
        val executor = harness.executor
        val coordinator = harness.coordinator
        val call = call()

        val request = coordinator.prepare(call).getOrThrow()
        val context = coordinator.deny(call, request).getOrThrow()
        val result = executor.execute(
            call = call,
            activeToolNames = setOf(TOOL_NAME),
            executionContext = context
        )

        assertTrue(result.isError)
        assertEquals("tool_approval_denied", result.metadata["error_code"])
        assertFalse(invoker.wasInvoked)
    }

    @Test
    fun approvalForOneCall_cannotAuthorizeDifferentArguments() = runBlocking {
        val invoker = RecordingInvoker(CallToolResult(listOf(TextContent("done")), false))
        val coordinator = harnessFor(invoker).coordinator

        val approvedCall = call("""{"path":"/tmp/safe.txt"}""")
        val request = coordinator.prepare(approvedCall).getOrThrow()

        val tamperedCall = call("""{"path":"/etc/shadow"}""")
        val reused = coordinator.approve(tamperedCall, request)

        assertTrue("approval must be bound to the exact arguments", reused.isFailure)
    }

    private fun executorFor(invoker: McpToolInvoker) = harnessFor(invoker).executor

    private fun registryFor(invoker: McpToolInvoker) = ToolRegistry(emptyList<ToolProvider>()).apply {
        installDynamicProviders(listOf(providerFor(invoker))).getOrThrow()
    }

    /**
     * The executor and the coordinator must share one [ToolApprovalAuthority], exactly as `ToolModule`
     * provides a single instance to both. Each authority mints its own signing key, so two instances
     * would make every approval token fail verification — which is the correct behaviour, and the reason
     * this harness exists instead of constructing them independently.
     */
    private class ApprovalHarness(
        val registry: ToolRegistry,
        val executor: ToolExecutor,
        val coordinator: ToolApprovalCoordinator
    )

    private fun harnessFor(invoker: McpToolInvoker): ApprovalHarness {
        val registry = registryFor(invoker)
        val authority = ToolApprovalAuthority()
        val contextFactory = ToolExecutionContextFactory()
        return ApprovalHarness(
            registry = registry,
            executor = ToolExecutor(
                toolRegistry = registry,
                approvalAuthority = authority,
                executionContextFactory = contextFactory
            ),
            coordinator = ToolApprovalCoordinator(
                toolRegistry = registry,
                approvalAuthority = authority,
                executionContextFactory = contextFactory
            )
        )
    }

    private fun providerFor(invoker: McpToolInvoker): ToolProvider {
        val descriptor = McpToolDescriptor(
            serverId = SERVER_ID,
            toolName = "read_file",
            namespacedName = TOOL_NAME,
            description = "Reads a file.",
            inputSchemaJson = SCHEMA_JSON
        )
        val parameters = (
            McpSchemaTranslator.translate(
                properties = Json.parseToJsonElement(SCHEMA_JSON).jsonObject["properties"] as? JsonObject,
                required = emptyList()
            ) as McpSchemaTranslation.Accepted
            ).parameters
        return McpToolProvider(
            server = McpServerConfig(
                serverId = SERVER_ID,
                alias = "acme",
                displayName = "Acme MCP",
                url = "https://mcp.example.invalid/mcp",
                enabled = true
            ),
            descriptor = descriptor,
            parameters = parameters,
            invoker = invoker
        )
    }

    private fun call(arguments: String = """{"path":"/tmp/report.txt"}""") =
        ToolCall(id = "call_1", name = TOOL_NAME, arguments = arguments)

    private class RecordingInvoker(private val result: CallToolResult) : McpToolInvoker {
        var wasInvoked = false
            private set

        override suspend fun callTool(
            server: McpServerConfig,
            toolName: String,
            arguments: Map<String, Any?>
        ): CallToolResult {
            wasInvoked = true
            return result
        }
    }

    private companion object {
        private const val SERVER_ID = "server-1"
        private const val TOOL_NAME = "mcp_acme_read_file"
        private const val SCHEMA_JSON = """{"type":"object","properties":{"path":{"type":"string"}}}"""
    }
}
