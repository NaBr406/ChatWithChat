package cn.nabr.chatwithchat.presentation.ui.chat

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.tool.AskUserQuestion
import cn.nabr.chatwithchat.data.tool.AskUserQuestionAnswer
import cn.nabr.chatwithchat.data.tool.AskUserQuestionBroker
import cn.nabr.chatwithchat.presentation.common.isSettingsDarkTheme
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.settingsTextFieldColors

private const val ASK_USER_QUESTION_BUBBLE_CORNER_RADIUS_DP = 24
private const val ASK_USER_QUESTION_BUBBLE_BLUR_RADIUS_DP = 28
private const val ASK_USER_QUESTION_BUBBLE_HORIZONTAL_MARGIN_DP = 12
private const val ASK_USER_QUESTION_BUBBLE_BOTTOM_MARGIN_DP = 10
private const val ASK_USER_QUESTION_BUBBLE_MAX_HEIGHT_FRACTION = 0.46f

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
    val materialColors = settingsMaterialColors()
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maxBubbleHeight = (windowHeight * ASK_USER_QUESTION_BUBBLE_MAX_HEIGHT_FRACTION).coerceAtLeast(220.dp)
    val shape = RoundedCornerShape(ASK_USER_QUESTION_BUBBLE_CORNER_RADIUS_DP.dp)

    fun updateDraft(index: Int, transform: (AskUserQuestionDraft) -> AskUserQuestionDraft) {
        drafts = drafts.toMutableList().also { values ->
            values[index] = transform(values[index])
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        FrostedGlassBubbleWindow(
            cornerRadiusDp = ASK_USER_QUESTION_BUBBLE_CORNER_RADIUS_DP,
            blurRadiusDp = ASK_USER_QUESTION_BUBBLE_BLUR_RADIUS_DP,
            horizontalMarginDp = ASK_USER_QUESTION_BUBBLE_HORIZONTAL_MARGIN_DP,
            bottomMarginDp = ASK_USER_QUESTION_BUBBLE_BOTTOM_MARGIN_DP
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxBubbleHeight),
            shape = shape,
            color = materialColors.grouped.copy(
                alpha = if (isSettingsDarkTheme()) 0.76f else 0.84f
            ),
            contentColor = materialColors.primaryLabel,
            border = BorderStroke(0.5.dp, materialColors.separatorStrong),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ask_user_question_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = materialColors.separator
                )

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pending.questions.forEachIndexed { index, question ->
                        if (index > 0) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = materialColors.separator
                            )
                        }
                        AskUserQuestionBlock(
                            question = question,
                            questionNumber = index + 1,
                            questionCount = pending.questions.size,
                            draft = drafts[index],
                            onDraftChanged = { updated -> updateDraft(index) { updated } }
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = materialColors.separator
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BubbleTextAction(
                        label = stringResource(R.string.ask_user_question_cancel),
                        onClick = onCancel
                    )
                    BubbleTextAction(
                        label = stringResource(R.string.ask_user_question_submit),
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
                    )
                }
            }
        }
    }
}

@Composable
private fun BubbleTextAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val materialColors = settingsMaterialColors()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .defaultMinSize(minWidth = 56.dp, minHeight = 32.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) materialColors.primaryLabel else materialColors.tertiaryLabel
        )
    }
}

@Composable
private fun FrostedGlassBubbleWindow(
    cornerRadiusDp: Int,
    blurRadiusDp: Int,
    horizontalMarginDp: Int,
    bottomMarginDp: Int
) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val horizontalMarginPx = with(density) { horizontalMarginDp.dp.roundToPx() }
    val bottomMarginPx = with(density) { bottomMarginDp.dp.roundToPx() }
    val popupWidthPx = screenWidthPx - horizontalMarginPx * 2
    val cornerRadiusPx = with(density) { cornerRadiusDp.dp.toPx() }
    val blurRadiusPx = with(density) { blurRadiusDp.dp.roundToPx() }
    val elevationPx = with(density) { 10.dp.toPx() }
    val background = remember(cornerRadiusPx) {
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(Color.Transparent.toArgb())
        }
    }

    DisposableEffect(window, popupWidthPx, background, blurRadiusPx) {
        val navigationBarInset = view.rootWindowInsets
            ?.getInsets(WindowInsets.Type.navigationBars())
            ?.bottom
            ?: 0
        window.setWindowAnimations(0)
        window.setBackgroundDrawable(background)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setDimAmount(0f)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.setLayout(popupWidthPx, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.clipToOutline = true
        window.decorView.elevation = elevationPx
        window.attributes = window.attributes.apply {
            width = popupWidthPx
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = 0
            y = navigationBarInset + bottomMarginPx
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(blurRadiusPx)
        }

        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.setBackgroundBlurRadius(0)
            }
        }
    }
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = question.header,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.ask_user_question_progress,
                    questionNumber,
                    questionCount
                ),
                style = MaterialTheme.typography.labelSmall,
                color = materialColors.secondaryLabel
            )
        }
        Text(
            text = question.question,
            style = MaterialTheme.typography.bodyMedium
        )
        if (question.multiSelect) {
            Text(
                text = stringResource(R.string.ask_user_question_multi_select_hint),
                style = MaterialTheme.typography.labelSmall,
                color = materialColors.secondaryLabel
            )
        }

        Column {
            question.options.forEachIndexed { index, option ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = materialColors.separator
                    )
                }
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

            HorizontalDivider(
                thickness = 0.5.dp,
                color = materialColors.separator
            )
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
        }

        if (draft.otherSelected) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.otherText,
                onValueChange = { value -> onDraftChanged(draft.copy(otherText = value.take(2_000))) },
                label = { Text(stringResource(R.string.ask_user_question_other_hint)) },
                textStyle = MaterialTheme.typography.bodySmall,
                colors = settingsTextFieldColors(),
                minLines = 1,
                maxLines = 3,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (multiSelect) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            RadioButton(selected = selected, onClick = onClick)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = materialColors.primaryLabel
            )
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
