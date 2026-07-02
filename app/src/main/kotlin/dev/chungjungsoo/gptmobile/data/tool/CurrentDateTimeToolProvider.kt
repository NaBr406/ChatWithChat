package dev.chungjungsoo.gptmobile.data.tool

import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class CurrentDateTimeToolProvider(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.CurrentDateTime

    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = 1,
        maxCallsPerChat = 2,
        timeoutSeconds = 2,
        maxResultChars = 500
    )

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
        val now = ZonedDateTime.now(clock.withZone(zoneId))
        val isoDateTime = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val localTime = now.toLocalTime().withNano(0).toString()

        return ToolResult(
            callId = call.id,
            name = call.name,
            content = buildString {
                appendLine("Current local date/time:")
                appendLine("ISO: $isoDateTime")
                appendLine("Date: ${now.toLocalDate()}")
                appendLine("Time: $localTime")
                appendLine("Timezone: ${zoneId.id}")
            }.trim(),
            metadata = mapOf(
                "iso_datetime" to isoDateTime,
                "date" to now.toLocalDate().toString(),
                "time" to localTime,
                "timezone" to zoneId.id
            )
        )
    }
}
