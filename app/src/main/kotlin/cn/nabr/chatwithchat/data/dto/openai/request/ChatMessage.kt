package cn.nabr.chatwithchat.data.dto.openai.request

import cn.nabr.chatwithchat.data.dto.openai.common.MessageContent
import cn.nabr.chatwithchat.data.dto.openai.common.Role
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = ChatMessageSerializer::class)
data class ChatMessage(
    @SerialName("role")
    val role: Role,

    @SerialName("content")
    val content: List<MessageContent> = emptyList(),

    @SerialName("tool_calls")
    val toolCalls: List<ChatCompletionMessageToolCall>? = null,

    @SerialName("tool_call_id")
    val toolCallId: String? = null,

    val contentText: String? = null
)

object ChatMessageSerializer : KSerializer<ChatMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ChatMessage")

    override fun serialize(encoder: Encoder, value: ChatMessage) {
        val jsonEncoder = encoder as JsonEncoder
        val element = buildJsonObject {
            put("role", jsonEncoder.json.encodeToJsonElement(Role.serializer(), value.role))
            put(
                "content",
                when {
                    value.contentText != null -> JsonPrimitive(value.contentText)
                    value.toolCalls != null -> JsonNull
                    else -> jsonEncoder.json.encodeToJsonElement(
                        ListSerializer(MessageContent.serializer()),
                        value.content
                    )
                }
            )
            value.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                put(
                    "tool_calls",
                    jsonEncoder.json.encodeToJsonElement(
                        ListSerializer(ChatCompletionMessageToolCall.serializer()),
                        calls
                    )
                )
            }
            value.toolCallId?.takeIf { it.isNotBlank() }?.let { callId ->
                put("tool_call_id", JsonPrimitive(callId))
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ChatMessage {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement().jsonObject
        val contentElement = element["content"]
        return ChatMessage(
            role = jsonDecoder.json.decodeFromJsonElement(Role.serializer(), element.getValue("role")),
            content = when (contentElement) {
                is JsonArray -> jsonDecoder.json.decodeFromJsonElement(
                    ListSerializer(MessageContent.serializer()),
                    contentElement
                )
                else -> emptyList()
            },
            toolCalls = element["tool_calls"]?.let { calls ->
                jsonDecoder.json.decodeFromJsonElement(
                    ListSerializer(ChatCompletionMessageToolCall.serializer()),
                    calls
                )
            },
            toolCallId = element["tool_call_id"]?.jsonPrimitive?.contentOrNull,
            contentText = when (contentElement) {
                is JsonPrimitive -> contentElement.takeUnless { it is JsonNull }?.contentOrNull
                else -> null
            }
        )
    }
}
