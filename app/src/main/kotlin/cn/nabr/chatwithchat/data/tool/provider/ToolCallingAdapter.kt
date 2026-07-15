package cn.nabr.chatwithchat.data.tool.provider

import cn.nabr.chatwithchat.data.tool.JsonToolModelOutput
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolMessage
import cn.nabr.chatwithchat.data.tool.ToolResult

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
