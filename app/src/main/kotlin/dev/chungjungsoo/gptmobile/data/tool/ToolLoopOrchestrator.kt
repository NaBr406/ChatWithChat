package dev.chungjungsoo.gptmobile.data.tool

class ToolLoopOrchestrator(
    private val toolExecutor: ToolExecutor,
    private val toolPromptBuilder: ToolPromptBuilder = ToolPromptBuilder(),
    private val jsonToolCallParser: JsonToolCallParser = JsonToolCallParser(),
    private val config: ToolLoopConfig = ToolLoopConfig.Default
) {
    suspend fun runSingleRound(
        requestModel: suspend (toolPrompt: String) -> Result<String>
    ): ToolLoopResult {
        val toolPrompt = toolPromptBuilder.buildJsonFallbackPrompt(
            tools = toolExecutor.definitions,
            config = config
        )
        val modelText = requestModel(toolPrompt).getOrElse { throwable ->
            return ToolLoopResult.Failed("tool_loop_model_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}")
        }
        val modelOutput = jsonToolCallParser.parse(modelText).getOrElse { throwable ->
            return ToolLoopResult.Failed("tool_loop_parse_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}")
        }

        return when (modelOutput) {
            is JsonToolModelOutput.FinalAnswer -> ToolLoopResult.FinalAnswer(modelOutput.content)
            is JsonToolModelOutput.ToolCalls -> {
                val calls = modelOutput.calls.take(config.maxToolCallsPerRound.coerceAtLeast(0))
                val results = toolExecutor.executeAll(calls, config)
                ToolLoopResult.ToolResults(
                    calls = calls,
                    results = results,
                    finalAnswerPrompt = buildFinalAnswerPrompt(results)
                )
            }
        }
    }

    private fun buildFinalAnswerPrompt(results: List<ToolResult>): String? {
        val formattedResults = toolPromptBuilder.formatToolResults(results, config) ?: return null
        return buildString {
            appendLine("Tool results are available for the latest user request.")
            appendLine("Use them only when relevant. If you use web sources, cite the source URLs in the answer.")
            appendLine()
            append(formattedResults)
        }.trim()
    }
}
