package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.model.reasoningModesForModel

data class ModelSelectionOption(
    val platformUid: String,
    val label: String,
    val model: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val reasoningModes: List<ReasoningMode> = emptyList()
)

@Composable
fun ModelSelectionMenu(
    label: String,
    options: List<ModelSelectionOption>,
    selectedReasoningMode: ReasoningMode,
    enabled: Boolean,
    onOptionSelected: (ModelSelectionOption) -> Unit,
    onReasoningModeSelected: (ReasoningMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var reasoningExpanded by remember { mutableStateOf(false) }
    val canOpen = enabled && options.isNotEmpty()
    val selectedOption = options.firstOrNull { it.selected } ?: options.firstOrNull()
    val reasoningLabel = selectedOption
        ?.takeIf { it.reasoningModes.isNotEmpty() }
        ?.let { reasoningModeLabel(selectedReasoningMode) }
    val contentColor = if (canOpen) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val triggerArrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "modelMenuArrowRotation"
    )
    val reasoningArrowRotation by animateFloatAsState(
        targetValue = if (reasoningExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "reasoningSubmenuArrowRotation"
    )
    val menuShape = RoundedCornerShape(18.dp)

    fun closeMenu() {
        expanded = false
        reasoningExpanded = false
    }

    Box(modifier = modifier.wrapContentSize(Alignment.Center)) {
        Row(
            modifier = Modifier
                .widthIn(max = 210.dp)
                .heightIn(min = 40.dp)
                .clickable(enabled = canOpen) {
                    reasoningExpanded = false
                    expanded = true
                }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor
                )
                reasoningLabel?.let { modeLabel ->
                    Text(
                        text = stringResource(R.string.reasoning_mode_current, modeLabel),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(R.string.chat_models),
                tint = contentColor,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = triggerArrowRotation }
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = ::closeMenu,
            modifier = Modifier
                .widthIn(min = 224.dp, max = 260.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shape = menuShape
                ),
            shape = menuShape,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
            )
        ) {
            ModelSelectionModelPage(
                options = options,
                selectedOption = selectedOption,
                selectedReasoningMode = selectedReasoningMode,
                reasoningArrowRotation = reasoningArrowRotation,
                onReasoningClick = { reasoningExpanded = reasoningExpanded.not() },
                onOptionSelected = { option ->
                    closeMenu()
                    onOptionSelected(option)
                }
            )
        }

        DropdownMenu(
            expanded = expanded && reasoningExpanded,
            onDismissRequest = { reasoningExpanded = false },
            modifier = Modifier
                .widthIn(min = 220.dp, max = 248.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = menuShape
                ),
            offset = DpOffset(x = 176.dp, y = 214.dp),
            properties = PopupProperties(focusable = false),
            shape = menuShape,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
            )
        ) {
            ModelSelectionReasoningMenu(
                selectedOption = selectedOption,
                selectedReasoningMode = selectedReasoningMode,
                onReasoningModeSelected = { reasoningMode ->
                    closeMenu()
                    onReasoningModeSelected(reasoningMode)
                }
            )
        }
    }
}

@Composable
private fun ModelSelectionModelPage(
    options: List<ModelSelectionOption>,
    selectedOption: ModelSelectionOption?,
    selectedReasoningMode: ReasoningMode,
    reasoningArrowRotation: Float,
    onReasoningClick: () -> Unit,
    onOptionSelected: (ModelSelectionOption) -> Unit
) {
    options.forEach { option ->
        DropdownMenuItem(
            modifier = Modifier.heightIn(min = 54.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            leadingIcon = {
                if (option.selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = option.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall
                    )
                    option.subtitle?.let { subtitle ->
                        Text(
                            text = subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            onClick = { onOptionSelected(option) }
        )
    }

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.56f)
    )

    DropdownMenuItem(
        modifier = Modifier.heightIn(min = 58.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        text = {
            Column {
                Text(
                    text = stringResource(R.string.reasoning_mode),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = selectedOption?.takeIf { it.reasoningModes.isNotEmpty() }
                        ?.let { reasoningModeLabel(selectedReasoningMode) }
                        ?: stringResource(R.string.reasoning_mode_not_supported),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = reasoningArrowRotation }
            )
        },
        onClick = onReasoningClick
    )
}

@Composable
private fun ModelSelectionReasoningMenu(
    selectedOption: ModelSelectionOption?,
    selectedReasoningMode: ReasoningMode,
    onReasoningModeSelected: (ReasoningMode) -> Unit
) {
    val reasoningModes = selectedOption?.reasoningModes.orEmpty()
    if (reasoningModes.isEmpty()) {
        DropdownMenuItem(
            enabled = false,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            text = {
                Text(
                    text = stringResource(R.string.reasoning_mode_not_supported),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            onClick = {}
        )
        return
    }

    reasoningModes.forEach { reasoningMode ->
        DropdownMenuItem(
            modifier = Modifier.heightIn(min = 62.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            leadingIcon = {
                if (reasoningMode == selectedReasoningMode) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = reasoningModeLabel(reasoningMode),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = reasoningModeDescription(reasoningMode),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            onClick = { onReasoningModeSelected(reasoningMode) }
        )
    }
}

@Composable
private fun reasoningModeLabel(reasoningMode: ReasoningMode): String = when (reasoningMode) {
    ReasoningMode.AUTO -> stringResource(R.string.reasoning_mode_auto)
    ReasoningMode.OFF -> stringResource(R.string.reasoning_mode_off)
    ReasoningMode.LOW -> stringResource(R.string.reasoning_mode_low)
    ReasoningMode.MEDIUM -> stringResource(R.string.reasoning_mode_medium)
    ReasoningMode.HIGH -> stringResource(R.string.reasoning_mode_high)
    ReasoningMode.MAX -> stringResource(R.string.reasoning_mode_max)
}

@Composable
private fun reasoningModeDescription(reasoningMode: ReasoningMode): String = when (reasoningMode) {
    ReasoningMode.AUTO -> stringResource(R.string.reasoning_mode_auto_description)
    ReasoningMode.OFF -> stringResource(R.string.reasoning_mode_off_description)
    ReasoningMode.LOW -> stringResource(R.string.reasoning_mode_low_description)
    ReasoningMode.MEDIUM -> stringResource(R.string.reasoning_mode_medium_description)
    ReasoningMode.HIGH -> stringResource(R.string.reasoning_mode_high_description)
    ReasoningMode.MAX -> stringResource(R.string.reasoning_mode_max_description)
}

fun buildModelSelectionOptions(
    models: List<AvailableChatModel>,
    selectedPlatformUid: String?,
    selectedModel: String?
): List<ModelSelectionOption> {
    val duplicateModelIds = models
        .groupingBy { it.modelId }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys

    return models.map { chatModel ->
        val isSelected = chatModel.platformUid == selectedPlatformUid && chatModel.modelId == selectedModel
        val subtitle = chatModel.platformName.takeIf { chatModel.modelId in duplicateModelIds }

        ModelSelectionOption(
            platformUid = chatModel.platformUid,
            label = chatModel.displayName,
            model = chatModel.modelId,
            subtitle = subtitle,
            selected = isSelected,
            reasoningModes = chatModel.platform.reasoningModesForModel(chatModel.modelId)
        )
    }
}

fun AvailableChatModel.toSelectionOption(selected: Boolean = false): ModelSelectionOption =
    ModelSelectionOption(
        platformUid = platformUid,
        label = displayName,
        model = modelId,
        subtitle = platformName,
        selected = selected,
        reasoningModes = platform.reasoningModesForModel(modelId)
    )
