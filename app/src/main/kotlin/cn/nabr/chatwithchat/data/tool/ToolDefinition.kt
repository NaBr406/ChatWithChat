package cn.nabr.chatwithchat.data.tool

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
        append(parameters.toSchemaJson(ToolSchemaDialect.JSON_FALLBACK))
    }

    @Serializable
    data class Parameters(
        val type: String = "object",
        val properties: Map<String, Parameter> = emptyMap(),
        val required: List<String> = emptyList(),
        val additionalProperties: Boolean = false,
        val description: String? = null
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Parameter(
        val type: String,
        val description: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val properties: Map<String, Parameter> = emptyMap(),
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val required: List<String> = emptyList(),
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val items: Parameter? = null,
        @SerialName("enum")
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val enumValues: List<String> = emptyList(),
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val additionalProperties: Boolean? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val minimum: Double? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val maximum: Double? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val minLength: Int? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val maxLength: Int? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val format: String? = null
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

        val CurrentDateTime = ToolDefinition(
            name = "current_datetime",
            description = "Returns the device's current local date, time, UTC offset, and timezone. Use this for local date or time questions. This tool does not access the network.",
            parameters = Parameters()
        )

        val DeviceLocation = ToolDefinition(
            name = "device_location",
            description = "Returns the device's current latitude, longitude, accuracy, provider, and timestamp when Android system location permission has been granted. If permission is missing, the tool returns tool_permission_denied so the user can enable the app's location permission. Use only when the latest user request explicitly asks for their current device location or location-based help. This tool does not search the web.",
            parameters = Parameters()
        )

        val AddSchedule = ToolDefinition(
            name = "add_schedule",
            description = "Add a calendar event to the device calendar. Use an ISO-8601 start time and optionally an end time or duration. This is a local external write that requires calendar permission; after the system permission is granted, ChatWithChat adds the event directly without another app approval dialog.",
            parameters = Parameters(
                properties = mapOf(
                    "title" to Parameter(
                        type = "string",
                        description = "Calendar event title.",
                        minLength = 1,
                        maxLength = 200
                    ),
                    "start_time" to Parameter(
                        type = "string",
                        description = "Event start as an ISO-8601 date-time, including timezone offset when possible.",
                        format = "date-time"
                    ),
                    "end_time" to Parameter(
                        type = "string",
                        description = "Optional event end as an ISO-8601 date-time.",
                        format = "date-time"
                    ),
                    "duration_minutes" to Parameter(
                        type = "integer",
                        description = "Optional duration in minutes when end_time is omitted.",
                        minimum = 1.0,
                        maximum = 1_440.0
                    ),
                    "description" to Parameter(
                        type = "string",
                        description = "Optional calendar event notes.",
                        maxLength = 2_000
                    ),
                    "location" to Parameter(
                        type = "string",
                        description = "Optional event location.",
                        maxLength = 500
                    ),
                    "all_day" to Parameter(
                        type = "boolean",
                        description = "Whether the event should be marked as an all-day event."
                    )
                ),
                required = listOf("title", "start_time")
            )
        )

        val SetAlarm = ToolDefinition(
            name = "set_alarm",
            description = "Set an alarm directly in the device's Clock app. Provide a local hour and minute, an optional message, and optional weekly repeat days. ChatWithChat uses the system alarm capability to create it without opening an editor or showing an additional app approval dialog.",
            parameters = Parameters(
                properties = mapOf(
                    "hour" to Parameter(
                        type = "integer",
                        description = "Local alarm hour from 0 through 23.",
                        minimum = 0.0,
                        maximum = 23.0
                    ),
                    "minute" to Parameter(
                        type = "integer",
                        description = "Alarm minute from 0 through 59.",
                        minimum = 0.0,
                        maximum = 59.0
                    ),
                    "message" to Parameter(
                        type = "string",
                        description = "Optional alarm label.",
                        maxLength = 200
                    ),
                    "days" to Parameter(
                        type = "array",
                        description = "Optional weekly repeat days using 1=Sunday through 7=Saturday.",
                        items = Parameter(
                            type = "integer",
                            minimum = 1.0,
                            maximum = 7.0
                        )
                    )
                ),
                required = listOf("hour", "minute")
            )
        )

        val BuiltIns = listOf(WebSearch, FetchUrl, CurrentDateTime, DeviceLocation, AddSchedule, SetAlarm)
    }
}
