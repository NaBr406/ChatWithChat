package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.mcp.MCP_TOOL_LIST_TRUNCATED
import cn.nabr.chatwithchat.data.mcp.MCP_TOOL_NAME_COLLISION
import cn.nabr.chatwithchat.data.mcp.McpServerCatalogEntry
import cn.nabr.chatwithchat.data.mcp.McpToolAnnotations
import cn.nabr.chatwithchat.data.mcp.McpToolDescriptor
import cn.nabr.chatwithchat.data.mcp.McpTransport
import cn.nabr.chatwithchat.presentation.common.AppleRed
import cn.nabr.chatwithchat.presentation.common.HigActionDialog
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.settingsSwitchColors

/**
 * MCP server management: one card per server, expanding to its tools.
 *
 * The screen leans on two ideas that come from the trust model rather than from layout taste. Server
 * annotations are shown but explicitly labelled as unverified server claims, because they carry no weight
 * in the security policy and a user should not read them as guarantees. And tools the app refused are
 * listed with their reason instead of being hidden, so a missing capability is explainable rather than
 * looking like a bug.
 */
@Composable
fun McpServersScreen(
    viewModel: McpServersViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val draftProbe by viewModel.draftProbe.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteServer by remember { mutableStateOf<McpServerCatalogEntry?>(null) }

    val messageText = message?.let { current -> mcpMessageText(current) }
    LaunchedEffect(messageText) {
        messageText?.let { text ->
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    McpServersContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onNavigationClick = onNavigationClick,
        onAddClick = { showAddDialog = true },
        onToggleExpanded = viewModel::toggleExpanded,
        onServerEnabledChange = viewModel::setServerEnabled,
        onRefreshClick = viewModel::refresh,
        onDeleteClick = { entry -> pendingDeleteServer = entry },
        onToolEnabledChange = viewModel::setToolEnabled
    )

    if (showAddDialog) {
        McpAddServerDialog(
            isBusy = state.isAddingServer,
            probeText = draftProbe?.let { probe -> mcpMessageText(probe) },
            onProbe = viewModel::probeDraft,
            onDraftEdited = viewModel::clearDraftProbe,
            onConfirm = { displayName, url, token, transport, allowPrivate ->
                viewModel.addServer(displayName, url, token, transport, allowPrivate)
                showAddDialog = false
            },
            onDismiss = {
                viewModel.clearDraftProbe()
                showAddDialog = false
            }
        )
    }

    pendingDeleteServer?.let { entry ->
        HigActionDialog(
            title = stringResource(R.string.mcp_delete_confirm_title),
            message = stringResource(R.string.mcp_delete_confirmation, entry.server.displayName),
            primaryActionLabel = stringResource(R.string.delete),
            onPrimaryAction = {
                viewModel.deleteServer(entry.server.serverId)
                pendingDeleteServer = null
            },
            onDismissRequest = { pendingDeleteServer = null },
            secondaryActionLabel = stringResource(R.string.cancel),
            onSecondaryAction = { pendingDeleteServer = null },
            primaryActionColor = AppleRed
        )
    }
}

/** Stateless body, split out so the list, status lines, and rejection reasons can be tested directly. */
@Composable
internal fun McpServersContent(
    state: McpServersUiState,
    snackbarHostState: SnackbarHostState,
    onNavigationClick: () -> Unit,
    onAddClick: () -> Unit,
    onToggleExpanded: (String) -> Unit,
    onServerEnabledChange: (String, Boolean) -> Unit,
    onRefreshClick: (String) -> Unit,
    onDeleteClick: (McpServerCatalogEntry) -> Unit,
    onToolEnabledChange: (String, Boolean) -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.mcp_servers),
                onNavigationClick = onNavigationClick,
                actions = {
                    IconButton(
                        enabled = !state.isAddingServer,
                        onClick = onAddClick
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.mcp_add_server)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "trust-notice") {
                McpTrustNotice()
            }
            if (state.catalog.isEmpty()) {
                item(key = "empty") {
                    McpEmptyState(onAddClick = onAddClick)
                }
            } else {
                items(state.catalog, key = { entry -> entry.server.serverId }) { entry ->
                    McpServerCard(
                        entry = entry,
                        isExpanded = entry.server.serverId in state.expandedServerIds,
                        isBusy = entry.server.serverId in state.busyServerIds,
                        isToolEnabled = state::isToolEnabled,
                        onToggleExpanded = { onToggleExpanded(entry.server.serverId) },
                        onEnabledChange = { enabled ->
                            onServerEnabledChange(entry.server.serverId, enabled)
                        },
                        onRefreshClick = { onRefreshClick(entry.server.serverId) },
                        onDeleteClick = { onDeleteClick(entry) },
                        onToolEnabledChange = onToolEnabledChange
                    )
                }
            }
        }
    }
}

@Composable
private fun McpTrustNotice() {
    val materialColors = settingsMaterialColors()
    SettingsMaterialGroup {
        Text(
            modifier = Modifier.padding(16.dp),
            text = stringResource(R.string.mcp_trust_notice),
            style = MaterialTheme.typography.bodySmall,
            color = materialColors.secondaryLabel
        )
    }
}

@Composable
private fun McpEmptyState(onAddClick: () -> Unit) {
    val materialColors = settingsMaterialColors()
    SettingsMaterialGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAddClick)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.mcp_empty_title),
                style = MaterialTheme.typography.bodyLarge,
                color = materialColors.primaryLabel
            )
            Text(
                text = stringResource(R.string.mcp_empty_description),
                style = MaterialTheme.typography.bodySmall,
                color = materialColors.secondaryLabel
            )
        }
    }
}

@Composable
private fun McpServerCard(
    entry: McpServerCatalogEntry,
    isExpanded: Boolean,
    isBusy: Boolean,
    isToolEnabled: (McpToolDescriptor) -> Boolean,
    onToggleExpanded: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRefreshClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onToolEnabledChange: (String, Boolean) -> Unit
) {
    val materialColors = settingsMaterialColors()
    val server = entry.server
    SettingsMaterialGroup {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = materialColors.primaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = server.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = materialColors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entry.statusLine(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (server.lastSyncError == null) {
                            materialColors.secondaryLabel
                        } else {
                            AppleRed
                        },
                        maxLines = 2
                    )
                }
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefreshClick) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.mcp_refresh_tools),
                            tint = materialColors.secondaryLabel
                        )
                    }
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onEnabledChange,
                    colors = settingsSwitchColors()
                )
            }

            if (isExpanded) {
                HorizontalDivider(color = materialColors.separator)
                entry.usableTools.forEach { tool ->
                    McpToolRow(
                        tool = tool,
                        isEnabled = isToolEnabled(tool),
                        isServerEnabled = server.enabled,
                        onEnabledChange = { enabled -> onToolEnabledChange(tool.namespacedName, enabled) }
                    )
                }
                if (entry.usableTools.isEmpty()) {
                    Text(
                        modifier = Modifier.padding(16.dp),
                        text = stringResource(R.string.mcp_no_tools),
                        style = MaterialTheme.typography.bodySmall,
                        color = materialColors.secondaryLabel
                    )
                }
                entry.rejectedTools.takeIf { rejected -> rejected.isNotEmpty() }?.let { rejected ->
                    HorizontalDivider(color = materialColors.separator)
                    Text(
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                        text = stringResource(R.string.mcp_incompatible_tools, rejected.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = materialColors.secondaryLabel
                    )
                    rejected.forEach { tool ->
                        Text(
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 4.dp),
                            text = "${tool.toolName} — ${rejectionText(tool.rejectionCode)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = materialColors.tertiaryLabel
                        )
                    }
                }
                HorizontalDivider(color = materialColors.separator)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDeleteClick)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = AppleRed
                    )
                    Text(
                        text = stringResource(R.string.mcp_delete_server),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppleRed
                    )
                }
            }
        }
    }
}

@Composable
private fun McpToolRow(
    tool: McpToolDescriptor,
    isEnabled: Boolean,
    isServerEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val materialColors = settingsMaterialColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tool.annotations.title?.takeIf { title -> title.isNotBlank() } ?: tool.toolName,
                style = MaterialTheme.typography.bodyMedium,
                color = materialColors.primaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = tool.description.ifBlank { tool.toolName },
                style = MaterialTheme.typography.bodySmall,
                color = materialColors.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            tool.annotations.claimSummary()?.let { claim ->
                Text(
                    text = stringResource(R.string.mcp_server_claims, claim),
                    style = MaterialTheme.typography.bodySmall,
                    color = materialColors.tertiaryLabel,
                    maxLines = 1
                )
            }
        }
        Switch(
            checked = isEnabled,
            enabled = isServerEnabled,
            onCheckedChange = onEnabledChange,
            colors = settingsSwitchColors()
        )
    }
}

@Composable
private fun McpAddServerDialog(
    isBusy: Boolean,
    probeText: String?,
    onProbe: (String, String, String, McpTransport, Boolean) -> Unit,
    onDraftEdited: () -> Unit,
    onConfirm: (String, String, String, McpTransport, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var displayName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(McpTransport.STREAMABLE_HTTP) }
    var allowPrivateNetwork by remember { mutableStateOf(false) }
    val materialColors = settingsMaterialColors()

    HigActionDialog(
        title = stringResource(R.string.mcp_add_server),
        message = stringResource(R.string.mcp_add_server_description),
        primaryActionLabel = stringResource(R.string.save),
        onPrimaryAction = { onConfirm(displayName, url, token, transport, allowPrivateNetwork) },
        onDismissRequest = onDismiss,
        secondaryActionLabel = stringResource(R.string.cancel),
        onSecondaryAction = onDismiss,
        isPrimaryActionEnabled = url.isNotBlank() && !isBusy
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = displayName,
                onValueChange = { value -> displayName = value },
                label = { Text(stringResource(R.string.mcp_field_name)) },
                singleLine = true
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = url,
                onValueChange = { value ->
                    url = value
                    onDraftEdited()
                },
                label = { Text(stringResource(R.string.mcp_field_url)) },
                placeholder = { Text("https://example.com/mcp") },
                singleLine = true
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = token,
                onValueChange = { value ->
                    token = value
                    onDraftEdited()
                },
                label = { Text(stringResource(R.string.mcp_field_token)) },
                singleLine = true
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        transport = when (transport) {
                            McpTransport.STREAMABLE_HTTP -> McpTransport.SSE
                            McpTransport.SSE -> McpTransport.STREAMABLE_HTTP
                        }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mcp_field_transport),
                        style = MaterialTheme.typography.bodyMedium,
                        color = materialColors.primaryLabel
                    )
                    Text(
                        text = transport.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = materialColors.secondaryLabel
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mcp_field_allow_private),
                        style = MaterialTheme.typography.bodyMedium,
                        color = materialColors.primaryLabel
                    )
                    Text(
                        text = stringResource(R.string.mcp_field_allow_private_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = materialColors.secondaryLabel
                    )
                }
                Switch(
                    checked = allowPrivateNetwork,
                    onCheckedChange = { value -> allowPrivateNetwork = value },
                    colors = settingsSwitchColors()
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = url.isNotBlank() && !isBusy) {
                        onProbe(displayName, url, token, transport, allowPrivateNetwork)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = stringResource(R.string.mcp_test_connection),
                    style = MaterialTheme.typography.bodyMedium,
                    color = materialColors.primaryLabel
                )
            }
            probeText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = materialColors.secondaryLabel
                )
            }
        }
    }
}

@Composable
private fun McpTransport.label(): String = when (this) {
    McpTransport.STREAMABLE_HTTP -> stringResource(R.string.mcp_transport_streamable_http)
    McpTransport.SSE -> stringResource(R.string.mcp_transport_sse)
}

@Composable
private fun McpServerCatalogEntry.statusLine(): String {
    val error = server.lastSyncError
    return when {
        error == MCP_TOOL_LIST_TRUNCATED ->
            stringResource(R.string.mcp_status_truncated, usableTools.size)
        error != null -> stringResource(R.string.mcp_status_error, rejectionText(error))
        server.lastSyncedAt == null -> stringResource(R.string.mcp_status_never_synced)
        else -> stringResource(R.string.mcp_status_synced, usableTools.size, rejectedTools.size)
    }
}

/** Server-declared hints, worded so they read as claims rather than as guarantees. */
@Composable
private fun McpToolAnnotations.claimSummary(): String? = listOfNotNull(
    stringResource(R.string.mcp_claim_read_only).takeIf { readOnlyHint == true },
    stringResource(R.string.mcp_claim_destructive).takeIf { destructiveHint == true },
    stringResource(R.string.mcp_claim_open_world).takeIf { openWorldHint == true }
).takeIf { claims -> claims.isNotEmpty() }?.joinToString(separator = " · ")

@Composable
private fun rejectionText(code: String?): String = when (code) {
    null -> ""
    MCP_TOOL_NAME_COLLISION -> stringResource(R.string.mcp_reason_name_collision)
    MCP_TOOL_LIST_TRUNCATED -> stringResource(R.string.mcp_reason_truncated)
    "schema_unsupported_composition" -> stringResource(R.string.mcp_reason_unsupported_composition)
    "schema_reference_unsupported" -> stringResource(R.string.mcp_reason_schema_reference)
    "schema_unsupported_type" -> stringResource(R.string.mcp_reason_unsupported_type)
    "schema_tuple_items_unsupported" -> stringResource(R.string.mcp_reason_tuple_items)
    "schema_array_items_missing" -> stringResource(R.string.mcp_reason_array_items_missing)
    "schema_non_string_enum" -> stringResource(R.string.mcp_reason_non_string_enum)
    "schema_const_unsupported" -> stringResource(R.string.mcp_reason_const)
    "schema_too_deep", "schema_too_large" -> stringResource(R.string.mcp_reason_schema_too_large)
    "schema_required_property_missing" -> stringResource(R.string.mcp_reason_required_missing)
    "schema_property_name_invalid" -> stringResource(R.string.mcp_reason_property_name)
    "mcp_invalid_url", "mcp_invalid_url_scheme" -> stringResource(R.string.mcp_reason_invalid_url)
    "mcp_url_credentials_rejected" -> stringResource(R.string.mcp_reason_url_credentials)
    "mcp_private_address_rejected" -> stringResource(R.string.mcp_reason_private_address)
    "mcp_connect_failed" -> stringResource(R.string.mcp_reason_connect_failed)
    else -> stringResource(R.string.mcp_reason_request_failed)
}

@Composable
private fun mcpMessageText(message: McpUiMessage): String = when (message) {
    is McpUiMessage.ServerAdded -> stringResource(R.string.mcp_message_server_added, message.displayName)
    McpUiMessage.ServerDeleted -> stringResource(R.string.mcp_message_server_deleted)
    is McpUiMessage.Synced -> if (message.truncated) {
        stringResource(R.string.mcp_message_synced_truncated, message.usableToolCount)
    } else {
        stringResource(
            R.string.mcp_message_synced,
            message.usableToolCount,
            message.rejectedToolCount
        )
    }
    is McpUiMessage.Failed -> rejectionText(message.reasonCode)
    is McpUiMessage.UrlRejected -> rejectionText(message.rejection.code)
    is McpUiMessage.ProbeSucceeded -> if (message.advertisesTools) {
        stringResource(R.string.mcp_message_probe_ok, message.serverName)
    } else {
        stringResource(R.string.mcp_message_probe_no_tools, message.serverName)
    }
}
