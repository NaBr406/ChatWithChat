package cn.nabr.chatwithchat.data.dto.anthropic.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UsageDelta(

    @SerialName("output_tokens")
    val outputTokens: Int
)
