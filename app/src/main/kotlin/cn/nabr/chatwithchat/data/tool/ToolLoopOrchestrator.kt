package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.tool.provider.OpenAICompatibleJsonToolAdapter
import cn.nabr.chatwithchat.data.tool.provider.ToolCallingAdapter

class ToolLoopOrchestrator(
    private val toolExecutor: ToolExecutor,
    private val toolPromptBuilder: ToolPromptBuilder = ToolPromptBuilder(),
    private val jsonToolCallParser: JsonToolCallParser = JsonToolCallParser(),
    private val defaultToolCallingAdapter: ToolCallingAdapter = OpenAICompatibleJsonToolAdapter(
        toolPromptBuilder = toolPromptBuilder,
        jsonToolCallParser = jsonToolCallParser
    ),
    private val config: ToolLoopConfig = ToolLoopConfig.Default,
    private val toolApprovalBroker: ToolApprovalBroker? = null,
    private val toolScopePlanner: ToolScopePlanner = ToolScopePlanner()
) {
    val configuration: ToolLoopConfig
        get() = config

    val toolDefinitions: List<ToolDefinition>
        get() = toolExecutor.definitions

    val toolCatalog: List<ToolCatalogEntry>
        get() = toolExecutor.catalog

    fun availableToolDefinitions(includeTool: (ToolDefinition) -> Boolean): List<ToolDefinition> =
        toolExecutor.availableDefinitions(includeTool)

    fun sourceMetadata(results: List<ToolResult>): List<MessageSourceMetadata> =
        results.flatMap { result -> toolExecutor.sourceMetadata(result) }

    fun presentationArtifacts(results: List<ToolResult>): List<ToolPresentationArtifact> = results
        .flatMap { result -> toolExecutor.presentationArtifacts(result) }
        .filter { artifact -> artifact.instanceId.isNotBlank() }
        .distinctBy { artifact -> artifact.instanceId }

    fun createExecutionSession(
        chatId: Int = 0,
        turnKey: String? = null,
        supportsVisualInput: Boolean = true
    ): ToolLoopExecutionSession = ToolLoopExecutionSession(
        config = config,
        policyFor = toolExecutor::policyFor,
        chatId = chatId,
        turnKey = turnKey,
        supportsVisualInput = supportsVisualInput
    )

    fun createToolScope(
        activeToolDefinitions: List<ToolDefinition>,
        initialIntent: String = "",
        advertisementSizer: ToolAdvertisementSizer = ToolAdvertisementSizer.promptText
    ): ToolScope = toolScopePlanner.createScope(
        entries = activeToolEntries(activeToolDefinitions),
        initialIntent = initialIntent,
        advertisementSizer = advertisementSizer
    )

    fun boundToolCalls(calls: Iterable<ToolCall>): List<ToolCall> = calls.boundedDistinctToolCalls(config)

    suspend fun runLoop(
        tools: List<ToolDefinition>,
        adapter: ToolCallingAdapter = defaultToolCallingAdapter,
        onProgress: suspend (ApiState) -> Unit = {},
        chatId: Int = 0,
        turnKey: String? = null,
        requestModel: suspend (toolPrompt: String) -> Result<String>
    ): ToolLoopResult = runLoop(
        scope = createFullToolScope(tools),
        adapter = adapter,
        onProgress = onProgress,
        chatId = chatId,
        turnKey = turnKey,
        requestModel = requestModel
    )

    suspend fun runLoop(
        scope: ToolScope,
        adapter: ToolCallingAdapter = defaultToolCallingAdapter,
        onProgress: suspend (ApiState) -> Unit = {},
        chatId: Int = 0,
        turnKey: String? = null,
        requestModel: suspend (toolPrompt: String) -> Result<String>
    ): ToolLoopResult = runLoopWithRoundPolicy(
        scope = scope,
        adapter = adapter,
        onProgress = onProgress,
        chatId = chatId,
        turnKey = turnKey,
        requestModel = { toolPrompt, _ -> requestModel(toolPrompt) }
    )

    /**
     * [chatId] scopes conversation-level approval grants. Zero disables them, so a caller that cannot
     * identify the conversation keeps strict per-call approval rather than silently widening consent.
     */
    internal suspend fun runLoopWithRoundPolicy(
        scope: ToolScope,
        adapter: ToolCallingAdapter = defaultToolCallingAdapter,
        onProgress: suspend (ApiState) -> Unit = {},
        chatId: Int = 0,
        turnKey: String? = null,
        supportsVisualInput: Boolean = true,
        onToolResults: suspend (List<ToolResult>) -> Unit = {},
        requestModel: suspend (
            toolPrompt: String,
            reasoningPolicy: ToolRoundReasoningPolicy
        ) -> Result<String>
    ): ToolLoopResult {
        val maxRounds = config.maxToolRounds.coerceAtLeast(0)
        if (maxRounds == 0) return ToolLoopResult.Failed("tool_loop_no_rounds")
        if (scope.definitions.isEmpty()) return ToolLoopResult.Failed("tool_loop_no_available_tools")

        val scratchpad = mutableListOf<ToolMessage>()
        val allCalls = mutableListOf<ToolCall>()
        val allResults = mutableListOf<ToolResult>()
        var hadToolInteraction = false
        val executionSession = createExecutionSession(chatId, turnKey, supportsVisualInput)
        val roundStateMachine = ToolRoundStateMachine(scope.definitions)

        var allowedRounds = maxRounds
        var discoveryRounds = 0
        var roundIndex = 0
        while (roundIndex < allowedRounds) {
            roundStateMachine.updateAvailableDefinitions(executionSession.availableWithinBudget(scope.definitions))
            val roundState = roundStateMachine.current
            // No tools left to offer — either the sticker phase ended or the budget is spent — so this round
            // is already the final answer and its content is the answer, not a draft.
            val isFinalAnswerRound = roundState.isFinalOnly || roundState.definitions.isEmpty()
            val toolPrompt = if (isFinalAnswerRound) {
                adapter.buildFinalAnswerEnvelopePrompt(
                    scratchpad = scratchpad,
                    config = config
                )
            } else {
                adapter.buildToolPrompt(
                    tools = roundState.definitions,
                    scratchpad = scratchpad,
                    config = config
                )
            }
            val modelText = requestModel(toolPrompt, roundState.reasoningPolicy).getOrElse { throwable ->
                return fallbackOrFailure(
                    adapter = adapter,
                    allCalls = allCalls,
                    allResults = allResults,
                    failure = "tool_loop_model_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}",
                    hadToolInteraction = hadToolInteraction
                )
            }
            hadToolInteraction = hadToolInteraction || adapter.hasToolCallIntent(modelText)
            val modelOutput = adapter.parseModelOutput(modelText, config).getOrElse { throwable ->
                return fallbackOrFailure(
                    adapter = adapter,
                    allCalls = allCalls,
                    allResults = allResults,
                    failure = "tool_loop_parse_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}",
                    hadToolInteraction = hadToolInteraction
                )
            }

            when (modelOutput) {
                is JsonToolModelOutput.FinalAnswer -> {
                    return if (allResults.isNotEmpty()) {
                        if (isFinalAnswerRound || roundState.acceptsDirectFinalAnswer) {
                            ToolLoopResult.CompletedWithToolResults(
                                content = modelOutput.content,
                                calls = allCalls,
                                results = allResults
                            )
                        } else {
                            ToolLoopResult.ToolResults(
                                calls = allCalls,
                                results = allResults,
                                finalAnswerPrompt = buildFinalAnswerPrompt(
                                    adapter = adapter,
                                    results = allResults,
                                    draftFinalAnswer = modelOutput.content
                                )
                            )
                        }
                    } else {
                        ToolLoopResult.FinalAnswer(modelOutput.content)
                    }
                }
                is JsonToolModelOutput.ToolCalls -> {
                    hadToolInteraction = hadToolInteraction || modelOutput.calls.isNotEmpty()
                    val calls = boundToolCalls(modelOutput.calls)
                    if (calls.isEmpty()) {
                        return fallbackOrFailure(
                            adapter = adapter,
                            allCalls = allCalls,
                            allResults = allResults,
                            failure = "tool_loop_no_tool_calls",
                            hadToolInteraction = hadToolInteraction
                        )
                    }
                    val results = executeScopedToolCalls(
                        calls = calls,
                        scope = scope,
                        allowedToolNames = roundState.allowedToolNames,
                        executionSession = executionSession,
                        onProgress = onProgress
                    )
                    onToolResults(results)
                    allCalls += calls
                    allResults += results
                    roundStateMachine.onToolResults(results)
                    if (roundStateMachine.current.shouldCompactScratchpad) {
                        scratchpad.clear()
                    }
                    calls.forEach { call -> scratchpad += ToolMessage.modelToolCall(call) }
                    results.forEach { result -> scratchpad += ToolMessage.toolResult(result) }
                    if (results.hasSuccessfulToolDiscovery() &&
                        discoveryRounds < scope.maxDiscoveryRounds
                    ) {
                        allowedRounds += 1
                        discoveryRounds += 1
                    }
                }
            }
            roundIndex += 1
        }

        return fallbackOrFailure(
            adapter = adapter,
            allCalls = allCalls,
            allResults = allResults,
            failure = "tool_loop_max_rounds_reached",
            hadToolInteraction = hadToolInteraction
        )
    }

    suspend fun runSingleRound(
        tools: List<ToolDefinition>,
        requestModel: suspend (toolPrompt: String) -> Result<String>
    ): ToolLoopResult = runLoop(tools = tools, requestModel = requestModel)

    suspend fun executeToolCalls(
        calls: List<ToolCall>,
        tools: List<ToolDefinition>,
        executionSession: ToolLoopExecutionSession = createExecutionSession(),
        onProgress: suspend (ApiState) -> Unit = {}
    ): List<ToolResult> = executeBoundedToolCalls(
        calls = boundToolCalls(calls),
        tools = tools,
        executionSession = executionSession,
        onProgress = onProgress
    )

    internal suspend fun executeScopedToolCalls(
        calls: List<ToolCall>,
        scope: ToolScope,
        allowedToolNames: Set<String> = scope.advertisedToolNames,
        executionSession: ToolLoopExecutionSession = createExecutionSession(),
        onProgress: suspend (ApiState) -> Unit = {}
    ): List<ToolResult> {
        val activeToolNames = scope.advertisedToolNames.intersect(allowedToolNames)
        val availableCalls = calls.selectAvailable(activeToolNames) { call ->
            // A tool that stopped being advertised because its budget ran out is refused for that reason,
            // not for not existing.
            executionSession.exhaustionReason(call.name) ?: "tool_unavailable:${call.name}"
        }
        val (allowedCalls, budgetRejectedCalls) = executionSession.select(availableCalls.allowed)
        val rejectedCalls = availableCalls.rejected + budgetRejectedCalls
        rejectedCalls.forEach { rejected ->
            onProgress(ApiState.ToolFailed(rejected.name, rejected.content))
        }

        val scopeControlCalls = allowedCalls.filter { call -> call.name == ToolDefinition.DiscoverTools.name }
        val ordinaryCalls = allowedCalls - scopeControlCalls.toSet()
        val scopeResults = mutableMapOf<String, ToolResult>()
        scopeControlCalls.forEach { call ->
            scopeResults[call.id] = executeScopeControlCall(call, scope, onProgress)
        }
        val ordinaryResults = executeCallsWithProgress(
            calls = ordinaryCalls,
            activeToolNames = activeToolNames,
            sessionState = executionSession.state,
            onProgress = onProgress
        ).associateBy(ToolResult::callId)
        val rawResults = allowedCalls.map { call ->
            scopeResults[call.id] ?: ordinaryResults.getValue(call.id)
        } + rejectedCalls
        return executionSession.bound(rawResults).also { results ->
            results.forEach { result -> toolExecutor.recordSessionResult(result, executionSession.state) }
        }
    }

    internal suspend fun executeBoundedToolCalls(
        calls: List<ToolCall>,
        tools: List<ToolDefinition>,
        executionSession: ToolLoopExecutionSession = createExecutionSession(),
        onProgress: suspend (ApiState) -> Unit = {}
    ): List<ToolResult> {
        val activeToolNames = tools.map { tool -> tool.name }.toSet()
        val availableCalls = calls.selectAvailable(activeToolNames)
        val (allowedCalls, budgetRejectedCalls) = executionSession.select(availableCalls.allowed)
        val rejectedCalls = availableCalls.rejected + budgetRejectedCalls
        rejectedCalls.forEach { rejected ->
            onProgress(ApiState.ToolFailed(rejected.name, rejected.content))
        }
        val rawResults = executeCallsWithProgress(
            calls = allowedCalls,
            activeToolNames = activeToolNames,
            sessionState = executionSession.state,
            onProgress = onProgress
        ) + rejectedCalls
        return executionSession.bound(rawResults).also { results ->
            results.forEach { result -> toolExecutor.recordSessionResult(result, executionSession.state) }
        }
    }

    private suspend fun executeCallsWithProgress(
        calls: List<ToolCall>,
        activeToolNames: Set<String>,
        sessionState: ToolExecutionSessionState,
        onProgress: suspend (ApiState) -> Unit
    ): List<ToolResult> = calls.map { call ->
        val label = toolExecutor.progressLabel(call)
        onProgress(ApiState.ToolStarted(call.name, label))
        var result = toolExecutor.execute(
            call = call,
            activeToolNames = activeToolNames,
            config = config,
            sessionState = sessionState
        )
        if (result.metadata["error_code"] == ToolApprovalStatus.MISSING.recoverableErrorCode) {
            when (val decision = toolApprovalBroker?.awaitApproval(call, sessionState.chatId, sessionState.turnKey)) {
                is ToolApprovalBroker.Decision.WithContext -> {
                    result = toolExecutor.execute(
                        call = call,
                        activeToolNames = activeToolNames,
                        config = config,
                        executionContext = decision.context,
                        sessionState = sessionState
                    )
                }
                ToolApprovalBroker.Decision.Unavailable,
                null -> {}
            }
        }
        if (result.isError) {
            onProgress(ApiState.ToolFailed(call.name, result.content, result.metadata["error_code"]))
        } else {
            onProgress(ApiState.ToolFinished(call.name, label))
        }
        result
    }

    private fun createFullToolScope(activeToolDefinitions: List<ToolDefinition>): ToolScope = ToolScopePlanner(
        maxAdvertisedTools = Int.MAX_VALUE,
        maxAdvertisedSchemaChars = Int.MAX_VALUE,
        maxInitialOnDemandTools = 0,
        maxDiscoveryResults = 0,
        maxDiscoveryCalls = 0
    ).createScope(
        entries = activeToolEntries(activeToolDefinitions).map { entry ->
            entry.copy(
                discovery = entry.discovery.copy(
                    exposure = ToolExposure.Resident,
                    requiredCompanionToolNames = entry.discovery.requiredCompanionToolNames
                        .intersect(activeToolDefinitions.map { definition -> definition.name }.toSet())
                )
            )
        }
    )

    private fun activeToolEntries(activeToolDefinitions: List<ToolDefinition>): List<ToolCatalogEntry> {
        val catalogByName = toolCatalog.associateBy { entry -> entry.definition.name }
        return activeToolDefinitions
            .distinctBy { definition -> definition.name }
            .map { definition ->
                catalogByName[definition.name] ?: ToolCatalogEntry(
                    definition = definition,
                    settings = ToolSettingsMetadata(userVisible = false),
                    permissionRequirements = emptyList(),
                    securityPolicy = ToolSecurityPolicy.FailClosed
                )
            }
    }

    private suspend fun executeScopeControlCall(
        call: ToolCall,
        scope: ToolScope,
        onProgress: suspend (ApiState) -> Unit
    ): ToolResult {
        onProgress(ApiState.ToolStarted(call.name, DISCOVER_TOOLS_PROGRESS_LABEL))
        val result = scope.discover(call)
        if (result.isError) {
            onProgress(ApiState.ToolFailed(call.name, result.content, result.metadata["error_code"]))
        } else {
            onProgress(ApiState.ToolFinished(call.name, DISCOVER_TOOLS_PROGRESS_LABEL))
        }
        return result
    }

    private fun fallbackOrFailure(
        adapter: ToolCallingAdapter,
        allCalls: List<ToolCall>,
        allResults: List<ToolResult>,
        failure: String,
        hadToolInteraction: Boolean
    ): ToolLoopResult = if (allResults.isNotEmpty()) {
        ToolLoopResult.ToolResults(
            calls = allCalls,
            results = allResults,
            finalAnswerPrompt = buildFinalAnswerPrompt(adapter = adapter, results = allResults)
        )
    } else {
        ToolLoopResult.Failed(failure, hadToolInteraction)
    }

    private fun buildFinalAnswerPrompt(
        adapter: ToolCallingAdapter,
        results: List<ToolResult>,
        draftFinalAnswer: String? = null
    ): String? = adapter.buildFinalAnswerPrompt(results, draftFinalAnswer, config)
}

private fun List<ToolCall>.selectAvailable(
    activeToolNames: Set<String>,
    unavailableReason: (ToolCall) -> String = { call -> "tool_unavailable:${call.name}" }
): BudgetedToolCalls {
    val allowed = mutableListOf<ToolCall>()
    val rejected = mutableListOf<ToolResult>()

    forEach { call ->
        if (call.name in activeToolNames) {
            allowed += call
        } else {
            rejected += call.errorResult(unavailableReason(call))
        }
    }

    return BudgetedToolCalls(allowed, rejected)
}

private class ToolBudgetState(
    config: ToolLoopConfig,
    private val policyFor: (String) -> ToolPolicy
) {    private var remainingToolCalls = config.maxToolCallsPerChat.coerceAtLeast(0)
    private val remainingToolCallsByName = mutableMapOf<String, Int>()

    fun select(calls: List<ToolCall>): BudgetedToolCalls {
        val allowed = mutableListOf<ToolCall>()
        val rejected = mutableListOf<ToolResult>()
        val requestToolCallsByName = mutableMapOf<String, Int>()

        calls.forEach { call ->
            val policy = policyFor(call.name)
            val requestToolCalls = requestToolCallsByName[call.name] ?: 0
            val remainingPolicyCalls = remainingPolicyCalls(call.name, policy)
            val rejection = when {
                remainingToolCalls <= 0 -> "${TOOL_BUDGET_EXCEEDED_PREFIX}max_tool_calls_per_chat"
                policy.maxCallsPerRequest != null && requestToolCalls >= policy.maxCallsPerRequest.coerceAtLeast(0) ->
                    "$TOOL_BUDGET_EXCEEDED_PREFIX${policy.maxCallsPerRequestKey(call.name)}"
                remainingPolicyCalls != null && remainingPolicyCalls <= 0 ->
                    "$TOOL_BUDGET_EXCEEDED_PREFIX${policy.maxCallsPerChatKey(call.name)}"
                else -> null
            }

            if (rejection != null) {
                rejected += call.errorResult(rejection)
            } else {
                allowed += call
                remainingToolCalls -= 1
                requestToolCallsByName[call.name] = requestToolCalls + 1
                remainingPolicyCalls?.let { remaining ->
                    remainingToolCallsByName[call.name] = remaining - 1
                }
            }
        }

        return BudgetedToolCalls(allowed, rejected)
    }

    private fun remainingPolicyCalls(toolName: String, policy: ToolPolicy): Int? =
        policy.maxCallsPerChat?.coerceAtLeast(0)?.let { limit ->
            remainingToolCallsByName.getOrPut(toolName) { limit }
        }

    /** False once the shared per-request allowance is gone, whatever the individual tool policies say. */
    fun hasRemainingBudget(): Boolean = remainingToolCalls > 0

    /**
     * Why [toolName] can no longer run in this request, or null while it still can.
     *
     * Read before a round is built, not only when a call arrives: enforcing the budget purely at execution
     * time meant an exhausted tool stayed in the advertised list, so the model kept calling it, kept
     * collecting `tool_budget_exceeded`, and burned rounds against the round ceiling for nothing.
     *
     * Returning the reason rather than a bare boolean keeps the message honest once the tool stops being
     * advertised: a call that arrives anyway is refused for being out of budget, not for not existing.
     */
    fun exhaustionReason(toolName: String): String? {
        if (remainingToolCalls <= 0) return "${TOOL_BUDGET_EXCEEDED_PREFIX}max_tool_calls_per_chat"
        val policy = policyFor(toolName)
        val limit = policy.maxCallsPerChat?.coerceAtLeast(0) ?: return null
        if ((remainingToolCallsByName[toolName] ?: limit) > 0) return null
        return "$TOOL_BUDGET_EXCEEDED_PREFIX${policy.maxCallsPerChatKey(toolName)}"
    }

    private fun ToolPolicy.maxCallsPerRequestKey(toolName: String): String =
        maxCallsPerRequestErrorKey ?: "max_${toolName}_calls_per_request"

    private fun ToolPolicy.maxCallsPerChatKey(toolName: String): String =
        maxCallsPerChatErrorKey ?: "max_${toolName}_calls_per_chat"
}

private data class BudgetedToolCalls(
    val allowed: List<ToolCall>,
    val rejected: List<ToolResult>
)

private const val DISCOVER_TOOLS_PROGRESS_LABEL = "正在查找可用工具"

/** Marks a call the budget refused, so the UI can say so in words rather than echoing the code. */
internal const val TOOL_BUDGET_EXCEEDED_PREFIX = "tool_budget_exceeded:"

/** Marks a call refused because the request has no room left to carry its result back. */
internal const val TOOL_RESULT_BUDGET_EXHAUSTED = "tool_result_budget_exhausted"

class ToolLoopExecutionSession internal constructor(
    config: ToolLoopConfig,
    policyFor: (String) -> ToolPolicy,
    chatId: Int = 0,
    turnKey: String? = null,
    supportsVisualInput: Boolean = true
) {
    private val toolBudget = ToolBudgetState(config, policyFor)
    private val resultPayloadBudget = ToolResultPayloadBudget(config)
    internal val state = ToolExecutionSessionState(chatId, turnKey, supportsVisualInput)

    internal fun select(calls: List<ToolCall>): Pair<List<ToolCall>, List<ToolResult>> {
        val selection = toolBudget.select(calls)
        return selection.allowed to selection.rejected
    }

    /**
     * [definitions] minus anything the budget can no longer afford, so the model is only ever shown tools it
     * can actually call. Empty means the request's whole allowance is spent — either the call count or the
     * room left to carry results back.
     */
    internal fun availableWithinBudget(definitions: List<ToolDefinition>): List<ToolDefinition> =
        if (!toolBudget.hasRemainingBudget() || !resultPayloadBudget.hasRemainingBudget()) {
            emptyList()
        } else {
            definitions.filterNot { definition -> toolBudget.exhaustionReason(definition.name) != null }
        }

    internal fun exhaustionReason(toolName: String): String? = toolBudget.exhaustionReason(toolName)

    internal fun bound(results: List<ToolResult>): List<ToolResult> = resultPayloadBudget.bound(results)
}

private class ToolResultPayloadBudget(
    private val config: ToolLoopConfig,
    usedPayloadChars: Int = 0
) {
    private var remainingPayloadChars = (
        config.maxTotalToolResultChars.toLong() - usedPayloadChars.coerceAtLeast(0).toLong()
        ).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    /**
     * False once there is no room left to carry a result worth reading.
     *
     * Exposed so the loop can stop offering tools, the same way it does when the call budget runs out. Before
     * this, an exhausted result budget was invisible: calls kept being made and kept "succeeding" with empty
     * content, because `clipWithNotice(0)` has no room for its own truncation notice.
     */
    fun hasRemainingBudget(): Boolean = remainingPayloadChars >= MIN_USEFUL_RESULT_CHARS

    fun bound(results: List<ToolResult>): List<ToolResult> = results.map { result ->
        if (result.isError || remainingPayloadChars >= MIN_USEFUL_RESULT_CHARS) {
            val bounded = result.boundPayload(
                ToolResultBounds(
                    maxContentChars = config.maxToolResultChars.coerceAtLeast(0),
                    maxStructuredContentChars = config.maxToolResultChars.coerceAtLeast(0),
                    maxSourcePayloadChars = config.maxToolResultChars.coerceAtLeast(0),
                    maxMetadataChars = config.maxToolResultChars.coerceAtLeast(0),
                    maxTotalPayloadChars = remainingPayloadChars
                )
            ).result
            remainingPayloadChars = (remainingPayloadChars - bounded.payloadCharCount()).coerceAtLeast(0)
            bounded
        } else {
            // Saying so beats handing back an empty success the model cannot interpret.
            ToolResult(
                callId = result.callId,
                name = result.name,
                content = "$TOOL_RESULT_BUDGET_EXHAUSTED:${result.name}",
                isError = true,
                metadata = mapOf("error_code" to TOOL_RESULT_BUDGET_EXHAUSTED)
            )
        }
    }

    private companion object {
        /**
         * Below this much remaining room a result cannot carry its own truncation notice, let alone content,
         * so the call is refused instead of silently emptied.
         */
        private const val MIN_USEFUL_RESULT_CHARS = 512
    }
}
