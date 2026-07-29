package cn.nabr.chatwithchat.presentation.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import cn.nabr.chatwithchat.data.memory.MemoryActivityCategory
import cn.nabr.chatwithchat.data.memory.MemoryActivityStatus
import cn.nabr.chatwithchat.data.memory.MemoryModelPreference
import cn.nabr.chatwithchat.presentation.common.AppleBlue
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    memoryViewModel: MemoryViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val uiState by memoryViewModel.uiState.collectAsStateWithLifecycle()
    val emptyMarkdownText = stringResource(R.string.memory_export_empty_markdown)
    var selectedTab by rememberSaveable { mutableIntStateOf(MEMORY_TAB) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val materialColors = settingsMaterialColors()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                memoryViewModel.refreshMemoryModels()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.memory),
                onNavigationClick = onNavigationClick,
                actions = {
                    if (selectedTab == MEMORY_TAB) {
                        IconButton(onClick = memoryViewModel::exportMarkdown) {
                            Icon(
                                Icons.Outlined.FileDownload,
                                contentDescription = stringResource(R.string.memory_export),
                                tint = AppleBlue
                            )
                        }
                    }
                }
            )
        },
        containerColor = materialColors.canvas
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = materialColors.navigation,
                contentColor = AppleBlue,
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedTab),
                        color = AppleBlue
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == MEMORY_TAB,
                    onClick = { selectedTab = MEMORY_TAB },
                    text = { Text(stringResource(R.string.memory_tab_content)) }
                )
                Tab(
                    selected = selectedTab == LOG_TAB,
                    onClick = { selectedTab = LOG_TAB },
                    text = { Text(stringResource(R.string.memory_tab_log)) }
                )
            }

            if (selectedTab == MEMORY_TAB) {
                MemoryContent(
                    memoryEnabled = uiState.memoryEnabled,
                    memoryModelPreference = uiState.memoryModelPreference,
                    memoryModelOptions = uiState.memoryModelOptions,
                    isMemoryModelLoading = uiState.isMemoryModelLoading,
                    memoryModelError = uiState.memoryModelError,
                    onMemoryModelClick = memoryViewModel::openMemoryModelPicker,
                    markdown = uiState.markdown,
                    emptyMarkdownText = emptyMarkdownText
                )
            } else {
                MemoryActivityLogList(uiState.activityLogs)
            }
        }
    }

    uiState.exportMarkdown?.let { markdown ->
        AlertDialog(
            title = { Text(stringResource(R.string.memory_export_title)) },
            text = {
                SelectionContainer {
                    Text(
                        text = markdown.ifBlank { emptyMarkdownText },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            onDismissRequest = memoryViewModel::closeExport,
            confirmButton = {
                TextButton(onClick = memoryViewModel::closeExport) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    if (uiState.isMemoryModelPickerOpen) {
        MemoryModelPickerDialog(
            preference = uiState.memoryModelPreference,
            options = uiState.memoryModelOptions,
            isSaving = uiState.isMemoryModelSaving,
            error = uiState.memoryModelError,
            onSelect = memoryViewModel::selectMemoryModel,
            onDismiss = memoryViewModel::closeMemoryModelPicker
        )
    }
}

@Composable
private fun MemoryContent(
    memoryEnabled: Boolean,
    memoryModelPreference: MemoryModelPreference,
    memoryModelOptions: List<MemoryModelOption>,
    isMemoryModelLoading: Boolean,
    memoryModelError: MemoryModelUiError?,
    onMemoryModelClick: () -> Unit,
    markdown: String,
    emptyMarkdownText: String
) {
    val materialColors = settingsMaterialColors()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsMaterialGroup {
                MemoryModelSettingRow(
                    preference = memoryModelPreference,
                    options = memoryModelOptions,
                    isLoading = isMemoryModelLoading,
                    error = memoryModelError,
                    onClick = onMemoryModelClick
                )
            }
        }
        if (!memoryEnabled) {
            item {
                SettingsMaterialGroup { MemoryDisabledNotice() }
            }
        }
        item {
            SettingsMaterialGroup {
                SelectionContainer {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        text = markdown.ifBlank { emptyMarkdownText },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (markdown.isBlank()) materialColors.secondaryLabel else materialColors.primaryLabel
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryModelSettingRow(
    preference: MemoryModelPreference,
    options: List<MemoryModelOption>,
    isLoading: Boolean,
    error: MemoryModelUiError?,
    onClick: () -> Unit
) {
    val selectedLabel = memoryModelPreferenceLabel(preference, options)
    val description = when {
        error != null -> memoryModelErrorLabel(error)
        isLoading -> stringResource(R.string.memory_model_loading)
        else -> selectedLabel
    }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading, onClick = onClick)
            .padding(horizontal = 8.dp),
        headlineContent = {
            Text(
                text = stringResource(R.string.memory_model_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = description,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (error == null) {
                    settingsMaterialColors().secondaryLabel
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.arrow_icon),
                modifier = Modifier.size(18.dp)
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun MemoryModelPickerDialog(
    preference: MemoryModelPreference,
    options: List<MemoryModelOption>,
    isSaving: Boolean,
    error: MemoryModelUiError?,
    onSelect: (MemoryModelPreference) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedOption = (preference as? MemoryModelPreference.Fixed)?.let { fixed ->
        options.firstOrNull { option -> option.platformUid == fixed.platformUid && option.modelId == fixed.modelId }
    }
    AlertDialog(
        title = { Text(stringResource(R.string.memory_model_picker_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                error?.let { currentError ->
                    item {
                        Text(
                            text = memoryModelErrorLabel(currentError),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                item {
                    MemoryModelRadioRow(
                        label = stringResource(R.string.memory_model_auto),
                        selected = preference == MemoryModelPreference.Auto,
                        enabled = !isSaving,
                        onClick = { onSelect(MemoryModelPreference.Auto) }
                    )
                }
                if (preference is MemoryModelPreference.Fixed && selectedOption == null) {
                    item {
                        MemoryModelRadioRow(
                            label = stringResource(
                                R.string.memory_model_unavailable_selection,
                                preference.platformUid,
                                preference.modelId
                            ),
                            selected = true,
                            enabled = false,
                            onClick = {}
                        )
                    }
                } else if (preference is MemoryModelPreference.Invalid) {
                    item {
                        MemoryModelRadioRow(
                            label = stringResource(R.string.memory_model_invalid_selection),
                            selected = true,
                            enabled = false,
                            onClick = {}
                        )
                    }
                }
                items(options, key = { option -> "${option.platformUid}:${option.modelId}" }) { option ->
                    MemoryModelRadioRow(
                        label = "${option.platformName} / ${option.modelName}",
                        selected = option == selectedOption,
                        enabled = !isSaving,
                        onClick = { onSelect(option.preference) }
                    )
                }
                if (options.isEmpty() && error != MemoryModelUiError.LOAD_FAILED) {
                    item {
                        Text(
                            text = stringResource(R.string.memory_model_no_available_models),
                            color = settingsMaterialColors().secondaryLabel,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun MemoryModelRadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun memoryModelPreferenceLabel(
    preference: MemoryModelPreference,
    options: List<MemoryModelOption>
): String = when (preference) {
    MemoryModelPreference.Auto -> stringResource(R.string.memory_model_auto)
    is MemoryModelPreference.Fixed ->
        options
            .firstOrNull { option -> option.platformUid == preference.platformUid && option.modelId == preference.modelId }
            ?.let { option -> "${option.platformName} / ${option.modelName}" }
            ?: stringResource(
                R.string.memory_model_unavailable_selection,
                preference.platformUid,
                preference.modelId
            )
    is MemoryModelPreference.Invalid -> stringResource(R.string.memory_model_invalid_selection)
}

@Composable
private fun memoryModelErrorLabel(error: MemoryModelUiError): String = stringResource(
    when (error) {
        MemoryModelUiError.LOAD_FAILED -> R.string.memory_model_load_failed
        MemoryModelUiError.SAVE_FAILED -> R.string.memory_model_save_failed
    }
)

@Composable
private fun MemoryActivityLogList(logs: List<MemoryActivityLog>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (logs.isEmpty()) {
            item {
                SettingsMaterialGroup {
                    Text(
                        text = stringResource(R.string.memory_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = settingsMaterialColors().secondaryLabel,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        items(logs, key = MemoryActivityLog::logId) { log ->
            SettingsMaterialGroup { MemoryActivityLogItem(log) }
        }
    }
}

@Composable
private fun MemoryActivityLogItem(log: MemoryActivityLog) {
    val category = when (log.category) {
        MemoryActivityCategory.MODEL_CALL -> stringResource(R.string.memory_log_category_model_call)
        MemoryActivityCategory.MEMORY_GENERATION -> stringResource(R.string.memory_log_category_generation)
        MemoryActivityCategory.MEMORY_ORGANIZATION -> stringResource(R.string.memory_log_category_organization)
        else -> log.category
    }
    val status = when (log.status) {
        MemoryActivityStatus.RUNNING -> stringResource(R.string.memory_log_status_running)
        MemoryActivityStatus.SUCCEEDED -> stringResource(R.string.memory_log_status_succeeded)
        MemoryActivityStatus.FAILED -> stringResource(R.string.memory_log_status_failed)
        else -> log.status
    }
    val statusColor = when (log.status) {
        MemoryActivityStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
        MemoryActivityStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    val model = listOfNotNull(log.platformName, log.modelName).joinToString(" / ")
    val metadata = buildList {
        add(formatLogTime(log.startedAt))
        if (model.isNotBlank()) add(model)
        log.turnCount?.let { add(stringResource(R.string.memory_log_turn_count, it)) }
        log.operationCount?.let { add(stringResource(R.string.memory_log_operation_count, it)) }
        log.attempt?.let { add(stringResource(R.string.memory_log_attempt, it)) }
    }.joinToString(" · ")

    ListItem(
        headlineContent = { Text(category) },
        supportingContent = {
            Column {
                Text(metadata)
                log.detail?.takeIf(String::isNotBlank)?.let { detail ->
                    Text(
                        text = detail,
                        color = if (log.status == MemoryActivityStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Text(
                    text = stringResource(R.string.memory_log_batch, log.batchId),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = { Text(text = status, color = statusColor) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private fun formatLogTime(epochSeconds: Long): String = LOG_TIME_FORMATTER.format(Instant.ofEpochSecond(epochSeconds))

@Composable
private fun MemoryDisabledNotice() {
    ListItem(
        headlineContent = { Text(stringResource(R.string.memory_disabled_notice_title)) },
        supportingContent = { Text(stringResource(R.string.memory_disabled_notice_description)) },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

private const val MEMORY_TAB = 0
private const val LOG_TAB = 1
private val LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
