package cn.nabr.chatwithchat.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Conversation-scoped approval grants.
 *
 * The property that matters is not "fewer dialogs" but that a grant narrows to exactly one capability in
 * exactly one conversation, and that the per-call token binding survives untouched.
 */
class ToolApprovalSessionGrantTest {

    @Test
    fun grantedTool_isApprovedWithoutPrompting() = runBlocking {
        val harness = harness()
        harness.grants.grant(CHAT_ID, SCOPE_READ)

        val decision = harness.broker.awaitApproval(call(READ_TOOL), CHAT_ID)

        assertTrue(decision is ToolApprovalBroker.Decision.WithContext)
        assertNull("no dialog should have been raised", harness.broker.pending.value)
    }

    @Test
    fun grantForOneTool_doesNotCoverAnotherToolOnTheSameServer() = runBlocking {
        val harness = harness()
        harness.grants.grant(CHAT_ID, SCOPE_READ)

        // This is the whole reason the scope key names the tool: approving a lookup must not authorize a write.
        assertTrue(harness.grants.isGranted(CHAT_ID, SCOPE_READ))
        assertFalse(harness.grants.isGranted(CHAT_ID, SCOPE_WRITE))
    }

    @Test
    fun grantIsScopedToOneConversation() {
        val grants = ToolApprovalSessionGrants()
        grants.grant(CHAT_ID, SCOPE_READ)

        assertTrue(grants.isGranted(CHAT_ID, SCOPE_READ))
        assertFalse(grants.isGranted(OTHER_CHAT_ID, SCOPE_READ))
    }

    @Test
    fun grantIsIgnoredWithoutAConversationId() {
        val grants = ToolApprovalSessionGrants()

        // A chat row that does not exist yet must not share a bucket with every other unsaved chat.
        grants.grant(0, SCOPE_READ)
        assertFalse(grants.isGranted(0, SCOPE_READ))
    }

    @Test
    fun ungrantedTool_stillRaisesTheDialog() = runBlocking {
        val harness = harness()

        val job = launch { harness.broker.awaitApproval(call(READ_TOOL), CHAT_ID) }
        val pending = harness.broker.awaitPending()

        assertNotNull("an ungranted call must prompt", pending)
        assertTrue("the dialog should offer a session grant", pending.canGrantForSession)
        harness.broker.deny(pending.requestId)
        job.join()
    }

    @Test
    fun approvingForSession_recordsTheGrantSoTheNextCallIsSilent() = runBlocking {
        val harness = harness()

        val job = launch { harness.broker.awaitApproval(call(READ_TOOL), CHAT_ID) }
        harness.broker.approveForSession(harness.broker.awaitPending().requestId)
        job.join()

        assertTrue(harness.grants.isGranted(CHAT_ID, SCOPE_READ))
        // A second, differently-argued call to the same tool now resolves without a dialog.
        val second = harness.broker.awaitApproval(
            ToolCall(id = "call_2", name = READ_TOOL, arguments = """{"path":"/other.txt"}"""),
            CHAT_ID
        )
        assertTrue(second is ToolApprovalBroker.Decision.WithContext)
        assertNull(harness.broker.pending.value)
    }

    @Test
    fun aGrantedCallStillGetsATokenBoundToItsOwnArguments() = runBlocking {
        val harness = harness()
        harness.grants.grant(CHAT_ID, SCOPE_READ)

        val granted = harness.broker.awaitApproval(call(READ_TOOL), CHAT_ID)
        val context = (granted as ToolApprovalBroker.Decision.WithContext).context

        // The grant skips the prompt, never the binding: the minted context must not match a different call.
        assertTrue(context.matchesOperation(call(READ_TOOL)))
        assertFalse(
            context.matchesOperation(
                ToolCall(id = "call_1", name = READ_TOOL, arguments = """{"path":"/etc/shadow"}""")
            )
        )
    }

    @Test
    fun executorAcceptsAGrantedContextAndRefusesAnUnapprovedOne() = runBlocking {
        val harness = harness()
        harness.grants.grant(CHAT_ID, SCOPE_READ)
        val call = call(READ_TOOL)

        val granted = harness.broker.awaitApproval(call, CHAT_ID)
        val approvedResult = harness.executor.execute(
            call = call,
            activeToolNames = setOf(READ_TOOL),
            executionContext = (granted as ToolApprovalBroker.Decision.WithContext).context
        )
        assertFalse(approvedResult.content, approvedResult.isError)

        val unapproved = harness.executor.execute(call, activeToolNames = setOf(READ_TOOL))
        assertEquals("tool_approval_required", unapproved.metadata["error_code"])
    }

    @Test
    fun toolWithoutAScope_neverOffersOrHonoursAGrant() = runBlocking {
        val harness = harness()
        // NO_SCOPE_TOOL opts out, so even a matching key in the store must not authorize it.
        harness.grants.grant(CHAT_ID, "mcp:server-1:no_scope")

        assertNull(harness.coordinator.sessionApprovalScope(NO_SCOPE_TOOL))
        assertTrue(harness.coordinator.approveFromSessionGrant(call(NO_SCOPE_TOOL)).isFailure)
    }

    @Test
    fun revokeByPrefix_dropsAWholeServerAcrossConversations() {
        val grants = ToolApprovalSessionGrants()
        grants.grant(CHAT_ID, SCOPE_READ)
        grants.grant(OTHER_CHAT_ID, SCOPE_WRITE)
        grants.grant(CHAT_ID, "mcp:server-2:read_file")

        grants.revokeByPrefix("mcp:server-1:")

        assertFalse(grants.isGranted(CHAT_ID, SCOPE_READ))
        assertFalse(grants.isGranted(OTHER_CHAT_ID, SCOPE_WRITE))
        assertTrue("another server must be untouched", grants.isGranted(CHAT_ID, "mcp:server-2:read_file"))
    }

    @Test
    fun revokeChat_dropsOnlyThatConversation() {
        val grants = ToolApprovalSessionGrants()
        grants.grant(CHAT_ID, SCOPE_READ)
        grants.grant(OTHER_CHAT_ID, SCOPE_READ)

        grants.revokeChat(CHAT_ID)

        assertFalse(grants.isGranted(CHAT_ID, SCOPE_READ))
        assertTrue(grants.isGranted(OTHER_CHAT_ID, SCOPE_READ))
    }

    /** The broker publishes the pending approval before suspending, so yielding is enough to observe it. */
    private suspend fun ToolApprovalBroker.awaitPending(): ToolApprovalBroker.PendingApproval {
        repeat(MAX_YIELDS) {
            pending.value?.let { current -> return current }
            yield()
        }
        error("no pending approval was published")
    }

    private class Harness(
        val grants: ToolApprovalSessionGrants,
        val coordinator: ToolApprovalCoordinator,
        val broker: ToolApprovalBroker,
        val executor: ToolExecutor
    )

    private fun harness(): Harness {
        val registry = ToolRegistry(
            listOf(
                scopedProvider(READ_TOOL, SCOPE_READ),
                scopedProvider(WRITE_TOOL, SCOPE_WRITE),
                scopedProvider(NO_SCOPE_TOOL, null)
            )
        )
        val authority = ToolApprovalAuthority()
        val contextFactory = ToolExecutionContextFactory()
        val coordinator = ToolApprovalCoordinator(registry, authority, contextFactory)
        val grants = ToolApprovalSessionGrants()
        return Harness(
            grants = grants,
            coordinator = coordinator,
            broker = ToolApprovalBroker(coordinator, grants),
            executor = ToolExecutor(
                toolRegistry = registry,
                approvalAuthority = authority,
                executionContextFactory = contextFactory
            )
        )
    }

    private fun scopedProvider(name: String, scope: String?): ToolProvider = object : ToolProvider {
        override val definition = ToolDefinition(
            name = name,
            description = "Scoped tool.",
            parameters = ToolDefinition.Parameters(
                properties = mapOf("path" to ToolDefinition.Parameter(type = "string"))
            )
        )
        override val securityPolicy = ToolSecurityPolicy(
            ToolEffect.EXTERNAL_WRITE,
            ToolApprovalPolicy.REQUIRE_EACH_CALL
        )
        override val sessionApprovalScope = scope

        override fun approvalArgumentSummary(call: ToolCall): String = "Scoped tool call"

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
            ToolResult(callId = call.id, name = call.name, content = "ok")
    }

    private fun call(name: String) =
        ToolCall(id = "call_1", name = name, arguments = """{"path":"/tmp/report.txt"}""")

    private companion object {
        private const val CHAT_ID = 7
        private const val OTHER_CHAT_ID = 9
        private const val READ_TOOL = "mcp_acme_read_file"
        private const val WRITE_TOOL = "mcp_acme_create_order"
        private const val NO_SCOPE_TOOL = "mcp_acme_no_scope"
        private const val SCOPE_READ = "mcp:server-1:read_file"
        private const val SCOPE_WRITE = "mcp:server-1:create_order"
        private const val MAX_YIELDS = 100
    }
}
