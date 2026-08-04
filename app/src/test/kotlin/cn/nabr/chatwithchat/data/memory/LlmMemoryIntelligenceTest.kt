package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.ProviderUsage
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentBlock
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentBlockType
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentDeltaResponseChunk
import cn.nabr.chatwithchat.data.dto.google.common.Content
import cn.nabr.chatwithchat.data.dto.google.common.Part
import cn.nabr.chatwithchat.data.dto.google.response.Candidate
import cn.nabr.chatwithchat.data.dto.google.response.GenerateContentResponse
import cn.nabr.chatwithchat.data.dto.openai.common.TextContent as OpenAiTextContent
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.dto.openai.response.Choice
import cn.nabr.chatwithchat.data.dto.openai.response.Delta
import cn.nabr.chatwithchat.data.dto.openai.response.ErrorDetail
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
    fun `memory generation prompts enforce conservative quality gates and retirement contract`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_PROPOSAL_JSON))
        val intelligence = intelligence(openAIAPI = openAIAPI)
        val platform = platform(ClientType.OPENROUTER, "model")

        intelligence.consolidateMemoryBatch(batchRequest(), platform)
        val batchPrompt = checkNotNull(openAIAPI.lastSystemPrompt)
        assertTrue(batchPrompt.contains("Default to ignore"))
        assertTrue(batchPrompt.contains("future utility"))
        assertTrue(batchPrompt.contains("one isolated assistant inference", ignoreCase = true))
        assertTrue(batchPrompt.contains("current task progress"))
        assertTrue(batchPrompt.contains("application tool-calling policy"))

        intelligence.distillDailyMemory(dailyRequest(), platform)
        val dailyPrompt = checkNotNull(openAIAPI.lastSystemPrompt)
        assertTrue(dailyPrompt.contains("Default to ignore"))
        assertTrue(dailyPrompt.contains("repeated independent evidence"))
        assertTrue(dailyPrompt.contains("current debugging"))
        assertTrue(dailyPrompt.contains("historical context stays daily", ignoreCase = true))

        intelligence.consolidateLongTermMemory(longTermRequest(), platform)
        val longTermPrompt = checkNotNull(openAIAPI.lastSystemPrompt)
        assertTrue(longTermPrompt.contains("canonicalize|retire|ignore"))
        assertTrue(longTermPrompt.contains("recoverable corpus-quality action"))
        assertTrue(longTermPrompt.contains("singleton decision"))
        assertTrue(longTermPrompt.contains("do not invent a supersession target"))
    }

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
    fun `custom compatible platform does not receive openai reasoning-only fields`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_PROPOSAL_JSON))
        val intelligence = intelligence(openAIAPI = openAIAPI)

        intelligence.consolidateMemoryBatch(
            batchRequest(),
            resolvedPlatform = platform(ClientType.CUSTOM, "compatible-model", reasoning = true)
        )

        assertEquals(0f, openAIAPI.lastChatRequest?.temperature)
        assertEquals(1f, openAIAPI.lastChatRequest?.topP)
        assertEquals(1200, openAIAPI.lastChatRequest?.maxTokens)
        assertNull(openAIAPI.lastChatRequest?.maxCompletionTokens)
        assertNull(openAIAPI.lastChatRequest?.reasoningEffort)
    }

    @Test
    fun `official deepseek memory request disables thinking`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(chatChunks = chatChunks(EMPTY_PROPOSAL_JSON))
        val intelligence = intelligence(openAIAPI = openAIAPI)

        intelligence.consolidateMemoryBatch(
            batchRequest(),
            resolvedPlatform = platform(ClientType.CUSTOM, "deepseek-v4-flash").copy(
                apiUrl = "https://api.deepseek.com"
            )
        )

        assertEquals("disabled", openAIAPI.lastChatRequest?.thinking?.type)
        assertNull(openAIAPI.lastChatRequest?.temperature)
        assertNull(openAIAPI.lastChatRequest?.topP)
        assertEquals(1200, openAIAPI.lastChatRequest?.maxTokens)
    }

    @Test
    fun `reasoning-only blank response records bounded generation diagnostics`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatChunks = flowOf(
                ChatCompletionChunk(
                    choices = listOf(
                        Choice(
                            index = 0,
                            delta = Delta(reasoningContent = "plan"),
                            finishReason = "length"
                        )
                    ),
                    usage = ProviderUsage(completionTokens = 1200)
                )
            )
        )
        val activityLogger = RecordingMemoryActivityLogger()
        val intelligence = intelligence(openAIAPI = openAIAPI, activityLogger = activityLogger)
        val activityRunId = activityLogger.startSemanticAttempt()

        assertNull(intelligence.consolidateMemoryBatch(batchRequest(), platform(ClientType.OPENROUTER, "model"), activityRunId))

        val detail = activityLogger.run(activityRunId).data.errorDetail
        assertTrue(detail?.contains("finish_reason=length") == true)
        assertTrue(detail?.contains("reasoning_chars=4") == true)
        assertTrue(detail?.contains("completion_tokens=1200") == true)
    }

    @Test
    fun `network error retries once before returning a model failure`() = runBlocking {
        var attempt = 0
        val openAIAPI = RecordingOpenAIAPI(
            chatChunkProvider = {
                if (attempt++ == 0) {
                    flowOf(ChatCompletionChunk(error = ErrorDetail(type = "network_error", message = "temporary")))
                } else {
                    chatChunks(EMPTY_PROPOSAL_JSON)
                }
            }
        )
        val intelligence = intelligence(openAIAPI = openAIAPI)

        val result = intelligence.consolidateMemoryBatch(batchRequest(), platform(ClientType.OPENROUTER, "model"))

        assertEquals(0, result?.operations?.size)
        assertEquals(2, openAIAPI.streamChatCompletionCalls)
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
    fun `batch invalid json finishes the same activity run at generation`() = runBlocking {
        val resolvedPlatform = platform(ClientType.OPENROUTER, "model")
        val openAIAPI = RecordingOpenAIAPI(
            chatChunks = chatChunks("""{"operations":[],"unexpected":true}""")
        )
        val activityLogger = RecordingMemoryActivityLogger()
        val intelligence = intelligence(
            openAIAPI = openAIAPI,
            activityLogger = activityLogger
        )

        val activityRunId = activityLogger.startSemanticAttempt()
        val result = intelligence.consolidateMemoryBatch(
            batchRequest(),
            resolvedPlatform,
            activityRunId
        )

        assertNull(result)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertEquals(1, activityLogger.runs.size)
        val activityRun = activityLogger.run(activityRunId)
        assertEquals(
            listOf(
                MemoryActivityPhase.MODEL_RESOLUTION,
                MemoryActivityPhase.MODEL_CALL,
                MemoryActivityPhase.GENERATION
            ),
            activityRun.phases
        )
        assertEquals(MemoryActivityPhase.GENERATION, activityRun.phase)
        assertEquals(MemoryActivityStatus.FAILED, activityRun.status)
        assertEquals("invalid_model_json", activityRun.data.errorCode)
        assertEquals(MemoryActivityCategory.TURN_BATCH_CONSOLIDATION, activityRun.category)
        assertEquals(resolvedPlatform.uid, activityRun.data.platformUid)
        assertEquals(resolvedPlatform.model, activityRun.data.modelId)
    }

    @Test
    fun `unavailable resolved platform finishes the same activity run at model call`() = runBlocking {
        val activityLogger = RecordingMemoryActivityLogger()
        val intelligence = intelligence(activityLogger = activityLogger)

        val activityRunId = activityLogger.startSemanticAttempt()
        val result = intelligence.consolidateMemoryBatch(
            batchRequest(),
            platform(ClientType.OPENROUTER, "model").copy(enabled = false),
            activityRunId
        )

        assertNull(result)
        assertEquals(1, activityLogger.runs.size)
        val activityRun = activityLogger.run(activityRunId)
        assertEquals(
            listOf(MemoryActivityPhase.MODEL_RESOLUTION, MemoryActivityPhase.MODEL_CALL),
            activityRun.phases
        )
        assertEquals(MemoryActivityPhase.MODEL_CALL, activityRun.phase)
        assertEquals(MemoryActivityStatus.FAILED, activityRun.status)
        assertEquals("model_call_failed", activityRun.data.errorCode)
        assertEquals(MemoryActivityCategory.TURN_BATCH_CONSOLIDATION, activityRun.category)
    }

    @Test
    fun `provider error keeps a bounded diagnostic detail in the activity run`() = runBlocking {
        val activityLogger = RecordingMemoryActivityLogger()
        val intelligence = intelligence(
            openAIAPI = RecordingOpenAIAPI(
                chatChunks = flowOf(
                    ChatCompletionChunk(
                        error = ErrorDetail(
                            type = "http_error",
                            code = "400",
                            message = "Unsupported parameter: reasoning_effort"
                        )
                    )
                )
            ),
            activityLogger = activityLogger
        )
        val activityRunId = activityLogger.startSemanticAttempt()

        assertNull(intelligence.consolidateMemoryBatch(batchRequest(), platform(ClientType.CUSTOM, "model"), activityRunId))

        val run = activityLogger.run(activityRunId)
        assertEquals("model_call_failed", run.data.errorCode)
        assertTrue(run.data.errorDetail?.contains("Unsupported parameter") == true)
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
    private val responseEvents: Flow<ResponsesStreamEvent> = emptyFlow(),
    private val chatChunkProvider: (() -> Flow<ChatCompletionChunk>)? = null
) : OpenAIAPI {
    var streamChatCompletionCalls = 0
    var streamResponsesCalls = 0
    var lastChatRequest: ChatCompletionRequest? = null
    var lastResponsesRequest: ResponsesRequest? = null
    var lastSystemPrompt: String? = null
    var lastChatTimeoutSeconds: Int? = null
    var lastResponsesTimeoutSeconds: Int? = null

    override fun setToken(token: String?) = Unit
    override fun setAPIUrl(url: String) = Unit

    override fun streamChatCompletion(request: ChatCompletionRequest, timeoutSeconds: Int): Flow<ChatCompletionChunk> {
        streamChatCompletionCalls += 1
        lastChatRequest = request
        lastSystemPrompt = request.messages.firstOrNull()?.content?.filterIsInstance<OpenAiTextContent>()
            ?.firstOrNull()
            ?.text
        lastChatTimeoutSeconds = timeoutSeconds
        return chatChunkProvider?.invoke() ?: chatChunks
    }

    override fun streamResponses(request: ResponsesRequest, timeoutSeconds: Int): Flow<ResponsesStreamEvent> {
        streamResponsesCalls += 1
        lastResponsesRequest = request
        lastSystemPrompt = request.instructions
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
    val runs = linkedMapOf<String, RecordedMemoryActivityRun>()

    suspend fun startSemanticAttempt(): String = startRun(
        MemoryActivityRunStart(
            key = MemoryActivityRunKey(
                jobId = "llm-memory-intelligence-test",
                retryCycle = 0,
                attempt = 1
            ),
            category = MemoryActivityCategory.TURN_BATCH_CONSOLIDATION,
            jobType = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
            initialPhase = MemoryActivityPhase.MODEL_RESOLUTION,
            data = MemoryActivityRunData(inputCount = 1)
        )
    )

    override suspend fun startRun(start: MemoryActivityRunStart): String {
        val activityRunId = start.key.activityRunId
        runs.putIfAbsent(
            activityRunId,
            RecordedMemoryActivityRun(
                category = start.category,
                phase = start.initialPhase,
                status = start.initialStatus,
                phases = mutableListOf(start.initialPhase),
                data = start.data
            )
        )
        return activityRunId
    }

    override suspend fun advancePhase(
        activityRunId: String,
        expectedPhase: String,
        nextPhase: String,
        data: MemoryActivityRunData
    ): Boolean {
        val run = runs[activityRunId] ?: return false
        if (run.status in MemoryActivityStatus.TERMINAL || run.phase != expectedPhase) return false
        run.phase = nextPhase
        run.status = MemoryActivityStatus.RUNNING
        run.phases += nextPhase
        run.data = run.data.merge(data)
        return true
    }

    override suspend fun finishRun(
        activityRunId: String,
        expectedPhase: String,
        status: String,
        data: MemoryActivityRunData
    ): Boolean {
        val run = runs[activityRunId] ?: return false
        if (run.status in MemoryActivityStatus.TERMINAL || run.phase != expectedPhase) return false
        run.status = status
        run.data = run.data.merge(data)
        return true
    }

    override suspend fun start(
        batchId: String,
        category: String,
        platformName: String?,
        modelName: String?,
        attempt: Int?,
        turnCount: Int?
    ): String = error("Legacy activity rows are not expected")

    override suspend fun finish(logId: String, status: String, detail: String?, operationCount: Int?) =
        error("Legacy activity rows are not expected")

    fun run(activityRunId: String): RecordedMemoryActivityRun = checkNotNull(runs[activityRunId])
}

private data class RecordedMemoryActivityRun(
    val category: String,
    var phase: String,
    var status: String,
    val phases: MutableList<String>,
    var data: MemoryActivityRunData
)

private fun MemoryActivityRunData.merge(update: MemoryActivityRunData): MemoryActivityRunData = copy(
    platformUid = update.platformUid ?: platformUid,
    modelId = update.modelId ?: modelId,
    platformName = update.platformName ?: platformName,
    modelName = update.modelName ?: modelName,
    inputCount = update.inputCount ?: inputCount,
    operationCount = update.operationCount ?: operationCount,
    cursor = update.cursor ?: cursor,
    hashPrefix = update.hashPrefix ?: hashPrefix,
    errorCode = update.errorCode ?: errorCode,
    errorDetail = update.errorDetail ?: errorDetail
)
