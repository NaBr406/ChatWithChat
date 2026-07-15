package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata

class ToolRegistry private constructor(
    registrations: List<ToolRegistration>,
    @Suppress("UNUSED_PARAMETER") marker: Unit
) {
    private val registrationsByName: Map<String, ToolRegistration>

    val definitions: List<ToolDefinition>
    val catalog: List<ToolCatalogEntry>

    init {
        registrations.forEachIndexed { index, registration ->
            ToolDefinitionValidator.validate(registration.definition, index)
        }
        val duplicateNames = registrations
            .groupingBy { registration -> registration.definition.name }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()
        require(duplicateNames.isEmpty()) {
            "Duplicate tool provider name(s): ${duplicateNames.joinToString()}"
        }
        registrationsByName = registrations.associateBy { registration -> registration.definition.name }
        definitions = registrations.map { registration -> registration.definition }
        catalog = registrations.map { registration ->
            ToolCatalogEntry(
                definition = registration.definition,
                settings = registration.provider?.settingsMetadata ?: ToolSettingsMetadata(userVisible = false),
                permissionRequirements = registration.provider?.permissionRequirements.orEmpty(),
                securityPolicy = registration.securityPolicy
            )
        }
    }

    constructor(providers: List<ToolProvider>) : this(
        providers.map { provider ->
            ToolRegistration(
                definition = provider.definition,
                handler = ToolHandler { call, config -> provider.execute(call, config) },
                provider = provider,
                securityPolicy = provider.securityPolicy
            )
        },
        Unit
    )

    constructor(
        definitions: List<ToolDefinition>,
        handlers: Map<String, ToolHandler>
    ) : this(definitions, handlers, emptyMap())

    constructor(
        definitions: List<ToolDefinition>,
        handlers: Map<String, ToolHandler>,
        securityPolicies: Map<String, ToolSecurityPolicy>
    ) : this(
        definitions.map { definition ->
            ToolRegistration(
                definition = definition,
                handler = handlers[definition.name],
                provider = null,
                securityPolicy = securityPolicies[definition.name] ?: ToolSecurityPolicy.FailClosed
            )
        },
        Unit
    )

    fun definitionFor(toolName: String): ToolDefinition? = registrationsByName[toolName]?.definition

    internal fun handlerFor(toolName: String): ToolHandler? = registrationsByName[toolName]?.handler

    fun providerFor(toolName: String): ToolProvider? = registrationsByName[toolName]?.provider

    fun catalogEntryFor(toolName: String): ToolCatalogEntry? = catalog.firstOrNull { entry ->
        entry.definition.name == toolName
    }

    fun availableDefinitions(includeTool: (ToolDefinition) -> Boolean): List<ToolDefinition> = definitions.filter(includeTool)

    fun policyFor(toolName: String): ToolPolicy = providerFor(toolName)?.policy ?: ToolPolicy.default()

    fun securityPolicyFor(toolName: String): ToolSecurityPolicy =
        registrationsByName[toolName]?.securityPolicy ?: ToolSecurityPolicy.FailClosed

    fun permissionRequirementsFor(toolName: String): List<ToolPermissionRequirement> =
        catalogEntryFor(toolName)?.permissionRequirements.orEmpty()

    fun requestedRuntimePermissions(
        includeTool: (ToolDefinition) -> Boolean = { true }
    ): List<String> = catalog
        .filter { entry -> includeTool(entry.definition) }
        .flatMap { entry -> entry.permissionRequirements }
        .flatMap { requirement -> requirement.requestedPermissions() }
        .distinct()

    fun progressLabel(call: ToolCall): String = providerFor(call.name)
        ?.progressLabel(call)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: call.name

    fun sourceMetadata(result: ToolResult): List<MessageSourceMetadata> =
        providerFor(result.name)?.sourceMetadata(result).orEmpty()

    internal suspend fun execute(
        call: ToolCall,
        config: ToolLoopConfig,
        executionContext: ToolExecutionContext
    ): ToolResult? {
        val registration = registrationsByName[call.name] ?: return null
        return registration.provider?.execute(call, config, executionContext)
            ?: registration.handler?.execute(call, config)
    }
}

private data class ToolRegistration(
    val definition: ToolDefinition,
    val handler: ToolHandler?,
    val provider: ToolProvider?,
    val securityPolicy: ToolSecurityPolicy
)

fun interface ToolHandler {
    suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult
}
