package cn.nabr.chatwithchat.data.tool

import javax.inject.Inject

@ConsistentCopyVisibility
data class ToolApprovalRequest private constructor(
    val preview: ToolApprovalPreview,
    internal val callBindingHash: String,
    internal val pendingExecutionContext: ToolExecutionContext
) {
    internal fun matches(call: ToolCall): Boolean = preview.matches(call) &&
        call.approvalBinding().getOrNull()?.callBindingHash == callBindingHash &&
        pendingExecutionContext.matchesOperation(call)

    companion object {
        internal fun create(
            preview: ToolApprovalPreview,
            pendingExecutionContext: ToolExecutionContext
        ): ToolApprovalRequest = ToolApprovalRequest(
            preview = preview,
            callBindingHash = preview.callBindingHash,
            pendingExecutionContext = pendingExecutionContext
        )
    }
}

class ToolApprovalCoordinator @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val approvalAuthority: ToolApprovalAuthority,
    private val executionContextFactory: ToolExecutionContextFactory
) {
    fun prepare(call: ToolCall): Result<ToolApprovalRequest> = runCatching {
        val entry = toolRegistry.catalogEntryFor(call.name)
            ?: throw IllegalArgumentException("unknown_tool:${call.name}")
        require(entry.securityPolicy.approvalPolicy == ToolApprovalPolicy.REQUIRE_EACH_CALL) {
            "tool_approval_not_required"
        }
        val provider = toolRegistry.providerFor(call.name)
            ?: throw IllegalStateException("tool_approval_provider_required")
        val argumentSummary = provider.approvalArgumentSummary(call)
            ?: throw IllegalStateException("tool_approval_summary_required")
        val preview = ToolApprovalPreview.create(
            call = call,
            presentationKey = entry.settings.presentationKey,
            fallbackDisplayName = "",
            humanReadableArgumentSummary = argumentSummary
        ).getOrThrow()
        ToolApprovalRequest.create(
            preview = preview,
            pendingExecutionContext = executionContextFactory.create(call).getOrThrow()
        )
    }

    fun approve(
        call: ToolCall,
        request: ToolApprovalRequest
    ): Result<ToolExecutionContext> = runCatching {
        require(request.matches(call)) { "tool_approval_request_mismatch" }
        val approval = approvalAuthority.approve(call).getOrThrow()
        executionContextFactory.explicitRetry(call, request.pendingExecutionContext, approval).getOrThrow()
    }

    /** The scope a conversation-scoped grant for [toolName] would cover, or `null` if grants are forbidden. */
    fun sessionApprovalScope(toolName: String): String? = toolRegistry.providerFor(toolName)
        ?.sessionApprovalScope
        ?.trim()
        ?.takeIf { scope -> scope.isNotBlank() }

    /**
     * Authorizes a call from an existing conversation-scoped grant, with no dialog.
     *
     * The approval token is minted for *this* call, so it stays bound to this tool name and these exact
     * arguments. The grant only decides whether to ask; it is not itself a credential and cannot be
     * replayed onto another operation. A tool that does not opt into grants is refused here.
     */
    fun approveFromSessionGrant(call: ToolCall): Result<ToolExecutionContext> = runCatching {
        val entry = toolRegistry.catalogEntryFor(call.name)
            ?: throw IllegalArgumentException("unknown_tool:${call.name}")
        require(entry.securityPolicy.approvalPolicy == ToolApprovalPolicy.REQUIRE_EACH_CALL) {
            "tool_approval_not_required"
        }
        require(sessionApprovalScope(call.name) != null) { "tool_session_grant_unsupported" }
        val approval = approvalAuthority.approve(call).getOrThrow()
        executionContextFactory.create(call, approval).getOrThrow()
    }

    fun deny(
        call: ToolCall,
        request: ToolApprovalRequest
    ): Result<ToolExecutionContext> = runCatching {
        require(request.matches(call)) { "tool_approval_request_mismatch" }
        executionContextFactory.explicitRetry(
            call,
            request.pendingExecutionContext,
            ToolCallApproval.Denied
        ).getOrThrow()
    }
}
