package cn.nabr.chatwithchat.data.dto.anthropic.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("message_stop")
data object MessageStopResponseChunk : MessageResponseChunk()
