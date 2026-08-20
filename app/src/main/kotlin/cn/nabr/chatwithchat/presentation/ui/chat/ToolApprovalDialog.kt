package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.tool.ToolApprovalPreview
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.presentation.common.HigActionDialog
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.settingsTextButtonColors
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme

@Composable
fun ToolApprovalDialog(
    preview: ToolApprovalPreview,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    canApproveForSession: Boolean = false,
    onApproveForSession: () -> Unit = {}
) {
    val displayName = when (preview.presentationKey) {
        "web_search" -> stringResource(R.string.tool_web_search_title)
        "fetch_url" -> stringResource(R.string.tool_fetch_url_title)
        "current_datetime" -> stringResource(R.string.tool_current_datetime_title)
        "device_location" -> stringResource(R.string.tool_device_location_title)
        "add_schedule" -> stringResource(R.string.tool_add_schedule_title)
        "set_alarm" -> stringResource(R.string.tool_set_alarm_title)
        // The argument summary already names the server and the tool, so the title stays generic rather
        // than repeating a mangled version of the namespaced tool name.
        "mcp_tool" -> stringResource(R.string.tool_mcp_title)
        else -> preview.fallbackDisplayName
    }
    HigActionDialog(
        title = stringResource(R.string.tool_approval_dialog_title, displayName),
        message = stringResource(R.string.tool_approval_dialog_description),
        detail = preview.argumentSummary,
        primaryActionLabel = stringResource(R.string.tool_approval_approve),
        secondaryActionLabel = stringResource(R.string.tool_approval_deny),
        onPrimaryAction = onApprove,
        onSecondaryAction = onDeny,
        onDismissRequest = onDeny
    ) {
        // Widening consent is a separate, explicit choice rather than a side effect of approving once, and
        // it sits away from the primary action so it cannot be hit by reflex. Outlined because a bare
        // centered TextButton in this dialog reads as a heading rather than something tappable.
        if (canApproveForSession) {
            val materialColors = settingsMaterialColors()
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = materialColors.separator,
                        shape = RoundedCornerShape(10.dp)
                    ),
                shape = RoundedCornerShape(10.dp),
                onClick = onApproveForSession,
                colors = settingsTextButtonColors()
            ) {
                Text(
                    text = stringResource(R.string.tool_approval_allow_for_session),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = stringResource(R.string.tool_approval_allow_for_session_hint),
                style = MaterialTheme.typography.bodySmall,
                color = materialColors.secondaryLabel
            )
        }
    }
}

@Preview
@Composable
private fun ToolApprovalDialogPreview() {
    val preview = ToolApprovalPreview.create(
        call = ToolCall(
            id = "preview-call",
            name = "device_location",
            arguments = "{}"
        ),
        presentationKey = "device_location",
        fallbackDisplayName = "Device location",
        humanReadableArgumentSummary = "Read the current device location for this reply"
    ).getOrThrow()
    ChatWithChatTheme {
        ToolApprovalDialog(
            preview = preview,
            onApprove = {},
            onDeny = {}
        )
    }
}
