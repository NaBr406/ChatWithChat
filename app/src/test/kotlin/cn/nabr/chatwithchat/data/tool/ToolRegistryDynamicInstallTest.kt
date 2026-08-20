package cn.nabr.chatwithchat.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dynamic half of the catalog exists for MCP tools, which arrive from a third-party server. These
 * tests pin the properties that make that safe: a bad batch changes nothing, and a server cannot take
 * over a built-in name.
 */
class ToolRegistryDynamicInstallTest {

    @Test
    fun installDynamicProviders_addsToolsToDefinitionsAndCatalog() {
        val registry = ToolRegistry(listOf(fakeProvider("builtin_tool")))

        registry.installDynamicProviders(listOf(fakeProvider("mcp_acme_read"))).getOrThrow()

        assertEquals(listOf("builtin_tool", "mcp_acme_read"), registry.definitions.map { it.name })
        assertNotNull(registry.catalogEntryFor("mcp_acme_read"))
        assertNotNull(registry.providerFor("mcp_acme_read"))
        assertNotNull(registry.definitionFor("mcp_acme_read"))
    }

    @Test
    fun installDynamicProviders_replacesThePreviousDynamicSetRatherThanAppending() {
        val registry = ToolRegistry(listOf(fakeProvider("builtin_tool")))

        registry.installDynamicProviders(listOf(fakeProvider("mcp_acme_first"))).getOrThrow()
        registry.installDynamicProviders(listOf(fakeProvider("mcp_acme_second"))).getOrThrow()

        assertEquals(listOf("builtin_tool", "mcp_acme_second"), registry.definitions.map { it.name })
        assertNull("a tool the server dropped must disappear", registry.definitionFor("mcp_acme_first"))
    }

    @Test
    fun installDynamicProviders_withEmptyList_clearsDynamicToolsButKeepsBuiltIns() {
        val registry = ToolRegistry(listOf(fakeProvider("builtin_tool")))
        registry.installDynamicProviders(listOf(fakeProvider("mcp_acme_read"))).getOrThrow()

        registry.installDynamicProviders(emptyList()).getOrThrow()

        assertEquals(listOf("builtin_tool"), registry.definitions.map { it.name })
    }

    @Test
    fun installDynamicProviders_rejectsTheWholeBatchWhenOneDefinitionIsInvalid() {
        val registry = ToolRegistry(listOf(fakeProvider("builtin_tool")))
        registry.installDynamicProviders(listOf(fakeProvider("mcp_acme_good"))).getOrThrow()

        val result = registry.installDynamicProviders(
            listOf(
                fakeProvider("mcp_acme_fresh"),
                // An illegal name for the shared provider subset.
                fakeProvider("mcp acme invalid!")
            )
        )

        assertTrue(result.isFailure)
        assertEquals(
            "the previous snapshot must survive a rejected batch",
            listOf("builtin_tool", "mcp_acme_good"),
            registry.definitions.map { it.name }
        )
        assertNull(registry.definitionFor("mcp_acme_fresh"))
    }

    @Test
    fun installDynamicProviders_rejectsABatchThatWouldShadowABuiltInName() {
        val registry = ToolRegistry(listOf(fakeProvider("web_search")))

        val result = registry.installDynamicProviders(listOf(fakeProvider("web_search")))

        assertTrue("a server must not be able to impersonate a built-in tool", result.isFailure)
        assertEquals(ToolSecurityPolicy.ReadOnlyPublic, registry.securityPolicyFor("web_search"))
    }

    @Test
    fun installDynamicProviders_rejectsABatchWithInternalDuplicates() {
        val registry = ToolRegistry(listOf(fakeProvider("builtin_tool")))

        val result = registry.installDynamicProviders(
            listOf(fakeProvider("mcp_acme_read"), fakeProvider("mcp_acme_read"))
        )

        assertTrue(result.isFailure)
        assertNull(registry.definitionFor("mcp_acme_read"))
    }

    @Test
    fun installDynamicProviders_rejectsADefinitionExceedingTheSchemaDepthCeiling() {
        val registry = ToolRegistry(listOf(fakeProvider("builtin_tool")))
        var nested = ToolDefinition.Parameter(type = "string")
        repeat(20) {
            nested = ToolDefinition.Parameter(type = "object", properties = mapOf("next" to nested))
        }

        val result = registry.installDynamicProviders(
            listOf(
                fakeProvider(
                    name = "mcp_acme_deep",
                    parameters = ToolDefinition.Parameters(properties = mapOf("root" to nested))
                )
            )
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun dynamicProviderPolicyAndSecurity_areReadThroughTheSnapshot() {
        val registry = ToolRegistry(emptyList<ToolProvider>())
        val provider = fakeProvider(
            name = "mcp_acme_write",
            securityPolicy = ToolSecurityPolicy(ToolEffect.EXTERNAL_WRITE, ToolApprovalPolicy.REQUIRE_EACH_CALL),
            policy = ToolPolicy(timeoutSeconds = 42)
        )

        registry.installDynamicProviders(listOf(provider)).getOrThrow()

        assertEquals(ToolEffect.EXTERNAL_WRITE, registry.securityPolicyFor("mcp_acme_write").effect)
        assertEquals(42L, registry.policyFor("mcp_acme_write").timeoutSeconds)
    }

    @Test
    fun constructor_stillThrowsOnAnInvalidBuiltInDefinition() {
        // Built-ins are ours, so a mistake there is a programming error and should fail loudly, unlike a
        // dynamic batch which returns a Result.
        val failure = runCatching { ToolRegistry(listOf(fakeProvider("bad name!"))) }
        assertTrue(failure.isFailure)
    }

    private fun fakeProvider(
        name: String,
        parameters: ToolDefinition.Parameters = ToolDefinition.Parameters(),
        securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic,
        policy: ToolPolicy = ToolPolicy.default()
    ): ToolProvider = object : ToolProvider {
        override val definition = ToolDefinition(name, "Fake tool.", parameters)
        override val securityPolicy = securityPolicy
        override val policy = policy

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
            ToolResult(callId = call.id, name = call.name, content = "ok")
    }
}
