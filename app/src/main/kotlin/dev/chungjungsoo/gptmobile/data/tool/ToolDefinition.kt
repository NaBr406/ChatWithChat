package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val toolProtocolJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Parameters
) {
    fun toPromptText(): String = buildString {
        appendLine("Name: $name")
        appendLine("Description: $description")
        append("Parameters: ")
        append(toolProtocolJson.encodeToString(parameters))
    }

    @Serializable
    data class Parameters(
        val type: String = "object",
        val properties: Map<String, Parameter> = emptyMap(),
        val required: List<String> = emptyList()
    )

    @Serializable
    data class Parameter(
        val type: String,
        val description: String
    )

    companion object {
        val WebSearch = ToolDefinition(
            name = "web_search",
            description = "Search the public web for recent news, changing facts, or external source material. Rewrite natural-language requests into concise search-engine queries with likely entity, topic/category, timeframe, geography/source scope, and primary or official source terms when useful. Prefer the user's language for local/regional facts. Do not use this for the user's local date, time, timezone, device state, or app settings.",
            parameters = Parameters(
                properties = mapOf(
                    "query" to Parameter(
                        type = "string",
                        description = "A concise, structured public-web search query. Include concrete dates/years, canonical names, geography, category/source terms, and official or primary-source terms when useful. Do not use clock/time-only queries."
                    )
                ),
                required = listOf("query")
            )
        )

        val FetchUrl = ToolDefinition(
            name = "fetch_url",
            description = "Fetch and extract readable text from one public web page URL.",
            parameters = Parameters(
                properties = mapOf(
                    "url" to Parameter(
                        type = "string",
                        description = "The http or https URL to fetch."
                    )
                ),
                required = listOf("url")
            )
        )

        val BuiltIns = listOf(WebSearch, FetchUrl)
    }
}
