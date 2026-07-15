package cn.nabr.chatwithchat.presentation.ui.chat

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider.getUriForFile
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.common.AppleBlue
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatComposer(
    inputState: TextFieldState = rememberTextFieldState(),
    chatEnabled: Boolean = true,
    sendButtonEnabled: Boolean = true,
    showAttachmentButton: Boolean = true,
    selectedAttachments: List<ChatAttachmentDraft> = emptyList(),
    onFileSelected: (String) -> Unit = {},
    onFileRemoved: (String) -> Unit = {},
    onSendButtonClick: () -> Unit = {}
) {
    val localStyle = LocalTextStyle.current
    val mergedStyle = localStyle.merge(TextStyle(color = LocalContentColor.current))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val materialColors = settingsMaterialColors()
    val chatInputLineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5)
    val hasQuestionText = inputState.text.isNotBlank()
    val hasSendableAttachment = selectedAttachments.any {
        it.status == ChatAttachmentDraft.Status.Ready || it.status == ChatAttachmentDraft.Status.Preparing
    }
    val canSend = chatEnabled && sendButtonEnabled && (hasQuestionText || hasSendableAttachment)
    var isAttachmentMenuExpanded by remember { mutableStateOf(false) }
    var pendingCameraPhotoPath by remember { mutableStateOf<String?>(null) }
    val attachmentButtonColor by animateColorAsState(
        targetValue = materialColors.controlFill.copy(alpha = if (isAttachmentMenuExpanded) 0.44f else 0f),
        label = "attachmentButtonColor"
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                val filePath = withContext(Dispatchers.IO) {
                    copyFileToAppDirectory(context, it)
                }
                filePath?.let { path -> onFileSelected(path) }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { isSaved ->
        val photoPath = pendingCameraPhotoPath
        pendingCameraPhotoPath = null
        if (photoPath == null) return@rememberLauncherForActivityResult

        val photoFile = File(photoPath)
        if (isSaved && photoFile.exists() && photoFile.length() > 0L) {
            onFileSelected(photoPath)
        } else {
            photoFile.delete()
        }
    }

    fun launchCameraCapture() {
        val photoFile = createCameraPhotoFile(context)
        if (photoFile == null) {
            Toast.makeText(context, context.getString(R.string.failed_to_start_camera), Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            val photoUri = getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            pendingCameraPhotoPath = photoFile.absolutePath
            cameraLauncher.launch(photoUri)
        }.onFailure {
            pendingCameraPhotoPath = null
            photoFile.delete()
            Toast.makeText(context, context.getString(R.string.failed_to_start_camera), Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = materialColors.navigation)
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = materialColors.separator)
        if (selectedAttachments.isNotEmpty()) {
            FileThumbnailRow(
                selectedAttachments = selectedAttachments,
                onFileRemoved = onFileRemoved
            )
        }
        BasicTextField(
            state = inputState,
            modifier = Modifier.fillMaxWidth(),
            enabled = chatEnabled,
            textStyle = mergedStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            lineLimits = chatInputLineLimits,
            decorator = { innerTextField ->
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = materialColors.grouped,
                    contentColor = materialColors.primaryLabel,
                    border = BorderStroke(
                        width = 0.5.dp,
                        color = materialColors.separatorStrong
                    ),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(start = if (showAttachmentButton) 2.dp else 14.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showAttachmentButton) {
                            Box(contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(attachmentButtonColor, CircleShape)
                                )
                                IconButton(
                                    enabled = chatEnabled,
                                    onClick = { isAttachmentMenuExpanded = true },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = if (isAttachmentMenuExpanded) AppleBlue else materialColors.secondaryLabel,
                                        disabledContentColor = materialColors.tertiaryLabel
                                    )
                                ) {
                                    Icon(
                                        modifier = Modifier.size(22.dp),
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_attach_file),
                                        contentDescription = stringResource(R.string.attach_file)
                                    )
                                }
                                DropdownMenu(
                                    modifier = Modifier.widthIn(min = 208.dp, max = 260.dp),
                                    expanded = isAttachmentMenuExpanded,
                                    onDismissRequest = { isAttachmentMenuExpanded = false },
                                    offset = DpOffset(x = (-4).dp, y = 4.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    containerColor = materialColors.grouped,
                                    tonalElevation = 0.dp,
                                    shadowElevation = 12.dp,
                                    border = BorderStroke(0.5.dp, materialColors.separatorStrong)
                                ) {
                                    DropdownMenuItem(
                                        modifier = Modifier.heightIn(min = 52.dp),
                                        text = {
                                            Text(
                                                text = stringResource(R.string.choose_image),
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = ImageVector.vectorResource(R.drawable.ic_image),
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            isAttachmentMenuExpanded = false
                                            filePickerLauncher.launch("image/*")
                                        }
                                    )
                                    DropdownMenuItem(
                                        modifier = Modifier.heightIn(min = 52.dp),
                                        text = {
                                            Text(
                                                text = stringResource(R.string.take_photo),
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = ImageVector.vectorResource(R.drawable.ic_photo_camera),
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            isAttachmentMenuExpanded = false
                                            launchCameraCapture()
                                        }
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                        ) {
                            if (inputState.text.isEmpty()) {
                                Text(
                                    modifier = Modifier.alpha(0.7f),
                                    text = if (chatEnabled) stringResource(R.string.ask_a_question) else stringResource(R.string.some_platforms_disabled),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = materialColors.secondaryLabel
                                )
                            }
                            Box(modifier = Modifier.fillMaxWidth()) {
                                innerTextField()
                            }
                        }
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        color = if (canSend) AppleBlue else materialColors.controlFill,
                                        shape = CircleShape
                                    )
                            )
                            IconButton(
                                enabled = canSend,
                                onClick = onSendButtonClick,
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = Color.White,
                                    disabledContentColor = materialColors.tertiaryLabel
                                )
                            ) {
                                Icon(
                                    modifier = Modifier.size(20.dp),
                                    imageVector = Icons.Rounded.ArrowUpward,
                                    contentDescription = stringResource(R.string.send)
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
internal fun FileThumbnailRow(
    selectedAttachments: List<ChatAttachmentDraft>,
    onFileRemoved: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        selectedAttachments.forEach { attachment ->
            FileThumbnail(
                attachment = attachment,
                onRemove = { onFileRemoved(attachment.sourceFilePath) }
            )
        }
    }
}

@Composable
internal fun FileThumbnail(
    attachment: ChatAttachmentDraft,
    onRemove: () -> Unit
) {
    val file = File(attachment.preparedFilePath ?: attachment.sourceFilePath)
    val isImage = isImageFile(file.extension)

    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (isImage) {
                LocalImageThumbnail(
                    filePath = file.absolutePath,
                    size = 64.dp,
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    fallback = {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_image),
                            contentDescription = file.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            } else {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_file),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .background(
                        MaterialTheme.colorScheme.error,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.remove),
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(10.dp)
                )
            }

            if (attachment.status == ChatAttachmentDraft.Status.Preparing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        Text(
            text = file.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp)
                .width(72.dp)
        )

        attachment.notice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(72.dp)
            )
        }

        attachment.errorMessage?.let { errorMessage ->
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(72.dp)
            )
        }
    }
}

internal fun createCameraPhotoFile(context: Context): File? {
    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
    val attachmentsDir = File(picturesDir, "attachments")
    if (!attachmentsDir.exists() && !attachmentsDir.mkdirs()) return null

    return runCatching {
        File.createTempFile("camera_photo_", ".jpg", attachmentsDir)
    }.getOrNull()
}

internal fun copyFileToAppDirectory(context: Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val rawFileName = getFileName(context, uri)
        val sanitizedFileName = sanitizeFileName(rawFileName)

        val attachmentsDir = File(context.filesDir, "attachments")
        attachmentsDir.mkdirs()

        var targetFile = File(attachmentsDir, sanitizedFileName)

        // If file exists, append timestamp to avoid overwrites.
        if (targetFile.exists()) {
            val nameWithoutExt = sanitizedFileName.substringBeforeLast(".")
            val ext = sanitizedFileName.substringAfterLast(".", "")
            val uniqueName = if (ext.isNotEmpty()) {
                "${nameWithoutExt}_${System.currentTimeMillis()}.$ext"
            } else {
                "${sanitizedFileName}_${System.currentTimeMillis()}"
            }
            targetFile = File(attachmentsDir, uniqueName)
        }

        // Verify canonical path is within attachments directory to prevent path traversal.
        val attachmentsDirCanonical = attachmentsDir.canonicalPath
        val targetFileCanonical = targetFile.canonicalPath
        if (!targetFileCanonical.startsWith(attachmentsDirCanonical + File.separator) &&
            targetFileCanonical != attachmentsDirCanonical
        ) {
            return null
        }

        inputStream.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        targetFile.absolutePath
    } catch (e: Exception) {
        null
    }
}

private fun getFileName(context: Context, uri: Uri): String {
    var fileName = "attachment_${System.currentTimeMillis()}"

    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex != -1) {
            fileName = cursor.getString(nameIndex) ?: fileName
        }
    }

    return fileName
}

private fun sanitizeFileName(fileName: String): String {
    val maxLength = 200

    val withoutPathTraversal = fileName
        .replace("..", "")
        .replace("/", "")
        .replace("\\", "")

    val sanitized = withoutPathTraversal
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        .take(maxLength)
        .trim('.')

    return sanitized.ifEmpty { "attachment_${System.currentTimeMillis()}" }
}

@Preview
@Composable
fun EmptyChatComposerPreview() {
    ChatWithChatTheme {
        ChatComposer()
    }
}

@Preview
@Composable
fun PreparingAttachmentChatComposerPreview() {
    ChatWithChatTheme {
        ChatComposer(
            selectedAttachments = listOf(
                ChatAttachmentDraft(
                    sourceFilePath = "/preview/photo.png",
                    status = ChatAttachmentDraft.Status.Preparing
                )
            )
        )
    }
}

@Preview
@Composable
fun FailedAttachmentChatComposerPreview() {
    ChatWithChatTheme {
        ChatComposer(
            selectedAttachments = listOf(
                ChatAttachmentDraft(
                    sourceFilePath = "/preview/document.txt",
                    status = ChatAttachmentDraft.Status.Failed,
                    errorMessage = "Upload failed"
                )
            )
        )
    }
}
