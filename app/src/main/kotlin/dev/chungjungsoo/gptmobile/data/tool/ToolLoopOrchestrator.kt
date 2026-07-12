package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceMetadata
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

    val toolCatalog: List<ToolCatalogEntry>
        get() = toolExecutor.catalog

    fun availableToolDefinitions(includeTool: (ToolDefinition) -> Boolean): List<ToolDefinition> =
        toolExecutor.availableDefinitions(includeTool)

    fun sourceMetadata(results: List<ToolResult>): List<MessageSourceMetadata> =
        results.flatMap { result -> toolExecutor.sourceMetadata(result) }

    fun createExecutionSession(): ToolLoopExecutionSession = ToolLoopExecutionSession(
        config = config,
        policyFor = toolExecutor::policyFor
    )

    fun boundToolCalls(calls: Iterable<ToolCall>): List<ToolCall> = calls.boundedDistinctToolCalls(config)

    suspend fun runLoop(
        tools: List<ToolDefinition>,
        adapter: ToolCallingAdapter = defaultToolCallingAdapter,
        onProgress: suspend (ApiState) -> Unit = {},
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
        val executionSession = createExecutionSession()

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
            val modelOutput = adapter.parseModelOutput(modelText, config).getOrElse { throwable ->
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
                    val calls = boundToolCalls(modelOutput.calls)
                    val availableCalls = calls.selectAvailable(activeToolNames)
                    val (allowedCalls, budgetRejectedCalls) = executionSession.select(availableCalls.allowed)
                    val rejectedCalls = availableCalls.rejected + budgetRejectedCalls
                    if (calls.isEmpty() || (allowedCalls.isEmpty() && rejectedCalls.isEmpty())) {
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
                    val rawResults = executeCallsWithProgress(
                        calls = allowedCalls,
                        activeToolNames = activeToolNames,
                        onProgress = onProgress
                    ) + rejectedCalls
                    val results = executionSession.bound(rawResults)
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
        tools: List<ToolDefinition>,
        requestModel: suspend (toolPrompt: String) -> Result<String>
    ): ToolLoopResult = runLoop(tools = tools, requestModel = requestModel)

    suspend fun executeToolCalls(
        calls: List<ToolCall>,
        tools: List<ToolDefinition>,
        executionSession: ToolLoopExecutionSession = createExecutionSession(),
        onProgress: suspend (ApiState) -> Unit = {}
    ): List<ToolResult> = executeBoundedToolCalls(
        calls = boundToolCalls(calls),
        tools = tools,
        executionSession = executionSession,
        onProgress = onProgress
    )

    internal suspend fun executeBoundedToolCalls(
        calls: List<ToolCall>,
        tools: List<ToolDefinition>,
        executionSession: ToolLoopExecutionSession = createExecutionSession(),
        onProgress: suspend (ApiState) -> Unit = {}
    ): List<ToolResult> {
        val activeToolNames = tools.map { tool -> tool.name }.toSet()
        val availableCalls = calls.selectAvailable(activeToolNames)
        val (allowedCalls, budgetRejectedCalls) = executionSession.select(availableCalls.allowed)
        val rejectedCalls = availableCalls.rejected + budgetRejectedCalls
        rejectedCalls.forEach { rejected ->
            onProgress(ApiState.ToolFailed(rejected.name, rejected.content))
        }
        val rawResults = executeCallsWithProgress(
            calls = allowedCalls,
            activeToolNames = activeToolNames,
            onProgress = onProgress
        ) + rejectedCalls
        return executionSession.bound(rawResults)
    }

    private suspend fun executeCallsWithProgress(
        calls: List<ToolCall>,
        activeToolNames: Set<String>,
        onProgress: suspend (ApiState) -> Unit
    ): List<ToolResult> = calls.map { call ->
        val label = toolExecutor.progressLabel(call)
        onProgress(ApiState.ToolStarted(call.name, label))
        val result = toolExecutor.execute(call, activeToolNames, config)
        if (result.isError) {
            onProgress(ApiState.ToolFailed(call.name, result.content, result.metadata["error_code"]))
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

class ToolLoopExecutionSession internal constructor(
    config: ToolLoopConfig,
    policyFor: (String) -> ToolPolicy
) {
    private val toolBudget = ToolBudgetState(config, policyFor)
    private val resultPayloadBudget = ToolResultPayloadBudget(config)

    internal fun select(calls: List<ToolCall>): Pair<List<ToolCall>, List<ToolResult>> {
        val selection = toolBudget.select(calls)
        return selection.allowed to selection.rejected
    }

    internal fun bound(results: List<ToolResult>): List<ToolResult> = resultPayloadBudget.bound(results)
}

private class ToolResultPayloadBudget(
    private val config: ToolLoopConfig,
    usedPayloadChars: Int = 0
) {
    private var remainingPayloadChars = (
        config.maxTotalToolResultChars.toLong() - usedPayloadChars.coerceAtLeast(0).toLong()
        ).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    fun bound(results: List<ToolResult>): List<ToolResult> = results.map { result ->
        val bounded = result.boundPayload(
            ToolResultBounds(
                maxContentChars = config.maxToolResultChars.coerceAtLeast(0),
                maxStructuredContentChars = config.maxToolResultChars.coerceAtLeast(0),
                maxSourcePayloadChars = config.maxToolResultChars.coerceAtLeast(0),
                maxMetadataChars = config.maxToolResultChars.coerceAtLeast(0),
                maxTotalPayloadChars = remainingPayloadChars
            )
        ).result
        remainingPayloadChars = (remainingPayloadChars - bounded.payloadCharCount()).coerceAtLeast(0)
        bounded
    }
}
