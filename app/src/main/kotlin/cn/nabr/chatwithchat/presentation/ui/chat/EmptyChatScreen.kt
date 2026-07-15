package cn.nabr.chatwithchat.presentation.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.ui.home.HomeModelsState
import cn.nabr.chatwithchat.presentation.ui.home.HomeViewModel
import cn.nabr.chatwithchat.presentation.ui.home.canStartChat
import cn.nabr.chatwithchat.presentation.ui.home.modelsOrEmpty
import cn.nabr.chatwithchat.presentation.ui.home.shouldShowAddProvider
import cn.nabr.chatwithchat.util.FileUtils
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmptyChatScreen(
    homeViewModel: HomeViewModel,
    onOpenDrawer: () -> Unit,
    onStartChat: (String, List<String>) -> Boolean,
    onAddProvider: () -> Unit
) {
    val lastSelectedModel by homeViewModel.lastSelectedModel.collectAsStateWithLifecycle()
    val homeModelsState by homeViewModel.homeModelsState.collectAsStateWithLifecycle()
    val availableChatModels = homeModelsState.modelsOrEmpty()
    val currentModelOptions = remember(availableChatModels, lastSelectedModel) {
        buildModelSelectionOptions(
            models = availableChatModels,
            selectedPlatformUid = lastSelectedModel?.platformUid,
            selectedModel = lastSelectedModel?.model
        )
    }
    val currentModelLabel = currentModelOptions.firstOrNull { it.selected }?.label
        ?: currentModelOptions.firstOrNull()?.label
        ?: stringResource(R.string.chat_models)
    val currentReasoningMode = lastSelectedModel?.reasoningMode ?: ReasoningMode.AUTO
    val inputState = rememberTextFieldState()
    val context = LocalContext.current
    var selectedAttachments by remember { mutableStateOf(listOf<ChatAttachmentDraft>()) }
    val latestSelectedAttachments = rememberUpdatedState(selectedAttachments)
    val attachmentsTransferred = remember { mutableStateOf(false) }
    val canChat = homeModelsState.canStartChat() && currentModelOptions.isNotEmpty()

    DisposableEffect(Unit) {
        onDispose {
            if (!attachmentsTransferred.value) {
                latestSelectedAttachments.value
                    .filter { it.cleanupOnDiscard }
                    .forEach { attachment -> File(attachment.sourceFilePath).delete() }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = settingsMaterialColors().canvas,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsMaterialColors().navigation,
                    scrolledContainerColor = settingsMaterialColors().navigation,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    when {
                        canChat -> {
                            ModelSelectionMenu(
                                label = currentModelLabel,
                                options = currentModelOptions,
                                selectedReasoningMode = currentReasoningMode,
                                enabled = true,
                                onOptionSelected = { option ->
                                    homeViewModel.updateLastSelectedModel(option.platformUid, option.model, currentReasoningMode)
                                },
                                onReasoningModeSelected = homeViewModel::updateLastSelectedReasoningMode
                            )
                        }

                        homeModelsState.shouldShowAddProvider() -> {
                            Text(
                                text = stringResource(R.string.empty_chat_no_platforms),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> Unit
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = stringResource(R.string.open_chat_history)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (homeModelsState is HomeModelsState.Loading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = stringResource(R.string.empty_chat_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            if (canChat) {
                ChatComposer(
                    inputState = inputState,
                    chatEnabled = true,
                    sendButtonEnabled = true,
                    selectedAttachments = selectedAttachments,
                    onFilesSelected = { filePaths, copyFailureCount ->
                        val current = selectedAttachments
                        val existingCandidates = current.map { attachment ->
                            attachment.sourceFilePath.toEmptyChatAdmissionCandidate(context)
                        }
                        val candidates = filePaths.map { filePath ->
                            filePath.toEmptyChatAdmissionCandidate(context)
                        }
                        val admission = admitAttachmentBatch(
                            existingIdentities = existingCandidates.mapTo(mutableSetOf()) { it.identity },
                            existingBytes = existingCandidates.sumOf { it.sizeBytes.coerceAtLeast(0L) },
                            candidates = candidates,
                            maxBytes = FileUtils.MAX_UPLOAD_SIZE_BYTES
                        )

                        val existingPaths = current.mapTo(mutableSetOf()) { it.sourceFilePath }
                        attachmentPathsToDiscard(existingPaths, candidates, admission).forEach { filePath ->
                            File(filePath).delete()
                        }

                        selectedAttachments = current + admission.accepted.map { candidate ->
                            ChatAttachmentDraft(
                                sourceFilePath = candidate.path,
                                status = ChatAttachmentDraft.Status.Ready,
                                cleanupOnDiscard = true
                            )
                        }
                        val totalRejectedCount = admission.totalRejectedCount(copyFailureCount)
                        if (totalRejectedCount > 0) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.attachment_batch_rejected, totalRejectedCount),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onFileRemoved = { filePath ->
                        selectedAttachments = selectedAttachments.filter { it.sourceFilePath != filePath }
                        File(filePath).delete()
                    },
                    onSendButtonClick = {
                        val prompt = inputState.text.toString().trim()
                        val attachmentPaths = selectedAttachments.map { it.sourceFilePath }
                        if (prompt.isNotEmpty() || attachmentPaths.isNotEmpty()) {
                            if (onStartChat(prompt, attachmentPaths)) {
                                attachmentsTransferred.value = true
                                selectedAttachments = emptyList()
                                inputState.clearText()
                            }
                        }
                    }
                )
            } else if (homeModelsState.shouldShowAddProvider()) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .height(56.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    onClick = onAddProvider
                ) {
                    Text(text = stringResource(R.string.add_platform))
                }
            }
        }
    }
}

private fun String.toEmptyChatAdmissionCandidate(context: android.content.Context): AttachmentAdmissionCandidate {
    val mimeType = FileUtils.getMimeType(context, this)
    return AttachmentAdmissionCandidate(
        path = this,
        identity = attachmentIdentity(this),
        sizeBytes = FileUtils.getFileSize(context, this),
        isSupported = FileUtils.isSupportedUploadMimeType(mimeType)
    )
}
