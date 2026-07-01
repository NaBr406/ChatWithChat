package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.tool.provider.OpenAICompatibleJsonToolAdapter
import dev.chungjungsoo.gptmobile.data.tool.provider.ToolCallingAdapter
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

    suspend fun runLoop(
        adapter: ToolCallingAdapter = defaultToolCallingAdapter,
        onProgress: suspend (ApiState) -> Unit = {},
        requestModel: suspend (toolPrompt: String) -> Result<String>
    ): ToolLoopResult {
        val maxRounds = config.maxToolRounds.coerceAtLeast(0)
        if (maxRounds == 0) return ToolLoopResult.Failed("tool_loop_no_rounds")

        val scratchpad = mutableListOf<ToolMessage>()
        val allCalls = mutableListOf<ToolCall>()
        val allResults = mutableListOf<ToolResult>()
        val budget = ToolBudgetState(config)

        repeat(maxRounds) {
            val toolPrompt = adapter.buildToolPrompt(
                tools = toolExecutor.definitions,
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
                    val budgetedCalls = budget.select(calls)
                    if (calls.isEmpty() || (budgetedCalls.allowed.isEmpty() && budgetedCalls.rejected.isEmpty())) {
                        return fallbackOrFailure(
                            adapter = adapter,
                            allCalls = allCalls,
                            allResults = allResults,
                            failure = "tool_loop_no_tool_calls"
                        )
                    }

                    budgetedCalls.rejected.forEach { rejected ->
                        onProgress(ApiState.ToolFailed(rejected.name, rejected.content))
                    }
                    val results = executeCallsWithProgress(budgetedCalls.allowed, onProgress) + budgetedCalls.rejected
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
        requestModel: suspend (toolPrompt: String) -> Result<String>
    ): ToolLoopResult = runLoop(requestModel = requestModel)

    suspend fun executeToolCalls(
        calls: List<ToolCall>,
        onProgress: suspend (ApiState) -> Unit = {}
    ): List<ToolResult> = calls
        .distinctBy { call -> "${call.name}:${call.arguments}" }
        .take(config.maxToolCallsPerRound.coerceAtLeast(0))
        .let { boundedCalls -> executeCallsWithProgress(boundedCalls, onProgress) }

    private suspend fun executeCallsWithProgress(
        calls: List<ToolCall>,
        onProgress: suspend (ApiState) -> Unit
    ): List<ToolResult> = calls.map { call ->
        val label = call.progressLabel()
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

    private fun ToolCall.progressLabel(): String {
        val arguments = argumentsObject().getOrNull()
        val label = when (name) {
            ToolDefinition.WebSearch.name -> arguments
                ?.get("query")
                ?.jsonPrimitive
                ?.contentOrNull
            ToolDefinition.FetchUrl.name -> arguments
                ?.get("url")
                ?.jsonPrimitive
                ?.contentOrNull
            else -> null
        }

        return label
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: name
    }
}

private class ToolBudgetState(config: ToolLoopConfig) {
    private var remainingToolCalls = config.maxToolCallsPerChat.coerceAtLeast(0)
    private var remainingSearchQueries = config.maxSearchQueriesPerChat.coerceAtLeast(0)
    private var remainingFetchedUrls = config.maxFetchedUrlsPerChat.coerceAtLeast(0)
    private val maxSearchQueriesPerRequest = config.maxSearchQueriesPerRequest.coerceAtLeast(0)
    private val maxFetchedUrlsPerRequest = config.maxFetchedUrlsPerRequest.coerceAtLeast(0)

    fun select(calls: List<ToolCall>): BudgetedToolCalls {
        val allowed = mutableListOf<ToolCall>()
        val rejected = mutableListOf<ToolResult>()
        var requestSearchQueries = 0
        var requestFetchedUrls = 0

        calls.forEach { call ->
            val rejection = when {
                remainingToolCalls <= 0 -> "tool_budget_exceeded:max_tool_calls_per_chat"
                call.name == ToolDefinition.WebSearch.name && requestSearchQueries >= maxSearchQueriesPerRequest ->
                    "tool_budget_exceeded:max_search_queries_per_request"
                call.name == ToolDefinition.WebSearch.name && remainingSearchQueries <= 0 ->
                    "tool_budget_exceeded:max_search_queries_per_chat"
                call.name == ToolDefinition.FetchUrl.name && requestFetchedUrls >= maxFetchedUrlsPerRequest ->
                    "tool_budget_exceeded:max_fetched_urls_per_request"
                call.name == ToolDefinition.FetchUrl.name && remainingFetchedUrls <= 0 ->
                    "tool_budget_exceeded:max_fetched_urls_per_chat"
                else -> null
            }

            if (rejection != null) {
                rejected += call.errorResult(rejection)
            } else {
                allowed += call
                remainingToolCalls -= 1
                when (call.name) {
                    ToolDefinition.WebSearch.name -> {
                        remainingSearchQueries -= 1
                        requestSearchQueries += 1
                    }
                    ToolDefinition.FetchUrl.name -> {
                        remainingFetchedUrls -= 1
                        requestFetchedUrls += 1
                    }
                }
            }
        }

        return BudgetedToolCalls(allowed, rejected)
    }
}

private data class BudgetedToolCalls(
    val allowed: List<ToolCall>,
    val rejected: List<ToolResult>
)
