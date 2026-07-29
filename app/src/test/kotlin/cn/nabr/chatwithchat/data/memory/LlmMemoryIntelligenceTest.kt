package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
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
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.network.UploadedProviderFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmMemoryIntelligenceTest {

    @Test
    fun `openai memory platform uses responses api for batch consolidation`() = runBlocking {
        val resolvedPlatform = platform(ClientType.OPENAI, "gpt-5", reasoning = true)
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
            openAIAPI = openAIAPI
        )

        val result = intelligence.consolidateMemoryBatch(batchRequest(), resolvedPlatform)

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
        val resolvedPlatform = platform(ClientType.OPENROUTER, "openai/gpt-4o")
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_PROPOSAL_JSON))
        val intelligence = intelligence(
            openAIAPI = openAIAPI
        )

        intelligence.consolidateMemoryBatch(batchRequest(), resolvedPlatform)

        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertEquals(0f, openAIAPI.lastChatRequest?.temperature)
        assertEquals(1f, openAIAPI.lastChatRequest?.topP)
        assertEquals(1200, openAIAPI.lastChatRequest?.maxTokens)
        assertEquals(120, openAIAPI.lastChatTimeoutSeconds)
    }

    @Test
    fun `batch uses the exact resolved platform snapshot`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_PROPOSAL_JSON))
        val intelligence = intelligence(openAIAPI = openAIAPI)

        intelligence.consolidateMemoryBatch(
            batchRequest(),
            resolvedPlatform = platform(ClientType.CUSTOM, "resolved-model")
        )

        assertEquals("resolved-model", openAIAPI.lastChatRequest?.model)
    }

    @Test
    fun `resolved anthropic platform executes the same batch contract`() = runBlocking {
        val anthropicAPI = RecordingAnthropicAPI(
            chunks = flowOf(
                ContentDeltaResponseChunk(
                    index = 0,
                    delta = ContentBlock(type = ContentBlockType.DELTA, text = EMPTY_PROPOSAL_JSON)
                )
            )
        )
        val intelligence = intelligence(anthropicAPI = anthropicAPI)

        val result = intelligence.consolidateMemoryBatch(
            batchRequest(),
            resolvedPlatform = platform(ClientType.ANTHROPIC, "claude-current")
        )

        assertEquals(0, result?.operations?.size)
        assertEquals(1, anthropicAPI.streamCalls)
        assertEquals("claude-current", anthropicAPI.lastRequest?.model)
        assertEquals(1200, anthropicAPI.lastRequest?.maxTokens)
        assertEquals(120, anthropicAPI.lastTimeoutSeconds)
    }

    @Test
    fun `resolved google platform executes the same batch contract`() = runBlocking {
        val googleAPI = RecordingGoogleAPI(
            responses = flowOf(
                GenerateContentResponse(
                    candidates = listOf(Candidate(content = Content(parts = listOf(Part.text(EMPTY_PROPOSAL_JSON)))))
                )
            )
        )
        val intelligence = intelligence(googleAPI = googleAPI)

        val result = intelligence.consolidateMemoryBatch(
            batchRequest(),
            resolvedPlatform = platform(ClientType.GOOGLE, "gemini-current")
        )

        assertEquals(0, result?.operations?.size)
        assertEquals(1, googleAPI.streamCalls)
        assertEquals("gemini-current", googleAPI.lastModel)
        assertEquals(1200, googleAPI.lastRequest?.generationConfig?.maxOutputTokens)
        assertEquals(120, googleAPI.lastTimeoutSeconds)
    }

    @Test
    fun `batch timeout preserves disabled timeout setting`() = runBlocking {
        val resolvedPlatform = platform(ClientType.OPENROUTER, "model", timeout = 0)
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_PROPOSAL_JSON))
        val intelligence = intelligence(openAIAPI = openAIAPI)

        intelligence.consolidateMemoryBatch(batchRequest(), resolvedPlatform)

        assertEquals(0, openAIAPI.lastChatTimeoutSeconds)
    }

    @Test
    fun `batch timeout preserves larger user timeout`() = runBlocking {
        val resolvedPlatform = platform(ClientType.OPENROUTER, "model", timeout = 180)
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_PROPOSAL_JSON))
        val intelligence = intelligence(openAIAPI = openAIAPI)

        intelligence.consolidateMemoryBatch(batchRequest(), resolvedPlatform)

        assertEquals(180, openAIAPI.lastChatTimeoutSeconds)
    }

    @Test
    fun `batch consolidation rejects non strict json with one provider call`() = runBlocking {
        val resolvedPlatform = platform(ClientType.OPENROUTER, "model")
        val openAIAPI = RecordingOpenAIAPI(
            chatChunks = chatChunks("""{"operations":[],"unexpected":true}""")
        )
        val activityLogger = RecordingMemoryActivityLogger()
        val intelligence = intelligence(
            openAIAPI = openAIAPI,
            activityLogger = activityLogger
        )

        val result = intelligence.consolidateMemoryBatch(batchRequest(), resolvedPlatform)

        assertNull(result)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertEquals(MemoryActivityStatus.SUCCEEDED, activityLogger.finishedStatus(MemoryActivityCategory.MODEL_CALL))
        assertEquals(MemoryActivityStatus.FAILED, activityLogger.finishedStatus(MemoryActivityCategory.MEMORY_GENERATION))
    }

    @Test
    fun `unavailable resolved platform records model call and generation failures`() = runBlocking {
        val activityLogger = RecordingMemoryActivityLogger()
        val intelligence = intelligence(activityLogger = activityLogger)

        val result = intelligence.consolidateMemoryBatch(
            batchRequest(),
            platform(ClientType.OPENROUTER, "model").copy(enabled = false)
        )

        assertNull(result)
        assertEquals(MemoryActivityStatus.FAILED, activityLogger.finishedStatus(MemoryActivityCategory.MODEL_CALL))
        assertEquals(MemoryActivityStatus.FAILED, activityLogger.finishedStatus(MemoryActivityCategory.MEMORY_GENERATION))
    }

    @Test
    fun `daily distillation uses one strict provider request`() = runBlocking {
        val resolvedPlatform = platform(ClientType.OPENROUTER, "model")
        val response =
            """{"operations":[{"action":"create","text":"Prefers concise answers.","type":"communication_style","sensitivity":"normal","source":"explicit_user_statement","evidenceKeys":["evidence-1"],"canonicalKey":"communication.response_style","scope":"general","evidenceAt":2,"recallState":"core","reason":"stable preference"}]}"""
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(response))
        val intelligence = intelligence(openAIAPI = openAIAPI)

        val result = intelligence.distillDailyMemory(dailyRequest(), resolvedPlatform)

        assertEquals(1, result?.operations?.size)
        assertEquals(MemoryDailyDistillationAction.CREATE, result?.operations?.single()?.action)
        assertEquals(listOf("evidence-1"), result?.operations?.single()?.evidenceKeys)
        assertEquals("communication.response_style", result?.operations?.single()?.canonicalKey)
        assertEquals(2L, result?.operations?.single()?.evidenceAt)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertEquals(120, openAIAPI.lastChatTimeoutSeconds)
        assertEquals(1200, openAIAPI.lastChatRequest?.maxTokens)
    }

    @Test
    fun `daily distillation rejects non strict json after one call`() = runBlocking {
        val resolvedPlatform = platform(ClientType.OPENROUTER, "model")
        val openAIAPI = RecordingOpenAIAPI(
            chatChunks = chatChunks("""{"operations":[],"unexpected":true}""")
        )
        val intelligence = intelligence(openAIAPI = openAIAPI)

        val result = intelligence.distillDailyMemory(dailyRequest(), resolvedPlatform)

        assertNull(result)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
    }

    @Test
    fun `long term consolidation uses the exact frozen platform`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_LONG_TERM_PROPOSAL_JSON))
        val intelligence = intelligence(openAIAPI = openAIAPI)

        val result = intelligence.consolidateLongTermMemory(
            request = longTermRequest(),
            resolvedPlatform = platform(ClientType.CUSTOM, "frozen-model")
        )

        assertEquals(0, result?.decisions?.size)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertEquals("frozen-model", openAIAPI.lastChatRequest?.model)
    }

    @Test
    fun `long term consolidation rejects an oversized serialized request before provider call`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_LONG_TERM_PROPOSAL_JSON))
        val intelligence = intelligence(openAIAPI = openAIAPI)
        val request = longTermRequest().copy(
            candidateGroups = longTermRequest().candidateGroups.map { group ->
                group.copy(
                    entries = group.entries.map { entry ->
                        entry.copy(text = "\"".repeat(MemoryLongTermConsolidationPolicy.MAX_SERIALIZED_REQUEST_CHARS))
                    }
                )
            }
        )

        val failure = runCatching {
            intelligence.consolidateLongTermMemory(
                request = request,
                resolvedPlatform = platform(ClientType.OPENROUTER, "frozen-model")
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, openAIAPI.streamChatCompletionCalls)
    }

    @Test
    fun `unavailable frozen long term platform never falls back`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_LONG_TERM_PROPOSAL_JSON))
        val intelligence = intelligence(openAIAPI = openAIAPI)

        val result = intelligence.consolidateLongTermMemory(
            request = longTermRequest(),
            resolvedPlatform = platform(ClientType.CUSTOM, "frozen-model").copy(token = null)
        )

        assertNull(result)
        assertEquals(0, openAIAPI.streamChatCompletionCalls)
    }

    @Test
    fun `long term consolidation rejects unknown response fields after one call`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatChunks = chatChunks("""{"decisions":[],"unexpected":true}""")
        )
        val intelligence = intelligence(openAIAPI = openAIAPI)

        val result = intelligence.consolidateLongTermMemory(
            request = longTermRequest(),
            resolvedPlatform = platform(ClientType.OPENROUTER, "frozen-model")
        )

        assertNull(result)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
    }

    private fun intelligence(
        openAIAPI: RecordingOpenAIAPI = RecordingOpenAIAPI(),
        anthropicAPI: RecordingAnthropicAPI = RecordingAnthropicAPI(),
        googleAPI: RecordingGoogleAPI = RecordingGoogleAPI(),
        activityLogger: MemoryActivityLogger = MemoryActivityLogger.None
    ) = LlmMemoryIntelligence(
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

    private fun longTermRequest() = MemoryLongTermConsolidationPartitionRequest(
        checkpointId = "checkpoint-1",
        partitionStart = 0,
        partitionEndExclusive = 1,
        candidateGroups = listOf(
            MemoryLongTermCandidateGroup(
                groupId = "group-1",
                anchorMemoryIds = listOf("memory-1"),
                entries = listOf(
                    MemoryLongTermCandidateEntry(
                        memoryId = "memory-1",
                        text = "Prefers concise answers.",
                        type = "communication_style",
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        scope = MemoryScope.GENERAL,
                        lastObservedAt = 2,
                        recallState = MemoryRecallState.QUERY
                    )
                )
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
        const val EMPTY_PROPOSAL_JSON = """{"operations":[]}"""
        const val EMPTY_LONG_TERM_PROPOSAL_JSON = """{"decisions":[]}"""
    }
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
