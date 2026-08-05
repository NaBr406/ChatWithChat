package cn.nabr.chatwithchat.presentation.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import cn.nabr.chatwithchat.data.memory.MemoryActivityCategory
import cn.nabr.chatwithchat.data.memory.MemoryActivityPhase
import cn.nabr.chatwithchat.data.memory.MemoryActivityPhaseHistory
import cn.nabr.chatwithchat.data.memory.MemoryActivityPhaseSummary
import cn.nabr.chatwithchat.data.memory.MemoryActivityStatus
import cn.nabr.chatwithchat.data.memory.MemoryModelPreference
import cn.nabr.chatwithchat.data.memory.MemoryRecallState
import cn.nabr.chatwithchat.data.memory.MemoryScope
import cn.nabr.chatwithchat.data.memory.MemorySensitivity
import cn.nabr.chatwithchat.data.memory.MemorySource
import cn.nabr.chatwithchat.data.memory.MemoryValidity
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsDropdownMenuItemColors
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    memoryViewModel: MemoryViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val uiState by memoryViewModel.uiState.collectAsStateWithLifecycle()
    val emptyMarkdownText = stringResource(R.string.memory_export_empty_markdown)
    var selectedTab by rememberSaveable { mutableIntStateOf(MEMORY_TAB) }
    var isActionsMenuOpen by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val materialColors = settingsMaterialColors()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(memoryViewModel, context) {
        memoryViewModel.events.collect { event ->
            val message = when (event) {
                is MemoryViewModel.Event.LongTermConsolidationFeedback -> context.getString(
                    when (event.result) {
                        LongTermConsolidationUiResult.STARTED -> R.string.memory_consolidation_started
                        LongTermConsolidationUiResult.ALREADY_RUNNING -> R.string.memory_consolidation_already_running
                        LongTermConsolidationUiResult.COMPLETED -> R.string.memory_consolidation_completed
                        LongTermConsolidationUiResult.MEMORY_DISABLED -> R.string.memory_consolidation_memory_disabled
                        LongTermConsolidationUiResult.FAILED -> R.string.memory_consolidation_failed
                    }
                )
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                memoryViewModel.refreshMemoryEnabled()
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
                    Box {
                        IconButton(
                            enabled = !uiState.isLongTermConsolidationScheduling,
                            onClick = { isActionsMenuOpen = true }
                        ) {
                            if (uiState.isLongTermConsolidationScheduling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = materialColors.primaryLabel
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.memory_actions),
                                    tint = materialColors.primaryLabel
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = isActionsMenuOpen,
                            onDismissRequest = { isActionsMenuOpen = false },
                            shape = RoundedCornerShape(8.dp),
                            containerColor = materialColors.grouped,
                            tonalElevation = 0.dp
                        ) {
                            DropdownMenuItem(
                                colors = settingsDropdownMenuItemColors(),
                                text = {
                                    Text(
                                        text = stringResource(R.string.memory_consolidate_now),
                                        color = materialColors.primaryLabel
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoFixHigh,
                                        contentDescription = null,
                                        tint = materialColors.primaryLabel
                                    )
                                },
                                onClick = {
                                    isActionsMenuOpen = false
                                    memoryViewModel.consolidateLongTermMemoryNow()
                                }
                            )
                            DropdownMenuItem(
                                colors = settingsDropdownMenuItemColors(),
                                text = {
                                    Text(
                                        text = stringResource(R.string.memory_force_consolidate_now),
                                        color = materialColors.primaryLabel
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoFixHigh,
                                        contentDescription = null,
                                        tint = materialColors.primaryLabel
                                    )
                                },
                                onClick = {
                                    isActionsMenuOpen = false
                                    memoryViewModel.requestForceLongTermConsolidation()
                                }
                            )
                            DropdownMenuItem(
                                colors = settingsDropdownMenuItemColors(),
                                text = {
                                    Text(
                                        text = stringResource(R.string.memory_model_title),
                                        color = materialColors.primaryLabel
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Tune,
                                        contentDescription = null,
                                        tint = materialColors.primaryLabel
                                    )
                                },
                                enabled = !uiState.isMemoryModelLoading && !uiState.isMemoryModelSaving,
                                onClick = {
                                    isActionsMenuOpen = false
                                    memoryViewModel.openMemoryModelPicker()
                                }
                            )
                            DropdownMenuItem(
                                colors = settingsDropdownMenuItemColors(),
                                text = {
                                    Text(
                                        text = stringResource(R.string.memory_export),
                                        color = materialColors.primaryLabel
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.FileDownload,
                                        contentDescription = null,
                                        tint = materialColors.primaryLabel
                                    )
                                },
                                onClick = {
                                    isActionsMenuOpen = false
                                    memoryViewModel.exportMarkdown()
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { snackbarData ->
                Snackbar(
                    snackbarData = snackbarData,
                    containerColor = materialColors.grouped,
                    contentColor = materialColors.primaryLabel
                )
            }
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
                contentColor = materialColors.primaryLabel,
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedTab),
                        color = materialColors.primaryLabel
                    )
                },
                divider = {
                    HorizontalDivider(thickness = 0.5.dp, color = materialColors.separator)
                }
            ) {
                Tab(
                    selected = selectedTab == MEMORY_TAB,
                    onClick = { selectedTab = MEMORY_TAB },
                    text = { Text(stringResource(R.string.memory_tab_content)) },
                    selectedContentColor = materialColors.primaryLabel,
                    unselectedContentColor = materialColors.secondaryLabel
                )
                Tab(
                    selected = selectedTab == LOG_TAB,
                    onClick = { selectedTab = LOG_TAB },
                    text = { Text(stringResource(R.string.memory_tab_log)) },
                    selectedContentColor = materialColors.primaryLabel,
                    unselectedContentColor = materialColors.secondaryLabel
                )
            }

            if (selectedTab == MEMORY_TAB) {
                MemoryContent(
                    memoryEnabled = uiState.memoryEnabled,
                    markdown = uiState.markdown,
                    sections = uiState.sections,
                    historyEntries = uiState.historyEntries,
                    hiddenHistoryCount = uiState.hiddenHistoryCount,
                    parseStatus = uiState.parseStatus
                )
            } else {
                MemoryActivityLogList(uiState.activityLogs)
            }
        }
    }

    uiState.exportMarkdown?.let { markdown ->
        MemoryExportDialog(
            markdown = markdown,
            emptyMarkdownText = emptyMarkdownText,
            onDismiss = memoryViewModel::closeExport
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

    if (uiState.isForceLongTermConsolidationConfirmationOpen) {
        ForceLongTermConsolidationConfirmationDialog(
            onConfirm = memoryViewModel::forceLongTermConsolidationNow,
            onDismiss = memoryViewModel::dismissForceLongTermConsolidationConfirmation
        )
    }
}

@Composable
private fun ForceLongTermConsolidationConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    MemoryDialogFrame(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                text = stringResource(R.string.memory_force_consolidate_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = materialColors.primaryLabel
            )
            Text(
                modifier = Modifier.padding(top = 10.dp),
                text = stringResource(R.string.memory_force_consolidate_description),
                style = MaterialTheme.typography.bodyMedium,
                color = materialColors.secondaryLabel
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = materialColors.separatorStrong)
        MemoryDialogAction(
            label = stringResource(R.string.memory_force_consolidate_confirm),
            enabled = true,
            onClick = onConfirm
        )
        HorizontalDivider(thickness = 0.5.dp, color = materialColors.separatorStrong)
        MemoryDialogAction(
            label = stringResource(R.string.cancel),
            enabled = true,
            onClick = onDismiss
        )
    }
}

@Composable
private fun MemoryContent(
    memoryEnabled: Boolean,
    markdown: String,
    sections: List<MemoryProjectionSection>,
    historyEntries: List<MemoryProjectionEntry>,
    hiddenHistoryCount: Int,
    parseStatus: MemoryProjectionParseStatus
) {
    val materialColors = settingsMaterialColors()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!memoryEnabled) {
            item {
                SettingsMaterialGroup { MemoryDisabledNotice() }
            }
        }
        if (parseStatus == MemoryProjectionParseStatus.PARTIAL ||
            parseStatus == MemoryProjectionParseStatus.FAILED
        ) {
            item {
                MemoryProjectionNotice(parseStatus)
            }
        }
        if (sections.isNotEmpty()) {
            items(sections, key = { section -> section.type }) { section ->
                MemoryProjectionSectionGroup(section)
            }
        } else if (markdown.isBlank()) {
            item {
                SettingsMaterialGroup {
                    Text(
                        modifier = Modifier.padding(16.dp),
                        text = stringResource(R.string.memory_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = materialColors.secondaryLabel
                    )
                }
            }
        } else {
            item {
                MemoryProjectionNoActiveEntriesNotice()
            }
        }
        if (hiddenHistoryCount > 0) {
            item {
                MemoryHistoryGroup(historyEntries)
            }
        }
    }
}

@Composable
private fun MemoryProjectionNoActiveEntriesNotice() {
    val materialColors = settingsMaterialColors()
    SettingsMaterialGroup {
        ListItem(
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = materialColors.primaryLabel,
                supportingColor = materialColors.secondaryLabel
            ),
            headlineContent = {
                Text(text = stringResource(R.string.memory_projection_no_active_title))
            },
            supportingContent = {
                Text(text = stringResource(R.string.memory_projection_no_active_description))
            }
        )
    }
}

@Composable
private fun MemoryProjectionNotice(status: MemoryProjectionParseStatus) {
    val materialColors = settingsMaterialColors()
    SettingsMaterialGroup {
        ListItem(
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = materialColors.primaryLabel,
                supportingColor = materialColors.secondaryLabel,
                leadingIconColor = materialColors.secondaryLabel
            ),
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null
                )
            },
            headlineContent = {
                Text(
                    text = stringResource(
                        if (status == MemoryProjectionParseStatus.FAILED) {
                            R.string.memory_projection_parse_failed_title
                        } else {
                            R.string.memory_projection_partial_title
                        }
                    )
                )
            },
            supportingContent = {
                Text(
                    text = stringResource(
                        if (status == MemoryProjectionParseStatus.FAILED) {
                            R.string.memory_projection_parse_failed_description
                        } else {
                            R.string.memory_projection_partial_description
                        }
                    )
                )
            }
        )
    }
}

@Composable
private fun MemoryProjectionSectionGroup(section: MemoryProjectionSection) {
    var expanded by rememberSaveable(section.type) { mutableStateOf(true) }
    val materialColors = settingsMaterialColors()
    SettingsMaterialGroup {
        MemoryProjectionGroupHeader(
            title = memoryTypeTitle(section.type),
            count = section.entries.size,
            expanded = expanded,
            onClick = { expanded = !expanded }
        )
        if (expanded) {
            HorizontalDivider(thickness = 0.5.dp, color = materialColors.separator)
            section.entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(thickness = 0.5.dp, color = materialColors.separator)
                }
                MemoryProjectionEntryRow(entry)
            }
        }
    }
}

@Composable
private fun MemoryHistoryGroup(historyEntries: List<MemoryProjectionEntry>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val materialColors = settingsMaterialColors()
    SettingsMaterialGroup {
        MemoryProjectionGroupHeader(
            title = stringResource(R.string.memory_projection_history_title),
            count = historyEntries.size,
            expanded = expanded,
            onClick = { expanded = !expanded }
        )
        if (expanded) {
            HorizontalDivider(thickness = 0.5.dp, color = materialColors.separator)
            historyEntries.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(thickness = 0.5.dp, color = materialColors.separator)
                }
                MemoryProjectionEntryRow(entry, historyStatus = memoryHistoryStatus(entry))
            }
        }
    }
}

@Composable
private fun MemoryProjectionGroupHeader(
    title: String,
    count: Int?,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = materialColors.primaryLabel,
            supportingColor = materialColors.secondaryLabel,
            trailingIconColor = materialColors.tertiaryLabel
        ),
        headlineContent = {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold
            )
        },
        supportingContent = {
            count?.let { value ->
                Text(stringResource(R.string.memory_projection_count, value))
            }
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(
                    if (expanded) R.string.memory_projection_collapse else R.string.memory_projection_expand
                ),
                modifier = Modifier.rotate(if (expanded) 90f else 0f)
            )
        }
    )
}

@Composable
private fun MemoryProjectionEntryRow(
    entry: MemoryProjectionEntry,
    historyStatus: String? = null
) {
    val materialColors = settingsMaterialColors()
    val metadata = buildList {
        historyStatus?.let(::add)
        add(memorySourceLabel(entry.source))
        add(memorySensitivityLabel(entry.sensitivity))
        add(memoryScopeLabel(entry.scope))
    }.joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SelectionContainer {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyMedium,
                color = materialColors.primaryLabel
            )
        }
        Text(
            modifier = Modifier.padding(top = 6.dp),
            text = metadata,
            style = MaterialTheme.typography.bodySmall,
            color = materialColors.secondaryLabel
        )
    }
}

@Composable
private fun memoryTypeTitle(type: String): String = when (type) {
    "stable_profile" -> stringResource(R.string.memory_type_stable_profile)
    "communication_style" -> stringResource(R.string.memory_type_communication_style)
    "boundary" -> stringResource(R.string.memory_type_boundary)
    "project_context" -> stringResource(R.string.memory_type_project_context)
    "interest" -> stringResource(R.string.memory_type_interest)
    "important_event" -> stringResource(R.string.memory_type_important_event)
    "important_person" -> stringResource(R.string.memory_type_important_person)
    "emotional_pattern" -> stringResource(R.string.memory_type_emotional_pattern)
    "life_context" -> stringResource(R.string.memory_type_life_context)
    "recurring_theme" -> stringResource(R.string.memory_type_recurring_theme)
    "light_productivity_preference" -> stringResource(R.string.memory_type_light_productivity_preference)
    else -> stringResource(R.string.memory_type_other)
}

@Composable
private fun memorySourceLabel(source: String): String = when (source) {
    MemorySource.EXPLICIT_USER_STATEMENT -> stringResource(R.string.memory_source_explicit_user_statement)
    MemorySource.ASSISTANT_INFERRED -> stringResource(R.string.memory_source_assistant_inferred)
    MemorySource.USER_CONFIRMED -> stringResource(R.string.memory_source_user_confirmed)
    else -> stringResource(R.string.memory_source_other)
}

@Composable
private fun memorySensitivityLabel(sensitivity: String): String = when (sensitivity) {
    MemorySensitivity.NORMAL -> stringResource(R.string.memory_sensitivity_normal)
    MemorySensitivity.PRIVATE -> stringResource(R.string.memory_sensitivity_private)
    MemorySensitivity.SENSITIVE -> stringResource(R.string.memory_sensitivity_sensitive)
    else -> stringResource(R.string.memory_sensitivity_uncategorized)
}

@Composable
private fun memoryScopeLabel(scope: String): String = when (scope) {
    MemoryScope.GENERAL -> stringResource(R.string.memory_scope_general)
    MemoryScope.WORK -> stringResource(R.string.memory_scope_work)
    MemoryScope.PERSONAL -> stringResource(R.string.memory_scope_personal)
    else -> stringResource(R.string.memory_scope_other)
}

@Composable
private fun memoryHistoryStatus(entry: MemoryProjectionEntry): String = when {
    entry.validity == MemoryValidity.CONTESTED -> stringResource(R.string.memory_status_pending)
    entry.validity == MemoryValidity.OBSOLETE && entry.supersededBy != null ->
        stringResource(R.string.memory_status_superseded)
    entry.validity == MemoryValidity.CURRENT && entry.recallState in setOf(
        MemoryRecallState.CORE,
        MemoryRecallState.QUERY
    ) -> stringResource(R.string.memory_status_active)
    else -> stringResource(R.string.memory_status_unknown)
}

@Composable
private fun MemoryExportDialog(
    markdown: String,
    emptyMarkdownText: String,
    onDismiss: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    MemoryDialogFrame(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.FileDownload,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = materialColors.primaryLabel
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.memory_export_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = materialColors.primaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = materialColors.secondaryLabel
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = materialColors.separatorStrong)
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    text = markdown.ifBlank { emptyMarkdownText },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (markdown.isBlank()) materialColors.secondaryLabel else materialColors.primaryLabel
                )
            }
        }
    }
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
    val materialColors = settingsMaterialColors()
    MemoryDialogFrame(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.memory_model_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = materialColors.primaryLabel
                )
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = memoryModelPreferenceLabel(preference, options),
                    style = MaterialTheme.typography.bodySmall,
                    color = materialColors.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = materialColors.primaryLabel
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = materialColors.separatorStrong)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(weight = 1f, fill = false)
                .heightIn(max = 420.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) {
            error?.let { currentError ->
                item {
                    Text(
                        text = memoryModelErrorLabel(currentError),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            item {
                MemoryModelOptionRow(
                    label = stringResource(R.string.memory_model_auto),
                    supportingLabel = stringResource(R.string.memory_model_auto_description),
                    selected = preference == MemoryModelPreference.Auto,
                    enabled = !isSaving,
                    onClick = { onSelect(MemoryModelPreference.Auto) }
                )
            }
            if (preference is MemoryModelPreference.Fixed && selectedOption == null) {
                item {
                    MemoryModelOptionRow(
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
                    MemoryModelOptionRow(
                        label = stringResource(R.string.memory_model_invalid_selection),
                        selected = true,
                        enabled = false,
                        onClick = {}
                    )
                }
            }
            items(options, key = { option -> "${option.platformUid}:${option.modelId}" }) { option ->
                MemoryModelOptionRow(
                    label = option.modelName,
                    supportingLabel = option.platformName,
                    selected = option == selectedOption,
                    enabled = !isSaving,
                    onClick = { onSelect(option.preference) }
                )
            }
            if (options.isEmpty() && error != MemoryModelUiError.LOAD_FAILED) {
                item {
                    Text(
                        text = stringResource(R.string.memory_model_no_available_models),
                        color = materialColors.secondaryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = materialColors.separatorStrong)
        MemoryDialogAction(
            label = stringResource(R.string.cancel),
            enabled = !isSaving,
            onClick = onDismiss
        )
    }
}

@Composable
private fun MemoryModelOptionRow(
    label: String,
    supportingLabel: String? = null,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    Row(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (enabled) materialColors.primaryLabel else materialColors.tertiaryLabel
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) materialColors.primaryLabel else materialColors.tertiaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            supportingLabel?.let { supportingText ->
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) materialColors.secondaryLabel else materialColors.tertiaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MemoryDialogFrame(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val materialColors = settingsMaterialColors()
    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val maxDialogHeight = (windowHeight - 48.dp).coerceAtLeast(240.dp)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .heightIn(max = maxDialogHeight)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = materialColors.grouped,
                contentColor = materialColors.primaryLabel,
                border = BorderStroke(0.5.dp, materialColors.separatorStrong),
                tonalElevation = 0.dp,
                shadowElevation = 16.dp,
                content = { Column(content = content) }
            )
        }
    }
}

@Composable
private fun MemoryDialogAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) settingsMaterialColors().primaryLabel else settingsMaterialColors().tertiaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
internal fun MemoryActivityLogItem(log: MemoryActivityLog) {
    val materialColors = settingsMaterialColors()
    val category = when (log.category) {
        MemoryActivityCategory.MAINTENANCE_PLANNING -> stringResource(R.string.memory_log_category_maintenance_planning)
        MemoryActivityCategory.TURN_BATCH_CONSOLIDATION -> stringResource(R.string.memory_log_category_turn_batch)
        MemoryActivityCategory.DAILY_DISTILLATION -> stringResource(R.string.memory_log_category_daily_distillation)
        MemoryActivityCategory.LONG_TERM_CONSOLIDATION -> stringResource(R.string.memory_log_category_long_term)
        MemoryActivityCategory.MODEL_CALL -> stringResource(R.string.memory_log_category_model_call)
        MemoryActivityCategory.MEMORY_GENERATION -> stringResource(R.string.memory_log_category_generation)
        MemoryActivityCategory.MEMORY_ORGANIZATION -> stringResource(R.string.memory_log_category_organization)
        else -> log.jobType ?: log.category
    }
    val status = memoryActivityStatusLabel(log.status)
    val statusColor = when (log.status) {
        MemoryActivityStatus.SCHEDULED,
        MemoryActivityStatus.RUNNING,
        MemoryActivityStatus.SUCCEEDED -> materialColors.primaryLabel
        MemoryActivityStatus.FAILED,
        MemoryActivityStatus.BLOCKED -> MaterialTheme.colorScheme.error
        else -> materialColors.secondaryLabel
    }
    val model = listOfNotNull(
        log.platformName ?: log.platformUid,
        log.modelName ?: log.modelId
    ).joinToString(" / ")
    val phaseHistory = remember(log.phaseSummaryJson) {
        log.phaseSummaryJson?.let { encoded ->
            runCatching { MemoryActivityPhaseHistory.decode(encoded) }.getOrNull()
        }
    }
    var isExpanded by rememberSaveable(log.logId) { mutableStateOf(false) }
    val metadata = buildList {
        add(formatLogTime(log.startedAt))
        if (model.isNotBlank()) add(model)
        log.inputCount?.let { add(stringResource(R.string.memory_log_input_count, it)) }
            ?: log.turnCount?.let { add(stringResource(R.string.memory_log_turn_count, it)) }
        log.operationCount?.let { add(stringResource(R.string.memory_log_operation_count, it)) }
        log.attempt?.let { add(stringResource(R.string.memory_log_attempt, it)) }
        if (log.retryCycle > 0) add(stringResource(R.string.memory_log_retry_cycle, log.retryCycle))
        log.triggerReason?.takeIf(String::isNotBlank)?.let(::add)
        add(memoryActivityDurationLabel(log.startedAt, log.completedAt ?: log.updatedAt))
    }.joinToString(" · ")

    ListItem(
        headlineContent = { Text(category) },
        supportingContent = {
            Column {
                Text(metadata, color = materialColors.secondaryLabel)
                log.phase?.let { phase ->
                    Text(
                        text = stringResource(R.string.memory_log_phase, memoryActivityPhaseLabel(phase)),
                        color = materialColors.secondaryLabel
                    )
                }
                log.errorCode?.takeIf(String::isNotBlank)?.let { errorCode ->
                    Text(
                        text = stringResource(R.string.memory_log_error_code, errorCode),
                        color = if (log.status in setOf(MemoryActivityStatus.FAILED, MemoryActivityStatus.BLOCKED)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            materialColors.secondaryLabel
                        }
                    )
                }
                log.detail?.takeIf(String::isNotBlank)?.let { detail ->
                    Text(
                        text = detail,
                        color = if (log.status == MemoryActivityStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            materialColors.secondaryLabel
                        }
                    )
                }
                if (isExpanded) {
                    phaseHistory?.phases.orEmpty().forEach { phase ->
                        MemoryActivityPhaseLine(phase)
                    }
                }
                Text(
                    text = if (log.jobId != null) {
                        stringResource(R.string.memory_log_job, log.jobId)
                    } else {
                        stringResource(R.string.memory_log_batch, log.batchId)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = materialColors.secondaryLabel
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = status, color = statusColor)
                if (!phaseHistory?.phases.isNullOrEmpty()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(if (isExpanded) 90f else 0f)
                    )
                }
            }
        },
        modifier = if (!phaseHistory?.phases.isNullOrEmpty()) {
            Modifier
                .testTag(MEMORY_ACTIVITY_ROW_TEST_TAG)
                .clickable(role = Role.Button) { isExpanded = !isExpanded }
        } else {
            Modifier.testTag(MEMORY_ACTIVITY_ROW_TEST_TAG)
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = materialColors.primaryLabel,
            supportingColor = materialColors.secondaryLabel,
            trailingIconColor = materialColors.tertiaryLabel
        )
    )
}

@Composable
private fun MemoryActivityPhaseLine(phase: MemoryActivityPhaseSummary) {
    val materialColors = settingsMaterialColors()
    val metadata = buildList {
        add(memoryActivityPhaseLabel(phase.phase))
        add(memoryActivityStatusLabel(phase.status))
        phase.completedAt?.let { completedAt ->
            add(memoryActivityDurationLabel(phase.startedAt, completedAt))
        }
        phase.inputCount?.let { inputCount -> add(stringResource(R.string.memory_log_input_count, inputCount)) }
        phase.operationCount?.let { count -> add(stringResource(R.string.memory_log_operation_count, count)) }
        phase.errorCode?.let { errorCode -> add(stringResource(R.string.memory_log_error_code, errorCode)) }
    }.joinToString(" · ")
    Text(
        text = metadata,
        style = MaterialTheme.typography.bodySmall,
        color = materialColors.secondaryLabel,
        modifier = Modifier
            .testTag(MEMORY_ACTIVITY_PHASE_TEST_TAG)
            .padding(top = 2.dp)
    )
}

@Composable
private fun memoryActivityStatusLabel(status: String): String = when (status) {
    MemoryActivityStatus.SCHEDULED -> stringResource(R.string.memory_log_status_scheduled)
    MemoryActivityStatus.RUNNING -> stringResource(R.string.memory_log_status_running)
    MemoryActivityStatus.SUCCEEDED -> stringResource(R.string.memory_log_status_succeeded)
    MemoryActivityStatus.NO_OP -> stringResource(R.string.memory_log_status_no_op)
    MemoryActivityStatus.SKIPPED -> stringResource(R.string.memory_log_status_skipped)
    MemoryActivityStatus.BLOCKED -> stringResource(R.string.memory_log_status_blocked)
    MemoryActivityStatus.FAILED -> stringResource(R.string.memory_log_status_failed)
    else -> status
}

@Composable
private fun memoryActivityPhaseLabel(phase: String): String = when (phase) {
    MemoryActivityPhase.SCHEDULED -> stringResource(R.string.memory_log_phase_scheduled)
    MemoryActivityPhase.PLANNING -> stringResource(R.string.memory_log_phase_planning)
    MemoryActivityPhase.MODEL_RESOLUTION -> stringResource(R.string.memory_log_phase_model_resolution)
    MemoryActivityPhase.MODEL_CALL -> stringResource(R.string.memory_log_phase_model_call)
    MemoryActivityPhase.GENERATION -> stringResource(R.string.memory_log_phase_generation)
    MemoryActivityPhase.ORGANIZATION -> stringResource(R.string.memory_log_phase_organization)
    else -> phase
}

@Composable
private fun memoryActivityDurationLabel(startedAt: Long, completedAt: Long): String {
    val durationSeconds = (completedAt - startedAt).coerceAtLeast(0)
    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60
    return if (minutes == 0L) {
        stringResource(R.string.memory_log_duration_seconds, seconds)
    } else {
        stringResource(R.string.memory_log_duration_minutes_seconds, minutes, seconds)
    }
}

private fun formatLogTime(epochSeconds: Long): String = LOG_TIME_FORMATTER.format(Instant.ofEpochSecond(epochSeconds))

@Composable
private fun MemoryDisabledNotice() {
    val materialColors = settingsMaterialColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(8.dp),
            color = materialColors.controlFill,
            contentColor = materialColors.primaryLabel
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.memory_disabled_notice_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = materialColors.primaryLabel
            )
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = stringResource(R.string.memory_disabled_notice_description),
                style = MaterialTheme.typography.bodySmall,
                color = materialColors.secondaryLabel
            )
        }
    }
}

private const val MEMORY_TAB = 0
private const val LOG_TAB = 1
internal const val MEMORY_ACTIVITY_ROW_TEST_TAG = "memory_activity_row"
internal const val MEMORY_ACTIVITY_PHASE_TEST_TAG = "memory_activity_phase"
private val LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
