package cn.nabr.chatwithchat.presentation.ui.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.visual.VisualAssetDiagnostic
import cn.nabr.chatwithchat.presentation.common.SettingItem
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground

@Composable
fun VisualAssetDebugScreen(
    onNavigationClick: () -> Unit,
    viewModel: VisualAssetDebugViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<VisualAssetDiagnostic?>(null) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.developer_debug_visual_assets),
                onNavigationClick = onNavigationClick
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    item {
                        SettingsMaterialGroup {
                            SettingItem(
                                title = stringResource(R.string.developer_debug_refresh_visual_assets),
                                description = uiState.errorCode,
                                onItemClick = viewModel::refresh,
                                showTrailingIcon = true,
                                showLeadingIcon = true,
                                leadingIcon = {
                                    Icon(Icons.Outlined.Image, contentDescription = null)
                                }
                            )
                        }
                    }
                    items(uiState.assets, key = { asset -> "${asset.chatId}:${asset.visualRef}" }) { asset ->
                        SettingsMaterialGroup(modifier = Modifier.padding(top = 12.dp)) {
                            SettingItem(
                                title = "${asset.visualRef} · chat=${asset.chatId}",
                                description = asset.summaryText(),
                                onItemClick = { selected = asset },
                                showTrailingIcon = true,
                                showLeadingIcon = false,
                                showDivider = false
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { asset ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(asset.visualRef) },
            text = { Text(asset.detailText()) },
            confirmButton = {
                TextButton(onClick = { selected = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

private fun VisualAssetDiagnostic.summaryText(): String = buildString {
    append("folded=")
    append(foldedAt != null)
    append(" · profile=")
    append(compactedProfile ?: "ORIGINAL")
    append(" · original=")
    append(originalExists)
    append(" · compacted=")
    append(compactedExists)
    append(" · providerRefs=")
    append(providerRefCount)
}

private fun VisualAssetDiagnostic.detailText(): String = buildString {
    appendLine("hash=$assetHash")
    appendLine("message=$messageId attachment=$attachmentIndex turn=$turnOrdinal role=$role")
    appendLine("foldedAt=${foldedAt ?: "null"}")
    appendLine("profile=${compactedProfile ?: "ORIGINAL"}")
    appendLine("original=$originalPath exists=$originalExists")
    appendLine("originalSize=$originalSizeBytes dimensions=${originalWidth ?: "?"}x${originalHeight ?: "?"}")
    appendLine("compacted=${compactedPath ?: "null"} exists=$compactedExists")
    appendLine("compactedSize=${compactedSizeBytes ?: "null"} dimensions=${compactedWidth ?: "?"}x${compactedHeight ?: "?"}")
    appendLine("compactionError=${compactionError ?: "null"}")
    append("providerRefs=$providerRefCount")
}
