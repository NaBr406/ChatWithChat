package cn.nabr.chatwithchat.data.mcp

import cn.nabr.chatwithchat.data.tool.ToolApprovalAuthority
import cn.nabr.chatwithchat.data.tool.ToolApprovalCoordinator
import cn.nabr.chatwithchat.data.tool.ToolApprovalPolicy
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolEffect
import cn.nabr.chatwithchat.data.tool.ToolEnablementOverrides
import cn.nabr.chatwithchat.data.tool.ToolEnablementResolver
import cn.nabr.chatwithchat.data.tool.ToolExecutionContextFactory
import cn.nabr.chatwithchat.data.tool.ToolExposure
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolProvider
import cn.nabr.chatwithchat.data.tool.ToolRegistry
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class McpToolProviderTest {

    // ---- Trust model ----

    @Test
    fun securityPolicy_staysWriteCapableEvenWhenTheServerClaimsReadOnly() {
        val provider = provider(
            annotations = McpToolAnnotations(
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = false
            )
        )

        // The most flattering hints a server can send must not move this boundary an inch.
        assertEquals(ToolEffect.EXTERNAL_WRITE, provider.securityPolicy.effect)
        assertEquals(ToolApprovalPolicy.REQUIRE_EACH_CALL, provider.securityPolicy.approvalPolicy)
    }

    @Test
    fun readOnlyHintedTool_isNotEnabledUntilTheUserSaysSo() {
        val registry = ToolRegistry(emptyList<ToolProvider>()).apply {
            installDynamicProviders(listOf(provider(annotations = McpToolAnnotations(readOnlyHint = true))))
                .getOrThrow()
        }
        val entry = registry.catalogEntryFor(TOOL_NAME)!!
        val resolver = ToolEnablementResolver()

        assertFalse(
            "an MCP tool must never be on by default",
            resolver.isEnabled(entry, ToolEnablementOverrides())
        )
        assertTrue(
            resolver.isEnabled(entry, ToolEnablementOverrides(enabledToolNames = setOf(TOOL_NAME)))
        )
    }

    @Test
    fun discoveryExposure_isResidentSoAServersToolsAreUsableAsASet() {
        // A server is only useful as a set: pricing a basket needs the pricing tool visible at the same
        // time as the menu tool. Overflow past the advertisement budget still falls back to discover_tools.
        assertEquals(ToolExposure.Resident, provider().discoveryMetadata.exposure)
    }

    @Test
    fun discoveryTags_alsoDrawOnTheDescription() {
        val tags = provider(
            toolName = "calc",
            annotations = McpToolAnnotations(title = "Price")
        ).discoveryMetadata.intentTags

        // The name alone is too terse to match anything; description terms give the matcher something.
        assertTrue(tags.contains("calc"))
        assertTrue(tags.contains("reads"))
    }

    @Test
    fun discoveryTags_comeFromTheToolNameAndTitle() {
        val tags = provider(
            toolName = "search.repo_docs",
            annotations = McpToolAnnotations(title = "Search Repo")
        ).discoveryMetadata.intentTags

        assertTrue(tags.containsAll(setOf("search", "repo", "docs")))
    }

    // ---- Approval summary ----

    @Test
    fun approvalArgumentSummary_isAcceptedByTheRealApprovalPipeline() {
        // ToolApprovalPreview rejects raw-JSON summaries outright, so running the actual coordinator is
        // the only assertion that proves the dialog can render this tool.
        val provider = provider()
        val registry = ToolRegistry(emptyList<ToolProvider>()).apply {
            installDynamicProviders(listOf(provider)).getOrThrow()
        }
        val coordinator = ToolApprovalCoordinator(
            toolRegistry = registry,
            approvalAuthority = ToolApprovalAuthority(),
            executionContextFactory = ToolExecutionContextFactory()
        )

        val request = coordinator.prepare(call("""{"path":"/tmp/report.txt","recursive":true}"""))
        assertTrue(request.exceptionOrNull()?.toString().orEmpty(), request.isSuccess)

        val summary = request.getOrThrow().preview.argumentSummary
        assertTrue(summary.contains("path: /tmp/report.txt"))
        assertTrue(summary.contains("recursive: true"))
        assertFalse("a raw JSON summary would have been rejected", summary.trimStart().startsWith("{"))
    }

    @Test
    fun approvalArgumentSummary_boundsArgumentCountAndValueLength() {
        val arguments = buildJsonObject {
            repeat(9) { index -> put("field$index", "v".repeat(400)) }
        }
        val summary = provider().approvalArgumentSummary(call(arguments.toString()))!!

        assertTrue("overflow must be announced, not hidden", summary.contains("+5 more"))
        assertTrue(summary.length <= 600)
    }

    @Test
    fun approvalArgumentSummary_describesAnArgumentlessCall() {
        assertTrue(provider().approvalArgumentSummary(call("{}"))!!.contains("no arguments"))
    }

    // ---- Result mapping ----

    @Test
    fun execute_joinsTextContentAndForwardsStructuredContent() = runBlocking {
        val structured = buildJsonObject { put("total", 2) }
        val provider = provider(
            invoker = invoker(
                CallToolResult(
                    listOf(TextContent("first"), TextContent("second")),
                    false,
                    structured
                )
            )
        )

        val result = provider.execute(call("{}"), ToolLoopConfig.Default)

        assertEquals("first\nsecond", result.content)
        assertFalse(result.isError)
        assertEquals(structured, result.structuredContent)
        assertEquals(SERVER_ALIAS, result.metadata["mcp_server"])
        assertEquals(TOOL_NAME_RAW, result.metadata["mcp_tool"])
    }

    @Test
    fun execute_announcesNonTextBlocksRatherThanDroppingThemSilently() = runBlocking {
        val provider = provider(
            invoker = invoker(
                CallToolResult(
                    listOf(TextContent("here is the chart"), ImageContent("AAAA", "image/png")),
                    false
                )
            )
        )

        val result = provider.execute(call("{}"), ToolLoopConfig.Default)

        assertTrue(result.content.contains("here is the chart"))
        assertTrue("the model must learn the image existed", result.content.contains("1 non-text content"))
        assertEquals("1", result.metadata["mcp_omitted_content_blocks"])
    }

    @Test
    fun execute_forwardsTheTextOfAnEmbeddedResource() = runBlocking {
        // The shape github-mcp-server's get_file_contents actually returns: a one-line note plus the file
        // body as a resource. Filtering to TextContent kept the note and threw away the file.
        val provider = provider(
            invoker = invoker(
                CallToolResult(
                    listOf(
                        TextContent("successfully downloaded text file (SHA: abc123)"),
                        EmbeddedResource(
                            TextResourceContents(
                                text = "plugins { id(\"com.android.application\") }",
                                uri = "repo://NaBr406/ChatWithChat/contents/app/build.gradle.kts",
                                mimeType = "text/plain"
                            )
                        )
                    ),
                    false
                )
            )
        )

        val result = provider.execute(call("{}"), ToolLoopConfig.Default)

        assertTrue(result.content.contains("successfully downloaded text file"))
        assertTrue("the file body is the point of the call", result.content.contains("com.android.application"))
        assertFalse(result.isError)
        assertNull("nothing was lost, so nothing should be announced as lost", result.metadata["mcp_omitted_content_blocks"])
    }

    @Test
    fun execute_labelsEachForwardedResourceWithItsUri() = runBlocking {
        val provider = provider(
            invoker = invoker(
                CallToolResult(
                    listOf(
                        EmbeddedResource(TextResourceContents(text = "alpha", uri = "repo://one.kt")),
                        EmbeddedResource(TextResourceContents(text = "beta", uri = "repo://two.kt"))
                    ),
                    false
                )
            )
        )

        val result = provider.execute(call("{}"), ToolLoopConfig.Default)

        assertTrue(result.content.contains("repo://one.kt"))
        assertTrue(result.content.contains("alpha"))
        assertTrue(result.content.contains("repo://two.kt"))
        assertTrue(result.content.contains("beta"))
        assertTrue(
            "each body must follow its own label",
            result.content.indexOf("alpha") < result.content.indexOf("repo://two.kt")
        )
    }

    @Test
    fun execute_countsABlobResourceAsOmittedRatherThanForwardingBase64() = runBlocking {
        val provider = provider(
            invoker = invoker(
                CallToolResult(
                    listOf(
                        TextContent("successfully downloaded binary file"),
                        EmbeddedResource(
                            BlobResourceContents(
                                blob = "QUFBQUFBQUE=",
                                uri = "repo://app/release.apk",
                                mimeType = "application/vnd.android.package-archive"
                            )
                        )
                    ),
                    false
                )
            )
        )

        val result = provider.execute(call("{}"), ToolLoopConfig.Default)

        assertFalse("base64 bytes would spend the result budget on nothing", result.content.contains("QUFBQUFBQUE="))
        assertTrue(result.content.contains("1 non-text content"))
        assertEquals("1", result.metadata["mcp_omitted_content_blocks"])
    }

    @Test
    fun execute_describesAResourceLinkRatherThanCountingItAsLost() = runBlocking {
        val provider = provider(
            invoker = invoker(
                CallToolResult(
                    listOf(
                        ResourceLink(
                            name = "huge.json",
                            uri = "https://example.test/huge.json",
                            mimeType = "application/json",
                            size = 2_097_152L
                        )
                    ),
                    false
                )
            )
        )

        val result = provider.execute(call("{}"), ToolLoopConfig.Default)

        assertTrue(result.content.contains("https://example.test/huge.json"))
        assertTrue(result.content.contains("2097152"))
        assertNull("the link is the payload, not a casualty", result.metadata["mcp_omitted_content_blocks"])
    }

    @Test
    fun execute_reportsAToolReturningNoTextWithoutLookingEmpty() = runBlocking {
        val provider = provider(invoker = invoker(CallToolResult(emptyList(), false)))

        val result = provider.execute(call("{}"), ToolLoopConfig.Default)

        assertFalse(result.content.isBlank())
        assertFalse(result.isError)
    }

    @Test
    fun execute_propagatesTheServerErrorFlag() = runBlocking {
        val provider = provider(
            invoker = invoker(CallToolResult(listOf(TextContent("permission denied")), true))
        )

        val result = provider.execute(call("{}"), ToolLoopConfig.Default)

        assertTrue(result.isError)
        assertEquals("permission denied", result.content)
    }

    @Test
    fun execute_malformedArguments_returnsRecoverableErrorWithoutCallingTheServer() = runBlocking {
        var invoked = false
        val provider = provider(
            invoker = object : McpToolInvoker {
                override suspend fun callTool(
                    server: McpServerConfig,
                    toolName: String,
                    arguments: Map<String, Any?>
                ): CallToolResult? {
                    invoked = true
                    return null
                }
            }
        )

        val result = provider.execute(call("not json at all"), ToolLoopConfig.Default)

        assertTrue(result.isError)
        assertEquals("mcp_invalid_arguments", result.metadata["error_code"])
        assertFalse("a malformed call must not reach the server", invoked)
    }

    @Test
    fun execute_transportFailure_returnsRecoverableErrorInsteadOfThrowing() = runBlocking {
        val provider = provider(
            invoker = object : McpToolInvoker {
                override suspend fun callTool(
                    server: McpServerConfig,
                    toolName: String,
                    arguments: Map<String, Any?>
                ): CallToolResult? = throw McpConnectionException("mcp_connect_failed")
            }
        )

        val result = provider.execute(call("{}"), ToolLoopConfig.Default)

        assertTrue(result.isError)
        assertEquals("mcp_connect_failed", result.metadata["error_code"])
    }

    @Test
    fun execute_unwrapsJsonArgumentsIntoPlainValuesForTheSdk() = runBlocking {
        var received: Map<String, Any?> = emptyMap()
        val provider = provider(
            invoker = object : McpToolInvoker {
                override suspend fun callTool(
                    server: McpServerConfig,
                    toolName: String,
                    arguments: Map<String, Any?>
                ): CallToolResult {
                    received = arguments
                    return CallToolResult(listOf(TextContent("ok")), false)
                }
            }
        )

        provider.execute(
            call("""{"text":"hi","count":3,"ratio":1.5,"flag":true,"tags":["a"],"nested":{"k":"v"}}"""),
            ToolLoopConfig.Default
        )

        assertEquals("hi", received["text"])
        assertEquals(3L, received["count"])
        assertEquals(1.5, received["ratio"])
        assertEquals(true, received["flag"])
        assertEquals(listOf("a"), received["tags"])
        assertEquals(mapOf("k" to "v"), received["nested"])
    }

    @Test
    fun definition_prefixesTheDescriptionWithTheServerName() {
        assertTrue(provider().definition.description.startsWith("[Acme MCP]"))
    }

    // ---- Helpers ----

    private fun provider(
        toolName: String = TOOL_NAME_RAW,
        annotations: McpToolAnnotations = McpToolAnnotations(),
        invoker: McpToolInvoker = invoker(CallToolResult(listOf(TextContent("ok")), false))
    ): McpToolProvider {
        val descriptor = McpToolDescriptor(
            serverId = SERVER_ID,
            toolName = toolName,
            namespacedName = McpToolNamespace.namespacedName(SERVER_ALIAS, SERVER_ID, toolName),
            description = "Reads a file.",
            inputSchemaJson = SCHEMA_JSON,
            annotations = annotations
        )
        val translation = McpSchemaTranslator.translate(
            properties = schema()["properties"] as? JsonObject,
            required = emptyList()
        ) as McpSchemaTranslation.Accepted
        return McpToolProvider(
            server = SERVER,
            descriptor = descriptor,
            parameters = translation.parameters,
            invoker = invoker
        )
    }

    private fun invoker(result: CallToolResult?): McpToolInvoker = object : McpToolInvoker {
        override suspend fun callTool(
            server: McpServerConfig,
            toolName: String,
            arguments: Map<String, Any?>
        ): CallToolResult? = result
    }

    private fun call(arguments: String) = ToolCall(id = "call_1", name = TOOL_NAME, arguments = arguments)

    private fun schema(): JsonObject = Json.parseToJsonElement(SCHEMA_JSON).jsonObject

    private companion object {
        private const val SERVER_ID = "server-1"
        private const val SERVER_ALIAS = "acme"
        private const val TOOL_NAME_RAW = "read_file"
        private const val TOOL_NAME = "mcp_acme_read_file"
        private const val SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string" },
                "recursive": { "type": "boolean" },
                "text": { "type": "string" },
                "count": { "type": "integer" },
                "ratio": { "type": "number" },
                "flag": { "type": "boolean" },
                "tags": { "type": "array", "items": { "type": "string" } },
                "nested": { "type": "object", "properties": { "k": { "type": "string" } } },
                "field0": { "type": "string" }
              }
            }
        """
        private val SERVER = McpServerConfig(
            serverId = SERVER_ID,
            alias = SERVER_ALIAS,
            displayName = "Acme MCP",
            url = "https://mcp.example.invalid/mcp",
            enabled = true
        )
    }
}
