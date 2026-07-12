package dev.chungjungsoo.gptmobile.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalAuthorityTest {
    private val authority = ToolApprovalAuthority(ByteArray(32) { index -> (index + 1).toByte() })

    @Test
    fun `canonical argument hash ignores object order and numeric spelling`() {
        val first = call(
            id = "call_1",
            arguments = """{"b":2,"a":{"z":true,"x":[3,1.0]}}"""
        ).approvalBinding().getOrThrow()
        val second = call(
            id = "call_1",
            arguments = """{"a":{"x":[3.0,1e0],"z":true},"b":2.00}"""
        ).approvalBinding().getOrThrow()

        assertEquals(first.argumentsHash, second.argumentsHash)
        assertEquals(first.callBindingHash, second.callBindingHash)
        assertEquals(first.operationHash, second.operationHash)
    }

    @Test
    fun `approval is bound to tool call id and normalized arguments`() {
        val approvedCall = call(id = "call_a", arguments = """{"title":"Review","count":1}""")
        val approval = authority.approve(approvedCall).getOrThrow()

        assertEquals(ToolApprovalStatus.APPROVED, authority.evaluate(approvedCall, approval).status)
        assertEquals(
            ToolApprovalStatus.APPROVED,
            authority.evaluate(
                approvedCall.copy(arguments = """{"count":1.0,"title":"Review"}"""),
                approval
            ).status
        )
        assertEquals(
            ToolApprovalStatus.INVALID,
            authority.evaluate(approvedCall.copy(id = "call_b"), approval).status
        )
        assertEquals(
            ToolApprovalStatus.INVALID,
            authority.evaluate(approvedCall.copy(arguments = """{"title":"Changed","count":1}"""), approval).status
        )
        assertEquals(
            ToolApprovalStatus.INVALID,
            authority.evaluate(approvedCall.copy(name = "other_write_tool"), approval).status
        )
    }

    @Test
    fun `missing denied and invalid approval states have distinct recoverable codes`() {
        val toolCall = call()
        val foreignApproval = ToolApprovalAuthority(ByteArray(32) { 7 })
            .approve(toolCall)
            .getOrThrow()

        val missing = authority.evaluate(toolCall, ToolCallApproval.Missing)
        val denied = authority.evaluate(toolCall, ToolCallApproval.Denied)
        val invalid = authority.evaluate(toolCall, foreignApproval)

        assertEquals(ToolApprovalStatus.MISSING, missing.status)
        assertEquals("tool_approval_required", missing.status.recoverableErrorCode)
        assertEquals(ToolApprovalStatus.DENIED, denied.status)
        assertEquals("tool_approval_denied", denied.status.recoverableErrorCode)
        assertEquals(ToolApprovalStatus.INVALID, invalid.status)
        assertEquals("tool_approval_invalid", invalid.status.recoverableErrorCode)
        assertFalse(missing.isApproved)
        assertFalse(denied.isApproved)
        assertFalse(invalid.isApproved)
    }

    @Test
    fun `approval token embedded in model arguments is not authorization`() {
        val originalCall = call()
        val token = authority.approve(originalCall).getOrThrow().token.encoded
        val modelCall = originalCall.copy(
            arguments = """{"title":"Review","approval_token":"$token"}"""
        )

        val evaluation = authority.evaluate(modelCall, ToolCallApproval.Missing)

        assertEquals(ToolApprovalStatus.MISSING, evaluation.status)
        assertNotEquals(
            originalCall.approvalBinding().getOrThrow().argumentsHash,
            modelCall.approvalBinding().getOrThrow().argumentsHash
        )
    }

    @Test
    fun `malformed arguments cannot receive approval`() {
        val malformed = call(arguments = "not-json")

        assertTrue(authority.approve(malformed).isFailure)
        assertEquals(
            ToolApprovalStatus.INVALID,
            authority.evaluate(
                malformed,
                ToolApprovalAuthority(ByteArray(32) { 9 }).approve(call()).getOrThrow()
            ).status
        )
    }

    @Test
    fun `write security policies fail closed without per-call approval`() {
        ToolEffect.entries.filter { effect -> effect.isWriteCapable }.forEach { effect ->
            val result = runCatching {
                ToolSecurityPolicy(effect, ToolApprovalPolicy.NOT_REQUIRED)
            }
            assertTrue("$effect should require approval", result.isFailure)
        }

        assertEquals(
            ToolApprovalPolicy.NOT_REQUIRED,
            ToolSecurityPolicy(
                effect = ToolEffect.READ_ONLY_PRIVATE,
                approvalPolicy = ToolApprovalPolicy.NOT_REQUIRED
            ).approvalPolicy
        )
    }

    private fun call(
        id: String = "call_1",
        name: String = "fake_write_tool",
        arguments: String = """{"title":"Review"}"""
    ): ToolCall = ToolCall(id = id, name = name, arguments = arguments)
}
