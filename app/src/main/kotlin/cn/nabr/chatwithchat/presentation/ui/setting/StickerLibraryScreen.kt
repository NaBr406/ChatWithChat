package cn.nabr.chatwithchat.presentation.ui.setting

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.sticker.StickerCatalogItem
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.settingsSwitchColors
import cn.nabr.chatwithchat.presentation.ui.chat.StickerAssetPreview
import kotlinx.coroutines.flow.collect

@Composable
fun StickerLibraryScreen(
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
    stickerLibraryViewModel: StickerLibraryViewModel = hiltViewModel()
) {
    val uiState by stickerLibraryViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var editingStickerId by remember { mutableStateOf<String?>(null) }
    var deletingStickerId by remember { mutableStateOf<String?>(null) }
    var isSavingMetadata by remember { mutableStateOf(false) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        stickerLibraryViewModel.importStaticImages(uris)
    }
    val fallbackImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        stickerLibraryViewModel.importStaticImages(uris)
    }
    val launchImagePicker = {
        try {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (_: ActivityNotFoundException) {
            try {
                fallbackImagePickerLauncher.launch("image/*")
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, context.getString(R.string.sticker_import_failed_generic), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val metadataSavedText = stringResource(R.string.sticker_library_saved)
    val itemDeletedText = stringResource(R.string.sticker_library_deleted)
    val updateFailedText = stringResource(R.string.sticker_library_update_failed)
    val catalogUnavailableText = stringResource(R.string.sticker_library_catalog_unavailable)

    LaunchedEffect(stickerLibraryViewModel, context) {
        stickerLibraryViewModel.events.collect { event ->
            when (event) {
                is StickerLibraryViewModel.Event.ImportFinished -> {
                    val message = buildList {
                        if (event.importedCount > 0) {
                            add(context.getString(R.string.sticker_library_import_summary, event.importedCount))
                        }
                        if (event.rejectedCount > 0) {
                            add(context.getString(R.string.sticker_library_import_failed, event.rejectedCount))
                        }
                    }.joinToString(separator = " · ")
                    if (message.isNotBlank()) snackbarHostState.showSnackbar(message)
                }

                StickerLibraryViewModel.Event.MetadataUpdated -> {
                    isSavingMetadata = false
                    editingStickerId = null
                    snackbarHostState.showSnackbar(metadataSavedText)
                }

                StickerLibraryViewModel.Event.ItemDeleted -> {
                    deletingStickerId = null
                    snackbarHostState.showSnackbar(itemDeletedText)
                }

                StickerLibraryViewModel.Event.MutationFailed -> {
                    isSavingMetadata = false
                    snackbarHostState.showSnackbar(updateFailedText)
                }

                StickerLibraryViewModel.Event.CatalogUnavailable -> {
                    snackbarHostState.showSnackbar(catalogUnavailableText)
                }
            }
        }
    }

    val editingSticker = uiState.customStickers.firstOrNull { item -> item.stickerId == editingStickerId }
    val deletingSticker = uiState.customStickers.firstOrNull { item -> item.stickerId == deletingStickerId }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.sticker_library),
                onNavigationClick = onNavigationClick,
                actions = {
                    IconButton(
                        enabled = !uiState.isImporting,
                        onClick = launchImagePicker
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AddPhotoAlternate,
                            contentDescription = stringResource(R.string.sticker_library_add)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = settingsMaterialColors().canvas
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "automatic-sticker-replies") {
                StickerAutomaticRepliesItem(
                    enabled = uiState.automaticStickerRepliesEnabled,
                    onEnabledChange = stickerLibraryViewModel::updateAutomaticStickerRepliesEnabled
                )
            }

            item(key = "custom-heading") {
                StickerSectionHeading(stringResource(R.string.sticker_library_my_stickers))
            }

            if (uiState.isImporting) {
                item(key = "import-progress") {
                    StickerImportProgress(
                        completed = uiState.importProgress,
                        total = uiState.importTotal
                    )
                }
            }

            when {
                uiState.isCatalogLoading -> {
                    item(key = "catalog-loading") {
                        StickerLibraryLoadingState()
                    }
                }

                uiState.customStickers.isEmpty() -> {
                    item(key = "custom-empty") {
                        StickerLibraryEmptyState(
                            isUnavailable = uiState.isCatalogUnavailable,
                            onAddClick = launchImagePicker
                        )
                    }
                }

                else -> {
                    items(uiState.customStickers, key = StickerCatalogItem::stickerId) { sticker ->
                        StickerCatalogRow(
                            sticker = sticker,
                            assetResolver = stickerLibraryViewModel,
                            onEnabledChange = { enabled ->
                                stickerLibraryViewModel.setCustomItemEnabled(sticker.stickerId, enabled)
                            },
                            onEditClick = { editingStickerId = sticker.stickerId },
                            onDeleteClick = { deletingStickerId = sticker.stickerId }
                        )
                    }
                }
            }

            if (uiState.builtInStickers.isNotEmpty()) {
                item(key = "builtin-heading") {
                    StickerSectionHeading(stringResource(R.string.sticker_library_builtin_stickers))
                }
                items(uiState.builtInStickers, key = StickerCatalogItem::stickerId) { sticker ->
                    StickerCatalogRow(
                        sticker = sticker,
                        assetResolver = stickerLibraryViewModel,
                        onEnabledChange = {},
                        onEditClick = {},
                        onDeleteClick = {}
                    )
                }
            }

            item(key = "bottom-space") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    editingSticker?.let { sticker ->
        StickerMetadataDialog(
            sticker = sticker,
            isSaving = isSavingMetadata,
            onSave = { title, altText, tags ->
                isSavingMetadata = true
                stickerLibraryViewModel.updateCustomItem(sticker.stickerId, title, altText, tags)
            },
            onDismissRequest = {
                if (!isSavingMetadata) editingStickerId = null
            }
        )
    }

    deletingSticker?.let { sticker ->
        AlertDialog(
            title = { Text(stringResource(R.string.sticker_library_delete)) },
            text = {
                Text(stringResource(R.string.sticker_library_delete_confirmation, sticker.title))
            },
            onDismissRequest = { deletingStickerId = null },
            confirmButton = {
                TextButton(onClick = { stickerLibraryViewModel.deleteCustomItem(sticker.stickerId) }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingStickerId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun StickerAutomaticRepliesItem(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    SettingsMaterialGroup(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = onEnabledChange
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = settingsMaterialColors().primaryLabel
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sticker_library_auto_replies),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = stringResource(R.string.sticker_library_auto_replies_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsMaterialColors().secondaryLabel
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = settingsSwitchColors()
            )
        }
    }
}

@Composable
private fun StickerSectionHeading(title: String) {
    Text(
        modifier = Modifier.padding(start = 32.dp, top = 12.dp, end = 16.dp, bottom = 2.dp),
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = settingsMaterialColors().secondaryLabel
    )
}

@Composable
private fun StickerImportProgress(
    completed: Int,
    total: Int
) {
    SettingsMaterialGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sticker_library_importing, completed, total),
                style = MaterialTheme.typography.bodyMedium
            )
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else completed.toFloat() / total },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun StickerLibraryLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun StickerLibraryEmptyState(
    isUnavailable: Boolean,
    onAddClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.AddPhotoAlternate,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = settingsMaterialColors().tertiaryLabel
        )
        Text(
            modifier = Modifier.padding(top = 10.dp),
            text = if (isUnavailable) {
                stringResource(R.string.sticker_library_catalog_unavailable)
            } else {
                stringResource(R.string.sticker_library_empty)
            },
            style = MaterialTheme.typography.titleSmall,
            color = settingsMaterialColors().primaryLabel
        )
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = if (isUnavailable) {
                stringResource(R.string.sticker_import_failed_generic)
            } else {
                stringResource(R.string.sticker_library_empty_description)
            },
            style = MaterialTheme.typography.bodySmall,
            color = settingsMaterialColors().secondaryLabel
        )
        TextButton(onClick = onAddClick) {
            Text(stringResource(R.string.sticker_library_add))
        }
    }
}

@Composable
private fun StickerCatalogRow(
    sticker: StickerCatalogItem,
    assetResolver: StickerLibraryViewModel,
    onEnabledChange: (Boolean) -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember(sticker.stickerId) { mutableStateOf(false) }
    SettingsMaterialGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StickerAssetPreview(
                assetKey = sticker.asset.assetKey,
                altText = sticker.altText,
                mediaKind = sticker.asset.mediaKind,
                assetResolver = assetResolver,
                size = 64.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sticker.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = sticker.altText,
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsMaterialColors().secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (sticker.tags.isNotEmpty()) {
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = sticker.tags.joinToString(separator = " · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = settingsMaterialColors().tertiaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (sticker.isBuiltin) {
                Text(
                    text = stringResource(R.string.sticker_builtin),
                    style = MaterialTheme.typography.labelMedium,
                    color = settingsMaterialColors().secondaryLabel
                )
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Switch(
                        checked = sticker.enabled,
                        onCheckedChange = onEnabledChange,
                        colors = settingsSwitchColors()
                    )
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.options)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sticker_library_edit)) },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Outlined.Edit, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onEditClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sticker_library_delete)) },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Outlined.Delete, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDeleteClick()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerMetadataDialog(
    sticker: StickerCatalogItem,
    isSaving: Boolean,
    onSave: (title: String, altText: String, tags: List<String>) -> Unit,
    onDismissRequest: () -> Unit
) {
    var title by remember(sticker.stickerId, sticker.updatedAt) { mutableStateOf(sticker.title) }
    var altText by remember(sticker.stickerId, sticker.updatedAt) { mutableStateOf(sticker.altText) }
    var tagsText by remember(sticker.stickerId, sticker.updatedAt) { mutableStateOf(sticker.tags.joinToString(", ")) }
    val tags = remember(tagsText) {
        tagsText.split(',', '，', ';', '；', '、')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(12)
    }

    AlertDialog(
        title = { Text(stringResource(R.string.sticker_library_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = title,
                    onValueChange = { value -> title = value.take(80) },
                    label = { Text(stringResource(R.string.sticker_title)) },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = altText,
                    onValueChange = { value -> altText = value.take(160) },
                    label = { Text(stringResource(R.string.sticker_alt_text)) },
                    minLines = 2,
                    maxLines = 3
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = tagsText,
                    onValueChange = { value -> tagsText = value.take(384) },
                    label = { Text(stringResource(R.string.sticker_tags)) },
                    supportingText = { Text(stringResource(R.string.sticker_tags_supporting)) },
                    minLines = 1,
                    maxLines = 2
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = !isSaving && title.isNotBlank() && altText.isNotBlank(),
                onClick = { onSave(title, altText, tags) }
            ) {
                Text(stringResource(R.string.sticker_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
