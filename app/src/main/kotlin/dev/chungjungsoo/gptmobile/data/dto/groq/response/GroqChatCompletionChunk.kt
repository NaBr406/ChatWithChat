package dev.chungjungsoo.gptmobile.data.dto.groq.response

import dev.chungjungsoo.gptmobile.data.dto.ProviderUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroqChatCompletionChunk(
    @SerialName("id")
    val id: String? = null,

    @SerialName("object")
    val objectType: String? = null,

    @SerialName("created")
    val created: Long? = null,

    @SerialName("model")
    val model: String? = null,

    @SerialName("choices")
    val choices: List<GroqChoice>? = null,

    @SerialName("usage")
    val usage: ProviderUsage? = null,

    @SerialName("x_groq")
    val xGroq: GroqMetadata? = null,

    @SerialName("error")
    val error: GroqErrorDetail? = null
)

@Serializable
data class GroqMetadata(
    @SerialName("usage")
    val usage: ProviderUsage? = null
)
