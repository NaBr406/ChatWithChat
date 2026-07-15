package cn.nabr.chatwithchat.data.dto.anthropic.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("error")
data class ErrorResponseChunk(

    @SerialName("error")
    val error: ErrorDetail
) : MessageResponseChunk()
