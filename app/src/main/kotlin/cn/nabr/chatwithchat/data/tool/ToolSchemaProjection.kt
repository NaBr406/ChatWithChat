package cn.nabr.chatwithchat.data.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal enum class ToolSchemaDialect(
    val supportsAdditionalProperties: Boolean
) {
    OPEN_AI(supportsAdditionalProperties = true),
    ANTHROPIC(supportsAdditionalProperties = true),
    GOOGLE(supportsAdditionalProperties = false),
    JSON_FALLBACK(supportsAdditionalProperties = true)
}

internal fun ToolDefinition.Parameters.toSchemaJson(dialect: ToolSchemaDialect): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    put(
        "properties",
        buildJsonObject {
            properties.toSortedMap().forEach { (name, schema) ->
                put(name, schema.toSchemaJson(dialect))
            }
        }
    )
    put(
        "required",
        buildJsonArray {
            required.distinct().forEach { name -> add(JsonPrimitive(name)) }
        }
    )
    if (dialect.supportsAdditionalProperties) {
        put("additionalProperties", JsonPrimitive(additionalProperties))
    }
    description?.let { value -> put("description", JsonPrimitive(value)) }
}

internal fun ToolDefinition.Parameter.toSchemaJson(dialect: ToolSchemaDialect): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    description?.let { value -> put("description", JsonPrimitive(value)) }
    if (properties.isNotEmpty()) {
        put(
            "properties",
            buildJsonObject {
                properties.toSortedMap().forEach { (name, schema) ->
                    put(name, schema.toSchemaJson(dialect))
                }
            }
        )
    }
    if (required.isNotEmpty()) {
        put(
            "required",
            buildJsonArray {
                required.distinct().forEach { name -> add(JsonPrimitive(name)) }
            }
        )
    }
    items?.let { schema -> put("items", schema.toSchemaJson(dialect)) }
    if (enumValues.isNotEmpty()) {
        put(
            "enum",
            buildJsonArray {
                enumValues.distinct().forEach { value -> add(JsonPrimitive(value)) }
            }
        )
    }
    if (dialect.supportsAdditionalProperties) {
        additionalProperties?.let { value -> put("additionalProperties", JsonPrimitive(value)) }
    }
    minimum?.let { value -> put("minimum", JsonPrimitive(value)) }
    maximum?.let { value -> put("maximum", JsonPrimitive(value)) }
    minLength?.let { value -> put("minLength", JsonPrimitive(value)) }
    maxLength?.let { value -> put("maxLength", JsonPrimitive(value)) }
    format
        ?.takeIf { value -> dialect.supportsFormat(value) }
        ?.let { value -> put("format", JsonPrimitive(value)) }
}

internal fun ToolDefinition.Parameters.isOpenAIStrictCompatible(): Boolean =
    type == JSON_SCHEMA_OBJECT &&
        !additionalProperties &&
        required.hasExactly(properties.keys) &&
        properties.values.all { schema -> schema.isOpenAIStrictCompatible() }

private fun ToolDefinition.Parameter.isOpenAIStrictCompatible(): Boolean = when (type) {
    JSON_SCHEMA_OBJECT ->
        additionalProperties == false &&
            required.hasExactly(properties.keys) &&
            properties.values.all { schema -> schema.isOpenAIStrictCompatible() }
    JSON_SCHEMA_ARRAY -> items?.isOpenAIStrictCompatible() == true
    JSON_SCHEMA_STRING,
    JSON_SCHEMA_INTEGER,
    JSON_SCHEMA_NUMBER,
    JSON_SCHEMA_BOOLEAN -> true
    else -> false
}

private fun List<String>.hasExactly(names: Set<String>): Boolean =
    size == distinct().size && toSet() == names

private fun ToolSchemaDialect.supportsFormat(format: String): Boolean = when (this) {
    ToolSchemaDialect.OPEN_AI -> format in OPEN_AI_STRING_FORMATS
    ToolSchemaDialect.ANTHROPIC,
    ToolSchemaDialect.GOOGLE,
    ToolSchemaDialect.JSON_FALLBACK -> true
}

private val OPEN_AI_STRING_FORMATS = setOf(
    "date-time",
    "time",
    "date",
    "duration",
    "email",
    "hostname",
    "ipv4",
    "ipv6",
    "uuid"
)

internal const val JSON_SCHEMA_OBJECT = "object"
internal const val JSON_SCHEMA_ARRAY = "array"
internal const val JSON_SCHEMA_STRING = "string"
internal const val JSON_SCHEMA_INTEGER = "integer"
internal const val JSON_SCHEMA_NUMBER = "number"
internal const val JSON_SCHEMA_BOOLEAN = "boolean"
