package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.context.ContextStrategy
import cn.nabr.chatwithchat.data.context.ContextWindowResolution
import cn.nabr.chatwithchat.data.context.ContextWindowSource
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.descriptionRes
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.titleRes
import java.text.NumberFormat

/**
 * Provider-level context strategy picker, a full settings page so the five options and their
 * longer explanations have room to breathe next to the current provider default.
 *
 * Selection persists immediately on tap — there is no confirm step, matching the other full-page
 * settings in this app (tool calling modes, model toggles). The page stays open after a save so
 * the descriptions can be compared at leisure; the back arrow in the top bar leaves without any
 * pending state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextStrategyScreen(
    modifier: Modifier = Modifier,
    settingViewModel: PlatformSettingViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    val platform by settingViewModel.platformState.collectAsStateWithLifecycle()
    val contextWindowResolution by settingViewModel.contextWindowResolution.collectAsStateWithLifecycle()
    val saveError by settingViewModel.contextStrategySaveError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailedMessage = stringResource(R.string.context_strategy_save_failed)

    LaunchedEffect(saveError) {
        if (saveError) {
            snackbarHostState.showSnackbar(saveFailedMessage)
            settingViewModel.consumeContextStrategySaveError()
        }
    }

    Scaffold(
        modifier = modifier.settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.context_strategy),
                onNavigationClick = onNavigationClick
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { innerPadding ->
        platform?.let { platformData ->
            val current = ContextStrategy.fromStorage(platformData.contextStrategy)
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
            ) {
                ContextStrategyHeader(current = current)
                contextWindowResolution
                    ?.takeIf { resolution ->
                        resolution.detectedSource.isEstimated &&
                            resolution.source != ContextWindowSource.USER_OVERRIDE
                    }
                    ?.let { resolution -> ContextWindowUnknownWarning(resolution) }
                contextWindowResolution
                    ?.takeIf { resolution -> resolution.detectedSource.isEstimated }
                    ?.let { resolution ->
                        ContextWindowValueSection(
                            current = resolution,
                            onSelect = settingViewModel::updateContextWindowOverride
                        )
                    }
                ContextStrategySection(
                    current = current,
                    onSelect = settingViewModel::updateContextStrategy
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ContextWindowUnknownWarning(resolution: ContextWindowResolution) {
    val materialColors = settingsMaterialColors()
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = materialColors.secondaryLabel
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = stringResource(R.string.context_window_unknown_warning_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = materialColors.primaryLabel
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.context_window_unknown_warning_body,
                        resolution.tokens
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = materialColors.secondaryLabel
                )
            }
        }
        ContextDivider()
    }
}

@Composable
private fun ContextWindowValueSection(
    current: ContextWindowResolution,
    onSelect: (Int?) -> Unit
) {
    val materialColors = settingsMaterialColors()
    val overrideTokens = current
        .takeIf { resolution -> resolution.source == ContextWindowSource.USER_OVERRIDE }
        ?.tokens
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .defaultMinSize(minHeight = 64.dp)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.context_window_value_section_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = materialColors.primaryLabel
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.context_window_value_current,
                        formatContextWindowTokens(current.tokens)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = materialColors.secondaryLabel
                )
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Outlined.KeyboardArrowUp
                } else {
                    Icons.Outlined.KeyboardArrowDown
                },
                contentDescription = null,
                tint = materialColors.secondaryLabel
            )
        }
        ContextDivider()
        if (expanded) {
            Text(
                modifier = Modifier.padding(vertical = 12.dp),
                text = stringResource(R.string.context_window_value_section_description),
                style = MaterialTheme.typography.bodyMedium,
                color = materialColors.secondaryLabel
            )
            ContextDivider()
            ContextWindowValueOption(
                title = stringResource(R.string.context_window_value_auto),
                description = stringResource(R.string.context_window_value_auto_description),
                selected = overrideTokens == null,
                onSelect = {
                    onSelect(null)
                    expanded = false
                }
            )
            CONTEXT_WINDOW_PRESETS.forEach { tokens ->
                ContextDivider()
                ContextWindowValueOption(
                    title = formatContextWindowTokens(tokens) + " token",
                    description = null,
                    selected = overrideTokens == tokens,
                    onSelect = {
                        onSelect(tokens)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ContextWindowValueOption(
    title: String,
    description: String?,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .defaultMinSize(minHeight = 56.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = materialColors.primaryLabel
            )
            description?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = materialColors.secondaryLabel
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = materialColors.primaryLabel
                )
            }
        }
    }
}

private fun formatContextWindowTokens(tokens: Int): String =
    NumberFormat.getIntegerInstance().format(tokens)

private val CONTEXT_WINDOW_PRESETS = listOf(
    8_192,
    16_384,
    32_768,
    65_536,
    128_000,
    200_000,
    400_000,
    1_000_000
)

@Composable
private fun ContextStrategyHeader(current: ContextStrategy) {
    val materialColors = settingsMaterialColors()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = materialColors.secondaryLabel
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = stringResource(R.string.context_strategy_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = materialColors.secondaryLabel
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.context_strategy_current,
                        stringResource(current.titleRes())
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = materialColors.primaryLabel
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ContextDivider()
    }
}

@Composable
private fun ContextStrategySection(
    current: ContextStrategy,
    onSelect: (ContextStrategy) -> Unit
) {
    val materialColors = settingsMaterialColors()
    Text(
        modifier = Modifier.padding(top = 28.dp, bottom = 12.dp),
        text = stringResource(R.string.context_strategy_options_section),
        style = MaterialTheme.typography.titleSmall,
        color = materialColors.secondaryLabel
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        ContextStrategy.entries.forEachIndexed { index, option ->
            if (index > 0) {
                ContextDivider()
            }
            ContextStrategyOption(
                option = option,
                selected = option == current,
                onSelect = { onSelect(option) }
            )
        }
    }
}

@Composable
private fun ContextStrategyOption(
    option: ContextStrategy,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .defaultMinSize(minHeight = 56.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(option.titleRes()),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = materialColors.primaryLabel
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(option.descriptionRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = materialColors.secondaryLabel
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = materialColors.primaryLabel
                )
            }
        }
    }
}

@Composable
private fun ContextDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = settingsMaterialColors().separator
    )
}
