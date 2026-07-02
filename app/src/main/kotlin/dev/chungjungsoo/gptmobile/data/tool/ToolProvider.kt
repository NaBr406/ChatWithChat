package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceMetadata

interface ToolProvider {
    val definition: ToolDefinition

    val policy: ToolPolicy
        get() = ToolPolicy.default()

    fun progressLabel(call: ToolCall): String = definition.name

    suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult

    fun sourceMetadata(result: ToolResult): List<MessageSourceMetadata> = emptyList()
}
