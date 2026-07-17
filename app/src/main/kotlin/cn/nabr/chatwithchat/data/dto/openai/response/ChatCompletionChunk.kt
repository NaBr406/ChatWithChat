package cn.nabr.chatwithchat.data.dto.openai.response

import cn.nabr.chatwithchat.data.dto.ProviderUsage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ChatCompletionChunk(
    @SerialName("id")
    val id: String? = null,

    @SerialName("object")
    val objectType: String? = null,

    @SerialName("created")
    val created: Long? = null,

    @SerialName("model")
    val model: String? = null,

    @SerialName("choices")
    val choices: List<Choice>? = null,

    @SerialName("usage")
    val usage: ProviderUsage? = null,

    @SerialName("error")
    val error: ErrorDetail? = null
)

@Serializable
data class Choice(
    @SerialName("index")
    val index: Int,

    @SerialName("delta")
    val delta: Delta,

    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class Delta(
    @SerialName("role")
    val role: String? = null,

    @SerialName("content")
    val content: String? = null,

    @SerialName("reasoning_content")
    @Serializable(with = LenientReasoningTextSerializer::class)
    val reasoningContent: String? = null,

    @SerialName("reasoning")
    @Serializable(with = LenientReasoningTextSerializer::class)
    val reasoning: String? = null,

    @SerialName("tool_calls")
    val toolCalls: List<ChatCompletionToolCallDelta>? = null
)

internal object LenientReasoningTextSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = "LenientReasoningText",
        kind = PrimitiveKind.STRING
    )

    override fun serialize(encoder: Encoder, value: String?) {
        val jsonEncoder = encoder as JsonEncoder
        jsonEncoder.encodeJsonElement(value?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as JsonDecoder
        return (jsonDecoder.decodeJsonElement() as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
    }
}

@Serializable
data class ChatCompletionToolCallDelta(
    @SerialName("index")
    val index: Int? = null,

    @SerialName("id")
    val id: String? = null,

    @SerialName("type")
    val type: String? = null,

    @SerialName("function")
    val function: ChatCompletionFunctionCallDelta? = null
)

@Serializable
data class ChatCompletionFunctionCallDelta(
    @SerialName("name")
    val name: String? = null,

    @SerialName("arguments")
    val arguments: String? = null
)

@Serializable
data class ErrorDetail(
    @SerialName("message")
    val message: String,

    @SerialName("type")
    val type: String? = null,

    @SerialName("code")
    val code: String? = null
)
