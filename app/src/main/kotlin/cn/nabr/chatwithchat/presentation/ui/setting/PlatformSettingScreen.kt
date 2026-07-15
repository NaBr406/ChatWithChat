package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.common.SettingItem
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.settingsSwitchColors
import cn.nabr.chatwithchat.util.formatPlatformTimeout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingScreen(
    modifier: Modifier = Modifier,
    settingViewModel: PlatformSettingViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    val platform by settingViewModel.platformState.collectAsStateWithLifecycle()
    val dialogState by settingViewModel.dialogState.collectAsStateWithLifecycle()
    val isDeleted by settingViewModel.isDeleted.collectAsStateWithLifecycle()

    LaunchedEffect(isDeleted) {
        if (isDeleted) {
            onNavigationClick()
        }
    }

    platform?.let { platformData ->
        val materialColors = settingsMaterialColors()
        Scaffold(
            modifier = modifier,
            topBar = {
                PlatformTopAppBar(
                    title = platformData.name,
                    onNavigationClick = onNavigationClick,
                    onDeleteClick = settingViewModel::openDeleteDialog
                )
            },
            containerColor = materialColors.canvas
        ) { innerPadding ->
            Column(
                Modifier
                    .padding(innerPadding)
                    .verticalScroll(scrollState)
            ) {
                PreferenceSwitchWithContainer(
                    title = stringResource(R.string.enable_api),
                    isChecked = platformData.enabled
                ) { settingViewModel.toggleEnabled() }
                SettingsMaterialGroup(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.platform_name),
                        description = platformData.name,
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openPlatformNameDialog,
                        showTrailingIcon = true,
                        showLeadingIcon = true,
                        showDivider = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Label,
                                contentDescription = stringResource(R.string.platform_name_icon)
                            )
                        }
                    )
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.api_url),
                        description = platformData.apiUrl,
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openApiUrlDialog,
                        showTrailingIcon = true,
                        showLeadingIcon = true,
                        showDivider = true,
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_link),
                                contentDescription = stringResource(R.string.url_icon)
                            )
                        }
                    )
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.api_key),
                        description = if (platformData.token.isNullOrEmpty()) {
                            stringResource(R.string.token_not_set)
                        } else {
                            stringResource(R.string.token_set, platformData.token[0])
                        },
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openApiTokenDialog,
                        showTrailingIcon = true,
                        showLeadingIcon = true,
                        showDivider = true,
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_key),
                                contentDescription = stringResource(R.string.key_icon)
                            )
                        }
                    )
                    val notSetText = stringResource(R.string.not_set)
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.temperature),
                        description = platformData.temperature?.toString() ?: notSetText,
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openTemperatureDialog,
                        showTrailingIcon = true,
                        showLeadingIcon = true,
                        showDivider = true,
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_temperature),
                                contentDescription = stringResource(R.string.temperature_icon)
                            )
                        }
                    )
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.top_p),
                        description = platformData.topP?.toString() ?: notSetText,
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openTopPDialog,
                        showTrailingIcon = true,
                        showLeadingIcon = true,
                        showDivider = true,
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_chart),
                                contentDescription = stringResource(R.string.top_p_icon)
                            )
                        }
                    )
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.system_prompt),
                        description = platformData.systemPrompt,
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openSystemPromptDialog,
                        showTrailingIcon = true,
                        showLeadingIcon = true,
                        showDivider = true,
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_instructions),
                                contentDescription = stringResource(R.string.system_prompt_icon)
                            )
                        }
                    )
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.timeout),
                        description = formatPlatformTimeout(platformData.timeout, stringResource(R.string.off)),
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openTimeoutDialog,
                        showTrailingIcon = true,
                        showLeadingIcon = true,
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_info),
                                contentDescription = stringResource(R.string.timeout_icon)
                            )
                        }
                    )
                }

                PlatformNameDialog(dialogState, platformData.name, settingViewModel)
                APIUrlDialog(dialogState, platformData.apiUrl, settingViewModel)
                APIKeyDialog(dialogState, settingViewModel)
                TemperatureDialog(dialogState, platformData.temperature, settingViewModel)
                TopPDialog(dialogState, platformData.topP, settingViewModel)
                SystemPromptDialog(dialogState, platformData.systemPrompt ?: "", settingViewModel)
                TimeoutDialog(dialogState, platformData.timeout, settingViewModel)
                DeletePlatformDialog(dialogState, settingViewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformTopAppBar(
    title: String,
    onNavigationClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    SettingsTopAppBar(
        title = title,
        onNavigationClick = onNavigationClick,
        actions = {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.more_options)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete_platform)) },
                    onClick = {
                        showMenu = false
                        onDeleteClick()
                    }
                )
            }
        }
    )
}

@Composable
fun PreferenceSwitchWithContainer(
    title: String,
    icon: ImageVector? = null,
    isChecked: Boolean,
    onClick: () -> Unit
) {
    val thumbContent: (@Composable () -> Unit)? = remember(isChecked) {
        if (isChecked) {
            {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        } else {
            null
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val materialColors = settingsMaterialColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(materialColors.grouped)
            .border(0.5.dp, materialColors.separator, RoundedCornerShape(12.dp))
            .toggleable(
                value = isChecked,
                onValueChange = { onClick() },
                interactionSource = interactionSource,
                indication = LocalIndication.current
            )
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp, end = 16.dp)
                    .size(24.dp),
                tint = materialColors.secondaryLabel
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (icon == null) 12.dp else 0.dp, end = 12.dp)
        ) {
            Text(
                text = title,
                maxLines = 1,
                style = MaterialTheme.typography.titleMedium,
                color = materialColors.primaryLabel
            )
        }
        Switch(
            checked = isChecked,
            interactionSource = interactionSource,
            onCheckedChange = null,
            modifier = Modifier.padding(start = 12.dp, end = 6.dp),
            thumbContent = thumbContent,
            colors = settingsSwitchColors()
        )
    }
}
