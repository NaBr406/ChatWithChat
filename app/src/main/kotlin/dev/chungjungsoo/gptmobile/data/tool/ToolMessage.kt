package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.serialization.Serializable

@Serializable
data class ToolMessage(
    val role: Role,
    val content: String,
    val toolCall: ToolCall? = null,
    val toolResult: ToolResult? = null
) {
    @Serializable
    enum class Role {
        Model,
        Tool
    }

    companion object {
        fun modelToolCall(call: ToolCall): ToolMessage = ToolMessage(
            role = Role.Model,
            content = "Requested tool ${call.name} with id ${call.id}.",
            toolCall = call
        )

        fun toolResult(result: ToolResult): ToolMessage = ToolMessage(
            role = Role.Tool,
            content = result.content,
            toolResult = result
        )
    }
}
