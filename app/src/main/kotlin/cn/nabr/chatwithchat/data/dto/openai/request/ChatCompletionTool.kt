package cn.nabr.chatwithchat.data.dto.openai.request

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ChatCompletionTool(
    @SerialName("function")
    val function: ChatCompletionFunctionTool,

    @SerialName("type")
    val type: String = "function"
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatCompletionFunctionTool(
    @SerialName("name")
    val name: String,

    @SerialName("description")
    val description: String,

    @SerialName("parameters")
    val parameters: JsonObject,

    @SerialName("strict")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val strict: Boolean? = null
)

@Serializable(with = ChatCompletionToolChoiceSerializer::class)
sealed class ChatCompletionToolChoice {
    data object Auto : ChatCompletionToolChoice()
    data object None : ChatCompletionToolChoice()
    data object Required : ChatCompletionToolChoice()

    data class Function(
        val name: String
    ) : ChatCompletionToolChoice()
}

object ChatCompletionToolChoiceSerializer : KSerializer<ChatCompletionToolChoice> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ChatCompletionToolChoice")

    override fun serialize(encoder: Encoder, value: ChatCompletionToolChoice) {
        val jsonEncoder = encoder as JsonEncoder
        val element: JsonElement = when (value) {
            ChatCompletionToolChoice.Auto -> JsonPrimitive("auto")
            ChatCompletionToolChoice.None -> JsonPrimitive("none")
            ChatCompletionToolChoice.Required -> JsonPrimitive("required")
            is ChatCompletionToolChoice.Function -> buildJsonObject {
                put("type", JsonPrimitive("function"))
                put(
                    "function",
                    buildJsonObject {
                        put("name", JsonPrimitive(value.name))
                    }
                )
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ChatCompletionToolChoice {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> when (element.contentOrNull) {
                "none" -> ChatCompletionToolChoice.None
                "required" -> ChatCompletionToolChoice.Required
                else -> ChatCompletionToolChoice.Auto
            }
            is JsonObject -> ChatCompletionToolChoice.Function(
                name = element["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            )
            else -> ChatCompletionToolChoice.Auto
        }
    }
}
