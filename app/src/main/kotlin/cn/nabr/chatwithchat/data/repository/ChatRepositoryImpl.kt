package cn.nabr.chatwithchat.data.repository

import android.content.Context
import androidx.room.withTransaction
import cn.nabr.chatwithchat.data.context.ContextBuilder
import cn.nabr.chatwithchat.data.context.ConversationContext
import cn.nabr.chatwithchat.data.context.ConversationTurn
import cn.nabr.chatwithchat.data.context.ProviderContextPolicy
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.dao.ChatPlatformModelV2Dao
import cn.nabr.chatwithchat.data.database.dao.ChatRoomDao
import cn.nabr.chatwithchat.data.database.dao.ChatRoomV2Dao
import cn.nabr.chatwithchat.data.database.dao.MessageDao
import cn.nabr.chatwithchat.data.database.dao.MessageV2Dao
import cn.nabr.chatwithchat.data.database.entity.ChatPlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.ChatRoom
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.Message
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.data.database.entity.safeDedupeKey
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.dto.ProviderUsage
import cn.nabr.chatwithchat.data.dto.anthropic.common.ImageContent as AnthropicImageContent
import cn.nabr.chatwithchat.data.dto.anthropic.common.ImageSource
import cn.nabr.chatwithchat.data.dto.anthropic.common.MediaType
import cn.nabr.chatwithchat.data.dto.anthropic.common.MessageContent as AnthropicMessageContent
import cn.nabr.chatwithchat.data.dto.anthropic.common.MessageRole
import cn.nabr.chatwithchat.data.dto.anthropic.common.TextContent as AnthropicTextContent
import cn.nabr.chatwithchat.data.dto.anthropic.request.AnthropicTool
import cn.nabr.chatwithchat.data.dto.anthropic.request.AnthropicToolChoice
import cn.nabr.chatwithchat.data.dto.anthropic.request.InputMessage
import cn.nabr.chatwithchat.data.dto.anthropic.request.MessageRequest
import cn.nabr.chatwithchat.data.dto.anthropic.request.ThinkingConfig
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentBlockType
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentDeltaResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentStartResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.ErrorResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageDeltaResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageStartResponseChunk
import cn.nabr.chatwithchat.data.dto.google.common.Content
import cn.nabr.chatwithchat.data.dto.google.common.Part
import cn.nabr.chatwithchat.data.dto.google.common.Role as GoogleRole
import cn.nabr.chatwithchat.data.dto.google.request.GenerateContentRequest
import cn.nabr.chatwithchat.data.dto.google.request.GenerationConfig
import cn.nabr.chatwithchat.data.dto.google.request.GoogleTool
import cn.nabr.chatwithchat.data.dto.google.request.GoogleToolConfig
import cn.nabr.chatwithchat.data.dto.google.response.GenerateContentResponse
import cn.nabr.chatwithchat.data.dto.groq.request.GroqChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.common.ImageContent as OpenAIImageContent
import cn.nabr.chatwithchat.data.dto.openai.common.ImageUrl
import cn.nabr.chatwithchat.data.dto.openai.common.MessageContent as OpenAIMessageContent
import cn.nabr.chatwithchat.data.dto.openai.common.Role as OpenAIRole
import cn.nabr.chatwithchat.data.dto.openai.common.TextContent as OpenAITextContent
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionStreamOptions
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionThinkingConfig
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionTool
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionToolChoice
import cn.nabr.chatwithchat.data.dto.openai.request.ChatMessage
import cn.nabr.chatwithchat.data.dto.openai.request.ReasoningConfig
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseContentPart
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputContent
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputItem
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputMessage
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseTool
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseToolChoice
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.dto.openai.response.FunctionCallArgumentsDeltaEvent
import cn.nabr.chatwithchat.data.dto.openai.response.FunctionCallArgumentsDoneEvent
import cn.nabr.chatwithchat.data.dto.openai.response.OutputItemDoneEvent
import cn.nabr.chatwithchat.data.dto.openai.response.OutputTextDeltaEvent
import cn.nabr.chatwithchat.data.dto.openai.response.OutputTextDoneEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ReasoningSummaryTextDeltaEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseCompletedEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseErrorEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseFailedEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesStreamEvent
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchScheduler
import cn.nabr.chatwithchat.data.model.ApiType
import cn.nabr.chatwithchat.data.model.ChatPlatformConfig
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.model.defaultReasoningMode
import cn.nabr.chatwithchat.data.model.isGptOssModel
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.GroqAPI
import cn.nabr.chatwithchat.data.network.NetworkClient
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.token.TokenUsageEstimator
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import cn.nabr.chatwithchat.data.tool.ToolArgumentStreamLimiter
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolCallingMode
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolEnablementOverrides
import cn.nabr.chatwithchat.data.tool.ToolEnablementResolver
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolLoopOrchestrator
import cn.nabr.chatwithchat.data.tool.ToolLoopResult
import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.appendToolProtocolFragment
import cn.nabr.chatwithchat.data.tool.maxToolProtocolResponseChars
import cn.nabr.chatwithchat.data.tool.provider.AnthropicNativeToolAdapter
import cn.nabr.chatwithchat.data.tool.provider.AnthropicToolAdapter
import cn.nabr.chatwithchat.data.tool.provider.GoogleNativeToolAdapter
import cn.nabr.chatwithchat.data.tool.provider.GoogleToolAdapter
import cn.nabr.chatwithchat.data.tool.provider.OpenAIChatCompletionsToolAdapter
import cn.nabr.chatwithchat.data.tool.provider.OpenAICompatibleJsonToolAdapter
import cn.nabr.chatwithchat.data.tool.provider.OpenAIResponsesToolAdapter
import cn.nabr.chatwithchat.data.tool.provider.ToolCallingAdapter
import cn.nabr.chatwithchat.data.tool.toolLimitErrorCodeOrNull
import cn.nabr.chatwithchat.data.tool.toolProtocolJson
import cn.nabr.chatwithchat.data.websearch.SearchDecision
import cn.nabr.chatwithchat.data.websearch.SearchDecisionService
import cn.nabr.chatwithchat.data.websearch.WebSearchMode
import cn.nabr.chatwithchat.util.AttachmentPayloadCache
import cn.nabr.chatwithchat.util.FileUtils
import cn.nabr.chatwithchat.util.isAssistantErrorMessage
import cn.nabr.chatwithchat.util.stripAssistantErrorNote
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ChatRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatRoomDao: ChatRoomDao,
    private val messageDao: MessageDao,
    private val chatRoomV2Dao: ChatRoomV2Dao,
    private val messageV2Dao: MessageV2Dao,
    private val chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
    private val settingRepository: SettingRepository,
    private val openAIAPI: OpenAIAPI,
    private val groqAPI: GroqAPI,
    private val anthropicAPI: AnthropicAPI,
    private val googleAPI: GoogleAPI,
    private val attachmentUploadCoordinator: AttachmentUploadCoordinator,
    private val contextBuilder: ContextBuilder,
    private val toolLoopOrchestrator: ToolLoopOrchestrator,
    private val toolEnablementResolver: ToolEnablementResolver = ToolEnablementResolver(),
    private val searchDecisionService: SearchDecisionService? = null,
    private val openAIResponsesToolAdapter: OpenAIResponsesToolAdapter = OpenAIResponsesToolAdapter(),
    private val openAIChatCompletionsToolAdapter: OpenAIChatCompletionsToolAdapter = OpenAIChatCompletionsToolAdapter(),
    private val anthropicNativeToolAdapter: AnthropicNativeToolAdapter = AnthropicNativeToolAdapter(),
    private val googleNativeToolAdapter: GoogleNativeToolAdapter = GoogleNativeToolAdapter(),
    private val openAICompatibleJsonToolAdapter: ToolCallingAdapter = OpenAICompatibleJsonToolAdapter(),
    private val anthropicToolAdapter: ToolCallingAdapter = AnthropicToolAdapter(),
    private val googleToolAdapter: ToolCallingAdapter = GoogleToolAdapter(),
    private val memoryTurnBatchScheduler: MemoryTurnBatchScheduler? = null,
    private val chatDatabaseV2: ChatDatabaseV2? = null
) : ChatRepository {

    private fun isImageFile(extension: String): Boolean = extension in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "svg")

    private fun isDocumentFile(extension: String): Boolean = extension in setOf("pdf", "txt", "doc", "docx", "xls", "xlsx")

    private fun getMimeType(extension: String): String = when (extension) {
        // Images
        "jpg", "jpeg" -> "image/jpeg"

        "png" -> "image/png"

        "gif" -> "image/gif"

        "bmp" -> "image/bmp"

        "webp" -> "image/webp"

        "tiff" -> "image/tiff"

        "svg" -> "image/svg+xml"

        // Documents
        "pdf" -> "application/pdf"

        "txt" -> "text/plain"

        "doc" -> "application/msword"

        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        "xls" -> "application/vnd.ms-excel"

        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        else -> "application/octet-stream"
    }

    override suspend fun completeChat(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode
    ): Flow<ApiState> {
        val toolCallingMode = runCatching { settingRepository.fetchToolCallingMode() }
            .getOrNull()
            ?: ToolCallingMode.Off
        val webSearchMode = runCatching { settingRepository.fetchWebSearchMode() }
            .getOrNull()
            ?: WebSearchMode.Off
        val toolEnablementOverrides = runCatching { settingRepository.fetchToolEnablementOverrides() }.getOrNull()
        val activeToolDefinitions = toolEnablementOverrides
            ?.let { overrides -> activeToolDefinitions(toolCallingMode, webSearchMode, overrides) }
            .orEmpty()
        return if (activeToolDefinitions.isNotEmpty()) {
            when (platform.compatibleType) {
                ClientType.OPENAI -> completeChatWithOpenAIResponsesNativeToolLoop(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    activeToolDefinitions = activeToolDefinitions
                )
                ClientType.OPENROUTER -> completeChatWithOpenAIChatCompletionsNativeToolLoop(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    activeToolDefinitions = activeToolDefinitions
                )
                ClientType.ANTHROPIC -> completeChatWithAnthropicNativeToolLoop(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    activeToolDefinitions = activeToolDefinitions
                )
                ClientType.GOOGLE -> completeChatWithGoogleNativeToolLoop(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    activeToolDefinitions = activeToolDefinitions
                )
                else -> completeChatWithToolLoopFallback(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    activeToolDefinitions = activeToolDefinitions
                )
            }
        } else {
            completeChatByProvider(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
        }
    }

    private suspend fun activeToolDefinitions(
        toolCallingMode: ToolCallingMode,
        webSearchMode: WebSearchMode,
        toolEnablementOverrides: ToolEnablementOverrides
    ): List<ToolDefinition> {
        if (toolCallingMode != ToolCallingMode.Auto) return emptyList()

        val enabledToolNames = toolEnablementResolver.enabledToolNames(
            catalog = toolLoopOrchestrator.toolCatalog,
            overrides = toolEnablementOverrides
        )

        val webSearchToolsAvailable = webSearchMode == WebSearchMode.Auto &&
            runCatching { settingRepository.fetchWebSearchSearxngBaseUrl().trim().isNotBlank() }
                .getOrDefault(false)

        return toolLoopOrchestrator.availableToolDefinitions { definition ->
            definition.name in enabledToolNames &&
                when (definition.name) {
                    ToolDefinition.WebSearch.name,
                    ToolDefinition.FetchUrl.name -> webSearchToolsAvailable
                    else -> true
                }
        }
    }

    private suspend fun completeChatByProvider(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        extraPrompt: String? = null,
        emitEstimatedUsageWithoutOutput: Boolean = false
    ): Flow<ApiState> = when (platform.compatibleType) {
        ClientType.OPENAI -> {
            // Use Responses API for OpenAI (supports reasoning/thinking)
            completeChatWithOpenAIResponses(
                userMessages,
                assistantMessages,
                platform,
                memoryPrompt,
                reasoningMode,
                extraPrompt,
                emitEstimatedUsageWithoutOutput
            )
        }

        ClientType.GROQ -> {
            completeChatWithGroq(
                userMessages,
                assistantMessages,
                platform,
                memoryPrompt,
                reasoningMode,
                extraPrompt,
                emitEstimatedUsageWithoutOutput
            )
        }

        ClientType.OLLAMA, ClientType.OPENROUTER, ClientType.CUSTOM -> {
            // Use Chat Completions API for OpenAI-compatible services
            completeChatWithOpenAIChatCompletions(
                userMessages,
                assistantMessages,
                platform,
                memoryPrompt,
                reasoningMode,
                extraPrompt,
                emitEstimatedUsageWithoutOutput
            )
        }

        ClientType.ANTHROPIC -> {
            completeChatWithAnthropic(
                userMessages,
                assistantMessages,
                platform,
                memoryPrompt,
                reasoningMode,
                extraPrompt,
                emitEstimatedUsageWithoutOutput
            )
        }

        ClientType.GOOGLE -> {
            completeChatWithGoogle(
                userMessages,
                assistantMessages,
                platform,
                memoryPrompt,
                reasoningMode,
                extraPrompt,
                emitEstimatedUsageWithoutOutput
            )
        }
    }

    private suspend fun completeChatWithToolLoopFallback(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        activeToolDefinitions: List<ToolDefinition>
    ): Flow<ApiState> = flow {
        emit(ApiState.Loading)
        val searchDecisionExecution = executeSearchDecisionIfNeeded(
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            platform = platform,
            activeToolDefinitions = activeToolDefinitions
        )
        if (searchDecisionExecution != null) {
            emitSearchDecisionFinalStates(
                completeChatByProvider(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    extraPrompt = searchDecisionExecution.finalAnswerPrompt
                ),
                searchDecisionExecution.usage
            )
            return@flow
        }

        val config = toolLoopOrchestrator.configuration
        val toolCallingAdapter = toolCallingAdapterFor(platform.compatibleType)
        val toolUsageRecords = mutableListOf<TokenUsageRecord>()
        val loopResult = toolLoopOrchestrator.runLoop(
            adapter = toolCallingAdapter,
            onProgress = { progress -> emit(progress) },
            tools = activeToolDefinitions,
            requestModel = { toolPrompt ->
                collectProviderText(
                    states = completeChatByProvider(
                        userMessages = userMessages,
                        assistantMessages = assistantMessages,
                        platform = platform,
                        memoryPrompt = memoryPrompt,
                        reasoningMode = reasoningMode,
                        extraPrompt = toolPrompt,
                        emitEstimatedUsageWithoutOutput = true
                    ),
                    maxChars = config.maxToolProtocolResponseChars(),
                    onThinking = { thinkingChunk -> emit(ApiState.Thinking(thinkingChunk)) },
                    onUsage = { usage -> toolUsageRecords += usage }
                )
            }
        )

        when (loopResult) {
            is ToolLoopResult.FinalAnswer -> {
                loopResult.content.takeIf { it.isNotBlank() }?.let { content ->
                    emit(ApiState.Success(content))
                }
                toolUsageRecords.lastOrNull()?.let { usage -> emit(ApiState.UsageUpdated(usage)) }
                emit(ApiState.Done)
            }
            is ToolLoopResult.ToolResults -> {
                toolLoopOrchestrator.sourceMetadata(loopResult.results)
                    .dedupeMessageSources()
                    .takeIf { it.isNotEmpty() }?.let { sources ->
                        emit(ApiState.SourcesUpdated(sources))
                    }
                var finalAnswerUsage: TokenUsageRecord? = null
                var hasVisibleFinalAnswer = false
                var isDone = false
                completeChatByProvider(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    extraPrompt = loopResult.finalAnswerPrompt,
                    emitEstimatedUsageWithoutOutput = true
                ).collect { state ->
                    when (state) {
                        is ApiState.Loading -> {}
                        is ApiState.UsageUpdated -> {
                            finalAnswerUsage = state.usage
                            toolUsageRecords += state.usage
                        }
                        is ApiState.Success -> {
                            hasVisibleFinalAnswer = hasVisibleFinalAnswer || state.textChunk.isNotBlank()
                            emit(state)
                        }
                        ApiState.Done -> isDone = true
                        else -> emit(state)
                    }
                }
                aggregateToolUsage(
                    currentAnswerUsage = finalAnswerUsage.takeIf { hasVisibleFinalAnswer },
                    toolUsages = toolUsageRecords
                )?.let { usage -> emit(ApiState.UsageUpdated(usage)) }
                if (isDone) emit(ApiState.Done)
            }
            is ToolLoopResult.Failed -> {
                val limitErrorCode = loopResult.message.toolLimitErrorCodeOrNull()
                if (limitErrorCode != null) {
                    emit(ApiState.Error(limitErrorCode))
                    if (loopResult.hadToolInteraction) {
                        aggregateToolUsage(currentAnswerUsage = null, toolUsages = toolUsageRecords)?.let { usage ->
                            emit(ApiState.UsageUpdated(usage))
                        }
                    }
                    emit(ApiState.Done)
                } else if (loopResult.hadToolInteraction) {
                    emitToolAggregatedProviderStates(
                        states = completeChatByProvider(
                            userMessages = userMessages,
                            assistantMessages = assistantMessages,
                            platform = platform,
                            memoryPrompt = memoryPrompt,
                            reasoningMode = reasoningMode,
                            emitEstimatedUsageWithoutOutput = true
                        ),
                        initialToolUsages = toolUsageRecords
                    )
                } else {
                    emitProviderStatesSkippingLoading(
                        completeChatByProvider(
                            userMessages = userMessages,
                            assistantMessages = assistantMessages,
                            platform = platform,
                            memoryPrompt = memoryPrompt,
                            reasoningMode = reasoningMode
                        )
                    )
                }
            }
        }
    }.catch { e ->
        if (e is CancellationException) throw e
        emit(ApiState.Error(e.message ?: "鏈煡閿欒"))
        emit(ApiState.Done)
    }

    private fun toolCallingAdapterFor(clientType: ClientType): ToolCallingAdapter = when (clientType) {
        ClientType.ANTHROPIC -> anthropicToolAdapter
        ClientType.GOOGLE -> googleToolAdapter
        ClientType.OPENAI,
        ClientType.GROQ,
        ClientType.OLLAMA,
        ClientType.OPENROUTER,
        ClientType.CUSTOM -> openAICompatibleJsonToolAdapter
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ApiState>.executeSearchDecisionIfNeeded(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        activeToolDefinitions: List<ToolDefinition>
    ): SearchDecisionExecution? {
        if (activeToolDefinitions.none { definition -> definition.name == ToolDefinition.WebSearch.name }) {
            return null
        }

        val outcome = runCatching {
            searchDecisionService?.decideWithUsage(
                platform = platform,
                latestUserMessage = userMessages.lastOrNull()?.content.orEmpty(),
                recentContext = searchDecisionRecentContext(userMessages, assistantMessages, platform),
                runtimeContext = currentRuntimeContextPrompt()
            )
        }.getOrNull()
            ?.takeIf { it.decision.shouldSearch }
            ?: return null

        val calls = outcome.decision.toWebSearchToolCalls()
        if (calls.isEmpty()) return null

        val results = toolLoopOrchestrator.executeToolCalls(
            calls = calls,
            tools = activeToolDefinitions
        ) { progress -> emit(progress) }
        toolLoopOrchestrator.sourceMetadata(results)
            .dedupeMessageSources()
            .takeIf { it.isNotEmpty() }?.let { sources ->
                emit(ApiState.SourcesUpdated(sources))
            }

        val finalAnswerPrompt = openAICompatibleJsonToolAdapter.buildFinalAnswerPrompt(
            results = results,
            draftFinalAnswer = null,
            config = toolLoopOrchestrator.configuration
        ) ?: return null
        val usage = outcome.usage ?: return null
        return SearchDecisionExecution(finalAnswerPrompt = finalAnswerPrompt, usage = usage)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ApiState>.emitSearchDecisionFinalStates(
        states: Flow<ApiState>,
        decisionUsage: TokenUsageRecord
    ) = emitToolAggregatedProviderStates(states, listOf(decisionUsage))

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ApiState>.emitToolAggregatedProviderStates(
        states: Flow<ApiState>,
        initialToolUsages: List<TokenUsageRecord>
    ) {
        val toolUsages = initialToolUsages.toMutableList()
        var finalAnswerUsage: TokenUsageRecord? = null
        var hasVisibleFinalAnswer = false
        var isDone = false
        states.collect { state ->
            when (state) {
                is ApiState.Loading -> {}
                is ApiState.UsageUpdated -> {
                    finalAnswerUsage = state.usage
                    toolUsages += state.usage
                }
                is ApiState.Success -> {
                    hasVisibleFinalAnswer = hasVisibleFinalAnswer || state.textChunk.isNotBlank()
                    emit(state)
                }
                ApiState.Done -> isDone = true
                else -> emit(state)
            }
        }
        aggregateToolUsage(finalAnswerUsage.takeIf { hasVisibleFinalAnswer }, toolUsages)?.let { usage ->
            emit(ApiState.UsageUpdated(usage))
        }
        if (isDone) emit(ApiState.Done)
    }

    private fun searchDecisionRecentContext(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2
    ): String? {
        val recentTurns = userMessages
            .dropLast(1)
            .takeLast(2)
            .mapIndexedNotNull { offset, userMessage ->
                val absoluteIndex = (userMessages.size - 1 - minOf(2, userMessages.size - 1)) + offset
                val assistantMessage = assistantMessages
                    .getOrNull(absoluteIndex)
                    ?.firstOrNull { message -> message.platformType == platform.uid }
                buildString {
                    userMessage.content.trim().takeIf { it.isNotBlank() }?.let { content ->
                        append("User: ")
                        appendLine(content.take(400))
                    }
                    assistantMessage?.sendableAssistantContent()?.trim()?.takeIf { it.isNotBlank() }?.let { content ->
                        append("Assistant: ")
                        appendLine(content.take(400))
                    }
                }.trim().takeIf { it.isNotBlank() }
            }

        return recentTurns.joinToString(separator = "\n\n").takeIf { it.isNotBlank() }
    }

    private suspend fun collectProviderText(
        states: Flow<ApiState>,
        maxChars: Int = Int.MAX_VALUE,
        onThinking: suspend (String) -> Unit = {},
        onUsage: (TokenUsageRecord) -> Unit = {}
    ): Result<String> =
        try {
            val text = StringBuilder()
            var errorMessage: String? = null
            states.collect { state ->
                when (state) {
                    is ApiState.Thinking -> onThinking(state.thinkingChunk)
                    is ApiState.Success -> text.appendToolProtocolFragment(state.textChunk, maxChars)
                    is ApiState.Error -> errorMessage = errorMessage ?: state.message
                    is ApiState.UsageUpdated -> onUsage(state.usage)
                    else -> {}
                }
            }
            errorMessage?.let { throw IllegalStateException(it) }
            Result.success(text.toString())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ApiState>.emitProviderStatesSkippingLoading(states: Flow<ApiState>) {
        states.collect { state ->
            if (state !is ApiState.Loading) {
                emit(state)
            }
        }
    }

    private suspend fun completeChatWithOpenAIResponsesNativeToolLoop(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        activeToolDefinitions: List<ToolDefinition>
    ): Flow<ApiState> = flow {
        emit(ApiState.Loading)
        openAIAPI.setToken(platform.token)
        openAIAPI.setAPIUrl(platform.apiUrl)

        val prepared = withContext(Dispatchers.Default) {
            val reasoningParameters = mapReasoningMode(platform, reasoningMode)
            val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
            val inputMessages = buildResponsesInputMessages(conversationContext.turns, platform.uid)
            OpenAIResponsesNativeToolRequest(
                model = platform.model,
                input = inputMessages,
                instructions = mergePromptSections(
                    platform.systemPrompt,
                    currentRuntimeContextPrompt(),
                    memoryPrompt,
                    conversationContext.summary,
                    openAINativeToolInstruction(activeToolDefinitions)
                ),
                temperature = if (reasoningParameters.hasExplicitReasoning) null else platform.temperature,
                topP = if (reasoningParameters.hasExplicitReasoning) null else platform.topP,
                reasoning = reasoningParameters.openAIEffort?.let { effort ->
                    ReasoningConfig(
                        effort = effort,
                        summary = "auto"
                    )
                }
            )
        }

        val config = toolLoopOrchestrator.configuration
        val tools = openAIResponsesToolAdapter.toResponseTools(activeToolDefinitions)
        val continuationItems = mutableListOf<ResponseInputItem>()
        val allResults = mutableListOf<ToolResult>()
        val toolUsageRecords = mutableListOf<TokenUsageRecord>()
        var hasToolInteraction = false
        val toolExecutionSession = toolLoopOrchestrator.createExecutionSession()
        val maxRounds = config.maxToolRounds.coerceAtLeast(0)

        val searchDecisionExecution = executeSearchDecisionIfNeeded(
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            platform = platform,
            activeToolDefinitions = activeToolDefinitions
        )
        if (searchDecisionExecution != null) {
            emitSearchDecisionFinalStates(
                completeChatWithOpenAIResponses(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    extraPrompt = searchDecisionExecution.finalAnswerPrompt
                ),
                searchDecisionExecution.usage
            )
            return@flow
        }

        repeat(maxRounds) { roundIndex ->
            val round = collectOpenAIResponsesNativeRound(
                request = prepared.toRequest(
                    continuationItems = continuationItems,
                    tools = tools,
                    toolChoice = ResponseToolChoice.Auto
                ),
                timeoutSeconds = platform.timeout,
                platform = platform,
                label = "工具请求 ${roundIndex + 1}",
                config = config
            )
            round.usage?.let { toolUsageRecords += it }
            hasToolInteraction = hasToolInteraction || openAIResponsesToolAdapter.hasToolCallIntent(round.events)
            if (round.errorMessage != null) {
                if (!hasToolInteraction) {
                    emitProviderStatesSkippingLoading(
                        completeChatWithOpenAIResponses(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
                    )
                } else {
                    emit(ApiState.Error(round.errorMessage))
                    aggregateToolUsage(currentAnswerUsage = null, toolUsages = toolUsageRecords)?.let { usage ->
                        emit(ApiState.UsageUpdated(usage))
                    }
                    emit(ApiState.Done)
                }
                return@flow
            }

            val calls = toolLoopOrchestrator.boundToolCalls(
                openAIResponsesToolAdapter.toolCallsFromEvents(round.events, config)
            )
            if (calls.isEmpty()) {
                val usage = if (hasToolInteraction) {
                    aggregateToolUsage(round.usage, toolUsageRecords)
                } else {
                    round.usage
                }
                usage?.let { usage ->
                    emit(ApiState.UsageUpdated(usage))
                }
                emit(ApiState.Done)
                return@flow
            }
            hasToolInteraction = true

            val results = toolLoopOrchestrator.executeBoundedToolCalls(
                calls = calls,
                tools = activeToolDefinitions,
                executionSession = toolExecutionSession
            ) { progress -> emit(progress) }
            allResults += results
            toolLoopOrchestrator.sourceMetadata(allResults)
                .dedupeMessageSources()
                .takeIf { it.isNotEmpty() }?.let { sources ->
                    emit(ApiState.SourcesUpdated(sources))
                }
            continuationItems += openAIResponsesToolAdapter.continuationInputItems(round.events, calls, results, config)
        }

        if (continuationItems.isEmpty()) {
            emitProviderStatesSkippingLoading(
                completeChatWithOpenAIResponses(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
            )
            return@flow
        }

        val finalRound = collectOpenAIResponsesNativeRound(
            request = prepared.toRequest(
                continuationItems = continuationItems,
                tools = tools,
                toolChoice = ResponseToolChoice.None,
                extraInstruction = OPENAI_NATIVE_FINAL_TOOL_INSTRUCTION
            ),
            timeoutSeconds = platform.timeout,
            platform = platform,
            label = "工具最终回答",
            config = config
        )
        finalRound.usage?.let { toolUsageRecords += it }
        finalRound.errorMessage?.let { message ->
            emit(ApiState.Error(message))
        }
        aggregateToolUsage(finalRound.usage, toolUsageRecords)?.let { usage ->
            emit(ApiState.UsageUpdated(usage))
        }
        emit(ApiState.Done)
    }.catch { e ->
        if (e is CancellationException) throw e
        emit(ApiState.Error(e.message ?: "鏈煡閿欒"))
        emit(ApiState.Done)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ApiState>.collectOpenAIResponsesNativeRound(
        request: ResponsesRequest,
        timeoutSeconds: Int,
        platform: PlatformV2,
        label: String,
        config: ToolLoopConfig
    ): OpenAIResponsesNativeRound {
        val events = mutableListOf<ResponsesStreamEvent>()
        val argumentLimiter = ToolArgumentStreamLimiter(
            maxArgumentChars = config.maxToolArgumentChars,
            maxCallIdentities = config.maxToolCallsPerRound
        )
        var errorMessage: String? = null
        var emittedOutputTextDelta = false
        val outputText = StringBuilder()

        try {
            openAIAPI.streamResponses(request, timeoutSeconds).collect { event ->
                events += event
                when (event) {
                    is FunctionCallArgumentsDeltaEvent -> argumentLimiter.append(event.outputIndex, event.delta)
                    is FunctionCallArgumentsDoneEvent -> argumentLimiter.checkComplete(event.outputIndex, event.arguments)
                    is OutputItemDoneEvent -> if (event.item.type == OPENAI_FUNCTION_CALL_TYPE) {
                        argumentLimiter.checkComplete(event.outputIndex, event.item.arguments ?: "{}")
                    }
                    else -> {}
                }
                when (event) {
                    is ReasoningSummaryTextDeltaEvent -> emit(ApiState.Thinking(event.delta))

                    is OutputTextDeltaEvent -> {
                        emittedOutputTextDelta = true
                        outputText.append(event.delta)
                        emit(ApiState.Success(event.delta))
                    }

                    is OutputTextDoneEvent -> {
                        if (!emittedOutputTextDelta && event.text.isNotBlank()) {
                            emittedOutputTextDelta = true
                            outputText.append(event.text)
                            emit(ApiState.Success(event.text))
                        }
                    }

                    is ResponseFailedEvent -> {
                        errorMessage = event.response.error?.message ?: event.response.status ?: "鍝嶅簲澶辫触"
                    }

                    is ResponseErrorEvent -> {
                        errorMessage = event.message
                    }

                    else -> {}
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            errorMessage = error.message?.toolLimitErrorCodeOrNull() ?: throw error
        } catch (error: IOException) {
            errorMessage = error.message ?: "provider_stream_failed"
        }

        return OpenAIResponsesNativeRound(
            events = events,
            errorMessage = errorMessage,
            usage = openAIResponsesUsageFromEvents(
                events = events,
                request = request,
                outputText = outputText.toString().ifBlank { events.joinToString(separator = "\n") },
                platform = platform,
                label = label
            )
        )
    }

    private suspend fun completeChatWithOpenAIChatCompletionsNativeToolLoop(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        activeToolDefinitions: List<ToolDefinition>
    ): Flow<ApiState> = flow {
        emit(ApiState.Loading)
        openAIAPI.setToken(platform.token)
        openAIAPI.setAPIUrl(platform.apiUrl)

        val searchDecisionExecution = executeSearchDecisionIfNeeded(
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            platform = platform,
            activeToolDefinitions = activeToolDefinitions
        )
        if (searchDecisionExecution != null) {
            emitSearchDecisionFinalStates(
                completeChatWithOpenAIChatCompletions(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    extraPrompt = searchDecisionExecution.finalAnswerPrompt
                ),
                searchDecisionExecution.usage
            )
            return@flow
        }

        val prepared = withContext(Dispatchers.Default) {
            val reasoningParameters = mapReasoningMode(platform, reasoningMode)
            val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
            validateInlineBudgetIfNeeded(conversationContext.turns, platform)
            val messages = buildOpenAIChatMessages(
                conversationContext.turns,
                mergePromptSections(
                    platform.systemPrompt,
                    currentRuntimeContextPrompt(),
                    memoryPrompt,
                    conversationContext.summary,
                    openAINativeToolInstruction(activeToolDefinitions)
                )
            )
            val thinking = reasoningParameters.openAICompatibleThinkingType?.let { type ->
                ChatCompletionThinkingConfig(type)
            }
            OpenAIChatCompletionsNativeToolRequest(
                model = platform.model,
                messages = messages,
                temperature = platform.temperature.takeIf { thinking == null },
                topP = platform.topP.takeIf { thinking == null },
                reasoningEffort = reasoningParameters.openAICompatibleReasoningEffort,
                thinking = thinking
            )
        }

        val config = toolLoopOrchestrator.configuration
        val tools = openAIChatCompletionsToolAdapter.toChatCompletionTools(activeToolDefinitions)
        val continuationMessages = mutableListOf<ChatMessage>()
        val allResults = mutableListOf<ToolResult>()
        val toolUsageRecords = mutableListOf<TokenUsageRecord>()
        var hasToolInteraction = false
        val toolExecutionSession = toolLoopOrchestrator.createExecutionSession()
        val maxRounds = config.maxToolRounds.coerceAtLeast(0)

        repeat(maxRounds) { roundIndex ->
            val round = collectOpenAIChatCompletionsNativeRound(
                request = prepared.toRequest(
                    continuationMessages = continuationMessages,
                    tools = tools,
                    toolChoice = ChatCompletionToolChoice.Auto
                ),
                timeoutSeconds = platform.timeout,
                platform = platform,
                label = "工具请求 ${roundIndex + 1}",
                config = config
            )
            round.usage?.let { toolUsageRecords += it }
            hasToolInteraction = hasToolInteraction || openAIChatCompletionsToolAdapter.hasToolCallIntent(round.chunks)
            if (round.errorMessage != null) {
                if (!hasToolInteraction) {
                    emitProviderStatesSkippingLoading(
                        completeChatWithOpenAIChatCompletions(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
                    )
                } else {
                    emit(ApiState.Error(round.errorMessage))
                    aggregateToolUsage(currentAnswerUsage = null, toolUsages = toolUsageRecords)?.let { usage ->
                        emit(ApiState.UsageUpdated(usage))
                    }
                    emit(ApiState.Done)
                }
                return@flow
            }

            val calls = toolLoopOrchestrator.boundToolCalls(
                openAIChatCompletionsToolAdapter.toolCallsFromChunks(round.chunks, config)
            )
            if (calls.isEmpty()) {
                val usage = if (hasToolInteraction) {
                    aggregateToolUsage(round.usage, toolUsageRecords)
                } else {
                    round.usage
                }
                usage?.let { usage ->
                    emit(ApiState.UsageUpdated(usage))
                }
                emit(ApiState.Done)
                return@flow
            }
            hasToolInteraction = true

            val results = toolLoopOrchestrator.executeBoundedToolCalls(
                calls = calls,
                tools = activeToolDefinitions,
                executionSession = toolExecutionSession
            ) { progress -> emit(progress) }
            allResults += results
            toolLoopOrchestrator.sourceMetadata(allResults)
                .dedupeMessageSources()
                .takeIf { it.isNotEmpty() }?.let { sources ->
                    emit(ApiState.SourcesUpdated(sources))
                }
            continuationMessages += openAIChatCompletionsToolAdapter.continuationMessages(calls, results, config)
        }

        if (continuationMessages.isEmpty()) {
            emitProviderStatesSkippingLoading(
                completeChatWithOpenAIChatCompletions(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
            )
            return@flow
        }

        val finalRound = collectOpenAIChatCompletionsNativeRound(
            request = prepared.toRequest(
                continuationMessages = continuationMessages,
                tools = tools,
                toolChoice = ChatCompletionToolChoice.None,
                extraInstruction = OPENAI_NATIVE_FINAL_TOOL_INSTRUCTION
            ),
            timeoutSeconds = platform.timeout,
            platform = platform,
            label = "工具最终回答",
            config = config
        )
        finalRound.usage?.let { toolUsageRecords += it }
        finalRound.errorMessage?.let { message ->
            emit(ApiState.Error(message))
        }
        aggregateToolUsage(finalRound.usage, toolUsageRecords)?.let { usage ->
            emit(ApiState.UsageUpdated(usage))
        }
        emit(ApiState.Done)
    }.catch { e ->
        if (e is CancellationException) throw e
        emit(ApiState.Error(e.message ?: "鏈煡閿欒"))
        emit(ApiState.Done)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ApiState>.collectOpenAIChatCompletionsNativeRound(
        request: ChatCompletionRequest,
        timeoutSeconds: Int,
        platform: PlatformV2,
        label: String,
        config: ToolLoopConfig
    ): OpenAIChatCompletionsNativeRound {
        val chunks = mutableListOf<ChatCompletionChunk>()
        val argumentLimiter = ToolArgumentStreamLimiter(
            maxArgumentChars = config.maxToolArgumentChars,
            maxCallIdentities = config.maxToolCallsPerRound
        )
        var errorMessage: String? = null
        val outputText = StringBuilder()
        val reasoningParser = ReasoningStreamParser()

        try {
            openAIAPI.streamChatCompletion(request, timeoutSeconds).collect { chunk ->
                chunks += chunk
                chunk.choices.orEmpty().forEach { choice ->
                    choice.delta.toolCalls.orEmpty().forEachIndexed { position, call ->
                        argumentLimiter.register(choice.index to (call.index ?: position))
                        call.function?.arguments?.let { fragment ->
                            argumentLimiter.append(choice.index to (call.index ?: position), fragment)
                        }
                    }
                }
                when {
                    chunk.error != null -> errorMessage = chunk.error.message

                    else -> chunk.choices.orEmpty().forEach { choice ->
                        reasoningParser.append(
                            contentChunk = choice.delta.content,
                            reasoningChunk = choice.delta.reasoningContent
                                ?.takeIf { it.isNotEmpty() }
                                ?: choice.delta.reasoning
                        ).forEach { state ->
                            if (state is ApiState.Success) {
                                outputText.append(state.textChunk)
                            }
                            emit(state)
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            errorMessage = error.message?.toolLimitErrorCodeOrNull() ?: throw error
        } catch (error: IOException) {
            errorMessage = error.message ?: "provider_stream_failed"
        }

        reasoningParser.flush().forEach { state ->
            if (state is ApiState.Success) {
                outputText.append(state.textChunk)
            }
            emit(state)
        }

        return OpenAIChatCompletionsNativeRound(
            chunks = chunks,
            errorMessage = errorMessage,
            usage = openAIChatUsageFromChunks(
                chunks = chunks,
                request = request,
                outputText = outputText.toString().ifBlank { chunks.joinToString(separator = "\n") },
                platform = platform,
                label = label
            )
        )
    }

    private suspend fun completeChatWithAnthropicNativeToolLoop(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        activeToolDefinitions: List<ToolDefinition>
    ): Flow<ApiState> = flow {
        emit(ApiState.Loading)
        anthropicAPI.setToken(platform.token)
        anthropicAPI.setAPIUrl(platform.apiUrl)

        val searchDecisionExecution = executeSearchDecisionIfNeeded(
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            platform = platform,
            activeToolDefinitions = activeToolDefinitions
        )
        if (searchDecisionExecution != null) {
            emitSearchDecisionFinalStates(
                completeChatWithAnthropic(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    extraPrompt = searchDecisionExecution.finalAnswerPrompt
                ),
                searchDecisionExecution.usage
            )
            return@flow
        }

        val prepared = withContext(Dispatchers.Default) {
            val reasoningParameters = mapReasoningMode(platform, reasoningMode)
            val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
            val messages = buildAnthropicInputMessages(conversationContext.turns, platform.uid)
            AnthropicNativeToolRequest(
                model = platform.model,
                messages = messages,
                maxTokens = reasoningParameters.anthropicMaxTokens ?: 4096,
                systemPrompt = mergePromptSections(
                    platform.systemPrompt,
                    currentRuntimeContextPrompt(),
                    memoryPrompt,
                    conversationContext.summary,
                    openAINativeToolInstruction(activeToolDefinitions)
                ),
                temperature = if (reasoningParameters.hasExplicitReasoning) null else platform.temperature,
                topP = if (reasoningParameters.hasExplicitReasoning) null else platform.topP,
                thinking = reasoningParameters.anthropicBudgetTokens?.let { budgetTokens ->
                    ThinkingConfig(
                        type = "enabled",
                        budgetTokens = budgetTokens
                    )
                }
            )
        }

        val config = toolLoopOrchestrator.configuration
        val tools = anthropicNativeToolAdapter.toAnthropicTools(activeToolDefinitions)
        val continuationMessages = mutableListOf<InputMessage>()
        val allResults = mutableListOf<ToolResult>()
        val toolUsageRecords = mutableListOf<TokenUsageRecord>()
        var hasToolInteraction = false
        val toolExecutionSession = toolLoopOrchestrator.createExecutionSession()
        val maxRounds = config.maxToolRounds.coerceAtLeast(0)

        repeat(maxRounds) { roundIndex ->
            val round = collectAnthropicNativeRound(
                request = prepared.toRequest(
                    continuationMessages = continuationMessages,
                    tools = tools,
                    toolChoice = AnthropicToolChoice.Auto
                ),
                timeoutSeconds = platform.timeout,
                platform = platform,
                label = "工具请求 ${roundIndex + 1}",
                config = config
            )
            round.usage?.let { toolUsageRecords += it }
            hasToolInteraction = hasToolInteraction || anthropicNativeToolAdapter.hasToolCallIntent(round.chunks)
            if (round.errorMessage != null) {
                if (!hasToolInteraction) {
                    emitProviderStatesSkippingLoading(
                        completeChatWithAnthropic(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
                    )
                } else {
                    emit(ApiState.Error(round.errorMessage))
                    aggregateToolUsage(currentAnswerUsage = null, toolUsages = toolUsageRecords)?.let { usage ->
                        emit(ApiState.UsageUpdated(usage))
                    }
                    emit(ApiState.Done)
                }
                return@flow
            }

            val calls = toolLoopOrchestrator.boundToolCalls(
                anthropicNativeToolAdapter.toolCallsFromChunks(round.chunks, config)
            )
            if (calls.isEmpty()) {
                val usage = if (hasToolInteraction) {
                    aggregateToolUsage(round.usage, toolUsageRecords)
                } else {
                    round.usage
                }
                usage?.let { usage ->
                    emit(ApiState.UsageUpdated(usage))
                }
                emit(ApiState.Done)
                return@flow
            }
            hasToolInteraction = true

            val results = toolLoopOrchestrator.executeBoundedToolCalls(
                calls = calls,
                tools = activeToolDefinitions,
                executionSession = toolExecutionSession
            ) { progress -> emit(progress) }
            allResults += results
            toolLoopOrchestrator.sourceMetadata(allResults)
                .dedupeMessageSources()
                .takeIf { it.isNotEmpty() }?.let { sources ->
                    emit(ApiState.SourcesUpdated(sources))
                }
            continuationMessages += anthropicNativeToolAdapter.continuationMessages(calls, results, config)
        }

        if (continuationMessages.isEmpty()) {
            emitProviderStatesSkippingLoading(
                completeChatWithAnthropic(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
            )
            return@flow
        }

        val finalRound = collectAnthropicNativeRound(
            request = prepared.toRequest(
                continuationMessages = continuationMessages,
                tools = tools,
                toolChoice = AnthropicToolChoice.None,
                extraInstruction = OPENAI_NATIVE_FINAL_TOOL_INSTRUCTION
            ),
            timeoutSeconds = platform.timeout,
            platform = platform,
            label = "工具最终回答",
            config = config
        )
        finalRound.usage?.let { toolUsageRecords += it }
        finalRound.errorMessage?.let { message ->
            emit(ApiState.Error(message))
        }
        aggregateToolUsage(finalRound.usage, toolUsageRecords)?.let { usage ->
            emit(ApiState.UsageUpdated(usage))
        }
        emit(ApiState.Done)
    }.catch { e ->
        if (e is CancellationException) throw e
        emit(ApiState.Error(e.message ?: "鏈煡閿欒"))
        emit(ApiState.Done)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ApiState>.collectAnthropicNativeRound(
        request: MessageRequest,
        timeoutSeconds: Int,
        platform: PlatformV2,
        label: String,
        config: ToolLoopConfig
    ): AnthropicNativeRound {
        val chunks = mutableListOf<MessageResponseChunk>()
        val argumentLimiter = ToolArgumentStreamLimiter(
            maxArgumentChars = config.maxToolArgumentChars,
            maxCallIdentities = config.maxToolCallsPerRound
        )
        var errorMessage: String? = null
        val outputText = StringBuilder()

        try {
            anthropicAPI.streamChatMessage(request, timeoutSeconds).collect { chunk ->
                chunks += chunk
                when (chunk) {
                    is ContentStartResponseChunk -> if (chunk.contentBlock.type == ContentBlockType.TOOL_USE) {
                        argumentLimiter.checkComplete(chunk.index, chunk.contentBlock.input?.toString() ?: "{}")
                    }
                    is ContentDeltaResponseChunk -> if (chunk.delta.type == ContentBlockType.INPUT_JSON_DELTA) {
                        chunk.delta.partialJson?.let { fragment ->
                            argumentLimiter.append(chunk.index, fragment)
                        }
                    }
                    else -> {}
                }
                when (chunk) {
                    is ContentDeltaResponseChunk -> {
                        when (chunk.delta.type) {
                            ContentBlockType.THINKING_DELTA -> {
                                chunk.delta.thinking?.let { emit(ApiState.Thinking(it)) }
                            }

                            ContentBlockType.DELTA -> {
                                chunk.delta.text?.let {
                                    outputText.append(it)
                                    emit(ApiState.Success(it))
                                }
                            }

                            else -> {}
                        }
                    }

                    is ErrorResponseChunk -> {
                        errorMessage = chunk.error.message
                    }

                    else -> {}
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            errorMessage = error.message?.toolLimitErrorCodeOrNull() ?: throw error
        } catch (error: IOException) {
            errorMessage = error.message ?: "provider_stream_failed"
        }

        return AnthropicNativeRound(
            chunks = chunks,
            errorMessage = errorMessage,
            usage = anthropicUsageFromChunks(
                chunks = chunks,
                request = request,
                outputText = outputText.toString().ifBlank { chunks.joinToString(separator = "\n") },
                platform = platform,
                label = label
            )
        )
    }

    private suspend fun completeChatWithGoogleNativeToolLoop(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        activeToolDefinitions: List<ToolDefinition>
    ): Flow<ApiState> = flow {
        emit(ApiState.Loading)
        googleAPI.setToken(platform.token)
        googleAPI.setAPIUrl(platform.apiUrl)

        val searchDecisionExecution = executeSearchDecisionIfNeeded(
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            platform = platform,
            activeToolDefinitions = activeToolDefinitions
        )
        if (searchDecisionExecution != null) {
            emitSearchDecisionFinalStates(
                completeChatWithGoogle(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    extraPrompt = searchDecisionExecution.finalAnswerPrompt
                ),
                searchDecisionExecution.usage
            )
            return@flow
        }

        val prepared = withContext(Dispatchers.Default) {
            val reasoningParameters = mapReasoningMode(platform, reasoningMode)
            val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
            val contents = buildGoogleContents(conversationContext.turns, platform.uid)
            GoogleNativeToolRequest(
                contents = contents,
                generationConfig = GenerationConfig(
                    temperature = platform.temperature,
                    topP = platform.topP,
                    thinkingConfig = reasoningParameters.googleThinkingBudget?.let { thinkingBudget ->
                        cn.nabr.chatwithchat.data.dto.google.request.ThinkingConfig(
                            thinkingBudget = thinkingBudget,
                            includeThoughts = reasoningParameters.googleIncludeThoughts ?: false
                        )
                    }
                ),
                systemInstruction = mergePromptSections(
                    platform.systemPrompt,
                    currentRuntimeContextPrompt(),
                    memoryPrompt,
                    conversationContext.summary,
                    openAINativeToolInstruction(activeToolDefinitions)
                )?.let { prompt ->
                    Content(
                        parts = listOf(Part.text(prompt))
                    )
                }
            )
        }

        val config = toolLoopOrchestrator.configuration
        val tools = googleNativeToolAdapter.toGoogleTools(activeToolDefinitions)
        val continuationContents = mutableListOf<Content>()
        val allResults = mutableListOf<ToolResult>()
        val toolUsageRecords = mutableListOf<TokenUsageRecord>()
        var hasToolInteraction = false
        val toolExecutionSession = toolLoopOrchestrator.createExecutionSession()
        val maxRounds = config.maxToolRounds.coerceAtLeast(0)

        repeat(maxRounds) { roundIndex ->
            val round = collectGoogleNativeRound(
                request = prepared.toRequest(
                    continuationContents = continuationContents,
                    tools = tools,
                    toolConfig = GoogleToolConfig.Auto
                ),
                model = platform.model,
                timeoutSeconds = platform.timeout,
                platform = platform,
                label = "工具请求 ${roundIndex + 1}",
                config = config
            )
            round.usage?.let { toolUsageRecords += it }
            hasToolInteraction = hasToolInteraction || googleNativeToolAdapter.hasToolCallIntent(round.responses)
            if (round.errorMessage != null) {
                if (!hasToolInteraction) {
                    emitProviderStatesSkippingLoading(
                        completeChatWithGoogle(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
                    )
                } else {
                    emit(ApiState.Error(round.errorMessage))
                    aggregateToolUsage(currentAnswerUsage = null, toolUsages = toolUsageRecords)?.let { usage ->
                        emit(ApiState.UsageUpdated(usage))
                    }
                    emit(ApiState.Done)
                }
                return@flow
            }

            val calls = toolLoopOrchestrator.boundToolCalls(
                googleNativeToolAdapter.toolCallsFromResponses(round.responses, config)
            )
            if (calls.isEmpty()) {
                val usage = if (hasToolInteraction) {
                    aggregateToolUsage(round.usage, toolUsageRecords)
                } else {
                    round.usage
                }
                usage?.let { usage ->
                    emit(ApiState.UsageUpdated(usage))
                }
                emit(ApiState.Done)
                return@flow
            }
            hasToolInteraction = true

            val results = toolLoopOrchestrator.executeBoundedToolCalls(
                calls = calls,
                tools = activeToolDefinitions,
                executionSession = toolExecutionSession
            ) { progress -> emit(progress) }
            allResults += results
            toolLoopOrchestrator.sourceMetadata(allResults)
                .dedupeMessageSources()
                .takeIf { it.isNotEmpty() }?.let { sources ->
                    emit(ApiState.SourcesUpdated(sources))
                }
            continuationContents += googleNativeToolAdapter.continuationContents(calls, results, config)
        }

        if (continuationContents.isEmpty()) {
            emitProviderStatesSkippingLoading(
                completeChatWithGoogle(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
            )
            return@flow
        }

        val finalRound = collectGoogleNativeRound(
            request = prepared.toRequest(
                continuationContents = continuationContents,
                tools = tools,
                toolConfig = GoogleToolConfig.None,
                extraInstruction = OPENAI_NATIVE_FINAL_TOOL_INSTRUCTION
            ),
            model = platform.model,
            timeoutSeconds = platform.timeout,
            platform = platform,
            label = "工具最终回答",
            config = config
        )
        finalRound.usage?.let { toolUsageRecords += it }
        finalRound.errorMessage?.let { message ->
            emit(ApiState.Error(message))
        }
        aggregateToolUsage(finalRound.usage, toolUsageRecords)?.let { usage ->
            emit(ApiState.UsageUpdated(usage))
        }
        emit(ApiState.Done)
    }.catch { e ->
        if (e is CancellationException) throw e
        emit(ApiState.Error(e.message ?: "鏈煡閿欒"))
        emit(ApiState.Done)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ApiState>.collectGoogleNativeRound(
        request: GenerateContentRequest,
        model: String,
        timeoutSeconds: Int,
        platform: PlatformV2,
        label: String,
        config: ToolLoopConfig
    ): GoogleNativeRound {
        val responses = mutableListOf<GenerateContentResponse>()
        val argumentLimiter = ToolArgumentStreamLimiter(
            maxArgumentChars = config.maxToolArgumentChars,
            maxCallIdentities = config.maxToolCallsPerRound
        )
        var errorMessage: String? = null
        val outputText = StringBuilder()
        var functionCallIndex = 0

        try {
            googleAPI.streamGenerateContent(request, model, timeoutSeconds).collect { response ->
                responses += response
                response.candidates.orEmpty().forEach { candidate ->
                    candidate.content.parts.forEach { part ->
                        part.functionCall?.let { call ->
                            argumentLimiter.checkComplete(functionCallIndex, call.args.toString())
                            functionCallIndex += 1
                        }
                    }
                }
                when {
                    response.error != null -> errorMessage = response.error.message

                    response.candidates?.firstOrNull()?.content?.parts != null -> {
                        val parts = response.candidates.first().content.parts
                        parts.forEach { part ->
                            part.text?.let { text ->
                                if (part.thought == true) {
                                    emit(ApiState.Thinking(text))
                                } else {
                                    outputText.append(text)
                                    emit(ApiState.Success(text))
                                }
                            }
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            errorMessage = error.message?.toolLimitErrorCodeOrNull() ?: throw error
        } catch (error: IOException) {
            errorMessage = error.message ?: "provider_stream_failed"
        }

        return GoogleNativeRound(
            responses = responses,
            errorMessage = errorMessage,
            usage = googleUsageFromResponses(
                responses = responses,
                request = request,
                outputText = outputText.toString().ifBlank { responses.joinToString(separator = "\n") },
                platform = platform,
                label = label
            )
        )
    }

    private suspend fun completeChatWithOpenAIResponses(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        extraPrompt: String? = null,
        emitEstimatedUsageWithoutOutput: Boolean = false
    ): Flow<ApiState> = try {
        openAIAPI.setToken(platform.token)
        openAIAPI.setAPIUrl(platform.apiUrl)

        streamPreparedApiState(
            prepare = {
                val reasoningParameters = mapReasoningMode(platform, reasoningMode)
                val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
                val inputMessages = buildResponsesInputMessages(conversationContext.turns, platform.uid)

                ProviderRequestWithSources(
                    request = ResponsesRequest(
                        model = platform.model,
                        input = inputMessages,
                        stream = true,
                        instructions = mergePromptSections(platform.systemPrompt, currentRuntimeContextPrompt(), memoryPrompt, conversationContext.summary, extraPrompt),
                        temperature = if (reasoningParameters.hasExplicitReasoning) null else platform.temperature,
                        topP = if (reasoningParameters.hasExplicitReasoning) null else platform.topP,
                        reasoning = reasoningParameters.openAIEffort?.let { effort ->
                            ReasoningConfig(
                                effort = effort,
                                summary = "auto"
                            )
                        }
                    ),
                    sources = emptyList()
                )
            },
            stream = { prepared ->
                flow {
                    val events = mutableListOf<ResponsesStreamEvent>()
                    val outputText = StringBuilder()
                    var emittedOutputTextDelta = false
                    openAIAPI.streamResponses(prepared.request, platform.timeout).collect { event ->
                        events += event
                        when (event) {
                            is ReasoningSummaryTextDeltaEvent -> emit(ApiState.Thinking(event.delta))

                            is OutputTextDeltaEvent -> {
                                emittedOutputTextDelta = true
                                outputText.append(event.delta)
                                emit(ApiState.Success(event.delta))
                            }

                            is OutputTextDoneEvent -> {
                                if (!emittedOutputTextDelta && event.text.isNotBlank()) {
                                    outputText.append(event.text)
                                    emit(ApiState.Success(event.text))
                                }
                            }

                            is ResponseFailedEvent -> {
                                val errorMessage = event.response.error?.message ?: "响应失败"
                                emit(ApiState.Error(errorMessage))
                            }

                            is ResponseErrorEvent -> {
                                emit(ApiState.Error(event.message))
                            }

                            else -> {}
                        }
                    }
                    val usage = openAIResponsesUsageFromEvents(
                        events = events,
                        request = prepared.request,
                        outputText = outputText.toString(),
                        platform = platform,
                        label = "OpenAI Responses"
                    )
                    if (outputText.isNotBlank() || !usage.isEstimated || emitEstimatedUsageWithoutOutput) {
                        emit(ApiState.UsageUpdated(usage))
                    }
                }
            },
            sourceMetadata = { it.sources }
        ).catch { e ->
            emit(ApiState.Error(e.message ?: "未知错误"))
        }.onCompletion {
            emit(ApiState.Done)
        }
    } catch (e: Exception) {
        flowOf(ApiState.Error(e.message ?: "完成会话失败"))
    }

    private suspend fun completeChatWithGroq(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        extraPrompt: String? = null,
        emitEstimatedUsageWithoutOutput: Boolean = false
    ): Flow<ApiState> = try {
        streamPreparedApiState(
            prepare = {
                val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
                validateInlineBudgetIfNeeded(conversationContext.turns, platform)
                val messages = buildOpenAIChatMessages(
                    conversationContext.turns,
                    mergePromptSections(platform.systemPrompt, currentRuntimeContextPrompt(), memoryPrompt, conversationContext.summary, extraPrompt)
                )

                ProviderRequestWithSources(
                    request = createGroqChatCompletionRequest(messages, platform, reasoningMode),
                    sources = emptyList()
                )
            },
            stream = { prepared ->
                flow {
                    val parser = ReasoningStreamParser()
                    val chunks = mutableListOf<cn.nabr.chatwithchat.data.dto.groq.response.GroqChatCompletionChunk>()
                    val outputText = StringBuilder()
                    groqAPI.streamChatCompletion(
                        request = prepared.request,
                        timeoutSeconds = platform.timeout,
                        token = platform.token,
                        apiUrl = platform.apiUrl
                    ).collect { chunk ->
                        chunks += chunk
                        when {
                            chunk.error != null -> {
                                emit(ApiState.Error(chunk.error.message))
                            }

                            else -> {
                                val choice = chunk.choices?.firstOrNull()
                                parser.append(
                                    reasoningChunk = choice?.delta?.reasoning ?: choice?.message?.reasoning,
                                    contentChunk = choice?.delta?.content ?: choice?.message?.content
                                ).forEach { state ->
                                    if (state is ApiState.Success) {
                                        outputText.append(state.textChunk)
                                    }
                                    emit(state)
                                }
                            }
                        }
                    }

                    parser.flush().forEach { state ->
                        if (state is ApiState.Success) {
                            outputText.append(state.textChunk)
                        }
                        emit(state)
                    }
                    val usage = groqUsageFromChunks(
                        chunks = chunks,
                        request = prepared.request,
                        outputText = outputText.toString(),
                        platform = platform,
                        label = "Groq"
                    )
                    if (outputText.isNotBlank() || !usage.isEstimated || emitEstimatedUsageWithoutOutput) {
                        emit(ApiState.UsageUpdated(usage))
                    }
                }
            },
            sourceMetadata = { it.sources }
        ).catch { e ->
            emit(ApiState.Error(e.message ?: "未知错误"))
        }.onCompletion {
            emit(ApiState.Done)
        }
    } catch (e: Exception) {
        flowOf(ApiState.Error(e.message ?: "完成会话失败"))
    }

    private suspend fun completeChatWithOpenAIChatCompletions(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        extraPrompt: String? = null,
        emitEstimatedUsageWithoutOutput: Boolean = false
    ): Flow<ApiState> = try {
        openAIAPI.setToken(platform.token)
        openAIAPI.setAPIUrl(platform.apiUrl)

        streamPreparedApiState(
            prepare = {
                val reasoningParameters = mapReasoningMode(platform, reasoningMode)
                val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
                validateInlineBudgetIfNeeded(conversationContext.turns, platform)
                val messages = buildOpenAIChatMessages(
                    conversationContext.turns,
                    mergePromptSections(platform.systemPrompt, currentRuntimeContextPrompt(), memoryPrompt, conversationContext.summary, extraPrompt)
                )

                ProviderRequestWithSources(
                    request = ChatCompletionRequest(
                        model = platform.model,
                        messages = messages,
                        stream = platform.stream,
                        temperature = platform.temperature.takeIf {
                            reasoningParameters.openAICompatibleThinkingType == null
                        },
                        topP = platform.topP.takeIf {
                            reasoningParameters.openAICompatibleThinkingType == null
                        },
                        reasoningEffort = reasoningParameters.openAICompatibleReasoningEffort,
                        thinking = reasoningParameters.openAICompatibleThinkingType?.let { type ->
                            ChatCompletionThinkingConfig(type)
                        },
                        streamOptions = chatCompletionStreamOptionsFor(platform)
                    ),
                    sources = emptyList()
                )
            },
            stream = { prepared ->
                flow {
                    val chunks = mutableListOf<ChatCompletionChunk>()
                    val outputText = StringBuilder()
                    val reasoningParser = ReasoningStreamParser()
                    openAIAPI.streamChatCompletion(prepared.request, platform.timeout).collect { chunk ->
                        chunks += chunk
                        when {
                            chunk.error != null -> {
                                emit(ApiState.Error(chunk.error.message))
                            }

                            else -> {
                                val delta = chunk.choices?.firstOrNull()?.delta
                                reasoningParser.append(
                                    contentChunk = delta?.content,
                                    reasoningChunk = delta?.reasoningContent
                                        ?.takeIf { it.isNotEmpty() }
                                        ?: delta?.reasoning
                                ).forEach { state ->
                                    if (state is ApiState.Success) {
                                        outputText.append(state.textChunk)
                                    }
                                    emit(state)
                                }
                            }
                        }
                    }
                    reasoningParser.flush().forEach { state ->
                        if (state is ApiState.Success) {
                            outputText.append(state.textChunk)
                        }
                        emit(state)
                    }
                    val usage = openAIChatUsageFromChunks(
                        chunks = chunks,
                        request = prepared.request,
                        outputText = outputText.toString(),
                        platform = platform,
                        label = "Chat Completions"
                    )
                    if (outputText.isNotBlank() || !usage.isEstimated || emitEstimatedUsageWithoutOutput) {
                        emit(ApiState.UsageUpdated(usage))
                    }
                }
            },
            sourceMetadata = { it.sources }
        ).catch { e ->
            emit(ApiState.Error(e.message ?: "未知错误"))
        }.onCompletion {
            emit(ApiState.Done)
        }
    } catch (e: Exception) {
        flowOf(ApiState.Error(e.message ?: "完成会话失败"))
    }

    private suspend fun buildConversationContext(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2
    ): ConversationContext {
        val policy = ProviderContextPolicy.forClientType(platform.compatibleType)
        val conversationContext = contextBuilder.buildContext(userMessages, assistantMessages, platform, policy)
        scheduleMemoryConsolidationForCompactionIfNeeded(conversationContext)
        if (!policy.preferProviderFileRefs || conversationContext.turns.isEmpty()) {
            return conversationContext
        }

        return conversationContext.copy(
            turns = ensureProviderReferencesForTurns(conversationContext.turns, platform)
        )
    }

    private suspend fun scheduleMemoryConsolidationForCompactionIfNeeded(
        conversationContext: ConversationContext
    ) {
        val scheduler = memoryTurnBatchScheduler ?: return
        if (conversationContext.omittedTurns.isEmpty()) return
        val memoryEnabled = runCatching { settingRepository.fetchMemoryEnabled() }.getOrDefault(false)
        if (!memoryEnabled) return

        val lastOmittedTurn = conversationContext.omittedTurns.last()
        val chatId = lastOmittedTurn.userMessage.chatId
        val lastUserMessageId = lastOmittedTurn.userMessage.id
        if (chatId <= 0 || lastUserMessageId <= 0) return
        scheduler.markCompactionUrgent(chatId, lastUserMessageId)
    }

    private suspend fun ensureProviderReferencesForTurns(
        turns: List<ConversationTurn>,
        platform: PlatformV2
    ): List<ConversationTurn> {
        val preparedUserMessages = prepareMessagesForPlatform(turns.map { it.userMessage }, platform)
        return turns.mapIndexed { index, turn ->
            turn.copy(userMessage = preparedUserMessages[index])
        }
    }

    private suspend fun validateInlineBudgetIfNeeded(
        contextTurns: List<ConversationTurn>,
        platform: PlatformV2
    ) {
        val maxInlineBytes = ProviderContextPolicy.forClientType(platform.compatibleType).maxInlineAttachmentBytes ?: return
        attachmentUploadCoordinator.validateInlineAttachmentBudget(contextTurns, maxInlineBytes)
    }

    private suspend fun buildOpenAIChatMessages(
        contextTurns: List<ConversationTurn>,
        systemPrompt: String?
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
            messages.add(
                ChatMessage(
                    role = OpenAIRole.SYSTEM,
                    content = listOf(OpenAITextContent(text = prompt))
                )
            )
        }

        contextTurns.forEach { turn ->
            if (hasRenderableMessageContent(turn.userMessage, isUser = true)) {
                messages.add(transformMessageV2ToChatMessage(turn.userMessage, isUser = true))
            }
            turn.assistantMessage?.takeIf { hasRenderableMessageContent(it, isUser = false) }?.let { assistantMessage ->
                messages.add(transformMessageV2ToChatMessage(assistantMessage, isUser = false))
            }
        }

        return messages
    }

    private suspend fun buildResponsesInputMessages(
        contextTurns: List<ConversationTurn>,
        platformUid: String
    ): List<ResponseInputItem> {
        val inputMessages = mutableListOf<ResponseInputItem>()

        contextTurns.forEach { turn ->
            if (hasRenderableMessageContent(turn.userMessage, isUser = true)) {
                inputMessages.add(
                    transformMessageV2ToResponsesInput(
                        turn.userMessage,
                        isUser = true,
                        platformUid = platformUid
                    )
                )
            }
            turn.assistantMessage?.takeIf { hasRenderableMessageContent(it, isUser = false) }?.let { assistantMessage ->
                inputMessages.add(
                    transformMessageV2ToResponsesInput(
                        assistantMessage,
                        isUser = false,
                        platformUid = platformUid
                    )
                )
            }
        }

        return inputMessages
    }

    private suspend fun buildAnthropicInputMessages(
        contextTurns: List<ConversationTurn>,
        platformUid: String
    ): List<InputMessage> {
        val messages = mutableListOf<InputMessage>()

        contextTurns.forEach { turn ->
            if (hasRenderableMessageContent(turn.userMessage, isUser = true)) {
                messages.add(transformMessageV2ToAnthropic(turn.userMessage, MessageRole.USER, platformUid))
            }
            turn.assistantMessage?.takeIf { hasRenderableMessageContent(it, isUser = false) }?.let { assistantMessage ->
                messages.add(transformMessageV2ToAnthropic(assistantMessage, MessageRole.ASSISTANT, platformUid))
            }
        }

        return messages
    }

    private suspend fun buildGoogleContents(
        contextTurns: List<ConversationTurn>,
        platformUid: String
    ): List<Content> {
        val contents = mutableListOf<Content>()

        contextTurns.forEach { turn ->
            if (hasRenderableMessageContent(turn.userMessage, isUser = true)) {
                contents.add(transformMessageV2ToGoogle(turn.userMessage, GoogleRole.USER, platformUid))
            }
            turn.assistantMessage?.takeIf { hasRenderableMessageContent(it, isUser = false) }?.let { assistantMessage ->
                contents.add(transformMessageV2ToGoogle(assistantMessage, GoogleRole.MODEL, platformUid))
            }
        }

        return contents
    }

    private fun hasRenderableMessageContent(message: MessageV2, isUser: Boolean): Boolean {
        val messageContent = if (isUser) message.content else message.sendableAssistantContent()
        return messageContent.isNotBlank() || message.attachments.isNotEmpty()
    }

    private suspend fun transformMessageV2ToChatMessage(message: MessageV2, isUser: Boolean): ChatMessage {
        val content = mutableListOf<OpenAIMessageContent>()
        val messageContent = if (isUser) message.content else message.sendableAssistantContent()

        // Add text content
        if (messageContent.isNotBlank()) {
            content.add(OpenAITextContent(text = messageContent))
        }

        // Add file content (images)
        message.attachments.forEach { attachment ->
            val filePath = attachment.preparedFilePath.ifBlank { attachment.localFilePath }
            val mimeType = attachment.mimeType.ifBlank { FileUtils.getMimeType(context, filePath) }
            val encodedImage = getEncodedAttachment(filePath, mimeType)
            if (encodedImage != null) {
                content.add(
                    OpenAIImageContent(
                        imageUrl = ImageUrl(url = "data:${encodedImage.mimeType};base64,${encodedImage.base64Data}")
                    )
                )
            }
        }

        return ChatMessage(
            role = if (isUser) OpenAIRole.USER else OpenAIRole.ASSISTANT,
            content = content
        )
    }

    private suspend fun transformMessageV2ToResponsesInput(message: MessageV2, isUser: Boolean, platformUid: String): ResponseInputMessage {
        val role = if (isUser) "user" else "assistant"
        val messageContent = if (isUser) message.content else message.sendableAssistantContent()

        // Check if there are any image files
        val imageAttachments = message.attachments.filter { attachment ->
            val filePath = attachment.preparedFilePath.ifBlank { attachment.localFilePath }
            val mimeType = attachment.mimeType.ifBlank { FileUtils.getMimeType(context, filePath) }
            FileUtils.isImage(mimeType)
        }

        // If no images, use simple text content
        if (imageAttachments.isEmpty()) {
            return ResponseInputMessage(
                role = role,
                content = ResponseInputContent.text(messageContent)
            )
        }

        // Build content parts for text + images
        val parts = mutableListOf<ResponseContentPart>()

        // Add text content if not blank
        if (messageContent.isNotBlank()) {
            parts.add(ResponseContentPart.text(messageContent))
        }

        // Add image content
        imageAttachments.forEach { attachment ->
            val providerRef = attachment.providerRefFor(platformUid)
            if (providerRef?.remoteType == cn.nabr.chatwithchat.data.model.AttachmentRemoteType.OPENAI_FILE) {
                parts.add(ResponseContentPart.imageFile(providerRef.remoteId))
            } else {
                val filePath = attachment.preparedFilePath.ifBlank { attachment.localFilePath }
                val mimeType = attachment.mimeType.ifBlank { FileUtils.getMimeType(context, filePath) }
                val encodedImage = getEncodedAttachment(filePath, mimeType)
                if (encodedImage != null) {
                    parts.add(
                        ResponseContentPart.image(
                            "data:${encodedImage.mimeType};base64,${encodedImage.base64Data}"
                        )
                    )
                }
            }
        }

        validateResponseInputPartsOrThrow(messageContent, parts.size, message.id)

        return ResponseInputMessage(
            role = role,
            content = ResponseInputContent.parts(parts)
        )
    }

    private suspend fun completeChatWithAnthropic(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        extraPrompt: String? = null,
        emitEstimatedUsageWithoutOutput: Boolean = false
    ): Flow<ApiState> = try {
        anthropicAPI.setToken(platform.token)
        anthropicAPI.setAPIUrl(platform.apiUrl)

        streamPreparedApiState(
            prepare = {
                val reasoningParameters = mapReasoningMode(platform, reasoningMode)
                val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
                val messages = buildAnthropicInputMessages(conversationContext.turns, platform.uid)

                ProviderRequestWithSources(
                    request = MessageRequest(
                        model = platform.model,
                        messages = messages,
                        maxTokens = reasoningParameters.anthropicMaxTokens ?: 4096,
                        stream = platform.stream,
                        systemPrompt = mergePromptSections(platform.systemPrompt, currentRuntimeContextPrompt(), memoryPrompt, conversationContext.summary, extraPrompt),
                        temperature = if (reasoningParameters.hasExplicitReasoning) null else platform.temperature,
                        topP = if (reasoningParameters.hasExplicitReasoning) null else platform.topP,
                        thinking = reasoningParameters.anthropicBudgetTokens?.let { budgetTokens ->
                            cn.nabr.chatwithchat.data.dto.anthropic.request.ThinkingConfig(
                                type = "enabled",
                                budgetTokens = budgetTokens
                            )
                        }
                    ),
                    sources = emptyList()
                )
            },
            stream = { prepared ->
                flow {
                    val chunks = mutableListOf<MessageResponseChunk>()
                    val outputText = StringBuilder()
                    anthropicAPI.streamChatMessage(prepared.request, platform.timeout).collect { chunk ->
                        chunks += chunk
                        when (chunk) {
                            is cn.nabr.chatwithchat.data.dto.anthropic.response.ContentDeltaResponseChunk -> {
                                when (chunk.delta.type) {
                                    cn.nabr.chatwithchat.data.dto.anthropic.response.ContentBlockType.THINKING_DELTA -> {
                                        chunk.delta.thinking?.let { emit(ApiState.Thinking(it)) }
                                    }

                                    cn.nabr.chatwithchat.data.dto.anthropic.response.ContentBlockType.DELTA -> {
                                        chunk.delta.text?.let { text ->
                                            outputText.append(text)
                                            emit(ApiState.Success(text))
                                        }
                                    }

                                    else -> {}
                                }
                            }

                            is cn.nabr.chatwithchat.data.dto.anthropic.response.ErrorResponseChunk -> {
                                emit(ApiState.Error(chunk.error.message))
                            }

                            else -> {}
                        }
                    }
                    val usage = anthropicUsageFromChunks(
                        chunks = chunks,
                        request = prepared.request,
                        outputText = outputText.toString(),
                        platform = platform,
                        label = "Anthropic"
                    )
                    if (outputText.isNotBlank() || !usage.isEstimated || emitEstimatedUsageWithoutOutput) {
                        emit(ApiState.UsageUpdated(usage))
                    }
                }
            },
            sourceMetadata = { it.sources }
        ).catch { e ->
            emit(ApiState.Error(e.message ?: "未知错误"))
        }.onCompletion {
            emit(ApiState.Done)
        }
    } catch (e: Exception) {
        flowOf(ApiState.Error(e.message ?: "完成会话失败"))
    }

    private suspend fun transformMessageV2ToAnthropic(message: MessageV2, role: MessageRole, platformUid: String): InputMessage {
        val content = mutableListOf<AnthropicMessageContent>()
        val messageContent = if (role == MessageRole.USER) message.content else message.sendableAssistantContent()

        // Add text content
        if (messageContent.isNotBlank()) {
            content.add(AnthropicTextContent(text = messageContent))
        }

        // Add file content (images)
        message.attachments.forEach { attachment ->
            val providerRef = attachment.providerRefFor(platformUid)
            if (providerRef?.remoteType == cn.nabr.chatwithchat.data.model.AttachmentRemoteType.ANTHROPIC_FILE) {
                content.add(AnthropicImageContent(source = ImageSource.file(providerRef.remoteId)))
            } else {
                val filePath = attachment.preparedFilePath.ifBlank { attachment.localFilePath }
                val mimeType = attachment.mimeType.ifBlank { FileUtils.getMimeType(context, filePath) }
                val encodedImage = getEncodedAttachment(filePath, mimeType)
                if (encodedImage != null) {
                    val mediaType = when {
                        encodedImage.mimeType.contains("jpeg") || encodedImage.mimeType.contains("jpg") -> MediaType.JPEG
                        encodedImage.mimeType.contains("png") -> MediaType.PNG
                        encodedImage.mimeType.contains("gif") -> MediaType.GIF
                        encodedImage.mimeType.contains("webp") -> MediaType.WEBP
                        else -> MediaType.JPEG
                    }

                    content.add(
                        AnthropicImageContent(
                            source = ImageSource.base64(mediaType, encodedImage.base64Data)
                        )
                    )
                }
            }
        }

        return InputMessage(role = role, content = content)
    }

    private suspend fun completeChatWithGoogle(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        extraPrompt: String? = null,
        emitEstimatedUsageWithoutOutput: Boolean = false
    ): Flow<ApiState> = try {
        googleAPI.setToken(platform.token)
        googleAPI.setAPIUrl(platform.apiUrl)

        streamPreparedApiState(
            prepare = {
                val reasoningParameters = mapReasoningMode(platform, reasoningMode)
                val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
                val contents = buildGoogleContents(conversationContext.turns, platform.uid)

                ProviderRequestWithSources(
                    request = GenerateContentRequest(
                        contents = contents,
                        generationConfig = GenerationConfig(
                            temperature = platform.temperature,
                            topP = platform.topP,
                            thinkingConfig = reasoningParameters.googleThinkingBudget?.let { thinkingBudget ->
                                cn.nabr.chatwithchat.data.dto.google.request.ThinkingConfig(
                                    thinkingBudget = thinkingBudget,
                                    includeThoughts = reasoningParameters.googleIncludeThoughts ?: false
                                )
                            }
                        ),
                        systemInstruction = mergePromptSections(platform.systemPrompt, currentRuntimeContextPrompt(), memoryPrompt, conversationContext.summary, extraPrompt)?.let {
                            Content(
                                parts = listOf(Part.text(it))
                            )
                        }
                    ),
                    sources = emptyList()
                )
            },
            stream = { prepared ->
                flow {
                    val responses = mutableListOf<GenerateContentResponse>()
                    val outputText = StringBuilder()
                    googleAPI.streamGenerateContent(prepared.request, platform.model, platform.timeout).collect { response ->
                        responses += response
                        when {
                            response.error != null -> {
                                emit(ApiState.Error(response.error.message))
                            }

                            response.candidates?.firstOrNull()?.content?.parts != null -> {
                                val parts = response.candidates.first().content.parts
                                parts.forEach { part ->
                                    part.text?.let { text ->
                                        if (part.thought == true) {
                                            emit(ApiState.Thinking(text))
                                        } else {
                                            outputText.append(text)
                                            emit(ApiState.Success(text))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val usage = googleUsageFromResponses(
                        responses = responses,
                        request = prepared.request,
                        outputText = outputText.toString(),
                        platform = platform,
                        label = "Google"
                    )
                    if (outputText.isNotBlank() || !usage.isEstimated || emitEstimatedUsageWithoutOutput) {
                        emit(ApiState.UsageUpdated(usage))
                    }
                }
            },
            sourceMetadata = { it.sources }
        ).catch { e ->
            emit(ApiState.Error(e.message ?: "未知错误"))
        }.onCompletion {
            emit(ApiState.Done)
        }
    } catch (e: Exception) {
        flowOf(ApiState.Error(e.message ?: "完成会话失败"))
    }

    private suspend fun transformMessageV2ToGoogle(message: MessageV2, role: GoogleRole, platformUid: String): Content {
        val parts = mutableListOf<Part>()
        val messageContent = if (role == GoogleRole.USER) message.content else message.sendableAssistantContent()

        // Add text content
        if (messageContent.isNotBlank()) {
            parts.add(Part.text(messageContent))
        }

        // Add file content (images)
        message.attachments.forEach { attachment ->
            val providerRef = attachment.providerRefFor(platformUid)
            if (providerRef?.remoteType == cn.nabr.chatwithchat.data.model.AttachmentRemoteType.GOOGLE_FILE) {
                parts.add(Part.fileData(providerRef.mimeType, providerRef.remoteId))
            } else {
                val filePath = attachment.preparedFilePath.ifBlank { attachment.localFilePath }
                val mimeType = attachment.mimeType.ifBlank { FileUtils.getMimeType(context, filePath) }
                val encodedImage = getEncodedAttachment(filePath, mimeType)
                if (encodedImage != null) {
                    parts.add(Part.inlineData(encodedImage.mimeType, encodedImage.base64Data))
                }
            }
        }

        return Content(role = role, parts = parts)
    }

    private suspend fun getEncodedAttachment(filePath: String, mimeType: String): FileUtils.EncodedImage? {
        if (!FileUtils.isSupportedUploadMimeType(mimeType)) return null
        AttachmentPayloadCache.get(filePath)?.let { return it }

        return withContext(Dispatchers.IO) {
            FileUtils.encodeFileForUpload(context, filePath, mimeType)?.also { encodedImage ->
                AttachmentPayloadCache.put(filePath, encodedImage)
            }
        }
    }

    private suspend fun prepareMessagesForPlatform(
        messages: List<MessageV2>,
        platform: PlatformV2
    ): List<MessageV2> {
        val updatedMessages = messages.map { attachmentUploadCoordinator.ensureMessageAttachmentsForPlatform(it, platform) }
        val changedMessages = updatedMessages
            .zip(messages)
            .mapNotNull { (updated, original) -> updated.takeIf { it != original } }

        if (changedMessages.isNotEmpty()) {
            messageV2Dao.editMessages(*changedMessages.toTypedArray())
        }

        return updatedMessages
    }

    override suspend fun fetchChatList(): List<ChatRoom> = chatRoomDao.getChatRooms()

    override suspend fun fetchChatListV2(): List<ChatRoomV2> = chatRoomV2Dao.getChatRooms()

    override suspend fun searchChatsV2(query: String): List<ChatRoomV2> {
        if (query.isBlank()) {
            return chatRoomV2Dao.getChatRooms()
        }

        // Search by title
        val titleMatches = chatRoomV2Dao.searchChatRoomsByTitle(query)

        // Search by message content and get chat IDs
        val messageMatchChatIds = messageV2Dao.searchMessagesByContent(query)

        // Get all chat rooms and filter by message match IDs
        val allChatRooms = chatRoomV2Dao.getChatRooms()
        val messageMatches = allChatRooms.filter { it.id in messageMatchChatIds }

        // Combine results and remove duplicates, maintaining order by updatedAt
        return (titleMatches + messageMatches)
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun fetchMessages(chatId: Int): List<Message> = messageDao.loadMessages(chatId)

    override suspend fun fetchMessagesV2(chatId: Int): List<MessageV2> = messageV2Dao.loadMessages(chatId)

    override suspend fun findChatIdByInitialRequestId(initialRequestId: Int): Int? =
        messageV2Dao.findChatIdByInitialRequestId(initialRequestId)

    override suspend fun fetchChatPlatformModels(chatId: Int): Map<String, ChatPlatformConfig> = chatPlatformModelV2Dao.getByChatId(chatId).associate {
        it.platformUid to ChatPlatformConfig(
            platformUid = it.platformUid,
            model = it.model,
            reasoningMode = ReasoningMode.fromStorageValue(it.reasoningMode)
        )
    }

    override suspend fun saveChatPlatformModels(chatId: Int, models: Map<String, ChatPlatformConfig>) {
        val rows = models
            .filterKeys { it.isNotBlank() }
            .mapNotNull { (platformUid, config) ->
                val sanitizedModel = config.model.trim()
                if (sanitizedModel.isBlank()) return@mapNotNull null

                ChatPlatformModelV2(
                    chatId = chatId,
                    platformUid = platformUid,
                    model = sanitizedModel,
                    reasoningMode = config.reasoningMode.storageValue
                )
            }

        if (rows.isNotEmpty()) {
            chatPlatformModelV2Dao.upsertAll(*rows.toTypedArray())
        }
    }

    override suspend fun migrateToChatRoomV2MessageV2() {
        val leftOverChatRoomV2s = chatRoomV2Dao.getChatRooms()
        leftOverChatRoomV2s.forEach { chatPlatformModelV2Dao.deleteByChatId(it.id) }
        chatRoomV2Dao.deleteChatRooms(*leftOverChatRoomV2s.toTypedArray())

        val chatList = fetchChatList()
        val platforms = settingRepository.fetchPlatformV2s()
        val apiTypeMap = mutableMapOf<ApiType, String>()
        val configByPlatformUid = mutableMapOf<String, ChatPlatformConfig>()

        platforms.forEach { platform ->
            configByPlatformUid[platform.uid] = ChatPlatformConfig(
                platformUid = platform.uid,
                model = platform.model,
                reasoningMode = platform.defaultReasoningMode()
            )
            when (platform.name) {
                "OpenAI" -> apiTypeMap[ApiType.OPENAI] = platform.uid
                "Anthropic" -> apiTypeMap[ApiType.ANTHROPIC] = platform.uid
                "Google" -> apiTypeMap[ApiType.GOOGLE] = platform.uid
                "Groq" -> apiTypeMap[ApiType.GROQ] = platform.uid
                "Ollama" -> apiTypeMap[ApiType.OLLAMA] = platform.uid
            }
        }

        chatList.forEach { chatRoom ->
            val messages = messageDao.loadMessages(chatRoom.id).map { m ->
                MessageV2(
                    id = m.id,
                    chatId = m.chatId,
                    content = m.content,
                    attachments = listOf(),
                    revisions = listOf(),
                    linkedMessageId = m.linkedMessageId,
                    platformType = m.platformType?.let { apiTypeMap[it] },
                    createdAt = m.createdAt
                )
            }

            val enabledPlatformUids = chatRoom.enabledPlatform.mapNotNull { apiTypeMap[it] }.filter { it.isNotBlank() }
            chatRoomV2Dao.addChatRoom(
                ChatRoomV2(
                    id = chatRoom.id,
                    title = chatRoom.title,
                    enabledPlatform = enabledPlatformUids,
                    createdAt = chatRoom.createdAt,
                    updatedAt = chatRoom.createdAt
                )
            )

            val modelRows = enabledPlatformUids.map { platformUid ->
                val config = configByPlatformUid[platformUid] ?: ChatPlatformConfig(
                    platformUid = platformUid,
                    model = "",
                    reasoningMode = ReasoningMode.AUTO
                )
                ChatPlatformModelV2(
                    chatId = chatRoom.id,
                    platformUid = platformUid,
                    model = config.model,
                    reasoningMode = config.reasoningMode.storageValue
                )
            }

            if (modelRows.isNotEmpty()) {
                chatPlatformModelV2Dao.upsertAll(*modelRows.toTypedArray())
            }

            messageV2Dao.addMessages(*messages.toTypedArray())
        }
    }

    override fun generateDefaultChatTitle(messages: List<MessageV2>): String? = messages.sortedBy { it.createdAt }.firstOrNull { it.platformType == null }?.content?.replace('\n', ' ')?.take(50)

    override suspend fun updateChatTitle(chatRoom: ChatRoomV2, title: String) {
        chatRoomV2Dao.editChatRoom(chatRoom.copy(title = title.replace('\n', ' ').take(50)))
    }

    override suspend fun saveChat(chatRoom: ChatRoomV2, messages: List<MessageV2>, chatPlatformModels: Map<String, ChatPlatformConfig>): ChatRoomV2 {
        if (chatRoom.id == 0) {
            return runInChatTransaction {
                val chatId = chatRoomV2Dao.addChatRoom(chatRoom)
                val updatedMessages = messages.map { it.copy(chatId = chatId.toInt()) }
                messageV2Dao.addMessages(*updatedMessages.toTypedArray())
                saveChatPlatformModels(
                    chatId = chatId.toInt(),
                    models = chatPlatformModels.filterKeys { it in chatRoom.enabledPlatform }
                )

                val savedChatRoom = chatRoom.copy(id = chatId.toInt())
                updateChatTitle(savedChatRoom, updatedMessages[0].content)

                savedChatRoom.copy(title = updatedMessages[0].content.replace('\n', ' ').take(50))
            }
        }

        return runInChatTransaction {
            val savedMessages = fetchMessagesV2(chatRoom.id)
            val updatedMessages = messages.map { it.copy(chatId = chatRoom.id) }

            val shouldBeDeleted = savedMessages.filter { m ->
                updatedMessages.firstOrNull { it.id == m.id } == null
            }
            val shouldBeUpdated = updatedMessages.filter { m ->
                savedMessages.firstOrNull { it.id == m.id && it != m } != null
            }
            val shouldBeAdded = updatedMessages.filter { m ->
                savedMessages.firstOrNull { it.id == m.id } == null
            }

            chatRoomV2Dao.editChatRoom(chatRoom)
            messageV2Dao.deleteMessages(*shouldBeDeleted.toTypedArray())
            messageV2Dao.editMessages(*shouldBeUpdated.toTypedArray())
            messageV2Dao.addMessages(*shouldBeAdded.toTypedArray())
            saveChatPlatformModels(
                chatId = chatRoom.id,
                models = chatPlatformModels.filterKeys { it in chatRoom.enabledPlatform }
            )

            chatRoom
        }
    }

    private suspend fun <T> runInChatTransaction(block: suspend () -> T): T =
        chatDatabaseV2?.withTransaction { block() } ?: block()

    override suspend fun duplicateChatV2(chatRoom: ChatRoomV2): ChatRoomV2 {
        val duplicatedTitle = "${chatRoom.title}（副本）".take(50)
        val duplicatedChatId = chatRoomV2Dao.addChatRoom(
            ChatRoomV2(
                title = duplicatedTitle,
                enabledPlatform = chatRoom.enabledPlatform
            )
        ).toInt()

        val messages = fetchMessagesV2(chatRoom.id).map { message ->
            message.copy(
                id = 0,
                chatId = duplicatedChatId,
                linkedMessageId = 0
            )
        }
        if (messages.isNotEmpty()) {
            messageV2Dao.addMessages(*messages.toTypedArray())
        }

        val chatPlatformModels = fetchChatPlatformModels(chatRoom.id)
        saveChatPlatformModels(duplicatedChatId, chatPlatformModels)

        return chatRoom.copy(
            id = duplicatedChatId,
            title = duplicatedTitle,
            createdAt = System.currentTimeMillis() / 1000,
            updatedAt = System.currentTimeMillis() / 1000
        )
    }

    override suspend fun deleteChats(chatRooms: List<ChatRoom>) {
        chatRoomDao.deleteChatRooms(*chatRooms.toTypedArray())
    }

    override suspend fun deleteChatsV2(chatRooms: List<ChatRoomV2>) {
        chatRoomV2Dao.deleteChatRooms(*chatRooms.toTypedArray())
    }
}

internal fun createGroqChatCompletionRequest(
    messages: List<ChatMessage>,
    platform: PlatformV2,
    reasoningMode: ReasoningMode = platform.defaultReasoningMode()
): GroqChatCompletionRequest {
    val reasoningParameters = mapReasoningMode(platform, reasoningMode)

    return GroqChatCompletionRequest(
        model = platform.model,
        messages = messages,
        stream = platform.stream,
        temperature = platform.temperature,
        topP = platform.topP,
        reasoningEffort = reasoningParameters.groqReasoningEffort,
        reasoningFormat = reasoningParameters.groqReasoningFormat,
        includeReasoning = reasoningParameters.groqIncludeReasoning,
        streamOptions = ChatCompletionStreamOptions()
    )
}

internal fun isGroqGptOssModel(model: String): Boolean = isGptOssModel(model)

private const val MAX_SEARCH_QUERY_COUNT = 2
private const val OPENAI_FUNCTION_CALL_TYPE = "function_call"
private const val OPENAI_NATIVE_TOOL_INSTRUCTION =
    "Use the available tools only when the latest user request needs current web information or source inspection. " +
        "When calling web_search, rewrite the user's request into a concise search-engine query with the likely entity, topic, timeframe, geography/source scope, and official or primary-source terms when useful; do not merely copy the user's wording. " +
        "Prefer the user's language for local or regional facts. " +
        "Do not use web_search for the user's local date, time, timezone, device state, or app settings. " +
        "Prefer answering directly when the conversation is enough. If you use web sources, cite source URLs in the answer."
private const val OPENAI_NATIVE_GENERIC_TOOL_INSTRUCTION =
    "Use the available tools only when the latest user request needs them. " +
        "Keep tool arguments concise and prefer answering directly when the conversation is enough."
private const val OPENAI_NATIVE_FINAL_TOOL_INSTRUCTION =
    "Do not call more tools. Use the available function_call_output items when relevant and provide the final answer. " +
        "If the user's request is broad or underspecified but the tool results are usable, answer with the most reasonable default scope, state that scope briefly, and avoid asking a clarifying question before giving useful content."

private data class ProviderRequestWithSources<T>(
    val request: T,
    val sources: List<MessageSourceMetadata>
)

private data class SearchDecisionExecution(
    val finalAnswerPrompt: String,
    val usage: TokenUsageRecord
)

private data class OpenAIResponsesNativeToolRequest(
    val model: String,
    val input: List<ResponseInputItem>,
    val instructions: String?,
    val temperature: Float?,
    val topP: Float?,
    val reasoning: ReasoningConfig?
) {
    fun toRequest(
        continuationItems: List<ResponseInputItem>,
        tools: List<ResponseTool>,
        toolChoice: ResponseToolChoice,
        extraInstruction: String? = null
    ): ResponsesRequest = ResponsesRequest(
        model = model,
        input = input + continuationItems,
        stream = true,
        instructions = mergePromptSections(instructions, extraInstruction),
        temperature = temperature,
        topP = topP,
        reasoning = reasoning,
        tools = tools,
        toolChoice = toolChoice
    )
}

private data class OpenAIResponsesNativeRound(
    val events: List<ResponsesStreamEvent>,
    val errorMessage: String?,
    val usage: TokenUsageRecord?
)

private data class OpenAIChatCompletionsNativeToolRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float?,
    val topP: Float?,
    val reasoningEffort: String?,
    val thinking: ChatCompletionThinkingConfig?
) {
    fun toRequest(
        continuationMessages: List<ChatMessage>,
        tools: List<ChatCompletionTool>,
        toolChoice: ChatCompletionToolChoice,
        extraInstruction: String? = null
    ): ChatCompletionRequest = ChatCompletionRequest(
        model = model,
        messages = messages.withAdditionalSystemInstruction(extraInstruction) + continuationMessages,
        stream = true,
        temperature = temperature,
        topP = topP,
        reasoningEffort = reasoningEffort,
        thinking = thinking,
        tools = tools,
        toolChoice = toolChoice,
        streamOptions = ChatCompletionStreamOptions()
    )
}

private data class OpenAIChatCompletionsNativeRound(
    val chunks: List<ChatCompletionChunk>,
    val errorMessage: String?,
    val usage: TokenUsageRecord?
)

private data class AnthropicNativeToolRequest(
    val model: String,
    val messages: List<InputMessage>,
    val maxTokens: Int,
    val systemPrompt: String?,
    val temperature: Float?,
    val topP: Float?,
    val thinking: ThinkingConfig?
) {
    fun toRequest(
        continuationMessages: List<InputMessage>,
        tools: List<AnthropicTool>,
        toolChoice: AnthropicToolChoice,
        extraInstruction: String? = null
    ): MessageRequest = MessageRequest(
        model = model,
        messages = messages + continuationMessages,
        maxTokens = maxTokens,
        stream = true,
        systemPrompt = mergePromptSections(systemPrompt, extraInstruction),
        temperature = temperature,
        topP = topP,
        thinking = thinking,
        tools = tools,
        toolChoice = toolChoice
    )
}

private data class AnthropicNativeRound(
    val chunks: List<MessageResponseChunk>,
    val errorMessage: String?,
    val usage: TokenUsageRecord?
)

private data class GoogleNativeToolRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig?,
    val systemInstruction: Content?
) {
    fun toRequest(
        continuationContents: List<Content>,
        tools: List<GoogleTool>,
        toolConfig: GoogleToolConfig,
        extraInstruction: String? = null
    ): GenerateContentRequest = GenerateContentRequest(
        contents = contents + continuationContents,
        generationConfig = generationConfig,
        systemInstruction = systemInstruction.withAdditionalText(extraInstruction),
        tools = tools,
        toolConfig = toolConfig
    )
}

private data class GoogleNativeRound(
    val responses: List<GenerateContentResponse>,
    val errorMessage: String?,
    val usage: TokenUsageRecord?
)

private fun List<ChatMessage>.withAdditionalSystemInstruction(extraInstruction: String?): List<ChatMessage> {
    val instruction = extraInstruction?.trim()?.takeIf { it.isNotBlank() } ?: return this
    val first = firstOrNull()
    return if (
        first?.role == OpenAIRole.SYSTEM &&
        first.toolCalls == null &&
        first.toolCallId == null &&
        first.contentText == null
    ) {
        listOf(first.copy(content = first.content + OpenAITextContent(instruction))) + drop(1)
    } else {
        listOf(
            ChatMessage(
                role = OpenAIRole.SYSTEM,
                content = listOf(OpenAITextContent(instruction))
            )
        ) + this
    }
}

private fun Content?.withAdditionalText(extraInstruction: String?): Content? {
    val instruction = extraInstruction?.trim()?.takeIf { it.isNotBlank() } ?: return this
    return this?.copy(parts = parts + Part.text(instruction))
        ?: Content(parts = listOf(Part.text(instruction)))
}

private fun SearchDecision.toWebSearchToolCalls(): List<ToolCall> = queries
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .distinct()
    .take(MAX_SEARCH_QUERY_COUNT)
    .mapIndexed { index, query ->
        ToolCall(
            id = "search_decision_${index + 1}",
            name = ToolDefinition.WebSearch.name,
            arguments = toolProtocolJson.encodeToString(
                buildJsonObject {
                    put("query", JsonPrimitive(query))
                }
            )
        )
    }

private fun List<MessageSourceMetadata>.dedupeMessageSources(): List<MessageSourceMetadata> =
    mapNotNull { source -> source.safeDedupeKey()?.let { key -> key to source } }
        .distinctBy { (key, _) -> key }
        .map { (_, source) -> source }

internal fun mergeSystemPrompt(basePrompt: String?, memoryPrompt: String?): String? = mergePromptSections(basePrompt, memoryPrompt)

internal fun currentRuntimeContextPrompt(): String {
    val zone = ZoneId.systemDefault()
    val now = ZonedDateTime.now(zone)
    return "Runtime context:\n" +
        "- Current local date/time: ${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)} (${zone.id}).\n" +
        "- Use this runtime context for simple date, time, and timezone questions. Do not use external lookup to determine local clock time."
}

private fun openAINativeToolInstruction(activeToolDefinitions: List<ToolDefinition>): String =
    if (activeToolDefinitions.any { definition -> definition.name == ToolDefinition.WebSearch.name }) {
        OPENAI_NATIVE_TOOL_INSTRUCTION
    } else {
        OPENAI_NATIVE_GENERIC_TOOL_INSTRUCTION
    }

internal fun mergePromptSections(vararg sections: String?): String? = sections
    .mapNotNull { section -> section?.trim()?.takeIf { it.isNotBlank() } }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(separator = "\n\n")

internal fun MessageV2.sendableAssistantContent(): String {
    val strippedContent = stripAssistantErrorNote(effectiveContent()).trim()
    return if (isAssistantErrorMessage(strippedContent)) "" else strippedContent
}

internal fun MessageV2.hasSendableAssistantPayload(): Boolean = sendableAssistantContent().isNotBlank() || attachments.isNotEmpty()

internal fun validateResponseInputPartsOrThrow(messageContent: String, partCount: Int, messageId: Int) {
    if (messageContent.isBlank() && partCount == 0) {
        throw IllegalStateException("消息没有可编码内容：messageId=$messageId")
    }
}

internal fun <T> streamPreparedApiState(
    prepare: suspend () -> T,
    stream: suspend (T) -> Flow<ApiState>,
    sourceMetadata: (T) -> List<MessageSourceMetadata> = { emptyList() }
): Flow<ApiState> = flow {
    emit(ApiState.Loading)
    val preparedRequest = withContext(Dispatchers.Default) { prepare() }
    sourceMetadata(preparedRequest).dedupeMessageSources().takeIf { it.isNotEmpty() }?.let { sources ->
        emit(ApiState.SourcesUpdated(sources))
    }
    emitAll(stream(preparedRequest))
}

private fun usageFromProviderOrEstimate(
    providerUsage: ProviderUsage?,
    requestText: String,
    outputText: String,
    platform: PlatformV2,
    label: String
): TokenUsageRecord = providerUsage
    ?.toTokenUsageRecord(
        platform = platform,
        label = label
    )
    ?: TokenUsageEstimator.estimateRequestUsage(
        requestText = requestText,
        outputText = outputText,
        platform = platform,
        label = label
    )

private fun openAIResponsesUsageFromEvents(
    events: List<ResponsesStreamEvent>,
    request: ResponsesRequest,
    outputText: String,
    platform: PlatformV2,
    label: String
): TokenUsageRecord = usageFromProviderOrEstimate(
    providerUsage = events.asReversed().firstNotNullOfOrNull { event ->
        when (event) {
            is ResponseCompletedEvent -> event.response.usage
            is ResponseFailedEvent -> event.response.usage
            else -> null
        }
    },
    requestText = NetworkClient.openAIJson.encodeToString(request),
    outputText = outputText,
    platform = platform,
    label = label
)

private fun openAIChatUsageFromChunks(
    chunks: List<ChatCompletionChunk>,
    request: ChatCompletionRequest,
    outputText: String,
    platform: PlatformV2,
    label: String
): TokenUsageRecord = usageFromProviderOrEstimate(
    providerUsage = chunks.lastOrNull { chunk -> chunk.usage != null }?.usage,
    requestText = NetworkClient.openAIJson.encodeToString(request),
    outputText = outputText,
    platform = platform,
    label = label
)

private fun groqUsageFromChunks(
    chunks: List<cn.nabr.chatwithchat.data.dto.groq.response.GroqChatCompletionChunk>,
    request: GroqChatCompletionRequest,
    outputText: String,
    platform: PlatformV2,
    label: String
): TokenUsageRecord = usageFromProviderOrEstimate(
    providerUsage = chunks
        .mapNotNull { chunk -> chunk.usage ?: chunk.xGroq?.usage }
        .lastOrNull(),
    requestText = NetworkClient.openAIJson.encodeToString(request),
    outputText = outputText,
    platform = platform,
    label = label
)

private fun anthropicUsageFromChunks(
    chunks: List<MessageResponseChunk>,
    request: MessageRequest,
    outputText: String,
    platform: PlatformV2,
    label: String
): TokenUsageRecord {
    val startUsage = chunks
        .filterIsInstance<MessageStartResponseChunk>()
        .lastOrNull()
        ?.message
        ?.usage
    val deltaUsage = chunks
        .filterIsInstance<MessageDeltaResponseChunk>()
        .lastOrNull()
        ?.usage
    val inputTokens = startUsage?.let { usage ->
        usage.inputTokens + (usage.cacheCreationInputTokens ?: 0) + (usage.cacheReadInputTokens ?: 0)
    }
    val providerUsage = if (inputTokens != null || deltaUsage != null || startUsage != null) {
        ProviderUsage(
            inputTokens = inputTokens ?: 0,
            outputTokens = deltaUsage?.outputTokens ?: startUsage?.outputTokens ?: 0
        )
    } else {
        null
    }

    return usageFromProviderOrEstimate(
        providerUsage = providerUsage,
        requestText = kotlinx.serialization.json.Json.encodeToString(request),
        outputText = outputText,
        platform = platform,
        label = label
    )
}

private fun googleUsageFromResponses(
    responses: List<GenerateContentResponse>,
    request: GenerateContentRequest,
    outputText: String,
    platform: PlatformV2,
    label: String
): TokenUsageRecord {
    val usageMetadata = responses.lastOrNull { response -> response.usageMetadata != null }?.usageMetadata
    val providerUsage = usageMetadata?.let { metadata ->
        ProviderUsage(
            promptTokens = metadata.promptTokenCount,
            completionTokens = (metadata.candidatesTokenCount ?: 0) + (metadata.thoughtsTokenCount ?: 0),
            totalTokens = metadata.totalTokenCount
        )
    }

    return usageFromProviderOrEstimate(
        providerUsage = providerUsage,
        requestText = NetworkClient.json.encodeToString(request),
        outputText = outputText,
        platform = platform,
        label = label
    )
}

private fun TokenUsageRecord.withToolAggregate(toolUsages: List<TokenUsageRecord>): TokenUsageRecord {
    val relatedUsages = toolUsages.map { usage -> usage.asToolRelated() }
    val toolInputTokens = relatedUsages.sumOf { usage -> usage.inputTokens }
    val toolOutputTokens = relatedUsages.sumOf { usage -> usage.outputTokens }
    val toolTotalTokens = relatedUsages.sumOf { usage -> usage.totalTokens }

    return copy(
        toolInputTokens = toolInputTokens,
        toolOutputTokens = toolOutputTokens,
        toolTotalTokens = toolTotalTokens,
        isEstimated = isEstimated || relatedUsages.any { usage -> usage.isEstimated },
        details = relatedUsages.flatMap { usage -> usage.details }
    )
}

private fun TokenUsageRecord.asToolRelated(): TokenUsageRecord = copy(
    toolInputTokens = inputTokens,
    toolOutputTokens = outputTokens,
    toolTotalTokens = totalTokens,
    details = details.map { detail -> detail.copy(isToolRelated = true) }
)

private fun aggregateToolUsage(
    currentAnswerUsage: TokenUsageRecord?,
    toolUsages: List<TokenUsageRecord>
): TokenUsageRecord? {
    val answerUsage = currentAnswerUsage ?: toolUsages.lastOrNull()?.copy(
        inputTokens = 0,
        outputTokens = 0,
        totalTokens = 0,
        details = emptyList()
    )
    return answerUsage?.withToolAggregate(toolUsages)
}

private fun chatCompletionStreamOptionsFor(platform: PlatformV2): ChatCompletionStreamOptions? = when (platform.compatibleType) {
    ClientType.OPENAI,
    ClientType.OPENROUTER -> ChatCompletionStreamOptions()
    ClientType.GROQ,
    ClientType.OLLAMA,
    ClientType.ANTHROPIC,
    ClientType.GOOGLE,
    ClientType.CUSTOM -> null
}
