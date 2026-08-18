package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.descriptionRes
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.titleRes

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
private fun ContextStrategyHeader(current: ContextStrategy) {
    val materialColors = settingsMaterialColors()
    SettingsMaterialGroup(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
    }
}

@Composable
private fun ContextStrategySection(
    current: ContextStrategy,
    onSelect: (ContextStrategy) -> Unit
) {
    val materialColors = settingsMaterialColors()
    Text(
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, end = 16.dp, bottom = 16.dp),
        text = stringResource(R.string.context_strategy_options_section),
        style = MaterialTheme.typography.titleSmall,
        color = materialColors.secondaryLabel
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ContextStrategy.entries.forEach { option ->
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
    SettingsMaterialGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
}
