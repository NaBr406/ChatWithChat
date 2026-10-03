package cn.nabr.chatwithchat.presentation.ui.chat

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.tool.TOOL_ARGUMENTS_TOO_LARGE
import cn.nabr.chatwithchat.data.tool.TOOL_BUDGET_EXCEEDED_PREFIX
import cn.nabr.chatwithchat.presentation.common.AppleGreen
import cn.nabr.chatwithchat.presentation.common.AppleRed
import cn.nabr.chatwithchat.presentation.common.HigActionDialog
import cn.nabr.chatwithchat.presentation.common.LocalToolPermissionRequester
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme

private const val WEB_SEARCH_TOOL = "web_search"
private const val FETCH_URL_TOOL = "fetch_url"
private const val CURRENT_DATETIME_TOOL = "current_datetime"
private const val DEVICE_LOCATION_TOOL = "device_location"
private const val ADD_SCHEDULE_TOOL = "add_schedule"
private const val SET_ALARM_TOOL = "set_alarm"
private const val ASK_USER_QUESTION_TOOL = "ask_user_question"
private const val TOOL_PERMISSION_DENIED = "tool_permission_denied"
private const val MAX_VISIBLE_TOOL_ROWS = 4

@Composable
fun ToolActivityBlock(
    progressStates: List<ChatViewModel.ToolProgressState>,
    modifier: Modifier = Modifier,
    onRetryAfterPermission: () -> Unit = {}
) {
    val visibleStates = remember(progressStates) {
        progressStates
            .withoutStickerToolProgress()
            .latestVisibleStates()
    }
    if (visibleStates.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        visibleStates.forEach { state ->
            key(state.toolName, state.label) {
                ToolActivityLine(
                    state = state,
                    onRetryAfterPermission = onRetryAfterPermission
                )
            }
        }
    }
}

internal fun List<ChatViewModel.ToolProgressState>.withoutStickerToolProgress(): List<ChatViewModel.ToolProgressState> =
    filterNot { state -> state.toolName.isStickerToolName() }

@Composable
private fun ToolActivityLine(
    state: ChatViewModel.ToolProgressState,
    onRetryAfterPermission: () -> Unit
) {
    val toolPermissionRequester = LocalToolPermissionRequester.current
    var showPermissionRationale by remember(state.toolName, state.label) { mutableStateOf(false) }
    var isPermissionGranted by remember(state.toolName, state.label, state.errorCode) { mutableStateOf(false) }
    val isPermissionFailure = state.errorCode == TOOL_PERMISSION_DENIED
    val canOpenPermissionRationale = isPermissionFailure && !isPermissionGranted

    LaunchedEffect(state.toolName, state.label, state.errorCode) {
        if (isPermissionFailure &&
            (state.toolName == ADD_SCHEDULE_TOOL || state.toolName == SET_ALARM_TOOL)
        ) {
            toolPermissionRequester.requestToolPermissions(state.toolName) { granted ->
                isPermissionGranted = granted
                if (granted) onRetryAfterPermission()
            }
        }
    }

    Text(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpenPermissionRationale) { showPermissionRationale = true }
            .padding(vertical = 2.dp),
        text = state.annotationText(isPermissionGranted),
        style = MaterialTheme.typography.labelSmall,
        color = state.annotationColor(isPermissionGranted),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )

    if (showPermissionRationale) {
        HigActionDialog(
            title = stringResource(
                R.string.tool_permission_rationale_title,
                state.toolDisplayName()
            ),
            message = stringResource(R.string.tool_permission_chat_description),
            primaryActionLabel = stringResource(R.string.next),
            onPrimaryAction = {
                showPermissionRationale = false
                toolPermissionRequester.requestToolPermissions(state.toolName) { granted ->
                    isPermissionGranted = granted
                    if (granted) onRetryAfterPermission()
                }
            },
            onDismissRequest = {},
            isDismissible = false
        )
    }
}

@Composable
private fun ChatViewModel.ToolProgressState.annotationText(isPermissionGranted: Boolean): String {
    val primary = primaryText()
    val detail = secondaryText(isPermissionGranted)
    return if (detail.isNullOrBlank()) primary else "$primary · $detail"
}

@Composable
private fun ChatViewModel.ToolProgressState.annotationColor(isPermissionGranted: Boolean): Color = when {
    isPermissionGranted -> AppleGreen
    status == ChatViewModel.ToolProgressStatus.Failed -> AppleRed
    else -> settingsMaterialColors().tertiaryLabel
}

@Composable
private fun ChatViewModel.ToolProgressState.primaryText(): String = when (status) {
    ChatViewModel.ToolProgressStatus.Running -> when (toolName) {
        WEB_SEARCH_TOOL -> stringResource(R.string.tool_web_search_running)
        FETCH_URL_TOOL -> stringResource(R.string.tool_fetch_url_running)
        CURRENT_DATETIME_TOOL -> stringResource(R.string.tool_current_datetime_running)
        DEVICE_LOCATION_TOOL -> stringResource(R.string.tool_device_location_running)
        ADD_SCHEDULE_TOOL -> stringResource(R.string.tool_add_schedule_running)
        SET_ALARM_TOOL -> stringResource(R.string.tool_set_alarm_running)
        ASK_USER_QUESTION_TOOL -> stringResource(R.string.tool_ask_user_question_running)
        else -> stringResource(R.string.tool_running)
    }
    ChatViewModel.ToolProgressStatus.Finished -> when (toolName) {
        WEB_SEARCH_TOOL -> stringResource(R.string.tool_web_search_finished)
        FETCH_URL_TOOL -> stringResource(R.string.tool_fetch_url_finished)
        CURRENT_DATETIME_TOOL -> stringResource(R.string.tool_current_datetime_finished)
        DEVICE_LOCATION_TOOL -> stringResource(R.string.tool_device_location_finished)
        ADD_SCHEDULE_TOOL -> stringResource(R.string.tool_add_schedule_finished)
        SET_ALARM_TOOL -> stringResource(R.string.tool_set_alarm_finished)
        ASK_USER_QUESTION_TOOL -> stringResource(R.string.tool_ask_user_question_finished)
        else -> stringResource(R.string.tool_finished)
    }
    ChatViewModel.ToolProgressStatus.Failed -> when (toolName) {
        WEB_SEARCH_TOOL -> stringResource(R.string.tool_web_search_failed)
        FETCH_URL_TOOL -> stringResource(R.string.tool_fetch_url_failed)
        CURRENT_DATETIME_TOOL -> stringResource(R.string.tool_current_datetime_failed)
        DEVICE_LOCATION_TOOL -> stringResource(R.string.tool_device_location_failed)
        ADD_SCHEDULE_TOOL -> stringResource(R.string.tool_add_schedule_failed)
        SET_ALARM_TOOL -> stringResource(R.string.tool_set_alarm_failed)
        ASK_USER_QUESTION_TOOL -> stringResource(R.string.tool_ask_user_question_failed)
        else -> stringResource(R.string.tool_failed)
    }
}

@Composable
private fun ChatViewModel.ToolProgressState.secondaryText(isPermissionGranted: Boolean): String? = when (status) {
    ChatViewModel.ToolProgressStatus.Failed -> when {
        isPermissionGranted -> stringResource(R.string.tool_permission_granted_retry)
        errorCode == TOOL_PERMISSION_DENIED -> stringResource(R.string.tool_permission_required)
        // A budget refusal is not a tool malfunction, and `tool_budget_exceeded:max_tool_calls_per_chat`
        // means nothing to the person reading it.
        message.orEmpty().contains(TOOL_BUDGET_EXCEEDED_PREFIX) ->
            stringResource(R.string.tool_error_budget_exhausted)
        message.orEmpty().contains(TOOL_ARGUMENTS_TOO_LARGE) ->
            stringResource(R.string.tool_error_arguments_too_large)
        else -> message.toReadableFailureDetail()
    }
    ChatViewModel.ToolProgressStatus.Running,
    ChatViewModel.ToolProgressStatus.Finished -> label.toUserVisibleDetail(toolName)
}

@Composable
private fun ChatViewModel.ToolProgressState.toolDisplayName(): String = when (toolName) {
    WEB_SEARCH_TOOL -> stringResource(R.string.tool_web_search_title)
    FETCH_URL_TOOL -> stringResource(R.string.tool_fetch_url_title)
    CURRENT_DATETIME_TOOL -> stringResource(R.string.tool_current_datetime_title)
    DEVICE_LOCATION_TOOL -> stringResource(R.string.tool_device_location_title)
    ADD_SCHEDULE_TOOL -> stringResource(R.string.tool_add_schedule_title)
    SET_ALARM_TOOL -> stringResource(R.string.tool_set_alarm_title)
    else -> toolName.replace('_', ' ')
}

private fun List<ChatViewModel.ToolProgressState>.latestVisibleStates(): List<ChatViewModel.ToolProgressState> {
    val latestByAction = linkedMapOf<String, ChatViewModel.ToolProgressState>()
    forEach { state ->
        val key = "${state.toolName}:${state.label}"
        latestByAction.remove(key)
        latestByAction[key] = state
    }
    return latestByAction.values.toList().takeLast(MAX_VISIBLE_TOOL_ROWS)
}

private fun String.toUserVisibleDetail(toolName: String): String? {
    val trimmed = trim().replace('\n', ' ')
    if (trimmed.isBlank() || trimmed == toolName) return null
    if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.contains("\"arguments\"")) return null
    if (toolName == FETCH_URL_TOOL && "://" in trimmed) {
        val host = runCatching { Uri.parse(trimmed).host }
            .getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        if (host != null) return host
    }
    return trimmed.take(120)
}

private fun String?.toReadableFailureDetail(): String? {
    val trimmed = this?.trim().orEmpty()
    val readable = trimmed
        .removePrefix("web_search_failed:")
        .removePrefix("fetch_url_failed:")
    return readable.toUserVisibleDetail(toolName = "")
}

@Preview
@Composable
private fun ToolActivityBlockPreview() {
    ChatWithChatTheme {
        ToolActivityBlock(
            progressStates = listOf(
                ChatViewModel.ToolProgressState(
                    toolName = WEB_SEARCH_TOOL,
                    label = "latest Kotlin Android updates",
                    status = ChatViewModel.ToolProgressStatus.Running
                ),
                ChatViewModel.ToolProgressState(
                    toolName = FETCH_URL_TOOL,
                    label = "https://developer.android.com/",
                    status = ChatViewModel.ToolProgressStatus.Finished
                ),
                ChatViewModel.ToolProgressState(
                    toolName = DEVICE_LOCATION_TOOL,
                    label = DEVICE_LOCATION_TOOL,
                    status = ChatViewModel.ToolProgressStatus.Failed,
                    message = TOOL_PERMISSION_DENIED,
                    errorCode = TOOL_PERMISSION_DENIED
                )
            )
        )
    }
}
