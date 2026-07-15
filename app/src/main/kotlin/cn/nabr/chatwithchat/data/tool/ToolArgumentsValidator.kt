package cn.nabr.chatwithchat.data.tool

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ToolArgumentsValidator(
    private val maxViolations: Int = DEFAULT_MAX_VIOLATIONS
) {
    fun validate(
        definition: ToolDefinition,
        arguments: String,
        maxArgumentChars: Int = ToolLoopConfig.Default.maxToolArgumentChars
    ): ToolArgumentsValidationResult = validate(arguments, definition.parameters, maxArgumentChars)

    fun validate(
        arguments: String,
        parameters: ToolDefinition.Parameters,
        maxArgumentChars: Int = ToolLoopConfig.Default.maxToolArgumentChars
    ): ToolArgumentsValidationResult {
        val collector = ViolationCollector(maxViolations.coerceIn(1, MAX_ALLOWED_VIOLATIONS))
        if (arguments.length > maxArgumentChars.coerceAtLeast(0)) {
            collector.add(
                path = ROOT_PATH,
                code = TOOL_ARGUMENTS_TOO_LARGE,
                message = "Arguments exceed the configured character limit."
            )
            return collector.toResult()
        }
        val element = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { EMPTY_ARGUMENTS })
        }.getOrElse {
            collector.add(
                path = ROOT_PATH,
                code = "invalid_json",
                message = "Arguments must be valid JSON."
            )
            return collector.toResult()
        }

        validateElement(
            element = element,
            schema = parameters.toRootSchema(),
            path = ROOT_PATH,
            collector = collector
        )
        return collector.toResult()
    }

    private fun validateElement(
        element: JsonElement,
        schema: ToolDefinition.Parameter,
        path: String,
        collector: ViolationCollector
    ) {
        if (collector.isFull) return

        when (schema.type) {
            JSON_SCHEMA_OBJECT -> validateObject(element, schema, path, collector)
            JSON_SCHEMA_ARRAY -> validateArray(element, schema, path, collector)
            JSON_SCHEMA_STRING -> validateString(element, schema, path, collector)
            JSON_SCHEMA_INTEGER -> validateNumber(element, schema, path, collector, integerOnly = true)
            JSON_SCHEMA_NUMBER -> validateNumber(element, schema, path, collector, integerOnly = false)
            JSON_SCHEMA_BOOLEAN -> validateBoolean(element, path, collector)
            else -> collector.add(
                path = path,
                code = "schema_type_unsupported",
                message = "The tool schema contains an unsupported type."
            )
        }
    }

    private fun validateObject(
        element: JsonElement,
        schema: ToolDefinition.Parameter,
        path: String,
        collector: ViolationCollector
    ) {
        val value = element as? JsonObject
        if (value == null) {
            collector.typeMismatch(path, JSON_SCHEMA_OBJECT)
            return
        }

        schema.required.distinct().forEach { name ->
            if (name !in value) {
                collector.add(
                    path = path.child(name),
                    code = "required",
                    message = "Required property is missing."
                )
            }
        }
        schema.properties.toSortedMap().forEach { (name, propertySchema) ->
            value[name]?.let { propertyValue ->
                validateElement(propertyValue, propertySchema, path.child(name), collector)
            }
        }
        if (schema.additionalProperties == false) {
            (value.keys - schema.properties.keys).sorted().forEach { name ->
                collector.add(
                    path = path.child(name),
                    code = "additional_property",
                    message = "Property is not allowed by the tool schema."
                )
            }
        }
    }

    private fun validateArray(
        element: JsonElement,
        schema: ToolDefinition.Parameter,
        path: String,
        collector: ViolationCollector
    ) {
        val value = element as? JsonArray
        if (value == null) {
            collector.typeMismatch(path, JSON_SCHEMA_ARRAY)
            return
        }

        schema.items?.let { itemSchema ->
            value.forEachIndexed { index, item ->
                validateElement(item, itemSchema, "$path[$index]", collector)
            }
        }
    }

    private fun validateString(
        element: JsonElement,
        schema: ToolDefinition.Parameter,
        path: String,
        collector: ViolationCollector
    ) {
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            collector.typeMismatch(path, JSON_SCHEMA_STRING)
            return
        }

        val value = primitive.content
        if (schema.enumValues.isNotEmpty() && value !in schema.enumValues) {
            collector.add(
                path = path,
                code = "enum",
                message = "String must match one of the allowed values."
            )
        }

        val length = value.codePointCount(0, value.length)
        schema.minLength?.let { minimum ->
            when {
                minimum < 0 -> collector.invalidSchema(path)
                length < minimum -> collector.add(
                    path = path,
                    code = "min_length",
                    message = "String is shorter than the allowed minimum."
                )
            }
        }
        schema.maxLength?.let { maximum ->
            when {
                maximum < 0 -> collector.invalidSchema(path)
                length > maximum -> collector.add(
                    path = path,
                    code = "max_length",
                    message = "String is longer than the allowed maximum."
                )
            }
        }
        if (schema.minLength != null && schema.maxLength != null && schema.minLength > schema.maxLength) {
            collector.invalidSchema(path)
        }
    }

    private fun validateNumber(
        element: JsonElement,
        schema: ToolDefinition.Parameter,
        path: String,
        collector: ViolationCollector,
        integerOnly: Boolean
    ) {
        val value = element.toBigDecimalOrNull()
        if (value == null || (integerOnly && !value.isInteger())) {
            collector.typeMismatch(path, if (integerOnly) JSON_SCHEMA_INTEGER else JSON_SCHEMA_NUMBER)
            return
        }

        schema.minimum?.let { minimum ->
            val bound = minimum.toFiniteBigDecimalOrNull()
            when {
                bound == null -> collector.invalidSchema(path)
                value < bound -> collector.add(
                    path = path,
                    code = "minimum",
                    message = "Number is below the allowed minimum."
                )
            }
        }
        schema.maximum?.let { maximum ->
            val bound = maximum.toFiniteBigDecimalOrNull()
            when {
                bound == null -> collector.invalidSchema(path)
                value > bound -> collector.add(
                    path = path,
                    code = "maximum",
                    message = "Number is above the allowed maximum."
                )
            }
        }
        if (schema.minimum != null && schema.maximum != null && schema.minimum > schema.maximum) {
            collector.invalidSchema(path)
        }
    }

    private fun validateBoolean(
        element: JsonElement,
        path: String,
        collector: ViolationCollector
    ) {
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString || primitive.content !in BOOLEAN_LITERALS) {
            collector.typeMismatch(path, JSON_SCHEMA_BOOLEAN)
        }
    }

    private fun ToolDefinition.Parameters.toRootSchema(): ToolDefinition.Parameter = ToolDefinition.Parameter(
        type = type,
        description = description,
        properties = properties,
        required = required,
        additionalProperties = additionalProperties
    )

    private fun JsonElement.toBigDecimalOrNull(): BigDecimal? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toBigDecimalOrNull()
    }

    private fun BigDecimal.isInteger(): Boolean = stripTrailingZeros().scale() <= 0

    private fun Double.toFiniteBigDecimalOrNull(): BigDecimal? =
        takeIf { value -> value.isFinite() }?.let(BigDecimal::valueOf)

    private class ViolationCollector(maxViolations: Int) {
        private val limit = maxViolations
        private val mutableViolations = mutableListOf<ToolArgumentViolation>()

        val isFull: Boolean
            get() = mutableViolations.size >= limit

        fun add(
            path: String,
            code: String,
            message: String
        ) {
            if (isFull) return
            mutableViolations += ToolArgumentViolation(
                path = path.take(MAX_PATH_CHARS),
                code = code,
                message = message.take(MAX_MESSAGE_CHARS)
            )
        }

        fun typeMismatch(path: String, expectedType: String) {
            add(
                path = path,
                code = "type_mismatch",
                message = "Expected JSON type $expectedType."
            )
        }

        fun invalidSchema(path: String) {
            add(
                path = path,
                code = "schema_invalid",
                message = "The tool schema contains invalid constraints."
            )
        }

        fun toResult(): ToolArgumentsValidationResult = ToolArgumentsValidationResult(mutableViolations.toList())
    }

    private fun String.child(name: String): String {
        val escaped = name
            .replace("~", "~0")
            .replace("/", "~1")
        return "$this/$escaped"
    }

    private companion object {
        private const val DEFAULT_MAX_VIOLATIONS = 8
        private const val MAX_ALLOWED_VIOLATIONS = 32
        private const val MAX_PATH_CHARS = 160
        private const val MAX_MESSAGE_CHARS = 200
        private const val ROOT_PATH = "$"
        private const val EMPTY_ARGUMENTS = "{}"
        private val BOOLEAN_LITERALS = setOf("true", "false")
    }
}

data class ToolArgumentsValidationResult(
    val violations: List<ToolArgumentViolation>
) {
    val isValid: Boolean
        get() = violations.isEmpty()
}

data class ToolArgumentViolation(
    val path: String,
    val code: String,
    val message: String
)
