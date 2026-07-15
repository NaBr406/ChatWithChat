package cn.nabr.chatwithchat.data.tool

data class ToolSettingsMetadata(
    val userVisible: Boolean = true,
    val category: ToolCategory = ToolCategory.Other,
    val defaultEnabled: Boolean = false,
    val isSensitive: Boolean = true,
    val presentationKey: String? = null,
    val iconKey: String? = null
)

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
    val securityPolicy: ToolSecurityPolicy
)
