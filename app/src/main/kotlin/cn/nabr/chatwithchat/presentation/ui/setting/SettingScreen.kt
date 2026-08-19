package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.tool.ResolvedToolCatalogEntry
import cn.nabr.chatwithchat.data.tool.ToolCallingMode
import cn.nabr.chatwithchat.data.tool.ToolCatalogEntry
import cn.nabr.chatwithchat.data.websearch.WebSearchBackend
import cn.nabr.chatwithchat.data.websearch.WebSearchMode
import cn.nabr.chatwithchat.presentation.common.AppleRed
import cn.nabr.chatwithchat.presentation.common.HigActionDialog
import cn.nabr.chatwithchat.presentation.common.LocalNotificationPermissionRequester
import cn.nabr.chatwithchat.presentation.common.LocalToolPermissionRequester
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.settingsSwitchColors
import cn.nabr.chatwithchat.presentation.common.settingsTextFieldColors
import cn.nabr.chatwithchat.util.getClientTypeDisplayName

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
    onNavigateToStickerLibrary: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToAboutPage: () -> Unit
) {
    val platformState by settingViewModel.platformState.collectAsStateWithLifecycle()
    val dialogState by settingViewModel.dialogState.collectAsStateWithLifecycle()
    val memoryEnabled by settingViewModel.memoryEnabled.collectAsStateWithLifecycle()
    val memoryMaintenanceNotificationsEnabled by settingViewModel.memoryMaintenanceNotificationsEnabled.collectAsStateWithLifecycle()
    val notificationPermissionRequester = LocalNotificationPermissionRequester.current
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current

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
        modifier = modifier
            .fillMaxSize()
            .settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.settings),
                onNavigationClick = onNavigationClick
            )
        },
        containerColor = Color.Transparent
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
                    leadingIcon = Icons.Outlined.Add,
                    onClick = onNavigateToAddPlatform
                )
                SettingsRow(
                    title = stringResource(R.string.model_management),
                    leadingIcon = Icons.Outlined.Tune,
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
                    leadingIcon = Icons.Outlined.Memory,
                    onClick = onNavigateToMemory
                )
                SettingsRow(
                    title = stringResource(R.string.sticker_library),
                    leadingIcon = Icons.Outlined.EmojiEmotions,
                    onClick = onNavigateToStickerLibrary
                )
                SettingsRow(
                    title = stringResource(R.string.skills),
                    description = stringResource(R.string.skills_description),
                    leadingIcon = Icons.Outlined.Extension,
                    onClick = onNavigateToSkills
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_app)) {
                SettingsRow(
                    title = stringResource(R.string.tool_settings_title),
                    leadingIcon = Icons.Outlined.Extension,
                    onClick = onNavigateToToolSettings
                )
                SettingsRow(
                    title = stringResource(R.string.about),
                    leadingIcon = Icons.Outlined.Info,
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
    val toolPermissionRequester = LocalToolPermissionRequester.current
    var pendingPermissionTool by remember { mutableStateOf<ResolvedToolCatalogEntry?>(null) }
    var deniedPermissionToolNames by remember { mutableStateOf(emptySet<String>()) }

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
        modifier = modifier
            .fillMaxSize()
            .settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.tool_settings_title),
                onNavigationClick = onNavigationClick
            )
        },
        containerColor = Color.Transparent
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
                    onSelected = settingViewModel::updateToolCallingMode
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_callable_tools)) {
                webSearchSettings.tools.forEach { resolvedEntry ->
                    val entry = resolvedEntry.catalogEntry
                    val toolName = entry.definition.name
                    val description = if (toolName in deniedPermissionToolNames && !resolvedEntry.isEnabled) {
                        stringResource(R.string.tool_permission_denied_settings_description)
                    } else {
                        entry.settingsDescription()
                    }
                    ToolEnabledItem(
                        toolName = toolName,
                        title = entry.settingsTitle(),
                        description = description,
                        icon = entry.settingsIcon(),
                        enabled = resolvedEntry.isEnabled,
                        onEnabledChange = { _, enabled ->
                            when {
                                !enabled -> settingViewModel.updateToolEnabled(toolName, false)
                                entry.permissionRequirements.isEmpty() -> settingViewModel.updateToolEnabled(toolName, true)
                                else -> pendingPermissionTool = resolvedEntry
                            }
                        }
                    )
                }
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
            }

            SettingsSection(title = stringResource(R.string.settings_section_web_search_backend)) {
                // 后端配好了但上面还是「关闭」的话，搜索工具根本不会上桌，而且一路上没有任何反馈。
                // 把这个状态摆在用户正在看的地方，别让它静默失败。
                if (webSearchSettings.mode == WebSearchMode.Off) {
                    WebSearchBackendInactiveNotice()
                }
                WebSearchBackendItem(
                    backend = WebSearchBackend.Searxng,
                    selectedBackend = webSearchSettings.backend,
                    title = stringResource(R.string.web_search_backend_searxng),
                    description = stringResource(R.string.web_search_backend_searxng_description),
                    onSelected = settingViewModel::updateWebSearchBackend
                )
                WebSearchBackendItem(
                    backend = WebSearchBackend.AnySearch,
                    selectedBackend = webSearchSettings.backend,
                    title = stringResource(R.string.web_search_backend_anysearch),
                    description = stringResource(R.string.web_search_backend_anysearch_description),
                    onSelected = settingViewModel::updateWebSearchBackend
                )
                when (webSearchSettings.backend) {
                    WebSearchBackend.Searxng -> WebSearchBaseUrlItem(
                        baseUrl = webSearchSettings.searxngBaseUrl,
                        hasError = webSearchSettings.searxngBaseUrlError,
                        onBaseUrlChange = settingViewModel::updateWebSearchSearxngBaseUrl
                    )
                    WebSearchBackend.AnySearch -> AnySearchApiKeyItem(
                        apiKey = webSearchSettings.anySearchApiKey,
                        onApiKeyChange = settingViewModel::updateWebSearchAnySearchApiKey
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    pendingPermissionTool?.let { resolvedEntry ->
        val entry = resolvedEntry.catalogEntry
        val title = entry.settingsTitle()
        HigActionDialog(
            title = stringResource(R.string.tool_permission_rationale_title, title),
            message = stringResource(R.string.tool_permission_rationale_description, title),
            primaryActionLabel = stringResource(R.string.grant_permission),
            onPrimaryAction = {
                pendingPermissionTool = null
                toolPermissionRequester.requestToolPermissions(entry.definition.name) { granted ->
                    if (granted) {
                        deniedPermissionToolNames -= entry.definition.name
                        settingViewModel.updateToolEnabled(entry.definition.name, true)
                    } else {
                        deniedPermissionToolNames += entry.definition.name
                    }
                }
            },
            onDismissRequest = { pendingPermissionTool = null },
            secondaryActionLabel = stringResource(R.string.cancel),
            onSecondaryAction = { pendingPermissionTool = null }
        )
    }
}

@Composable
private fun ToolCatalogEntry.settingsTitle(): String = when (settings.presentationKey) {
    "web_search" -> stringResource(R.string.tool_web_search_title)
    "fetch_url" -> stringResource(R.string.tool_fetch_url_title)
    "current_datetime" -> stringResource(R.string.tool_current_datetime_title)
    "device_location" -> stringResource(R.string.tool_device_location_title)
    "add_schedule" -> stringResource(R.string.tool_add_schedule_title)
    "set_alarm" -> stringResource(R.string.tool_set_alarm_title)
    else -> definition.name.replace('_', ' ').ifBlank { stringResource(R.string.tool_generic_title) }
}

@Composable
private fun ToolCatalogEntry.settingsDescription(): String = when (settings.presentationKey) {
    "web_search" -> stringResource(R.string.tool_web_search_description)
    "fetch_url" -> stringResource(R.string.tool_fetch_url_description)
    "current_datetime" -> stringResource(R.string.tool_current_datetime_description)
    "device_location" -> stringResource(R.string.tool_device_location_description)
    "add_schedule" -> stringResource(R.string.tool_add_schedule_description)
    "set_alarm" -> stringResource(R.string.tool_set_alarm_description)
    else -> definition.description.ifBlank { stringResource(R.string.tool_generic_description) }
}

private fun ToolCatalogEntry.settingsIcon(): ImageVector = when (settings.iconKey) {
    "search" -> Icons.Outlined.Search
    "language" -> Icons.Outlined.Language
    "schedule" -> Icons.Outlined.Schedule
    "location" -> Icons.Outlined.LocationOn
    "event" -> Icons.Outlined.Event
    "alarm" -> Icons.Outlined.Alarm
    else -> Icons.Outlined.Extension
}

/**
 * A titled run of settings cards. Each row inside gets its own card instead of the group sharing one
 * card with hairline dividers, which is what keeps the list from reading as a dense iOS table.
 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val materialColors = settingsMaterialColors()
    Text(
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, end = 16.dp, bottom = 16.dp),
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = materialColors.secondaryLabel
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
private fun SettingsRow(
    title: String,
    description: String? = null,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
    toggleValue: Boolean? = null,
    descriptionMaxLines: Int = 1,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val materialColors = settingsMaterialColors()
    val interactionModifier = if (toggleValue == null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.toggleable(
            value = toggleValue,
            role = Role.Switch,
            onValueChange = { onClick() }
        )
    }
    SettingsMaterialGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(interactionModifier)
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = materialColors.secondaryLabel
                    )
                }
                Spacer(modifier = Modifier.size(16.dp))
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
                        maxLines = descriptionMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = materialColors.secondaryLabel
                    )
                }
            }
            if (trailingContent != null) {
                Spacer(modifier = Modifier.size(8.dp))
                trailingContent()
            }
        }
    }
}

@Composable
private fun ToolCallingModeItem(
    mode: ToolCallingMode,
    selectedMode: ToolCallingMode,
    title: String,
    description: String,
    onSelected: (ToolCallingMode) -> Unit
) {
    SettingsRow(
        title = title,
        description = description,
        onClick = { onSelected(mode) },
        trailingContent = {
            SettingsSelectionCheckmark(selected = selectedMode == mode)
        }
    )
}

@Composable
private fun ToolEnabledItem(
    toolName: String,
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onEnabledChange: (String, Boolean) -> Unit
) {
    SettingsRow(
        title = title,
        description = description,
        onClick = { onEnabledChange(toolName, !enabled) },
        leadingIcon = icon,
        toggleValue = enabled,
        descriptionMaxLines = 2,
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = null,
                colors = settingsSwitchColors()
            )
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
        trailingContent = {
            SettingsSelectionCheckmark(selected = selectedMode == mode)
        }
    )
}

@Composable
private fun WebSearchBackendItem(
    backend: WebSearchBackend,
    selectedBackend: WebSearchBackend,
    title: String,
    description: String,
    onSelected: (WebSearchBackend) -> Unit
) {
    SettingsRow(
        title = title,
        description = description,
        onClick = { onSelected(backend) },
        descriptionMaxLines = 2,
        trailingContent = {
            SettingsSelectionCheckmark(selected = selectedBackend == backend)
        }
    )
}

@Composable
private fun WebSearchBackendInactiveNotice() {
    SettingsMaterialGroup {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            text = stringResource(R.string.web_search_backend_inactive_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun WebSearchBaseUrlItem(
    baseUrl: String,
    hasError: Boolean,
    onBaseUrlChange: (String) -> Unit
) {
    SettingsMaterialGroup {
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
}

@Composable
private fun AnySearchApiKeyItem(
    apiKey: String,
    onApiKeyChange: (String) -> Unit
) {
    SettingsMaterialGroup {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.anysearch_api_key)) },
                placeholder = { Text(stringResource(R.string.anysearch_api_key_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = settingsTextFieldColors(),
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    Text(stringResource(R.string.anysearch_api_key_description))
                }
            )
        }
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
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = settingsMaterialColors().primaryLabel
            )
        }
    }
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
        leadingIcon = Icons.Outlined.Cloud,
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
        leadingIcon = Icons.Outlined.Memory,
        descriptionMaxLines = 2,
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
        onClick = { onCheckedChange(!enabled) },
        leadingIcon = Icons.Outlined.Notifications,
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
    HigActionDialog(
        title = stringResource(R.string.delete_platform),
        message = stringResource(R.string.delete_platform_confirmation),
        primaryActionLabel = stringResource(R.string.delete),
        primaryActionColor = AppleRed,
        onPrimaryAction = settingViewModel::confirmDelete,
        onDismissRequest = settingViewModel::closeDeleteDialog,
        secondaryActionLabel = stringResource(R.string.cancel),
        onSecondaryAction = settingViewModel::closeDeleteDialog
    )
}
