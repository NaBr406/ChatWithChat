package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ToolExecutor(
    private val toolRegistry: ToolRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val definitions: List<ToolDefinition>
        get() = toolRegistry.definitions

    suspend fun execute(
        call: ToolCall,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): ToolResult {
        val handler = toolRegistry.handlerFor(call.name)
            ?: return call.errorResult("unknown_tool:${call.name}")
        val policy = toolRegistry.policyFor(call.name)

        return try {
            withContext(dispatcher) {
                withTimeout(config.timeoutMillis(policy)) {
                    runCatching {
                        handler.execute(call, config)
                    }.getOrElse { throwable ->
                        call.errorResult("tool_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}")
                    }.clipContent(policy.maxResultChars ?: config.maxToolResultChars)
                }
            }
        } catch (throwable: TimeoutCancellationException) {
            call.errorResult("tool_timeout:${call.name}")
        }
    }

    suspend fun executeAll(
        calls: List<ToolCall>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): List<ToolResult> = calls
        .take(config.maxToolCallsPerRound.coerceAtLeast(0))
        .map { call -> execute(call, config) }

    fun progressLabel(call: ToolCall): String = toolRegistry.progressLabel(call)

    fun availableDefinitions(includeTool: (ToolDefinition) -> Boolean): List<ToolDefinition> =
        toolRegistry.availableDefinitions(includeTool)

    fun policyFor(toolName: String): ToolPolicy = toolRegistry.policyFor(toolName)

    private fun ToolLoopConfig.timeoutMillis(policy: ToolPolicy): Long =
        (policy.timeoutSeconds ?: toolTimeoutSeconds).coerceAtLeast(0) * 1_000L

    private fun ToolResult.clipContent(maxChars: Int): ToolResult = copy(content = content.clip(maxChars))
}

internal fun ToolCall.errorResult(message: String): ToolResult = ToolResult(
    callId = id,
    name = name,
    content = message,
    isError = true
)
