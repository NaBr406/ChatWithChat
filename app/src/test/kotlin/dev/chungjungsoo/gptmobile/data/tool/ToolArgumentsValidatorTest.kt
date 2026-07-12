package dev.chungjungsoo.gptmobile.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentsValidatorTest {
    private val validator = ToolArgumentsValidator()

    @Test
    fun `valid nested objects and arrays pass validation`() {
        val result = validator.validate(
            definition = complexSchemaToolDefinition(),
            arguments = """
                {
                  "mode": "safe",
                  "options": {"enabled": true, "threshold": 0.5},
                  "tags": ["alpha", "beta"],
                  "retries": 2,
                  "endpoint": "https://example.com/tools"
                }
            """.trimIndent()
        )

        assertTrue(result.isValid)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `missing required and disallowed additional properties are rejected`() {
        val result = validator.validate(
            definition = complexSchemaToolDefinition(),
            arguments = """
                {
                  "mode": "safe",
                  "options": {"enabled": true, "threshold": 0.5, "extra": true},
                  "retries": 2,
                  "endpoint": "https://example.com",
                  "unexpected": "value"
                }
            """.trimIndent()
        )

        assertFalse(result.isValid)
        assertViolation(result, "$/tags", "required")
        assertViolation(result, "$/options/extra", "additional_property")
        assertViolation(result, "$/unexpected", "additional_property")
    }

    @Test
    fun `wrong primitive object and array types are rejected`() {
        val result = validator.validate(
            definition = complexSchemaToolDefinition(),
            arguments = """
                {
                  "mode": 1,
                  "options": {"enabled": "true", "threshold": "0.5"},
                  "tags": {},
                  "retries": 1.5,
                  "endpoint": "https://example.com"
                }
            """.trimIndent()
        )

        assertFalse(result.isValid)
        assertViolation(result, "$/mode", "type_mismatch")
        assertViolation(result, "$/options/enabled", "type_mismatch")
        assertViolation(result, "$/options/threshold", "type_mismatch")
        assertViolation(result, "$/tags", "type_mismatch")
        assertViolation(result, "$/retries", "type_mismatch")
    }

    @Test
    fun `enum values and array items are validated recursively`() {
        val result = validator.validate(
            definition = complexSchemaToolDefinition(),
            arguments = """
                {
                  "mode": "turbo",
                  "options": {"enabled": true, "threshold": 0.5},
                  "tags": ["valid", 7],
                  "retries": 2,
                  "endpoint": "https://example.com"
                }
            """.trimIndent()
        )

        assertFalse(result.isValid)
        assertViolation(result, "$/mode", "enum")
        assertViolation(result, "$/tags[1]", "type_mismatch")
    }

    @Test
    fun `numeric and string bounds are enforced`() {
        val parameters = ToolDefinition.Parameters(
            properties = mapOf(
                "label" to ToolDefinition.Parameter(
                    type = "string",
                    minLength = 3,
                    maxLength = 5
                ),
                "count" to ToolDefinition.Parameter(
                    type = "integer",
                    minimum = 1.0,
                    maximum = 3.0
                ),
                "ratio" to ToolDefinition.Parameter(
                    type = "number",
                    minimum = 0.0,
                    maximum = 1.0
                )
            ),
            required = listOf("label", "count", "ratio")
        )

        val below = validator.validate(
            arguments = """{"label":"ab","count":0,"ratio":-0.1}""",
            parameters = parameters
        )
        val above = validator.validate(
            arguments = """{"label":"abcdef","count":4,"ratio":1.1}""",
            parameters = parameters
        )
        val inclusive = validator.validate(
            arguments = """{"label":"abc","count":3.0,"ratio":1.0}""",
            parameters = parameters
        )

        assertViolation(below, "$/label", "min_length")
        assertViolation(below, "$/count", "minimum")
        assertViolation(below, "$/ratio", "minimum")
        assertViolation(above, "$/label", "max_length")
        assertViolation(above, "$/count", "maximum")
        assertViolation(above, "$/ratio", "maximum")
        assertTrue(inclusive.isValid)
    }

    @Test
    fun `malformed JSON and non object roots are rejected`() {
        val malformed = validator.validate(complexSchemaToolDefinition(), "{broken")
        val nonObject = validator.validate(complexSchemaToolDefinition(), "[]")

        assertViolation(malformed, "$", "invalid_json")
        assertViolation(nonObject, "$", "type_mismatch")
    }

    @Test
    fun `blank arguments remain compatible with parameterless tools`() {
        val result = validator.validate(ToolDefinition.CurrentDateTime, "")

        assertTrue(result.isValid)
    }

    @Test
    fun `oversized arguments are rejected before JSON parsing`() {
        val result = validator.validate(
            definition = ToolDefinition.CurrentDateTime,
            arguments = "{" + "x".repeat(64),
            maxArgumentChars = 16
        )

        assertFalse(result.isValid)
        assertEquals(1, result.violations.size)
        assertViolation(result, "$", TOOL_ARGUMENTS_TOO_LARGE)
    }

    @Test
    fun `validation violations are deterministically bounded`() {
        val result = ToolArgumentsValidator(maxViolations = 2).validate(
            definition = complexSchemaToolDefinition(),
            arguments = "{}"
        )

        assertFalse(result.isValid)
        assertEquals(2, result.violations.size)
        assertEquals(listOf("$/mode", "$/options"), result.violations.map { violation -> violation.path })
    }

    private fun assertViolation(
        result: ToolArgumentsValidationResult,
        path: String,
        code: String
    ) {
        assertTrue(
            "Expected $code at $path but got ${result.violations}",
            result.violations.any { violation -> violation.path == path && violation.code == code }
        )
    }
}
