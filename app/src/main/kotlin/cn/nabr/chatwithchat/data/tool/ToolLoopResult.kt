package cn.nabr.chatwithchat.data.tool

sealed class ToolLoopResult {
    data class FinalAnswer(
        val content: String
    ) : ToolLoopResult()

    data class ToolResults(
        val calls: List<ToolCall>,
        val results: List<ToolResult>,
        val finalAnswerPrompt: String?
    ) : ToolLoopResult()

    data class Failed(
        val message: String,
        val hadToolInteraction: Boolean = false
    ) : ToolLoopResult()
}
