package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.model.ReasoningCapability
import cn.nabr.chatwithchat.data.model.ReasoningCapabilityProfile
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.model.reasoningCapabilityForModel

data class ModelSelectionOption(
    val platformUid: String,
    val label: String,
    val model: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val reasoningCapability: ReasoningCapabilityProfile = ReasoningCapabilityProfile(
        capability = ReasoningCapability.UNKNOWN
    )
)

@OptIn(ExperimentalMaterial3Api::class)
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
    val canOpen = enabled && options.isNotEmpty()
    val selectedOption = options.firstOrNull { it.selected } ?: options.firstOrNull()
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
    val configuration = LocalConfiguration.current
    val showTriggerReasoningSummary = modelTriggerShowsReasoningSummary(LocalDensity.current.fontScale)
    val popupWidth = modelSelectionPopupWidthDp(configuration.screenWidthDp).dp
    val popupMaxHeight = minOf(configuration.screenHeightDp.dp * 0.78f, 560.dp)
    val isLightTheme = MaterialTheme.colorScheme.surface.luminance() >= 0.5f
    val popupContainerColor = if (isLightTheme) {
        Color.White
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val popupBorderColor = if (isLightTheme) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val menuStateDescription = stringResource(if (expanded) R.string.collapse else R.string.expand)

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .widthIn(max = 230.dp)
                .heightIn(min = 48.dp)
                .semantics { stateDescription = menuStateDescription }
                .clickable(enabled = canOpen, role = Role.Button) { expanded = true }
                .padding(horizontal = 4.dp),
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
                selectedOption?.takeIf { showTriggerReasoningSummary }?.let { option ->
                    Text(
                        text = stringResource(
                            R.string.reasoning_mode_current,
                            reasoningSummary(option.reasoningCapability, selectedReasoningMode)
                        ),
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
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(popupWidth)
                .heightIn(max = popupMaxHeight),
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                clippingEnabled = true
            ),
            shape = RoundedCornerShape(8.dp),
            containerColor = popupContainerColor,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, popupBorderColor)
        ) {
            ModelSelectionPopup(
                options = options,
                initiallySelectedOption = selectedOption,
                selectedReasoningMode = selectedReasoningMode,
                onDismissRequest = { expanded = false },
                onOptionSelected = onOptionSelected,
                onReasoningModeSelected = { reasoningMode ->
                    expanded = false
                    onReasoningModeSelected(reasoningMode)
                }
            )
        }
    }
}

@Composable
private fun ColumnScope.ModelSelectionPopup(
    options: List<ModelSelectionOption>,
    initiallySelectedOption: ModelSelectionOption?,
    selectedReasoningMode: ReasoningMode,
    onDismissRequest: () -> Unit,
    onOptionSelected: (ModelSelectionOption) -> Unit,
    onReasoningModeSelected: (ReasoningMode) -> Unit
) {
    var activeSelection by remember(options, initiallySelectedOption) {
        mutableStateOf(initiallySelectedOption?.selectionKey)
    }
    val activeOption = options.firstOrNull { it.selectionKey == activeSelection }
        ?: initiallySelectedOption
        ?: options.firstOrNull()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.chat_models),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        IconButton(onClick = onDismissRequest) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    )

    options.forEach { option ->
        ModelOptionRow(
            option = option,
            isSelected = option.selectionKey == activeOption?.selectionKey,
            onClick = {
                activeSelection = option.selectionKey
                onOptionSelected(option)
            }
        )
    }

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )

    Text(
        text = stringResource(R.string.reasoning_control),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )

    val capability = activeOption?.reasoningCapability
        ?: ReasoningCapabilityProfile(ReasoningCapability.UNKNOWN)
    if (capability.isConfigurable) {
        capability.supportedModes.forEach { reasoningMode ->
            ReasoningModeRow(
                reasoningMode = reasoningMode,
                capability = capability.capability,
                isSelected = reasoningMode == selectedReasoningMode,
                onClick = { onReasoningModeSelected(reasoningMode) }
            )
        }
    } else {
        ReasoningCapabilitySummary(capability.capability)
    }
    Spacer(modifier = Modifier.heightIn(min = 8.dp))
}

@Composable
private fun ModelOptionRow(
    option: ModelSelectionOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    val rowColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(rowColor)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = isSelected }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
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
    }
}

@Composable
private fun ReasoningModeRow(
    reasoningMode: ReasoningMode,
    capability: ReasoningCapability,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = isSelected }
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reasoningModeLabel(reasoningMode, capability),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = reasoningModeDescription(reasoningMode, capability),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReasoningCapabilitySummary(capability: ReasoningCapability) {
    val summary = when (capability) {
        ReasoningCapability.UNKNOWN -> stringResource(R.string.reasoning_mode_unknown)
        ReasoningCapability.DEFAULT_ONLY -> stringResource(R.string.reasoning_mode_default_only)
        ReasoningCapability.UNSUPPORTED -> stringResource(R.string.reasoning_mode_not_supported)
        ReasoningCapability.TOGGLE,
        ReasoningCapability.EFFORT -> stringResource(R.string.reasoning_mode_model_default)
    }

    Text(
        text = summary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun reasoningSummary(
    capability: ReasoningCapabilityProfile,
    selectedReasoningMode: ReasoningMode
): String = when (capability.capability) {
    ReasoningCapability.TOGGLE,
    ReasoningCapability.EFFORT -> reasoningModeLabel(selectedReasoningMode, capability.capability)

    ReasoningCapability.DEFAULT_ONLY,
    ReasoningCapability.UNKNOWN -> stringResource(R.string.reasoning_mode_model_default)

    ReasoningCapability.UNSUPPORTED -> stringResource(R.string.reasoning_mode_not_supported)
}

@Composable
private fun reasoningModeLabel(
    reasoningMode: ReasoningMode,
    capability: ReasoningCapability
): String {
    if (capability == ReasoningCapability.TOGGLE && reasoningMode == ReasoningMode.MEDIUM) {
        return stringResource(R.string.reasoning_mode_on)
    }

    return when (reasoningMode) {
        ReasoningMode.AUTO -> stringResource(R.string.reasoning_mode_auto)
        ReasoningMode.OFF -> stringResource(R.string.reasoning_mode_off)
        ReasoningMode.LOW -> stringResource(R.string.reasoning_mode_low)
        ReasoningMode.MEDIUM -> stringResource(R.string.reasoning_mode_medium)
        ReasoningMode.HIGH -> stringResource(R.string.reasoning_mode_high)
        ReasoningMode.MAX -> stringResource(R.string.reasoning_mode_max)
    }
}

@Composable
private fun reasoningModeDescription(
    reasoningMode: ReasoningMode,
    capability: ReasoningCapability
): String {
    if (capability == ReasoningCapability.TOGGLE && reasoningMode == ReasoningMode.MEDIUM) {
        return stringResource(R.string.reasoning_mode_on_description)
    }

    return when (reasoningMode) {
        ReasoningMode.AUTO -> stringResource(R.string.reasoning_mode_auto_description)
        ReasoningMode.OFF -> stringResource(R.string.reasoning_mode_off_description)
        ReasoningMode.LOW -> stringResource(R.string.reasoning_mode_low_description)
        ReasoningMode.MEDIUM -> stringResource(R.string.reasoning_mode_medium_description)
        ReasoningMode.HIGH -> stringResource(R.string.reasoning_mode_high_description)
        ReasoningMode.MAX -> stringResource(R.string.reasoning_mode_max_description)
    }
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
            reasoningCapability = chatModel.platform.reasoningCapabilityForModel(chatModel.modelId)
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
        reasoningCapability = platform.reasoningCapabilityForModel(modelId)
    )

private val ModelSelectionOption.selectionKey: String
    get() = "$platformUid:$model"

internal fun modelSelectionPopupWidthDp(screenWidthDp: Int): Int =
    (screenWidthDp - MODEL_SELECTION_HORIZONTAL_MARGIN_DP)
        .coerceAtLeast(1)
        .coerceAtMost(MODEL_SELECTION_MAX_WIDTH_DP)

internal fun modelTriggerShowsReasoningSummary(fontScale: Float): Boolean =
    fontScale < MODEL_TRIGGER_SUMMARY_MAX_FONT_SCALE

private const val MODEL_SELECTION_HORIZONTAL_MARGIN_DP = 24
private const val MODEL_SELECTION_MAX_WIDTH_DP = 400
private const val MODEL_TRIGGER_SUMMARY_MAX_FONT_SCALE = 1.5f
