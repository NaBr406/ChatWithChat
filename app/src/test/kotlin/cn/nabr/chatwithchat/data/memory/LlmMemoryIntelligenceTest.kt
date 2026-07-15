package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.Platform
import cn.nabr.chatwithchat.data.dto.ThemeSetting
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentBlock
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentBlockType
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentDeltaResponseChunk
import cn.nabr.chatwithchat.data.dto.google.common.Content
import cn.nabr.chatwithchat.data.dto.google.common.Part
import cn.nabr.chatwithchat.data.dto.google.response.Candidate
import cn.nabr.chatwithchat.data.dto.google.response.GenerateContentResponse
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.dto.openai.response.Choice
import cn.nabr.chatwithchat.data.dto.openai.response.Delta
import cn.nabr.chatwithchat.data.dto.openai.response.OutputTextDeltaEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesStreamEvent
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.model.LastSelectedModel
import cn.nabr.chatwithchat.data.model.ModelRefreshResult
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.network.UploadedProviderFile
import cn.nabr.chatwithchat.data.repository.SettingRepository
import cn.nabr.chatwithchat.data.tool.ToolCallingMode
import cn.nabr.chatwithchat.data.websearch.WebSearchMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmMemoryIntelligenceTest {

    @Test
    fun `openai memory platform uses responses api for batch consolidation`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            responseEvents = flowOf(
                OutputTextDeltaEvent(
                    itemId = "item",
                    outputIndex = 0,
                    contentIndex = 0,
                    delta = EMPTY_PROPOSAL_JSON
                )
            )
        )
        val intelligence = intelligence(
            platforms = listOf(platform(ClientType.OPENAI, "gpt-5", reasoning = true)),
            openAIAPI = openAIAPI
        )

        val result = intelligence.consolidateMemoryBatch(batchRequest())

        assertEquals(0, result?.operations?.size)
        assertEquals(1, openAIAPI.streamResponsesCalls)
        assertEquals(0, openAIAPI.streamChatCompletionCalls)
        assertNull(openAIAPI.lastResponsesRequest?.temperature)
        assertNull(openAIAPI.lastResponsesRequest?.topP)
        assertEquals("low", openAIAPI.lastResponsesRequest?.reasoning?.effort)
        assertEquals(1200, openAIAPI.lastResponsesRequest?.maxOutputTokens)
        assertEquals(120, openAIAPI.lastResponsesTimeoutSeconds)
    }

    @Test
    fun `openai compatible platform uses deterministic batch sampling`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_PROPOSAL_JSON))
        val intelligence = intelligence(
            platforms = listOf(platform(ClientType.OPENROUTER, "openai/gpt-4o")),
            openAIAPI = openAIAPI
        )

        intelligence.consolidateMemoryBatch(batchRequest())

        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertEquals(0f, openAIAPI.lastChatRequest?.temperature)
        assertEquals(1f, openAIAPI.lastChatRequest?.topP)
        assertEquals(1200, openAIAPI.lastChatRequest?.maxTokens)
        assertEquals(120, openAIAPI.lastChatTimeoutSeconds)
    }

    @Test
    fun `preferred batch platform overrides fallback platform`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_PROPOSAL_JSON))
        val intelligence = intelligence(
            platforms = listOf(platform(ClientType.OPENROUTER, "fallback-model")),
            openAIAPI = openAIAPI
        )

        intelligence.consolidateMemoryBatch(
            batchRequest(),
            preferredPlatform = platform(ClientType.CUSTOM, "current-chat-model")
        )

        assertEquals("current-chat-model", openAIAPI.lastChatRequest?.model)
    }

    @Test
    fun `preferred anthropic platform executes the same batch contract`() = runBlocking {
        val anthropicAPI = RecordingAnthropicAPI(
            chunks = flowOf(
                ContentDeltaResponseChunk(
                    index = 0,
                    delta = ContentBlock(type = ContentBlockType.DELTA, text = EMPTY_PROPOSAL_JSON)
                )
            )
        )
        val intelligence = intelligence(
            platforms = listOf(platform(ClientType.OPENROUTER, "fallback-model")),
            anthropicAPI = anthropicAPI
        )

        val result = intelligence.consolidateMemoryBatch(
            batchRequest(),
            preferredPlatform = platform(ClientType.ANTHROPIC, "claude-current")
        )

        assertEquals(0, result?.operations?.size)
        assertEquals(1, anthropicAPI.streamCalls)
        assertEquals("claude-current", anthropicAPI.lastRequest?.model)
        assertEquals(1200, anthropicAPI.lastRequest?.maxTokens)
        assertEquals(120, anthropicAPI.lastTimeoutSeconds)
    }

    @Test
    fun `preferred google platform executes the same batch contract`() = runBlocking {
        val googleAPI = RecordingGoogleAPI(
            responses = flowOf(
                GenerateContentResponse(
                    candidates = listOf(Candidate(content = Content(parts = listOf(Part.text(EMPTY_PROPOSAL_JSON)))))
                )
            )
        )
        val intelligence = intelligence(
            platforms = listOf(platform(ClientType.OPENROUTER, "fallback-model")),
            googleAPI = googleAPI
        )

        val result = intelligence.consolidateMemoryBatch(
            batchRequest(),
            preferredPlatform = platform(ClientType.GOOGLE, "gemini-current")
        )

        assertEquals(0, result?.operations?.size)
        assertEquals(1, googleAPI.streamCalls)
        assertEquals("gemini-current", googleAPI.lastModel)
        assertEquals(1200, googleAPI.lastRequest?.generationConfig?.maxOutputTokens)
        assertEquals(120, googleAPI.lastTimeoutSeconds)
    }

    @Test
    fun `batch timeout preserves disabled timeout setting`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_PROPOSAL_JSON))
        val intelligence = intelligence(
            platforms = listOf(platform(ClientType.OPENROUTER, "model", timeout = 0)),
            openAIAPI = openAIAPI
        )

        intelligence.consolidateMemoryBatch(batchRequest())

        assertEquals(0, openAIAPI.lastChatTimeoutSeconds)
    }

    @Test
    fun `batch timeout preserves larger user timeout`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_PROPOSAL_JSON))
        val intelligence = intelligence(
            platforms = listOf(platform(ClientType.OPENROUTER, "model", timeout = 180)),
            openAIAPI = openAIAPI
        )

        intelligence.consolidateMemoryBatch(batchRequest())

        assertEquals(180, openAIAPI.lastChatTimeoutSeconds)
    }

    @Test
    fun `batch consolidation rejects non strict json with one provider call`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatChunks = chatChunks("""{"operations":[],"unexpected":true}""")
        )
        val activityLogger = RecordingMemoryActivityLogger()
        val intelligence = intelligence(
            platforms = listOf(platform(ClientType.OPENROUTER, "model")),
            openAIAPI = openAIAPI,
            activityLogger = activityLogger
        )

        val result = intelligence.consolidateMemoryBatch(batchRequest())

        assertNull(result)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertEquals(MemoryActivityStatus.SUCCEEDED, activityLogger.finishedStatus(MemoryActivityCategory.MODEL_CALL))
        assertEquals(MemoryActivityStatus.FAILED, activityLogger.finishedStatus(MemoryActivityCategory.MEMORY_GENERATION))
    }

    @Test
    fun `missing memory platform records model call and generation failures`() = runBlocking {
        val activityLogger = RecordingMemoryActivityLogger()
        val intelligence = intelligence(
            platforms = emptyList(),
            activityLogger = activityLogger
        )

        val result = intelligence.consolidateMemoryBatch(batchRequest())

        assertNull(result)
        assertEquals(MemoryActivityStatus.FAILED, activityLogger.finishedStatus(MemoryActivityCategory.MODEL_CALL))
        assertEquals(MemoryActivityStatus.FAILED, activityLogger.finishedStatus(MemoryActivityCategory.MEMORY_GENERATION))
    }

    @Test
    fun `daily distillation uses one strict provider request`() = runBlocking {
        val response =
            """{"operations":[{"action":"create","text":"Prefers concise answers.","type":"communication_style","sensitivity":"normal","source":"explicit_user_statement","evidenceKeys":["evidence-1"],"reason":"stable preference"}]}"""
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(response))
        val intelligence = intelligence(
            platforms = listOf(platform(ClientType.OPENROUTER, "model")),
            openAIAPI = openAIAPI
        )

        val result = intelligence.distillDailyMemory(dailyRequest())

        assertEquals(1, result?.operations?.size)
        assertEquals(MemoryDailyDistillationAction.CREATE, result?.operations?.single()?.action)
        assertEquals(listOf("evidence-1"), result?.operations?.single()?.evidenceKeys)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertEquals(120, openAIAPI.lastChatTimeoutSeconds)
        assertEquals(1200, openAIAPI.lastChatRequest?.maxTokens)
    }

    @Test
    fun `daily distillation rejects non strict json after one call`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatChunks = chatChunks("""{"operations":[],"unexpected":true}""")
        )
        val intelligence = intelligence(
            platforms = listOf(platform(ClientType.OPENROUTER, "model")),
            openAIAPI = openAIAPI
        )

        val result = intelligence.distillDailyMemory(dailyRequest())

        assertNull(result)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
    }

    private fun intelligence(
        platforms: List<PlatformV2>,
        openAIAPI: RecordingOpenAIAPI = RecordingOpenAIAPI(),
        anthropicAPI: RecordingAnthropicAPI = RecordingAnthropicAPI(),
        googleAPI: RecordingGoogleAPI = RecordingGoogleAPI(),
        activityLogger: MemoryActivityLogger = MemoryActivityLogger.None
    ) = LlmMemoryIntelligence(
        settingRepository = FakeSettingRepository(platforms),
        openAIAPI = openAIAPI,
        anthropicAPI = anthropicAPI,
        googleAPI = googleAPI,
        activityLogger = activityLogger
    )

    private fun chatChunks(content: String): Flow<ChatCompletionChunk> = flowOf(
        ChatCompletionChunk(
            choices = listOf(Choice(index = 0, delta = Delta(content = content)))
        )
    )

    private fun batchRequest() = MemoryBatchConsolidationRequest(
        batchId = "memory_batch:1:first:last:hash",
        chatId = 1,
        chatTitle = "Chat",
        triggerReason = MemoryTurnBatchTriggerReason.THRESHOLD,
        turns = listOf(
            MemoryCompletedTurnSnapshot(
                turnKey = "chat:1:user:1",
                chatId = 1,
                chatTitle = "Chat",
                userMessageId = 1,
                userContent = "Remember that I prefer concise answers.",
                userAttachments = emptyList(),
                assistantPlatformUid = "platform",
                assistantContent = "Understood.",
                completedAt = 100L
            )
        ),
        existingMemories = emptyList()
    )

    private fun dailyRequest() = MemoryDailyDistillationFrozenInput(
        batchId = "daily-batch",
        batchKey = "batch-0000",
        dailySourcePath = "memory/2026-07-11.md",
        dailySourceHash = "d".repeat(64),
        dailyDate = "2026-07-11",
        dailyEvidence = listOf(
            MemoryDailyDistillationEvidence(
                evidenceKey = "evidence-1",
                entryId = "daily-1",
                text = "The user explicitly prefers concise answers.",
                type = "communication_style",
                sensitivity = MemorySensitivity.NORMAL,
                source = MemorySource.EXPLICIT_USER_STATEMENT,
                createdAt = 1,
                updatedAt = 2
            )
        ),
        existingMemories = emptyList(),
        targetBaseHash = "b".repeat(64),
        createdAt = 10
    )

    private fun platform(
        compatibleType: ClientType,
        model: String,
        reasoning: Boolean = false,
        timeout: Int = 30
    ) = PlatformV2(
        name = compatibleType.name,
        compatibleType = compatibleType,
        enabled = true,
        apiUrl = "https://example.test/",
        token = "token",
        model = model,
        reasoning = reasoning,
        timeout = timeout
    )

    private companion object {
        const val EMPTY_PROPOSAL_JSON = """{"operations":[]}"""
    }
}

private class FakeSettingRepository(
    private val platforms: List<PlatformV2>
) : SettingRepository {
    override suspend fun fetchPlatforms(): List<Platform> = emptyList()
    override suspend fun fetchPlatformV2s(): List<PlatformV2> = platforms
    override suspend fun fetchPlatformModels(): List<PlatformModelV2> = emptyList()
    override suspend fun fetchPlatformModels(platformUid: String): List<PlatformModelV2> = emptyList()
    override suspend fun fetchEnabledChatModels(): List<AvailableChatModel> = emptyList()
    override suspend fun resolveDefaultChatModel(): AvailableChatModel? = null
    override suspend fun fetchThemes(): ThemeSetting = ThemeSetting()
    override suspend fun fetchLastSelectedModel(): LastSelectedModel? = null
    override suspend fun fetchMemoryEnabled(): Boolean = false
    override suspend fun fetchMemoryMaintenanceNotificationsEnabled(): Boolean = true
    override suspend fun fetchToolCallingMode(): ToolCallingMode = ToolCallingMode.Off
    override suspend fun fetchDisabledToolNames(): Set<String> = emptySet()
    override suspend fun fetchWebSearchMode(): WebSearchMode = WebSearchMode.Off
    override suspend fun fetchWebSearchSearxngBaseUrl(): String = ""
    override suspend fun migrateToPlatformV2() = Unit
    override suspend fun updatePlatforms(platforms: List<Platform>) = Unit
    override suspend fun updateThemes(themeSetting: ThemeSetting) = Unit
    override suspend fun updateLastSelectedModel(platformUid: String, model: String, reasoningMode: ReasoningMode) = Unit
    override suspend fun updateMemoryEnabled(enabled: Boolean) = Unit
    override suspend fun updateMemoryMaintenanceNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun updateToolCallingMode(mode: ToolCallingMode) = Unit
    override suspend fun updateToolEnabled(toolName: String, enabled: Boolean) = Unit
    override suspend fun updateWebSearchMode(mode: WebSearchMode) = Unit
    override suspend fun updateWebSearchSearxngBaseUrl(baseUrl: String) = Unit
    override suspend fun refreshPlatformModels(platformUid: String): ModelRefreshResult = ModelRefreshResult(platforms.first(), emptyList())
    override suspend fun updatePlatformModelEnabled(platformUid: String, modelId: String, enabled: Boolean) = Unit
    override suspend fun setPlatformDefaultModel(platformUid: String, modelId: String) = Unit
    override suspend fun addPlatformV2(platform: PlatformV2) = Unit
    override suspend fun updatePlatformV2(platform: PlatformV2) = Unit
    override suspend fun deletePlatformV2(platform: PlatformV2) = Unit
    override suspend fun getPlatformV2ById(id: Int): PlatformV2? = platforms.firstOrNull { it.id == id }
}

private class RecordingOpenAIAPI(
    private val chatChunks: Flow<ChatCompletionChunk> = emptyFlow(),
    private val responseEvents: Flow<ResponsesStreamEvent> = emptyFlow()
) : OpenAIAPI {
    var streamChatCompletionCalls = 0
    var streamResponsesCalls = 0
    var lastChatRequest: ChatCompletionRequest? = null
    var lastResponsesRequest: ResponsesRequest? = null
    var lastChatTimeoutSeconds: Int? = null
    var lastResponsesTimeoutSeconds: Int? = null

    override fun setToken(token: String?) = Unit
    override fun setAPIUrl(url: String) = Unit

    override fun streamChatCompletion(request: ChatCompletionRequest, timeoutSeconds: Int): Flow<ChatCompletionChunk> {
        streamChatCompletionCalls += 1
        lastChatRequest = request
        lastChatTimeoutSeconds = timeoutSeconds
        return chatChunks
    }

    override fun streamResponses(request: ResponsesRequest, timeoutSeconds: Int): Flow<ResponsesStreamEvent> {
        streamResponsesCalls += 1
        lastResponsesRequest = request
        lastResponsesTimeoutSeconds = timeoutSeconds
        return responseEvents
    }

    override suspend fun uploadFile(filePath: String, fileName: String, mimeType: String): UploadedProviderFile =
        error("Not used")
    override suspend fun isFileAvailable(fileId: String): Boolean = error("Not used")
}

private class RecordingAnthropicAPI(
    private val chunks: Flow<cn.nabr.chatwithchat.data.dto.anthropic.response.MessageResponseChunk> = emptyFlow()
) : AnthropicAPI {
    var streamCalls = 0
    var lastRequest: cn.nabr.chatwithchat.data.dto.anthropic.request.MessageRequest? = null
    var lastTimeoutSeconds: Int? = null

    override fun setToken(token: String?) = Unit
    override fun setAPIUrl(url: String) = Unit

    override fun streamChatMessage(
        messageRequest: cn.nabr.chatwithchat.data.dto.anthropic.request.MessageRequest,
        timeoutSeconds: Int
    ): Flow<cn.nabr.chatwithchat.data.dto.anthropic.response.MessageResponseChunk> {
        streamCalls += 1
        lastRequest = messageRequest
        lastTimeoutSeconds = timeoutSeconds
        return chunks
    }

    override suspend fun uploadFile(filePath: String, fileName: String, mimeType: String): UploadedProviderFile =
        error("Not used")
    override suspend fun isFileAvailable(fileId: String): Boolean = error("Not used")
}

private class RecordingGoogleAPI(
    private val responses: Flow<GenerateContentResponse> = emptyFlow()
) : GoogleAPI {
    var streamCalls = 0
    var lastRequest: cn.nabr.chatwithchat.data.dto.google.request.GenerateContentRequest? = null
    var lastModel: String? = null
    var lastTimeoutSeconds: Int? = null

    override fun setToken(token: String?) = Unit
    override fun setAPIUrl(url: String) = Unit

    override fun streamGenerateContent(
        request: cn.nabr.chatwithchat.data.dto.google.request.GenerateContentRequest,
        model: String,
        timeoutSeconds: Int
    ): Flow<GenerateContentResponse> {
        streamCalls += 1
        lastRequest = request
        lastModel = model
        lastTimeoutSeconds = timeoutSeconds
        return responses
    }

    override suspend fun uploadFile(filePath: String, fileName: String, mimeType: String): UploadedProviderFile =
        error("Not used")
    override suspend fun isFileAvailable(fileName: String): Boolean = error("Not used")
}

private class RecordingMemoryActivityLogger : MemoryActivityLogger {
    private val categoriesById = mutableMapOf<String, String>()
    private val statusesByCategory = mutableMapOf<String, String>()

    override suspend fun start(
        batchId: String,
        category: String,
        platformName: String?,
        modelName: String?,
        attempt: Int?,
        turnCount: Int?
    ): String = "log-${categoriesById.size}".also { logId -> categoriesById[logId] = category }

    override suspend fun finish(logId: String, status: String, detail: String?, operationCount: Int?) {
        categoriesById[logId]?.let { category -> statusesByCategory[category] = status }
    }

    fun finishedStatus(category: String): String? = statusesByCategory[category]
}
