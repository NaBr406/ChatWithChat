package cn.nabr.chatwithchat.presentation.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider.getUriForFile
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.textskill.SelectedTextSkill
import cn.nabr.chatwithchat.data.textskill.TextSkillSelectionScope
import cn.nabr.chatwithchat.presentation.common.LocalAttachmentPreviewLauncher
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
import cn.nabr.chatwithchat.presentation.common.settingsDropdownMenuItemColors
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import cn.nabr.chatwithchat.util.AttachmentNaming
import cn.nabr.chatwithchat.util.FileUtils
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatComposer(
    inputState: TextFieldState = rememberTextFieldState(),
    chatEnabled: Boolean = true,
    sendButtonEnabled: Boolean = true,
    showAttachmentButton: Boolean = true,
    isAttachmentImportInProgress: Boolean = false,
    selectedAttachments: List<ChatAttachmentDraft> = emptyList(),
    selectedTextSkills: List<SelectedTextSkill> = emptyList(),
    // Deliberately has no default: every call site has to state whether Skills are invocable, so a
    // new screen cannot silently ship an always-empty Skill picker.
    availableTextSkills: List<SelectedTextSkill>,
    onFilesSelected: (List<String>, Int) -> Unit = { _, _ -> },
    onFileRemoved: (String) -> Unit = {},
    onTextSkillsSelected: (List<SelectedTextSkill>, TextSkillSelectionScope) -> Unit = { _, _ -> },
    onTextSkillRemoved: (SelectedTextSkill) -> Unit = {},
    /**
     * Turns the send button into a stop button. False leaves the composer exactly as it was.
     *
     * Shares the button rather than adding a second one: the two actions are mutually exclusive by
     * definition — nothing can be sent while a turn runs, which is why [sendButtonEnabled] is already
     * false throughout — so a separate control would be dead half the time and would take space from
     * the input field on a phone.
     */
    isGenerating: Boolean = false,
    onStopButtonClick: () -> Unit = {},
    onSendButtonClick: () -> Unit = {}
) {
    val localStyle = LocalTextStyle.current
    val mergedStyle = localStyle.merge(TextStyle(color = LocalContentColor.current))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val materialColors = settingsMaterialColors()
    val chatInputLineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5)
    val hasQuestionText = inputState.text.isNotBlank()
    val hasSendableAttachment = selectedAttachments.any { it.status == ChatAttachmentDraft.Status.Ready }
    val hasUnreadyAttachment = selectedAttachments.any { it.status != ChatAttachmentDraft.Status.Ready }
    var isAttachmentMenuExpanded by remember { mutableStateOf(false) }
    var isAttachmentMenuMounted by remember { mutableStateOf(false) }
    var inputFieldBounds by remember { mutableStateOf(Rect.Zero) }
    var isCopyingPickedAttachments by remember { mutableStateOf(false) }
    var pendingCameraPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var isTextSkillSheetOpen by remember { mutableStateOf(false) }
    val isAttachmentBusy = isAttachmentImportInProgress || isCopyingPickedAttachments
    val canSend = canSubmitChatMessage(
        chatEnabled = chatEnabled,
        sendButtonEnabled = sendButtonEnabled,
        hasQuestionText = hasQuestionText,
        hasSendableAttachment = hasSendableAttachment,
        hasUnreadyAttachment = hasUnreadyAttachment,
        isAttachmentBusy = isAttachmentBusy
    )
    val attachmentButtonColor by animateColorAsState(
        targetValue = materialColors.controlFill.copy(alpha = if (isAttachmentMenuExpanded) 0.44f else 0f),
        label = "attachmentButtonColor"
    )

    LaunchedEffect(pendingCameraPhotoPath) {
        withContext(Dispatchers.IO) {
            cleanupAbandonedCameraPhotoFiles(
                pendingDirectory = cameraPendingDirectory(context),
                protectedPath = pendingCameraPhotoPath
            )
        }
    }

    fun importPickedAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        isCopyingPickedAttachments = true
        scope.launch {
            try {
                copyPickedAttachments(context, uris) { result ->
                    onFilesSelected(result.copiedPaths, result.failedCount)
                }
            } finally {
                isCopyingPickedAttachments = false
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris -> importPickedAttachments(uris) }
    )
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris -> importPickedAttachments(uris) }
    )

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { isSaved ->
        val photoPath = pendingCameraPhotoPath
        pendingCameraPhotoPath = null
        if (photoPath == null) return@rememberLauncherForActivityResult

        val pendingPhotoFile = File(photoPath)
        if (isSaved && pendingPhotoFile.exists() && pendingPhotoFile.length() > 0L) {
            val promotedPhoto = promoteCameraPhotoFile(context, pendingPhotoFile)
            if (promotedPhoto != null) {
                onFilesSelected(listOf(promotedPhoto.absolutePath), 0)
            } else {
                pendingPhotoFile.delete()
                Toast.makeText(context, context.getString(R.string.failed_to_prepare_attachment), Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingPhotoFile.delete()
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

    fun launchImagePicker() {
        try {
            filePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (_: ActivityNotFoundException) {
            try {
                documentPickerLauncher.launch(arrayOf("image/*"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, context.getString(R.string.failed_to_prepare_attachment), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchDocumentPicker() {
        try {
            documentPickerLauncher.launch(DOCUMENT_PICKER_MIME_TYPES)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.failed_to_prepare_attachment), Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCanvasBackground()
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = materialColors.separator)
        if (selectedAttachments.isNotEmpty()) {
            FileThumbnailRow(
                selectedAttachments = selectedAttachments,
                onFileRemoved = onFileRemoved
            )
        }
        if (selectedTextSkills.isNotEmpty()) {
            TextSkillDraftRow(selectedTextSkills, onTextSkillRemoved)
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
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            inputFieldBounds = coordinates.boundsInWindow()
                        },
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
                                        .size(30.dp)
                                        .background(attachmentButtonColor, CircleShape)
                                )
                                IconButton(
                                    enabled = chatEnabled && !isAttachmentBusy,
                                    onClick = {
                                        isAttachmentMenuMounted = true
                                        isAttachmentMenuExpanded = true
                                    },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = if (isAttachmentMenuExpanded) materialColors.primaryLabel else materialColors.secondaryLabel,
                                        disabledContentColor = materialColors.tertiaryLabel
                                    )
                                ) {
                                    Icon(
                                        modifier = Modifier.size(20.dp),
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_attach_file),
                                        contentDescription = stringResource(R.string.attach_file)
                                    )
                                }
                                if (isAttachmentMenuMounted) {
                                    ComposerActionMenu(
                                        expanded = isAttachmentMenuExpanded,
                                        anchorBounds = inputFieldBounds,
                                        onDismissRequest = { isAttachmentMenuExpanded = false },
                                        onExitFinished = { isAttachmentMenuMounted = false }
                                    ) {
                                        DropdownMenuItem(
                                            colors = settingsDropdownMenuItemColors(),
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            text = {
                                                Text(
                                                    text = stringResource(R.string.choose_image),
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.Image,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                    tint = materialColors.secondaryLabel
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 14.dp),
                                            onClick = {
                                                isAttachmentMenuExpanded = false
                                                launchImagePicker()
                                            }
                                        )
                                        DropdownMenuItem(
                                            colors = settingsDropdownMenuItemColors(),
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            text = {
                                                Text(
                                                    text = stringResource(R.string.choose_document),
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.Description,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                    tint = materialColors.secondaryLabel
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 14.dp),
                                            onClick = {
                                                isAttachmentMenuExpanded = false
                                                launchDocumentPicker()
                                            }
                                        )
                                        DropdownMenuItem(
                                            colors = settingsDropdownMenuItemColors(),
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            text = { Text(stringResource(R.string.invoke_skill), style = MaterialTheme.typography.bodyMedium) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.Extension,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                    tint = materialColors.secondaryLabel
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 14.dp),
                                            onClick = {
                                                isAttachmentMenuExpanded = false
                                                isTextSkillSheetOpen = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            colors = settingsDropdownMenuItemColors(),
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            text = {
                                                Text(
                                                    text = stringResource(R.string.take_photo),
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.PhotoCamera,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                    tint = materialColors.secondaryLabel
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 14.dp),
                                            onClick = {
                                                isAttachmentMenuExpanded = false
                                                launchCameraCapture()
                                            }
                                        )
                                    }
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
                                        color = if (isGenerating || canSend) {
                                            materialColors.primaryLabel
                                        } else {
                                            materialColors.controlFill
                                        },
                                        shape = CircleShape
                                    )
                            )
                            IconButton(
                                enabled = isGenerating || canSend,
                                onClick = if (isGenerating) onStopButtonClick else onSendButtonClick,
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = materialColors.canvas,
                                    disabledContentColor = materialColors.tertiaryLabel
                                )
                            ) {
                                Icon(
                                    modifier = Modifier.size(if (isGenerating) 16.dp else 20.dp),
                                    imageVector = if (isGenerating) {
                                        Icons.Rounded.Stop
                                    } else {
                                        Icons.Rounded.ArrowUpward
                                    },
                                    contentDescription = stringResource(
                                        if (isGenerating) R.string.stop_generating else R.string.send
                                    )
                                )
                            }
                        }
                    }
                }
            }
        )
    }
    if (isTextSkillSheetOpen) {
        TextSkillSelectionSheet(
            skills = availableTextSkills,
            onDismiss = { isTextSkillSheetOpen = false },
            onConfirm = { selected, scope ->
                onTextSkillsSelected(selected, scope)
                isTextSkillSheetOpen = false
            }
        )
    }
}

internal fun canSubmitChatMessage(
    chatEnabled: Boolean,
    sendButtonEnabled: Boolean,
    hasQuestionText: Boolean,
    hasSendableAttachment: Boolean,
    hasUnreadyAttachment: Boolean,
    isAttachmentBusy: Boolean
): Boolean = chatEnabled &&
    sendButtonEnabled &&
    !hasUnreadyAttachment &&
    !isAttachmentBusy &&
    (hasQuestionText || hasSendableAttachment)

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
private fun TextSkillDraftRow(skills: List<SelectedTextSkill>, onRemove: (SelectedTextSkill) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        skills.forEach { skill ->
            Surface(shape = RoundedCornerShape(8.dp), color = settingsMaterialColors().grouped) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(skill.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { onRemove(skill) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.remove), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TextSkillSelectionSheet(
    skills: List<SelectedTextSkill>,
    onDismiss: () -> Unit,
    onConfirm: (List<SelectedTextSkill>, TextSkillSelectionScope) -> Unit
) {
    var selected by remember { mutableStateOf(emptyList<SelectedTextSkill>()) }
    var scope by remember { mutableStateOf(TextSkillSelectionScope.ONE_SHOT) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.invoke_skill), style = MaterialTheme.typography.titleMedium)
            if (skills.isEmpty()) {
                Text(stringResource(R.string.skill_no_available), modifier = Modifier.padding(vertical = 20.dp))
            } else {
                skills.forEach { skill ->
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text(skill.displayName) },
                        modifier = Modifier.clickable {
                            selected = if (skill in selected) selected - skill else selected + skill
                        },
                        trailingContent = {
                            androidx.compose.material3.Checkbox(
                                checked = skill in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + skill else selected - skill
                                }
                            )
                        }
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = scope == TextSkillSelectionScope.ONE_SHOT,
                        onClick = { scope = TextSkillSelectionScope.ONE_SHOT },
                        label = { Text(stringResource(R.string.skill_one_shot)) }
                    )
                    androidx.compose.material3.FilterChip(
                        selected = scope == TextSkillSelectionScope.SESSION,
                        onClick = { scope = TextSkillSelectionScope.SESSION },
                        label = { Text(stringResource(R.string.skill_session)) }
                    )
                }
                androidx.compose.material3.Button(
                    enabled = selected.isNotEmpty(),
                    onClick = { onConfirm(selected, scope) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                ) { Text(stringResource(R.string.confirm)) }
            }
        }
    }
}

/**
 * Attachment/Skill menu shown from the composer.
 *
 * Material 3's [androidx.compose.material3.DropdownMenu] cannot be used here: it validates every
 * candidate position against a 48dp window margin, so a menu anchored to a button that sits inside
 * the input bubble always flips to "above the anchor" and leaves a large gap above the navigation
 * bar. This popup instead pins its own bottom edge to the bottom edge of the input bubble.
 */
@Composable
private fun ComposerActionMenu(
    expanded: Boolean,
    anchorBounds: Rect,
    onDismissRequest: () -> Unit,
    onExitFinished: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val materialColors = settingsMaterialColors()
    val windowMarginPx = with(LocalDensity.current) { ATTACHMENT_MENU_WINDOW_MARGIN_DP.dp.roundToPx() }
    val positionProvider = remember(anchorBounds, windowMarginPx) {
        ComposerBottomAnchoredPositionProvider(anchorBounds, windowMarginPx)
    }
    val visibilityState = remember { MutableTransitionState(false) }.apply { targetState = expanded }
    LaunchedEffect(expanded, visibilityState.isIdle, visibilityState.currentState) {
        if (!expanded && visibilityState.isIdle && !visibilityState.currentState) onExitFinished()
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        AnimatedVisibility(
            visibleState = visibilityState,
            enter = scaleIn(
                animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                initialScale = 0.94f,
                transformOrigin = TransformOrigin(0f, 1f)
            ) + fadeIn(animationSpec = tween(durationMillis = 120), initialAlpha = 0.72f),
            exit = scaleOut(
                animationSpec = tween(durationMillis = 140, easing = FastOutLinearInEasing),
                targetScale = 0.96f,
                transformOrigin = TransformOrigin(0f, 1f)
            ) + fadeOut(animationSpec = tween(durationMillis = 90))
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = materialColors.navigation,
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(0.5.dp, materialColors.separator)
            ) {
                Column(
                    modifier = Modifier
                        .width(ATTACHMENT_MENU_WIDTH_DP.dp)
                        .padding(vertical = 8.dp),
                    content = content
                )
            }
        }
    }
}

/**
 * Places the popup so its bottom edge lines up with the bottom edge of [menuBounds] (the composer's
 * input bubble) and its start edge with that bubble's start edge, clamped to a small window margin.
 * Falls back to the anchor view's own bounds until the bubble has been measured.
 */
private class ComposerBottomAnchoredPositionProvider(
    private val menuBounds: Rect,
    private val windowMarginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val isMeasured = menuBounds.width > 0f && menuBounds.height > 0f
        val left = if (isMeasured) menuBounds.left.roundToInt() else anchorBounds.left
        val right = if (isMeasured) menuBounds.right.roundToInt() else anchorBounds.right
        val bottom = if (isMeasured) menuBounds.bottom.roundToInt() else anchorBounds.bottom
        val x = if (layoutDirection == LayoutDirection.Ltr) left else right - popupContentSize.width
        val maxX = (windowSize.width - popupContentSize.width - windowMarginPx).coerceAtLeast(windowMarginPx)
        val maxY = (windowSize.height - popupContentSize.height - windowMarginPx).coerceAtLeast(windowMarginPx)
        return IntOffset(
            x = x.coerceIn(windowMarginPx, maxX),
            y = (bottom - popupContentSize.height).coerceIn(windowMarginPx, maxY)
        )
    }
}

@Composable
internal fun FileThumbnail(
    attachment: ChatAttachmentDraft,
    onRemove: () -> Unit
) {
    val file = File(attachment.preparedFilePath ?: attachment.sourceFilePath)
    val isImage = isImageFile(file.extension)
    val displayName = attachment.displayName
    // 文档发出去的多半是本地抽取的正文而不是原文件，所以点开要能确认模型到底会读到什么。
    val previewLauncher = LocalAttachmentPreviewLauncher.current
    val previewLabel = stringResource(R.string.document_preview_title)

    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (isImage) {
                        Modifier
                    } else {
                        Modifier.clickable(onClickLabel = previewLabel) {
                            previewLauncher.open(file.absolutePath)
                        }
                    }
                )
        ) {
            if (isImage) {
                LocalImageThumbnail(
                    filePath = file.absolutePath,
                    size = 64.dp,
                    contentDescription = displayName,
                    modifier = Modifier.fillMaxSize(),
                    fallback = {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_image),
                            contentDescription = displayName,
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
                    contentDescription = displayName,
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
            text = displayName,
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
    val pendingDirectory = cameraPendingDirectory(context) ?: return null
    if (!pendingDirectory.exists() && !pendingDirectory.mkdirs()) return null

    return runCatching {
        File.createTempFile(CAMERA_PENDING_PREFIX, ".jpg", pendingDirectory)
    }.getOrNull()
}

internal fun promoteCameraPhotoFile(context: Context, pendingPhotoFile: File): File? {
    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
    val attachmentsDir = File(picturesDir, "attachments")
    if (!attachmentsDir.exists() && !attachmentsDir.mkdirs()) return null
    val target = File(attachmentsDir, "camera_photo_${UUID.randomUUID()}.jpg")
    return target.takeIf { pendingPhotoFile.renameTo(it) }
}

internal fun cleanupAbandonedCameraPhotoFiles(
    pendingDirectory: File?,
    protectedPath: String?
): Int {
    val protectedCanonicalPath = protectedPath
        ?.let(::File)
        ?.let { file -> runCatching { file.canonicalPath }.getOrNull() }
    return pendingDirectory
        ?.listFiles { file -> file.isFile && file.name.startsWith(CAMERA_PENDING_PREFIX) }
        .orEmpty()
        .count { file ->
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
            canonicalPath != protectedCanonicalPath && file.delete()
        }
}

private fun cameraPendingDirectory(context: Context): File? = context
    .getExternalFilesDir(Environment.DIRECTORY_PICTURES)
    ?.let { picturesDir -> File(picturesDir, "attachment-pending-camera") }

internal data class AttachmentCopyBatchResult(
    val copiedPaths: List<String>,
    val failedCount: Int
)

internal fun copyFilesToAppDirectory(
    context: Context,
    uris: List<Uri>,
    onFileCreated: (String) -> Unit = {}
): AttachmentCopyBatchResult {
    val copiedPaths = mutableListOf<String>()
    var failedCount = 0

    uris.distinctBy(Uri::toString).forEach { uri ->
        val copiedPath = copyFileToAppDirectory(context, uri)
        if (copiedPath == null) {
            failedCount += 1
        } else {
            onFileCreated(copiedPath)
            val copiedIdentity = attachmentIdentity(copiedPath)
            if (copiedPaths.any { path -> attachmentIdentity(path) == copiedIdentity }) {
                File(copiedPath).delete()
            } else {
                copiedPaths += copiedPath
            }
        }
    }

    return AttachmentCopyBatchResult(
        copiedPaths = copiedPaths,
        failedCount = failedCount
    )
}

internal suspend fun copyPickedAttachments(
    context: Context,
    uris: List<Uri>,
    onResult: (AttachmentCopyBatchResult) -> Unit
) {
    val createdPaths = Collections.synchronizedSet(mutableSetOf<String>())
    var delivered = false
    try {
        val result = withContext(Dispatchers.IO) {
            copyFilesToAppDirectory(context, uris, createdPaths::add)
        }
        onResult(result)
        delivered = true
    } finally {
        if (!delivered) {
            withContext(NonCancellable + Dispatchers.IO) {
                createdPaths.forEach { filePath -> File(filePath).delete() }
            }
        }
    }
}

internal fun copyFileToAppDirectory(
    context: Context,
    uri: Uri,
    maxBytes: Long = FileUtils.MAX_UPLOAD_SIZE_BYTES
): String? {
    val attachmentsDir = File(context.filesDir, "attachments")
    if (!attachmentsDir.exists() && !attachmentsDir.mkdirs()) return null
    val temporaryFile = runCatching {
        File.createTempFile("attachment_copy_", ".tmp", attachmentsDir)
    }.getOrNull() ?: return null

    return try {
        val rawFileName = getFileName(context, uri)
        val sanitizedFileName = sanitizeFileName(rawFileName)
        val extension = attachmentExtension(
            fileName = sanitizedFileName,
            mimeType = context.contentResolver.getType(uri)
        )
        val digest = MessageDigest.getInstance("SHA-256")

        var copiedBytes = 0L
        var exceededCopyLimit = false
        context.contentResolver.openInputStream(uri)?.use { input ->
            temporaryFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (wouldExceedAttachmentCopyLimit(copiedBytes, count, maxBytes)) {
                        exceededCopyLimit = true
                        break
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    copiedBytes += count
                }
            }
        } ?: return null.also { temporaryFile.delete() }
        if (exceededCopyLimit || copiedBytes <= 0L) return null.also { temporaryFile.delete() }

        val digestHex = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        // 文件名里带上原始名字，chip / 气泡 / <attachment name> 才不会显示成 attachment_9f3a….docx
        val targetName = AttachmentNaming.buildAttachmentFileName(
            digestHex = digestHex,
            baseName = sanitizedFileName.substringBeforeLast('.', sanitizedFileName),
            extension = extension
        )
        val targetFile = File(attachmentsDir, targetName)

        // Verify canonical path is within attachments directory to prevent path traversal.
        val attachmentsDirCanonical = attachmentsDir.canonicalPath
        val targetFileCanonical = targetFile.canonicalPath
        if (!targetFileCanonical.startsWith(attachmentsDirCanonical + File.separator) &&
            targetFileCanonical != attachmentsDirCanonical
        ) {
            return null.also { temporaryFile.delete() }
        }

        if (targetFile.exists()) {
            temporaryFile.delete()
            targetFile.absolutePath
        } else {
            try {
                Files.move(temporaryFile.toPath(), targetFile.toPath())
                targetFile.absolutePath
            } catch (_: FileAlreadyExistsException) {
                temporaryFile.delete()
                targetFile.takeIf(File::exists)?.absolutePath
            }
        }
    } catch (_: Exception) {
        temporaryFile.delete()
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

internal fun attachmentExtension(fileName: String, mimeType: String?): String {
    val fileNameExtension = fileName
        .substringAfterLast('.', "")
        .filter(Char::isLetterOrDigit)
        .take(MAX_ATTACHMENT_EXTENSION_LENGTH)
    if (fileNameExtension.isNotEmpty()) return fileNameExtension

    val normalizedMimeType = mimeType?.trim()?.lowercase()?.substringBefore(';').orEmpty()
    // 文档类：SAF 给的名字偶尔没有后缀，靠 mime 补一个，否则后面按扩展名判类型会失手
    DOCUMENT_MIME_EXTENSIONS[normalizedMimeType]?.let { return it }
    if (!normalizedMimeType.startsWith("image/")) {
        if (normalizedMimeType.startsWith("text/")) return "txt"
        return ""
    }
    val subtype = normalizedMimeType
        .substringAfter('/')
        .substringBefore('+')
        .removePrefix("x-")
    return when (subtype) {
        "jpeg", "pjpeg" -> "jpg"
        else -> subtype.filter(Char::isLetterOrDigit).take(MAX_ATTACHMENT_EXTENSION_LENGTH)
    }
}

private val DOCUMENT_MIME_EXTENSIONS = mapOf(
    "application/pdf" to "pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
    "application/json" to "json",
    "application/ld+json" to "json",
    "application/xml" to "xml",
    "application/xhtml+xml" to "html",
    "application/yaml" to "yaml",
    "application/x-yaml" to "yaml",
    "application/javascript" to "js",
    "application/x-javascript" to "js",
    "application/typescript" to "ts",
    "application/sql" to "sql",
    "application/toml" to "toml",
    "application/x-sh" to "sh",
    "application/x-httpd-php" to "php",
    "application/x-tex" to "tex",
    "text/plain" to "txt",
    "text/markdown" to "md",
    "text/x-markdown" to "md",
    "text/csv" to "csv",
    "text/tab-separated-values" to "tsv",
    "text/html" to "html",
    "text/xml" to "xml"
)

private const val MAX_ATTACHMENT_EXTENSION_LENGTH = 12
private const val CAMERA_PENDING_PREFIX = "camera_pending_"
private const val ATTACHMENT_MENU_WIDTH_DP = 184
private const val ATTACHMENT_MENU_WINDOW_MARGIN_DP = 8

/**
 * 文档选择器的 mime 过滤。必须带上 `application/octet-stream`：SAF 对 `.md` / `.kt` 这类后缀
 * 常常报不出具体类型，不放行的话用户在选择器里根本点不到它们。不受支持的类型仍由附件准入拦掉。
 */
internal val DOCUMENT_PICKER_MIME_TYPES = arrayOf(
    "text/*",
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/json",
    "application/xml",
    "application/octet-stream"
)

@Preview
@Composable
fun EmptyChatComposerPreview() {
    ChatWithChatTheme {
        ChatComposer(availableTextSkills = emptyList())
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
            ),
            availableTextSkills = emptyList()
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
            ),
            availableTextSkills = emptyList()
        )
    }
}
