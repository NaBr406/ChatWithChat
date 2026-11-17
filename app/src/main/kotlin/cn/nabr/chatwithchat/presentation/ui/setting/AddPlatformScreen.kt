package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ApiProtocol
import cn.nabr.chatwithchat.data.model.ToolCallStrategy
import cn.nabr.chatwithchat.presentation.common.ProviderConnectionFields
import cn.nabr.chatwithchat.presentation.common.ProviderFieldShape
import cn.nabr.chatwithchat.presentation.common.SettingsFieldTitle
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
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
    var selectedProtocol by remember { mutableStateOf(ApiProtocol.OPENAI_CHAT_COMPLETIONS) }
    var toolCallStrategy by remember { mutableStateOf(ToolCallStrategy.AUTO) }
    var apiUrl by remember { mutableStateOf(ApiProtocol.OPENAI_CHAT_COMPLETIONS.defaultApiUrl) }
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

            ProviderConnectionFields(
                protocol = selectedProtocol,
                onProtocolChange = { protocol ->
                    if (apiUrl == selectedProtocol.defaultApiUrl) apiUrl = protocol.defaultApiUrl
                    selectedProtocol = protocol
                },
                name = platformName,
                onNameChange = { platformName = it },
                apiUrl = apiUrl,
                onApiUrlChange = { apiUrl = it },
                isFullApiUrl = isFullApiUrl,
                onFullApiUrlChange = { isFullApiUrl = it },
                customHeaders = customHeaders,
                onCustomHeadersChange = { customHeaders = it },
                toolCallStrategy = toolCallStrategy,
                onToolCallStrategyChange = { toolCallStrategy = it },
                enabled = !isBusy && !platformSaved,
                credentialField = {
                    Column {
                        SettingsFieldTitle(title = stringResource(R.string.api_key))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            placeholder = { Text(stringResource(R.string.api_key_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBusy && !platformSaved,
                            singleLine = true,
                            shape = ProviderFieldShape,
                            colors = settingsTextFieldColors(),
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                }
            )

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
                            selectedProtocol = selectedProtocol,
                            toolCallStrategy = toolCallStrategy,
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
                        selectedProtocol = selectedProtocol,
                        toolCallStrategy = toolCallStrategy,
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
                        selectedProtocol = selectedProtocol,
                        toolCallStrategy = toolCallStrategy,
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
    selectedProtocol: ApiProtocol,
    toolCallStrategy: ToolCallStrategy,
    apiKey: String,
    enabled: Boolean,
    onSave: (PlatformV2) -> Unit,
    onCancel: () -> Unit
) {
    Button(
        onClick = {
            val platform = PlatformV2(
                name = platformName.trim(),
                compatibleType = selectedProtocol.legacyClientType,
                apiProtocol = selectedProtocol,
                toolCallStrategy = toolCallStrategy,
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
