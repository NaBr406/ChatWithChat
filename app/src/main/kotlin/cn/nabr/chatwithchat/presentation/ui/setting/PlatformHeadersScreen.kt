package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground

private const val CODEX_HEADERS = "User-Agent: codex-cli"
private const val CLAUDE_CODE_HEADERS = "User-Agent: claude-code"
private const val HERMES_HEADERS = "User-Agent: hermes"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformHeadersScreen(
    settingViewModel: PlatformSettingViewModel,
    onNavigationClick: () -> Unit
) {
    val platform by settingViewModel.platformState.collectAsStateWithLifecycle()
    var headers by remember { mutableStateOf("") }
    var showPresetMenu by remember { mutableStateOf(false) }

    LaunchedEffect(platform?.uid) {
        headers = platform?.customHeaders.orEmpty()
    }

    Scaffold(
        modifier = Modifier.settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.simulated_request_headers),
                onNavigationClick = onNavigationClick,
                actions = {
                    IconButton(onClick = { showPresetMenu = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.header_presets)
                        )
                    }
                    DropdownMenu(
                        expanded = showPresetMenu,
                        onDismissRequest = { showPresetMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.header_preset_codex)) },
                            onClick = {
                                headers = CODEX_HEADERS
                                showPresetMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.header_preset_claude_code)) },
                            onClick = {
                                headers = CLAUDE_CODE_HEADERS
                                showPresetMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.header_preset_hermes)) },
                            onClick = {
                                headers = HERMES_HEADERS
                                showPresetMenu = false
                            }
                        )
                    }
                    IconButton(
                        onClick = {
                            settingViewModel.updateCustomHeaders(headers)
                            onNavigationClick()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = stringResource(R.string.save)
                        )
                    }
                }
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        OutlinedTextField(
            value = headers,
            onValueChange = { headers = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            label = { Text(stringResource(R.string.simulated_request_headers)) },
            placeholder = { Text(stringResource(R.string.simulated_request_headers_hint)) },
            supportingText = { Text(stringResource(R.string.simulated_request_headers_supporting)) },
            minLines = 8
        )
    }
}