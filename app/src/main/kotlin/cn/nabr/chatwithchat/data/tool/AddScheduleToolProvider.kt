package cn.nabr.chatwithchat.data.tool

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ScheduleEventRequest(
    val title: String,
    val startAt: Instant,
    val endAt: Instant,
    val description: String? = null,
    val location: String? = null,
    val allDay: Boolean = false
)

fun interface ScheduleEventLauncher {
    fun launch(request: ScheduleEventRequest): Result<Unit>
}

class AndroidScheduleEventLauncher(
    context: Context
) : ScheduleEventLauncher {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @SuppressLint("MissingPermission")
    override fun launch(request: ScheduleEventRequest): Result<Unit> = runCatching {
        requireCalendarPermissions()
        val calendarId = findWritableCalendarId()
            ?: findOrCreateLocalCalendarId()
            ?: throw IllegalStateException("writable_calendar_unavailable")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, request.title)
            put(CalendarContract.Events.DTSTART, request.startAt.toEpochMilli())
            put(CalendarContract.Events.DTEND, request.endAt.toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, if (request.allDay) 1 else 0)
            request.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            request.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw IllegalStateException("calendar_event_insert_failed")
    }

    private fun requireCalendarPermissions() {
        val missingPermissions = listOf(
            Manifest.permission.WRITE_CALENDAR
        ).filter { permission ->
            ContextCompat.checkSelfPermission(appContext, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        require(missingPermissions.isEmpty()) {
            "calendar_permission_required:${missingPermissions.joinToString(",")}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun findWritableCalendarId(): Long? {
        return runCatching {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE
            )
            appContext.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val accessIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                val visibleIndex = cursor.getColumnIndex(CalendarContract.Calendars.VISIBLE)
                while (cursor.moveToNext()) {
                    if (idIndex >= 0 && accessIndex >= 0 &&
                        (visibleIndex < 0 || cursor.getInt(visibleIndex) != 0) &&
                        cursor.getInt(accessIndex) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                    ) {
                        return@runCatching cursor.getLong(idIndex)
                    }
                }
            }
            null
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun findOrCreateLocalCalendarId(): Long? {
        preferences.getLong(LOCAL_CALENDAR_ID_KEY, INVALID_CALENDAR_ID)
            .takeIf { id -> id != INVALID_CALENDAR_ID }
            ?.let { return it }
        findExistingLocalCalendarId()?.let { return it }

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_COLOR, LOCAL_CALENDAR_COLOR)
        }
        val syncAdapterUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val insertedUri = appContext.contentResolver.insert(syncAdapterUri, values) ?: return null
        return ContentUris.parseId(insertedUri).also { id ->
            preferences.edit().putLong(LOCAL_CALENDAR_ID_KEY, id).apply()
        }
    }

    @SuppressLint("MissingPermission")
    private fun findExistingLocalCalendarId(): Long? {
        return runCatching {
            val projection = arrayOf(CalendarContract.Calendars._ID)
            appContext.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(LOCAL_ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL),
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                if (idIndex >= 0 && cursor.moveToFirst()) cursor.getLong(idIndex) else null
            }
        }.getOrNull()
    }

    private companion object {
        private const val PREFERENCES_NAME = "calendar_tool"
        private const val LOCAL_CALENDAR_ID_KEY = "local_calendar_id"
        private const val INVALID_CALENDAR_ID = -1L
        private const val LOCAL_ACCOUNT_NAME = "ChatWithChat"
        private const val LOCAL_CALENDAR_NAME = "ChatWithChat"
        private const val LOCAL_CALENDAR_COLOR = 0xff1976d2.toInt()
    }
}

class AddScheduleToolProvider(
    private val launcher: ScheduleEventLauncher,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.AddSchedule

    override val settingsMetadata: ToolSettingsMetadata = ToolSettingsMetadata(
        category = ToolCategory.Utility,
        defaultEnabled = false,
        isSensitive = true,
        presentationKey = "add_schedule",
        iconKey = "event"
    )

    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy(
        effect = ToolEffect.EXTERNAL_WRITE,
        approvalPolicy = ToolApprovalPolicy.REQUIRE_SYSTEM_PERMISSION
    )

    override val permissionRequirements: List<ToolPermissionRequirement> = listOf(CalendarPermissionRequirement)

    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = 1,
        maxCallsPerChat = 4,
        timeoutSeconds = 10,
        maxResultChars = 900
    )

    override fun progressLabel(call: ToolCall): String = call.stringArgument("title")
        .getOrNull()
        ?.take(80)
        ?: definition.name

    override fun approvalArgumentSummary(call: ToolCall): String? = runCatching {
        val title = call.stringArgument("title").getOrThrow()
        val start = call.stringArgument("start_time").getOrThrow()
        "Add calendar event \"$title\" at $start"
    }.getOrNull()

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
        val request = call.parseRequest().getOrElse { throwable ->
            return call.errorResult("tool_arguments_invalid:${throwable.message}")
        }
        launcher.launch(request).getOrElse { throwable ->
            return call.errorResult(
                "add_schedule_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}"
            )
        }

        val start = request.startAt.toString()
        val end = request.endAt.toString()
        return ToolResult(
            callId = call.id,
            name = call.name,
            content = buildString {
                appendLine("Calendar event added to the device calendar.")
                appendLine("Title: ${request.title}")
                appendLine("Start: $start")
                appendLine("End: $end")
                request.location?.let { appendLine("Location: $it") }
            }.trim(),
            metadata = buildMap {
                put("title", request.title)
                put("start_time", start)
                put("end_time", end)
                put("all_day", request.allDay.toString())
                request.location?.let { put("location", it) }
            }
        )
    }

    private fun ToolCall.parseRequest(): Result<ScheduleEventRequest> = runCatching {
        val arguments = argumentsObject().getOrThrow()
        val title = stringArgument("title").getOrThrow()
        val startText = stringArgument("start_time").getOrThrow()
        val startAt = parseInstant(startText)
        val endAt = arguments["end_time"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::parseInstant)
            ?: run {
                val durationMinutes = arguments["duration_minutes"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toLongOrNull()
                    ?: DEFAULT_DURATION_MINUTES
                require(durationMinutes in 1..MAX_DURATION_MINUTES) {
                    "duration_minutes_out_of_range"
                }
                startAt.plusSeconds(durationMinutes * SECONDS_PER_MINUTE)
            }
        require(endAt > startAt) { "end_time_must_be_after_start_time" }
        val allDay = arguments["all_day"]?.jsonPrimitive?.booleanOrNull ?: false
        ScheduleEventRequest(
            title = title,
            startAt = startAt,
            endAt = endAt,
            description = optionalString(arguments, "description"),
            location = optionalString(arguments, "location"),
            allDay = allDay
        )
    }

    private fun parseInstant(value: String): Instant = runCatching {
        Instant.parse(value)
    }.recoverCatching {
        OffsetDateTime.parse(value).toInstant()
    }.recoverCatching {
        ZonedDateTime.parse(value).toInstant()
    }.recoverCatching {
        LocalDateTime.parse(value).atZone(zoneId).toInstant()
    }.recoverCatching {
        LocalDate.parse(value).atStartOfDay(zoneId).toInstant()
    }.getOrThrow()

    private fun optionalString(
        arguments: kotlinx.serialization.json.JsonObject,
        name: String
    ): String? = arguments[name]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private companion object {
        private const val DEFAULT_DURATION_MINUTES = 60L
        private const val MAX_DURATION_MINUTES = 1_440L
        private const val SECONDS_PER_MINUTE = 60L
    }
}

object UnavailableScheduleEventLauncher : ScheduleEventLauncher {
    override fun launch(request: ScheduleEventRequest): Result<Unit> =
        Result.failure(IllegalStateException("calendar_app_unavailable"))
}

private val CalendarPermissionRequirement = ToolPermissionRequirement(
    permissions = listOf(
        Manifest.permission.WRITE_CALENDAR
    ),
    label = "Calendar",
    deniedMessage = "The add_schedule tool needs calendar access. ChatWithChat will request it before retrying the calendar action. If your system does not show a prompt, grant Calendar access in the app permission manager.",
    grantMode = ToolPermissionGrantMode.ALL_OF
)
