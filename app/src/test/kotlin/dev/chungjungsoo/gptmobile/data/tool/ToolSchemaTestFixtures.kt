package dev.chungjungsoo.gptmobile.data.tool

internal fun complexSchemaToolDefinition(
    required: List<String> = listOf("mode", "options", "tags", "retries", "endpoint")
): ToolDefinition = ToolDefinition(
    name = "complex_tool",
    description = "Exercises the supported tool parameter schema.",
    parameters = ToolDefinition.Parameters(
        description = "Arguments for the complex tool.",
        properties = mapOf(
            "mode" to ToolDefinition.Parameter(
                type = "string",
                description = "Execution mode.",
                enumValues = listOf("safe", "fast"),
                minLength = 4,
                maxLength = 8
            ),
            "options" to ToolDefinition.Parameter(
                type = "object",
                description = "Nested execution options.",
                properties = mapOf(
                    "enabled" to ToolDefinition.Parameter(
                        type = "boolean",
                        description = "Whether the option is enabled."
                    ),
                    "threshold" to ToolDefinition.Parameter(
                        type = "number",
                        description = "A normalized threshold.",
                        minimum = 0.0,
                        maximum = 1.0
                    )
                ),
                required = listOf("enabled", "threshold"),
                additionalProperties = false
            ),
            "tags" to ToolDefinition.Parameter(
                type = "array",
                description = "Labels applied to the request.",
                items = ToolDefinition.Parameter(
                    type = "string",
                    description = "One request label.",
                    minLength = 1,
                    maxLength = 20
                )
            ),
            "retries" to ToolDefinition.Parameter(
                type = "integer",
                description = "Maximum retry count.",
                minimum = 0.0,
                maximum = 5.0
            ),
            "endpoint" to ToolDefinition.Parameter(
                type = "string",
                description = "Destination endpoint.",
                format = "uri"
            )
        ),
        required = required,
        additionalProperties = false
    )
)
