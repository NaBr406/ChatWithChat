package cn.nabr.chatwithchat.data.mcp

import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolProvider
import cn.nabr.chatwithchat.data.tool.ToolRegistry
import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.ToolSecurityPolicy
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The translator run against `inputSchema` payloads captured verbatim from live public MCP servers.
 *
 * Hand-written schemas prove the rules; these prove the rules meet reality. Each fixture is a real
 * response, so a regression here means an actual server would break rather than a contrived edge case.
 * Captured 2026-08-20 from DeepWiki, Cloudflare docs, and CoinGecko.
 */
class McpRealWorldSchemaTest {

    @Test
    fun deepWiki_anyOfOnARequiredProperty_rejectsTheWholeTool() {
        // `repoName` accepts either a string or an array of strings. That is a shape decision, and it is
        // required, so quietly narrowing it would make the app send calls the server rejects.
        val result = translate(DEEPWIKI_ASK_QUESTION)

        val rejection = result as McpSchemaTranslation.Rejected
        assertEquals(McpSchemaRejectionReason.UNSUPPORTED_COMPOSITION, rejection.reason)
        assertEquals("$.properties.repoName.anyOf", rejection.path)
    }

    @Test
    fun deepWiki_plainStringTools_areAccepted() {
        listOf(DEEPWIKI_READ_WIKI_CONTENTS, DEEPWIKI_READ_WIKI_STRUCTURE).forEach { schema ->
            val parameters = accept(schema)
            assertEquals(listOf("repoName"), parameters.required)
            assertEquals("string", parameters.properties.getValue("repoName").type)
            assertTrue(parameters.properties.getValue("repoName").description!!.isNotBlank())
        }
    }

    @Test
    fun cloudflareDocs_draft2020SchemaKeyword_isHarmless() {
        // A root `$schema` is not a `$ref`, so it must not trip the reference guard.
        val parameters = accept(CLOUDFLARE_SEARCH_DOCS)
        assertEquals(setOf("query"), parameters.properties.keys)
        assertEquals(listOf("query"), parameters.required)
    }

    @Test
    fun cloudflareDocs_parameterlessTool_isAccepted() {
        val parameters = accept(CLOUDFLARE_MIGRATE_GUIDE)
        assertEquals(emptyMap<String, ToolDefinition.Parameter>(), parameters.properties)
        assertEquals(emptyList<String>(), parameters.required)
    }

    @Test
    fun coinGecko_stringEnumsSurviveTranslation() {
        val parameters = accept(COINGECKO_SEARCH_DOCS)

        assertEquals(listOf("query", "language"), parameters.required)
        assertEquals(
            listOf("http", "python", "go", "typescript", "javascript", "terraform", "ruby", "java", "kotlin"),
            parameters.properties.getValue("language").enumValues
        )
        assertEquals(listOf("default", "verbose"), parameters.properties.getValue("detail").enumValues)
        // An optional property stays optional rather than being promoted or dropped.
        assertTrue("detail" !in parameters.required)
    }

    @Test
    fun everyAcceptedRealSchema_installsIntoTheRegistry() {
        val accepted = ALL_FIXTURES
            .mapValues { (_, schema) -> translate(schema) }
            .filterValues { translation -> translation is McpSchemaTranslation.Accepted }

        // One real tool is refused (DeepWiki's ask_question); the rest must be installable together.
        assertEquals(ALL_FIXTURES.size - 1, accepted.size)

        val registry = ToolRegistry(emptyList<ToolProvider>())
        val install = registry.installDynamicProviders(
            accepted.map { (name, translation) ->
                FixtureProvider(
                    ToolDefinition(
                        name = McpToolNamespace.namespacedName("live", "server-1", name),
                        description = "Captured from a live MCP server.",
                        parameters = (translation as McpSchemaTranslation.Accepted).parameters
                    )
                )
            }
        )

        assertTrue(install.exceptionOrNull()?.toString().orEmpty(), install.isSuccess)
        assertEquals(accepted.size, registry.definitions.size)
    }

    @Test
    fun namespacedNamesForRealToolsStayReadable() {
        assertEquals(
            "mcp_deepwiki_read_wiki_structure",
            McpToolNamespace.namespacedName("deepwiki", "server-1", "read_wiki_structure")
        )
        assertEquals(
            "mcp_cfdocs_search_cloudflare_documentation",
            McpToolNamespace.namespacedName("cfdocs", "server-2", "search_cloudflare_documentation")
        )
    }

    @Test
    fun realToolArgumentsProduceAReadableApprovalSummary() {
        val parameters = accept(DEEPWIKI_READ_WIKI_STRUCTURE)
        val provider = McpToolProvider(
            server = McpServerConfig(
                serverId = "server-1",
                alias = "deepwiki",
                displayName = "DeepWiki",
                url = "https://mcp.deepwiki.com/mcp",
                enabled = true
            ),
            descriptor = McpToolDescriptor(
                serverId = "server-1",
                toolName = "read_wiki_structure",
                namespacedName = "mcp_deepwiki_read_wiki_structure",
                description = "Get a list of documentation topics for a GitHub repository.",
                inputSchemaJson = DEEPWIKI_READ_WIKI_STRUCTURE
            ),
            parameters = parameters,
            invoker = UnusedInvoker
        )

        val summary = provider.approvalArgumentSummary(
            ToolCall(
                id = "call_1",
                name = "mcp_deepwiki_read_wiki_structure",
                arguments = """{"repoName":"facebook/react"}"""
            )
        )

        // This is the exact line the on-device approval dialog renders.
        assertEquals("DeepWiki · read_wiki_structure — repoName: facebook/react", summary)
    }

    @Test
    fun orderingServerWithIntegerEnums_isAcceptedAfterEnumBecameNarrowing() {
        // Shape of the tools an ordering server exposes, which an earlier build refused wholesale because
        // every one of them used an integer enum. Integer enums are common in generated API schemas.
        val parameters = accept(ORDERING_CALCULATE_PRICE)

        assertEquals(listOf("storeCode", "productList"), parameters.required)
        // The integer enum is dropped, not fatal: the type and therefore the shape is unchanged.
        val orderType = parameters.properties.getValue("orderType")
        assertEquals("integer", orderType.type)
        assertEquals(emptyList<String>(), orderType.enumValues)
        // A string enum alongside it still survives intact.
        assertEquals(listOf("CNY", "USD"), parameters.properties.getValue("currency").enumValues)
        // Nested array-of-object arguments come through so a basket can actually be described.
        assertEquals("array", parameters.properties.getValue("productList").type)
        assertEquals("object", parameters.properties.getValue("productList").items?.type)
    }

    private fun accept(schemaJson: String): ToolDefinition.Parameters {
        val result = translate(schemaJson)
        assertTrue("expected acceptance but got $result", result is McpSchemaTranslation.Accepted)
        return (result as McpSchemaTranslation.Accepted).parameters
    }

    /** Mirrors how `McpToolSync` feeds the SDK's typed `ToolSchema` fields into the translator. */
    private fun translate(schemaJson: String): McpSchemaTranslation {
        val schema = Json.parseToJsonElement(schemaJson).jsonObject
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { entry -> (entry as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .orEmpty()
        return McpSchemaTranslator.translate(
            properties = schema["properties"] as? JsonObject,
            required = required,
            rawSchema = schema
        )
    }

    private class FixtureProvider(override val definition: ToolDefinition) : ToolProvider {
        override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.FailClosed

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
            ToolResult(callId = call.id, name = call.name, content = "unused")
    }

    private object UnusedInvoker : McpToolInvoker {
        override suspend fun callTool(
            server: McpServerConfig,
            toolName: String,
            arguments: Map<String, Any?>
        ): CallToolResult? = throw UnsupportedOperationException("not called")
    }

    private companion object {
        private const val DEEPWIKI_ASK_QUESTION = """
        {
          "type": "object",
          "properties": {
            "repoName": {
              "anyOf": [
                { "type": "string" },
                { "items": { "type": "string" }, "type": "array" }
              ],
              "description": "GitHub repository or list of repositories (max 10) in owner/repo format."
            },
            "question": {
              "description": "The question to ask about the repository.",
              "type": "string"
            }
          },
          "required": ["repoName", "question"]
        }
        """

        private const val DEEPWIKI_READ_WIKI_CONTENTS = """
        {
          "type": "object",
          "properties": {
            "repoName": {
              "description": "GitHub repository in owner/repo format (e.g. \"facebook/react\").",
              "type": "string"
            }
          },
          "required": ["repoName"]
        }
        """

        private const val DEEPWIKI_READ_WIKI_STRUCTURE = DEEPWIKI_READ_WIKI_CONTENTS

        private const val CLOUDFLARE_SEARCH_DOCS = """
        {
          "type": "object",
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "properties": { "query": { "type": "string" } },
          "required": ["query"]
        }
        """

        private const val CLOUDFLARE_MIGRATE_GUIDE = """
        {
          "type": "object",
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "properties": {}
        }
        """

        private const val COINGECKO_SEARCH_DOCS = """
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "The query to search for." },
            "language": {
              "type": "string",
              "description": "The language for the SDK to search for.",
              "enum": ["http","python","go","typescript","javascript","terraform","ruby","java","kotlin"]
            },
            "detail": {
              "type": "string",
              "description": "The amount of detail to return.",
              "enum": ["default","verbose"]
            }
          },
          "required": ["query","language"]
        }
        """

        private const val COINGECKO_EXECUTE = """
        {
          "type": "object",
          "properties": {
            "code": { "type": "string", "description": "Code to execute." },
            "intent": { "type": "string", "description": "Task you are trying to perform." }
          },
          "required": ["code"]
        }
        """

        /** Integer enums plus a nested basket, the pattern that blocked a real ordering server. */
        private const val ORDERING_CALCULATE_PRICE = """
        {
          "type": "object",
          "properties": {
            "storeCode": { "type": "string", "description": "门店编码" },
            "orderType": { "type": "integer", "description": "订单类型", "enum": [1, 2, 3] },
            "currency": { "type": "string", "enum": ["CNY", "USD"] },
            "productList": {
              "type": "array",
              "description": "商品列表",
              "items": {
                "type": "object",
                "properties": {
                  "spuId": { "type": "string" },
                  "quantity": { "type": "integer", "minimum": 1 }
                },
                "required": ["spuId", "quantity"]
              }
            }
          },
          "required": ["storeCode", "productList"]
        }
        """

        private val ALL_FIXTURES = mapOf(
            "ask_question" to DEEPWIKI_ASK_QUESTION,
            "read_wiki_contents" to DEEPWIKI_READ_WIKI_CONTENTS,
            "read_wiki_structure" to DEEPWIKI_READ_WIKI_STRUCTURE,
            "search_cloudflare_documentation" to CLOUDFLARE_SEARCH_DOCS,
            "migrate_pages_to_workers_guide" to CLOUDFLARE_MIGRATE_GUIDE,
            "search_docs" to COINGECKO_SEARCH_DOCS,
            "execute" to COINGECKO_EXECUTE
        )
    }
}
