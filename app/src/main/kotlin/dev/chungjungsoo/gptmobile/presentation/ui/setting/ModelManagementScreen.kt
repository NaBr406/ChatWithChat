package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformModelRefreshStatus
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    modifier: Modifier = Modifier,
    settingViewModel: SettingViewModelV2 = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val state by settingViewModel.modelManagementState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingViewModel.fetchPlatforms()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_management)) },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.platforms, key = { it.uid }) { platform ->
                val models = state.modelsByPlatformUid[platform.uid].orEmpty()
                val refreshing = platform.uid in state.refreshingPlatformUids
                PlatformModelGroup(
                    platform = platform,
                    models = models,
                    refreshing = refreshing,
                    onRefreshClick = { settingViewModel.refreshPlatformModels(platform.uid) },
                    onModelEnabledChange = { model, enabled ->
                        settingViewModel.updatePlatformModelEnabled(platform.uid, model.modelId, enabled)
                    },
                    onDefaultClick = { model ->
                        settingViewModel.setPlatformDefaultModel(platform.uid, model.modelId)
                    }
                )
            }
        }
    }
}

@Composable
private fun PlatformModelGroup(
    platform: PlatformV2,
    models: List<PlatformModelV2>,
    refreshing: Boolean,
    onRefreshClick: () -> Unit,
    onModelEnabledChange: (PlatformModelV2, Boolean) -> Unit,
    onDefaultClick: (PlatformModelV2) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 14.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = platform.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = modelRefreshLabel(platform),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (platform.modelRefreshStatus == PlatformModelRefreshStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(
                enabled = !refreshing,
                onClick = onRefreshClick
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    modifier = Modifier.padding(start = 6.dp),
                    text = stringResource(R.string.refresh_models)
                )
            }
        }

        if (models.isEmpty()) {
            Text(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                text = if (platform.modelRefreshStatus == PlatformModelRefreshStatus.FAILED) {
                    stringResource(R.string.model_refresh_failed_inline, platform.modelRefreshError.orEmpty())
                } else {
                    stringResource(R.string.no_available_models)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            models.forEachIndexed { index, model ->
                ModelManagementRow(
                    model = model,
                    onEnabledChange = { enabled -> onModelEnabledChange(model, enabled) },
                    onDefaultClick = { onDefaultClick(model) }
                )
                if (index < models.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelManagementRow(
    model: PlatformModelV2,
    onEnabledChange: (Boolean) -> Unit,
    onDefaultClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(enabled = model.enabled, onClick = onDefaultClick),
        leadingContent = {
            RadioButton(
                selected = model.isDefault,
                enabled = model.enabled,
                onClick = onDefaultClick
            )
        },
        headlineContent = {
            Text(
                text = model.displayName.ifBlank { model.modelId },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = model.description.ifBlank { model.modelId },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Switch(
                checked = model.enabled,
                onCheckedChange = onEnabledChange
            )
        }
    )
}

@Composable
private fun modelRefreshLabel(platform: PlatformV2): String = when (platform.modelRefreshStatus) {
    PlatformModelRefreshStatus.SUCCESS -> stringResource(R.string.model_refresh_success)
    PlatformModelRefreshStatus.FAILED -> stringResource(R.string.model_refresh_failed_short)
    else -> stringResource(R.string.model_not_refreshed)
}
