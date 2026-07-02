package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlock
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlockType
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentDeltaResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.google.common.Content
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part
import dev.chungjungsoo.gptmobile.data.dto.google.response.Candidate
import dev.chungjungsoo.gptmobile.data.dto.google.response.GenerateContentResponse
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputTextDeltaEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.model.AvailableChatModel
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.LastSelectedModel
import dev.chungjungsoo.gptmobile.data.model.ModelRefreshResult
import dev.chungjungsoo.gptmobile.data.model.ReasoningMode
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.UploadedProviderFile
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.tool.ToolCallingMode
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmMemoryIntelligenceTest {

    @Test
    fun `openai memory platform uses responses api and omits sampling for reasoning`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            responseEvents = flowOf(
                OutputTextDeltaEvent(
                    itemId = "item",
                    outputIndex = 0,
                    contentIndex = 0,
                    delta = classificationJson
                )
            )
        )
        val intelligence = LlmMemoryIntelligence(
            settingRepository = FakeSettingRepository(
                listOf(
                    platform(
                        compatibleType = ClientType.OPENAI,
                        reasoning = true,
                        model = "gpt-5"
                    )
                )
            ),
            openAIAPI = openAIAPI,
            anthropicAPI = RecordingAnthropicAPI(),
            googleAPI = RecordingGoogleAPI()
        )

        val result = intelligence.classifyConversation(classificationRequest(), preferredPlatform = null)

        assertEquals(true, result?.shouldLearnMemories)
        assertEquals(1, openAIAPI.streamResponsesCalls)
        assertEquals(0, openAIAPI.streamChatCompletionCalls)
        assertNull(openAIAPI.lastResponsesRequest?.temperature)
        assertNull(openAIAPI.lastResponsesRequest?.topP)
        assertEquals("low", openAIAPI.lastResponsesRequest?.reasoning?.effort)
        assertEquals(512, openAIAPI.lastResponsesRequest?.maxOutputTokens)
        assertEquals(60, openAIAPI.lastResponsesTimeoutSeconds)
    }

    @Test
    fun `openai compatible memory platform uses chat completions with deterministic sampling`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatChunks = flowOf(
                ChatCompletionChunk(
                    choices = listOf(
                        Choice(
                            index = 0,
                            delta = Delta(content = classificationJson)
                        )
                    )
                )
            )
        )
        val intelligence = LlmMemoryIntelligence(
            settingRepository = FakeSettingRepository(
                listOf(platform(compatibleType = ClientType.OPENROUTER, model = "openai/gpt-4o"))
            ),
            openAIAPI = openAIAPI,
            anthropicAPI = RecordingAnthropicAPI(),
            googleAPI = RecordingGoogleAPI()
        )

        val result = intelligence.classifyConversation(classificationRequest(), preferredPlatform = null)

        assertEquals(true, result?.shouldLearnMemories)
        assertEquals(0, openAIAPI.streamResponsesCalls)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertEquals(0f, openAIAPI.lastChatRequest?.temperature)
        assertEquals(1f, openAIAPI.lastChatRequest?.topP)
        assertEquals(512, openAIAPI.lastChatRequest?.maxTokens)
        assertEquals(60, openAIAPI.lastChatTimeoutSeconds)
    }

    @Test
    fun `preferred openai compatible platform overrides fallback platform`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatChunks = flowOf(
                ChatCompletionChunk(
                    choices = listOf(
                        Choice(
                            index = 0,
                            delta = Delta(content = classificationJson)
                        )
                    )
                )
            )
        )
        val intelligence = LlmMemoryIntelligence(
            settingRepository = FakeSettingRepository(
                listOf(platform(compatibleType = ClientType.OPENROUTER, model = "fallback-model"))
            ),
            openAIAPI = openAIAPI,
            anthropicAPI = RecordingAnthropicAPI(),
            googleAPI = RecordingGoogleAPI()
        )

        intelligence.classifyConversation(
            classificationRequest(),
            preferredPlatform = platform(compatibleType = ClientType.CUSTOM, model = "current-chat-model")
        )

        assertEquals("current-chat-model", openAIAPI.lastChatRequest?.model)
    }

    @Test
    fun `preferred anthropic platform uses anthropic api`() = runBlocking {
        val anthropicAPI = RecordingAnthropicAPI(
            chunks = flowOf(
                ContentDeltaResponseChunk(
                    index = 0,
                    delta = ContentBlock(type = ContentBlockType.DELTA, text = classificationJson)
                )
            )
        )
        val openAIAPI = RecordingOpenAIAPI()
        val intelligence = LlmMemoryIntelligence(
            settingRepository = FakeSettingRepository(
                listOf(platform(compatibleType = ClientType.OPENROUTER, model = "fallback-model"))
            ),
            openAIAPI = openAIAPI,
            anthropicAPI = anthropicAPI,
            googleAPI = RecordingGoogleAPI()
        )

        val result = intelligence.classifyConversation(
            classificationRequest(),
            preferredPlatform = platform(compatibleType = ClientType.ANTHROPIC, model = "claude-current")
        )

        assertEquals(true, result?.shouldLearnMemories)
        assertEquals(1, anthropicAPI.streamCalls)
        assertEquals("claude-current", anthropicAPI.lastRequest?.model)
        assertEquals(512, anthropicAPI.lastRequest?.maxTokens)
        assertNull(anthropicAPI.lastRequest?.thinking)
        assertEquals(60, anthropicAPI.lastTimeoutSeconds)
        assertEquals(0, openAIAPI.streamChatCompletionCalls)
    }

    @Test
    fun `preferred google platform uses google api`() = runBlocking {
        val googleAPI = RecordingGoogleAPI(
            responses = flowOf(
                GenerateContentResponse(
                    candidates = listOf(
                        Candidate(
                            content = Content(parts = listOf(Part.text(classificationJson)))
                        )
                    )
                )
            )
        )
        val openAIAPI = RecordingOpenAIAPI()
        val intelligence = LlmMemoryIntelligence(
            settingRepository = FakeSettingRepository(
                listOf(platform(compatibleType = ClientType.OPENROUTER, model = "fallback-model"))
            ),
            openAIAPI = openAIAPI,
            anthropicAPI = RecordingAnthropicAPI(),
            googleAPI = googleAPI
        )

        val result = intelligence.classifyConversation(
            classificationRequest(),
            preferredPlatform = platform(compatibleType = ClientType.GOOGLE, model = "gemini-current")
        )

        assertEquals(true, result?.shouldLearnMemories)
        assertEquals(1, googleAPI.streamCalls)
        assertEquals("gemini-current", googleAPI.lastModel)
        assertEquals(512, googleAPI.lastRequest?.generationConfig?.maxOutputTokens)
        assertNull(googleAPI.lastRequest?.generationConfig?.thinkingConfig)
        assertEquals(60, googleAPI.lastTimeoutSeconds)
        assertEquals(0, openAIAPI.streamChatCompletionCalls)
    }

    @Test
    fun `memory extraction uses longer timeout floor`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatChunks = flowOf(
                ChatCompletionChunk(
                    choices = listOf(
                        Choice(
                            index = 0,
                            delta = Delta(content = extractionJson)
                        )
                    )
                )
            )
        )
        val intelligence = LlmMemoryIntelligence(
            settingRepository = FakeSettingRepository(
                listOf(
                    platform(
                        compatibleType = ClientType.OPENROUTER,
                        model = "openai/gpt-4o",
                        timeout = 30
                    )
                )
            ),
            openAIAPI = openAIAPI,
            anthropicAPI = RecordingAnthropicAPI(),
            googleAPI = RecordingGoogleAPI()
        )

        val result = intelligence.extractMemoryCandidates(
            MemoryExtractionRequest(
                chatTitle = "Chat",
                recentMessages = classificationRequest().recentMessages
            ),
            preferredPlatform = null
        )

        assertEquals(1, result.size)
        assertEquals(120, openAIAPI.lastChatTimeoutSeconds)
        assertEquals(1200, openAIAPI.lastChatRequest?.maxTokens)
    }

    @Test
    fun `memory timeout preserves disabled timeout setting`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatChunks = flowOf(
                ChatCompletionChunk(
                    choices = listOf(
                        Choice(
                            index = 0,
                            delta = Delta(content = classificationJson)
                        )
                    )
                )
            )
        )
        val intelligence = LlmMemoryIntelligence(
            settingRepository = FakeSettingRepository(
                listOf(
                    platform(
                        compatibleType = ClientType.OPENROUTER,
                        model = "openai/gpt-4o",
                        timeout = 0
                    )
                )
            ),
            openAIAPI = openAIAPI,
            anthropicAPI = RecordingAnthropicAPI(),
            googleAPI = RecordingGoogleAPI()
        )

        intelligence.classifyConversation(classificationRequest(), preferredPlatform = null)

        assertEquals(0, openAIAPI.lastChatTimeoutSeconds)
    }

    @Test
    fun `memory timeout preserves larger user timeout`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            responseEvents = flowOf(
                OutputTextDeltaEvent(
                    itemId = "item",
                    outputIndex = 0,
                    contentIndex = 0,
                    delta = classificationJson
                )
            )
        )
        val intelligence = LlmMemoryIntelligence(
            settingRepository = FakeSettingRepository(
                listOf(
                    platform(
                        compatibleType = ClientType.OPENAI,
                        model = "gpt-5",
                        timeout = 180
                    )
                )
            ),
            openAIAPI = openAIAPI,
            anthropicAPI = RecordingAnthropicAPI(),
            googleAPI = RecordingGoogleAPI()
        )

        intelligence.classifyConversation(classificationRequest(), preferredPlatform = null)

        assertEquals(180, openAIAPI.lastResponsesTimeoutSeconds)
    }

    private fun classificationRequest() = ConversationClassificationRequest(
        chatTitle = "Chat",
        recentMessages = listOf(
            MemoryConversationMessage(
                role = "user",
                content = "Remember that I prefer concise answers."
            )
        )
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
        private const val classificationJson =
            """{"mode":"personal_update","intent":"sharing","shouldUseMemories":true,"shouldLearnMemories":true,"sensitivity":"normal","confidence":0.9}"""
        private const val extractionJson =
            """{"candidates":[{"summary":"The user prefers concise answers.","recallText":"The user prefers concise answers.","type":"communication_style","scope":"personal","importance":0.8,"confidence":0.9,"source":"user_confirmed","sensitivity":"normal","suggestedStatus":"active","requiresConfirmation":false,"reason":"The user explicitly asked for this preference to be remembered."}]}"""
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
    override suspend fun fetchToolCallingMode(): ToolCallingMode = ToolCallingMode.Off
    override suspend fun fetchWebSearchMode(): WebSearchMode = WebSearchMode.Off
    override suspend fun fetchWebSearchSearxngBaseUrl(): String = ""
    override suspend fun migrateToPlatformV2() = Unit
    override suspend fun updatePlatforms(platforms: List<Platform>) = Unit
    override suspend fun updateThemes(themeSetting: ThemeSetting) = Unit
    override suspend fun updateLastSelectedModel(platformUid: String, model: String, reasoningMode: ReasoningMode) = Unit
    override suspend fun updateMemoryEnabled(enabled: Boolean) = Unit
    override suspend fun updateToolCallingMode(mode: ToolCallingMode) = Unit
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

    override fun streamChatCompletion(
        request: ChatCompletionRequest,
        timeoutSeconds: Int
    ): Flow<ChatCompletionChunk> {
        streamChatCompletionCalls += 1
        lastChatRequest = request
        lastChatTimeoutSeconds = timeoutSeconds
        return chatChunks
    }

    override fun streamResponses(
        request: ResponsesRequest,
        timeoutSeconds: Int
    ): Flow<ResponsesStreamEvent> {
        streamResponsesCalls += 1
        lastResponsesRequest = request
        lastResponsesTimeoutSeconds = timeoutSeconds
        return responseEvents
    }

    override suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String
    ): UploadedProviderFile = error("Not used")

    override suspend fun isFileAvailable(fileId: String): Boolean = error("Not used")
}

private class RecordingAnthropicAPI(
    private val chunks: Flow<dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponseChunk> = emptyFlow()
) : AnthropicAPI {
    var streamCalls = 0
    var lastRequest: dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest? = null
    var lastTimeoutSeconds: Int? = null

    override fun setToken(token: String?) = Unit

    override fun setAPIUrl(url: String) = Unit

    override fun streamChatMessage(
        messageRequest: dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest,
        timeoutSeconds: Int
    ): Flow<dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponseChunk> {
        streamCalls += 1
        lastRequest = messageRequest
        lastTimeoutSeconds = timeoutSeconds
        return chunks
    }

    override suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String
    ): UploadedProviderFile = error("Not used")

    override suspend fun isFileAvailable(fileId: String): Boolean = error("Not used")
}

private class RecordingGoogleAPI(
    private val responses: Flow<GenerateContentResponse> = emptyFlow()
) : GoogleAPI {
    var streamCalls = 0
    var lastRequest: dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest? = null
    var lastModel: String? = null
    var lastTimeoutSeconds: Int? = null

    override fun setToken(token: String?) = Unit

    override fun setAPIUrl(url: String) = Unit

    override fun streamGenerateContent(
        request: dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest,
        model: String,
        timeoutSeconds: Int
    ): Flow<GenerateContentResponse> {
        streamCalls += 1
        lastRequest = request
        lastModel = model
        lastTimeoutSeconds = timeoutSeconds
        return responses
    }

    override suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String
    ): UploadedProviderFile = error("Not used")

    override suspend fun isFileAvailable(fileName: String): Boolean = error("Not used")
}
