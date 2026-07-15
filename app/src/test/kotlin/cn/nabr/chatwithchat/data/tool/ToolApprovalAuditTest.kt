package cn.nabr.chatwithchat.data.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalAuditTest {
    @Test
    fun `audit event contains hashes and status without raw arguments`() {
        val call = ToolCall(
            id = "call_secret",
            name = "external_write_tool",
            arguments = """{"recipient":"person@example.com","api_key":"super-secret"}"""
        )
        val context = ToolExecutionContextFactory { "audit-nonce" }.create(call).getOrThrow()

        val event = ToolAuditEvent.create(
            call = call,
            status = ToolAuditStatus.ATTEMPTED,
            executionContext = context
        ).getOrThrow()

        assertEquals(ToolAuditStatus.ATTEMPTED, event.status)
        assertTrue(event.toolNameHash.isSha256())
        assertTrue(event.argumentsHash.isSha256())
        assertTrue(event.callBindingHash.isSha256())
        assertTrue(event.operationHash.isSha256())
        assertTrue(event.idempotencyKeyHash.orEmpty().isSha256())
        assertFalse(event.toString().contains("person@example.com"))
        assertFalse(event.toString().contains("super-secret"))
        assertFalse(event.toString().contains(call.arguments))
    }

    @Test
    fun `audit event without execution context omits idempotency hash`() {
        val event = ToolAuditEvent.create(
            call = ToolCall("call_1", "read_tool", "{}"),
            status = ToolAuditStatus.EXECUTED
        ).getOrThrow()

        assertNull(event.idempotencyKeyHash)
    }

    @Test
    fun `malformed and non object arguments use hash only audit fallback`() {
        listOf("{", "[]").forEachIndexed { index, arguments ->
            val event = ToolAuditEvent.create(
                call = ToolCall("invalid_$index", "read_tool", arguments),
                status = ToolAuditStatus.ATTEMPTED
            ).getOrThrow()

            assertTrue(event.toolNameHash.isSha256())
            assertTrue(event.argumentsHash.isSha256())
            assertTrue(event.callBindingHash.isSha256())
            assertTrue(event.operationHash.isSha256())
            assertFalse(event.toString().contains(arguments))
        }
    }

    @Test
    fun `oversized argument audit uses bounded prefix and original length`() {
        val sharedPrefix = "x".repeat(512)
        val first = ToolAuditEvent.bindingFor(
            ToolCall("call-1", "read_tool", sharedPrefix + "a"),
            maxArgumentChars = 8
        ).getOrThrow()
        val sameLengthAndPrefix = ToolAuditEvent.bindingFor(
            ToolCall("call-1", "read_tool", sharedPrefix + "b"),
            maxArgumentChars = 8
        ).getOrThrow()
        val differentLength = ToolAuditEvent.bindingFor(
            ToolCall("call-1", "read_tool", sharedPrefix + "bc"),
            maxArgumentChars = 8
        ).getOrThrow()

        assertEquals(first.argumentsHash, sameLengthAndPrefix.argumentsHash)
        assertNotEquals(first.argumentsHash, differentLength.argumentsHash)
    }

    @Test
    fun `audit sink failure is isolated but cancellation propagates`() = runBlocking {
        val event = ToolAuditEvent.create(
            call = ToolCall("call_1", "read_tool", "{}"),
            status = ToolAuditStatus.EXECUTED
        ).getOrThrow()
        val failingSink = ToolAuditSink { throw IllegalStateException("audit unavailable") }

        failingSink.recordSafely(event)
        NoOpToolAuditSink.recordSafely(event)

        val cancellation = runCatching {
            ToolAuditSink { throw CancellationException("cancel") }.recordSafely(event)
        }
        assertTrue(cancellation.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `audit context from another operation is rejected`() {
        val original = ToolCall("call_1", "write_tool", """{"value":1}""")
        val context = ToolExecutionContextFactory { "nonce" }.create(original).getOrThrow()

        val result = ToolAuditEvent.create(
            call = original.copy(arguments = """{"value":2}"""),
            status = ToolAuditStatus.ATTEMPTED,
            executionContext = context
        )

        assertTrue(result.isFailure)
    }

    private fun String.isSha256(): Boolean = length == 64 &&
        all { char ->
            char in '0'..'9' || char in 'a'..'f'
        }
}
