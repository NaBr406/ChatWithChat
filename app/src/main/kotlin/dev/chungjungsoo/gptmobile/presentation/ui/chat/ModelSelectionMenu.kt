package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.AvailableChatModel

data class ModelSelectionOption(
    val platformUid: String,
    val label: String,
    val model: String,
    val subtitle: String? = null,
    val selected: Boolean = false
)

@Composable
fun ModelSelectionMenu(
    label: String,
    options: List<ModelSelectionOption>,
    enabled: Boolean,
    onOptionSelected: (ModelSelectionOption) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val canOpen = enabled && options.isNotEmpty()
    val contentColor = if (canOpen) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(modifier = modifier.wrapContentSize(Alignment.Center)) {
        Row(
            modifier = Modifier
                .widthIn(max = 220.dp)
                .heightIn(min = 40.dp)
                .clickable(enabled = canOpen) { expanded = true }
                .padding(horizontal = 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor
            )
            Spacer(modifier = Modifier.size(2.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(R.string.chat_models),
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(12.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
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
                        androidx.compose.foundation.layout.Column {
                            Text(
                                text = option.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                    onClick = {
                        expanded = false
                        onOptionSelected(option)
                    }
                )
            }
        }
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
            selected = isSelected
        )
    }
}

fun AvailableChatModel.toSelectionOption(selected: Boolean = false): ModelSelectionOption =
    ModelSelectionOption(
        platformUid = platformUid,
        label = displayName,
        model = modelId,
        subtitle = platformName,
        selected = selected
    )
