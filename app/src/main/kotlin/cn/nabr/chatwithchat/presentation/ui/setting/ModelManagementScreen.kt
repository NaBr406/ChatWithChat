package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.PlatformModelRefreshStatus
import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.presentation.common.AppleBlue
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.settingsSwitchColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    modifier: Modifier = Modifier,
    settingViewModel: SettingViewModelV2 = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val state by settingViewModel.modelManagementState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val materialColors = settingsMaterialColors()

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
            SettingsTopAppBar(
                title = stringResource(R.string.model_management),
                onNavigationClick = onNavigationClick
            )
        },
        containerColor = materialColors.canvas
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
    SettingsMaterialGroup {
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
                    color = settingsMaterialColors().primaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = modelRefreshLabel(platform),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (platform.modelRefreshStatus == PlatformModelRefreshStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        settingsMaterialColors().secondaryLabel
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(
                enabled = !refreshing,
                onClick = onRefreshClick,
                colors = ButtonDefaults.textButtonColors(contentColor = AppleBlue)
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
                color = settingsMaterialColors().secondaryLabel
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
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (model.isDefault) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = if (model.enabled) AppleBlue else settingsMaterialColors().tertiaryLabel
                    )
                }
            }
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
                onCheckedChange = onEnabledChange,
                colors = settingsSwitchColors()
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun modelRefreshLabel(platform: PlatformV2): String = when (platform.modelRefreshStatus) {
    PlatformModelRefreshStatus.SUCCESS -> stringResource(R.string.model_refresh_success)
    PlatformModelRefreshStatus.FAILED -> stringResource(R.string.model_refresh_failed_short)
    else -> stringResource(R.string.model_not_refreshed)
}
