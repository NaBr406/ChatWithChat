package cn.nabr.chatwithchat.data.dto.groq.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroqMessage(
    @SerialName("role")
    val role: String? = null,

    @SerialName("content")
    val content: String? = null,

    @SerialName("reasoning")
    val reasoning: String? = null
)
