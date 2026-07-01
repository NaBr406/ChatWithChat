package dev.chungjungsoo.gptmobile.presentation.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import dev.chungjungsoo.gptmobile.data.memory.MemorySensitivity
import dev.chungjungsoo.gptmobile.data.memory.MemorySource
import dev.chungjungsoo.gptmobile.data.memory.MemoryStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    memoryViewModel: MemoryViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val uiState by memoryViewModel.uiState.collectAsStateWithLifecycle()
    val pendingTitle = stringResource(R.string.memory_section_pending)
    val activeTitle = stringResource(R.string.memory_section_active)
    val inactiveTitle = stringResource(R.string.memory_section_inactive)
    val noMemoriesText = stringResource(R.string.memory_empty)
    val emptyExportMarkdown = stringResource(R.string.memory_export_empty_markdown)

    LaunchedEffect(Unit) {
        memoryViewModel.loadMemories()
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Text(
                        text = stringResource(R.string.memory),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                actions = {
                    IconButton(onClick = memoryViewModel::exportMarkdown) {
                        Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.memory_export))
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding)
        ) {
            val pending = uiState.memories.filter { it.status == MemoryStatus.PENDING_CONFIRMATION }
            val active = uiState.memories.filter { it.status == MemoryStatus.ACTIVE }
            val inactive = uiState.memories.filter { it.status != MemoryStatus.PENDING_CONFIRMATION && it.status != MemoryStatus.ACTIVE }

            if (!uiState.memoryEnabled) {
                item { MemoryDisabledNotice() }
            }

            memorySection(pendingTitle, pending, noMemoriesText, memoryViewModel)
            memorySection(activeTitle, active, noMemoriesText, memoryViewModel)
            memorySection(inactiveTitle, inactive, noMemoriesText, memoryViewModel)
        }
    }

    uiState.editingMemory?.let { memory ->
        EditMemoryDialog(
            memory = memory,
            onDismiss = memoryViewModel::closeEdit,
            onSave = memoryViewModel::saveEdit
        )
    }

    uiState.exportMarkdown?.let { markdown ->
        AlertDialog(
            title = { Text(stringResource(R.string.memory_export_title)) },
            text = {
                Text(
                    text = markdown.ifBlank { emptyExportMarkdown },
                    style = MaterialTheme.typography.bodyMedium
                )
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
private fun MemoryDisabledNotice() {
    ListItem(
        headlineContent = { Text(stringResource(R.string.memory_disabled_notice_title)) },
        supportingContent = { Text(stringResource(R.string.memory_disabled_notice_description)) },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.memorySection(
    title: String,
    memories: List<PersonalMemory>,
    emptyText: String,
    memoryViewModel: MemoryViewModel
) {
    item {
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
    }
    if (memories.isEmpty()) {
        item {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        items(memories, key = { it.id }) { memory ->
            MemoryItem(
                memory = memory,
                onEdit = { memoryViewModel.openEdit(memory) },
                onConfirm = { memoryViewModel.confirm(memory) },
                onReject = { memoryViewModel.reject(memory) },
                onResolved = { memoryViewModel.markResolved(memory) },
                onArchive = { memoryViewModel.archive(memory) },
                onDelete = { memoryViewModel.delete(memory) }
            )
        }
    }
}

@Composable
private fun MemoryItem(
    memory: PersonalMemory,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onResolved: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Column {
            ListItem(
                headlineContent = { Text(memory.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(memory.recallText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(onClick = {}, label = { Text(stringResource(memory.type.memoryTypeStringRes())) })
                            AssistChip(onClick = {}, label = { Text(stringResource(memory.source.memorySourceStringRes())) })
                            AssistChip(onClick = {}, label = { Text(stringResource(memory.sensitivity.memorySensitivityStringRes())) })
                            AssistChip(onClick = {}, label = { Text(stringResource(memory.status.memoryStatusStringRes())) })
                        }
                    }
                }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (memory.status == MemoryStatus.PENDING_CONFIRMATION) {
                    IconButton(onClick = onConfirm) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.confirm))
                    }
                    IconButton(onClick = onReject) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.memory_reject))
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                }
                IconButton(onClick = onResolved) {
                    Icon(Icons.Filled.DoneAll, contentDescription = stringResource(R.string.memory_mark_resolved))
                }
                IconButton(onClick = onArchive) {
                    Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.memory_archive))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
    }
}

@Composable
private fun EditMemoryDialog(
    memory: PersonalMemory,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var summary by remember(memory.id) { mutableStateOf(memory.summary) }
    var recallText by remember(memory.id) { mutableStateOf(memory.recallText) }

    AlertDialog(
        title = { Text(stringResource(R.string.memory_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text(stringResource(R.string.memory_summary_label)) }
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = recallText,
                    onValueChange = { recallText = it },
                    label = { Text(stringResource(R.string.memory_recall_text_label)) },
                    minLines = 3
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = summary.isNotBlank() && recallText.isNotBlank(),
                onClick = { onSave(summary, recallText) }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun String.memoryTypeStringRes(): Int = when (this) {
    "stable_profile" -> R.string.memory_type_stable_profile
    "communication_style" -> R.string.memory_type_communication_style
    "interest" -> R.string.memory_type_interest
    "important_event" -> R.string.memory_type_important_event
    "important_person" -> R.string.memory_type_important_person
    "emotional_pattern" -> R.string.memory_type_emotional_pattern
    "boundary" -> R.string.memory_type_boundary
    "life_context" -> R.string.memory_type_life_context
    "recurring_theme" -> R.string.memory_type_recurring_theme
    "light_productivity_preference" -> R.string.memory_type_light_productivity_preference
    else -> R.string.memory_type_other
}

private fun String.memorySourceStringRes(): Int = when (this) {
    MemorySource.EXPLICIT_USER_STATEMENT -> R.string.memory_source_explicit_user_statement
    MemorySource.ASSISTANT_INFERRED -> R.string.memory_source_assistant_inferred
    MemorySource.USER_CONFIRMED -> R.string.memory_source_user_confirmed
    else -> R.string.memory_source_other
}

private fun String.memorySensitivityStringRes(): Int = when (this) {
    MemorySensitivity.NORMAL -> R.string.memory_sensitivity_normal
    MemorySensitivity.PRIVATE -> R.string.memory_sensitivity_private
    MemorySensitivity.SENSITIVE -> R.string.memory_sensitivity_sensitive
    else -> R.string.memory_sensitivity_uncategorized
}

private fun String.memoryStatusStringRes(): Int = when (this) {
    MemoryStatus.ACTIVE -> R.string.memory_status_active
    MemoryStatus.PENDING_CONFIRMATION -> R.string.memory_status_pending
    MemoryStatus.RESOLVED -> R.string.memory_status_resolved
    MemoryStatus.ARCHIVED -> R.string.memory_status_archived
    MemoryStatus.SUPERSEDED -> R.string.memory_status_superseded
    else -> R.string.memory_status_unknown
}
