package cn.nabr.chatwithchat.data.dto.groq.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroqChoice(
    @SerialName("index")
    val index: Int,

    @SerialName("delta")
    val delta: GroqDelta? = null,

    @SerialName("message")
    val message: GroqMessage? = null,

    @SerialName("finish_reason")
    val finishReason: String? = null
)
