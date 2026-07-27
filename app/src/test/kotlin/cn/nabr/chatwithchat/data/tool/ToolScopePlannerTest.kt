package cn.nabr.chatwithchat.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolScopePlannerTest {

    @Test
    fun `large enabled catalog starts with only discovery control tool`() {
        val entries = (1..100).map { index ->
            catalogEntry(
                name = "tool_$index",
                tags = setOf("capability_$index")
            )
        }

        val scope = ToolScopePlanner(
            maxAdvertisedTools = 4,
            maxAdvertisedSchemaChars = 1_200
        ).createScope(entries)
        val prompt = ToolPromptBuilder(maxPromptChars = 1_200).buildJsonFallbackPrompt(scope.definitions)

        assertEquals(listOf(ToolDefinition.DiscoverTools.name), scope.definitions.map(ToolDefinition::name))
        assertTrue(scope.definitions.sumOf { definition -> definition.toPromptText().length } <= 1_200)
        assertTrue(prompt.length <= 1_200)
        assertFalse(prompt.contains("tool_100("))
    }

    @Test
    fun `injected provider sizer bounds the advertised schema payload`() {
        val scope = ToolScopePlanner(
            maxAdvertisedTools = 4,
            maxAdvertisedSchemaChars = 800
        ).createScope(
            entries = listOf(catalogEntry(name = "calendar_tool", tags = setOf("calendar"))),
            initialIntent = "Create a calendar event",
            advertisementSizer = ToolAdvertisementSizer { definitions -> definitions.size * 600 }
        )

        assertEquals(listOf(ToolDefinition.DiscoverTools.name), scope.definitions.map(ToolDefinition::name))
    }

    @Test
    fun `resident tools also respect the advertised schema budget`() {
        val scope = ToolScopePlanner(
            maxAdvertisedTools = 2,
            maxAdvertisedSchemaChars = 1_200
        ).createScope(
            (1..10).map { index ->
                catalogEntry(
                    name = "resident_$index",
                    exposure = ToolExposure.Resident
                ).copy(
                    definition = ToolDefinition(
                        name = "resident_$index",
                        description = "A resident capability with an intentionally long description ".repeat(20),
                        parameters = ToolDefinition.Parameters()
                    )
                )
            }
        )

        assertTrue(scope.definitions.size <= 2)
        assertTrue(scope.definitions.sumOf { definition -> definition.toPromptText().length } <= 1_200)
        assertTrue(scope.contains(ToolDefinition.DiscoverTools.name))
    }

    @Test
    fun `resident overflow remains discoverable within the next response`() {
        val scope = ToolScopePlanner(
            maxAdvertisedTools = 2,
            maxAdvertisedSchemaChars = 1_200
        ).createScope(
            (1..3).map { index ->
                catalogEntry(
                    name = "resident_$index",
                    tags = setOf("resident", "capability_$index"),
                    exposure = ToolExposure.Resident
                )
            }
        )

        assertTrue(scope.contains(ToolDefinition.DiscoverTools.name))
        val result = scope.discover(
            ToolCall(
                id = "discover_resident",
                name = ToolDefinition.DiscoverTools.name,
                arguments = "{\"query\":\"capability_3\"}"
            )
        )

        assertFalse(result.isError)
        assertTrue(scope.contains("resident_3"))
        assertTrue(result.metadata.containsKey("discovered_tool_names"))
    }

    @Test
    fun `single advertised slot replaces discovery control with the discovered tool`() {
        val scope = ToolScopePlanner(maxAdvertisedTools = 1).createScope(
            listOf(catalogEntry(name = "calendar_tool", tags = setOf("calendar")))
        )

        val result = scope.discover(
            ToolCall(
                id = "discover_single_slot",
                name = ToolDefinition.DiscoverTools.name,
                arguments = "{\"query\":\"calendar\"}"
            )
        )

        assertFalse(result.isError)
        assertFalse(scope.contains(ToolDefinition.DiscoverTools.name))
        assertTrue(scope.contains("calendar_tool"))
    }

    @Test
    fun `discovery errors expose stable error codes`() {
        val scope = ToolScopePlanner(maxDiscoveryCalls = 0).createScope(
            listOf(catalogEntry(name = "calendar_tool", tags = setOf("calendar")))
        )

        val result = scope.discover(
            ToolCall(
                id = "discover_budget",
                name = ToolDefinition.DiscoverTools.name,
                arguments = "{\"query\":\"calendar\"}"
            )
        )

        assertTrue(result.isError)
        assertEquals("tool_discovery_budget_exceeded", result.metadata["error_code"])
    }

    @Test
    fun `resident sticker pair stays visible without matching user intent`() {
        val scope = ToolScopePlanner().createScope(
            listOf(
                catalogEntry(
                    name = ToolDefinition.SearchStickers.name,
                    exposure = ToolExposure.Resident,
                    companions = setOf(ToolDefinition.SendSticker.name)
                ),
                catalogEntry(
                    name = ToolDefinition.SendSticker.name,
                    exposure = ToolExposure.Resident,
                    companions = setOf(ToolDefinition.SearchStickers.name)
                ),
                catalogEntry(name = "calendar_tool", tags = setOf("calendar"))
            ),
            initialIntent = "just say hello"
        )

        assertEquals(
            setOf(
                ToolDefinition.SearchStickers.name,
                ToolDefinition.SendSticker.name,
                ToolDefinition.DiscoverTools.name
            ),
            scope.advertisedToolNames
        )
    }

    @Test
    fun `transitive companion closure is advertised atomically without looping`() {
        val scope = ToolScopePlanner(
            maxAdvertisedTools = 3,
            maxAdvertisedSchemaChars = 1_200
        ).createScope(
            listOf(
                catalogEntry(
                    name = "primary_tool",
                    exposure = ToolExposure.Resident,
                    companions = setOf("companion_tool")
                ),
                catalogEntry(
                    name = "companion_tool",
                    exposure = ToolExposure.Resident,
                    companions = setOf("final_tool")
                ),
                catalogEntry(
                    name = "final_tool",
                    exposure = ToolExposure.Resident,
                    companions = setOf("primary_tool")
                )
            )
        )

        assertEquals(
            setOf("primary_tool", "companion_tool", "final_tool"),
            scope.advertisedToolNames
        )
    }

    @Test
    fun `companion closure is not partially advertised when a budget cannot fit it`() {
        val primary = catalogEntry(
            name = "primary_tool",
            exposure = ToolExposure.Resident,
            companions = setOf("companion_tool")
        )
        val companion = catalogEntry(
            name = "companion_tool",
            exposure = ToolExposure.Resident,
            companions = setOf("primary_tool")
        )
        val entries = listOf(primary, companion)

        val countLimitedScope = ToolScopePlanner(
            maxAdvertisedTools = 1,
            maxAdvertisedSchemaChars = 1_200
        ).createScope(entries)
        val individualSchemaCost = maxOf(
            primary.definition.toPromptText().length,
            companion.definition.toPromptText().length
        )
        val schemaLimitedScope = ToolScopePlanner(
            maxAdvertisedTools = 2,
            maxAdvertisedSchemaChars = individualSchemaCost
        ).createScope(entries)

        assertFalse(countLimitedScope.contains("primary_tool"))
        assertFalse(countLimitedScope.contains("companion_tool"))
        assertFalse(schemaLimitedScope.contains("primary_tool"))
        assertFalse(schemaLimitedScope.contains("companion_tool"))
    }

    @Test
    fun `missing required companion rejects only its tool and keeps an independent web tool`() {
        val scope = ToolScopePlanner(
            maxAdvertisedTools = 2,
            maxAdvertisedSchemaChars = 1_200
        ).createScope(
            listOf(
                catalogEntry(
                    name = "requires_companion",
                    exposure = ToolExposure.Resident,
                    companions = setOf("missing_companion")
                ),
                catalogEntry(
                    name = ToolDefinition.WebSearch.name,
                    exposure = ToolExposure.Resident
                )
            )
        )

        assertFalse(scope.contains("requires_companion"))
        assertTrue(scope.contains(ToolDefinition.WebSearch.name))
    }

    @Test
    fun `clear initial intent advertises one matching tool without the whole catalog`() {
        val scope = ToolScopePlanner().createScope(
            listOf(
                catalogEntry(name = "create_calendar_event", tags = setOf("calendar", "schedule")),
                catalogEntry(name = "read_weather", tags = setOf("weather"))
            ),
            initialIntent = "Create a calendar schedule for tomorrow"
        )

        assertTrue(scope.contains(ToolDefinition.DiscoverTools.name))
        assertTrue(scope.contains("create_calendar_event"))
        assertFalse(scope.contains("read_weather"))
    }

    @Test
    fun `generic current wording does not select a device time tool`() {
        val scope = ToolScopePlanner().createScope(
            listOf(
                catalogEntry(
                    name = ToolDefinition.CurrentDateTime.name,
                    tags = setOf("date", "time", "timezone")
                )
            ),
            initialIntent = "current Android target SDK"
        )

        assertEquals(setOf(ToolDefinition.DiscoverTools.name), scope.advertisedToolNames)
    }

    @Test
    fun `generic current wording does not select web search from a generic tag`() {
        val scope = ToolScopePlanner().createScope(
            listOf(
                catalogEntry(
                    name = ToolDefinition.WebSearch.name,
                    tags = setOf("current", "web", "search"),
                    priority = 30
                ),
                catalogEntry(
                    name = ToolDefinition.CurrentDateTime.name,
                    tags = setOf("date", "time", "timezone"),
                    priority = 20
                )
            ),
            initialIntent = "current Android target SDK"
        )

        assertEquals(setOf(ToolDefinition.DiscoverTools.name), scope.advertisedToolNames)
    }

    @Test
    fun `explicit tags prevent generic description words from selecting a tool`() {
        val scope = ToolScopePlanner().createScope(
            listOf(
                ToolCatalogEntry(
                    definition = ToolDefinition(
                        name = "device_location",
                        description = "Returns Android system location data.",
                        parameters = ToolDefinition.Parameters()
                    ),
                    settings = ToolSettingsMetadata(userVisible = false),
                    permissionRequirements = emptyList(),
                    securityPolicy = ToolSecurityPolicy.ReadOnlyPrivate,
                    discovery = ToolDiscoveryMetadata(intentTags = setOf("location", "gps", "where"))
                )
            ),
            initialIntent = "What is the current Android target SDK?"
        )

        assertEquals(setOf(ToolDefinition.DiscoverTools.name), scope.advertisedToolNames)
    }

    @Test
    fun `news intent prefers the higher priority web capability over device date`() {
        val scope = ToolScopePlanner().createScope(
            listOf(
                catalogEntry(
                    name = "web_search",
                    tags = setOf("today", "news", "happened"),
                    priority = 30
                ),
                catalogEntry(
                    name = ToolDefinition.CurrentDateTime.name,
                    tags = setOf("today", "date", "time"),
                    priority = 20
                )
            ),
            initialIntent = "What happened today?"
        )

        assertTrue(scope.contains("web_search"))
        assertFalse(scope.contains(ToolDefinition.CurrentDateTime.name))
    }

    @Test
    fun `discovery expands only matching on demand tools`() {
        val scope = ToolScopePlanner().createScope(
            listOf(
                catalogEntry(name = "create_calendar_event", tags = setOf("calendar", "schedule")),
                catalogEntry(name = "read_weather", tags = setOf("weather"))
            )
        )

        val result = scope.discover(
            ToolCall(
                id = "discover_calendar",
                name = ToolDefinition.DiscoverTools.name,
                arguments = "{\"query\":\"calendar schedule\"}"
            )
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("create_calendar_event"))
        assertTrue(scope.contains("create_calendar_event"))
        assertFalse(scope.contains("read_weather"))
    }

    @Test
    fun `discovery result is bounded by scope capacity instead of exposing hidden tools`() {
        val scope = ToolScopePlanner(
            maxAdvertisedTools = 2,
            maxAdvertisedSchemaChars = 1_200
        ).createScope(
            listOf(
                catalogEntry(name = "first_calendar_tool", tags = setOf("calendar")),
                catalogEntry(name = "second_calendar_tool", tags = setOf("calendar"))
            )
        )

        val result = scope.discover(
            ToolCall(
                id = "discover_calendar",
                name = ToolDefinition.DiscoverTools.name,
                arguments = "{\"query\":\"calendar\"}"
            )
        )

        assertFalse(result.isError)
        assertEquals(2, scope.definitions.size)
        assertEquals(1, result.metadata.getValue("discovered_tool_names").split(',').size)
        assertFalse(scope.contains("second_calendar_tool"))
    }

    private fun catalogEntry(
        name: String,
        tags: Set<String> = emptySet(),
        exposure: ToolExposure = ToolExposure.OnDemand,
        companions: Set<String> = emptySet(),
        priority: Int = 0
    ): ToolCatalogEntry = ToolCatalogEntry(
        definition = ToolDefinition(
            name = name,
            description = "Provide $name capability.",
            parameters = ToolDefinition.Parameters()
        ),
        settings = ToolSettingsMetadata(userVisible = false),
        permissionRequirements = emptyList(),
        securityPolicy = ToolSecurityPolicy.ReadOnlyPublic,
        discovery = ToolDiscoveryMetadata(
            exposure = exposure,
            intentTags = tags,
            requiredCompanionToolNames = companions,
            priority = priority
        )
    )
}
