package cn.nabr.chatwithchat.data.tool

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class AlarmRequest(
    val hour: Int,
    val minute: Int,
    val message: String? = null,
    val days: List<Int> = emptyList()
)

fun interface AlarmLauncher {
    fun launch(request: AlarmRequest): Result<Unit>
}

class AndroidAlarmLauncher(
    context: Context
) : AlarmLauncher {
    private val appContext = context.applicationContext

    override fun launch(request: AlarmRequest): Result<Unit> = runCatching {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, request.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, request.minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            request.message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            if (request.days.isNotEmpty()) {
                putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(request.days))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            appContext.startActivity(intent)
        } catch (exception: ActivityNotFoundException) {
            throw IllegalStateException("clock_app_unavailable", exception)
        }
    }
}

class SetAlarmToolProvider(
    private val launcher: AlarmLauncher
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.SetAlarm

    override val settingsMetadata: ToolSettingsMetadata = ToolSettingsMetadata(
        category = ToolCategory.Utility,
        defaultEnabled = false,
        isSensitive = true,
        presentationKey = "set_alarm",
        iconKey = "alarm"
    )

    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy(
        effect = ToolEffect.EXTERNAL_WRITE,
        approvalPolicy = ToolApprovalPolicy.REQUIRE_SYSTEM_PERMISSION
    )

    override val permissionRequirements: List<ToolPermissionRequirement> = listOf(AlarmPermissionRequirement)

    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = 1,
        maxCallsPerChat = 4,
        timeoutSeconds = 10,
        maxResultChars = 700
    )

    override fun progressLabel(call: ToolCall): String = runCatching {
        val arguments = call.argumentsObject().getOrThrow()
        val hour = arguments["hour"]?.jsonPrimitive?.content ?: return@runCatching definition.name
        val minute = arguments["minute"]?.jsonPrimitive?.content ?: return@runCatching definition.name
        "%02d:%02d".format(hour.toInt(), minute.toInt())
    }.getOrDefault(definition.name)

    override fun approvalArgumentSummary(call: ToolCall): String? = runCatching {
        val label = call.stringArgument("message").getOrNull()
        val arguments = call.argumentsObject().getOrThrow()
        val hour = arguments["hour"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: error("hour_required")
        val minute = arguments["minute"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: error("minute_required")
        buildString {
            append("Set alarm for %02d:%02d".format(hour, minute))
            label?.let { append(" (\"$it\")") }
        }
    }.getOrNull()

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
        val request = call.parseRequest().getOrElse { throwable ->
            return call.errorResult("tool_arguments_invalid:${throwable.message}")
        }
        launcher.launch(request).getOrElse { throwable ->
            return call.errorResult(
                "set_alarm_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}"
            )
        }

        return ToolResult(
            callId = call.id,
            name = call.name,
            content = buildString {
                appendLine("Alarm set for %02d:%02d.".format(request.hour, request.minute))
                request.message?.let { appendLine("Label: $it") }
                if (request.days.isNotEmpty()) appendLine("Weekly days: ${request.days.joinToString(",")}")
            }.trim(),
            metadata = buildMap {
                put("hour", request.hour.toString())
                put("minute", request.minute.toString())
                request.message?.let { put("message", it) }
                if (request.days.isNotEmpty()) put("days", request.days.joinToString(","))
            }
        )
    }

    private fun ToolCall.parseRequest(): Result<AlarmRequest> = runCatching {
        val arguments = argumentsObject().getOrThrow()
        val hour = arguments["hour"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: error("hour_required")
        val minute = arguments["minute"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: error("minute_required")
        require(hour in 0..23) { "hour_out_of_range" }
        require(minute in 0..59) { "minute_out_of_range" }
        val days = arguments["days"]?.jsonArray?.map { element ->
            element.jsonPrimitive.content.toIntOrNull() ?: error("day_invalid")
        }.orEmpty().distinct()
        require(days.all { day -> day in 1..7 }) { "day_out_of_range" }
        AlarmRequest(
            hour = hour,
            minute = minute,
            message = arguments["message"]?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() },
            days = days
        )
    }
}

object UnavailableAlarmLauncher : AlarmLauncher {
    override fun launch(request: AlarmRequest): Result<Unit> =
        Result.failure(IllegalStateException("clock_app_unavailable"))
}

private val AlarmPermissionRequirement = ToolPermissionRequirement(
    permissions = listOf(SET_ALARM_PERMISSION),
    label = "系统闹钟权限",
    deniedMessage = "设置闹钟需要系统闹钟权限。授权后 ChatWithChat 会直接写入系统时钟，不会打开编辑页让你手动确认。",
    grantMode = ToolPermissionGrantMode.ALL_OF
)

private const val SET_ALARM_PERMISSION = "com.android.alarm.permission.SET_ALARM"
