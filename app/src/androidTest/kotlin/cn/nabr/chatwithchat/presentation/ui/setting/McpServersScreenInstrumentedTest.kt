package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.mcp.MCP_TOOL_NAME_COLLISION
import cn.nabr.chatwithchat.data.mcp.McpServerCatalogEntry
import cn.nabr.chatwithchat.data.mcp.McpServerConfig
import cn.nabr.chatwithchat.data.mcp.McpToolAnnotations
import cn.nabr.chatwithchat.data.mcp.McpToolDescriptor
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpServersScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun emptyCatalog_showsTrustNoticeAndEmptyState() {
        render(McpServersUiState())

        composeRule.onNodeWithText(context.getString(R.string.mcp_empty_title)).assertIsDisplayed()
        // The trust notice is the one piece of copy that must never be optional.
        composeRule.onNodeWithText(context.getString(R.string.mcp_trust_notice)).assertIsDisplayed()
    }

    @Test
    fun collapsedServer_showsNameUrlAndSyncSummaryWithoutTools() {
        render(McpServersUiState(catalog = listOf(catalogEntry())))

        composeRule.onNodeWithText("Acme MCP").assertIsDisplayed()
        composeRule.onNodeWithText("https://mcp.example.invalid/mcp").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.mcp_status_synced, 1, 1)).assertIsDisplayed()
        // Tools stay hidden until the card is expanded.
        composeRule.onAllNodesWithText("read_file").assertCountEquals(0)
    }

    @Test
    fun expandedServer_listsUsableToolsAndExplainsRejectedOnes() {
        render(
            McpServersUiState(
                catalog = listOf(catalogEntry()),
                expandedServerIds = setOf(SERVER_ID)
            )
        )

        composeRule.onNodeWithText("read_file").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.mcp_incompatible_tools, 1)).assertIsDisplayed()
        // A refused tool is named with its reason rather than silently disappearing.
        composeRule
            .onNodeWithText("clashing_tool — ${context.getString(R.string.mcp_reason_name_collision)}")
            .assertIsDisplayed()
    }

    @Test
    fun serverClaims_areLabelledAsUnverified() {
        render(
            McpServersUiState(
                catalog = listOf(catalogEntry()),
                expandedServerIds = setOf(SERVER_ID)
            )
        )

        val claim = context.getString(
            R.string.mcp_server_claims,
            context.getString(R.string.mcp_claim_read_only)
        )
        composeRule.onNodeWithText(claim).assertIsDisplayed()
    }

    @Test
    fun toolSwitch_isDisabledWhileTheServerIsOff() {
        render(
            McpServersUiState(
                catalog = listOf(catalogEntry(serverEnabled = false)),
                expandedServerIds = setOf(SERVER_ID)
            )
        )

        // A tool cannot be armed on a server that is switched off, so the control says so.
        composeRule.onNodeWithText("read_file").assertIsDisplayed()
        // Index 0 is the server switch (still usable, to turn the server on); index 1 is the tool switch.
        composeRule.onAllNodes(isToggleable())[1].assertIsNotEnabled()
    }

    @Test
    fun neverSyncedServer_saysSoInsteadOfShowingZeroTools() {
        render(McpServersUiState(catalog = listOf(catalogEntry(lastSyncedAt = null, tools = emptyList()))))

        composeRule.onNodeWithText(context.getString(R.string.mcp_status_never_synced)).assertIsDisplayed()
    }

    @Test
    fun syncError_isRenderedAsALocalizedReasonNotARawCode() {
        render(
            McpServersUiState(
                catalog = listOf(catalogEntry(lastSyncError = "mcp_connect_failed"))
            )
        )

        val expected = context.getString(
            R.string.mcp_status_error,
            context.getString(R.string.mcp_reason_connect_failed)
        )
        composeRule.onNodeWithText(expected).assertIsDisplayed()
        composeRule.onAllNodesWithText("mcp_connect_failed").assertCountEquals(0)
    }

    @Test
    fun tappingACard_reportsTheServerIdToExpand() {
        var expandedId: String? = null
        render(
            state = McpServersUiState(catalog = listOf(catalogEntry())),
            onToggleExpanded = { serverId -> expandedId = serverId }
        )

        composeRule.onNodeWithText("Acme MCP").performClick()

        assertEquals(SERVER_ID, expandedId)
    }

    private fun render(
        state: McpServersUiState,
        onToggleExpanded: (String) -> Unit = {}
    ) {
        composeRule.setContent {
            ChatWithChatTheme {
                McpServersContent(
                    state = state,
                    snackbarHostState = SnackbarHostState(),
                    onNavigationClick = {},
                    onAddClick = {},
                    onToggleExpanded = onToggleExpanded,
                    onServerEnabledChange = { _, _ -> },
                    onRefreshClick = {},
                    onDeleteClick = {},
                    onToolEnabledChange = { _, _ -> }
                )
            }
        }
    }

    private fun catalogEntry(
        serverEnabled: Boolean = true,
        lastSyncedAt: Long? = 1_000L,
        lastSyncError: String? = null,
        tools: List<McpToolDescriptor> = listOf(usableTool(), rejectedTool())
    ) = McpServerCatalogEntry(
        server = McpServerConfig(
            serverId = SERVER_ID,
            alias = "acme",
            displayName = "Acme MCP",
            url = "https://mcp.example.invalid/mcp",
            enabled = serverEnabled,
            lastSyncedAt = lastSyncedAt,
            lastSyncError = lastSyncError
        ),
        tools = tools
    )

    private fun usableTool() = McpToolDescriptor(
        serverId = SERVER_ID,
        toolName = "read_file",
        namespacedName = "mcp_acme_read_file",
        description = "Reads a file from the workspace.",
        inputSchemaJson = """{"type":"object","properties":{}}""",
        annotations = McpToolAnnotations(readOnlyHint = true)
    )

    private fun rejectedTool() = McpToolDescriptor(
        serverId = SERVER_ID,
        toolName = "clashing_tool",
        namespacedName = "mcp_acme_read_file",
        description = "Collides with another tool name.",
        inputSchemaJson = """{"type":"object","properties":{}}""",
        rejectionCode = MCP_TOOL_NAME_COLLISION
    )

    private companion object {
        private const val SERVER_ID = "server-1"
    }
}
