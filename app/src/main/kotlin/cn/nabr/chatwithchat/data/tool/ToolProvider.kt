package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata

interface ToolProvider {
    val definition: ToolDefinition

    val settingsMetadata: ToolSettingsMetadata
        get() = ToolSettingsMetadata()

    val securityPolicy: ToolSecurityPolicy

    val policy: ToolPolicy
        get() = ToolPolicy.default()

    val permissionRequirements: List<ToolPermissionRequirement>
        get() = emptyList()

    fun progressLabel(call: ToolCall): String = definition.name

    fun approvalArgumentSummary(call: ToolCall): String? = null

    suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult

    suspend fun execute(
        call: ToolCall,
        config: ToolLoopConfig,
        executionContext: ToolExecutionContext
    ): ToolResult = execute(call, config)

    fun sourceMetadata(result: ToolResult): List<MessageSourceMetadata> =
        result.sources.toMessageSourceMetadata(definition.name)
}
