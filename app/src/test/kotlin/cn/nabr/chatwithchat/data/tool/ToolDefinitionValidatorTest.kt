package cn.nabr.chatwithchat.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDefinitionValidatorTest {
    @Test
    fun `registry rejects tool names outside the common provider subset`() {
        val exception = assertInvalid(
            ToolDefinition("invalid tool", "description", ToolDefinition.Parameters()),
            "invalid_tool_name"
        )

        assertEquals(
            "Invalid tool definition at index 0 (\$.name): invalid_tool_name",
            exception.message
        )
    }

    @Test
    fun `registry rejects invalid root and nested schema types`() {
        assertInvalid(
            ToolDefinition(
                "invalid_root",
                "description",
                ToolDefinition.Parameters(type = "array")
            ),
            "root_type_must_be_object"
        )
        assertInvalid(
            definitionWithParameter(ToolDefinition.Parameter(type = "null")),
            "unsupported_schema_type"
        )
    }

    @Test
    fun `registry rejects arrays without items and unknown required properties`() {
        assertInvalid(
            definitionWithParameter(ToolDefinition.Parameter(type = "array")),
            "array_items_required"
        )
        assertInvalid(
            ToolDefinition(
                name = "unknown_required",
                description = "description",
                parameters = ToolDefinition.Parameters(required = listOf("missing"))
            ),
            "required_property_not_defined"
        )
    }

    @Test
    fun `registry rejects conflicting bounds and misplaced keywords`() {
        assertInvalid(
            definitionWithParameter(
                ToolDefinition.Parameter(
                    type = "number",
                    minimum = 2.0,
                    maximum = 1.0
                )
            ),
            "reversed_numeric_bounds"
        )
        assertInvalid(
            definitionWithParameter(
                ToolDefinition.Parameter(
                    type = "string",
                    minLength = 2,
                    maxLength = 1
                )
            ),
            "reversed_string_bounds"
        )
        assertInvalid(
            definitionWithParameter(
                ToolDefinition.Parameter(
                    type = "boolean",
                    minimum = 0.0
                )
            ),
            "keyword_not_allowed_for_type"
        )
    }

    @Test
    fun `registry rejects duplicate enums and unsupported formats`() {
        assertInvalid(
            definitionWithParameter(
                ToolDefinition.Parameter(
                    type = "string",
                    enumValues = listOf("same", "same")
                )
            ),
            "duplicate_enum_value"
        )
        assertInvalid(
            definitionWithParameter(
                ToolDefinition.Parameter(
                    type = "string",
                    format = "provider-specific"
                )
            ),
            "unsupported_string_format"
        )
    }

    @Test
    fun `registry rejects recursive schemas without overflowing`() {
        val properties = linkedMapOf<String, ToolDefinition.Parameter>()
        val recursive = ToolDefinition.Parameter(type = "object", properties = properties)
        properties["self"] = recursive

        assertInvalid(definitionWithParameter(recursive), "recursive_schema")
    }

    private fun assertInvalid(
        definition: ToolDefinition,
        expectedCode: String
    ): IllegalArgumentException {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ToolRegistry(listOf(provider(definition)))
        }
        assertTrue(
            "Expected $expectedCode but got ${exception.message}",
            exception.message.orEmpty().endsWith(": $expectedCode")
        )
        return exception
    }

    private fun definitionWithParameter(parameter: ToolDefinition.Parameter): ToolDefinition = ToolDefinition(
        name = "schema_test",
        description = "description",
        parameters = ToolDefinition.Parameters(properties = mapOf("value" to parameter))
    )

    private fun provider(toolDefinition: ToolDefinition): ToolProvider = object : ToolProvider {
        override val definition = toolDefinition
        override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult = ToolResult(
            callId = call.id,
            name = call.name,
            content = "ok"
        )
    }
}
