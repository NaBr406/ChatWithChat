package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata

/**
 * The runtime tool catalog.
 *
 * Built-in providers are fixed at construction. MCP tools arrive at runtime from a server's
 * `tools/list`, so the registry holds an immutable [Snapshot] behind a volatile reference rather than
 * being an immutable object itself: [installDynamicProviders] validates a whole batch and then swaps one
 * reference. Any snapshot a reader observes is still immutable and fully validated — what changed is
 * that the registry's identity no longer implies its contents.
 *
 * A batch is all-or-nothing. One malformed tool from a server cannot half-install a catalog, and a
 * rejected batch leaves the previous snapshot untouched.
 */
class ToolRegistry private constructor(
    baseRegistrations: List<ToolRegistration>,
    @Suppress("UNUSED_PARAMETER") marker: Unit
) {
    private val base: Snapshot = Snapshot.create(baseRegistrations).getOrThrow()

    @Volatile
    private var current: Snapshot = base

    private val installLock = Any()

    val definitions: List<ToolDefinition>
        get() = current.definitions

    val catalog: List<ToolCatalogEntry>
        get() = current.catalog

    constructor(providers: List<ToolProvider>) : this(
        providers.map { provider -> provider.toRegistration() },
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

    /**
     * Replaces the dynamic half of the catalog with [providers].
     *
     * Returns a failure — never throws — when a provider carries an invalid definition or collides with
     * a built-in or another dynamic name, because the input originates from a third-party server. Pass an
     * empty list to drop every dynamic tool.
     */
    fun installDynamicProviders(providers: List<ToolProvider>): Result<Unit> = synchronized(installLock) {
        Snapshot.create(base.registrations + providers.map { provider -> provider.toRegistration() })
            .map { snapshot -> current = snapshot }
    }

    fun definitionFor(toolName: String): ToolDefinition? = current.registrationsByName[toolName]?.definition

    internal fun handlerFor(toolName: String): ToolHandler? = current.registrationsByName[toolName]?.handler

    fun providerFor(toolName: String): ToolProvider? = current.registrationsByName[toolName]?.provider

    fun catalogEntryFor(toolName: String): ToolCatalogEntry? = current.catalogByName[toolName]

    fun availableDefinitions(includeTool: (ToolDefinition) -> Boolean): List<ToolDefinition> =
        current.definitions.filter(includeTool)

    fun policyFor(toolName: String): ToolPolicy = providerFor(toolName)?.policy ?: ToolPolicy.default()

    fun securityPolicyFor(toolName: String): ToolSecurityPolicy =
        current.registrationsByName[toolName]?.securityPolicy ?: ToolSecurityPolicy.FailClosed

    fun permissionRequirementsFor(toolName: String): List<ToolPermissionRequirement> =
        catalogEntryFor(toolName)?.permissionRequirements.orEmpty()

    fun requestedRuntimePermissions(
        includeTool: (ToolDefinition) -> Boolean = { true }
    ): List<String> = current.catalog
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

    fun presentationArtifacts(result: ToolResult): List<ToolPresentationArtifact> =
        if (result.isError) {
            emptyList()
        } else {
            providerFor(result.name)?.presentationArtifacts(result).orEmpty()
        }

    internal fun validateSessionCall(call: ToolCall, sessionState: ToolExecutionSessionState): ToolResult? =
        providerFor(call.name)?.validateSessionCall(call, sessionState)

    internal fun recordSessionResult(result: ToolResult, sessionState: ToolExecutionSessionState) {
        providerFor(result.name)?.onSessionResult(result, sessionState)
    }

    internal suspend fun execute(
        call: ToolCall,
        config: ToolLoopConfig,
        executionContext: ToolExecutionContext
    ): ToolResult? {
        val registration = current.registrationsByName[call.name] ?: return null
        return registration.provider?.execute(call, config, executionContext)
            ?: registration.handler?.execute(call, config)
    }

    private class Snapshot private constructor(
        val registrations: List<ToolRegistration>,
        val registrationsByName: Map<String, ToolRegistration>,
        val definitions: List<ToolDefinition>,
        val catalog: List<ToolCatalogEntry>,
        val catalogByName: Map<String, ToolCatalogEntry>
    ) {
        companion object {
            fun create(registrations: List<ToolRegistration>): Result<Snapshot> = runCatching {
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
                val catalog = registrations.map { registration ->
                    ToolCatalogEntry(
                        definition = registration.definition,
                        settings = registration.provider?.settingsMetadata
                            ?: ToolSettingsMetadata(userVisible = false),
                        permissionRequirements = registration.provider?.permissionRequirements.orEmpty(),
                        securityPolicy = registration.securityPolicy,
                        discovery = registration.provider?.discoveryMetadata ?: ToolDiscoveryMetadata()
                    )
                }
                Snapshot(
                    registrations = registrations,
                    registrationsByName = registrations.associateBy { it.definition.name },
                    definitions = registrations.map { it.definition },
                    catalog = catalog,
                    catalogByName = catalog.associateBy { it.definition.name }
                )
            }
        }
    }
}

private fun ToolProvider.toRegistration(): ToolRegistration = ToolRegistration(
    definition = definition,
    handler = ToolHandler { call, config -> execute(call, config) },
    provider = this,
    securityPolicy = securityPolicy
)

private data class ToolRegistration(
    val definition: ToolDefinition,
    val handler: ToolHandler?,
    val provider: ToolProvider?,
    val securityPolicy: ToolSecurityPolicy
)

fun interface ToolHandler {
    suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult
}
