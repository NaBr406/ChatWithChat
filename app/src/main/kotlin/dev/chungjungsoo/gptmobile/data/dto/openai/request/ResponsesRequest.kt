package dev.chungjungsoo.gptmobile.data.dto.openai.request

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Request body for OpenAI Responses API.
 * Used for reasoning models (o1, o3, etc.) to get reasoning content in streaming responses.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponsesRequest(
    @SerialName("model")
    val model: String,

    @SerialName("input")
    val input: List<ResponseInputItem>,

    @SerialName("stream")
    val stream: Boolean = true,

    @SerialName("instructions")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val instructions: String? = null,

    @SerialName("max_output_tokens")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxOutputTokens: Int? = null,

    @SerialName("temperature")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val temperature: Float? = null,

    @SerialName("top_p")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val topP: Float? = null,

    @SerialName("reasoning")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reasoning: ReasoningConfig? = null,

    @SerialName("tools")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tools: List<ResponseTool>? = null,

    @SerialName("tool_choice")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val toolChoice: ResponseToolChoice? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReasoningConfig(
    @SerialName("effort")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val effort: String? = null,

    @SerialName("summary")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val summary: String? = null
)

/**
 * Message format for Responses API input.
 * Content can be a string (text only) or a list of content parts (text + images).
 */
@Serializable(with = ResponseInputItemSerializer::class)
sealed class ResponseInputItem

@Serializable
data class ResponseInputMessage(
    @SerialName("role")
    val role: String,

    @SerialName("content")
    val content: ResponseInputContent
) : ResponseInputItem()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponseFunctionCallInputItem(
    @SerialName("call_id")
    val callId: String,

    @SerialName("name")
    val name: String,

    @SerialName("arguments")
    val arguments: String,

    @SerialName("id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val id: String? = null,

    @SerialName("status")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val status: String? = null,

    @SerialName("type")
    val type: String = "function_call"
) : ResponseInputItem()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponseFunctionCallOutputItem(
    @SerialName("call_id")
    val callId: String,

    @SerialName("output")
    val output: String,

    @SerialName("id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val id: String? = null,

    @SerialName("status")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val status: String? = null,

    @SerialName("type")
    val type: String = "function_call_output"
) : ResponseInputItem()

data class ResponsePassthroughInputItem(
    val raw: JsonObject
) : ResponseInputItem()

object ResponseInputItemSerializer : KSerializer<ResponseInputItem> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ResponseInputItem")

    override fun serialize(encoder: Encoder, value: ResponseInputItem) {
        val jsonEncoder = encoder as JsonEncoder
        val element = when (value) {
            is ResponseInputMessage -> jsonEncoder.json.encodeToJsonElement(ResponseInputMessage.serializer(), value)
            is ResponseFunctionCallInputItem -> jsonEncoder.json.encodeToJsonElement(ResponseFunctionCallInputItem.serializer(), value)
            is ResponseFunctionCallOutputItem -> jsonEncoder.json.encodeToJsonElement(ResponseFunctionCallOutputItem.serializer(), value)
            is ResponsePassthroughInputItem -> value.raw
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ResponseInputItem {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement().jsonObject
        return when (element["type"]?.jsonPrimitive?.contentOrNull) {
            "function_call" -> jsonDecoder.json.decodeFromJsonElement<ResponseFunctionCallInputItem>(element)
            "function_call_output" -> jsonDecoder.json.decodeFromJsonElement<ResponseFunctionCallOutputItem>(element)
            "message" -> jsonDecoder.json.decodeFromJsonElement<ResponseInputMessage>(element)
            else -> {
                if (element["role"] != null && element["content"] != null) {
                    jsonDecoder.json.decodeFromJsonElement<ResponseInputMessage>(element)
                } else {
                    ResponsePassthroughInputItem(element)
                }
            }
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponseTool(
    @SerialName("name")
    val name: String,

    @SerialName("description")
    val description: String,

    @SerialName("parameters")
    val parameters: JsonObject,

    @SerialName("strict")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val strict: Boolean? = null,

    @SerialName("type")
    val type: String = "function"
)

@Serializable(with = ResponseToolChoiceSerializer::class)
sealed class ResponseToolChoice {
    data object Auto : ResponseToolChoice()
    data object None : ResponseToolChoice()
    data object Required : ResponseToolChoice()

    data class Function(
        val name: String
    ) : ResponseToolChoice()
}

object ResponseToolChoiceSerializer : KSerializer<ResponseToolChoice> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ResponseToolChoice")

    override fun serialize(encoder: Encoder, value: ResponseToolChoice) {
        val jsonEncoder = encoder as JsonEncoder
        val element: JsonElement = when (value) {
            ResponseToolChoice.Auto -> JsonPrimitive("auto")
            ResponseToolChoice.None -> JsonPrimitive("none")
            ResponseToolChoice.Required -> JsonPrimitive("required")
            is ResponseToolChoice.Function -> buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("name", JsonPrimitive(value.name))
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ResponseToolChoice {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> when (element.contentOrNull) {
                "none" -> ResponseToolChoice.None
                "required" -> ResponseToolChoice.Required
                else -> ResponseToolChoice.Auto
            }
            is JsonObject -> ResponseToolChoice.Function(
                name = element["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
            else -> ResponseToolChoice.Auto
        }
    }
}

/**
 * Content can be either a simple string or a list of content parts.
 * Serializes as JSON string for Text, JSON array for Parts.
 */
@Serializable(with = ResponseInputContentSerializer::class)
sealed class ResponseInputContent {
    data class Text(val text: String) : ResponseInputContent()
    data class Parts(val parts: List<ResponseContentPart>) : ResponseInputContent()

    companion object {
        fun text(text: String): ResponseInputContent = Text(text)
        fun parts(parts: List<ResponseContentPart>): ResponseInputContent = Parts(parts)
    }
}

/**
 * Custom serializer that outputs string for Text and array for Parts.
 */
object ResponseInputContentSerializer : KSerializer<ResponseInputContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ResponseInputContent")

    override fun serialize(encoder: Encoder, value: ResponseInputContent) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is ResponseInputContent.Text -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.text))

            is ResponseInputContent.Parts -> {
                val jsonArray = jsonEncoder.json.encodeToJsonElement(
                    ListSerializer(ResponseContentPart.serializer()),
                    value.parts
                )
                jsonEncoder.encodeJsonElement(jsonArray)
            }
        }
    }

    override fun deserialize(decoder: Decoder): ResponseInputContent {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> ResponseInputContent.Text(element.jsonPrimitive.content)

            is JsonArray -> {
                val parts = jsonDecoder.json.decodeFromJsonElement(
                    ListSerializer(ResponseContentPart.serializer()),
                    element
                )
                ResponseInputContent.Parts(parts)
            }

            else -> throw IllegalArgumentException("Unexpected JSON element type")
        }
    }
}

/**
 * Content part for multi-modal input (text or image).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponseContentPart(
    @SerialName("type")
    val type: String,

    @SerialName("text")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val text: String? = null,

    @SerialName("image_url")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val imageUrl: String? = null,

    @SerialName("file_id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fileId: String? = null,

    @SerialName("detail")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val detail: String? = null
) {
    companion object {
        fun text(text: String) = ResponseContentPart(type = "input_text", text = text)
        fun image(url: String, detail: String = "auto") = ResponseContentPart(
            type = "input_image",
            imageUrl = url,
            detail = detail
        )
        fun imageFile(fileId: String, detail: String = "auto") = ResponseContentPart(
            type = "input_image",
            fileId = fileId,
            detail = detail
        )
    }
}
