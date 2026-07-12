package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ToolResult(
    val callId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
    val structuredContent: JsonElement? = null,
    val sources: List<ToolSource> = emptyList()
)
