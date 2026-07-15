package cn.nabr.chatwithchat.data.dto.groq.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroqErrorDetail(
    @SerialName("message")
    val message: String,

    @SerialName("type")
    val type: String? = null,

    @SerialName("code")
    val code: String? = null
)
