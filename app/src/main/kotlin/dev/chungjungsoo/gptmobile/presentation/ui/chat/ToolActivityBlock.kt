package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.AppleBlue
import dev.chungjungsoo.gptmobile.presentation.common.AppleGreen
import dev.chungjungsoo.gptmobile.presentation.common.AppleIndigo
import dev.chungjungsoo.gptmobile.presentation.common.AppleOrange
import dev.chungjungsoo.gptmobile.presentation.common.ApplePurple
import dev.chungjungsoo.gptmobile.presentation.common.AppleRed
import dev.chungjungsoo.gptmobile.presentation.common.HigActionDialog
import dev.chungjungsoo.gptmobile.presentation.common.LocalToolPermissionRequester
import dev.chungjungsoo.gptmobile.presentation.common.settingsMaterialColors
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme

private const val WEB_SEARCH_TOOL = "web_search"
private const val FETCH_URL_TOOL = "fetch_url"
private const val CURRENT_DATETIME_TOOL = "current_datetime"
private const val DEVICE_LOCATION_TOOL = "device_location"
private const val TOOL_PERMISSION_DENIED = "tool_permission_denied"
private const val MAX_VISIBLE_TOOL_ROWS = 4

@Composable
fun ToolActivityBlock(
    progressStates: List<ChatViewModel.ToolProgressState>,
    modifier: Modifier = Modifier
) {
    val visibleStates = remember(progressStates) { progressStates.latestVisibleStates() }
    if (visibleStates.isEmpty()) return
    val materialColors = settingsMaterialColors()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = materialColors.grouped,
        contentColor = materialColors.primaryLabel,
        border = BorderStroke(0.5.dp, materialColors.separatorStrong),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column {
            visibleStates.forEachIndexed { index, state ->
                key(state.toolName, state.label) {
                    ToolActivityRow(state = state)
                }
                if (index < visibleStates.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        thickness = 0.5.dp,
                        color = materialColors.separator
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolActivityRow(state: ChatViewModel.ToolProgressState) {
    val materialColors = settingsMaterialColors()
    val toolPermissionRequester = LocalToolPermissionRequester.current
    var showPermissionRationale by remember(state.toolName, state.label) { mutableStateOf(false) }
    var isPermissionGranted by remember(state.toolName, state.label, state.errorCode) { mutableStateOf(false) }
    val isPermissionFailure = state.errorCode == TOOL_PERMISSION_DENIED
    val detail = state.secondaryText(isPermissionGranted)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ToolIcon(toolName = state.toolName)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = state.primaryText(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = materialColors.primaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPermissionGranted) AppleGreen else materialColors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isPermissionFailure && !isPermissionGranted) {
            TextButton(
                modifier = Modifier.defaultMinSize(minWidth = 0.dp, minHeight = 44.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                onClick = { showPermissionRationale = true }
            ) {
                Text(
                    text = stringResource(R.string.next),
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            ToolStatusIndicator(status = state.status)
        }
    }

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
                }
            },
            onDismissRequest = {},
            isDismissible = false
        )
    }
}

@Composable
private fun ToolIcon(toolName: String) {
    val (icon, tint) = toolName.toolVisual()
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(tint.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ToolStatusIndicator(status: ChatViewModel.ToolProgressStatus) {
    Box(
        modifier = Modifier.size(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = status,
            animationSpec = tween(durationMillis = 160),
            label = "toolStatus"
        ) { currentStatus ->
            when (currentStatus) {
                ChatViewModel.ToolProgressStatus.Running -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = AppleBlue
                )
                ChatViewModel.ToolProgressStatus.Finished,
                ChatViewModel.ToolProgressStatus.Failed -> Icon(
                    imageVector = if (currentStatus == ChatViewModel.ToolProgressStatus.Finished) {
                        Icons.Rounded.Check
                    } else {
                        Icons.Rounded.Close
                    },
                    contentDescription = null,
                    tint = currentStatus.statusColor(),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatViewModel.ToolProgressState.primaryText(): String = when (status) {
    ChatViewModel.ToolProgressStatus.Running -> when (toolName) {
        WEB_SEARCH_TOOL -> stringResource(R.string.tool_web_search_running)
        FETCH_URL_TOOL -> stringResource(R.string.tool_fetch_url_running)
        CURRENT_DATETIME_TOOL -> stringResource(R.string.tool_current_datetime_running)
        DEVICE_LOCATION_TOOL -> stringResource(R.string.tool_device_location_running)
        else -> stringResource(R.string.tool_running)
    }
    ChatViewModel.ToolProgressStatus.Finished -> when (toolName) {
        WEB_SEARCH_TOOL -> stringResource(R.string.tool_web_search_finished)
        FETCH_URL_TOOL -> stringResource(R.string.tool_fetch_url_finished)
        CURRENT_DATETIME_TOOL -> stringResource(R.string.tool_current_datetime_finished)
        DEVICE_LOCATION_TOOL -> stringResource(R.string.tool_device_location_finished)
        else -> stringResource(R.string.tool_finished)
    }
    ChatViewModel.ToolProgressStatus.Failed -> when (toolName) {
        WEB_SEARCH_TOOL -> stringResource(R.string.tool_web_search_failed)
        FETCH_URL_TOOL -> stringResource(R.string.tool_fetch_url_failed)
        CURRENT_DATETIME_TOOL -> stringResource(R.string.tool_current_datetime_failed)
        DEVICE_LOCATION_TOOL -> stringResource(R.string.tool_device_location_failed)
        else -> stringResource(R.string.tool_failed)
    }
}

@Composable
private fun ChatViewModel.ToolProgressState.secondaryText(isPermissionGranted: Boolean): String? = when (status) {
    ChatViewModel.ToolProgressStatus.Failed -> when {
        isPermissionGranted -> stringResource(R.string.tool_permission_granted_retry)
        errorCode == TOOL_PERMISSION_DENIED -> stringResource(R.string.tool_permission_required)
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
    else -> toolName.replace('_', ' ')
}

private fun String.toolVisual(): Pair<ImageVector, Color> = when (this) {
    WEB_SEARCH_TOOL -> Icons.Rounded.Search to AppleBlue
    FETCH_URL_TOOL -> Icons.Rounded.Language to AppleIndigo
    CURRENT_DATETIME_TOOL -> Icons.Rounded.Schedule to AppleOrange
    DEVICE_LOCATION_TOOL -> Icons.Rounded.LocationOn to AppleGreen
    else -> Icons.Rounded.Extension to ApplePurple
}

private fun ChatViewModel.ToolProgressStatus.statusColor(): Color = when (this) {
    ChatViewModel.ToolProgressStatus.Running -> AppleBlue
    ChatViewModel.ToolProgressStatus.Finished -> AppleGreen
    ChatViewModel.ToolProgressStatus.Failed -> AppleRed
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
    GPTMobileTheme {
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
