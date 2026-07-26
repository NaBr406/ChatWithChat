package cn.nabr.chatwithchat.data.tool

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun `provider automatically appears in catalog with safe fallback metadata`() {
        val registry = ToolRegistry(listOf(provider("future_tool")))

        val entry = registry.catalogEntryFor("future_tool")

        assertNotNull(entry)
        assertEquals("future_tool", entry?.definition?.name)
        assertFalse(entry?.settings?.defaultEnabled ?: true)
        assertEquals(ToolCategory.Other, entry?.settings?.category)
    }

    @Test
    fun `duplicate provider names are rejected deterministically`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ToolRegistry(listOf(provider("duplicate"), provider("duplicate")))
        }

        assertEquals("Duplicate tool provider name(s): duplicate", exception.message)
    }

    @Test
    fun `registered provider handler remains executable`() = runBlocking {
        val registry = ToolRegistry(listOf(provider("echo")))

        val result = registry.handlerFor("echo")?.execute(
            ToolCall("call-1", "echo", "{}"),
            ToolLoopConfig.Default
        )

        assertEquals("ok", result?.content)
    }

    @Test
    fun `presentation artifacts are extracted only from successful provider results`() {
        val artifact = object : ToolPresentationArtifact {
            override val instanceId: String = "call_artifact"
        }
        val provider = object : ToolProvider {
            override val definition = ToolDefinition("presentation", "description", ToolDefinition.Parameters())
            override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic

            override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult = ToolResult(
                callId = call.id,
                name = call.name,
                content = "ok",
                presentationArtifacts = listOf(artifact)
            )

            override fun presentationArtifacts(result: ToolResult): List<ToolPresentationArtifact> =
                result.presentationArtifacts
        }
        val registry = ToolRegistry(listOf(provider))
        val successfulResult = ToolResult(
            callId = "call_artifact",
            name = provider.definition.name,
            content = "ok",
            presentationArtifacts = listOf(artifact)
        )

        assertEquals(listOf(artifact), registry.presentationArtifacts(successfulResult))
        assertTrue(registry.presentationArtifacts(successfulResult.copy(isError = true)).isEmpty())
    }

    private fun provider(name: String): ToolProvider = object : ToolProvider {
        override val definition = ToolDefinition(name, "description", ToolDefinition.Parameters())
        override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult = ToolResult(
            callId = call.id,
            name = call.name,
            content = "ok"
        )
    }
}
