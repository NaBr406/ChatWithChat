package dev.chungjungsoo.gptmobile.data.tool

class ToolRegistry(
    val definitions: List<ToolDefinition>,
    private val handlers: Map<String, ToolHandler>
) {
    fun handlerFor(toolName: String): ToolHandler? = handlers[toolName]
}

fun interface ToolHandler {
    suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult
}
