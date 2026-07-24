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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ContentCopy
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
import cn.nabr.chatwithchat.data.debug.PromptTraceEntry
import cn.nabr.chatwithchat.data.debug.MemoryRecallTrace
import cn.nabr.chatwithchat.data.debug.PromptTraceStage
import cn.nabr.chatwithchat.data.memory.MemoryRetrievalMode
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun PromptTraceScreen(
    onNavigationClick: () -> Unit,
    promptTraceViewModel: PromptTraceViewModel = hiltViewModel()
) {
    val entries by promptTraceViewModel.entries.collectAsStateWithLifecycle()
    var selectedEntry by remember { mutableStateOf<PromptTraceEntry?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    val colors = settingsMaterialColors()

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.prompt_trace_title),
                onNavigationClick = onNavigationClick,
                actions = {
                    IconButton(
                        enabled = entries.isNotEmpty(),
                        onClick = { showClearConfirmation = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.prompt_trace_clear)
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
            if (entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.prompt_trace_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondaryLabel,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
            items(entries, key = PromptTraceEntry::traceId) { entry ->
                PromptTraceListItem(entry = entry, onClick = { selectedEntry = entry })
            }
        }
    }

    selectedEntry?.let { entry ->
        PromptTraceDetailDialog(entry = entry, onDismissRequest = { selectedEntry = null })
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.prompt_trace_clear)) },
            text = { Text(stringResource(R.string.prompt_trace_clear_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        promptTraceViewModel.clear()
                        selectedEntry = null
                        showClearConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun PromptTraceListItem(entry: PromptTraceEntry, onClick: () -> Unit) {
    val colors = settingsMaterialColors()
    val promptText = entry.systemPrompt.ifBlank { stringResource(R.string.prompt_trace_prompt_empty) }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = entry.platformName.ifBlank { entry.clientType.name },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primaryLabel,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = PROMPT_TRACE_TIME_FORMATTER.format(Instant.ofEpochMilli(entry.createdAtMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiaryLabel
                )
            }
            Text(
                text = stringResource(
                    R.string.prompt_trace_item_metadata,
                    entry.model,
                    promptTraceStageLabel(entry.stage)
                ),
                style = MaterialTheme.typography.labelMedium,
                color = colors.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.prompt_trace_chat_turn, entry.chatId, entry.turnNumber),
                style = MaterialTheme.typography.labelSmall,
                color = colors.tertiaryLabel
            )
            Text(
                text = memoryRecallSummary(entry.memoryRecall),
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryLabel
            )
            Text(
                text = promptText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.primaryLabel,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PromptTraceDetailDialog(entry: PromptTraceEntry, onDismissRequest: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val promptText = entry.systemPrompt.ifBlank { stringResource(R.string.prompt_trace_prompt_empty) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Column {
                Text(stringResource(R.string.prompt_trace_detail_title))
                Text(
                    text = stringResource(
                        R.string.prompt_trace_item_metadata,
                        entry.model,
                        promptTraceStageLabel(entry.stage)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MemoryRecallDetail(recall = entry.memoryRecall)
                        Text(
                            text = promptText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("system_prompt", entry.systemPrompt))
                        )
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.prompt_trace_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun memoryRecallSummary(recall: MemoryRecallTrace?): String {
    if (recall == null) return stringResource(R.string.prompt_trace_recall_unavailable)
    val label = memoryRecallModeLabel(recall.mode, recall.hitCount, recall.errorMessage)
    return stringResource(R.string.prompt_trace_recall_summary, label, recall.hitCount)
}

@Composable
private fun MemoryRecallDetail(recall: MemoryRecallTrace?) {
    val colors = settingsMaterialColors()
    if (recall == null) {
        Text(
            text = stringResource(R.string.prompt_trace_recall_unavailable),
            style = MaterialTheme.typography.labelMedium,
            color = colors.secondaryLabel
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = memoryRecallModeLabel(recall.mode, recall.hitCount, recall.errorMessage),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.primaryLabel
        )
        if (recall.memoryIds.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.prompt_trace_recall_ids,
                    recall.memoryIds.joinToString(", ")
                ),
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryLabel
            )
        }
        recall.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = stringResource(R.string.prompt_trace_recall_error, message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun memoryRecallModeLabel(
    mode: MemoryRetrievalMode,
    hitCount: Int,
    errorMessage: String?
): String = when {
    errorMessage != null || mode == MemoryRetrievalMode.FAILED ->
        stringResource(R.string.prompt_trace_recall_failed)
    hitCount <= 0 || mode == MemoryRetrievalMode.NONE ->
        stringResource(R.string.prompt_trace_recall_none)
    mode == MemoryRetrievalMode.LEXICAL -> stringResource(R.string.prompt_trace_recall_lexical)
    mode == MemoryRetrievalMode.LEXICAL_FALLBACK ->
        stringResource(R.string.prompt_trace_recall_lexical_fallback)
    mode == MemoryRetrievalMode.SEMANTIC -> stringResource(R.string.prompt_trace_recall_semantic)
    mode == MemoryRetrievalMode.HYBRID -> stringResource(R.string.prompt_trace_recall_hybrid)
    else -> stringResource(R.string.prompt_trace_recall_none)
}

@Composable
private fun promptTraceStageLabel(stage: String): String = when {
    stage == PromptTraceStage.ANSWER -> stringResource(R.string.prompt_trace_stage_answer)
    stage == PromptTraceStage.ANSWER_WITH_EXTRA_INSTRUCTIONS ->
        stringResource(R.string.prompt_trace_stage_answer_with_extra)
    stage == PromptTraceStage.TOOL_FINAL_ANSWER -> stringResource(R.string.prompt_trace_stage_tool_final)
    stage.startsWith(TOOL_REQUEST_STAGE_PREFIX) -> {
        val roundNumber = stage.removePrefix(TOOL_REQUEST_STAGE_PREFIX).toIntOrNull() ?: 0
        stringResource(R.string.prompt_trace_stage_tool_request, roundNumber)
    }
    else -> stage
}

private const val TOOL_REQUEST_STAGE_PREFIX = "tool_request_"
private val PROMPT_TRACE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    .withZone(ZoneId.systemDefault())
