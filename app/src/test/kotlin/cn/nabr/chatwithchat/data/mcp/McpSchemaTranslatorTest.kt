package cn.nabr.chatwithchat.data.mcp

import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolProvider
import cn.nabr.chatwithchat.data.tool.ToolRegistry
import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.ToolSecurityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class McpSchemaTranslatorTest {

    // ---- Structural rejections: the whole tool is refused, optional or not ----

    @Test
    fun translate_referenceInProperty_isRejected() {
        assertRejected(
            McpSchemaRejectionReason.SCHEMA_REFERENCE,
            """{ "target": { "${'$'}ref": "#/${'$'}defs/Target" } }"""
        )
    }

    @Test
    fun translate_referenceAnywhereInRawSchema_isRejectedEvenOutsideProperties() {
        val rawSchema = schema(
            """
            {
              "type": "object",
              "properties": { "name": { "type": "string" } },
              "additionalProperties": { "${'$'}ref": "#/${'$'}defs/Extra" }
            }
            """
        )
        val result = McpSchemaTranslator.translate(
            properties = rawSchema["properties"]!!.jsonObject,
            required = emptyList(),
            rawSchema = rawSchema
        )
        assertEquals(
            McpSchemaRejectionReason.SCHEMA_REFERENCE,
            (result as McpSchemaTranslation.Rejected).reason
        )
    }

    @Test
    fun translate_unusedDefsWithoutReference_isAccepted() {
        val rawSchema = schema(
            """
            {
              "type": "object",
              "properties": { "name": { "type": "string" } },
              "${'$'}defs": { "Unused": { "type": "string" } }
            }
            """
        )
        val result = McpSchemaTranslator.translate(
            properties = rawSchema["properties"]!!.jsonObject,
            required = emptyList(),
            rawSchema = rawSchema
        )
        assertTrue(result is McpSchemaTranslation.Accepted)
    }

    @Test
    fun translate_compositionKeywords_areRejected() {
        listOf("oneOf", "anyOf", "allOf", "not", "if", "patternProperties", "contains").forEach { keyword ->
            assertRejected(
                reason = McpSchemaRejectionReason.UNSUPPORTED_COMPOSITION,
                propertiesJson = """{ "value": { "type": "string", "$keyword": {} } }""",
                hint = keyword
            )
        }
    }

    @Test
    fun translate_constKeyword_isRejected() {
        assertRejected(
            McpSchemaRejectionReason.CONST_UNSUPPORTED,
            """{ "mode": { "type": "string", "const": "fixed" } }"""
        )
    }

    @Test
    fun translate_tupleItems_areRejected() {
        assertRejected(
            McpSchemaRejectionReason.TUPLE_ITEMS,
            """{ "pair": { "type": "array", "items": [ { "type": "string" } ] } }"""
        )
        assertRejected(
            McpSchemaRejectionReason.TUPLE_ITEMS,
            """{ "pair": { "type": "array", "prefixItems": [ { "type": "string" } ] } }"""
        )
    }

    @Test
    fun translate_arrayWithoutItems_isRejected() {
        assertRejected(
            McpSchemaRejectionReason.ARRAY_ITEMS_MISSING,
            """{ "tags": { "type": "array" } }"""
        )
    }

    @Test
    fun translate_numericEnum_dropsTheConstraintButKeepsTheProperty() {
        // `enum` only narrows which values are valid; it does not change the shape. `Parameter.enumValues`
        // cannot carry numbers, so the constraint is dropped like any other narrowing keyword and the
        // server keeps enforcing it. Rejecting the tool here would have blocked most real servers.
        val accepted = accept("""{ "level": { "type": "integer", "enum": [1, 2, 3] } }""")

        val level = accepted.properties.getValue("level")
        assertEquals("integer", level.type)
        assertEquals(emptyList<String>(), level.enumValues)
    }

    @Test
    fun translate_mixedEnumOnAStringType_keepsOnlyTheStringMembers() {
        // `type: string` with `enum: ["a", 2]` can only ever be satisfied by "a", so this is exact.
        val accepted = accept("""{ "level": { "type": "string", "enum": ["a", 2] } }""")

        assertEquals(listOf("a"), accepted.properties.getValue("level").enumValues)
    }

    @Test
    fun translate_typelessEnum_infersTheTypeFromItsMembers() {
        val accepted = accept(
            """
            {
              "text":   { "enum": ["a", "b"] },
              "count":  { "enum": [1, 2, 3] },
              "ratio":  { "enum": [1.5, 2.5] },
              "toggle": { "enum": [true, false] }
            }
            """
        )

        // Assuming `string` here would have mislabelled every numeric enum.
        assertEquals("string", accepted.properties.getValue("text").type)
        assertEquals("integer", accepted.properties.getValue("count").type)
        assertEquals("number", accepted.properties.getValue("ratio").type)
        assertEquals("boolean", accepted.properties.getValue("toggle").type)
    }

    @Test
    fun translate_typelessEnumMixingStringsAndNumbers_isRejectedAsARealUnion() {
        assertRejected(
            McpSchemaRejectionReason.UNSUPPORTED_TYPE,
            """{ "value": { "enum": ["a", 1] } }"""
        )
    }

    @Test
    fun translate_realUnionType_isRejected() {
        assertRejected(
            McpSchemaRejectionReason.UNSUPPORTED_TYPE,
            """{ "value": { "type": ["string", "integer"] } }"""
        )
    }

    @Test
    fun translate_nullOnlyTypeOrUnknownType_isRejected() {
        assertRejected(
            McpSchemaRejectionReason.UNSUPPORTED_TYPE,
            """{ "value": { "type": "null" } }"""
        )
        assertRejected(
            McpSchemaRejectionReason.UNSUPPORTED_TYPE,
            """{ "value": { "type": "date" } }"""
        )
        assertRejected(
            McpSchemaRejectionReason.UNSUPPORTED_TYPE,
            """{ "value": { "description": "no type and nothing to infer from" } }"""
        )
    }

    @Test
    fun translate_requiredNamingUnknownProperty_isRejected() {
        val result = McpSchemaTranslator.translate(
            properties = schema("""{ "name": { "type": "string" } }"""),
            required = listOf("name", "absent")
        )
        assertEquals(
            McpSchemaRejectionReason.REQUIRED_PROPERTY_MISSING,
            (result as McpSchemaTranslation.Rejected).reason
        )
    }

    @Test
    fun translate_nestedRequiredNamingUnknownProperty_isRejected() {
        assertRejected(
            McpSchemaRejectionReason.REQUIRED_PROPERTY_MISSING,
            """
            {
              "filter": {
                "type": "object",
                "properties": { "kind": { "type": "string" } },
                "required": ["kind", "missing"]
              }
            }
            """
        )
    }

    @Test
    fun translate_schemaDeeperThanRegistryCeiling_isRejected() {
        // 20 nested objects, past the 16-level ceiling ToolDefinitionValidator enforces.
        var nested = """{ "type": "string" }"""
        repeat(20) {
            nested = """{ "type": "object", "properties": { "next": $nested } }"""
        }
        assertRejected(McpSchemaRejectionReason.SCHEMA_TOO_DEEP, """{ "root": $nested }""")
    }

    @Test
    fun translate_schemaWithMoreNodesThanRegistryCeiling_isRejected() {
        val properties = (1..600).joinToString(separator = ",") { index ->
            """ "p$index": { "type": "string" } """
        }
        assertRejected(McpSchemaRejectionReason.SCHEMA_TOO_LARGE, "{ $properties }")
    }

    @Test
    fun translate_invalidPropertyName_isRejected() {
        val overlongName = "a".repeat(129)
        assertRejected(
            McpSchemaRejectionReason.PROPERTY_NAME_INVALID,
            """{ "$overlongName": { "type": "string" } }"""
        )
    }

    // ---- Faithful mappings ----

    @Test
    fun translate_primitivesAndContainers_mapOntoSupportedSubset() {
        val accepted = accept(
            """
            {
              "name":    { "type": "string", "description": "A name.", "minLength": 1, "maxLength": 40 },
              "count":   { "type": "integer", "minimum": 0, "maximum": 10 },
              "ratio":   { "type": "number" },
              "dryRun":  { "type": "boolean" },
              "tags":    { "type": "array", "items": { "type": "string" } },
              "filter":  {
                "type": "object",
                "properties": { "kind": { "type": "string", "enum": ["a", "b"] } },
                "required": ["kind"],
                "additionalProperties": false
              }
            }
            """,
            required = listOf("name")
        )

        assertEquals(listOf("name"), accepted.required)
        assertEquals(false, accepted.additionalProperties)

        val name = accepted.properties.getValue("name")
        assertEquals("string", name.type)
        assertEquals("A name.", name.description)
        assertEquals(1, name.minLength)
        assertEquals(40, name.maxLength)

        val count = accepted.properties.getValue("count")
        assertEquals("integer", count.type)
        assertEquals(0.0, count.minimum!!, 0.0)
        assertEquals(10.0, count.maximum!!, 0.0)

        assertEquals("string", accepted.properties.getValue("tags").items?.type)

        val filter = accepted.properties.getValue("filter")
        assertEquals(listOf("kind"), filter.required)
        assertEquals(false, filter.additionalProperties)
        assertEquals(listOf("a", "b"), filter.properties.getValue("kind").enumValues)
    }

    @Test
    fun translate_missingTypeButInferable_resolvesTheContainerType() {
        val accepted = accept(
            """
            {
              "nested": { "properties": { "leaf": { "type": "string" } } },
              "list":   { "items": { "type": "string" } },
              "choice": { "enum": ["x", "y"] }
            }
            """
        )
        assertEquals("object", accepted.properties.getValue("nested").type)
        assertEquals("array", accepted.properties.getValue("list").type)
        assertEquals("string", accepted.properties.getValue("choice").type)
    }

    @Test
    fun translate_nullableUnionType_collapsesToTheConcreteMember() {
        val accepted = accept("""{ "note": { "type": ["string", "null"] } }""")
        assertEquals("string", accepted.properties.getValue("note").type)
    }

    @Test
    fun translate_schemaValuedAdditionalProperties_becomesUnspecified() {
        val accepted = accept(
            """
            {
              "bag": {
                "type": "object",
                "properties": { "known": { "type": "string" } },
                "additionalProperties": { "type": "string" }
              }
            }
            """
        )
        assertNull(accepted.properties.getValue("bag").additionalProperties)
    }

    @Test
    fun translate_narrowingKeywords_areDroppedWithoutLosingTheProperty() {
        val accepted = accept(
            """
            {
              "code":  { "type": "string", "pattern": "^[A-Z]+$", "format": "not-a-real-format" },
              "score": { "type": "number", "exclusiveMinimum": 0, "multipleOf": 0.5 },
              "tags":  { "type": "array", "items": { "type": "string" }, "minItems": 1, "uniqueItems": true }
            }
            """
        )
        assertEquals(setOf("code", "score", "tags"), accepted.properties.keys)
        assertNull("unsupported formats are dropped, not fatal", accepted.properties.getValue("code").format)
        assertNull(accepted.properties.getValue("score").minimum)
    }

    @Test
    fun translate_supportedFormatIsKept() {
        val accepted = accept("""{ "at": { "type": "string", "format": "date-time" } }""")
        assertEquals("date-time", accepted.properties.getValue("at").format)
    }

    @Test
    fun translate_reversedBounds_areDroppedSoTheRegistryStillAcceptsTheTool() {
        val accepted = accept(
            """
            {
              "count": { "type": "integer", "minimum": 10, "maximum": 1 },
              "name":  { "type": "string", "minLength": 9, "maxLength": 2 }
            }
            """
        )
        val count = accepted.properties.getValue("count")
        assertNull(count.minimum)
        assertNull(count.maximum)
        val name = accepted.properties.getValue("name")
        assertNull(name.minLength)
        assertNull(name.maxLength)
    }

    @Test
    fun translate_duplicateEnumValuesAndRequiredNames_areDeduplicated() {
        val accepted = accept(
            """{ "kind": { "type": "string", "enum": ["a", "b", "a"] } }""",
            required = listOf("kind", "kind")
        )
        assertEquals(listOf("a", "b"), accepted.properties.getValue("kind").enumValues)
        assertEquals(listOf("kind"), accepted.required)
    }

    @Test
    fun translate_absentProperties_yieldsParameterlessSchema() {
        // MCP omits `properties` entirely for a tool that takes no arguments.
        val accepted = (
            McpSchemaTranslator.translate(properties = null, required = emptyList())
                as McpSchemaTranslation.Accepted
            ).parameters
        assertEquals(emptyMap<String, ToolDefinition.Parameter>(), accepted.properties)
        assertEquals(emptyList<String>(), accepted.required)
    }

    @Test
    fun boundedDescription_clipsVerboseServerText() {
        assertEquals(512, McpSchemaTranslator.boundedDescription("d".repeat(5_000)).length)
        assertEquals("", McpSchemaTranslator.boundedDescription(null))
    }

    // ---- The contract that matters: anything accepted must survive registry validation ----

    @Test
    fun acceptedTranslation_passesToolRegistryValidation() {
        val accepted = accept(
            """
            {
              "name":   { "type": "string", "minLength": 1, "maxLength": 40, "format": "date-time" },
              "count":  { "type": "integer", "minimum": 0, "maximum": 10 },
              "dryRun": { "type": "boolean" },
              "tags":   { "type": "array", "items": { "type": "string", "enum": ["a", "b"] } },
              "filter": {
                "type": "object",
                "properties": { "kind": { "type": "string" } },
                "required": ["kind"],
                "additionalProperties": false
              }
            }
            """,
            required = listOf("name")
        )

        val registry = ToolRegistry(
            listOf(
                FakeMcpToolProvider(
                    ToolDefinition(
                        name = McpToolNamespace.namespacedName("Acme Corp", "server-1", "search.docs"),
                        description = "Bridged MCP tool.",
                        parameters = accepted
                    )
                )
            )
        )

        assertEquals(listOf("mcp_acme_corp_search_docs"), registry.definitions.map(ToolDefinition::name))
    }

    @Test
    fun acceptedTranslationAtCeilingDepth_passesToolRegistryValidation() {
        // 15 nested objects sits just inside the ceiling; the registry must not throw.
        var nested = """{ "type": "string" }"""
        repeat(15) {
            nested = """{ "type": "object", "properties": { "next": $nested } }"""
        }
        val accepted = accept("""{ "root": $nested }""")

        val registry = ToolRegistry(
            listOf(
                FakeMcpToolProvider(
                    ToolDefinition("mcp_srv_deep", "Deeply nested MCP tool.", accepted)
                )
            )
        )
        assertEquals(1, registry.definitions.size)
    }

    // ---- Helpers ----

    private fun accept(
        propertiesJson: String,
        required: List<String> = emptyList()
    ): ToolDefinition.Parameters {
        val result = McpSchemaTranslator.translate(schema(propertiesJson), required)
        assertTrue("expected acceptance but got $result", result is McpSchemaTranslation.Accepted)
        return (result as McpSchemaTranslation.Accepted).parameters
    }

    private fun assertRejected(
        reason: McpSchemaRejectionReason,
        propertiesJson: String,
        hint: String = ""
    ) {
        val result = McpSchemaTranslator.translate(schema(propertiesJson), emptyList())
        assertTrue("expected rejection for $hint but got $result", result is McpSchemaTranslation.Rejected)
        assertEquals(hint, reason, (result as McpSchemaTranslation.Rejected).reason)
    }

    private fun schema(json: String): JsonObject = Json.parseToJsonElement(json.trimIndent()).jsonObject
}

private class FakeMcpToolProvider(override val definition: ToolDefinition) : ToolProvider {
    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.FailClosed

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
        ToolResult(callId = call.id, name = call.name, content = "unused")
}
