package cn.nabr.chatwithchat.data.dto.anthropic.common

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
@SerialName("tool_use")
data class ToolUseContent(
    @SerialName("id")
    val id: String,

    @SerialName("name")
    val name: String,

    @SerialName("input")
    val input: JsonObject
) : MessageContent()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("tool_result")
data class ToolResultContent(
    @SerialName("tool_use_id")
    val toolUseId: String,

    @SerialName("content")
    val content: String,

    @SerialName("is_error")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val isError: Boolean? = null
) : MessageContent()
