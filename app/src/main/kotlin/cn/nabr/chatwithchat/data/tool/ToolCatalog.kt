package cn.nabr.chatwithchat.data.tool

data class ToolSettingsMetadata(
    val userVisible: Boolean = true,
    val category: ToolCategory = ToolCategory.Other,
    val defaultEnabled: Boolean = false,
    val isSensitive: Boolean = true,
    val presentationKey: String? = null,
    val iconKey: String? = null,
    val enablementGroup: ToolEnablementGroup = ToolEnablementGroup.General
)

enum class ToolEnablementGroup {
    General,
    AutomaticStickerReplies
}

enum class ToolCategory {
    Web,
    Device,
    Utility,
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
 * Metadata used only to decide when a tool schema should be advertised to a model.
 * It never grants execution permission; ToolScope remains the execution allowlist.
 */
data class ToolDiscoveryMetadata(
    val exposure: ToolExposure = ToolExposure.OnDemand,
    val intentTags: Set<String> = emptySet(),
    val requiredCompanionToolNames: Set<String> = emptySet(),
    val priority: Int = 0
)

enum class ToolExposure {
    Resident,
    OnDemand
}
