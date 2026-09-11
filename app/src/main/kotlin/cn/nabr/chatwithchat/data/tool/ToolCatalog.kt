package cn.nabr.chatwithchat.data.tool

data class ToolSettingsMetadata(
    val userVisible: Boolean = true,
    val userOverrideAllowed: Boolean = true,
    val category: ToolCategory = ToolCategory.Other,
    val defaultEnabled: Boolean = false,
    val isSensitive: Boolean = true,
    /**
     * Opt in to default enablement for a private read-only tool. This is deliberately narrower than
     * "read-only": a private source can still expose user data, so only built-ins that read the
     * current chat's own assets should set it.
     */
    val allowDefaultReadOnlyPrivate: Boolean = false,
    val presentationKey: String? = null,
    val iconKey: String? = null,
    val enablementGroup: ToolEnablementGroup = ToolEnablementGroup.General
)

enum class ToolEnablementGroup {
    General,
    AutomaticStickerReplies,

    /**
     * Tools bridged from an MCP server. Grouped so the general tool list can exclude them — a server
     * with thirty tools would otherwise bury the built-ins — and manage them on their own screen instead.
     */
    McpServer
}

enum class ToolCategory {
    Web,
    Device,
    Utility,
    Mcp,
    Other
}

data class ToolCatalogEntry(
    val definition: ToolDefinition,
    val settings: ToolSettingsMetadata,
    val permissionRequirements: List<ToolPermissionRequirement>,
    val securityPolicy: ToolSecurityPolicy,
    val discovery: ToolDiscoveryMetadata = ToolDiscoveryMetadata()
)

/**
 * Whether any enabled tool outranks the pre-flight web-search shortcut.
 *
 * The shortcut (`executeSearchDecisionIfNeeded`) decides for the model that a turn needs a web search, runs
 * it, and returns a final-answer prompt — the tool loop never runs, so no other tool gets a turn. That is a
 * fair trade when `web_search` is the only source of outside information, and the wrong one the moment
 * something better is enabled. It is why "配了 MCP 还是联网搜索" and "开了位置工具还是联网搜索" were one bug
 * and not two.
 */
internal fun List<ToolCatalogEntry>.supersedesWebSearchShortcut(
    activeToolDefinitions: List<ToolDefinition>
): Boolean {
    val activeNames = activeToolDefinitions.mapTo(mutableSetOf(), ToolDefinition::name)
    return any { entry ->
        entry.definition.name in activeNames && entry.discovery.supersedesWebSearch
    }
}

/**
 * Metadata used only to decide when a tool schema should be advertised to a model.
 * It never grants execution permission; ToolScope remains the execution allowlist.
 */
data class ToolDiscoveryMetadata(
    val exposure: ToolExposure = ToolExposure.OnDemand,
    val intentTags: Set<String> = emptySet(),
    val requiredCompanionToolNames: Set<String> = emptySet(),
    val priority: Int = 0,
    /**
     * Declares that this tool can serve information the model would otherwise web-search for, so the
     * pre-flight search shortcut must stand down while it is enabled. See
     * [supersedesWebSearchShortcut].
     *
     * Set it on tools that *answer* — a first-party MCP data source, or a device tool whose reading a
     * search query has to be written around. Output-side tools (stickers, alarms, schedules) leave it
     * `false`: they cannot answer a question, so their presence is no reason to make every searchable turn
     * pay for a tool-loop round-trip.
     */
    val supersedesWebSearch: Boolean = false
)

enum class ToolExposure {
    Resident,
    OnDemand
}
