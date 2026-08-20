package cn.nabr.chatwithchat.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the boundary of the pre-flight web-search shortcut.
 *
 * The shortcut ends a turn on its own, so anything it runs ahead of is a tool that never gets called. These
 * tests pin which tools are worth paying a tool-loop round-trip for and which are not.
 */
class WebSearchShortcutTest {

    @Test
    fun `an enabled mcp tool stands the search shortcut down`() {
        val catalog = listOf(
            catalogEntry(ToolDefinition.WebSearch),
            catalogEntry(
                definition = mcpToolDefinition,
                discovery = ToolDiscoveryMetadata(
                    exposure = ToolExposure.Resident,
                    supersedesWebSearch = true
                )
            )
        )

        assertTrue(
            catalog.supersedesWebSearchShortcut(
                listOf(ToolDefinition.WebSearch, mcpToolDefinition)
            )
        )
    }

    @Test
    fun `an enabled device location tool stands the search shortcut down`() {
        val catalog = listOf(
            catalogEntry(ToolDefinition.WebSearch),
            catalogEntry(
                definition = ToolDefinition.DeviceLocation,
                discovery = ToolDiscoveryMetadata(supersedesWebSearch = true)
            )
        )

        assertTrue(
            catalog.supersedesWebSearchShortcut(
                listOf(ToolDefinition.WebSearch, ToolDefinition.DeviceLocation)
            )
        )
    }

    @Test
    fun `a catalogued but disabled specialist tool leaves the shortcut alone`() {
        val catalog = listOf(
            catalogEntry(ToolDefinition.WebSearch),
            catalogEntry(
                definition = mcpToolDefinition,
                discovery = ToolDiscoveryMetadata(supersedesWebSearch = true)
            )
        )

        // Present in the catalog, absent from the active set: the user never turned it on, so the cheap path
        // is still the right one.
        assertFalse(catalog.supersedesWebSearchShortcut(listOf(ToolDefinition.WebSearch)))
    }

    @Test
    fun `output side tools leave the shortcut alone`() {
        val catalog = listOf(
            catalogEntry(ToolDefinition.WebSearch),
            catalogEntry(ToolDefinition.SearchStickers),
            catalogEntry(ToolDefinition.SendSticker),
            catalogEntry(ToolDefinition.SetAlarm)
        )

        // None of these can answer a question, so their presence is no reason to make a searchable turn pay
        // for a tool-loop round-trip.
        assertFalse(
            catalog.supersedesWebSearchShortcut(
                listOf(
                    ToolDefinition.WebSearch,
                    ToolDefinition.SearchStickers,
                    ToolDefinition.SendSticker,
                    ToolDefinition.SetAlarm
                )
            )
        )
    }

    @Test
    fun `the shipped device location provider stands the shortcut down`() {
        val provider = DeviceLocationToolProvider(UnavailableDeviceLocationReader)

        assertTrue(provider.discoveryMetadata.supersedesWebSearch)
        // Resident so an enabled location tool is visible on the turn it is needed, without a discover_tools
        // round first. Advertisement only — enablement and the Android permission check are untouched.
        assertEquals(ToolExposure.Resident, provider.discoveryMetadata.exposure)
        assertFalse(provider.settingsMetadata.defaultEnabled)
    }
}

private val mcpToolDefinition = ToolDefinition(
    name = "mcp_shop_query_stores",
    description = "[Shop] 查询用户可点餐门店",
    parameters = ToolDefinition.Parameters()
)

private fun catalogEntry(
    definition: ToolDefinition,
    discovery: ToolDiscoveryMetadata = ToolDiscoveryMetadata()
): ToolCatalogEntry = ToolCatalogEntry(
    definition = definition,
    settings = ToolSettingsMetadata(),
    permissionRequirements = emptyList(),
    securityPolicy = ToolSecurityPolicy.ReadOnlyPublic,
    discovery = discovery
)
