package dev.chungjungsoo.gptmobile.data.tool

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
