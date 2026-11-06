package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.ModelConstants
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.presentation.common.FullApiUrlCheckbox
import cn.nabr.chatwithchat.presentation.common.SettingsHelpTooltipIcon
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
import cn.nabr.chatwithchat.presentation.common.settingsDropdownMenuItemColors
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.settingsTextFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlatformScreen(
    modifier: Modifier = Modifier,
    settingViewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit
) {
    var platformName by remember { mutableStateOf("") }
    var selectedClientType by remember { mutableStateOf(ClientType.OPENAI) }
    var clientTypeExpanded by remember { mutableStateOf(false) }
    var apiUrl by remember { mutableStateOf("") }
    var isFullApiUrl by remember { mutableStateOf(false) }
    var customHeaders by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    val saveState by settingViewModel.addPlatformSaveState.collectAsStateWithLifecycle()
    val isBusy = saveState is SettingViewModelV2.AddPlatformSaveState.Saving ||
        saveState is SettingViewModelV2.AddPlatformSaveState.RefreshingModels
    val platformSaved = saveState is SettingViewModelV2.AddPlatformSaveState.Success ||
        (saveState as? SettingViewModelV2.AddPlatformSaveState.Error)?.platformSaved == true
    val materialColors = settingsMaterialColors()

    fun closeScreen() {
        settingViewModel.clearAddPlatformSaveState()
        onNavigationClick()
    }

    Scaffold(
        modifier = modifier.settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.add_platform),
                onNavigationClick = ::closeScreen,
                navigationEnabled = !isBusy
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            SettingsMaterialGroup {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Titles sit above the fields rather than floating inside them, so a help icon can
                    // stand beside the title of the field it explains.
                    AddPlatformFieldTitle(title = stringResource(R.string.platform_name))
                    OutlinedTextField(
                        value = platformName,
                        onValueChange = { platformName = it },
                        placeholder = { Text(stringResource(R.string.platform_name_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy && !platformSaved,
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = settingsTextFieldColors()
                    )

                    Spacer(modifier = Modifier.height(ADD_PLATFORM_FIELD_SPACING))

                    AddPlatformFieldTitle(title = stringResource(R.string.api_type))
                    ExposedDropdownMenuBox(
                        expanded = clientTypeExpanded,
                        onExpandedChange = { if (!isBusy && !platformSaved) clientTypeExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = getClientTypeName(selectedClientType),
                            onValueChange = {},
                            readOnly = true,
                            enabled = !isBusy && !platformSaved,
                            shape = RoundedCornerShape(10.dp),
                            colors = settingsTextFieldColors(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientTypeExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = clientTypeExpanded,
                            onDismissRequest = { clientTypeExpanded = false },
                            shape = RoundedCornerShape(12.dp),
                            containerColor = materialColors.canvas,
                            tonalElevation = 0.dp,
                            shadowElevation = 3.dp,
                            border = BorderStroke(0.5.dp, materialColors.separator)
                        ) {
                            ClientType.entries.forEach { clientType ->
                                val isSelected = clientType == selectedClientType
                                DropdownMenuItem(
                                    colors = settingsDropdownMenuItemColors(),
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .background(
                                            color = if (isSelected) settingsMaterialColors().primaryLabel.copy(alpha = 0.08f) else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        ),
                                    text = {
                                        Text(
                                            text = getClientTypeName(clientType),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = materialColors.primaryLabel
                                        )
                                    },
                                    trailingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = null,
                                                tint = settingsMaterialColors().primaryLabel
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        selectedClientType = clientType
                                        isFullApiUrl = false
                                        // Set default API URL based on client type
                                        apiUrl = when (clientType) {
                                            ClientType.OPENAI -> ModelConstants.OPENAI_API_URL
                                            ClientType.GLM -> ModelConstants.GLM_API_URL
                                            ClientType.ANTHROPIC -> ModelConstants.ANTHROPIC_API_URL
                                            ClientType.GOOGLE -> ModelConstants.GOOGLE_API_URL
                                            ClientType.GROQ -> ModelConstants.GROQ_API_URL
                                            ClientType.OLLAMA -> ModelConstants.OLLAMA_API_URL
                                            ClientType.OPENROUTER -> ModelConstants.OPENROUTER_API_URL
                                            ClientType.CUSTOM -> ""
                                        }
                                        clientTypeExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(ADD_PLATFORM_FIELD_SPACING))

                    AddPlatformFieldTitle(title = stringResource(R.string.api_url))
                    OutlinedTextField(
                        value = apiUrl,
                        onValueChange = { apiUrl = it },
                        placeholder = { Text(stringResource(R.string.api_url_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy && !platformSaved,
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = settingsTextFieldColors()
                    )

                    FullApiUrlCheckbox(
                        checked = isFullApiUrl,
                        onCheckedChange = { isFullApiUrl = it },
                        enabled = !isBusy && !platformSaved,
                        helpInTooltip = true
                    )

                    Spacer(modifier = Modifier.height(ADD_PLATFORM_FIELD_SPACING - 4.dp))

                    AddPlatformFieldTitle(
                        title = stringResource(R.string.simulated_request_headers),
                        helpText = stringResource(R.string.simulated_request_headers_supporting)
                    )
                    OutlinedTextField(
                        value = customHeaders,
                        onValueChange = { customHeaders = it },
                        placeholder = { Text(stringResource(R.string.simulated_request_headers_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy && !platformSaved,
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(10.dp),
                        colors = settingsTextFieldColors()
                    )

                    Spacer(modifier = Modifier.height(ADD_PLATFORM_FIELD_SPACING))

                    AddPlatformFieldTitle(title = stringResource(R.string.api_key))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        placeholder = { Text(stringResource(R.string.api_key_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy && !platformSaved,
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = settingsTextFieldColors(),
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            AddPlatformSaveStatus(saveState)

            if (saveState !is SettingViewModelV2.AddPlatformSaveState.Idle) {
                Spacer(modifier = Modifier.height(16.dp))
            }

            when (val state = saveState) {
                is SettingViewModelV2.AddPlatformSaveState.Success -> {
                    Button(
                        onClick = ::closeScreen,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = settingsMaterialColors().primaryLabel,
                            contentColor = settingsMaterialColors().canvas
                        )
                    ) {
                        Text(stringResource(R.string.done))
                    }
                }

                is SettingViewModelV2.AddPlatformSaveState.Error -> {
                    if (state.platformSaved) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = settingViewModel::retryAddedPlatformModelRefresh,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                            Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                            Button(
                                onClick = ::closeScreen,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = settingsMaterialColors().primaryLabel,
                                    contentColor = settingsMaterialColors().canvas
                                )
                            ) {
                                Text(stringResource(R.string.done))
                            }
                        }
                    } else {
                        AddPlatformActionButtons(
                            platformName = platformName,
                            apiUrl = apiUrl,
                            isFullApiUrl = isFullApiUrl,
                            customHeaders = customHeaders,
                            selectedClientType = selectedClientType,
                            apiKey = apiKey,
                            enabled = !isBusy,
                            onSave = settingViewModel::addPlatformAndRefreshModels,
                            onCancel = ::closeScreen
                        )
                    }
                }

                SettingViewModelV2.AddPlatformSaveState.Saving,
                SettingViewModelV2.AddPlatformSaveState.RefreshingModels -> {
                    AddPlatformActionButtons(
                        platformName = platformName,
                        apiUrl = apiUrl,
                        isFullApiUrl = isFullApiUrl,
                        customHeaders = customHeaders,
                        selectedClientType = selectedClientType,
                        apiKey = apiKey,
                        enabled = false,
                        onSave = settingViewModel::addPlatformAndRefreshModels,
                        onCancel = ::closeScreen
                    )
                }

                SettingViewModelV2.AddPlatformSaveState.Idle -> {
                    AddPlatformActionButtons(
                        platformName = platformName,
                        apiUrl = apiUrl,
                        isFullApiUrl = isFullApiUrl,
                        customHeaders = customHeaders,
                        selectedClientType = selectedClientType,
                        apiKey = apiKey,
                        enabled = true,
                        onSave = settingViewModel::addPlatformAndRefreshModels,
                        onCancel = ::closeScreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AddPlatformActionButtons(
    platformName: String,
    apiUrl: String,
    isFullApiUrl: Boolean,
    customHeaders: String,
    selectedClientType: ClientType,
    apiKey: String,
    enabled: Boolean,
    onSave: (PlatformV2) -> Unit,
    onCancel: () -> Unit
) {
    Button(
        onClick = {
            val platform = PlatformV2(
                name = platformName.trim(),
                compatibleType = selectedClientType,
                enabled = true,
                apiUrl = apiUrl.trim(),
                isFullApiUrl = isFullApiUrl,
                customHeaders = customHeaders.trim(),
                token = apiKey.trim().takeIf { it.isNotEmpty() },
                model = "",
                temperature = 1.0f,
                topP = 1.0f,
                systemPrompt = null,
                stream = true,
                reasoning = false,
                timeout = 30
            )
            onSave(platform)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled && platformName.isNotBlank() && apiUrl.isNotBlank(),
        colors = ButtonDefaults.buttonColors(
            containerColor = settingsMaterialColors().primaryLabel,
            contentColor = settingsMaterialColors().canvas
        )
    ) {
        Text(stringResource(R.string.save))
    }

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        Text(stringResource(R.string.cancel))
    }
}

@Composable
private fun AddPlatformSaveStatus(saveState: SettingViewModelV2.AddPlatformSaveState) {
    when (saveState) {
        SettingViewModelV2.AddPlatformSaveState.Idle -> Unit
        SettingViewModelV2.AddPlatformSaveState.Saving -> SavingStatusText(
            text = stringResource(R.string.platform_save_saving)
        )

        SettingViewModelV2.AddPlatformSaveState.RefreshingModels -> SavingStatusText(
            text = stringResource(R.string.platform_save_refreshing_models)
        )

        is SettingViewModelV2.AddPlatformSaveState.Success -> Text(
            text = stringResource(R.string.platform_save_refresh_success, saveState.modelCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )

        is SettingViewModelV2.AddPlatformSaveState.Error -> Text(
            text = if (saveState.platformSaved) {
                stringResource(R.string.platform_save_refresh_failed, saveState.message)
            } else {
                stringResource(R.string.platform_save_failed, saveState.message)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun SavingStatusText(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(end = 12.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun getClientTypeName(clientType: ClientType): String = when (clientType) {
    ClientType.OPENAI -> "OpenAI"
    ClientType.GLM -> "GLM"
    ClientType.ANTHROPIC -> "Anthropic"
    ClientType.GOOGLE -> "Google"
    ClientType.GROQ -> "Groq"
    ClientType.OLLAMA -> "Ollama"
    ClientType.OPENROUTER -> "OpenRouter"
    ClientType.CUSTOM -> stringResource(R.string.custom)
}

@Composable
private fun AddPlatformFieldTitle(
    title: String,
    helpText: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(start = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = settingsMaterialColors().secondaryLabel
        )
        if (helpText != null) {
            SettingsHelpTooltipIcon(helpText = helpText)
        }
    }
}

private val ADD_PLATFORM_FIELD_SPACING = 16.dp
