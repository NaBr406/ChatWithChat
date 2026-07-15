package cn.nabr.chatwithchat.data.tool

internal suspend fun ToolExecutor.executeWithRegisteredTools(
    call: ToolCall,
    config: ToolLoopConfig = ToolLoopConfig.Default,
    executionContext: ToolExecutionContext? = null
): ToolResult = execute(
    call = call,
    activeToolNames = definitions.map { definition -> definition.name }.toSet(),
    config = config,
    executionContext = executionContext
)
