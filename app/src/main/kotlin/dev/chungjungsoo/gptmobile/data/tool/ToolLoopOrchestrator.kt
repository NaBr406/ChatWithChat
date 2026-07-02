package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.tool.provider.OpenAICompatibleJsonToolAdapter
import dev.chungjungsoo.gptmobile.data.tool.provider.ToolCallingAdapter

class ToolLoopOrchestrator(
    private val toolExecutor: ToolExecutor,
    private val toolPromptBuilder: ToolPromptBuilder = ToolPromptBuilder(),
    private val jsonToolCallParser: JsonToolCallParser = JsonToolCallParser(),
    private val defaultToolCallingAdapter: ToolCallingAdapter = OpenAICompatibleJsonToolAdapter(
        toolPromptBuilder = toolPromptBuilder,
        jsonToolCallParser = jsonToolCallParser
    ),
    private val config: ToolLoopConfig = ToolLoopConfig.Default
) {
    val configuration: ToolLoopConfig
        get() = config

    val toolDefinitions: List<ToolDefinition>
        get() = toolExecutor.definitions

    fun availableToolDefinitions(includeTool: (ToolDefinition) -> Boolean): List<ToolDefinition> =
        toolExecutor.availableDefinitions(includeTool)

    suspend fun runLoop(
        adapter: ToolCallingAdapter = defaultToolCallingAdapter,
        onProgress: suspend (ApiState) -> Unit = {},
        tools: List<ToolDefinition> = toolDefinitions,
        requestModel: suspend (toolPrompt: String) -> Result<String>
    ): ToolLoopResult {
        val maxRounds = config.maxToolRounds.coerceAtLeast(0)
        if (maxRounds == 0) return ToolLoopResult.Failed("tool_loop_no_rounds")
        val activeTools = tools.distinctBy { tool -> tool.name }
        if (activeTools.isEmpty()) return ToolLoopResult.Failed("tool_loop_no_available_tools")
        val activeToolNames = activeTools.map { tool -> tool.name }.toSet()

        val scratchpad = mutableListOf<ToolMessage>()
        val allCalls = mutableListOf<ToolCall>()
        val allResults = mutableListOf<ToolResult>()
        val budget = ToolBudgetState(config, toolExecutor::policyFor)

        repeat(maxRounds) {
            val toolPrompt = adapter.buildToolPrompt(
                tools = activeTools,
                scratchpad = scratchpad,
                config = config
            )
            val modelText = requestModel(toolPrompt).getOrElse { throwable ->
                return fallbackOrFailure(
                    adapter = adapter,
                    allCalls = allCalls,
                    allResults = allResults,
                    failure = "tool_loop_model_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}"
                )
            }
            val modelOutput = adapter.parseModelOutput(modelText).getOrElse { throwable ->
                return fallbackOrFailure(
                    adapter = adapter,
                    allCalls = allCalls,
                    allResults = allResults,
                    failure = "tool_loop_parse_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}"
                )
            }

            when (modelOutput) {
                is JsonToolModelOutput.FinalAnswer -> {
                    return if (allResults.isNotEmpty()) {
                        ToolLoopResult.ToolResults(
                            calls = allCalls,
                            results = allResults,
                            finalAnswerPrompt = buildFinalAnswerPrompt(
                                adapter = adapter,
                                results = allResults,
                                draftFinalAnswer = modelOutput.content
                            )
                        )
                    } else {
                        ToolLoopResult.FinalAnswer(modelOutput.content)
                    }
                }
                is JsonToolModelOutput.ToolCalls -> {
                    val calls = modelOutput.calls
                        .distinctBy { call -> "${call.name}:${call.arguments}" }
                        .take(config.maxToolCallsPerRound.coerceAtLeast(0))
                    val availableCalls = calls.selectAvailable(activeToolNames)
                    val budgetedCalls = budget.select(availableCalls.allowed)
                    val rejectedCalls = availableCalls.rejected + budgetedCalls.rejected
                    if (calls.isEmpty() || (budgetedCalls.allowed.isEmpty() && rejectedCalls.isEmpty())) {
                        return fallbackOrFailure(
                            adapter = adapter,
                            allCalls = allCalls,
                            allResults = allResults,
                            failure = "tool_loop_no_tool_calls"
                        )
                    }

                    rejectedCalls.forEach { rejected ->
                        onProgress(ApiState.ToolFailed(rejected.name, rejected.content))
                    }
                    val results = executeCallsWithProgress(budgetedCalls.allowed, onProgress) + rejectedCalls
                    allCalls += calls
                    allResults += results
                    calls.forEach { call -> scratchpad += ToolMessage.modelToolCall(call) }
                    results.forEach { result -> scratchpad += ToolMessage.toolResult(result) }
                }
            }
        }

        return fallbackOrFailure(
            adapter = adapter,
            allCalls = allCalls,
            allResults = allResults,
            failure = "tool_loop_max_rounds_reached"
        )
    }

    suspend fun runSingleRound(
        tools: List<ToolDefinition> = toolDefinitions,
        requestModel: suspend (toolPrompt: String) -> Result<String>
    ): ToolLoopResult = runLoop(tools = tools, requestModel = requestModel)

    suspend fun executeToolCalls(
        calls: List<ToolCall>,
        tools: List<ToolDefinition> = toolDefinitions,
        onProgress: suspend (ApiState) -> Unit = {}
    ): List<ToolResult> {
        val activeToolNames = tools.map { tool -> tool.name }.toSet()
        val boundedCalls = calls
            .distinctBy { call -> "${call.name}:${call.arguments}" }
            .take(config.maxToolCallsPerRound.coerceAtLeast(0))
        val availableCalls = boundedCalls.selectAvailable(activeToolNames)
        val budgetedCalls = ToolBudgetState(config, toolExecutor::policyFor).select(availableCalls.allowed)
        val rejectedCalls = availableCalls.rejected + budgetedCalls.rejected
        rejectedCalls.forEach { rejected ->
            onProgress(ApiState.ToolFailed(rejected.name, rejected.content))
        }
        return executeCallsWithProgress(budgetedCalls.allowed, onProgress) + rejectedCalls
    }

    private suspend fun executeCallsWithProgress(
        calls: List<ToolCall>,
        onProgress: suspend (ApiState) -> Unit
    ): List<ToolResult> = calls.map { call ->
        val label = toolExecutor.progressLabel(call)
        onProgress(ApiState.ToolStarted(call.name, label))
        val result = toolExecutor.execute(call, config)
        if (result.isError) {
            onProgress(ApiState.ToolFailed(call.name, result.content))
        } else {
            onProgress(ApiState.ToolFinished(call.name, label))
        }
        result
    }

    private fun fallbackOrFailure(
        adapter: ToolCallingAdapter,
        allCalls: List<ToolCall>,
        allResults: List<ToolResult>,
        failure: String
    ): ToolLoopResult = if (allResults.isNotEmpty()) {
        ToolLoopResult.ToolResults(
            calls = allCalls,
            results = allResults,
            finalAnswerPrompt = buildFinalAnswerPrompt(adapter = adapter, results = allResults)
        )
    } else {
        ToolLoopResult.Failed(failure)
    }

    private fun buildFinalAnswerPrompt(
        adapter: ToolCallingAdapter,
        results: List<ToolResult>,
        draftFinalAnswer: String? = null
    ): String? = adapter.buildFinalAnswerPrompt(results, draftFinalAnswer, config)
}

private fun List<ToolCall>.selectAvailable(activeToolNames: Set<String>): BudgetedToolCalls {
    val allowed = mutableListOf<ToolCall>()
    val rejected = mutableListOf<ToolResult>()

    forEach { call ->
        if (call.name in activeToolNames) {
            allowed += call
        } else {
            rejected += call.errorResult("tool_unavailable:${call.name}")
        }
    }

    return BudgetedToolCalls(allowed, rejected)
}

private class ToolBudgetState(
    config: ToolLoopConfig,
    private val policyFor: (String) -> ToolPolicy
) {
    private var remainingToolCalls = config.maxToolCallsPerChat.coerceAtLeast(0)
    private val remainingToolCallsByName = mutableMapOf<String, Int>()

    fun select(calls: List<ToolCall>): BudgetedToolCalls {
        val allowed = mutableListOf<ToolCall>()
        val rejected = mutableListOf<ToolResult>()
        val requestToolCallsByName = mutableMapOf<String, Int>()

        calls.forEach { call ->
            val policy = policyFor(call.name)
            val requestToolCalls = requestToolCallsByName[call.name] ?: 0
            val remainingPolicyCalls = remainingPolicyCalls(call.name, policy)
            val rejection = when {
                remainingToolCalls <= 0 -> "tool_budget_exceeded:max_tool_calls_per_chat"
                policy.maxCallsPerRequest != null && requestToolCalls >= policy.maxCallsPerRequest.coerceAtLeast(0) ->
                    "tool_budget_exceeded:${policy.maxCallsPerRequestKey(call.name)}"
                remainingPolicyCalls != null && remainingPolicyCalls <= 0 ->
                    "tool_budget_exceeded:${policy.maxCallsPerChatKey(call.name)}"
                else -> null
            }

            if (rejection != null) {
                rejected += call.errorResult(rejection)
            } else {
                allowed += call
                remainingToolCalls -= 1
                requestToolCallsByName[call.name] = requestToolCalls + 1
                remainingPolicyCalls?.let { remaining ->
                    remainingToolCallsByName[call.name] = remaining - 1
                }
            }
        }

        return BudgetedToolCalls(allowed, rejected)
    }

    private fun remainingPolicyCalls(toolName: String, policy: ToolPolicy): Int? =
        policy.maxCallsPerChat?.coerceAtLeast(0)?.let { limit ->
            remainingToolCallsByName.getOrPut(toolName) { limit }
        }

    private fun ToolPolicy.maxCallsPerRequestKey(toolName: String): String =
        maxCallsPerRequestErrorKey ?: "max_${toolName}_calls_per_request"

    private fun ToolPolicy.maxCallsPerChatKey(toolName: String): String =
        maxCallsPerChatErrorKey ?: "max_${toolName}_calls_per_chat"
}

private data class BudgetedToolCalls(
    val allowed: List<ToolCall>,
    val rejected: List<ToolResult>
)
