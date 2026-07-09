package dev.chungjungsoo.gptmobile.presentation.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceJobStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    memoryViewModel: MemoryViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val uiState by memoryViewModel.uiState.collectAsStateWithLifecycle()
    val emptyMarkdownText = stringResource(R.string.memory_export_empty_markdown)

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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!uiState.memoryEnabled) {
                item { MemoryDisabledNotice() }
            }

            if (uiState.maintenanceJobs.isNotEmpty()) {
                item {
                    Text(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        text = stringResource(R.string.memory_maintenance_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                uiState.maintenanceJobs.forEach { job ->
                    item(key = job.jobId) {
                        MemoryMaintenanceJobItem(
                            job = job,
                            onRetry = { memoryViewModel.retryMaintenanceJob(job.jobId) },
                            onDismiss = { memoryViewModel.dismissMaintenanceJob(job.jobId) }
                        )
                    }
                }
            }

            item {
                SelectionContainer {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        text = uiState.markdown.ifBlank { emptyMarkdownText },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.markdown.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
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
private fun MemoryDisabledNotice() {
    ListItem(
        headlineContent = { Text(stringResource(R.string.memory_disabled_notice_title)) },
        supportingContent = { Text(stringResource(R.string.memory_disabled_notice_description)) },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun MemoryMaintenanceJobItem(
    job: MemoryMaintenanceJob,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(stringResource(R.string.memory_maintenance_job, job.type, job.status))
        },
        supportingContent = {
            job.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(stringResource(R.string.memory_maintenance_error, error))
            }
        },
        trailingContent = {
            if (job.status in RETRYABLE_JOB_STATUSES) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.retry))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
            }
        },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

private val RETRYABLE_JOB_STATUSES = setOf(
    MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
    MemoryMaintenanceJobStatus.FAILED_TERMINAL
)
