package cn.nabr.chatwithchat.presentation.ui.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.common.SettingItem
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground

@Composable
fun DeveloperDebugScreen(
    onNavigationClick: () -> Unit,
    onNavigateToPromptTrace: () -> Unit,
    onNavigateToMemoryMaintenanceFailures: () -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.developer_debug_title),
                onNavigationClick = onNavigationClick
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SettingsMaterialGroup {
                SettingItem(
                    title = stringResource(R.string.developer_debug_prompt_trace),
                    onItemClick = onNavigateToPromptTrace,
                    showTrailingIcon = true,
                    showLeadingIcon = true,
                    showDivider = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Code,
                            contentDescription = null
                        )
                    }
                )
                SettingItem(
                    title = stringResource(R.string.developer_debug_memory_failures),
                    description = stringResource(R.string.developer_debug_memory_failures_description),
                    onItemClick = onNavigateToMemoryMaintenanceFailures,
                    showTrailingIcon = true,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.DataObject,
                            contentDescription = null
                        )
                    }
                )
            }
        }
    }
}
