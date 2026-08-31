package cn.nabr.chatwithchat.presentation.ui.debug

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceFailureResponse
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.settingsTextButtonColors
import cn.nabr.chatwithchat.util.formatEpochSecondsAsDate
import kotlinx.coroutines.launch

@Composable
fun MemoryMaintenanceFailureResponseScreen(
    onNavigationClick: () -> Unit,
    viewModel: MemoryMaintenanceFailureResponseViewModel = hiltViewModel()
) {
    val responses by viewModel.responses.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    var selectedResponse by remember { mutableStateOf<MemoryMaintenanceFailureResponse?>(null) }
    val colors = settingsMaterialColors()

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.memory_failure_response_title),
                onNavigationClick = onNavigationClick,
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.memory_failure_response_refresh)
                        )
                    }
                }
            )
        },
        containerColor = colors.canvas
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (jobs.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.memory_failure_job_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primaryLabel,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                items(jobs, key = MemoryMaintenanceJob::jobId) { job ->
                    MemoryMaintenanceJobListItem(job)
                }
            }
            if (responses.isNotEmpty() && jobs.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.memory_failure_response_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primaryLabel,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
            }
            if (responses.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.memory_failure_response_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondaryLabel,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
            items(responses, key = MemoryMaintenanceFailureResponse::fileName) { response ->
                MemoryMaintenanceFailureResponseListItem(
                    response = response,
                    onClick = { selectedResponse = response }
                )
            }
        }
    }

    selectedResponse?.let { response ->
        MemoryMaintenanceFailureResponseDetailDialog(
            response = response,
            onDismissRequest = { selectedResponse = null }
        )
    }
}

@Composable
private fun MemoryMaintenanceJobListItem(job: MemoryMaintenanceJob) {
    val colors = settingsMaterialColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.grouped,
        border = BorderStroke(0.5.dp, colors.separator)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.memory_failure_job_item, job.type, job.status),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.primaryLabel
            )
            Text(
                text = stringResource(R.string.memory_failure_job_id, job.jobId),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = colors.secondaryLabel
            )
            Text(
                text = stringResource(R.string.memory_failure_job_attempts, job.attempts),
                style = MaterialTheme.typography.labelMedium,
                color = colors.secondaryLabel
            )
            Text(
                text = stringResource(
                    R.string.memory_failure_job_error,
                    job.lastError ?: job.blockedReason ?: "-"
                ),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.primaryLabel
            )
            Text(
                text = stringResource(
                    R.string.memory_failure_job_updated,
                    formatEpochSecondsAsDate(job.updatedAt)
                ),
                style = MaterialTheme.typography.labelMedium,
                color = colors.secondaryLabel
            )
        }
    }
}

@Composable
private fun MemoryMaintenanceFailureResponseListItem(
    response: MemoryMaintenanceFailureResponse,
    onClick: () -> Unit
) {
    val colors = settingsMaterialColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = colors.grouped,
        border = BorderStroke(0.5.dp, colors.separator)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.memory_failure_response_detail_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.primaryLabel
            )
            Text(
                text = stringResource(
                    R.string.memory_failure_response_item,
                    formatEpochSecondsAsDate(response.capturedAtEpochSeconds)
                ),
                style = MaterialTheme.typography.labelMedium,
                color = colors.secondaryLabel
            )
            Text(
                text = response.rawResponseJson,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.primaryLabel,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MemoryMaintenanceFailureResponseDetailDialog(
    response: MemoryMaintenanceFailureResponse,
    onDismissRequest: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        containerColor = settingsMaterialColors().grouped,
        titleContentColor = settingsMaterialColors().primaryLabel,
        textContentColor = settingsMaterialColors().secondaryLabel,
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.memory_failure_response_detail_title)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = response.rawResponseJson,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("memory_failure_response", response.rawResponseJson))
                        )
                    }
                },
                colors = settingsTextButtonColors()
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.memory_failure_response_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, colors = settingsTextButtonColors()) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
