package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.data.database.entity.effectiveThoughts
import cn.nabr.chatwithchat.presentation.common.AppleBlue
import cn.nabr.chatwithchat.presentation.common.HigActionDialog
import cn.nabr.chatwithchat.presentation.common.settingsTextFieldColors
import kotlinx.coroutines.launch

@Composable
fun ChatTitleDialog(
    initialTitle: String,
    onDefaultTitleMode: () -> String?,
    onConfirmRequest: (title: String) -> Unit,
    onDismissRequest: () -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    val untitledChat = stringResource(R.string.untitled_chat)
    val normalizedTitle = normalizeChatTitle(title)
    val canUpdate = normalizedTitle.isNotBlank() &&
        normalizedTitle.length <= 50 &&
        normalizedTitle != normalizeChatTitle(initialTitle)

    HigActionDialog(
        title = stringResource(R.string.chat_title),
        message = null,
        primaryActionLabel = stringResource(R.string.update),
        onPrimaryAction = {
            onConfirmRequest(normalizedTitle)
            onDismissRequest()
        },
        onDismissRequest = onDismissRequest,
        secondaryActionLabel = stringResource(R.string.cancel),
        onSecondaryAction = onDismissRequest,
        isPrimaryActionEnabled = canUpdate
    ) {
        Spacer(modifier = Modifier.heightIn(min = 14.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = title,
            singleLine = true,
            isError = normalizedTitle.length > 50,
            supportingText = {
                if (normalizedTitle.length > 50) {
                    Text(stringResource(R.string.title_length_limit, normalizedTitle.length))
                }
            },
            onValueChange = { title = it },
            placeholder = { Text(stringResource(R.string.chat_title)) },
            shape = RoundedCornerShape(10.dp),
            colors = settingsTextFieldColors()
        )
        TextButton(
            onClick = { title = onDefaultTitleMode.invoke() ?: untitledChat },
            colors = ButtonDefaults.textButtonColors(contentColor = AppleBlue)
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = stringResource(R.string.default_mode))
        }
    }
}

private val CHAT_TITLE_WHITESPACE = Regex("\\s+")

internal fun normalizeChatTitle(title: String): String = title
    .trim()
    .replace(CHAT_TITLE_WHITESPACE, " ")

@Composable
fun UserMessageEditDialog(
    initialQuestion: MessageV2,
    attachments: List<ChatAttachmentDraft>,
    isAttachmentImportInProgress: Boolean,
    onFilesSelected: (List<String>, Int) -> Unit,
    onFileRemoved: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (MessageV2) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf(initialQuestion.content) }
    var isCopyingPickedImages by remember { mutableStateOf(false) }
    val isAttachmentBusy = isAttachmentImportInProgress || isCopyingPickedImages
    val questionFieldMaxLines = 8
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isCopyingPickedImages = true
            scope.launch {
                try {
                    copyPickedImages(context, uris) { result ->
                        onFilesSelected(result.copiedPaths, result.failedCount)
                    }
                } finally {
                    isCopyingPickedImages = false
                }
            }
        }
    }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.edit_question)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    value = question,
                    onValueChange = { question = it },
                    minLines = 3,
                    maxLines = questionFieldMaxLines,
                    label = { Text(stringResource(R.string.user_message)) }
                )
                AttachmentEditorSection(
                    attachments = attachments,
                    enabled = !isAttachmentBusy,
                    onAttachFileClick = {
                        filePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onFileRemoved = onFileRemoved
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            val hasPendingOrFailedAttachments = attachments.any { it.status != ChatAttachmentDraft.Status.Ready }
            TextButton(
                enabled = !isAttachmentBusy &&
                    !hasPendingOrFailedAttachments &&
                    (question.isNotBlank() || attachments.isNotEmpty()) &&
                    (question != initialQuestion.content || attachments.mapNotNull { it.attachment } != initialQuestion.attachments),
                onClick = { onConfirmRequest(initialQuestion.copy(content = question)) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun AssistantMessageEditDialog(
    initialMessage: MessageV2,
    attachments: List<ChatAttachmentDraft>,
    isAttachmentImportInProgress: Boolean,
    onFilesSelected: (List<String>, Int) -> Unit,
    onFileRemoved: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (MessageV2, String) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var responseText by remember { mutableStateOf(initialMessage.effectiveContent()) }
    var thoughtsText by remember { mutableStateOf(initialMessage.effectiveThoughts()) }
    var isCopyingPickedImages by remember { mutableStateOf(false) }
    val isAttachmentBusy = isAttachmentImportInProgress || isCopyingPickedImages
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isCopyingPickedImages = true
            scope.launch {
                try {
                    copyPickedImages(context, uris) { result ->
                        onFilesSelected(result.copiedPaths, result.failedCount)
                    }
                } finally {
                    isCopyingPickedImages = false
                }
            }
        }
    }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.edit_assistant_message)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    value = responseText,
                    onValueChange = { responseText = it },
                    minLines = 3,
                    maxLines = 8,
                    label = { Text(stringResource(R.string.assistant_message)) }
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    value = thoughtsText,
                    onValueChange = { thoughtsText = it },
                    minLines = 2,
                    maxLines = 8,
                    label = { Text(stringResource(R.string.assistant_thoughts)) }
                )
                AttachmentEditorSection(
                    attachments = attachments,
                    enabled = !isAttachmentBusy,
                    onAttachFileClick = {
                        filePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onFileRemoved = onFileRemoved
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            val hasPendingOrFailedAttachments = attachments.any { it.status != ChatAttachmentDraft.Status.Ready }
            TextButton(
                enabled = !isAttachmentBusy &&
                    !hasPendingOrFailedAttachments &&
                    (responseText.isNotBlank() || thoughtsText.isNotBlank() || attachments.isNotEmpty()) &&
                    (
                        responseText != initialMessage.effectiveContent() ||
                            thoughtsText != initialMessage.effectiveThoughts() ||
                            attachments.mapNotNull { it.attachment } != initialMessage.attachments
                        ),
                onClick = {
                    onConfirmRequest(
                        initialMessage.copy(content = responseText),
                        thoughtsText
                    )
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun AttachmentEditorSection(
    attachments: List<ChatAttachmentDraft>,
    enabled: Boolean,
    onAttachFileClick: () -> Unit,
    onFileRemoved: (String) -> Unit
) {
    if (attachments.isNotEmpty()) {
        FileThumbnailRow(
            selectedAttachments = attachments,
            onFileRemoved = onFileRemoved
        )
    }
    TextButton(
        modifier = Modifier.padding(horizontal = 12.dp),
        enabled = enabled,
        onClick = onAttachFileClick
    ) {
        Text(text = stringResource(R.string.attach_file))
    }
}
