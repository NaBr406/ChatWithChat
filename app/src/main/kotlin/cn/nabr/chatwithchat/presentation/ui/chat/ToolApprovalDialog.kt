package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.tool.ToolApprovalPreview
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.presentation.common.HigActionDialog
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme

@Composable
fun ToolApprovalDialog(
    preview: ToolApprovalPreview,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    val displayName = when (preview.presentationKey) {
        "web_search" -> stringResource(R.string.tool_web_search_title)
        "fetch_url" -> stringResource(R.string.tool_fetch_url_title)
        "current_datetime" -> stringResource(R.string.tool_current_datetime_title)
        "device_location" -> stringResource(R.string.tool_device_location_title)
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
    )
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
