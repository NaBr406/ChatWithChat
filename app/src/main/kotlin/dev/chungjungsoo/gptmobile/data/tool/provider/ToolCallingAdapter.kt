package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.tool.JsonToolModelOutput
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopConfig
import dev.chungjungsoo.gptmobile.data.tool.ToolMessage
import dev.chungjungsoo.gptmobile.data.tool.ToolResult

interface ToolCallingAdapter {
    val name: String
    val supportsNativeToolCalling: Boolean

    fun renderToolDefinitions(tools: List<ToolDefinition>): String

    fun buildToolPrompt(
        tools: List<ToolDefinition>,
        scratchpad: List<ToolMessage>,
        config: ToolLoopConfig
    ): String

    fun parseModelOutput(
        rawText: String,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): Result<JsonToolModelOutput>

    fun renderToolResults(
        results: List<ToolResult>,
        config: ToolLoopConfig
    ): String?

    fun buildFinalAnswerPrompt(
        results: List<ToolResult>,
        draftFinalAnswer: String?,
        config: ToolLoopConfig
    ): String?
}
