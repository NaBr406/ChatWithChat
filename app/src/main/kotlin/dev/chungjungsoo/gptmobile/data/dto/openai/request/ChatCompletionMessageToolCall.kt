package dev.chungjungsoo.gptmobile.data.dto.openai.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionMessageToolCall(
    @SerialName("id")
    val id: String,

    @SerialName("function")
    val function: ChatCompletionMessageFunctionCall,

    @SerialName("type")
    val type: String = "function"
)

@Serializable
data class ChatCompletionMessageFunctionCall(
    @SerialName("name")
    val name: String,

    @SerialName("arguments")
    val arguments: String
)
