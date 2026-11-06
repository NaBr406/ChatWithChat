package cn.nabr.chatwithchat.presentation.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R

/**
 * @param helpInTooltip moves the explanation out of the layout and behind a help icon beside the
 * label. Screens that still show it inline keep the default.
 */
@Composable
fun FullApiUrlCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    helpInTooltip: Boolean = false
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
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                Text(
                    text = stringResource(R.string.use_full_api_url),
                    modifier = Modifier.padding(start = 8.dp, end = 2.dp)
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
