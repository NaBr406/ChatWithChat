package cn.nabr.chatwithchat.data.mcp

import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolCatalogEntry
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolRegistry
import cn.nabr.chatwithchat.data.tool.ToolScopePlanner
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A realistically large MCP server must stay usable end to end.
 *
 * The fixture is the tool list of a production ordering server (麦当劳 / McDonald's China), captured
 * verbatim from `mcp_tools` on a device on 2026-08-20: 29 tools, Chinese descriptions, deeply nested
 * baskets. It is here because every earlier test used a 2–3 tool server, and the failure this catches only
 * appears at scale — a server whose tools do not all fit the advertisement budget.
 *
 * The property under test is not "all 29 fit". It is that the tools a task actually needs are reachable,
 * and that whatever does not fit is dropped by relevance rather than by alphabet.
 */
class McpLargeServerScopeTest {

    @Test
    fun everyToolInTheFixtureTranslates() {
        val rejected = fixtures().mapNotNull { fixture ->
            (fixture.translate() as? McpSchemaTranslation.Rejected)
                ?.let { rejection -> "${fixture.name}:${rejection.reason.code}@${rejection.path}" }
        }

        // A non-string `enum` used to reject 7 of these outright. Narrowing keywords are dropped, not fatal.
        assertEquals(
            "no tool in a production ordering server should be untranslatable",
            emptyList<String>(),
            rejected
        )
    }

    @Test
    fun theWholeServerInstallsIntoTheRegistry() {
        val registry = ToolRegistry(emptyList())

        val outcome = registry.installDynamicProviders(providers())

        assertTrue("install failed: ${outcome.exceptionOrNull()?.message}", outcome.isSuccess)
        assertEquals(FIXTURE_COUNT, registry.catalog.count { entry -> entry.definition.name.startsWith("mcp_") })
    }

    @Test
    fun theOrderingPathIsAdvertisedToTheModel() {
        val scope = ToolScopePlanner().createScope(catalogEntries(), initialIntent = "帮我点一份巨无霸套餐")

        val advertised = scope.advertisedToolNames
        // Without these four in one scope the model cannot get from "I want a burger" to a placed order:
        // find a store, list its menu, price the basket, submit it.
        val missing = ORDERING_PATH.filterNot { tool -> namespaced(tool) in advertised }

        assertEquals(
            "the ordering path must survive the advertisement budget; advertised=${advertised.size} of $FIXTURE_COUNT",
            emptyList<String>(),
            missing
        )
    }

    @Test
    fun aTruncatedScopeStillLeavesDiscoveryReachable() {
        // Deliberately far too small for 29 tools: the point is that overflow degrades rather than strands.
        val scope = tightPlanner().createScope(catalogEntries(), initialIntent = "点餐")

        assertTrue(
            "an oversized server must keep discover_tools so the remainder stays reachable",
            scope.contains(ToolDefinition.DiscoverTools.name)
        )
    }

    @Test
    fun discoveryFindsStoreToolsFromAChineseQuery() {
        val scope = tightPlanner().createScope(catalogEntries(), initialIntent = "点餐")

        val result = scope.discover(discoverCall("查询附近的门店"))

        // Every Chinese query used to score zero, so discovery returned nothing for a Chinese conversation.
        // The assertion is on the returned set, not on one tool: the server has several store lookups
        // (到店 / 外送 / 派对) and nothing in their Chinese text distinguishes "附近" — that word appears only
        // in an English tool name. Demanding a specific winner would test a coin flip.
        assertTrue("discovery must not error: ${result.content}", !result.isError)
        val discovered = result.metadata["discovered_tool_names"].orEmpty()
        assertTrue("a Chinese query must surface store tools, got: $discovered", discovered.contains("store"))
    }

    @Test
    fun discoveryCanReturnMoreThanOneToolWhenTheServerOverflows() {
        val scope = tightPlanner().createScope(catalogEntries(), initialIntent = "点餐")

        val discovered = scope.discover(discoverCall("查询门店餐品价格"))
            .metadata["discovered_tool_names"].orEmpty()
            .split(',')
            .filter { name -> name.isNotBlank() }

        // Residents used to fill every slot, leaving discovery exactly one — so an overflowing server that
        // needs four tools together could never assemble them, one call at a time.
        assertTrue("discovery should deliver a set, got: $discovered", discovered.size > 1)
    }

    @Test
    fun advertisedPromptCostStaysBounded() {
        val scope = ToolScopePlanner().createScope(catalogEntries(), initialIntent = "点餐")
        val cost = scope.definitions.sumOf { definition -> definition.toPromptText().length }

        // Not a claim that this is a good number — a guard so a schema-shape change that inflates a 29-tool
        // server's prompt cost fails here instead of silently truncating the ordering path.
        assertTrue("advertised prompt cost grew unexpectedly: $cost chars", cost <= MAX_EXPECTED_PROMPT_CHARS)
    }

    @Test
    fun theWholeServerFitsTheDefaultBudget() {
        val scope = ToolScopePlanner().createScope(catalogEntries(), initialIntent = "帮我点一份巨无霸套餐")

        // The budget is sized for exactly this shape of server, so discovery should not be needed at all.
        assertEquals(FIXTURE_COUNT, scope.advertisedToolNames.count { name -> name.startsWith("mcp_") })
    }

    @Test
    fun anOverflowingResidentSetIsCutByRelevanceNotAlphabet() {
        // Room for a handful only. The store lookup sorts late alphabetically, so if it survives here the
        // cut is being made on intent rather than on name.
        val scope = ToolScopePlanner(maxAdvertisedTools = 8, maxAdvertisedSchemaChars = 48_000)
            .createScope(catalogEntries(), initialIntent = "查询附近的门店")

        assertTrue(
            "relevance ranking must keep the matching tool; advertised=${scope.advertisedToolNames}",
            namespaced("query-nearby-stores") in scope.advertisedToolNames
        )
    }

    @Test
    fun aQueryThatMatchesNothingIsNotReportedAsABudgetFailure() {
        val scope = tightPlanner().createScope(catalogEntries(), initialIntent = "点餐")

        val result = scope.discover(discoverCall("量子物理论文检索"))

        // It used to return tool_scope_budget_exceeded, which sent the model retrying a query that was
        // never the problem instead of rewording it.
        assertTrue("a no-match query is not an error: ${result.content}", !result.isError)
    }

    /**
     * A budget this server overflows, without being so small that nothing could fit anyway.
     * 29 tools cost 23,544 chars, so 16 slots / 16,000 chars truncates on count — the realistic shape of
     * overflow, and the case where discovery has to carry the remainder.
     */
    private fun tightPlanner() = ToolScopePlanner(maxAdvertisedTools = 16, maxAdvertisedSchemaChars = 16_000)

    private fun catalogEntries(): List<ToolCatalogEntry> {
        val registry = ToolRegistry(emptyList())
        registry.installDynamicProviders(providers()).getOrThrow()
        return registry.catalog
    }

    private fun providers(): List<McpToolProvider> = fixtures().mapNotNull { fixture ->
        val parameters = (fixture.translate() as? McpSchemaTranslation.Accepted)?.parameters ?: return@mapNotNull null
        McpToolProvider(
            server = SERVER,
            descriptor = fixture.descriptor(),
            parameters = parameters,
            invoker = NoopInvoker
        )
    }

    private data class Fixture(val name: String, val description: String, val schemaJson: String) {
        fun descriptor(): McpToolDescriptor = McpToolDescriptor(
            serverId = SERVER_ID,
            toolName = name,
            namespacedName = namespaced(name),
            description = description,
            inputSchemaJson = schemaJson
        )

        fun translate(): McpSchemaTranslation {
            val schema = fixtureJson.parseToJsonElement(schemaJson).jsonObject
            return McpSchemaTranslator.translate(
                properties = schema["properties"] as? JsonObject,
                required = (schema["required"] as? JsonArray)
                    ?.mapNotNull { entry -> (entry as? JsonPrimitive)?.contentOrNull }
                    .orEmpty(),
                rawSchema = schema
            )
        }
    }

    private object NoopInvoker : McpToolInvoker {
        override suspend fun callTool(
            server: McpServerConfig,
            toolName: String,
            arguments: Map<String, Any?>
        ): CallToolResult? = null
    }

    private companion object {
        private const val SERVER_ID = "mcd-fixture"
        private const val FIXTURE_COUNT = 29
        private const val MAX_EXPECTED_PROMPT_CHARS = 24_000

        private val ORDERING_PATH =
            listOf("query-nearby-stores", "query-meals", "calculate-price", "create-order")

        private val fixtureJson = Json { isLenient = true; ignoreUnknownKeys = true }

        private val SERVER = McpServerConfig(
            serverId = SERVER_ID,
            alias = "mcd",
            displayName = "麦当劳MCP",
            url = "https://mcp.mcd.cn",
            enabled = true
        )

        private fun namespaced(toolName: String): String =
            "mcp_mcd_${toolName.replace('-', '_')}"

        private fun discoverCall(query: String) = ToolCall(
            id = "discover_1",
            name = ToolDefinition.DiscoverTools.name,
            arguments = """{"query":"$query"}"""
        )

        /**
         * Rows are `name<<>>description<<>>schema`, one tool per record. Split on the record boundary
         * rather than on newlines, because both descriptions and schemas contain newlines.
         */
        private fun fixtures(): List<Fixture> {
            val raw = checkNotNull(
                McpLargeServerScopeTest::class.java.getResourceAsStream("/mcp/mcdonalds_tools.txt")
            ) { "fixture missing" }.use { stream -> stream.readBytes().decodeToString() }

            return raw.split(Regex("\\n(?=[a-z][a-z0-9-]*<<>>)"))
                .map { record -> record.trim() }
                .filter { record -> record.isNotEmpty() }
                .map { record ->
                    val parts = record.split("<<>>")
                    require(parts.size == 3) { "malformed fixture record: ${record.take(60)}" }
                    Fixture(parts[0].trim(), parts[1].trim(), parts[2].trim())
                }
        }
    }
}
