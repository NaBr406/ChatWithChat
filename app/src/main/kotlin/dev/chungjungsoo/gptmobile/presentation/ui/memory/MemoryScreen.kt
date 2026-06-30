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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                        text = "记忆",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = memoryViewModel::exportMarkdown) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "导出")
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

            memorySection("待确认", pending, memoryViewModel)
            memorySection("生效中", active, memoryViewModel)
            memorySection("已解决与已归档", inactive, memoryViewModel)
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
            title = { Text("记忆导出") },
            text = {
                Text(
                    text = markdown.ifBlank { "# 用户记忆" },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onDismissRequest = memoryViewModel::closeExport,
            confirmButton = {
                TextButton(onClick = memoryViewModel::closeExport) {
                    Text("关闭")
                }
            }
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.memorySection(
    title: String,
    memories: List<PersonalMemory>,
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
                text = "暂无记忆",
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
                            AssistChip(onClick = {}, label = { Text(memory.type.displayMemoryType()) })
                            AssistChip(onClick = {}, label = { Text(memory.source.displayMemorySource()) })
                            AssistChip(onClick = {}, label = { Text(memory.sensitivity.displayMemorySensitivity()) })
                            AssistChip(onClick = {}, label = { Text(memory.status.displayMemoryStatus()) })
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
                        Icon(Icons.Filled.Check, contentDescription = "确认")
                    }
                    IconButton(onClick = onReject) {
                        Icon(Icons.Filled.Close, contentDescription = "拒绝")
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onResolved) {
                    Icon(Icons.Filled.DoneAll, contentDescription = "标记为已解决")
                }
                IconButton(onClick = onArchive) {
                    Icon(Icons.Filled.Archive, contentDescription = "归档")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
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
        title = { Text("编辑记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("摘要") }
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = recallText,
                    onValueChange = { recallText = it },
                    label = { Text("召回文本") },
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
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun String.displayMemoryType(): String = when (this) {
    "stable_profile" -> "个人资料"
    "communication_style" -> "沟通风格"
    "interest" -> "兴趣"
    "important_event" -> "重要事件"
    "important_person" -> "重要人物"
    "emotional_pattern" -> "情绪模式"
    "boundary" -> "边界"
    "life_context" -> "生活背景"
    "recurring_theme" -> "重复主题"
    "light_productivity_preference" -> "轻量生产力偏好"
    else -> "其他类型"
}

private fun String.displayMemorySource(): String = when (this) {
    MemorySource.EXPLICIT_USER_STATEMENT -> "用户明确提到"
    MemorySource.ASSISTANT_INFERRED -> "助手推断"
    MemorySource.USER_CONFIRMED -> "用户确认"
    else -> "其他来源"
}

private fun String.displayMemorySensitivity(): String = when (this) {
    MemorySensitivity.NORMAL -> "普通"
    MemorySensitivity.PRIVATE -> "私密"
    MemorySensitivity.SENSITIVE -> "敏感"
    else -> "未分类"
}

private fun String.displayMemoryStatus(): String = when (this) {
    MemoryStatus.ACTIVE -> "生效中"
    MemoryStatus.PENDING_CONFIRMATION -> "待确认"
    MemoryStatus.RESOLVED -> "已解决"
    MemoryStatus.ARCHIVED -> "已归档"
    MemoryStatus.SUPERSEDED -> "已替换"
    else -> "未知状态"
}
