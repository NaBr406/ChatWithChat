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
        overrides: ToolEnablementOverrides,
        automaticStickerRepliesEnabled: Boolean = true
    ): List<ResolvedToolCatalogEntry> = catalog.map { entry ->
        ResolvedToolCatalogEntry(
            catalogEntry = entry,
            isEnabled = isEnabled(entry, overrides, automaticStickerRepliesEnabled)
        )
    }

    fun enabledToolNames(
        catalog: List<ToolCatalogEntry>,
        overrides: ToolEnablementOverrides,
        automaticStickerRepliesEnabled: Boolean = true
    ): Set<String> = resolve(catalog, overrides, automaticStickerRepliesEnabled)
        .filter { entry -> entry.isEnabled }
        .map { entry -> entry.catalogEntry.definition.name }
        .toSet()

    fun isEnabled(
        entry: ToolCatalogEntry,
        overrides: ToolEnablementOverrides,
        automaticStickerRepliesEnabled: Boolean = true
    ): Boolean {
        val toolName = entry.definition.name
        return when {
            entry.settings.enablementGroup == ToolEnablementGroup.AutomaticStickerReplies &&
                !automaticStickerRepliesEnabled -> false
            toolName in overrides.disabledToolNames -> false
            toolName in overrides.enabledToolNames -> true
            else -> entry.settings.defaultEnabled && entry.isSafeForDefaultEnablement()
        }
    }
}

private fun ToolCatalogEntry.isSafeForDefaultEnablement(): Boolean =
    !settings.isSensitive &&
        permissionRequirements.isEmpty() &&
        securityPolicy.approvalPolicy == ToolApprovalPolicy.NOT_REQUIRED &&
        when (securityPolicy.effect) {
            ToolEffect.READ_ONLY_PUBLIC -> true
            ToolEffect.READ_ONLY_PRIVATE ->
                settings.enablementGroup == ToolEnablementGroup.AutomaticStickerReplies
            ToolEffect.LOCAL_WRITE,
            ToolEffect.EXTERNAL_WRITE,
            ToolEffect.IRREVERSIBLE -> false
        }
