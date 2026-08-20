package cn.nabr.chatwithchat.data.mcp

import cn.nabr.chatwithchat.data.tool.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Stable reason code for an MCP tool this app refuses to expose. Surfaced verbatim in MCP settings. */
enum class McpSchemaRejectionReason(val code: String) {
    UNSUPPORTED_COMPOSITION("schema_unsupported_composition"),
    SCHEMA_REFERENCE("schema_reference_unsupported"),
    UNSUPPORTED_TYPE("schema_unsupported_type"),
    TUPLE_ITEMS("schema_tuple_items_unsupported"),
    ARRAY_ITEMS_MISSING("schema_array_items_missing"),
    /**
     * No longer produced: a non-string `enum` is a narrowing constraint and is now dropped rather than
     * fatal. Retained so rows cached by an older version still resolve to a label.
     */
    NON_STRING_ENUM("schema_non_string_enum"),
    CONST_UNSUPPORTED("schema_const_unsupported"),
    SCHEMA_TOO_DEEP("schema_too_deep"),
    SCHEMA_TOO_LARGE("schema_too_large"),
    REQUIRED_PROPERTY_MISSING("schema_required_property_missing"),
    PROPERTY_NAME_INVALID("schema_property_name_invalid")
}

sealed interface McpSchemaTranslation {
    data class Accepted(val parameters: ToolDefinition.Parameters) : McpSchemaTranslation

    data class Rejected(
        val reason: McpSchemaRejectionReason,
        val path: String
    ) : McpSchemaTranslation
}

/**
 * Translates an MCP server's `inputSchema` into the constrained JSON Schema subset that
 * [ToolDefinition.Parameters] can express, or refuses the tool outright.
 *
 * MCP servers may send any JSON Schema; `ToolDefinitionValidator` accepts a small, closed subset and
 * throws on anything else. Translating here rather than at registry-install time turns "this server
 * broke the tool catalog" into a typed per-tool verdict the settings screen can explain.
 *
 * The accept/reject line is drawn by whether a keyword changes the *shape* of a valid argument object:
 *
 * - **Structural** keywords (`$ref`, `oneOf`/`anyOf`/`allOf`/`not`, `if`/`then`/`else`, tuple `items`,
 *   real union types, `const`) decide which shapes validate at all. They cannot be approximated, so the
 *   whole tool is rejected — including when the offending property is optional. Silently dropping an
 *   optional property would hide a capability, and for a write-capable remote tool it could drop
 *   something load-bearing like a `dry_run` flag. Fail closed and tell the user which tool and why.
 * - **Narrowing** keywords (`pattern`, `exclusiveMinimum`, `multipleOf`, `minItems`, …) only shrink an
 *   otherwise-representable type. They are dropped: [cn.nabr.chatwithchat.data.tool.ToolArgumentsValidator]
 *   is a pre-filter that keeps malformed calls off the wire, not the authority on validity — the server
 *   still enforces its own schema. A dropped constraint can cost one round trip; it cannot bypass
 *   enablement, permissions, or per-call approval.
 *
 * Depth and node ceilings mirror `ToolDefinitionValidator` so an accepted result always survives
 * registry validation.
 */
internal object McpSchemaTranslator {

    fun translate(
        properties: JsonObject?,
        required: List<String>,
        rawSchema: JsonObject? = null
    ): McpSchemaTranslation = try {
        // `$defs` alone is harmless dead weight; only an actual `$ref` means we would have to resolve it.
        rawSchema?.let { schema -> rejectIfContainsReference(schema, ROOT_PATH) }

        val context = TranslationContext()
        val translatedProperties = translateProperties(
            properties = properties,
            path = ROOT_PATH,
            depth = 0,
            context = context
        )
        val normalizedRequired = required.distinct()
        normalizedRequired.forEach { name ->
            if (name !in translatedProperties) {
                fail(McpSchemaRejectionReason.REQUIRED_PROPERTY_MISSING, "$ROOT_PATH.required")
            }
        }

        McpSchemaTranslation.Accepted(
            ToolDefinition.Parameters(
                properties = translatedProperties,
                required = normalizedRequired,
                // MCP's typed ToolSchema drops root-level `additionalProperties` entirely, so there is
                // nothing to mirror. `false` is both the project default and the safer reading: it keeps
                // a model from smuggling arguments the server never advertised.
                additionalProperties = false
            )
        )
    } catch (rejection: SchemaRejectedException) {
        McpSchemaTranslation.Rejected(rejection.reason, rejection.path)
    }

    /** Bounds a server-supplied description so one verbose tool cannot eat the advertisement budget. */
    fun boundedDescription(description: String?): String =
        description?.trim()?.take(MAX_DESCRIPTION_CHARS).orEmpty()

    private fun translateProperties(
        properties: JsonObject?,
        path: String,
        depth: Int,
        context: TranslationContext
    ): Map<String, ToolDefinition.Parameter> {
        properties ?: return emptyMap()
        return properties.entries
            .sortedBy { entry -> entry.key }
            .associate { (name, schema) ->
                if (!name.isValidPropertyName()) {
                    fail(McpSchemaRejectionReason.PROPERTY_NAME_INVALID, "$path.properties")
                }
                val propertyPath = "$path.properties.$name"
                val node = schema as? JsonObject
                    ?: fail(McpSchemaRejectionReason.UNSUPPORTED_TYPE, propertyPath)
                name to translateNode(node, propertyPath, depth + 1, context)
            }
    }

    private fun translateNode(
        node: JsonObject,
        path: String,
        depth: Int,
        context: TranslationContext
    ): ToolDefinition.Parameter {
        if (depth > MAX_SCHEMA_DEPTH) fail(McpSchemaRejectionReason.SCHEMA_TOO_DEEP, path)
        context.visit(path)
        rejectStructuralKeywords(node, path)

        val description = boundedDescription(node.stringOrNull(KEY_DESCRIPTION)).ifBlank { null }
        return when (val type = node.resolveType(path)) {
            JSON_SCHEMA_OBJECT -> {
                val nested = translateProperties(node.objectOrNull(KEY_PROPERTIES), path, depth, context)
                val nestedRequired = node.stringListOrEmpty(KEY_REQUIRED).distinct()
                nestedRequired.forEach { name ->
                    if (name !in nested) {
                        fail(McpSchemaRejectionReason.REQUIRED_PROPERTY_MISSING, "$path.required")
                    }
                }
                ToolDefinition.Parameter(
                    type = type,
                    description = description,
                    properties = nested,
                    required = nestedRequired,
                    // A schema-valued `additionalProperties` describes typed extras we cannot express;
                    // leaving it unset simply skips the extra-property check for this nested object.
                    additionalProperties = node[KEY_ADDITIONAL_PROPERTIES]?.booleanOrNullOrDropped()
                )
            }

            JSON_SCHEMA_ARRAY -> {
                if (node[KEY_PREFIX_ITEMS] != null) fail(McpSchemaRejectionReason.TUPLE_ITEMS, path)
                val items = node[KEY_ITEMS]
                    ?: fail(McpSchemaRejectionReason.ARRAY_ITEMS_MISSING, path)
                if (items is JsonArray) fail(McpSchemaRejectionReason.TUPLE_ITEMS, "$path.items")
                val itemNode = items as? JsonObject
                    ?: fail(McpSchemaRejectionReason.UNSUPPORTED_TYPE, "$path.items")
                ToolDefinition.Parameter(
                    type = type,
                    description = description,
                    items = translateNode(itemNode, "$path.items", depth + 1, context)
                )
            }

            JSON_SCHEMA_STRING -> {
                val (minLength, maxLength) = node.stringBounds()
                ToolDefinition.Parameter(
                    type = type,
                    description = description,
                    enumValues = node.stringEnumMembers(),
                    minLength = minLength,
                    maxLength = maxLength,
                    format = node.stringOrNull(KEY_FORMAT)?.takeIf { it in SUPPORTED_STRING_FORMATS }
                )
            }

            JSON_SCHEMA_INTEGER, JSON_SCHEMA_NUMBER -> {
                // A numeric `enum` cannot be carried (`Parameter.enumValues` is `List<String>`), so it is
                // dropped like any other narrowing keyword: the type and therefore the shape is unchanged,
                // and the server still enforces the allowed values.
                val (minimum, maximum) = node.numericBounds()
                ToolDefinition.Parameter(
                    type = type,
                    description = description,
                    minimum = minimum,
                    maximum = maximum
                )
            }

            else -> {
                // Boolean: an `enum` here is redundant at best, and unrepresentable, so it is dropped.
                ToolDefinition.Parameter(type = type, description = description)
            }
        }
    }

    private fun rejectStructuralKeywords(node: JsonObject, path: String) {
        STRUCTURAL_COMPOSITION_KEYWORDS.firstOrNull { keyword -> keyword in node }?.let { keyword ->
            fail(McpSchemaRejectionReason.UNSUPPORTED_COMPOSITION, "$path.$keyword")
        }
        if (KEY_REF in node) fail(McpSchemaRejectionReason.SCHEMA_REFERENCE, "$path.${KEY_REF}")
        if (KEY_CONST in node) fail(McpSchemaRejectionReason.CONST_UNSUPPORTED, "$path.$KEY_CONST")
    }

    private fun rejectIfContainsReference(element: JsonElement, path: String) {
        when (element) {
            is JsonObject -> {
                if (KEY_REF in element) fail(McpSchemaRejectionReason.SCHEMA_REFERENCE, path)
                element.forEach { (key, value) -> rejectIfContainsReference(value, "$path.$key") }
            }
            is JsonArray -> element.forEachIndexed { index, value ->
                rejectIfContainsReference(value, "$path[$index]")
            }
            else -> Unit
        }
    }

    private fun JsonObject.resolveType(path: String): String {
        val declared = this[KEY_TYPE]
        val resolved = when (declared) {
            null -> inferredType(path)
            is JsonArray -> {
                // `["string", "null"]` is how many generators spell an optional field. Collapsing it to
                // the single non-null member is stricter than the server, never looser.
                val concrete = declared
                    .mapNotNull { entry -> (entry as? JsonPrimitive)?.contentOrNullIfNotString() }
                    .filter { type -> type != JSON_SCHEMA_NULL }
                    .distinct()
                concrete.singleOrNull() ?: fail(McpSchemaRejectionReason.UNSUPPORTED_TYPE, "$path.type")
            }
            is JsonPrimitive -> declared.contentOrNullIfNotString()
                ?: fail(McpSchemaRejectionReason.UNSUPPORTED_TYPE, "$path.type")
            else -> fail(McpSchemaRejectionReason.UNSUPPORTED_TYPE, "$path.type")
        }
        if (resolved !in SUPPORTED_SCHEMA_TYPES) {
            fail(McpSchemaRejectionReason.UNSUPPORTED_TYPE, "$path.type")
        }
        return resolved
    }

    /** Real schemas often omit `type`; inferring it only narrows what we accept. */
    private fun JsonObject.inferredType(path: String): String = when {
        KEY_PROPERTIES in this -> JSON_SCHEMA_OBJECT
        KEY_ITEMS in this || KEY_PREFIX_ITEMS in this -> JSON_SCHEMA_ARRAY
        this[KEY_ENUM] is JsonArray -> enumInferredType(path)
        else -> fail(McpSchemaRejectionReason.UNSUPPORTED_TYPE, "$path.type")
    }

    /**
     * The string members of an `enum`, if any.
     *
     * Non-string members are discarded rather than fatal. For a declared `string` type that is exact:
     * `enum: ["a", 1]` can only ever be satisfied by `"a"`, since `1` fails the type check anyway.
     */
    private fun JsonObject.stringEnumMembers(): List<String> = (this[KEY_ENUM] as? JsonArray)
        ?.mapNotNull { entry -> (entry as? JsonPrimitive)?.contentOrNullIfNotString() }
        ?.distinct()
        .orEmpty()

    /**
     * Infers a type for `enum` without a declared `type`, from the members' own JSON kinds.
     *
     * Assuming `string` would mislabel `enum: [1, 2, 3]`, which is common in generated schemas. A
     * genuinely mixed enum is a real union and stays a rejection.
     */
    private fun JsonObject.enumInferredType(path: String): String {
        val members = (this[KEY_ENUM] as? JsonArray)
            ?.map { entry -> entry as? JsonPrimitive ?: fail(McpSchemaRejectionReason.UNSUPPORTED_TYPE, "$path.enum") }
            ?.takeIf { values -> values.isNotEmpty() }
            ?: fail(McpSchemaRejectionReason.UNSUPPORTED_TYPE, "$path.enum")

        val literals = members.filterNot { member -> member.isString }
        return when {
            members.all { member -> member.isString } -> JSON_SCHEMA_STRING
            literals.size != members.size -> fail(McpSchemaRejectionReason.UNSUPPORTED_TYPE, "$path.enum")
            literals.all { member -> member.content in BOOLEAN_LITERALS } -> JSON_SCHEMA_BOOLEAN
            literals.all { member -> member.content.toLongOrNull() != null } -> JSON_SCHEMA_INTEGER
            literals.all { member -> member.content.toDoubleOrNull() != null } -> JSON_SCHEMA_NUMBER
            else -> fail(McpSchemaRejectionReason.UNSUPPORTED_TYPE, "$path.enum")
        }
    }

    /** Reversed or unusable bounds are dropped rather than forwarded; the registry rejects those. */
    private fun JsonObject.stringBounds(): Pair<Int?, Int?> {
        val minimum = this[KEY_MIN_LENGTH]?.intOrNullSafe()?.takeIf { value -> value >= 0 }
        val maximum = this[KEY_MAX_LENGTH]?.intOrNullSafe()?.takeIf { value -> value >= 0 }
        return if (minimum != null && maximum != null && minimum > maximum) null to null else minimum to maximum
    }

    private fun JsonObject.numericBounds(): Pair<Double?, Double?> {
        val minimum = this[KEY_MINIMUM]?.doubleOrNullSafe()?.takeIf(Double::isFinite)
        val maximum = this[KEY_MAXIMUM]?.doubleOrNullSafe()?.takeIf(Double::isFinite)
        return if (minimum != null && maximum != null && minimum > maximum) null to null else minimum to maximum
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNullIfNotString()

    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.stringListOrEmpty(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { entry -> (entry as? JsonPrimitive)?.contentOrNullIfNotString() }
            .orEmpty()

    private fun JsonPrimitive.contentOrNullIfNotString(): String? = if (isString) content else null

    private fun JsonElement.intOrNullSafe(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonElement.doubleOrNullSafe(): Double? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** `true`/`false` are mirrored faithfully; a schema-valued form becomes "unspecified". */
    private fun JsonElement.booleanOrNullOrDropped(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /** Matches `ToolDefinitionValidator.isValidPropertyName`. */
    private fun String.isValidPropertyName(): Boolean =
        isNotBlank() && length <= MAX_PROPERTY_NAME_CHARS && none { character -> character.isISOControl() }

    private fun fail(reason: McpSchemaRejectionReason, path: String): Nothing =
        throw SchemaRejectedException(reason, path.take(MAX_PATH_CHARS))

    private class SchemaRejectedException(
        val reason: McpSchemaRejectionReason,
        val path: String
    ) : RuntimeException(reason.code)

    private class TranslationContext {
        private var visitedNodeCount = 0

        fun visit(path: String) {
            visitedNodeCount += 1
            if (visitedNodeCount > MAX_SCHEMA_NODES) {
                fail(McpSchemaRejectionReason.SCHEMA_TOO_LARGE, path)
            }
        }
    }

    private const val ROOT_PATH = "$"
    private const val JSON_SCHEMA_OBJECT = "object"
    private const val JSON_SCHEMA_ARRAY = "array"
    private const val JSON_SCHEMA_STRING = "string"
    private const val JSON_SCHEMA_INTEGER = "integer"
    private const val JSON_SCHEMA_NUMBER = "number"
    private const val JSON_SCHEMA_BOOLEAN = "boolean"
    private const val JSON_SCHEMA_NULL = "null"

    private const val KEY_TYPE = "type"
    private const val KEY_PROPERTIES = "properties"
    private const val KEY_REQUIRED = "required"
    private const val KEY_ITEMS = "items"
    private const val KEY_PREFIX_ITEMS = "prefixItems"
    private const val KEY_ENUM = "enum"
    private val BOOLEAN_LITERALS = setOf("true", "false")
    private const val KEY_CONST = "const"
    private const val KEY_REF = "\$ref"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_FORMAT = "format"
    private const val KEY_ADDITIONAL_PROPERTIES = "additionalProperties"
    private const val KEY_MIN_LENGTH = "minLength"
    private const val KEY_MAX_LENGTH = "maxLength"
    private const val KEY_MINIMUM = "minimum"
    private const val KEY_MAXIMUM = "maximum"

    private val SUPPORTED_SCHEMA_TYPES = setOf(
        JSON_SCHEMA_OBJECT,
        JSON_SCHEMA_ARRAY,
        JSON_SCHEMA_STRING,
        JSON_SCHEMA_INTEGER,
        JSON_SCHEMA_NUMBER,
        JSON_SCHEMA_BOOLEAN
    )

    /** Keywords that decide which argument shapes validate at all; none can be approximated. */
    private val STRUCTURAL_COMPOSITION_KEYWORDS = listOf(
        "oneOf",
        "anyOf",
        "allOf",
        "not",
        "if",
        "then",
        "else",
        "patternProperties",
        "propertyNames",
        "dependentSchemas",
        "dependentRequired",
        "dependencies",
        "unevaluatedProperties",
        "unevaluatedItems",
        "contains"
    )

    /** Mirrors `ToolDefinitionValidator.SUPPORTED_STRING_FORMATS`; anything else is dropped. */
    private val SUPPORTED_STRING_FORMATS = setOf(
        "date-time",
        "time",
        "date",
        "duration",
        "email",
        "hostname",
        "ipv4",
        "ipv6",
        "uuid",
        "uri"
    )

    private const val MAX_SCHEMA_DEPTH = 16
    private const val MAX_SCHEMA_NODES = 512
    private const val MAX_PROPERTY_NAME_CHARS = 128
    private const val MAX_DESCRIPTION_CHARS = 512
    private const val MAX_PATH_CHARS = 160
}
