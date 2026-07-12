package dev.chungjungsoo.gptmobile.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEnablementResolverTest {
    private val resolver = ToolEnablementResolver()

    @Test
    fun `provider defaults apply when no override exists`() {
        val publicTool = catalogEntry("public", defaultEnabled = true, isSensitive = false)
        val sensitiveTool = catalogEntry("sensitive", defaultEnabled = false, isSensitive = true)

        val resolved = resolver.resolve(listOf(publicTool, sensitiveTool), ToolEnablementOverrides())

        assertTrue(resolved.first { it.catalogEntry.definition.name == "public" }.isEnabled)
        assertFalse(resolved.first { it.catalogEntry.definition.name == "sensitive" }.isEnabled)
    }

    @Test
    fun `unsafe provider defaults remain off until explicitly enabled`() {
        val permissionRequirement = ToolPermissionRequirement(
            permissions = listOf("android.permission.TEST"),
            label = "Test permission",
            deniedMessage = "Permission denied."
        )
        val entries = listOf(
            catalogEntry("sensitive", defaultEnabled = true, isSensitive = true),
            catalogEntry(
                name = "permission",
                defaultEnabled = true,
                isSensitive = false,
                permissionRequirements = listOf(permissionRequirement)
            ),
            catalogEntry(
                name = "private",
                defaultEnabled = true,
                isSensitive = false,
                securityPolicy = ToolSecurityPolicy.ReadOnlyPrivate
            ),
            catalogEntry(
                name = "write",
                defaultEnabled = true,
                isSensitive = false,
                securityPolicy = ToolSecurityPolicy(ToolEffect.LOCAL_WRITE, ToolApprovalPolicy.REQUIRE_EACH_CALL)
            )
        )

        assertTrue(resolver.resolve(entries, ToolEnablementOverrides()).none { entry -> entry.isEnabled })
        assertTrue(
            resolver.resolve(
                entries,
                ToolEnablementOverrides(enabledToolNames = entries.map { entry -> entry.definition.name }.toSet())
            ).all { entry -> entry.isEnabled }
        )
    }

    @Test
    fun `explicit overrides win and disabled wins on conflict`() {
        val entry = catalogEntry("private_read", defaultEnabled = false, isSensitive = true)

        assertTrue(
            resolver.isEnabled(
                entry,
                ToolEnablementOverrides(enabledToolNames = setOf("private_read"))
            )
        )
        assertFalse(
            resolver.isEnabled(
                entry,
                ToolEnablementOverrides(
                    enabledToolNames = setOf("private_read"),
                    disabledToolNames = setOf("private_read")
                )
            )
        )
    }

    @Test
    fun `unknown persisted names are ignored by resolution`() {
        val entry = catalogEntry("known", defaultEnabled = true, isSensitive = false)
        val overrides = ToolEnablementOverrides(
            enabledToolNames = setOf("future_enabled"),
            disabledToolNames = setOf("future_disabled")
        )

        assertEquals(setOf("known"), resolver.enabledToolNames(listOf(entry), overrides))
        assertEquals(setOf("future_enabled"), overrides.enabledToolNames)
        assertEquals(setOf("future_disabled"), overrides.disabledToolNames)
    }

    private fun catalogEntry(
        name: String,
        defaultEnabled: Boolean,
        isSensitive: Boolean,
        permissionRequirements: List<ToolPermissionRequirement> = emptyList(),
        securityPolicy: ToolSecurityPolicy = if (isSensitive) {
            ToolSecurityPolicy.ReadOnlyPrivate
        } else {
            ToolSecurityPolicy.ReadOnlyPublic
        }
    ): ToolCatalogEntry = ToolCatalogEntry(
        definition = ToolDefinition(name, "description", ToolDefinition.Parameters()),
        settings = ToolSettingsMetadata(
            defaultEnabled = defaultEnabled,
            isSensitive = isSensitive
        ),
        permissionRequirements = permissionRequirements,
        securityPolicy = securityPolicy
    )
}
