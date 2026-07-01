package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme

private const val WEB_SEARCH_TOOL = "web_search"
private const val FETCH_URL_TOOL = "fetch_url"
private const val MAX_VISIBLE_TOOL_ROWS = 4

@Composable
fun ToolActivityBlock(
    progressStates: List<ChatViewModel.ToolProgressState>,
    modifier: Modifier = Modifier
) {
    val visibleStates = remember(progressStates) { progressStates.latestVisibleStates() }
    if (visibleStates.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        visibleStates.forEach { state ->
            ToolActivityRow(state = state)
        }
    }
}

@Composable
private fun ToolActivityRow(state: ChatViewModel.ToolProgressState) {
    val statusColor = state.status.statusColor()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.status == ChatViewModel.ToolProgressStatus.Running) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = statusColor
            )
        } else {
            Icon(
                imageVector = if (state.status == ChatViewModel.ToolProgressStatus.Finished) {
                    Icons.Rounded.Check
                } else {
                    Icons.Rounded.Close
                },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(16.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.primaryText(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            state.secondaryText()?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
        else -> stringResource(R.string.tool_running)
    }
    ChatViewModel.ToolProgressStatus.Finished -> when (toolName) {
        WEB_SEARCH_TOOL -> stringResource(R.string.tool_web_search_finished)
        FETCH_URL_TOOL -> stringResource(R.string.tool_fetch_url_finished)
        else -> stringResource(R.string.tool_finished)
    }
    ChatViewModel.ToolProgressStatus.Failed -> stringResource(R.string.tool_failed)
}

private fun ChatViewModel.ToolProgressState.secondaryText(): String? = when (status) {
    ChatViewModel.ToolProgressStatus.Failed -> message.toReadableFailureDetail()
    ChatViewModel.ToolProgressStatus.Running,
    ChatViewModel.ToolProgressStatus.Finished -> label.toUserVisibleDetail(toolName)
}

@Composable
private fun ChatViewModel.ToolProgressStatus.statusColor(): Color = when (this) {
    ChatViewModel.ToolProgressStatus.Running -> MaterialTheme.colorScheme.primary
    ChatViewModel.ToolProgressStatus.Finished -> MaterialTheme.colorScheme.secondary
    ChatViewModel.ToolProgressStatus.Failed -> MaterialTheme.colorScheme.error
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
                    toolName = WEB_SEARCH_TOOL,
                    label = WEB_SEARCH_TOOL,
                    status = ChatViewModel.ToolProgressStatus.Failed,
                    message = "web_search_failed:backend not configured"
                )
            )
        )
    }
}
