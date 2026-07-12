package dev.chungjungsoo.gptmobile.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalExecutionContextTest {
    private val authority = ToolApprovalAuthority(ByteArray(32) { index -> (index + 3).toByte() })

    @Test
    fun `explicit retry preserves idempotency key for the same normalized operation`() {
        val nonces = ArrayDeque(listOf("initial-nonce", "unused-nonce"))
        val factory = ToolExecutionContextFactory { nonces.removeFirst() }
        val initialCall = call(id = "call_initial", arguments = """{"b":2,"a":1}""")
        val initialContext = factory.create(
            initialCall,
            authority.approve(initialCall).getOrThrow()
        ).getOrThrow()
        val retryCall = call(id = "call_retry", arguments = """{"a":1.0,"b":2.00}""")
        val retryApproval = authority.approve(retryCall).getOrThrow()

        val retryContext = factory.explicitRetry(
            call = retryCall,
            previousContext = initialContext,
            approval = retryApproval
        ).getOrThrow()

        assertEquals(initialContext.idempotencyKey, retryContext.idempotencyKey)
        assertTrue(retryContext.matchesOperation(retryCall))
        assertEquals(ToolApprovalStatus.APPROVED, authority.evaluate(retryCall, retryContext.approval).status)
        assertEquals(
            ToolApprovalStatus.INVALID,
            authority.evaluate(retryCall, initialContext.approval).status
        )
    }

    @Test
    fun `retry with modified arguments cannot reuse idempotency context`() {
        val factory = ToolExecutionContextFactory { "nonce" }
        val initialCall = call(arguments = """{"value":1}""")
        val initialContext = factory.create(initialCall).getOrThrow()

        val retry = factory.explicitRetry(
            call = initialCall.copy(id = "call_retry", arguments = """{"value":2}"""),
            previousContext = initialContext
        )

        assertTrue(retry.isFailure)
        assertFalse(initialContext.matchesOperation(initialCall.copy(arguments = """{"value":2}""")))
    }

    @Test
    fun `separate initial operations receive distinct idempotency keys`() {
        val nonces = ArrayDeque(listOf("nonce-1", "nonce-2"))
        val factory = ToolExecutionContextFactory { nonces.removeFirst() }
        val toolCall = call()

        val first = factory.create(toolCall).getOrThrow()
        val second = factory.create(toolCall).getOrThrow()

        assertNotEquals(first.idempotencyKey, second.idempotencyKey)
        assertEquals(ToolCallApproval.Missing, first.approval)
    }

    private fun call(
        id: String = "call_1",
        arguments: String = """{"value":1}"""
    ): ToolCall = ToolCall(id = id, name = "fake_write_tool", arguments = arguments)
}
