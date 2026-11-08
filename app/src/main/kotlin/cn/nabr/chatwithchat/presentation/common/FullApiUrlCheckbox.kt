package cn.nabr.chatwithchat.presentation.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R

/**
 * @param helpInTooltip moves the explanation out of the layout and behind a help icon beside the
 * label. Screens that still show it inline keep the default.
 * @param compact renders a secondary option: a scaled-down box and a short, smaller secondary label,
 * for places where the checkbox sits among heavier fields and should not compete with them.
 */
@Composable
fun FullApiUrlCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    helpInTooltip: Boolean = false,
    compact: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The toggle covers the checkbox and its label only, so the help icon stays a separate target.
            Row(
                modifier = Modifier
                    .then(if (helpInTooltip) Modifier else Modifier.weight(1f))
                    .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
                    .then(if (compact) Modifier.heightIn(min = 40.dp) else Modifier.padding(vertical = 4.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = if (compact) settingsCheckboxColors() else CheckboxDefaults.colors(),
                    modifier = if (compact) Modifier.scale(COMPACT_CHECKBOX_SCALE) else Modifier
                )
                Text(
                    text = stringResource(if (compact) R.string.use_full_api_url_compact else R.string.use_full_api_url),
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    color = if (compact) settingsMaterialColors().secondaryLabel else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = if (compact) 6.dp else 8.dp, end = 2.dp)
                )
            }
            if (helpInTooltip) {
                SettingsHelpTooltipIcon(helpText = stringResource(R.string.use_full_api_url_supporting))
            }
        }
        if (!helpInTooltip) {
            Text(
                text = stringResource(R.string.use_full_api_url_supporting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val COMPACT_CHECKBOX_SCALE = 0.8f
