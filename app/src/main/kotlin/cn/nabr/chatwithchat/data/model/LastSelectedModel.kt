package cn.nabr.chatwithchat.data.model

data class LastSelectedModel(
    val platformUid: String,
    val model: String,
    val reasoningMode: ReasoningMode = ReasoningMode.AUTO
)
