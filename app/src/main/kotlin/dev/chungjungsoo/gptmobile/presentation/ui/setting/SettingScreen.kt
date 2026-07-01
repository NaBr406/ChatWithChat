package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.util.getClientTypeDisplayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    modifier: Modifier = Modifier,
    settingViewModel: SettingViewModelV2 = hiltViewModel(),
    onNavigationClick: () -> Unit,
    onNavigateToAddPlatform: () -> Unit,
    onNavigateToPlatformSetting: (String) -> Unit,
    onNavigateToModelManagement: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToAboutPage: () -> Unit
) {
    val platformState by settingViewModel.platformState.collectAsStateWithLifecycle()
    val dialogState by settingViewModel.dialogState.collectAsStateWithLifecycle()
    val memoryEnabled by settingViewModel.memoryEnabled.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingViewModel.fetchPlatforms()
                settingViewModel.fetchMemoryEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
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
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(scrollState)
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_models_providers)) {
                SettingsRow(
                    title = stringResource(R.string.add_platform),
                    description = stringResource(R.string.add_platform_description),
                    onClick = onNavigateToAddPlatform
                )
                SettingsRow(
                    title = stringResource(R.string.model_management),
                    description = stringResource(R.string.model_management_description),
                    onClick = onNavigateToModelManagement
                )
                platformState.forEach { platform ->
                    PlatformItem(
                        platform = platform,
                        onItemClick = { onNavigateToPlatformSetting(platform.uid) }
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_personalization)) {
                MemoryEnabledItem(
                    memoryEnabled = memoryEnabled,
                    onCheckedChange = settingViewModel::updateMemoryEnabled
                )
                SettingsRow(
                    title = stringResource(R.string.memory),
                    description = stringResource(R.string.memory_page_description),
                    onClick = onNavigateToMemory
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_app)) {
                SettingsRow(
                    title = stringResource(R.string.about),
                    description = stringResource(R.string.about_description),
                    onClick = onNavigateToAboutPage
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (dialogState.isDeleteDialogOpen) {
                DeletePlatformDialog(settingViewModel)
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        modifier = Modifier.padding(start = 24.dp, top = 18.dp, end = 24.dp, bottom = 6.dp),
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    content()
}

@Composable
private fun SettingsRow(
    title: String,
    description: String?,
    onClick: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = description?.let {
            {
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
    HorizontalDivider(modifier = Modifier.padding(start = 24.dp))
}

@Composable
private fun PlatformItem(
    platform: PlatformV2,
    onItemClick: () -> Unit
) {
    val statusText = if (platform.enabled) stringResource(R.string.enabled) else stringResource(R.string.disabled)
    SettingsRow(
        title = platform.name,
        description = "${getClientTypeDisplayName(platform.compatibleType)} · $statusText",
        onClick = onItemClick
    )
}

@Composable
fun MemoryEnabledItem(
    memoryEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsRow(
        title = stringResource(R.string.memory_enabled_title),
        description = stringResource(R.string.memory_enabled_description),
        onClick = { onCheckedChange(!memoryEnabled) },
        trailingContent = {
            Switch(
                checked = memoryEnabled,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Composable
fun DeletePlatformDialog(
    settingViewModel: SettingViewModelV2 = hiltViewModel()
) {
    AlertDialog(
        title = { Text(stringResource(R.string.delete_platform)) },
        text = { Text(stringResource(R.string.delete_platform_confirmation)) },
        onDismissRequest = settingViewModel::closeDeleteDialog,
        confirmButton = {
            TextButton(onClick = settingViewModel::confirmDelete) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = settingViewModel::closeDeleteDialog) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
