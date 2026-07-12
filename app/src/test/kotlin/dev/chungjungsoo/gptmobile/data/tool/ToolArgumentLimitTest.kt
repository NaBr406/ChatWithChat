package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentLimitTest {
    @Test
    fun `stream limiter rejects cumulative arguments before append`() {
        val limiter = ToolArgumentStreamLimiter(maxArgumentChars = 4, maxCallIdentities = 2)
        limiter.append("call-1", "12")

        val exception = assertThrows(ToolArgumentsTooLargeException::class.java) {
            limiter.append("call-1", "345")
        }

        assertEquals(TOOL_ARGUMENTS_TOO_LARGE, exception.message)
    }

    @Test
    fun `stream limiter counts tool identities without arguments`() {
        val limiter = ToolArgumentStreamLimiter(maxArgumentChars = 4, maxCallIdentities = 1)
        limiter.register("call-1")

        val exception = assertThrows(ToolCallIdentityLimitExceededException::class.java) {
            limiter.register("call-2")
        }

        assertEquals(TOOL_CALL_IDENTITY_LIMIT_EXCEEDED, exception.message)
    }

    @Test
    fun `fallback parser rejects oversized arguments before creating a tool call`() {
        val result = JsonToolCallParser().parse(
            rawText = """{"type":"tool_calls","tool_calls":[{"name":"bounded_arguments","arguments":{"value":"oversized"}}]}""",
            config = ToolLoopConfig(maxToolArgumentChars = 8)
        )

        assertTrue(result.exceptionOrNull() is ToolArgumentsTooLargeException)
    }

    @Test
    fun `fallback parser rejects the whole protocol response before JSON parsing`() {
        val config = ToolLoopConfig(maxToolArgumentChars = 4, maxToolCallsPerRound = 1)
        val rawText = "x".repeat(config.maxToolProtocolResponseChars() + 1)

        val result = JsonToolCallParser().parse(rawText, config)

        assertTrue(result.exceptionOrNull() is ToolProtocolResponseTooLargeException)
    }

    @Test
    fun `fallback parser stops constructing calls after the unique call limit`() {
        val result = JsonToolCallParser().parse(
            rawText =
            """
                {"type":"tool_calls","tool_calls":[
                  {"id":"call-1","name":"first","arguments":{}},
                  {"id":"call-2","name":"first","arguments":{}},
                  {"id":"call-3","name":"second","arguments":{}},
                  {"id":"call-4","name":"not-evaluated","arguments":[]}
                ]}
            """.trimIndent(),
            config = ToolLoopConfig(maxToolCallsPerRound = 2)
        ).getOrThrow() as JsonToolModelOutput.ToolCalls

        assertEquals(listOf("call-1", "call-3"), result.calls.map { call -> call.id })
    }

    @Test
    fun `executor rejects oversized arguments before provider execution`() = runBlocking {
        var didExecute = false
        val provider = object : ToolProvider {
            override val definition = ToolDefinition(
                name = "bounded_arguments",
                description = "A tool with bounded arguments.",
                parameters = ToolDefinition.Parameters(
                    properties = mapOf("value" to ToolDefinition.Parameter(type = "string")),
                    required = listOf("value")
                )
            )
            override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic

            override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
                didExecute = true
                return ToolResult(call.id, call.name, "executed")
            }
        }
        val executor = ToolExecutor(ToolRegistry(listOf(provider)))

        val result = executor.execute(
            call = ToolCall("call-1", provider.definition.name, "{\"value\":\"oversized\"}"),
            activeToolNames = setOf(provider.definition.name),
            config = ToolLoopConfig(maxToolArgumentChars = 8)
        )

        assertTrue(result.isError)
        assertFalse(didExecute)
        assertEquals("tool_arguments_invalid", result.metadata["error_code"])
        assertTrue(result.content.contains(TOOL_ARGUMENTS_TOO_LARGE))
    }
}
