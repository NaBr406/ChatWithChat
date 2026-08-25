package cn.nabr.chatwithchat.data.tool

import java.util.UUID

@JvmInline
value class ToolIdempotencyKey internal constructor(val value: String)

class ToolExecutionContext internal constructor(
    val approval: ToolCallApproval,
    val idempotencyKey: ToolIdempotencyKey,
    internal val operationHash: String,
    val chatId: Int = 0,
    val turnKey: String? = null
) {
    fun matchesOperation(call: ToolCall): Boolean = call.approvalBinding()
        .getOrNull()
        ?.operationHash == operationHash
}

class ToolExecutionContextFactory(
    private val nonceGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    fun create(
        call: ToolCall,
        approval: ToolCallApproval = ToolCallApproval.Missing,
        chatId: Int = 0,
        turnKey: String? = null
    ): Result<ToolExecutionContext> = call.approvalBinding().mapCatching { binding ->
        val nonce = nonceGenerator().trim()
        require(nonce.isNotBlank()) { "tool_idempotency_nonce_required" }
        ToolExecutionContext(
            approval = approval,
            idempotencyKey = ToolIdempotencyKey(
                sha256Domain(IDEMPOTENCY_KEY_DOMAIN, listOf(binding.operationHash, nonce))
            ),
            operationHash = binding.operationHash,
            chatId = chatId,
            turnKey = turnKey
        )
    }

    fun explicitRetry(
        call: ToolCall,
        previousContext: ToolExecutionContext,
        approval: ToolCallApproval = ToolCallApproval.Missing
    ): Result<ToolExecutionContext> = call.approvalBinding().mapCatching { binding ->
        require(binding.operationHash == previousContext.operationHash) {
            "tool_retry_operation_mismatch"
        }
        ToolExecutionContext(
            approval = approval,
            idempotencyKey = previousContext.idempotencyKey,
            operationHash = binding.operationHash,
            chatId = previousContext.chatId,
            turnKey = previousContext.turnKey
        )
    }

    companion object {
        private const val IDEMPOTENCY_KEY_DOMAIN = "tool-idempotency-v1"
    }
}
