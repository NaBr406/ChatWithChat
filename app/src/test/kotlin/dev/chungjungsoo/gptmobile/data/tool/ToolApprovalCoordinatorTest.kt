package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalCoordinatorTest {
    private val authority = ToolApprovalAuthority(ByteArray(32) { index -> (index + 3).toByte() })
    private val contextFactory = ToolExecutionContextFactory { "approval-nonce" }

    @Test
    fun `bound preview approves only the displayed write call`() = runBlocking {
        val provider = PreviewWriteProvider()
        val registry = ToolRegistry(listOf(provider))
        var nonceCalls = 0
        val contextFactory = ToolExecutionContextFactory { "approval-nonce-${++nonceCalls}" }
        val coordinator = ToolApprovalCoordinator(registry, authority, contextFactory)
        val executor = ToolExecutor(
            toolRegistry = registry,
            approvalAuthority = authority,
            executionContextFactory = contextFactory
        )
        val displayedCall = ToolCall("call-a", "preview_write_tool", """{"value":1}""")
        val changedCall = displayedCall.copy(id = "call-b")
        val request = coordinator.prepare(displayedCall).getOrThrow()

        assertEquals("Update value to 1", request.preview.argumentSummary)
        assertEquals("Preview Write Tool", request.preview.fallbackDisplayName)
        assertTrue(coordinator.approve(changedCall, request).isFailure)

        val context = coordinator.approve(displayedCall, request).getOrThrow()
        val duplicateContext = coordinator.approve(displayedCall, request).getOrThrow()
        assertEquals(context.idempotencyKey, duplicateContext.idempotencyKey)
        assertEquals(1, nonceCalls)
        val result = executor.execute(
            call = displayedCall,
            activeToolNames = setOf(displayedCall.name),
            executionContext = context
        )

        assertFalse(result.isError)
        assertEquals(1, provider.executions)
    }

    @Test
    fun `bound preview denial remains fail closed`() = runBlocking {
        val provider = PreviewWriteProvider()
        val registry = ToolRegistry(listOf(provider))
        val coordinator = ToolApprovalCoordinator(registry, authority, contextFactory)
        val executor = ToolExecutor(
            toolRegistry = registry,
            approvalAuthority = authority,
            executionContextFactory = contextFactory
        )
        val call = ToolCall("call-denied", "preview_write_tool", """{"value":2}""")
        val request = coordinator.prepare(call).getOrThrow()
        val context = coordinator.deny(call, request).getOrThrow()

        val result = executor.execute(
            call = call,
            activeToolNames = setOf(call.name),
            executionContext = context
        )

        assertTrue(result.isError)
        assertEquals("tool_approval_denied", result.metadata["error_code"])
        assertEquals(0, provider.executions)
    }

    private class PreviewWriteProvider : ToolProvider {
        override val definition = ToolDefinition(
            name = "preview_write_tool",
            description = "Updates a fake value.",
            parameters = ToolDefinition.Parameters(
                properties = mapOf("value" to ToolDefinition.Parameter(type = "integer")),
                required = listOf("value")
            )
        )
        override val settingsMetadata = ToolSettingsMetadata(
            defaultEnabled = false,
            isSensitive = true,
            presentationKey = "preview_write_tool"
        )
        override val securityPolicy = ToolSecurityPolicy(
            effect = ToolEffect.LOCAL_WRITE,
            approvalPolicy = ToolApprovalPolicy.REQUIRE_EACH_CALL
        )
        var executions: Int = 0

        override fun approvalArgumentSummary(call: ToolCall): String =
            "Update value to ${toolProtocolJson.parseToJsonElement(call.arguments).jsonObject.getValue("value").jsonPrimitive.content}"

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
            error("contextual execution required")

        override suspend fun execute(
            call: ToolCall,
            config: ToolLoopConfig,
            executionContext: ToolExecutionContext
        ): ToolResult {
            executions += 1
            return ToolResult(call.id, call.name, "updated")
        }
    }
}
