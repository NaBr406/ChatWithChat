package dev.chungjungsoo.gptmobile.presentation.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryStatus
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    modifier: Modifier = Modifier,
    memoryViewModel: MemoryViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val uiState by memoryViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                memoryViewModel.refreshMemories()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            LargeTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Text(
                        modifier = Modifier.padding(4.dp),
                        text = "Memory",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = onNavigationClick
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.memories.isEmpty() && !uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No memories yet",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = "Explicit preferences and important personal context will appear here after chats are saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                items(uiState.memories, key = { it.id }) { memory ->
                    MemoryItem(
                        memory = memory,
                        onEdit = { memoryViewModel.startEditing(memory) },
                        onResolve = { memoryViewModel.markResolved(memory.id) },
                        onArchive = { memoryViewModel.archive(memory.id) },
                        onDelete = { memoryViewModel.delete(memory.id) }
                    )
                }
            }
        }
    }

    uiState.editingMemory?.let {
        EditMemoryDialog(
            content = uiState.editedContent,
            onContentChange = memoryViewModel::updateEditedContent,
            onDismiss = memoryViewModel::dismissEditor,
            onSave = memoryViewModel::saveEditedMemory
        )
    }
}

@Composable
private fun MemoryItem(
    memory: PersonalMemory,
    onEdit: () -> Unit,
    onResolve: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = {},
                label = { Text(memory.type) }
            )
            AssistChip(
                onClick = {},
                label = { Text(memory.status) }
            )
        }
        Text(
            modifier = Modifier.padding(top = 8.dp),
            text = memory.content,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = "importance ${memory.importance} · confidence ${memory.confidence} · ${memory.source}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(
                enabled = memory.status == MemoryStatus.ACTIVE,
                onClick = onResolve
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Resolved")
            }
            IconButton(
                enabled = memory.status != MemoryStatus.ARCHIVED,
                onClick = onArchive
            ) {
                Icon(Icons.Filled.Archive, contentDescription = "Archive")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun EditMemoryDialog(
    content: String,
    onContentChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        title = { Text("Edit memory") },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = content,
                onValueChange = onContentChange,
                minLines = 3
            )
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onSave) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
