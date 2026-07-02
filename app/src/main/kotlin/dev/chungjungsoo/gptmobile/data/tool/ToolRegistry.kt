package dev.chungjungsoo.gptmobile.data.tool

class ToolRegistry private constructor(
    val definitions: List<ToolDefinition>,
    private val handlers: Map<String, ToolHandler>,
    private val providersByName: Map<String, ToolProvider>
) {
    constructor(providers: List<ToolProvider>) : this(
        definitions = providers.map { provider -> provider.definition },
        handlers = providers.associate { provider ->
            provider.definition.name to ToolHandler { call, config -> provider.execute(call, config) }
        },
        providersByName = providers.associateBy { provider -> provider.definition.name }
    )

    constructor(
        definitions: List<ToolDefinition>,
        handlers: Map<String, ToolHandler>
    ) : this(
        definitions = definitions,
        handlers = handlers,
        providersByName = emptyMap()
    )

    fun handlerFor(toolName: String): ToolHandler? = handlers[toolName]

    fun providerFor(toolName: String): ToolProvider? = providersByName[toolName]

    fun progressLabel(call: ToolCall): String = providerFor(call.name)
        ?.progressLabel(call)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: call.name
}

fun interface ToolHandler {
    suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult
}
