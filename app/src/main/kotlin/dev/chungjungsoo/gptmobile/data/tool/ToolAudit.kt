package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.coroutines.CancellationException

enum class ToolAuditStatus {
    ATTEMPTED,
    APPROVED,
    DENIED,
    APPROVAL_MISSING,
    APPROVAL_INVALID,
    EXECUTED,
    FAILED
}

@ConsistentCopyVisibility
data class ToolAuditEvent private constructor(
    val toolNameHash: String,
    val argumentsHash: String,
    val callBindingHash: String,
    val operationHash: String,
    val idempotencyKeyHash: String?,
    val status: ToolAuditStatus
) {
    companion object {
        fun create(
            call: ToolCall,
            status: ToolAuditStatus,
            executionContext: ToolExecutionContext? = null
        ): Result<ToolAuditEvent> = runCatching {
            val binding = bindingFor(call, ToolLoopConfig.Default.maxToolArgumentChars).getOrThrow()
            create(binding, status, executionContext).getOrThrow()
        }

        internal fun bindingFor(
            call: ToolCall,
            maxArgumentChars: Int
        ): Result<ToolAuditBinding> = runCatching {
            if (call.arguments.length > maxArgumentChars.coerceAtLeast(0)) {
                call.oversizedAuditBinding()
            } else {
                call.approvalBinding().getOrNull()?.toAuditBinding() ?: call.rawAuditBinding()
            }
        }

        internal fun create(
            binding: ToolAuditBinding,
            status: ToolAuditStatus,
            executionContext: ToolExecutionContext? = null
        ): Result<ToolAuditEvent> = runCatching {
            val contextMatches = if (executionContext == null) {
                true
            } else {
                executionContext.operationHash == binding.operationHash
            }
            require(contextMatches) {
                "tool_audit_context_mismatch"
            }
            ToolAuditEvent(
                toolNameHash = binding.toolNameHash,
                argumentsHash = binding.argumentsHash,
                callBindingHash = binding.callBindingHash,
                operationHash = binding.operationHash,
                idempotencyKeyHash = executionContext?.let { context ->
                    sha256Domain(IDEMPOTENCY_AUDIT_HASH_DOMAIN, listOf(context.idempotencyKey.value))
                },
                status = status
            )
        }
    }
}

internal data class ToolAuditBinding(
    val toolNameHash: String,
    val argumentsHash: String,
    val callBindingHash: String,
    val operationHash: String
)

private fun ToolApprovalBinding.toAuditBinding(): ToolAuditBinding = ToolAuditBinding(
    toolNameHash = toolNameHash,
    argumentsHash = argumentsHash,
    callBindingHash = callBindingHash,
    operationHash = operationHash
)

private fun ToolCall.rawAuditBinding(): ToolAuditBinding {
    val toolNameHash = sha256Domain(RAW_TOOL_NAME_HASH_DOMAIN, listOf(name))
    val argumentsHash = sha256Domain(RAW_ARGUMENTS_HASH_DOMAIN, listOf(arguments))
    return ToolAuditBinding(
        toolNameHash = toolNameHash,
        argumentsHash = argumentsHash,
        callBindingHash = sha256Domain(RAW_CALL_BINDING_HASH_DOMAIN, listOf(name, id, argumentsHash)),
        operationHash = sha256Domain(RAW_OPERATION_HASH_DOMAIN, listOf(name, argumentsHash))
    )
}

private fun ToolCall.oversizedAuditBinding(): ToolAuditBinding {
    val boundedName = name.take(MAX_OVERSIZED_AUDIT_IDENTITY_CHARS)
    val boundedId = id.take(MAX_OVERSIZED_AUDIT_IDENTITY_CHARS)
    val argumentsHash = sha256Domain(
        OVERSIZED_ARGUMENTS_HASH_DOMAIN,
        listOf(arguments.length.toString(), arguments.take(MAX_OVERSIZED_AUDIT_PREFIX_CHARS))
    )
    val toolNameHash = sha256Domain(RAW_TOOL_NAME_HASH_DOMAIN, listOf(boundedName))
    return ToolAuditBinding(
        toolNameHash = toolNameHash,
        argumentsHash = argumentsHash,
        callBindingHash = sha256Domain(RAW_CALL_BINDING_HASH_DOMAIN, listOf(boundedName, boundedId, argumentsHash)),
        operationHash = sha256Domain(RAW_OPERATION_HASH_DOMAIN, listOf(boundedName, argumentsHash))
    )
}

fun interface ToolAuditSink {
    suspend fun record(event: ToolAuditEvent)
}

object NoOpToolAuditSink : ToolAuditSink {
    override suspend fun record(event: ToolAuditEvent) = Unit
}

suspend fun ToolAuditSink.recordSafely(event: ToolAuditEvent) {
    try {
        record(event)
    } catch (throwable: CancellationException) {
        throw throwable
    } catch (_: Throwable) {
        // Audit delivery is best effort; execution authorization remains a separate fail-closed check.
    }
}

private const val IDEMPOTENCY_AUDIT_HASH_DOMAIN = "tool-audit-idempotency-v1"
private const val RAW_TOOL_NAME_HASH_DOMAIN = "tool-audit-raw-name-v1"
private const val RAW_ARGUMENTS_HASH_DOMAIN = "tool-audit-raw-arguments-v1"
private const val OVERSIZED_ARGUMENTS_HASH_DOMAIN = "tool-audit-oversized-arguments-v1"
private const val RAW_CALL_BINDING_HASH_DOMAIN = "tool-audit-raw-call-v1"
private const val RAW_OPERATION_HASH_DOMAIN = "tool-audit-raw-operation-v1"
private const val MAX_OVERSIZED_AUDIT_PREFIX_CHARS = 256
private const val MAX_OVERSIZED_AUDIT_IDENTITY_CHARS = 128
