package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.LastSelectedModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    platforms: List<PlatformV2>,
    selectedPlatformUids: List<String>,
    modelOverrides: Map<String, String>,
    lastSelectedModel: LastSelectedModel?,
    allowCompare: Boolean,
    startInCompareMode: Boolean = false,
    onDismissRequest: () -> Unit,
    onSingleModelSelected: (platformUid: String, model: String) -> Unit,
    onCompareSelected: (platformUids: List<String>) -> Unit
) {
    val enabledPlatforms = remember(platforms) { platforms.filter { it.enabled } }
    val enabledPlatformUids = remember(enabledPlatforms) { enabledPlatforms.map { it.uid }.toSet() }
    val initialSelectedUid = remember(enabledPlatforms, selectedPlatformUids, lastSelectedModel) {
        selectedPlatformUids.firstOrNull { it in enabledPlatformUids }
            ?: lastSelectedModel?.platformUid?.takeIf { it in enabledPlatformUids }
            ?: enabledPlatforms.firstOrNull()?.uid.orEmpty()
    }
    var isCompareMode by remember(allowCompare, startInCompareMode) { mutableStateOf(allowCompare && startInCompareMode) }
    var selectedPlatformUid by remember(initialSelectedUid) { mutableStateOf(initialSelectedUid) }
    var modelEdits by remember(platforms, modelOverrides, lastSelectedModel) {
        mutableStateOf(
            platforms.associate { platform ->
                platform.uid to defaultModelForPlatform(
                    platform = platform,
                    modelOverrides = modelOverrides,
                    lastSelectedModel = lastSelectedModel
                )
            }
        )
    }
    var compareSelection by remember(enabledPlatforms, selectedPlatformUids) {
        mutableStateOf(selectedPlatformUids.filter { it in enabledPlatformUids })
    }
    val selectedModel = modelEdits[selectedPlatformUid].orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.model_picker_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (allowCompare) {
                ModelPickerModeToggle(
                    modifier = Modifier.padding(top = 16.dp),
                    isCompareMode = isCompareMode,
                    onSingleModeClick = { isCompareMode = false },
                    onCompareModeClick = { isCompareMode = true }
                )
            }

            if (enabledPlatforms.isEmpty()) {
                Text(
                    modifier = Modifier.padding(vertical = 32.dp),
                    text = stringResource(R.string.model_picker_no_enabled_platform),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isCompareMode) {
                CompareModeContent(
                    platforms = enabledPlatforms,
                    selectedPlatformUids = compareSelection,
                    modelOverrides = modelEdits,
                    onPlatformToggled = { platformUid ->
                        compareSelection = if (platformUid in compareSelection) {
                            compareSelection - platformUid
                        } else {
                            compareSelection + platformUid
                        }
                    }
                )
            } else {
                SingleModelContent(
                    platforms = enabledPlatforms,
                    selectedPlatformUid = selectedPlatformUid,
                    selectedModel = selectedModel,
                    modelOverrides = modelEdits,
                    onPlatformSelected = { platformUid -> selectedPlatformUid = platformUid },
                    onModelChanged = { model ->
                        modelEdits = modelEdits.toMutableMap().apply { put(selectedPlatformUid, model) }
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    enabled = if (isCompareMode) {
                        compareSelection.size > 1
                    } else {
                        selectedPlatformUid.isNotBlank() && selectedModel.trim().isNotBlank()
                    },
                    onClick = {
                        if (isCompareMode) {
                            onCompareSelected(compareSelection)
                        } else {
                            onSingleModelSelected(selectedPlatformUid, selectedModel.trim())
                        }
                    }
                ) {
                    Text(
                        text = if (isCompareMode) {
                            stringResource(R.string.model_picker_start_compare)
                        } else {
                            stringResource(R.string.model_picker_use_model)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerModeToggle(
    modifier: Modifier = Modifier,
    isCompareMode: Boolean,
    onSingleModeClick: () -> Unit,
    onCompareModeClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(18.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModelPickerModeToggleItem(
            modifier = Modifier.weight(1f),
            selected = !isCompareMode,
            label = stringResource(R.string.model_picker_single),
            onClick = onSingleModeClick
        )
        ModelPickerModeToggleItem(
            modifier = Modifier.weight(1f),
            selected = isCompareMode,
            label = stringResource(R.string.model_picker_compare),
            onClick = onCompareModeClick
        )
    }
}

@Composable
private fun ModelPickerModeToggleItem(
    modifier: Modifier,
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            modifier = Modifier.padding(vertical = 10.dp),
            text = label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun SingleModelContent(
    platforms: List<PlatformV2>,
    selectedPlatformUid: String,
    selectedModel: String,
    modelOverrides: Map<String, String>,
    onPlatformSelected: (String) -> Unit,
    onModelChanged: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )
        ) {
            Column {
                platforms.forEachIndexed { index, platform ->
                    ModelPickerPlatformRow(
                        platform = platform,
                        model = modelOverrides[platform.uid].orEmpty(),
                        selected = platform.uid == selectedPlatformUid,
                        compareMode = false,
                        onClick = { onPlatformSelected(platform.uid) }
                    )
                    if (index < platforms.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 58.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            value = selectedModel,
            onValueChange = onModelChanged,
            singleLine = true,
            label = { Text(text = stringResource(R.string.model_picker_model_label)) }
        )
    }
}

@Composable
private fun CompareModeContent(
    platforms: List<PlatformV2>,
    selectedPlatformUids: List<String>,
    modelOverrides: Map<String, String>,
    onPlatformToggled: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )
        ) {
            Column {
                platforms.forEachIndexed { index, platform ->
                    ModelPickerPlatformRow(
                        platform = platform,
                        model = modelOverrides[platform.uid].orEmpty(),
                        selected = platform.uid in selectedPlatformUids,
                        compareMode = true,
                        onClick = { onPlatformToggled(platform.uid) }
                    )
                    if (index < platforms.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 58.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerPlatformRow(
    platform: PlatformV2,
    model: String,
    selected: Boolean,
    compareMode: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (compareMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() }
            )
        } else {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
        }
        Column(
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f)
        ) {
            Text(
                text = platform.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = model.ifBlank { platform.model },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun defaultModelForPlatform(
    platform: PlatformV2,
    modelOverrides: Map<String, String>,
    lastSelectedModel: LastSelectedModel?
): String = when {
    modelOverrides[platform.uid].orEmpty().isNotBlank() -> modelOverrides[platform.uid].orEmpty()
    lastSelectedModel?.platformUid == platform.uid && lastSelectedModel.model.isNotBlank() -> lastSelectedModel.model
    else -> platform.model
}
