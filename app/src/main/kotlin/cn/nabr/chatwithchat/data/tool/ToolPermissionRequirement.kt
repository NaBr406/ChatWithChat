package cn.nabr.chatwithchat.data.tool

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

data class ToolPermissionRequirement(
    val permissions: List<String>,
    val label: String,
    val deniedMessage: String,
    val grantMode: ToolPermissionGrantMode = ToolPermissionGrantMode.ANY_OF
) {
    fun requestedPermissions(): List<String> = permissions
        .map { permission -> permission.trim() }
        .filter { permission -> permission.isNotBlank() }
        .distinct()

    fun isSatisfied(isPermissionGranted: (String) -> Boolean): Boolean {
        val requestedPermissions = requestedPermissions()
        if (requestedPermissions.isEmpty()) return true

        return when (grantMode) {
            ToolPermissionGrantMode.ANY_OF -> requestedPermissions.any(isPermissionGranted)
            ToolPermissionGrantMode.ALL_OF -> requestedPermissions.all(isPermissionGranted)
        }
    }
}

enum class ToolPermissionGrantMode {
    ANY_OF,
    ALL_OF
}

fun interface ToolPermissionChecker {
    fun missingRequirements(requirements: List<ToolPermissionRequirement>): List<ToolPermissionRequirement>
}

object AlwaysGrantedToolPermissionChecker : ToolPermissionChecker {
    override fun missingRequirements(requirements: List<ToolPermissionRequirement>): List<ToolPermissionRequirement> = emptyList()
}

class AndroidToolPermissionChecker(
    context: Context
) : ToolPermissionChecker {
    private val appContext = context.applicationContext

    override fun missingRequirements(requirements: List<ToolPermissionRequirement>): List<ToolPermissionRequirement> =
        requirements.filter { requirement ->
            !requirement.isSatisfied { permission -> appContext.isPermissionGranted(permission) }
        }

    private fun Context.isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

class ToolPermissionDeniedException(
    val toolName: String,
    val missingRequirements: List<ToolPermissionRequirement>
) : SecurityException(buildPermissionDeniedMessage(toolName, missingRequirements)) {
    val missingPermissions: List<String> = missingRequirements
        .flatMap { requirement -> requirement.requestedPermissions() }
        .distinct()

    val missingLabels: List<String> = missingRequirements
        .map { requirement -> requirement.label.trim() }
        .filter { label -> label.isNotBlank() }
        .distinct()

    val userMessage: String = missingRequirements
        .map { requirement -> requirement.deniedMessage.trim() }
        .filter { message -> message.isNotBlank() }
        .distinct()
        .joinToString(separator = " ")
        .ifBlank {
            "The $toolName tool needs Android permission before it can run. Ask the user to enable the missing permission and try again."
        }
}

internal fun ToolCall.permissionDeniedResult(exception: ToolPermissionDeniedException): ToolResult {
    val payload = ToolPermissionDeniedPayload(
        tool = exception.toolName,
        missingPermissions = exception.missingPermissions,
        missingPermissionLabels = exception.missingLabels,
        message = exception.userMessage
    )
    return ToolResult(
        callId = id,
        name = name,
        content = toolProtocolJson.encodeToString(payload),
        isError = true,
        metadata = mapOf(
            "error_code" to TOOL_PERMISSION_DENIED,
            "missing_permissions" to exception.missingPermissions.joinToString(","),
            "missing_permission_labels" to exception.missingLabels.joinToString(","),
            "user_message" to exception.userMessage
        )
    )
}

private fun buildPermissionDeniedMessage(
    toolName: String,
    missingRequirements: List<ToolPermissionRequirement>
): String {
    val missingPermissions = missingRequirements
        .flatMap { requirement -> requirement.requestedPermissions() }
        .distinct()
        .joinToString(",")
    return "$TOOL_PERMISSION_DENIED:$toolName:$missingPermissions"
}

@Serializable
private data class ToolPermissionDeniedPayload(
    val error: String = TOOL_PERMISSION_DENIED,
    val tool: String,
    @SerialName("missing_permissions")
    val missingPermissions: List<String>,
    @SerialName("missing_permission_labels")
    val missingPermissionLabels: List<String>,
    val message: String,
    @SerialName("user_action")
    val userAction: String = "Ask the user to enable the missing Android permission(s), then retry the tool."
)

private const val TOOL_PERMISSION_DENIED = "tool_permission_denied"
