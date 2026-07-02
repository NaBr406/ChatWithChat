package dev.chungjungsoo.gptmobile.data.dto.anthropic.request

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AnthropicTool(
    @SerialName("name")
    val name: String,

    @SerialName("description")
    val description: String,

    @SerialName("input_schema")
    val inputSchema: JsonObject
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnthropicToolChoice(
    @SerialName("type")
    val type: String,

    @SerialName("name")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val name: String? = null
) {
    companion object {
        val Auto = AnthropicToolChoice(type = "auto")
        val None = AnthropicToolChoice(type = "none")
        val Any = AnthropicToolChoice(type = "any")

        fun tool(name: String): AnthropicToolChoice = AnthropicToolChoice(
            type = "tool",
            name = name
        )
    }
}
