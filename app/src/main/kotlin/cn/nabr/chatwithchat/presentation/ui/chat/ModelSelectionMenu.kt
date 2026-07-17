package cn.nabr.chatwithchat.presentation.ui.chat

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.model.ReasoningCapability
import cn.nabr.chatwithchat.data.model.ReasoningCapabilityProfile
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.model.reasoningCapabilityForModel
import cn.nabr.chatwithchat.presentation.common.AppleBlue
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import kotlin.math.roundToInt

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
    var isDialogComposed by remember { mutableStateOf(false) }
    var anchorBottomPx by remember { mutableIntStateOf(0) }
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
    val popupWidth = modelSelectionPopupWidthDp(configuration.screenWidthDp).dp
    val popupMaxHeight = minOf(configuration.screenHeightDp.dp * 0.66f, 460.dp)
    val isLightTheme = MaterialTheme.colorScheme.surface.luminance() >= 0.5f
    val popupMaterialColors = settingsMaterialColors()
    val popupContainerColor = popupMaterialColors.grouped.copy(alpha = if (isLightTheme) 0.96f else 0.94f)
    val popupBorderColor = popupMaterialColors.separatorStrong
    val menuStateDescription = stringResource(if (expanded) R.string.collapse else R.string.expand)

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .widthIn(max = 230.dp)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics { stateDescription = menuStateDescription }
                .clickable(enabled = canOpen, role = Role.Button) {
                    isDialogComposed = true
                    expanded = true
                }
                .onGloballyPositioned { coordinates ->
                    anchorBottomPx = coordinates.boundsInWindow().bottom.roundToInt()
                }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor
            )
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

        if (isDialogComposed) {
            ModelSelectionDialog(
                visible = expanded,
                popupWidth = popupWidth,
                popupMaxHeight = popupMaxHeight,
                anchorBottomPx = anchorBottomPx,
                containerColor = popupContainerColor,
                borderColor = popupBorderColor,
                options = options,
                selectedOption = selectedOption,
                selectedReasoningMode = selectedReasoningMode,
                onDismissRequest = { expanded = false },
                onDismissed = { isDialogComposed = false },
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
private fun ModelSelectionDialog(
    visible: Boolean,
    popupWidth: Dp,
    popupMaxHeight: Dp,
    anchorBottomPx: Int,
    containerColor: Color,
    borderColor: Color,
    options: List<ModelSelectionOption>,
    selectedOption: ModelSelectionOption?,
    selectedReasoningMode: ReasoningMode,
    onDismissRequest: () -> Unit,
    onDismissed: () -> Unit,
    onOptionSelected: (ModelSelectionOption) -> Unit,
    onReasoningModeSelected: (ReasoningMode) -> Unit
) {
    val shape = RoundedCornerShape(MODEL_SELECTION_CORNER_RADIUS_DP.dp)
    val visibilityState = remember {
        MutableTransitionState(false).apply {
            targetState = visible
        }
    }
    if (visibilityState.targetState != visible) {
        visibilityState.targetState = visible
    }
    LaunchedEffect(visible, visibilityState.isIdle, visibilityState.currentState) {
        if (!visible && visibilityState.isIdle && !visibilityState.currentState) {
            onDismissed()
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        FrostedGlassDialogWindow(
            anchorBottomPx = anchorBottomPx,
            popupWidth = popupWidth
        )
        AnimatedVisibility(
            visibleState = visibilityState,
            enter = scaleIn(
                animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                initialScale = 0.98f,
                transformOrigin = TransformOrigin(0.5f, 0f)
            ) + fadeIn(
                animationSpec = tween(durationMillis = 120),
                initialAlpha = 0.72f
            ),
            exit = scaleOut(
                animationSpec = tween(durationMillis = 140, easing = FastOutLinearInEasing),
                targetScale = 0.96f,
                transformOrigin = TransformOrigin(0.5f, 0f)
            ) + fadeOut(
                animationSpec = tween(durationMillis = 90)
            )
        ) {
            Surface(
                modifier = Modifier
                    .width(popupWidth)
                    .heightIn(max = popupMaxHeight),
                shape = shape,
                color = containerColor,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ModelSelectionPopup(
                        options = options,
                        initiallySelectedOption = selectedOption,
                        selectedReasoningMode = selectedReasoningMode,
                        onOptionSelected = onOptionSelected,
                        onReasoningModeSelected = onReasoningModeSelected
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ModelSelectionPopup(
    options: List<ModelSelectionOption>,
    initiallySelectedOption: ModelSelectionOption?,
    selectedReasoningMode: ReasoningMode,
    onOptionSelected: (ModelSelectionOption) -> Unit,
    onReasoningModeSelected: (ReasoningMode) -> Unit
) {
    var activeSelection by remember(options, initiallySelectedOption) {
        mutableStateOf(initiallySelectedOption?.selectionKey)
    }
    var isReasoningExpanded by remember(activeSelection) { mutableStateOf(false) }
    val activeOption = options.firstOrNull { it.selectionKey == activeSelection }
        ?: initiallySelectedOption
        ?: options.firstOrNull()

    Spacer(modifier = Modifier.heightIn(min = 4.dp))
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
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
    )

    val capability = activeOption?.reasoningCapability
        ?: ReasoningCapabilityProfile(ReasoningCapability.UNKNOWN)
    ReasoningControlRow(
        capability = capability,
        selectedReasoningMode = selectedReasoningMode,
        expanded = isReasoningExpanded,
        onClick = { isReasoningExpanded = !isReasoningExpanded }
    )
    AnimatedVisibility(
        visible = capability.isConfigurable && isReasoningExpanded,
        enter = expandVertically(
            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top,
            clip = true
        ) + fadeIn(
            animationSpec = tween(durationMillis = 100),
            initialAlpha = 0.72f
        ),
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = 120, easing = FastOutLinearInEasing),
            shrinkTowards = Alignment.Top,
            clip = true
        ) + fadeOut(
            animationSpec = tween(durationMillis = 80)
        )
    ) {
        Column {
            capability.supportedModes.forEach { reasoningMode ->
                ReasoningModeRow(
                    reasoningMode = reasoningMode,
                    capability = capability.capability,
                    isSelected = reasoningMode == selectedReasoningMode,
                    onClick = { onReasoningModeSelected(reasoningMode) }
                )
            }
        }
    }
    Spacer(modifier = Modifier.heightIn(min = 4.dp))
}

@Composable
private fun ModelOptionRow(
    option: ModelSelectionOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = isSelected }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = AppleBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = option.label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        option.subtitle?.let { subtitle ->
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = subtitle,
                modifier = Modifier.widthIn(max = 96.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReasoningControlRow(
    capability: ReasoningCapabilityProfile,
    selectedReasoningMode: ReasoningMode,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "reasoningMenuArrowRotation"
    )
    val summary = if (capability.isConfigurable) {
        reasoningModeLabel(selectedReasoningMode, capability.capability)
    } else {
        reasoningCapabilitySummary(capability.capability)
    }

    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = capability.isConfigurable, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.reasoning_mode),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = summary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (capability.isConfigurable) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
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
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
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
                    tint = AppleBlue,
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
private fun reasoningCapabilitySummary(capability: ReasoningCapability): String =
    when (capability) {
        ReasoningCapability.UNKNOWN -> stringResource(R.string.reasoning_mode_unknown)
        ReasoningCapability.DEFAULT_ONLY -> stringResource(R.string.reasoning_mode_default_only)
        ReasoningCapability.UNSUPPORTED -> stringResource(R.string.reasoning_mode_not_supported)
        ReasoningCapability.TOGGLE,
        ReasoningCapability.EFFORT -> stringResource(R.string.reasoning_mode_model_default)
    }

@Composable
private fun FrostedGlassDialogWindow(
    anchorBottomPx: Int,
    popupWidth: Dp
) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val density = LocalDensity.current
    val popupWidthPx = with(density) { popupWidth.roundToPx() }
    val cornerRadiusPx = with(density) { MODEL_SELECTION_CORNER_RADIUS_DP.dp.toPx() }
    val blurRadiusPx = with(density) { MODEL_SELECTION_BLUR_RADIUS_DP.dp.roundToPx() }
    val verticalOffsetPx = with(density) { MODEL_SELECTION_VERTICAL_OFFSET_DP.dp.roundToPx() }
    val elevationPx = with(density) { 8.dp.toPx() }
    val background = remember(cornerRadiusPx) {
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(Color.Transparent.toArgb())
        }
    }

    DisposableEffect(window, popupWidthPx, anchorBottomPx, background, blurRadiusPx) {
        window.setWindowAnimations(0)
        window.setBackgroundDrawable(background)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        window.setLayout(popupWidthPx, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.decorView.clipToOutline = true
        window.decorView.elevation = elevationPx
        window.attributes = window.attributes.apply {
            width = popupWidthPx
            height = WindowManager.LayoutParams.WRAP_CONTENT
            y = anchorBottomPx + verticalOffsetPx
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
        .coerceAtLeast(MODEL_SELECTION_MIN_WIDTH_DP)
        .coerceAtMost(MODEL_SELECTION_MAX_WIDTH_DP)
        .coerceAtMost(screenWidthDp.coerceAtLeast(1))

private const val MODEL_SELECTION_HORIZONTAL_MARGIN_DP = 96
private const val MODEL_SELECTION_MIN_WIDTH_DP = 208
private const val MODEL_SELECTION_MAX_WIDTH_DP = 264
private const val MODEL_SELECTION_BLUR_RADIUS_DP = 20
private const val MODEL_SELECTION_CORNER_RADIUS_DP = 16
private const val MODEL_SELECTION_VERTICAL_OFFSET_DP = 4
