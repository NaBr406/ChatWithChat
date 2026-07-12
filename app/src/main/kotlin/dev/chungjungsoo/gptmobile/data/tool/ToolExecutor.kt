package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ToolExecutor(
    private val toolRegistry: ToolRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val permissionChecker: ToolPermissionChecker = AlwaysGrantedToolPermissionChecker,
    private val argumentsValidator: ToolArgumentsValidator = ToolArgumentsValidator(),
    private val approvalAuthority: ToolApprovalAuthority = ToolApprovalAuthority(),
    private val executionContextFactory: ToolExecutionContextFactory = ToolExecutionContextFactory(),
    private val auditSink: ToolAuditSink = NoOpToolAuditSink
) {
    val definitions: List<ToolDefinition>
        get() = toolRegistry.definitions

    val catalog: List<ToolCatalogEntry>
        get() = toolRegistry.catalog

    suspend fun execute(
        call: ToolCall,
        activeToolNames: Set<String>,
        config: ToolLoopConfig = ToolLoopConfig.Default,
        executionContext: ToolExecutionContext? = null
    ): ToolResult {
        if (call.name !in activeToolNames) {
            return call.errorResult("tool_unavailable:${call.name}", TOOL_UNAVAILABLE)
                .boundForExecution(config.maxToolResultChars)
        }
        if (toolRegistry.handlerFor(call.name) == null) {
            return call.errorResult("unknown_tool:${call.name}", UNKNOWN_TOOL)
                .boundForExecution(config.maxToolResultChars)
        }
        val definition = toolRegistry.definitionFor(call.name)
            ?: return call.errorResult("unknown_tool:${call.name}", UNKNOWN_TOOL)
                .boundForExecution(config.maxToolResultChars)
        val policy = toolRegistry.policyFor(call.name)
        val securityPolicy = toolRegistry.securityPolicyFor(call.name)
        val maxResultChars = policy.maxResultChars ?: config.maxToolResultChars
        val auditBinding = ToolAuditEvent.bindingFor(call, config.maxToolArgumentChars).getOrNull()

        val validation = argumentsValidator.validate(
            definition = definition,
            arguments = call.arguments,
            maxArgumentChars = config.maxToolArgumentChars
        )
        if (!validation.isValid) {
            audit(ToolAuditStatus.ATTEMPTED, null, auditBinding)
            audit(ToolAuditStatus.FAILED, null, auditBinding)
            return call.invalidArgumentsResult(validation.violations)
                .boundForExecution(maxResultChars)
        }

        val trustedContext = executionContext ?: executionContextFactory.create(call).getOrNull()
        if (trustedContext == null) {
            audit(ToolAuditStatus.ATTEMPTED, null, auditBinding)
            audit(ToolAuditStatus.APPROVAL_INVALID, null, auditBinding)
            return call.errorResult(TOOL_EXECUTION_CONTEXT_INVALID, TOOL_EXECUTION_CONTEXT_INVALID)
                .boundForExecution(maxResultChars)
        }
        val isTrustedContextValid = trustedContext.matchesOperation(call)
        audit(ToolAuditStatus.ATTEMPTED, trustedContext.takeIf { isTrustedContextValid }, auditBinding)
        if (!isTrustedContextValid) {
            audit(ToolAuditStatus.APPROVAL_INVALID, null, auditBinding)
            return call.approvalResult(ToolApprovalStatus.INVALID)
                .boundForExecution(maxResultChars)
        }

        return try {
            val missingPermissions = permissionChecker.missingRequirements(toolRegistry.permissionRequirementsFor(call.name))
            if (missingPermissions.isNotEmpty()) {
                throw ToolPermissionDeniedException(call.name, missingPermissions)
            }

            if (securityPolicy.approvalPolicy == ToolApprovalPolicy.REQUIRE_EACH_CALL) {
                val approval = approvalAuthority.evaluate(call, trustedContext.approval)
                if (!approval.isApproved) {
                    audit(approval.status.auditStatus(), trustedContext, auditBinding)
                    return call.approvalResult(approval.status)
                        .boundForExecution(maxResultChars)
                }
                audit(ToolAuditStatus.APPROVED, trustedContext, auditBinding)
            }

            val result = withContext(dispatcher) {
                withTimeout(config.timeoutMillis(policy)) {
                    runCatching {
                        toolRegistry.execute(call, config, trustedContext)
                            ?: call.errorResult("unknown_tool:${call.name}", UNKNOWN_TOOL)
                    }.getOrElse { throwable ->
                        if (throwable is CancellationException || throwable is ToolPermissionDeniedException) throw throwable
                        call.errorResult(
                            "tool_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}",
                            TOOL_FAILED
                        )
                    }.boundForExecution(maxResultChars)
                }
            }
            audit(
                status = if (result.isError) ToolAuditStatus.FAILED else ToolAuditStatus.EXECUTED,
                executionContext = trustedContext,
                binding = auditBinding
            )
            result
        } catch (throwable: ToolPermissionDeniedException) {
            audit(ToolAuditStatus.FAILED, trustedContext, auditBinding)
            call.permissionDeniedResult(throwable).boundForExecution(maxResultChars)
        } catch (throwable: TimeoutCancellationException) {
            audit(ToolAuditStatus.FAILED, trustedContext, auditBinding)
            call.errorResult("tool_timeout:${call.name}", TOOL_TIMEOUT)
                .boundForExecution(maxResultChars)
        }
    }

    suspend fun executeAll(
        calls: List<ToolCall>,
        activeToolNames: Set<String>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): List<ToolResult> = calls
        .take(config.maxToolCallsPerRound.coerceAtLeast(0))
        .map { call -> execute(call, activeToolNames, config) }

    fun progressLabel(call: ToolCall): String = toolRegistry.progressLabel(call)

    fun availableDefinitions(includeTool: (ToolDefinition) -> Boolean): List<ToolDefinition> =
        toolRegistry.availableDefinitions(includeTool)

    fun policyFor(toolName: String): ToolPolicy = toolRegistry.policyFor(toolName)

    fun sourceMetadata(result: ToolResult): List<MessageSourceMetadata> = toolRegistry.sourceMetadata(result)

    private suspend fun audit(
        status: ToolAuditStatus,
        executionContext: ToolExecutionContext?,
        binding: ToolAuditBinding?
    ) {
        binding ?: return
        ToolAuditEvent.create(binding, status, executionContext)
            .getOrNull()
            ?.let { event -> auditSink.recordSafely(event) }
    }

    private fun ToolLoopConfig.timeoutMillis(policy: ToolPolicy): Long =
        (policy.timeoutSeconds ?: toolTimeoutSeconds).coerceAtLeast(0) * 1_000L

    private fun ToolResult.boundForExecution(maxChars: Int): ToolResult {
        val contentLimit = maxChars.coerceAtLeast(0)
        val auxiliaryLimit = minOf(contentLimit, MAX_AUXILIARY_RESULT_CHARS)
        val totalLimit = (contentLimit.toLong() + auxiliaryLimit.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return boundPayload(
            ToolResultBounds(
                maxContentChars = contentLimit,
                maxStructuredContentChars = contentLimit,
                maxSourcePayloadChars = auxiliaryLimit,
                maxMetadataChars = auxiliaryLimit,
                maxTotalPayloadChars = totalLimit
            )
        ).result
    }
}

internal fun ToolCall.errorResult(
    message: String,
    errorCode: String? = null
): ToolResult = ToolResult(
    callId = id,
    name = name,
    content = message,
    isError = true,
    metadata = errorCode?.let { code -> mapOf("error_code" to code) }.orEmpty()
)

private fun ToolCall.invalidArgumentsResult(violations: List<ToolArgumentViolation>): ToolResult {
    val boundedViolations = violations.take(MAX_ARGUMENT_VIOLATIONS_IN_RESULT)
    val content = buildJsonObject {
        put("error", JsonPrimitive(TOOL_ARGUMENTS_INVALID))
        put("tool", JsonPrimitive(name.take(MAX_TOOL_NAME_CHARS)))
        put(
            "violations",
            JsonArray(
                boundedViolations.map { violation ->
                    buildJsonObject {
                        put("path", JsonPrimitive(violation.path))
                        put("code", JsonPrimitive(violation.code))
                        put("message", JsonPrimitive(violation.message))
                    }
                }
            )
        )
    }.toString()
    return ToolResult(
        callId = id,
        name = name,
        content = content,
        isError = true,
        metadata = mapOf(
            "error_code" to TOOL_ARGUMENTS_INVALID,
            "violation_count" to violations.size.toString()
        )
    )
}

private fun ToolCall.approvalResult(status: ToolApprovalStatus): ToolResult {
    val errorCode = status.recoverableErrorCode ?: TOOL_APPROVAL_INVALID
    val content = buildJsonObject {
        put("error", JsonPrimitive(errorCode))
        put("tool", JsonPrimitive(name.take(MAX_TOOL_NAME_CHARS)))
        put(
            "message",
            JsonPrimitive(
                when (status) {
                    ToolApprovalStatus.DENIED -> "The user denied this tool action."
                    ToolApprovalStatus.MISSING -> "This tool action requires user approval before execution."
                    ToolApprovalStatus.INVALID -> "The approval does not match this tool call."
                    ToolApprovalStatus.APPROVED -> "The approval state is invalid."
                }
            )
        )
    }.toString()
    return ToolResult(
        callId = id,
        name = name,
        content = content,
        isError = true,
        metadata = mapOf("error_code" to errorCode)
    )
}

private fun ToolApprovalStatus.auditStatus(): ToolAuditStatus = when (this) {
    ToolApprovalStatus.APPROVED -> ToolAuditStatus.APPROVED
    ToolApprovalStatus.DENIED -> ToolAuditStatus.DENIED
    ToolApprovalStatus.MISSING -> ToolAuditStatus.APPROVAL_MISSING
    ToolApprovalStatus.INVALID -> ToolAuditStatus.APPROVAL_INVALID
}

private const val TOOL_ARGUMENTS_INVALID = "tool_arguments_invalid"
private const val TOOL_APPROVAL_INVALID = "tool_approval_invalid"
private const val TOOL_EXECUTION_CONTEXT_INVALID = "tool_execution_context_invalid"
private const val TOOL_FAILED = "tool_failed"
private const val TOOL_TIMEOUT = "tool_timeout"
private const val TOOL_UNAVAILABLE = "tool_unavailable"
private const val UNKNOWN_TOOL = "unknown_tool"
private const val MAX_ARGUMENT_VIOLATIONS_IN_RESULT = 8
private const val MAX_TOOL_NAME_CHARS = 64
private const val MAX_AUXILIARY_RESULT_CHARS = 2_048
