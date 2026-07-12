package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.tool.ToolCallingMode
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchMode
import dev.chungjungsoo.gptmobile.presentation.common.AppleBlue
import dev.chungjungsoo.gptmobile.presentation.common.AppleGray
import dev.chungjungsoo.gptmobile.presentation.common.AppleGreen
import dev.chungjungsoo.gptmobile.presentation.common.AppleIndigo
import dev.chungjungsoo.gptmobile.presentation.common.AppleOrange
import dev.chungjungsoo.gptmobile.presentation.common.ApplePurple
import dev.chungjungsoo.gptmobile.presentation.common.AppleRed
import dev.chungjungsoo.gptmobile.presentation.common.LocalNotificationPermissionRequester
import dev.chungjungsoo.gptmobile.presentation.common.SettingsMaterialGroup
import dev.chungjungsoo.gptmobile.presentation.common.settingsMaterialColors
import dev.chungjungsoo.gptmobile.presentation.common.settingsSwitchColors
import dev.chungjungsoo.gptmobile.presentation.common.settingsTextFieldColors
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
    onNavigateToToolSettings: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToAboutPage: () -> Unit
) {
    val platformState by settingViewModel.platformState.collectAsStateWithLifecycle()
    val dialogState by settingViewModel.dialogState.collectAsStateWithLifecycle()
    val memoryEnabled by settingViewModel.memoryEnabled.collectAsStateWithLifecycle()
    val memoryMaintenanceNotificationsEnabled by settingViewModel.memoryMaintenanceNotificationsEnabled.collectAsStateWithLifecycle()
    val notificationPermissionRequester = LocalNotificationPermissionRequester.current
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val materialColors = settingsMaterialColors()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingViewModel.fetchPlatforms()
                settingViewModel.fetchMemoryEnabled()
                settingViewModel.fetchMemoryMaintenanceNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBackIos,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = materialColors.navigation)
            )
        },
        containerColor = materialColors.canvas
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_models_providers)) {
                SettingsRow(
                    title = stringResource(R.string.add_platform),
                    description = stringResource(R.string.add_platform_description),
                    leadingIcon = Icons.Rounded.Add,
                    iconContainerColor = AppleBlue,
                    onClick = onNavigateToAddPlatform
                )
                SettingsRow(
                    title = stringResource(R.string.model_management),
                    description = stringResource(R.string.model_management_description),
                    leadingIcon = Icons.Rounded.Tune,
                    iconContainerColor = AppleIndigo,
                    showDivider = platformState.isNotEmpty(),
                    onClick = onNavigateToModelManagement
                )
                platformState.forEachIndexed { index, platform ->
                    PlatformItem(
                        platform = platform,
                        showDivider = index < platformState.lastIndex,
                        onItemClick = { onNavigateToPlatformSetting(platform.uid) }
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_personalization)) {
                MemoryEnabledItem(
                    memoryEnabled = memoryEnabled,
                    onCheckedChange = settingViewModel::updateMemoryEnabled
                )
                MemoryMaintenanceNotificationsItem(
                    enabled = memoryMaintenanceNotificationsEnabled,
                    onCheckedChange = { enabled ->
                        settingViewModel.updateMemoryMaintenanceNotificationsEnabled(enabled)
                        if (enabled) {
                            notificationPermissionRequester.requestPostNotificationsPermission()
                        }
                    }
                )
                SettingsRow(
                    title = stringResource(R.string.memory),
                    description = stringResource(R.string.memory_page_description),
                    leadingIcon = Icons.Rounded.Memory,
                    iconContainerColor = ApplePurple,
                    showDivider = false,
                    onClick = onNavigateToMemory
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_tools)) {
                SettingsRow(
                    title = stringResource(R.string.tool_settings_title),
                    description = stringResource(R.string.tool_settings_description),
                    leadingIcon = Icons.Rounded.Extension,
                    iconContainerColor = AppleOrange,
                    showDivider = false,
                    onClick = onNavigateToToolSettings
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_app)) {
                SettingsRow(
                    title = stringResource(R.string.about),
                    description = stringResource(R.string.about_description),
                    leadingIcon = Icons.Rounded.Info,
                    iconContainerColor = AppleGray,
                    showDivider = false,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSettingsScreen(
    modifier: Modifier = Modifier,
    settingViewModel: SettingViewModelV2 = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val webSearchSettings by settingViewModel.webSearchSettings.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val materialColors = settingsMaterialColors()

    LaunchedEffect(Unit) {
        settingViewModel.fetchWebSearchSettings()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingViewModel.fetchWebSearchSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.tool_settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBackIos,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = materialColors.navigation)
            )
        },
        containerColor = materialColors.canvas
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_tool_calling)) {
                ToolCallingModeItem(
                    mode = ToolCallingMode.Off,
                    selectedMode = webSearchSettings.toolCallingMode,
                    title = stringResource(R.string.tool_calling_mode_off),
                    description = stringResource(R.string.tool_calling_mode_off_description),
                    onSelected = settingViewModel::updateToolCallingMode
                )
                ToolCallingModeItem(
                    mode = ToolCallingMode.Auto,
                    selectedMode = webSearchSettings.toolCallingMode,
                    title = stringResource(R.string.tool_calling_mode_auto),
                    description = stringResource(R.string.tool_calling_mode_auto_description),
                    showDivider = false,
                    onSelected = settingViewModel::updateToolCallingMode
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_web_search)) {
                WebSearchModeItem(
                    mode = WebSearchMode.Off,
                    selectedMode = webSearchSettings.mode,
                    title = stringResource(R.string.web_search_mode_off),
                    description = stringResource(R.string.web_search_mode_off_description),
                    onSelected = settingViewModel::updateWebSearchMode
                )
                WebSearchModeItem(
                    mode = WebSearchMode.Auto,
                    selectedMode = webSearchSettings.mode,
                    title = stringResource(R.string.web_search_mode_auto),
                    description = stringResource(R.string.web_search_mode_auto_description),
                    onSelected = settingViewModel::updateWebSearchMode
                )
                WebSearchModeItem(
                    mode = WebSearchMode.Always,
                    selectedMode = webSearchSettings.mode,
                    title = stringResource(R.string.web_search_mode_always),
                    description = stringResource(R.string.web_search_mode_always_description),
                    onSelected = settingViewModel::updateWebSearchMode
                )
                WebSearchBaseUrlItem(
                    baseUrl = webSearchSettings.searxngBaseUrl,
                    hasError = webSearchSettings.searxngBaseUrlError,
                    onBaseUrlChange = settingViewModel::updateWebSearchSearxngBaseUrl
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val materialColors = settingsMaterialColors()
    Text(
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 7.dp),
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = materialColors.secondaryLabel
    )
    SettingsMaterialGroup(content = content)
}

@Composable
private fun SettingsRow(
    title: String,
    description: String?,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
    iconContainerColor: Color = AppleBlue,
    showNavigationIndicator: Boolean = true,
    showDivider: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val materialColors = settingsMaterialColors()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(iconContainerColor, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
            }
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = materialColors.primaryLabel
                )
                if (description != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = materialColors.secondaryLabel
                    )
                }
            }
            if (trailingContent != null) {
                Spacer(modifier = Modifier.size(8.dp))
                trailingContent()
            } else if (showNavigationIndicator) {
                Spacer(modifier = Modifier.size(8.dp))
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = materialColors.tertiaryLabel
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = if (leadingIcon == null) 16.dp else 58.dp),
                color = materialColors.separator
            )
        }
    }
}

@Composable
private fun ToolCallingModeItem(
    mode: ToolCallingMode,
    selectedMode: ToolCallingMode,
    title: String,
    description: String,
    showDivider: Boolean = true,
    onSelected: (ToolCallingMode) -> Unit
) {
    SettingsRow(
        title = title,
        description = description,
        onClick = { onSelected(mode) },
        showNavigationIndicator = false,
        showDivider = showDivider,
        trailingContent = {
            SettingsSelectionCheckmark(selected = selectedMode == mode)
        }
    )
}

@Composable
private fun WebSearchModeItem(
    mode: WebSearchMode,
    selectedMode: WebSearchMode,
    title: String,
    description: String,
    onSelected: (WebSearchMode) -> Unit
) {
    SettingsRow(
        title = title,
        description = description,
        onClick = { onSelected(mode) },
        showNavigationIndicator = false,
        trailingContent = {
            SettingsSelectionCheckmark(selected = selectedMode == mode)
        }
    )
}

@Composable
private fun WebSearchBaseUrlItem(
    baseUrl: String,
    hasError: Boolean,
    onBaseUrlChange: (String) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.searxng_base_url)) },
            placeholder = { Text(stringResource(R.string.searxng_base_url_hint)) },
            singleLine = true,
            isError = hasError,
            shape = RoundedCornerShape(10.dp),
            colors = settingsTextFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = {
                Text(
                    text = if (hasError) {
                        stringResource(R.string.searxng_base_url_invalid)
                    } else {
                        stringResource(R.string.searxng_base_url_description)
                    }
                )
            }
        )
    }
}

@Composable
private fun SettingsSelectionCheckmark(selected: Boolean) {
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = AppleBlue
            )
        }
    }
}

@Composable
private fun PlatformItem(
    platform: PlatformV2,
    showDivider: Boolean,
    onItemClick: () -> Unit
) {
    val statusText = if (platform.enabled) stringResource(R.string.enabled) else stringResource(R.string.disabled)
    SettingsRow(
        title = platform.name,
        description = "${getClientTypeDisplayName(platform.compatibleType)} · $statusText",
        leadingIcon = Icons.Rounded.Cloud,
        iconContainerColor = if (platform.enabled) AppleGreen else AppleGray,
        showDivider = showDivider,
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
        leadingIcon = Icons.Rounded.Memory,
        iconContainerColor = ApplePurple,
        showNavigationIndicator = false,
        trailingContent = {
            Switch(
                checked = memoryEnabled,
                onCheckedChange = onCheckedChange,
                colors = settingsSwitchColors()
            )
        }
    )
}

@Composable
fun MemoryMaintenanceNotificationsItem(
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsRow(
        title = stringResource(R.string.memory_maintenance_notifications_title),
        description = stringResource(R.string.memory_maintenance_notifications_description),
        onClick = { onCheckedChange(!enabled) },
        leadingIcon = Icons.Rounded.Notifications,
        iconContainerColor = AppleRed,
        showNavigationIndicator = false,
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = onCheckedChange,
                colors = settingsSwitchColors()
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
