package dev.chungjungsoo.gptmobile.presentation.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryActivityLog
import dev.chungjungsoo.gptmobile.data.memory.MemoryActivityCategory
import dev.chungjungsoo.gptmobile.data.memory.MemoryActivityStatus
import dev.chungjungsoo.gptmobile.presentation.common.AppleBlue
import dev.chungjungsoo.gptmobile.presentation.common.SettingsMaterialGroup
import dev.chungjungsoo.gptmobile.presentation.common.SettingsTopAppBar
import dev.chungjungsoo.gptmobile.presentation.common.settingsMaterialColors
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
    val materialColors = settingsMaterialColors()

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.memory),
                onNavigationClick = onNavigationClick,
                actions = {
                    if (selectedTab == MEMORY_TAB) {
                        IconButton(onClick = memoryViewModel::exportMarkdown) {
                            Icon(
                                Icons.Filled.FileDownload,
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
}

@Composable
private fun MemoryContent(memoryEnabled: Boolean, markdown: String, emptyMarkdownText: String) {
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
