package dev.chungjungsoo.gptmobile.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.context.ContextBuilder
import dev.chungjungsoo.gptmobile.data.context.ConversationContext
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.ProviderContextPolicy
import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomDao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageDao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoom
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.Message
import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceMetadata
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.ImageContent as AnthropicImageContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.ImageSource
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MediaType
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageContent as AnthropicMessageContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageRole
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.TextContent as AnthropicTextContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.InputMessage
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest
import dev.chungjungsoo.gptmobile.data.dto.google.common.Content
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part
import dev.chungjungsoo.gptmobile.data.dto.google.common.Role as GoogleRole
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerationConfig
import dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.common.ImageContent as OpenAIImageContent
import dev.chungjungsoo.gptmobile.data.dto.openai.common.ImageUrl
import dev.chungjungsoo.gptmobile.data.dto.openai.common.MessageContent as OpenAIMessageContent
import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role as OpenAIRole
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent as OpenAITextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ReasoningConfig
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseContentPart
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseTool
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseToolChoice
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputTextDeltaEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputTextDoneEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ReasoningSummaryTextDeltaEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseFailedEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.model.ApiType
import dev.chungjungsoo.gptmobile.data.model.ChatPlatformConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ReasoningMode
import dev.chungjungsoo.gptmobile.data.model.defaultReasoningMode
import dev.chungjungsoo.gptmobile.data.model.isGptOssModel
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolCall
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopOrchestrator
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopResult
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import dev.chungjungsoo.gptmobile.data.tool.toolProtocolJson
import dev.chungjungsoo.gptmobile.data.tool.provider.AnthropicToolAdapter
import dev.chungjungsoo.gptmobile.data.tool.provider.GoogleToolAdapter
import dev.chungjungsoo.gptmobile.data.tool.provider.OpenAICompatibleJsonToolAdapter
import dev.chungjungsoo.gptmobile.data.tool.provider.OpenAIResponsesToolAdapter
import dev.chungjungsoo.gptmobile.data.tool.provider.ToolCallingAdapter
import dev.chungjungsoo.gptmobile.data.websearch.SearchDecision
import dev.chungjungsoo.gptmobile.data.websearch.SearchDecisionService
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchMode
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchPromptBuilder
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchResult
import dev.chungjungsoo.gptmobile.util.AttachmentPayloadCache
import dev.chungjungsoo.gptmobile.util.FileUtils
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage
import dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
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
    private val webSearchRepository: WebSearchRepository,
    private val toolLoopOrchestrator: ToolLoopOrchestrator,
    private val searchDecisionService: SearchDecisionService? = null,
    private val webSearchPromptBuilder: WebSearchPromptBuilder = WebSearchPromptBuilder(),
    private val openAIResponsesToolAdapter: OpenAIResponsesToolAdapter = OpenAIResponsesToolAdapter(),
    private val openAICompatibleJsonToolAdapter: ToolCallingAdapter = OpenAICompatibleJsonToolAdapter(),
    private val anthropicToolAdapter: ToolCallingAdapter = AnthropicToolAdapter(),
    private val googleToolAdapter: ToolCallingAdapter = GoogleToolAdapter()
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
        val webSearchMode = runCatching { settingRepository.fetchWebSearchMode() }
            .getOrNull()
            ?: WebSearchMode.Off
        return if (webSearchMode == WebSearchMode.Auto) {
            if (platform.compatibleType == ClientType.OPENAI) {
                completeChatWithOpenAIResponsesNativeToolLoop(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
            } else {
                completeChatWithToolLoopFallback(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
            }
        } else {
            completeChatByProvider(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
        }
    }

    private suspend fun completeChatByProvider(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        extraPrompt: String? = null
    ): Flow<ApiState> = when (platform.compatibleType) {
        ClientType.OPENAI -> {
            // Use Responses API for OpenAI (supports reasoning/thinking)
            completeChatWithOpenAIResponses(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode, extraPrompt)
        }

        ClientType.GROQ -> {
            completeChatWithGroq(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode, extraPrompt)
        }

        ClientType.OLLAMA, ClientType.OPENROUTER, ClientType.CUSTOM -> {
            // Use Chat Completions API for OpenAI-compatible services
            completeChatWithOpenAIChatCompletions(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode, extraPrompt)
        }

        ClientType.ANTHROPIC -> {
            completeChatWithAnthropic(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode, extraPrompt)
        }

        ClientType.GOOGLE -> {
            completeChatWithGoogle(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode, extraPrompt)
        }
    }

    private suspend fun completeChatWithToolLoopFallback(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode
    ): Flow<ApiState> = flow {
        emit(ApiState.Loading)
        val searchDecisionPrompt = executeSearchDecisionIfNeeded(
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            platform = platform
        )
        if (searchDecisionPrompt != null) {
            emitProviderStatesSkippingLoading(
                completeChatByProvider(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    extraPrompt = searchDecisionPrompt
                )
            )
            return@flow
        }

        val toolCallingAdapter = toolCallingAdapterFor(platform.compatibleType)
        val loopResult = toolLoopOrchestrator.runLoop(
            adapter = toolCallingAdapter,
            onProgress = { progress -> emit(progress) },
            requestModel = { toolPrompt ->
                collectProviderText(
                    completeChatByProvider(
                        userMessages = userMessages,
                        assistantMessages = assistantMessages,
                        platform = platform,
                        memoryPrompt = memoryPrompt,
                        reasoningMode = reasoningMode,
                        extraPrompt = toolPrompt
                    )
                )
            }
        )

        when (loopResult) {
            is ToolLoopResult.FinalAnswer -> {
                loopResult.content.takeIf { it.isNotBlank() }?.let { content ->
                    emit(ApiState.Success(content))
                }
                emit(ApiState.Done)
            }
            is ToolLoopResult.ToolResults -> {
                loopResult.results.toMessageSourceMetadata().takeIf { it.isNotEmpty() }?.let { sources ->
                    emit(ApiState.SourcesUpdated(sources))
                }
                emitProviderStatesSkippingLoading(
                    completeChatByProvider(
                        userMessages = userMessages,
                        assistantMessages = assistantMessages,
                        platform = platform,
                        memoryPrompt = memoryPrompt,
                        reasoningMode = reasoningMode,
                        extraPrompt = loopResult.finalAnswerPrompt
                    )
                )
            }
            is ToolLoopResult.Failed -> {
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
    }.catch { e ->
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
        platform: PlatformV2
    ): String? {
        val decision = runCatching {
            searchDecisionService?.decide(
                platform = platform,
                latestUserMessage = userMessages.lastOrNull()?.content.orEmpty(),
                recentContext = searchDecisionRecentContext(userMessages, assistantMessages, platform)
            )
        }.getOrNull()
            ?.takeIf { it.shouldSearch }
            ?: return null

        val calls = decision.toWebSearchToolCalls()
        if (calls.isEmpty()) return null

        val results = toolLoopOrchestrator.executeToolCalls(calls) { progress ->
            emit(progress)
        }
        results.toMessageSourceMetadata().takeIf { it.isNotEmpty() }?.let { sources ->
            emit(ApiState.SourcesUpdated(sources))
        }

        return openAICompatibleJsonToolAdapter.buildFinalAnswerPrompt(
            results = results,
            draftFinalAnswer = null,
            config = toolLoopOrchestrator.configuration
        )
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

    private suspend fun collectProviderText(states: Flow<ApiState>): Result<String> = runCatching {
        val text = StringBuilder()
        var errorMessage: String? = null
        states.collect { state ->
            when (state) {
                is ApiState.Success -> text.append(state.textChunk)
                is ApiState.Error -> errorMessage = errorMessage ?: state.message
                else -> {}
            }
        }
        errorMessage?.let { throw IllegalStateException(it) }
        text.toString()
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
        reasoningMode: ReasoningMode
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
                    OPENAI_NATIVE_TOOL_INSTRUCTION
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
        val tools = openAIResponsesToolAdapter.toResponseTools(toolLoopOrchestrator.toolDefinitions)
        val continuationItems = mutableListOf<ResponseInputItem>()
        val allResults = mutableListOf<ToolResult>()
        val maxRounds = config.maxToolRounds.coerceAtLeast(1)

        val searchDecisionPrompt = executeSearchDecisionIfNeeded(
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            platform = platform
        )
        if (searchDecisionPrompt != null) {
            emitProviderStatesSkippingLoading(
                completeChatWithOpenAIResponses(
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    platform = platform,
                    memoryPrompt = memoryPrompt,
                    reasoningMode = reasoningMode,
                    extraPrompt = searchDecisionPrompt
                )
            )
            return@flow
        }

        repeat(maxRounds) {
            val round = collectOpenAIResponsesNativeRound(
                request = prepared.toRequest(
                    continuationItems = continuationItems,
                    tools = tools,
                    toolChoice = ResponseToolChoice.Auto
                ),
                timeoutSeconds = platform.timeout
            )
            if (round.errorMessage != null) {
                if (allResults.isEmpty()) {
                    emitProviderStatesSkippingLoading(
                        completeChatWithOpenAIResponses(userMessages, assistantMessages, platform, memoryPrompt, reasoningMode)
                    )
                } else {
                    emit(ApiState.Error(round.errorMessage))
                    emit(ApiState.Done)
                }
                return@flow
            }

            val calls = openAIResponsesToolAdapter
                .toolCallsFromEvents(round.events)
                .distinctBy { call -> "${call.name}:${call.arguments}" }
                .take(config.maxToolCallsPerRound.coerceAtLeast(0))
            if (calls.isEmpty()) {
                emit(ApiState.Done)
                return@flow
            }

            val results = toolLoopOrchestrator.executeToolCalls(calls) { progress ->
                emit(progress)
            }
            allResults += results
            results.toMessageSourceMetadata().takeIf { it.isNotEmpty() }?.let { sources ->
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
            timeoutSeconds = platform.timeout
        )
        finalRound.errorMessage?.let { message ->
            emit(ApiState.Error(message))
        }
        emit(ApiState.Done)
    }.catch { e ->
        emit(ApiState.Error(e.message ?: "鏈煡閿欒"))
        emit(ApiState.Done)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ApiState>.collectOpenAIResponsesNativeRound(
        request: ResponsesRequest,
        timeoutSeconds: Int
    ): OpenAIResponsesNativeRound {
        val events = mutableListOf<ResponsesStreamEvent>()
        var errorMessage: String? = null
        var emittedOutputTextDelta = false

        openAIAPI.streamResponses(request, timeoutSeconds).collect { event ->
            events += event
            when (event) {
                is ReasoningSummaryTextDeltaEvent -> emit(ApiState.Thinking(event.delta))

                is OutputTextDeltaEvent -> {
                    emittedOutputTextDelta = true
                    emit(ApiState.Success(event.delta))
                }

                is OutputTextDoneEvent -> {
                    if (!emittedOutputTextDelta && event.text.isNotBlank()) {
                        emittedOutputTextDelta = true
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

        return OpenAIResponsesNativeRound(
            events = events,
            errorMessage = errorMessage
        )
    }

    private suspend fun completeChatWithOpenAIResponses(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode,
        extraPrompt: String? = null
    ): Flow<ApiState> = try {
        openAIAPI.setToken(platform.token)
        openAIAPI.setAPIUrl(platform.apiUrl)

        streamPreparedApiState(
            prepare = {
                val reasoningParameters = mapReasoningMode(platform, reasoningMode)
                val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
                val webSearchContext = prepareWebSearchContext(userMessages)
                val inputMessages = buildResponsesInputMessages(conversationContext.turns, platform.uid)

                ProviderRequestWithSources(
                    request = ResponsesRequest(
                        model = platform.model,
                        input = inputMessages,
                        stream = true,
                        instructions = mergePromptSections(platform.systemPrompt, currentRuntimeContextPrompt(), memoryPrompt, conversationContext.summary, webSearchContext.prompt, extraPrompt),
                        temperature = if (reasoningParameters.hasExplicitReasoning) null else platform.temperature,
                        topP = if (reasoningParameters.hasExplicitReasoning) null else platform.topP,
                        reasoning = reasoningParameters.openAIEffort?.let { effort ->
                            ReasoningConfig(
                                effort = effort,
                                summary = "auto"
                            )
                        }
                    ),
                    sources = webSearchContext.sources
                )
            },
            stream = { prepared ->
                flow {
                    openAIAPI.streamResponses(prepared.request, platform.timeout).collect { event ->
                        when (event) {
                            is ReasoningSummaryTextDeltaEvent -> emit(ApiState.Thinking(event.delta))

                            is OutputTextDeltaEvent -> emit(ApiState.Success(event.delta))

                            is ResponseFailedEvent -> {
                                val errorMessage = event.response.error?.message ?: "响应失败"
                                emit(ApiState.Error(errorMessage))
                            }

                            is ResponseErrorEvent -> emit(ApiState.Error(event.message))

                            else -> {}
                        }
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
        extraPrompt: String? = null
    ): Flow<ApiState> = try {
        streamPreparedApiState(
            prepare = {
                val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
                val webSearchContext = prepareWebSearchContext(userMessages)
                validateInlineBudgetIfNeeded(conversationContext.turns, platform)
                val messages = buildOpenAIChatMessages(
                    conversationContext.turns,
                    mergePromptSections(platform.systemPrompt, currentRuntimeContextPrompt(), memoryPrompt, conversationContext.summary, webSearchContext.prompt, extraPrompt)
                )

                ProviderRequestWithSources(
                    request = createGroqChatCompletionRequest(messages, platform, reasoningMode),
                    sources = webSearchContext.sources
                )
            },
            stream = { prepared ->
                flow {
                    val parser = GroqReasoningParser()
                    groqAPI.streamChatCompletion(
                        request = prepared.request,
                        timeoutSeconds = platform.timeout,
                        token = platform.token,
                        apiUrl = platform.apiUrl
                    ).collect { chunk ->
                        when {
                            chunk.error != null -> emit(ApiState.Error(chunk.error.message))

                            else -> {
                                val choice = chunk.choices?.firstOrNull()
                                parser.append(
                                    reasoningChunk = choice?.delta?.reasoning ?: choice?.message?.reasoning,
                                    contentChunk = choice?.delta?.content ?: choice?.message?.content
                                ).forEach { emit(it) }
                            }
                        }
                    }

                    parser.flush().forEach { emit(it) }
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
        extraPrompt: String? = null
    ): Flow<ApiState> = try {
        openAIAPI.setToken(platform.token)
        openAIAPI.setAPIUrl(platform.apiUrl)

        streamPreparedApiState(
            prepare = {
                val reasoningParameters = mapReasoningMode(platform, reasoningMode)
                val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
                val webSearchContext = prepareWebSearchContext(userMessages)
                validateInlineBudgetIfNeeded(conversationContext.turns, platform)
                val messages = buildOpenAIChatMessages(
                    conversationContext.turns,
                    mergePromptSections(platform.systemPrompt, currentRuntimeContextPrompt(), memoryPrompt, conversationContext.summary, webSearchContext.prompt, extraPrompt)
                )

                ProviderRequestWithSources(
                    request = ChatCompletionRequest(
                        model = platform.model,
                        messages = messages,
                        stream = platform.stream,
                        temperature = platform.temperature,
                        topP = platform.topP,
                        reasoningEffort = reasoningParameters.openAICompatibleReasoningEffort
                    ),
                    sources = webSearchContext.sources
                )
            },
            stream = { prepared ->
                flow {
                    openAIAPI.streamChatCompletion(prepared.request, platform.timeout).collect { chunk ->
                        when {
                            chunk.error != null -> emit(ApiState.Error(chunk.error.message))

                            chunk.choices?.firstOrNull()?.delta?.content != null -> {
                                emit(ApiState.Success(chunk.choices.first().delta.content!!))
                            }
                        }
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
        if (!policy.preferProviderFileRefs || conversationContext.turns.isEmpty()) {
            return conversationContext
        }

        return conversationContext.copy(
            turns = ensureProviderReferencesForTurns(conversationContext.turns, platform)
        )
    }

    private suspend fun prepareWebSearchContext(userMessages: List<MessageV2>): WebSearchContext {
        val webSearchMode = runCatching { settingRepository.fetchWebSearchMode() }
            .getOrNull()
            ?: WebSearchMode.Off
        val latestUserMessage = userMessages.lastOrNull()
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return WebSearchContext()

        val searchQueries = when (webSearchMode) {
            WebSearchMode.Off -> return WebSearchContext()
            WebSearchMode.Always -> listOf(latestUserMessage)
            WebSearchMode.Auto -> return WebSearchContext()
        }

        val results = searchWebQueries(searchQueries)
        return WebSearchContext(
            prompt = webSearchPromptBuilder.build(results),
            sources = results.toMessageSourceMetadata(ToolDefinition.WebSearch.name)
        )
    }

    private suspend fun searchWebQueries(queries: List<String>): List<WebSearchResult> =
        queries
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_SEARCH_QUERY_COUNT)
            .flatMap { query ->
                webSearchRepository.search(query, WEB_SEARCH_RESULT_LIMIT)
                    .getOrDefault(emptyList())
            }
            .distinctBy { it.url }
            .take(WEB_SEARCH_RESULT_LIMIT)

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
            if (providerRef?.remoteType == dev.chungjungsoo.gptmobile.data.model.AttachmentRemoteType.OPENAI_FILE) {
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
        extraPrompt: String? = null
    ): Flow<ApiState> = try {
        anthropicAPI.setToken(platform.token)
        anthropicAPI.setAPIUrl(platform.apiUrl)

        streamPreparedApiState(
            prepare = {
                val reasoningParameters = mapReasoningMode(platform, reasoningMode)
                val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
                val webSearchContext = prepareWebSearchContext(userMessages)
                val messages = buildAnthropicInputMessages(conversationContext.turns, platform.uid)

                ProviderRequestWithSources(
                    request = MessageRequest(
                        model = platform.model,
                        messages = messages,
                        maxTokens = reasoningParameters.anthropicMaxTokens ?: 4096,
                        stream = platform.stream,
                        systemPrompt = mergePromptSections(platform.systemPrompt, currentRuntimeContextPrompt(), memoryPrompt, conversationContext.summary, webSearchContext.prompt, extraPrompt),
                        temperature = if (reasoningParameters.hasExplicitReasoning) null else platform.temperature,
                        topP = if (reasoningParameters.hasExplicitReasoning) null else platform.topP,
                        thinking = reasoningParameters.anthropicBudgetTokens?.let { budgetTokens ->
                            dev.chungjungsoo.gptmobile.data.dto.anthropic.request.ThinkingConfig(
                                type = "enabled",
                                budgetTokens = budgetTokens
                            )
                        }
                    ),
                    sources = webSearchContext.sources
                )
            },
            stream = { prepared ->
                flow {
                    anthropicAPI.streamChatMessage(prepared.request, platform.timeout).collect { chunk ->
                        when (chunk) {
                            is dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentDeltaResponseChunk -> {
                                when (chunk.delta.type) {
                                    dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlockType.THINKING_DELTA -> {
                                        chunk.delta.thinking?.let { emit(ApiState.Thinking(it)) }
                                    }

                                    dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlockType.DELTA -> {
                                        chunk.delta.text?.let { emit(ApiState.Success(it)) }
                                    }

                                    else -> {}
                                }
                            }

                            is dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ErrorResponseChunk -> {
                                emit(ApiState.Error(chunk.error.message))
                            }

                            else -> {}
                        }
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
            if (providerRef?.remoteType == dev.chungjungsoo.gptmobile.data.model.AttachmentRemoteType.ANTHROPIC_FILE) {
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
        extraPrompt: String? = null
    ): Flow<ApiState> = try {
        googleAPI.setToken(platform.token)
        googleAPI.setAPIUrl(platform.apiUrl)

        streamPreparedApiState(
            prepare = {
                val reasoningParameters = mapReasoningMode(platform, reasoningMode)
                val conversationContext = buildConversationContext(userMessages, assistantMessages, platform)
                val webSearchContext = prepareWebSearchContext(userMessages)
                val contents = buildGoogleContents(conversationContext.turns, platform.uid)

                ProviderRequestWithSources(
                    request = GenerateContentRequest(
                        contents = contents,
                        generationConfig = GenerationConfig(
                            temperature = platform.temperature,
                            topP = platform.topP,
                            thinkingConfig = reasoningParameters.googleThinkingBudget?.let { thinkingBudget ->
                                dev.chungjungsoo.gptmobile.data.dto.google.request.ThinkingConfig(
                                    thinkingBudget = thinkingBudget,
                                    includeThoughts = reasoningParameters.googleIncludeThoughts ?: false
                                )
                            }
                        ),
                        systemInstruction = mergePromptSections(platform.systemPrompt, currentRuntimeContextPrompt(), memoryPrompt, conversationContext.summary, webSearchContext.prompt, extraPrompt)?.let {
                            Content(
                                parts = listOf(Part.text(it))
                            )
                        }
                    ),
                    sources = webSearchContext.sources
                )
            },
            stream = { prepared ->
                flow {
                    googleAPI.streamGenerateContent(prepared.request, platform.model, platform.timeout).collect { response ->
                        when {
                            response.error != null -> emit(ApiState.Error(response.error.message))

                            response.candidates?.firstOrNull()?.content?.parts != null -> {
                                val parts = response.candidates.first().content.parts
                                parts.forEach { part ->
                                    part.text?.let { text ->
                                        if (part.thought == true) {
                                            emit(ApiState.Thinking(text))
                                        } else {
                                            emit(ApiState.Success(text))
                                        }
                                    }
                                }
                            }
                        }
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
            if (providerRef?.remoteType == dev.chungjungsoo.gptmobile.data.model.AttachmentRemoteType.GOOGLE_FILE) {
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
            // New Chat
            val chatId = chatRoomV2Dao.addChatRoom(chatRoom)
            val updatedMessages = messages.map { it.copy(chatId = chatId.toInt()) }
            messageV2Dao.addMessages(*updatedMessages.toTypedArray())
            saveChatPlatformModels(
                chatId = chatId.toInt(),
                models = chatPlatformModels.filterKeys { it in chatRoom.enabledPlatform }
            )

            val savedChatRoom = chatRoom.copy(id = chatId.toInt())
            updateChatTitle(savedChatRoom, updatedMessages[0].content)

            return savedChatRoom.copy(title = updatedMessages[0].content.replace('\n', ' ').take(50))
        }

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

        return chatRoom
    }

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
        includeReasoning = reasoningParameters.groqIncludeReasoning
    )
}

internal fun isGroqGptOssModel(model: String): Boolean = isGptOssModel(model)

private const val WEB_SEARCH_RESULT_LIMIT = 5
private const val MAX_SEARCH_QUERY_COUNT = 2
private const val MAX_SOURCE_SNIPPET_CHARS = 240
private const val OPENAI_NATIVE_TOOL_INSTRUCTION =
    "Use the available tools only when the latest user request needs current web information or source inspection. " +
        "Do not use web_search for the user's local date, time, timezone, device state, or app settings. " +
        "Prefer answering directly when the conversation is enough. If you use web sources, cite source URLs in the answer."
private const val OPENAI_NATIVE_FINAL_TOOL_INSTRUCTION =
    "Do not call more tools. Use the available function_call_output items when relevant and provide the final answer."

private data class WebSearchContext(
    val prompt: String? = null,
    val sources: List<MessageSourceMetadata> = emptyList()
)

private data class ProviderRequestWithSources<T>(
    val request: T,
    val sources: List<MessageSourceMetadata>
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
    val errorMessage: String?
)

private fun List<WebSearchResult>.toMessageSourceMetadata(sourceToolName: String): List<MessageSourceMetadata> =
    mapNotNull { result ->
        result.url.trim().takeIf { it.isNotBlank() }?.let { url ->
            MessageSourceMetadata(
                title = result.title.trim().ifBlank { url },
                url = url,
                snippet = result.snippet.toSourceSnippet(),
                sourceToolName = sourceToolName
            )
        }
    }.dedupeMessageSources()

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

private fun List<ToolResult>.toMessageSourceMetadata(): List<MessageSourceMetadata> =
    flatMap { result -> result.toMessageSourceMetadata() }.dedupeMessageSources()

private fun ToolResult.toMessageSourceMetadata(): List<MessageSourceMetadata> = when (name) {
    ToolDefinition.WebSearch.name -> {
        val sources = mutableListOf<MessageSourceMetadata>()
        var index = 0
        while (true) {
            val url = metadata["source_${index}_url"]?.trim()?.takeIf { it.isNotBlank() } ?: break
            sources += MessageSourceMetadata(
                title = metadata["source_${index}_title"]?.trim()?.takeIf { it.isNotBlank() } ?: url,
                url = url,
                snippet = metadata["source_${index}_snippet"].orEmpty().toSourceSnippet(),
                sourceToolName = metadata["source_${index}_tool"]?.trim()?.takeIf { it.isNotBlank() } ?: name
            )
            index += 1
        }
        sources
    }
    ToolDefinition.FetchUrl.name -> {
        val url = metadata["url"]?.trim()?.takeIf { it.isNotBlank() }
        if (url == null) {
            emptyList()
        } else {
            listOf(
                MessageSourceMetadata(
                    title = metadata["title"]?.trim()?.takeIf { it.isNotBlank() } ?: url,
                    url = url,
                    snippet = metadata["snippet"].orEmpty().toSourceSnippet(),
                    sourceToolName = metadata["source_tool"]?.trim()?.takeIf { it.isNotBlank() } ?: name
                )
            )
        }
    }
    else -> emptyList()
}

private fun List<MessageSourceMetadata>.dedupeMessageSources(): List<MessageSourceMetadata> =
    filter { source -> source.url.isNotBlank() }
        .distinctBy { source -> source.url.trim().lowercase() }

private fun String.toSourceSnippet(): String = trim()
    .replace(Regex("\\s+"), " ")
    .take(MAX_SOURCE_SNIPPET_CHARS)

internal fun mergeSystemPrompt(basePrompt: String?, memoryPrompt: String?): String? = mergePromptSections(basePrompt, memoryPrompt)

internal fun currentRuntimeContextPrompt(): String {
    val zone = ZoneId.systemDefault()
    val now = ZonedDateTime.now(zone)
    return "Runtime context:\n" +
        "- Current local date/time: ${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)} (${zone.id}).\n" +
        "- Use this runtime context for simple date, time, and timezone questions. Do not call web_search to determine local clock time."
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
