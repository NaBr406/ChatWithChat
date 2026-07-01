package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class ToolLoopOrchestrator(
    private val toolExecutor: ToolExecutor,
    private val toolPromptBuilder: ToolPromptBuilder = ToolPromptBuilder(),
    private val jsonToolCallParser: JsonToolCallParser = JsonToolCallParser(),
    private val config: ToolLoopConfig = ToolLoopConfig.Default
) {
    suspend fun runLoop(
        onProgress: suspend (ApiState) -> Unit = {},
        requestModel: suspend (toolPrompt: String) -> Result<String>
    ): ToolLoopResult {
        val maxRounds = config.maxToolRounds.coerceAtLeast(0)
        if (maxRounds == 0) return ToolLoopResult.Failed("tool_loop_no_rounds")

        val scratchpad = mutableListOf<ToolMessage>()
        val allCalls = mutableListOf<ToolCall>()
        val allResults = mutableListOf<ToolResult>()

        repeat(maxRounds) {
            val toolPrompt = toolPromptBuilder.buildJsonFallbackPrompt(
                tools = toolExecutor.definitions,
                scratchpad = scratchpad,
                config = config
            )
            val modelText = requestModel(toolPrompt).getOrElse { throwable ->
                return fallbackOrFailure(
                    allCalls = allCalls,
                    allResults = allResults,
                    failure = "tool_loop_model_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}"
                )
            }
            val modelOutput = jsonToolCallParser.parse(modelText).getOrElse { throwable ->
                return fallbackOrFailure(
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
                    if (calls.isEmpty()) {
                        return fallbackOrFailure(
                            allCalls = allCalls,
                            allResults = allResults,
                            failure = "tool_loop_no_tool_calls"
                        )
                    }

                    val results = executeCallsWithProgress(calls, onProgress)
                    allCalls += calls
                    allResults += results
                    calls.forEach { call -> scratchpad += ToolMessage.modelToolCall(call) }
                    results.forEach { result -> scratchpad += ToolMessage.toolResult(result) }
                }
            }
        }

        return fallbackOrFailure(
            allCalls = allCalls,
            allResults = allResults,
            failure = "tool_loop_max_rounds_reached"
        )
    }

    suspend fun runSingleRound(
        requestModel: suspend (toolPrompt: String) -> Result<String>
    ): ToolLoopResult = runLoop(requestModel = requestModel)

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
        allCalls: List<ToolCall>,
        allResults: List<ToolResult>,
        failure: String
    ): ToolLoopResult = if (allResults.isNotEmpty()) {
        ToolLoopResult.ToolResults(
            calls = allCalls,
            results = allResults,
            finalAnswerPrompt = buildFinalAnswerPrompt(results = allResults)
        )
    } else {
        ToolLoopResult.Failed(failure)
    }

    private fun buildFinalAnswerPrompt(
        results: List<ToolResult>,
        draftFinalAnswer: String? = null
    ): String? {
        val formattedResults = toolPromptBuilder.formatToolResults(results, config) ?: return null
        return buildString {
            appendLine("Tool results are available for the latest user request.")
            appendLine("Use them only when relevant. If you use web sources, cite the source URLs in the answer.")
            draftFinalAnswer?.trim()?.takeIf { it.isNotBlank() }?.let { draft ->
                appendLine()
                appendLine("The tool loop drafted this final answer. Use it as guidance, but answer naturally:")
                appendLine(draft.clip(config.maxToolResultChars))
            }
            appendLine()
            append(formattedResults)
        }.trim()
    }

    private fun String.clip(maxChars: Int): String {
        val boundedMax = maxChars.coerceAtLeast(0)
        if (length <= boundedMax) return this
        return take(boundedMax).trimEnd()
    }

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
