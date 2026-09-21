package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata

enum class ToolInteractionMode {
    HeadlessAllowed,
    ForegroundRequired
}

interface ToolProvider {
    val definition: ToolDefinition

    val settingsMetadata: ToolSettingsMetadata
        get() = ToolSettingsMetadata()

    val discoveryMetadata: ToolDiscoveryMetadata
        get() = ToolDiscoveryMetadata()

    val securityPolicy: ToolSecurityPolicy

    /**
     * Foreground-required tools may suspend on UI interaction and must never run in an unattended loop.
     */
    val interactionMode: ToolInteractionMode
        get() = ToolInteractionMode.HeadlessAllowed

    /**
     * Opaque key naming what a conversation-scoped approval grant covers, or `null` to forbid grants.
     *
     * A provider that returns non-null lets the user answer "allow for this conversation" once instead of
     * confirming every call. The key defines the blast radius, so it should be as narrow as the capability
     * it represents — one remote server *and* one tool, not a whole server.
     *
     * Returning `null` (the default) keeps a tool on strict per-call approval. New write-capable built-ins
     * therefore do not silently inherit grants.
     */
    val sessionApprovalScope: String?
        get() = null

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

    fun validateSessionCall(call: ToolCall, sessionState: ToolExecutionSessionState): ToolResult? = null

    fun onSessionResult(result: ToolResult, sessionState: ToolExecutionSessionState) = Unit

    fun sourceMetadata(result: ToolResult): List<MessageSourceMetadata> =
        result.sources.toMessageSourceMetadata(definition.name)

    fun presentationArtifacts(result: ToolResult): List<ToolPresentationArtifact> = emptyList()
}
