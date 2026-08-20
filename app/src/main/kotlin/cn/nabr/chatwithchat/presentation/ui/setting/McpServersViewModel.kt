package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import cn.nabr.chatwithchat.data.mcp.McpProbeResult
import cn.nabr.chatwithchat.data.mcp.McpServerCatalogEntry
import cn.nabr.chatwithchat.data.mcp.McpServerConfig
import cn.nabr.chatwithchat.data.mcp.McpServerRepository
import cn.nabr.chatwithchat.data.mcp.McpServerUrlPolicy
import cn.nabr.chatwithchat.data.mcp.McpConnectionManager
import cn.nabr.chatwithchat.data.mcp.McpSyncResult
import cn.nabr.chatwithchat.data.mcp.McpToolDescriptor
import cn.nabr.chatwithchat.data.mcp.McpToolRegistrar
import cn.nabr.chatwithchat.data.mcp.McpToolSync
import cn.nabr.chatwithchat.data.mcp.McpTransport
import cn.nabr.chatwithchat.data.mcp.mcpServerScopePrefix
import cn.nabr.chatwithchat.data.mcp.mcpToolScopeKey
import cn.nabr.chatwithchat.data.mcp.McpUrlRejection
import cn.nabr.chatwithchat.data.repository.SettingRepository
import cn.nabr.chatwithchat.data.tool.ToolApprovalSessionGrants
import cn.nabr.chatwithchat.data.tool.ToolEnablementOverrides
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Outcomes the screen turns into localized text.
 *
 * Reason codes travel as codes rather than sentences so nothing server-supplied lands in the UI
 * verbatim, and so the strings stay in `strings.xml` where they can be translated.
 */
sealed interface McpUiMessage {
    data class ServerAdded(val displayName: String) : McpUiMessage
    data object ServerDeleted : McpUiMessage
    data class Synced(val usableToolCount: Int, val rejectedToolCount: Int, val truncated: Boolean) : McpUiMessage
    data class Failed(val reasonCode: String) : McpUiMessage
    data class UrlRejected(val rejection: McpUrlRejection) : McpUiMessage
    data class ProbeSucceeded(val serverName: String, val advertisesTools: Boolean) : McpUiMessage
}

data class McpServersUiState(
    val catalog: List<McpServerCatalogEntry> = emptyList(),
    val overrides: ToolEnablementOverrides = ToolEnablementOverrides(),
    val busyServerIds: Set<String> = emptySet(),
    val expandedServerIds: Set<String> = emptySet(),
    val isAddingServer: Boolean = false
) {
    fun isToolEnabled(tool: McpToolDescriptor): Boolean =
        tool.namespacedName !in overrides.disabledToolNames &&
            tool.namespacedName in overrides.enabledToolNames
}

@HiltViewModel
class McpServersViewModel @Inject constructor(
    private val repository: McpServerRepository,
    private val settingRepository: SettingRepository,
    private val connectionManager: McpConnectionManager,
    private val toolSync: McpToolSync,
    private val registrar: McpToolRegistrar,
    private val urlPolicy: McpServerUrlPolicy,
    private val sessionGrants: ToolApprovalSessionGrants
) : ViewModel() {

    private val _uiState = MutableStateFlow(McpServersUiState())
    val uiState: StateFlow<McpServersUiState> = _uiState.asStateFlow()

    private val _message = MutableStateFlow<McpUiMessage?>(null)
    val message: StateFlow<McpUiMessage?> = _message.asStateFlow()

    /**
     * Connection-test outcome, kept separate from [message] because the add-server dialog is modal: a
     * snackbar hosted by the screen behind it would never be seen.
     */
    private val _draftProbe = MutableStateFlow<McpUiMessage?>(null)
    val draftProbe: StateFlow<McpUiMessage?> = _draftProbe.asStateFlow()

    private val overridesMutex = Mutex()

    init {
        viewModelScope.launch {
            refreshOverrides()
            repository.observeCatalog().collect { catalog ->
                _uiState.update { state -> state.copy(catalog = catalog) }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Clears the last connection-test result, so an edited URL cannot keep showing a stale verdict. */
    fun clearDraftProbe() {
        _draftProbe.value = null
    }

    fun toggleExpanded(serverId: String) {
        _uiState.update { state ->
            val expanded = state.expandedServerIds
            state.copy(
                expandedServerIds = if (serverId in expanded) expanded - serverId else expanded + serverId
            )
        }
    }

    /**
     * Checks a URL and credentials against a live server before anything is persisted, so a typo surfaces
     * here rather than as a mysteriously empty tool list later.
     */
    fun probeDraft(
        displayName: String,
        url: String,
        authToken: String,
        transport: McpTransport,
        allowPrivateNetwork: Boolean
    ) {
        urlPolicy.validateOrNull(url, allowPrivateNetwork)?.let { rejection ->
            _draftProbe.value = McpUiMessage.UrlRejected(rejection)
            return
        }
        _draftProbe.value = null
        _uiState.update { state -> state.copy(isAddingServer = true) }
        viewModelScope.launch {
            val draft = McpServerConfig(
                serverId = DRAFT_SERVER_ID,
                alias = DRAFT_SERVER_ID,
                displayName = displayName.ifBlank { url },
                url = url.trim(),
                transport = transport,
                authToken = authToken.takeIf { token -> token.isNotBlank() },
                allowPrivateNetwork = allowPrivateNetwork
            )
            _draftProbe.value = when (val probe = connectionManager.probe(draft)) {
                is McpProbeResult.Reachable ->
                    McpUiMessage.ProbeSucceeded(probe.serverName, probe.advertisesTools)
                is McpProbeResult.Unreachable -> McpUiMessage.Failed(probe.reasonCode)
            }
            // The draft used a throwaway id; nothing should keep its session alive.
            connectionManager.disconnect(DRAFT_SERVER_ID)
            _uiState.update { state -> state.copy(isAddingServer = false) }
        }
    }

    fun addServer(
        displayName: String,
        url: String,
        authToken: String,
        transport: McpTransport,
        allowPrivateNetwork: Boolean
    ) {
        urlPolicy.validateOrNull(url, allowPrivateNetwork)?.let { rejection ->
            _message.value = McpUiMessage.UrlRejected(rejection)
            return
        }
        _uiState.update { state -> state.copy(isAddingServer = true) }
        viewModelScope.launch {
            val created = repository.createServer(
                displayName = displayName,
                url = url,
                transport = transport,
                authToken = authToken.takeIf { token -> token.isNotBlank() },
                allowPrivateNetwork = allowPrivateNetwork
            )
            _uiState.update { state -> state.copy(isAddingServer = false) }
            _draftProbe.value = null
            _message.value = McpUiMessage.ServerAdded(created.displayName)
            // Sync immediately so the tool list is populated even though the server starts disabled.
            refresh(created.serverId)
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            connectionManager.disconnect(serverId)
            // Drop any "allow for this conversation" grants with the server itself.
            sessionGrants.revokeByPrefix(mcpServerScopePrefix(serverId))
            repository.deleteServer(serverId)
            registrar.refreshInstalledTools()
            _message.value = McpUiMessage.ServerDeleted
        }
    }

    fun setServerEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setServerEnabled(serverId, enabled)
            if (!enabled) {
                connectionManager.disconnect(serverId)
                // Switching a server off must also withdraw consent, or turning it back on would silently
                // resume skipping confirmations the user granted in an earlier conversation.
                sessionGrants.revokeByPrefix(mcpServerScopePrefix(serverId))
            }
            // Disabling a server drops its tools from the catalog entirely, which is what keeps a
            // switched-off server unreachable even if a model forges one of its tool names.
            registrar.refreshInstalledTools()
        }
    }

    fun refresh(serverId: String) {
        viewModelScope.launch {
            val config = repository.loadServer(serverId) ?: return@launch
            _uiState.update { state -> state.copy(busyServerIds = state.busyServerIds + serverId) }
            _message.value = when (val result = toolSync.sync(config)) {
                is McpSyncResult.Synced -> McpUiMessage.Synced(
                    usableToolCount = result.usableToolCount,
                    rejectedToolCount = result.rejectedToolCount,
                    truncated = result.truncated
                )
                is McpSyncResult.Failed -> McpUiMessage.Failed(result.reasonCode)
            }
            _uiState.update { state -> state.copy(busyServerIds = state.busyServerIds - serverId) }
        }
    }

    fun setToolEnabled(namespacedName: String, enabled: Boolean) {
        viewModelScope.launch {
            overridesMutex.withLock {
                settingRepository.updateToolEnabled(namespacedName, enabled)
                if (!enabled) {
                    // Same reasoning as disabling a server, narrowed to the one tool.
                    _uiState.value.catalog
                        .flatMap { entry -> entry.tools }
                        .firstOrNull { tool -> tool.namespacedName == namespacedName }
                        ?.let { tool -> sessionGrants.revokeByPrefix(mcpToolScopeKey(tool.serverId, tool.toolName)) }
                }
                refreshOverrides()
            }
        }
    }

    private suspend fun refreshOverrides() {
        val overrides = settingRepository.fetchToolEnablementOverrides()
        _uiState.update { state -> state.copy(overrides = overrides) }
    }

    private companion object {
        /** Never persisted; only used to key the throwaway session of a connection test. */
        private const val DRAFT_SERVER_ID = "mcp-draft"
    }
}
