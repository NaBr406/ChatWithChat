package dev.chungjungsoo.gptmobile.data.model

data class LastSelectedModel(
    val platformUid: String,
    val model: String,
    val reasoningMode: ReasoningMode = ReasoningMode.AUTO
)
