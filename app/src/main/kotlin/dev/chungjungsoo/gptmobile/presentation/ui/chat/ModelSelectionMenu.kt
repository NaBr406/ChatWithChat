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
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

data class ModelSelectionOption(
    val platformUid: String,
    val label: String,
    val model: String,
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
                        Text(
                            text = option.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
    platforms: List<PlatformV2>,
    selectedPlatformUid: String?,
    modelForPlatform: (PlatformV2) -> String?
): List<ModelSelectionOption> = platforms.mapNotNull { platform ->
    val model = modelForPlatform(platform)?.trim().orEmpty().ifBlank { platform.model.trim() }
    val label = model.ifBlank { platform.name.trim() }

    ModelSelectionOption(
        platformUid = platform.uid,
        label = label,
        model = model,
        selected = platform.uid == selectedPlatformUid
    )
}
