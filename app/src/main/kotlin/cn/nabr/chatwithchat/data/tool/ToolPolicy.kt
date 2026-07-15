package cn.nabr.chatwithchat.data.tool

data class ToolPolicy(
    val maxCallsPerRequest: Int? = null,
    val maxCallsPerChat: Int? = null,
    val timeoutSeconds: Long? = null,
    val maxResultChars: Int? = null,
    val maxCallsPerRequestErrorKey: String? = null,
    val maxCallsPerChatErrorKey: String? = null
) {
    companion object {
        fun default(): ToolPolicy = ToolPolicy()
    }
}
