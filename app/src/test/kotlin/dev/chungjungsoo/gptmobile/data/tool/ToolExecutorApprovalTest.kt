package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutorApprovalTest {
    private val authority = ToolApprovalAuthority(ByteArray(32) { index -> (index + 11).toByte() })

    @Test
    fun `write provider cannot execute without per call approval`() = runBlocking {
        val provider = RecordingWriteProvider()
        val auditSink = RecordingAuditSink()
        val executor = executor(provider, auditSink)

        val result = executor.executeWithRegisteredTools(call())

        assertTrue(result.isError)
        assertEquals("tool_approval_required", result.metadata["error_code"])
        assertTrue(provider.contexts.isEmpty())
        assertEquals(
            listOf(ToolAuditStatus.ATTEMPTED, ToolAuditStatus.APPROVAL_MISSING),
            auditSink.events.map { event -> event.status }
        )
    }

    @Test
    fun `call bound approval executes only the matching call`() = runBlocking {
        val provider = RecordingWriteProvider()
        val executor = executor(provider)
        val approvedCall = call(id = "call-a", arguments = """{"value":1}""")
        val context = ToolExecutionContextFactory { "nonce-a" }.create(
            approvedCall,
            authority.approve(approvedCall).getOrThrow()
        ).getOrThrow()

        val approvedResult = executor.executeWithRegisteredTools(approvedCall, executionContext = context)
        val changedCallResult = executor.executeWithRegisteredTools(
            approvedCall.copy(id = "call-b"),
            executionContext = context
        )
        val changedArgumentsResult = executor.executeWithRegisteredTools(
            approvedCall.copy(arguments = """{"value":2}"""),
            executionContext = context
        )

        assertFalse(approvedResult.isError)
        assertEquals("tool_approval_invalid", changedCallResult.metadata["error_code"])
        assertEquals("tool_approval_invalid", changedArgumentsResult.metadata["error_code"])
        assertEquals(1, provider.contexts.size)
    }

    @Test
    fun `mismatched execution context is audited without trusting its idempotency key`() = runBlocking {
        val provider = RecordingWriteProvider()
        val auditSink = RecordingAuditSink()
        val executor = executor(provider, auditSink)
        val originalCall = call(arguments = """{"value":1}""")
        val context = ToolExecutionContextFactory { "nonce-original" }.create(
            originalCall,
            authority.approve(originalCall).getOrThrow()
        ).getOrThrow()

        val result = executor.executeWithRegisteredTools(
            originalCall.copy(arguments = """{"value":2}"""),
            executionContext = context
        )

        assertEquals("tool_approval_invalid", result.metadata["error_code"])
        assertEquals(
            listOf(ToolAuditStatus.ATTEMPTED, ToolAuditStatus.APPROVAL_INVALID),
            auditSink.events.map { event -> event.status }
        )
        assertTrue(auditSink.events.all { event -> event.idempotencyKeyHash == null })
        assertTrue(provider.contexts.isEmpty())
    }

    @Test
    fun `explicit denial is recoverable and does not execute provider`() = runBlocking {
        val provider = RecordingWriteProvider()
        val executor = executor(provider)
        val call = call()
        val context = ToolExecutionContextFactory { "nonce-denied" }
            .create(call, ToolCallApproval.Denied)
            .getOrThrow()

        val result = executor.executeWithRegisteredTools(call, executionContext = context)

        assertTrue(result.isError)
        assertEquals("tool_approval_denied", result.metadata["error_code"])
        assertTrue(provider.contexts.isEmpty())
    }

    @Test
    fun `read only provider executes without approval`() = runBlocking {
        var didExecute = false
        val provider = object : ToolProvider {
            override val definition = ToolDefinition("read_tool", "read", ToolDefinition.Parameters())
            override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic

            override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
                didExecute = true
                return ToolResult(call.id, call.name, "read")
            }
        }

        val result = executor(provider).executeWithRegisteredTools(ToolCall("read-call", "read_tool", "{}"))

        assertFalse(result.isError)
        assertTrue(didExecute)
    }

    @Test
    fun `explicit retry preserves idempotency key delivered to provider`() = runBlocking {
        val provider = RecordingWriteProvider()
        val executor = executor(provider)
        val contextFactory = ToolExecutionContextFactory { "stable-nonce" }
        val firstCall = call(id = "first", arguments = """{"b":2,"a":1}""")
        val firstContext = contextFactory.create(
            firstCall,
            authority.approve(firstCall).getOrThrow()
        ).getOrThrow()
        val retryCall = call(id = "retry", arguments = """{"a":1.0,"b":2.00}""")
        val retryContext = contextFactory.explicitRetry(
            retryCall,
            firstContext,
            authority.approve(retryCall).getOrThrow()
        ).getOrThrow()

        executor.executeWithRegisteredTools(firstCall, executionContext = firstContext)
        executor.executeWithRegisteredTools(retryCall, executionContext = retryContext)

        assertEquals(2, provider.contexts.size)
        assertEquals(provider.contexts[0].idempotencyKey, provider.contexts[1].idempotencyKey)
    }

    @Test
    fun `executor rejects calls outside the active catalog before handler`() = runBlocking {
        val provider = RecordingWriteProvider()
        val result = executor(provider).execute(call(), activeToolNames = emptySet())

        assertTrue(result.isError)
        assertEquals("tool_unavailable", result.metadata["error_code"])
        assertTrue(provider.contexts.isEmpty())
    }

    private fun executor(
        provider: ToolProvider,
        auditSink: ToolAuditSink = NoOpToolAuditSink
    ): ToolExecutor = ToolExecutor(
        toolRegistry = ToolRegistry(listOf(provider)),
        approvalAuthority = authority,
        auditSink = auditSink
    )

    private fun call(
        id: String = "write-call",
        arguments: String = """{"value":1}"""
    ): ToolCall = ToolCall(id, "fake_write_tool", arguments)

    private class RecordingWriteProvider : ToolProvider {
        override val definition = ToolDefinition(
            "fake_write_tool",
            "test write",
            ToolDefinition.Parameters(
                properties = mapOf(
                    "value" to ToolDefinition.Parameter(type = "integer"),
                    "a" to ToolDefinition.Parameter(type = "integer"),
                    "b" to ToolDefinition.Parameter(type = "integer")
                )
            )
        )
        override val securityPolicy = ToolSecurityPolicy(
            ToolEffect.LOCAL_WRITE,
            ToolApprovalPolicy.REQUIRE_EACH_CALL
        )
        val contexts = mutableListOf<ToolExecutionContext>()

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
            error("contextual execution required")

        override suspend fun execute(
            call: ToolCall,
            config: ToolLoopConfig,
            executionContext: ToolExecutionContext
        ): ToolResult {
            contexts += executionContext
            return ToolResult(call.id, call.name, "written")
        }
    }

    private class RecordingAuditSink : ToolAuditSink {
        val events = mutableListOf<ToolAuditEvent>()

        override suspend fun record(event: ToolAuditEvent) {
            events += event
        }
    }
}
