package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.tool.AskUserQuestion
import cn.nabr.chatwithchat.data.tool.AskUserQuestionAnswer
import cn.nabr.chatwithchat.data.tool.AskUserQuestionBroker
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors

@Composable
fun AskUserQuestionDialog(
    pending: AskUserQuestionBroker.PendingQuestion,
    onSubmit: (List<AskUserQuestionAnswer>) -> Unit,
    onCancel: () -> Unit
) {
    var drafts by remember(pending.requestId) {
        mutableStateOf(List(pending.questions.size) { AskUserQuestionDraft() })
    }
    val allAnswered = pending.questions.indices.all { index ->
        drafts.getOrNull(index)?.isAnswered ?: false
    }

    fun updateDraft(index: Int, transform: (AskUserQuestionDraft) -> AskUserQuestionDraft) {
        drafts = drafts.toMutableList().also { values ->
            values[index] = transform(values[index])
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = stringResource(R.string.ask_user_question_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                pending.questions.forEachIndexed { index, question ->
                    AskUserQuestionBlock(
                        question = question,
                        questionNumber = index + 1,
                        questionCount = pending.questions.size,
                        draft = drafts[index],
                        onDraftChanged = { updated -> updateDraft(index) { updated } }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = allAnswered,
                onClick = {
                    onSubmit(
                        pending.questions.mapIndexed { index, question ->
                            val draft = drafts[index]
                            AskUserQuestionAnswer(
                                header = question.header,
                                selectedOptions = draft.selectedOptions.toList(),
                                otherText = draft.otherText.trim().takeIf(String::isNotEmpty)
                                    .takeIf { draft.otherSelected }
                            )
                        }
                    )
                }
            ) {
                Text(stringResource(R.string.ask_user_question_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.ask_user_question_cancel))
            }
        }
    )
}

@Composable
private fun AskUserQuestionBlock(
    question: AskUserQuestion,
    questionNumber: Int,
    questionCount: Int,
    draft: AskUserQuestionDraft,
    onDraftChanged: (AskUserQuestionDraft) -> Unit
) {
    val materialColors = settingsMaterialColors()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = question.header,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.ask_user_question_progress,
                    questionNumber,
                    questionCount
                ),
                style = MaterialTheme.typography.labelMedium,
                color = materialColors.secondaryLabel
            )
        }
        Text(
            text = question.question,
            style = MaterialTheme.typography.bodyLarge
        )
        if (question.multiSelect) {
            Text(
                text = stringResource(R.string.ask_user_question_multi_select_hint),
                style = MaterialTheme.typography.bodySmall,
                color = materialColors.secondaryLabel
            )
        }

        question.options.forEach { option ->
            AskUserQuestionOptionRow(
                label = option.label,
                description = option.description,
                selected = option.label in draft.selectedOptions,
                multiSelect = question.multiSelect,
                onClick = {
                    val selected = if (question.multiSelect) {
                        if (option.label in draft.selectedOptions) {
                            draft.selectedOptions - option.label
                        } else {
                            draft.selectedOptions + option.label
                        }
                    } else {
                        setOf(option.label)
                    }
                    onDraftChanged(
                        draft.copy(
                            selectedOptions = selected,
                            otherSelected = if (question.multiSelect) draft.otherSelected else false,
                            otherText = if (question.multiSelect || selected.isNotEmpty()) draft.otherText else ""
                        )
                    )
                }
            )
        }

        AskUserQuestionOptionRow(
            label = stringResource(R.string.ask_user_question_other),
            description = stringResource(R.string.ask_user_question_other_description),
            selected = draft.otherSelected,
            multiSelect = question.multiSelect,
            onClick = {
                val selected = !draft.otherSelected
                onDraftChanged(
                    if (question.multiSelect) {
                        draft.copy(otherSelected = selected)
                    } else {
                        draft.copy(
                            selectedOptions = emptySet(),
                            otherSelected = selected,
                            otherText = if (selected) draft.otherText else ""
                        )
                    }
                )
            }
        )
        if (draft.otherSelected) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.otherText,
                onValueChange = { value -> onDraftChanged(draft.copy(otherText = value.take(2_000))) },
                label = { Text(stringResource(R.string.ask_user_question_other_hint)) },
                minLines = 2,
                maxLines = 5,
                singleLine = false
            )
        }
    }
}

@Composable
private fun AskUserQuestionOptionRow(
    label: String,
    description: String,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) materialColors.primaryLabel else materialColors.separator, shape)
            .background(MaterialTheme.colorScheme.surface, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (multiSelect) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            RadioButton(selected = selected, onClick = onClick)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = materialColors.secondaryLabel
            )
        }
    }
}

private data class AskUserQuestionDraft(
    val selectedOptions: Set<String> = emptySet(),
    val otherSelected: Boolean = false,
    val otherText: String = ""
) {
    val isAnswered: Boolean
        get() = selectedOptions.isNotEmpty() || (otherSelected && otherText.isNotBlank())
}
