package cn.nabr.chatwithchat.data.tool

import java.util.IdentityHashMap

internal object ToolDefinitionValidator {
    fun validate(
        definition: ToolDefinition,
        definitionIndex: Int
    ) {
        val context = ValidationContext(definitionIndex)
        if (!TOOL_NAME_PATTERN.matches(definition.name)) {
            context.fail("\$.name", "invalid_tool_name")
        }
        if (definition.parameters.type != JSON_SCHEMA_OBJECT) {
            context.fail("\$.parameters.type", "root_type_must_be_object")
        }
        validateObjectMembers(
            properties = definition.parameters.properties,
            required = definition.parameters.required,
            path = "\$.parameters",
            depth = 0,
            context = context
        )
    }

    private fun validateParameter(
        schema: ToolDefinition.Parameter,
        path: String,
        depth: Int,
        context: ValidationContext
    ) {
        if (depth > MAX_SCHEMA_DEPTH) context.fail(path, "schema_too_deep")
        context.visit(schema, path)
        try {
            when (schema.type) {
                JSON_SCHEMA_OBJECT -> validateObject(schema, path, depth, context)
                JSON_SCHEMA_ARRAY -> validateArray(schema, path, depth, context)
                JSON_SCHEMA_STRING -> validateString(schema, path, context)
                JSON_SCHEMA_INTEGER,
                JSON_SCHEMA_NUMBER -> validateNumber(schema, path, context)
                JSON_SCHEMA_BOOLEAN -> validateBoolean(schema, path, context)
                else -> context.fail("$path.type", "unsupported_schema_type")
            }
        } finally {
            context.leave(schema)
        }
    }

    private fun validateObject(
        schema: ToolDefinition.Parameter,
        path: String,
        depth: Int,
        context: ValidationContext
    ) {
        if (schema.items != null) context.fail("$path.items", "keyword_not_allowed_for_type")
        validateNoPrimitiveKeywords(schema, path, context)
        validateObjectMembers(schema.properties, schema.required, path, depth, context)
    }

    private fun validateArray(
        schema: ToolDefinition.Parameter,
        path: String,
        depth: Int,
        context: ValidationContext
    ) {
        validateNoObjectKeywords(schema, path, context)
        validateNoPrimitiveKeywords(schema, path, context)
        val items = schema.items ?: context.fail("$path.items", "array_items_required")
        validateParameter(items, "$path.items", depth + 1, context)
    }

    private fun validateString(
        schema: ToolDefinition.Parameter,
        path: String,
        context: ValidationContext
    ) {
        validateNoContainerKeywords(schema, path, context)
        if (schema.minimum != null) context.fail("$path.minimum", "keyword_not_allowed_for_type")
        if (schema.maximum != null) context.fail("$path.maximum", "keyword_not_allowed_for_type")
        if (schema.enumValues.size != schema.enumValues.distinct().size) {
            context.fail("$path.enum", "duplicate_enum_value")
        }
        schema.minLength?.let { minimum ->
            if (minimum < 0) context.fail("$path.minLength", "negative_string_bound")
        }
        schema.maxLength?.let { maximum ->
            if (maximum < 0) context.fail("$path.maxLength", "negative_string_bound")
        }
        if (schema.minLength != null && schema.maxLength != null && schema.minLength > schema.maxLength) {
            context.fail(path, "reversed_string_bounds")
        }
        schema.format?.let { format ->
            if (format !in SUPPORTED_STRING_FORMATS) context.fail("$path.format", "unsupported_string_format")
        }
    }

    private fun validateNumber(
        schema: ToolDefinition.Parameter,
        path: String,
        context: ValidationContext
    ) {
        validateNoContainerKeywords(schema, path, context)
        validateNoStringKeywords(schema, path, context)
        schema.minimum?.let { minimum ->
            if (!minimum.isFinite()) context.fail("$path.minimum", "non_finite_numeric_bound")
        }
        schema.maximum?.let { maximum ->
            if (!maximum.isFinite()) context.fail("$path.maximum", "non_finite_numeric_bound")
        }
        if (schema.minimum != null && schema.maximum != null && schema.minimum > schema.maximum) {
            context.fail(path, "reversed_numeric_bounds")
        }
    }

    private fun validateBoolean(
        schema: ToolDefinition.Parameter,
        path: String,
        context: ValidationContext
    ) {
        validateNoContainerKeywords(schema, path, context)
        validateNoPrimitiveKeywords(schema, path, context)
    }

    private fun validateObjectMembers(
        properties: Map<String, ToolDefinition.Parameter>,
        required: List<String>,
        path: String,
        depth: Int,
        context: ValidationContext
    ) {
        if (required.size != required.distinct().size) {
            context.fail("$path.required", "duplicate_required_property")
        }
        if (required.any { name -> name !in properties }) {
            context.fail("$path.required", "required_property_not_defined")
        }
        properties.toSortedMap().forEach { (name, propertySchema) ->
            if (!name.isValidPropertyName()) {
                context.fail("$path.properties", "invalid_property_name")
            }
            validateParameter(
                schema = propertySchema,
                path = "$path.properties/${name.toJsonPointerToken()}".take(MAX_ERROR_PATH_CHARS),
                depth = depth + 1,
                context = context
            )
        }
    }

    private fun validateNoContainerKeywords(
        schema: ToolDefinition.Parameter,
        path: String,
        context: ValidationContext
    ) {
        validateNoObjectKeywords(schema, path, context)
        if (schema.items != null) context.fail("$path.items", "keyword_not_allowed_for_type")
    }

    private fun validateNoObjectKeywords(
        schema: ToolDefinition.Parameter,
        path: String,
        context: ValidationContext
    ) {
        if (schema.properties.isNotEmpty()) context.fail("$path.properties", "keyword_not_allowed_for_type")
        if (schema.required.isNotEmpty()) context.fail("$path.required", "keyword_not_allowed_for_type")
        if (schema.additionalProperties != null) {
            context.fail("$path.additionalProperties", "keyword_not_allowed_for_type")
        }
    }

    private fun validateNoPrimitiveKeywords(
        schema: ToolDefinition.Parameter,
        path: String,
        context: ValidationContext
    ) {
        if (schema.enumValues.isNotEmpty()) context.fail("$path.enum", "keyword_not_allowed_for_type")
        if (schema.minimum != null) context.fail("$path.minimum", "keyword_not_allowed_for_type")
        if (schema.maximum != null) context.fail("$path.maximum", "keyword_not_allowed_for_type")
        validateNoStringKeywords(schema, path, context)
    }

    private fun validateNoStringKeywords(
        schema: ToolDefinition.Parameter,
        path: String,
        context: ValidationContext
    ) {
        if (schema.minLength != null) context.fail("$path.minLength", "keyword_not_allowed_for_type")
        if (schema.maxLength != null) context.fail("$path.maxLength", "keyword_not_allowed_for_type")
        if (schema.format != null) context.fail("$path.format", "keyword_not_allowed_for_type")
        if (schema.enumValues.isNotEmpty()) context.fail("$path.enum", "keyword_not_allowed_for_type")
    }

    private class ValidationContext(private val definitionIndex: Int) {
        private val activeSchemas = IdentityHashMap<ToolDefinition.Parameter, Unit>()
        private var visitedSchemaCount = 0

        fun visit(schema: ToolDefinition.Parameter, path: String) {
            visitedSchemaCount += 1
            if (visitedSchemaCount > MAX_SCHEMA_NODES) fail(path, "schema_too_large")
            if (activeSchemas.put(schema, Unit) != null) fail(path, "recursive_schema")
        }

        fun leave(schema: ToolDefinition.Parameter) {
            activeSchemas.remove(schema)
        }

        fun fail(path: String, code: String): Nothing =
            throw IllegalArgumentException(
                "Invalid tool definition at index $definitionIndex (${path.take(MAX_ERROR_PATH_CHARS)}): $code"
            )
    }

    private fun String.isValidPropertyName(): Boolean =
        isNotBlank() && length <= MAX_PROPERTY_NAME_CHARS && none { character -> character.isISOControl() }

    private fun String.toJsonPointerToken(): String = replace("~", "~0").replace("/", "~1")

    private val TOOL_NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}")
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
    private const val MAX_ERROR_PATH_CHARS = 160
}
