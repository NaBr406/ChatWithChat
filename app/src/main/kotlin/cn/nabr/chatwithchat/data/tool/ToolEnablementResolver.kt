package cn.nabr.chatwithchat.data.tool

import javax.inject.Inject

data class ToolEnablementOverrides(
    val enabledToolNames: Set<String> = emptySet(),
    val disabledToolNames: Set<String> = emptySet()
) {
    fun withOverride(toolName: String, enabled: Boolean): ToolEnablementOverrides {
        val normalizedName = toolName.trim()
        if (normalizedName.isBlank()) return this

        return if (enabled) {
            copy(
                enabledToolNames = enabledToolNames + normalizedName,
                disabledToolNames = disabledToolNames - normalizedName
            )
        } else {
            copy(
                enabledToolNames = enabledToolNames - normalizedName,
                disabledToolNames = disabledToolNames + normalizedName
            )
        }
    }
}

data class ResolvedToolCatalogEntry(
    val catalogEntry: ToolCatalogEntry,
    val isEnabled: Boolean
)

class ToolEnablementResolver @Inject constructor() {
    fun resolve(
        catalog: List<ToolCatalogEntry>,
        overrides: ToolEnablementOverrides
    ): List<ResolvedToolCatalogEntry> = catalog.map { entry ->
        ResolvedToolCatalogEntry(
            catalogEntry = entry,
            isEnabled = isEnabled(entry, overrides)
        )
    }

    fun enabledToolNames(
        catalog: List<ToolCatalogEntry>,
        overrides: ToolEnablementOverrides
    ): Set<String> = resolve(catalog, overrides)
        .filter { entry -> entry.isEnabled }
        .map { entry -> entry.catalogEntry.definition.name }
        .toSet()

    fun isEnabled(
        entry: ToolCatalogEntry,
        overrides: ToolEnablementOverrides
    ): Boolean {
        val toolName = entry.definition.name
        return when {
            toolName in overrides.disabledToolNames -> false
            toolName in overrides.enabledToolNames -> true
            else -> entry.settings.defaultEnabled && entry.isSafeForDefaultEnablement()
        }
    }
}

private fun ToolCatalogEntry.isSafeForDefaultEnablement(): Boolean =
    !settings.isSensitive &&
        permissionRequirements.isEmpty() &&
        securityPolicy.effect == ToolEffect.READ_ONLY_PUBLIC &&
        securityPolicy.approvalPolicy == ToolApprovalPolicy.NOT_REQUIRED
