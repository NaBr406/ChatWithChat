package cn.nabr.chatwithchat.data.repository

import android.content.ContextWrapper
import cn.nabr.chatwithchat.data.context.ContextBuilder
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.dto.ProviderUsage
import cn.nabr.chatwithchat.data.dto.anthropic.common.ToolResultContent
import cn.nabr.chatwithchat.data.dto.anthropic.common.ToolUseContent
import cn.nabr.chatwithchat.data.dto.anthropic.request.AnthropicToolChoice
import cn.nabr.chatwithchat.data.dto.anthropic.request.MessageRequest
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentBlock
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentBlockType
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentDeltaResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentStartResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageDeltaResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageResponse
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageStartResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.StopReason
import cn.nabr.chatwithchat.data.dto.anthropic.response.StopReasonDelta
import cn.nabr.chatwithchat.data.dto.anthropic.response.Usage
import cn.nabr.chatwithchat.data.dto.anthropic.response.UsageDelta
import cn.nabr.chatwithchat.data.dto.google.common.Content
import cn.nabr.chatwithchat.data.dto.google.common.Part
import cn.nabr.chatwithchat.data.dto.google.common.Role as GoogleRole
import cn.nabr.chatwithchat.data.dto.google.request.GenerateContentRequest
import cn.nabr.chatwithchat.data.dto.google.request.GoogleToolConfig
import cn.nabr.chatwithchat.data.dto.google.response.Candidate
import cn.nabr.chatwithchat.data.dto.google.response.GenerateContentResponse
import cn.nabr.chatwithchat.data.dto.google.response.UsageMetadata
import cn.nabr.chatwithchat.data.dto.groq.request.GroqChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.groq.response.GroqChatCompletionChunk
import cn.nabr.chatwithchat.data.dto.groq.response.GroqChoice
import cn.nabr.chatwithchat.data.dto.groq.response.GroqDelta
import cn.nabr.chatwithchat.data.dto.openai.common.Role as OpenAIRole
import cn.nabr.chatwithchat.data.dto.openai.common.TextContent as OpenAITextContent
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionToolChoice
import cn.nabr.chatwithchat.data.dto.openai.request.ChatMessage
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseFunctionCallInputItem
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseFunctionCallOutputItem
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseToolChoice
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionFunctionCallDelta
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionToolCallDelta
import cn.nabr.chatwithchat.data.dto.openai.response.Choice
import cn.nabr.chatwithchat.data.dto.openai.response.Delta
import cn.nabr.chatwithchat.data.dto.openai.response.ErrorDetail
import cn.nabr.chatwithchat.data.dto.openai.response.FunctionCallArgumentsDoneEvent
import cn.nabr.chatwithchat.data.dto.openai.response.OutputTextDeltaEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseCompletedEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseError
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseFailedEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseObject
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesStreamEvent
import cn.nabr.chatwithchat.data.model.ChatAttachment
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.GroqAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.network.UploadedProviderFile
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import cn.nabr.chatwithchat.data.tool.BuiltInTools
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolCallingMode
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolEnablementOverrides
import cn.nabr.chatwithchat.data.tool.ToolExecutor
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolLoopOrchestrator
import cn.nabr.chatwithchat.data.tool.ToolProvider
import cn.nabr.chatwithchat.data.tool.ToolRegistry
import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.ToolSecurityPolicy
import cn.nabr.chatwithchat.data.tool.ToolSource
import cn.nabr.chatwithchat.data.websearch.SearchDecisionModelClient
import cn.nabr.chatwithchat.data.websearch.SearchDecisionModelResponse
import cn.nabr.chatwithchat.data.websearch.SearchDecisionService
import cn.nabr.chatwithchat.data.websearch.WebPageExtractor
import cn.nabr.chatwithchat.data.websearch.WebSearchMode
import cn.nabr.chatwithchat.data.websearch.WebSearchRepository
import cn.nabr.chatwithchat.data.websearch.WebSearchResult
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryImplTest {

    @Test(expected = IllegalStateException::class)
    fun `blank response input without encodable parts throws`() {
        validateResponseInputPartsOrThrow("", 0, 42)
    }

    @Test
    fun `response input with text does not throw when image encoding fails`() {
        validateResponseInputPartsOrThrow("hello", 0, 42)
    }

    @Test
    fun `response input with encoded image parts does not throw when text is blank`() {
        validateResponseInputPartsOrThrow("", 1, 42)
    }

    @Test
    fun `loading is emitted before expensive request preparation finishes`() = runBlocking {
        val firstState = withTimeout(100) {
            streamPreparedApiState(
                prepare = {
                    Thread.sleep(200)
                },
                stream = {
                    flowOf(ApiState.Success("done"))
                }
            ).first()
        }

        assertEquals(ApiState.Loading, firstState)
    }

    @Test
    fun `groq path uses groq api and emits parsed reasoning`() = runBlocking {
        val groqAPI = FakeGroqAPI(
            flowOf(
                GroqChatCompletionChunk(
                    choices = listOf(
                        GroqChoice(
                            index = 0,
                            delta = GroqDelta(
                                reasoning = "Plan",
                                content = "Answer"
                            )
                        )
                    )
                )
            )
        )
        val openAIAPI = RecordingOpenAIAPI()
        val repository = createRepository(
            groqAPI = groqAPI,
            openAIAPI = openAIAPI
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = groqPlatform(reasoning = true, model = "qwen/qwen3-32b")
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Thinking("Plan"),
                ApiState.Success("Answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertTrue(states.any { it is ApiState.UsageUpdated })
        assertEquals(1, groqAPI.streamCalls)
        assertEquals(0, openAIAPI.streamChatCompletionCalls)
    }

    @Test
    fun `responses failure retains exact provider usage`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(
                flowOf(
                    ResponseFailedEvent(
                        response = ResponseObject(
                            id = "resp_failed",
                            status = "failed",
                            error = ResponseError(message = "provider failure"),
                            usage = ProviderUsage(inputTokens = 13, outputTokens = 2, totalTokens = 15)
                        )
                    )
                )
            )
        )
        val repository = createRepository(openAIAPI = openAIAPI)

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = openAIPlatform()
        ).toList()
        val usage = states.filterIsInstance<ApiState.UsageUpdated>().single().usage

        assertTrue(states.any { it == ApiState.Error("provider failure") })
        assertEquals(13, usage.inputTokens)
        assertEquals(2, usage.outputTokens)
        assertEquals(15, usage.totalTokens)
        assertFalse(usage.isEstimated)
    }

    @Test
    fun `groq raw think fallback populates thinking state`() = runBlocking {
        val groqAPI = FakeGroqAPI(
            flowOf(
                GroqChatCompletionChunk(
                    choices = listOf(
                        GroqChoice(
                            index = 0,
                            delta = GroqDelta(content = "<think>Secret</think>\nVisible")
                        )
                    )
                )
            )
        )
        val repository = createRepository(groqAPI = groqAPI)

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = groqPlatform(reasoning = true, model = "qwen/qwen3-32b")
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Thinking("Secret"),
                ApiState.Success("Visible"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
    }

    @Test
    fun `groq reasoning disabled hides qwen reasoning`() = runBlocking {
        val groqAPI = FakeGroqAPI(emptyFlow())
        val repository = createRepository(groqAPI = groqAPI)

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = groqPlatform(reasoning = false, model = "qwen/qwen3-32b"),
            reasoningMode = ReasoningMode.OFF
        ).toList()

        val request = groqAPI.lastRequest
        assertEquals("hidden", request?.reasoningFormat)
        assertNull(request?.includeReasoning)
        assertNull(request?.reasoningEffort)
    }

    @Test
    fun `groq reasoning disabled turns off gpt oss reasoning`() = runBlocking {
        val groqAPI = FakeGroqAPI(emptyFlow())
        val repository = createRepository(groqAPI = groqAPI)

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = groqPlatform(reasoning = false, model = "openai/gpt-oss-20b"),
            reasoningMode = ReasoningMode.OFF
        ).toList()

        val request = groqAPI.lastRequest
        assertNull(request?.reasoningFormat)
        assertEquals(false, request?.includeReasoning)
        assertNull(request?.reasoningEffort)
    }

    @Test
    fun `failed historical turn is excluded from subsequent inline budget checks`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI()
        val repository = createRepository(openAIAPI = openAIAPI)
        val tempDir = kotlin.io.path.createTempDirectory("context-inline-budget").toFile().apply {
            deleteOnExit()
        }
        val missingAttachmentFile = File(tempDir, "oversized-${UUID.randomUUID()}.png")
        if (missingAttachmentFile.exists()) {
            missingAttachmentFile.delete()
        }
        assertFalse(missingAttachmentFile.exists())
        val failedTurnAttachment = ChatAttachment(
            localFilePath = missingAttachmentFile.absolutePath,
            preparedFilePath = missingAttachmentFile.absolutePath,
            displayName = "oversized.png",
            mimeType = "image/png",
            sizeBytes = 13L * 1024 * 1024
        )
        val customPlatform = customPlatform()

        val states = repository.completeChat(
            userMessages = listOf(
                MessageV2(
                    id = 1,
                    content = "",
                    platformType = null,
                    attachments = listOf(failedTurnAttachment)
                ),
                MessageV2(
                    id = 2,
                    content = "Try again with text only",
                    platformType = null
                )
            ),
            assistantMessages = listOf(
                listOf(
                    MessageV2(
                        id = 11,
                        content = "Error: These images are too large to upload safely on this provider.",
                        platformType = customPlatform.uid
                    )
                ),
                listOf(
                    MessageV2(
                        id = 12,
                        content = "",
                        platformType = customPlatform.uid
                    )
                )
            ),
            platform = customPlatform
        ).toList()

        assertEquals(listOf(ApiState.Loading, ApiState.Done), states)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
    }

    @Test
    fun `old conversation turns are summarized into system prompt`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI()
        val repository = createRepository(openAIAPI = openAIAPI)
        val customPlatform = customPlatform()

        repository.completeChat(
            userMessages = (1..8).map { index ->
                MessageV2(
                    id = index,
                    content = "topic-$index user detail",
                    platformType = null
                )
            },
            assistantMessages = (1..8).map { index ->
                listOf(
                    MessageV2(
                        id = 100 + index,
                        content = "topic-$index assistant detail",
                        platformType = customPlatform.uid
                    )
                )
            },
            platform = customPlatform.copy(systemPrompt = "Base system prompt")
        ).toList()

        val request = openAIAPI.lastChatCompletionRequest
        val systemText = request
            ?.messages
            ?.firstOrNull()
            ?.content
            ?.filterIsInstance<OpenAITextContent>()
            ?.firstOrNull()
            ?.text
            .orEmpty()

        assertTrue(systemText.contains("Base system prompt"))
        assertTrue(systemText.contains("Earlier conversation summary"))
        assertTrue(systemText.contains("topic-1"))
    }

    @Test
    fun `web search off does not call web search repository`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI()
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(WebSearchMode.Off),
            webSearchRepository = webSearchRepository
        )

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "latest question", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform(systemPrompt = "Base system prompt")
        ).toList()

        assertTrue(webSearchRepository.queries.isEmpty())
        assertFalse(systemText(openAIAPI).contains("Web search results"))
    }

    @Test
    fun `auto web search uses generic tool loop and injects tool results into final request`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow(
                    """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"current Android target SDK"}}]}"""
                ),
                chatCompletionFlow("""{"type":"final_answer","content":"Draft searched answer"}"""),
                chatCompletionFlow("Final searched answer")
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What is the current Android target SDK?", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.ToolStarted("web_search", "current Android target SDK"),
                ApiState.ToolFinished("web_search", "current Android target SDK"),
                ApiState.SourcesUpdated(
                    listOf(
                        MessageSourceMetadata(
                            title = "Example Source",
                            url = "https://example.com/source",
                            snippet = "Example search snippet",
                            sourceToolName = "web_search"
                        )
                    )
                ),
                ApiState.Success("Final searched answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(listOf("current Android target SDK"), webSearchRepository.queries)
        assertEquals(3, openAIAPI.streamChatCompletionCalls)
        assertTrue(openAIAPI.chatCompletionRequests[0].systemText().contains("Available tools:"))
        assertTrue(openAIAPI.chatCompletionRequests[1].systemText().contains("Tool scratchpad:"))
        assertTrue(openAIAPI.chatCompletionRequests[1].systemText().contains("Example Source"))
        assertTrue(openAIAPI.chatCompletionRequests[1].systemText().contains("https://example.com/source"))
        assertTrue(openAIAPI.chatCompletionRequests[2].systemText().contains("Tool results are available"))
        assertTrue(openAIAPI.chatCompletionRequests[2].systemText().contains("Draft searched answer"))
    }

    @Test
    fun `tool calling off skips tool loop even when web search mode is auto`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow("Normal answer")
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Off
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What is the current Android target SDK?", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Success("Normal answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertTrue(webSearchRepository.queries.isEmpty())
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertFalse(openAIAPI.chatCompletionRequests.single().systemText().contains("Available tools:"))
    }

    @Test
    fun `tool calling auto keeps only non search tools when web search mode is off`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow("""{"type":"final_answer","content":"Normal answer"}""")
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What is the current Android target SDK?", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Success("Normal answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertTrue(webSearchRepository.queries.isEmpty())
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        val toolPrompt = openAIAPI.chatCompletionRequests.single().systemText()
        assertTrue(toolPrompt.contains("Available tools:"))
        assertTrue(toolPrompt.contains("current_datetime"))
        assertTrue(toolPrompt.contains("device_location"))
        assertFalse(toolPrompt.contains("web_search"))
        assertFalse(toolPrompt.contains("fetch_url"))
    }

    @Test
    fun `tool calling auto hides individually disabled tools`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow("""{"type":"final_answer","content":"Normal answer"}""")
            )
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto,
                disabledToolNames = setOf(ToolDefinition.CurrentDateTime.name)
            )
        )

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "What time is it?", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        val toolPrompt = openAIAPI.chatCompletionRequests.single().systemText()
        assertTrue(toolPrompt.contains("Available tools:"))
        assertFalse(toolPrompt.contains(ToolDefinition.CurrentDateTime.name))
        assertTrue(toolPrompt.contains(ToolDefinition.DeviceLocation.name))
    }

    @Test
    fun `all individually disabled tools bypass tool loop`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(chatCompletionFlow("Normal answer"))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto,
                disabledToolNames = ToolDefinition.BuiltIns.map { definition -> definition.name }.toSet()
            )
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hello", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(
            listOf(ApiState.Loading, ApiState.Success("Normal answer"), ApiState.Done),
            states.withoutUsageUpdates()
        )
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertFalse(openAIAPI.chatCompletionRequests.single().systemText().contains("Available tools:"))
    }

    @Test
    fun `tool preference read failure disables tools`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(chatCompletionFlow("Normal answer"))
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto,
                disabledToolNamesFailure = IllegalStateException("preferences unavailable")
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "latest question", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(
            listOf(ApiState.Loading, ApiState.Success("Normal answer"), ApiState.Done),
            states.withoutUsageUpdates()
        )
        assertTrue(webSearchRepository.queries.isEmpty())
        assertFalse(openAIAPI.chatCompletionRequests.single().systemText().contains("Available tools:"))
    }

    @Test
    fun `tool calling auto hides web search tools when backend is not configured`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow("""{"type":"final_answer","content":"Normal answer"}""")
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto,
                webSearchBaseUrl = ""
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What is the current Android target SDK?", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Success("Normal answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertTrue(webSearchRepository.queries.isEmpty())
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        val toolPrompt = openAIAPI.chatCompletionRequests.single().systemText()
        assertTrue(toolPrompt.contains("Available tools:"))
        assertTrue(toolPrompt.contains("current_datetime"))
        assertTrue(toolPrompt.contains("device_location"))
        assertFalse(toolPrompt.contains("web_search"))
        assertFalse(toolPrompt.contains("fetch_url"))
    }

    @Test
    fun `tool calling auto keeps non search tool when web search mode is off`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow(
                    """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"current_datetime","arguments":{}}]}"""
                ),
                chatCompletionFlow("""{"type":"final_answer","content":"Draft from datetime"}"""),
                chatCompletionFlow("Final answer with datetime")
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What time is it?", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(ApiState.Loading, states.first())
        assertTrue(states.contains(ApiState.ToolStarted("current_datetime", "current_datetime")))
        assertTrue(states.contains(ApiState.ToolFinished("current_datetime", "current_datetime")))
        assertTrue(states.contains(ApiState.Success("Final answer with datetime")))
        assertEquals(ApiState.Done, states.last())
        assertTrue(webSearchRepository.queries.isEmpty())
        assertEquals(3, openAIAPI.streamChatCompletionCalls)
        val firstToolPrompt = openAIAPI.chatCompletionRequests[0].systemText()
        assertTrue(firstToolPrompt.contains("Available tools:"))
        assertTrue(firstToolPrompt.contains("current_datetime"))
        assertTrue(firstToolPrompt.contains("device_location"))
        assertFalse(firstToolPrompt.contains("web_search"))
        assertFalse(firstToolPrompt.contains("fetch_url"))
    }

    @Test
    fun `disabled web search tool call is rejected without execution`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow(
                    """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"current news"}}]}"""
                ),
                chatCompletionFlow("""{"type":"final_answer","content":"Draft without unavailable tool"}"""),
                chatCompletionFlow("Final answer after rejection")
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Search current news", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(ApiState.Loading, states.first())
        assertTrue(states.contains(ApiState.ToolFailed("web_search", "tool_unavailable:web_search")))
        assertTrue(states.contains(ApiState.Success("Final answer after rejection")))
        assertEquals(ApiState.Done, states.last())
        assertTrue(webSearchRepository.queries.isEmpty())
        assertEquals(3, openAIAPI.streamChatCompletionCalls)
        assertTrue(openAIAPI.chatCompletionRequests[1].systemText().contains("tool_unavailable:web_search"))
    }

    @Test
    fun `openai native tools use filtered non search tool list`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(
                flowOf(
                    FunctionCallArgumentsDoneEvent(
                        itemId = "fc_1",
                        outputIndex = 0,
                        callId = "call_1",
                        name = "current_datetime",
                        arguments = "{}"
                    )
                ),
                flowOf(
                    OutputTextDeltaEvent(
                        itemId = "msg_1",
                        outputIndex = 0,
                        contentIndex = 0,
                        delta = "Final native datetime answer"
                    )
                )
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What time is it?", platformType = null)),
            assistantMessages = emptyList(),
            platform = openAIPlatform()
        ).toList()

        assertEquals(ApiState.Loading, states.first())
        assertTrue(states.contains(ApiState.ToolStarted("current_datetime", "current_datetime")))
        assertTrue(states.contains(ApiState.ToolFinished("current_datetime", "current_datetime")))
        assertTrue(states.contains(ApiState.Success("Final native datetime answer")))
        assertEquals(ApiState.Done, states.last())
        assertTrue(webSearchRepository.queries.isEmpty())
        assertEquals(2, openAIAPI.streamResponsesCalls)
        assertEquals(
            listOf("current_datetime", "device_location"),
            openAIAPI.responsesRequests[0].tools.orEmpty().map { tool -> tool.name }
        )
        assertFalse(openAIAPI.responsesRequests[0].instructions.orEmpty().contains("web_search"))
    }

    @Test
    fun `openai native tool loop with zero rounds falls back without a tool request`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(
                flowOf(
                    OutputTextDeltaEvent(
                        itemId = "msg_1",
                        outputIndex = 0,
                        contentIndex = 0,
                        delta = "Normal answer"
                    )
                )
            )
        )
        val webSearchRepository = RecordingWebSearchRepository()
        val toolLoopOrchestrator = ToolLoopOrchestrator(
            toolExecutor = ToolExecutor(
                BuiltInTools(
                    webSearchRepository = webSearchRepository,
                    webPageExtractor = WebPageExtractor()
                ).registry()
            ),
            config = ToolLoopConfig(maxToolRounds = 0)
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository,
            toolLoopOrchestrator = toolLoopOrchestrator
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hello", platformType = null)),
            assistantMessages = emptyList(),
            platform = openAIPlatform()
        ).toList()

        assertTrue(states.contains(ApiState.Success("Normal answer")))
        assertEquals(1, openAIAPI.streamResponsesCalls)
        assertTrue(openAIAPI.responsesRequests.single().tools.orEmpty().isEmpty())
    }

    @Test
    fun `openai native tool rounds retain sources from earlier rounds`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(
                flowOf(
                    FunctionCallArgumentsDoneEvent(
                        itemId = "fc_1",
                        outputIndex = 0,
                        callId = "call_1",
                        name = "current_datetime",
                        arguments = "{}"
                    )
                ),
                flowOf(
                    FunctionCallArgumentsDoneEvent(
                        itemId = "fc_2",
                        outputIndex = 0,
                        callId = "call_2",
                        name = "device_location",
                        arguments = "{}"
                    )
                ),
                flowOf(
                    OutputTextDeltaEvent(
                        itemId = "msg_1",
                        outputIndex = 0,
                        contentIndex = 0,
                        delta = "Final answer with two sources"
                    )
                )
            )
        )
        val toolLoopOrchestrator = ToolLoopOrchestrator(
            ToolExecutor(
                ToolRegistry(
                    listOf(
                        sourceProvider(ToolDefinition.CurrentDateTime, "https://example.com/first"),
                        sourceProvider(ToolDefinition.DeviceLocation, "https://example.com/second")
                    )
                )
            )
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            toolLoopOrchestrator = toolLoopOrchestrator
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Use both tools", platformType = null)),
            assistantMessages = emptyList(),
            platform = openAIPlatform()
        ).toList()

        val sourceUpdates = states.filterIsInstance<ApiState.SourcesUpdated>()
        assertEquals(2, sourceUpdates.size)
        assertEquals(listOf("https://example.com/first"), sourceUpdates[0].sources.map { source -> source.url })
        assertEquals(
            listOf("https://example.com/first", "https://example.com/second"),
            sourceUpdates[1].sources.map { source -> source.url }
        )
    }

    @Test
    fun `auto search decision executes web search before final provider request`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow("Final answer with source")
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val searchDecisionService = SearchDecisionService(
            SearchDecisionModelClient { _, prompt ->
                assertTrue(prompt.contains("latest Kotlin release"))
                assertTrue(prompt.contains("Runtime context"))
                assertTrue(prompt.contains("Current local date/time"))
                Result.success(
                    SearchDecisionModelResponse(
                        """{"shouldSearch":true,"queries":["latest Kotlin release"],"reason":"latest requested"}"""
                    )
                )
            }
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository,
            searchDecisionService = searchDecisionService
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Search the latest Kotlin release", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(ApiState.Loading, states.first())
        assertTrue(states.contains(ApiState.ToolStarted("web_search", "latest Kotlin release")))
        assertTrue(states.contains(ApiState.ToolFinished("web_search", "latest Kotlin release")))
        assertTrue(states.contains(ApiState.Success("Final answer with source")))
        assertEquals(ApiState.Done, states.last())
        assertEquals(listOf("latest Kotlin release"), webSearchRepository.queries)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertTrue(openAIAPI.chatCompletionRequests.single().systemText().contains("Tool results are available"))
        assertTrue(openAIAPI.chatCompletionRequests.single().systemText().contains("https://example.com/source"))
    }

    @Test
    fun `auto search decision and final answer aggregate once across all execution paths`() = runBlocking {
        searchDecisionUsageScenarios().forEach { scenario ->
            val usage = scenario.collectSingleUsage()

            assertEquals("${scenario.name} visible input", 20, usage.inputTokens)
            assertEquals("${scenario.name} visible output", 7, usage.outputTokens)
            assertEquals("${scenario.name} visible total", 27, usage.totalTokens)
            assertEquals("${scenario.name} aggregate input", 30, usage.toolInputTokens)
            assertEquals("${scenario.name} aggregate output", 12, usage.toolOutputTokens)
            assertEquals("${scenario.name} aggregate total", 42, usage.toolTotalTokens)
            assertEquals("${scenario.name} must not duplicate final usage", 42, usage.details.sumOf { it.totalTokens })
            assertTrue("${scenario.name} details should be tool related", usage.details.all { it.isToolRelated })
            assertFalse("${scenario.name} should prefer exact provider usage", usage.isEstimated)
        }
    }

    @Test
    fun `malformed nonempty fallback tool envelopes retain full loop usage`() = runBlocking {
        listOf(
            "malformed" to """{"type":"tool_calls","tool_calls":[{""",
            "missing discriminator" to """{"tool_calls":[{"name":"web_search"}]}"""
        ).forEach { (name, envelope) ->
            val openAIAPI = RecordingOpenAIAPI(
                chatCompletionResponses = mutableListOf(
                    chatCompletionFlow(envelope, ProviderUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)),
                    chatCompletionFlow("Final answer", ProviderUsage(promptTokens = 20, completionTokens = 7, totalTokens = 27))
                )
            )
            val repository = createRepository(
                openAIAPI = openAIAPI,
                settingRepository = settingRepository(
                    webSearchMode = WebSearchMode.Off,
                    toolCallingMode = ToolCallingMode.Auto
                )
            )

            val usage = UsageScenario(name, repository, customPlatform()).collectSingleUsage()

            assertEquals("$name visible answer", 27, usage.totalTokens)
            assertEquals("$name full loop", 42, usage.toolTotalTokens)
            assertEquals("$name no duplicate rounds", 42, usage.details.sumOf { it.totalTokens })
            assertTrue("$name tool classification", usage.details.all { it.isToolRelated })
        }
    }

    @Test
    fun `empty fallback tool envelope remains ordinary`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow(
                    """{"type":"tool_calls","tool_calls":[]}""",
                    ProviderUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
                ),
                chatCompletionFlow(
                    "Final answer",
                    ProviderUsage(promptTokens = 20, completionTokens = 7, totalTokens = 27)
                )
            )
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            )
        )

        val usage = UsageScenario("empty fallback envelope", repository, customPlatform()).collectSingleUsage()

        assertEquals(27, usage.totalTokens)
        assertEquals(0, usage.toolTotalTokens)
        assertEquals(27, usage.details.sumOf { detail -> detail.totalTokens })
        assertTrue(usage.details.none { detail -> detail.isToolRelated })
    }

    @Test
    fun `oversized native tool calls retain aggregated usage across providers`() = runBlocking {
        val config = ToolLoopConfig(maxToolArgumentChars = 4)
        val settings = settingRepository(
            webSearchMode = WebSearchMode.Off,
            toolCallingMode = ToolCallingMode.Auto
        )

        fun repository(
            openAIAPI: OpenAIAPI = RecordingOpenAIAPI(),
            anthropicAPI: AnthropicAPI = RecordingAnthropicAPI(),
            googleAPI: GoogleAPI = RecordingGoogleAPI()
        ): ChatRepositoryImpl {
            val searchRepository = RecordingWebSearchRepository()
            return createRepository(
                openAIAPI = openAIAPI,
                anthropicAPI = anthropicAPI,
                googleAPI = googleAPI,
                settingRepository = settings,
                webSearchRepository = searchRepository,
                toolLoopOrchestrator = toolLoopOrchestrator(searchRepository, config)
            )
        }

        val openAIResponsesAPI = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(
                flowOf(
                    FunctionCallArgumentsDoneEvent(
                        itemId = "function_1",
                        outputIndex = 0,
                        callId = "call_1",
                        name = ToolDefinition.CurrentDateTime.name,
                        arguments = "12345"
                    )
                )
            )
        )
        val openRouterAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatToolCallFlow("call_1", ToolDefinition.CurrentDateTime.name, "12345")
            )
        )
        val anthropicAPI = RecordingAnthropicAPI(
            responses = mutableListOf(
                flowOf(
                    ContentStartResponseChunk(
                        index = 0,
                        contentBlock = ContentBlock(
                            type = ContentBlockType.TOOL_USE,
                            id = "toolu_1",
                            name = ToolDefinition.CurrentDateTime.name,
                            input = buildJsonObject { put("value", JsonPrimitive("12345")) }
                        )
                    )
                )
            )
        )
        val googleAPI = RecordingGoogleAPI(
            responses = mutableListOf(
                flowOf(
                    GenerateContentResponse(
                        candidates = listOf(
                            Candidate(
                                content = Content(
                                    role = GoogleRole.MODEL,
                                    parts = listOf(
                                        Part.functionCall(
                                            id = "function_1",
                                            name = ToolDefinition.CurrentDateTime.name,
                                            args = buildJsonObject { put("value", JsonPrimitive("12345")) }
                                        )
                                    )
                                )
                            )
                        ),
                        usageMetadata = UsageMetadata(
                            promptTokenCount = 10,
                            candidatesTokenCount = 2,
                            totalTokenCount = 12
                        )
                    )
                )
            )
        )
        val scenarios = listOf(
            UsageScenario("OpenAI Responses", repository(openAIAPI = openAIResponsesAPI), openAIPlatform()),
            UsageScenario("OpenRouter Chat Completions", repository(openAIAPI = openRouterAPI), openRouterPlatform()),
            UsageScenario("Anthropic native", repository(anthropicAPI = anthropicAPI), anthropicPlatform()),
            UsageScenario("Google native", repository(googleAPI = googleAPI), googlePlatform())
        )

        scenarios.forEach { scenario ->
            val states = scenario.repository.completeChat(
                userMessages = listOf(MessageV2(content = "What time is it?", platformType = null)),
                assistantMessages = emptyList(),
                platform = scenario.platform
            ).toList()
            val usage = states.filterIsInstance<ApiState.UsageUpdated>().single().usage

            assertTrue(
                "${scenario.name} should expose the tool limit error",
                states.filterIsInstance<ApiState.Error>().any { it.message == "tool_arguments_too_large" }
            )
            assertTrue("${scenario.name} should retain usage", usage.toolTotalTokens > 0)
            assertEquals("${scenario.name} has no visible answer", 0, usage.totalTokens)
            assertEquals(
                "${scenario.name} should not duplicate usage",
                usage.toolTotalTokens,
                usage.details.sumOf { it.totalTokens }
            )
            assertTrue("${scenario.name} details should be tool related", usage.details.all { it.isToolRelated })
        }
    }

    @Test
    fun `native transport failures retain prior and failed round usage without duplication`() = runBlocking {
        val settings = settingRepository(
            webSearchMode = WebSearchMode.Off,
            toolCallingMode = ToolCallingMode.Auto
        )
        val firstUsage = ProviderUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
        val scenarios = listOf(
            UsageScenario(
                name = "OpenAI Responses",
                repository = createRepository(
                    openAIAPI = RecordingOpenAIAPI(
                        responsesResponses = mutableListOf(
                            responseToolCallFlow(firstUsage),
                            flow { throw IOException("native transport failure") }
                        )
                    ),
                    settingRepository = settings
                ),
                platform = openAIPlatform()
            ),
            UsageScenario(
                name = "OpenRouter Chat Completions",
                repository = createRepository(
                    openAIAPI = RecordingOpenAIAPI(
                        chatCompletionResponses = mutableListOf(
                            chatToolCallFlow("call_1", ToolDefinition.CurrentDateTime.name, "{}", firstUsage),
                            flow { throw IOException("native transport failure") }
                        )
                    ),
                    settingRepository = settings
                ),
                platform = openRouterPlatform()
            ),
            UsageScenario(
                name = "Anthropic native",
                repository = createRepository(
                    anthropicAPI = RecordingAnthropicAPI(
                        responses = mutableListOf(
                            anthropicToolCallFlow(
                                inputTokens = 8,
                                cacheCreationInputTokens = 2,
                                cacheReadInputTokens = 1,
                                outputTokens = 5,
                                includeProviderUsage = true
                            ),
                            flow { throw IOException("native transport failure") }
                        )
                    ),
                    settingRepository = settings
                ),
                platform = anthropicPlatform()
            ),
            UsageScenario(
                name = "Google native",
                repository = createRepository(
                    googleAPI = RecordingGoogleAPI(
                        responses = mutableListOf(
                            googleToolCallFlow(
                                UsageMetadata(
                                    promptTokenCount = 10,
                                    candidatesTokenCount = 2,
                                    totalTokenCount = 12
                                )
                            ),
                            flow { throw IOException("native transport failure") }
                        )
                    ),
                    settingRepository = settings
                ),
                platform = googlePlatform()
            )
        )

        scenarios.forEach { scenario ->
            val states = scenario.repository.completeChat(
                userMessages = listOf(MessageV2(content = "What time is it?", platformType = null)),
                assistantMessages = emptyList(),
                platform = scenario.platform
            ).toList()
            val usage = states.filterIsInstance<ApiState.UsageUpdated>().single().usage

            assertTrue(
                "${scenario.name} should surface the transport error",
                states.filterIsInstance<ApiState.Error>().any { state -> state.message == "native transport failure" }
            )
            assertEquals("${scenario.name} has no visible final answer", 0, usage.totalTokens)
            assertEquals("${scenario.name} should retain exactly two rounds", 2, usage.details.size)
            assertFalse("${scenario.name} first round should stay exact", usage.details.first().isEstimated)
            assertTrue("${scenario.name} failed round should be estimated", usage.details.last().isEstimated)
            assertEquals(
                "${scenario.name} must not duplicate a round",
                usage.toolTotalTokens,
                usage.details.sumOf { detail -> detail.totalTokens }
            )
            assertTrue(
                "${scenario.name} should include the failed request cost",
                usage.toolTotalTokens > usage.details.first().totalTokens
            )
        }
    }

    @Test
    fun `native transport cancellation remains cooperative`() {
        val repository = createRepository(
            openAIAPI = RecordingOpenAIAPI(
                responsesResponses = mutableListOf(
                    flow<ResponsesStreamEvent> { throw CancellationException("cancel native round") }
                )
            ),
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            )
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                repository.completeChat(
                    userMessages = listOf(MessageV2(content = "Hello", platformType = null)),
                    assistantMessages = emptyList(),
                    platform = openAIPlatform()
                ).toList()
            }
        }
    }

    @Test
    fun `fallback final failure estimates the missing round inside the tool aggregate`() = runBlocking {
        val firstUsage = ProviderUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
        val secondUsage = ProviderUsage(promptTokens = 20, completionTokens = 7, totalTokens = 27)
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow(toolCallProtocol(), firstUsage),
                chatCompletionFlow(directAnswerProtocol(), secondUsage),
                flowOf(ChatCompletionChunk(error = ErrorDetail(message = "final provider failure")))
            )
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            )
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What time is it?", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()
        val usage = states.filterIsInstance<ApiState.UsageUpdated>().single().usage

        assertTrue(states.contains(ApiState.Error("final provider failure")))
        assertEquals(0, usage.totalTokens)
        assertEquals(3, usage.details.size)
        assertTrue(usage.details.last().isEstimated)
        assertTrue(usage.details.all { detail -> detail.isToolRelated })
        assertEquals(usage.toolTotalTokens, usage.details.sumOf { detail -> detail.totalTokens })
        assertTrue(usage.toolTotalTokens > firstUsage.totalTokens!! + secondUsage.totalTokens!!)
    }

    @Test
    fun `auto web search uses openai responses native tools for openai platform`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(
                flowOf(
                    FunctionCallArgumentsDoneEvent(
                        itemId = "fc_1",
                        outputIndex = 0,
                        callId = "call_1",
                        name = "web_search",
                        arguments = """{"query":"current Android target SDK"}"""
                    )
                ),
                flowOf(
                    OutputTextDeltaEvent(
                        itemId = "msg_1",
                        outputIndex = 0,
                        contentIndex = 0,
                        delta = "Final searched answer"
                    )
                )
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What is the current Android target SDK?", platformType = null)),
            assistantMessages = emptyList(),
            platform = openAIPlatform()
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.ToolStarted("web_search", "current Android target SDK"),
                ApiState.ToolFinished("web_search", "current Android target SDK"),
                ApiState.SourcesUpdated(
                    listOf(
                        MessageSourceMetadata(
                            title = "Example Source",
                            url = "https://example.com/source",
                            snippet = "Example search snippet",
                            sourceToolName = "web_search"
                        )
                    )
                ),
                ApiState.Success("Final searched answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(listOf("current Android target SDK"), webSearchRepository.queries)
        assertEquals(0, openAIAPI.streamChatCompletionCalls)
        assertEquals(2, openAIAPI.streamResponsesCalls)
        assertEquals(ResponseToolChoice.Auto, openAIAPI.responsesRequests[0].toolChoice)
        assertTrue(openAIAPI.responsesRequests[0].tools.orEmpty().any { tool -> tool.name == "web_search" })
        assertTrue(openAIAPI.responsesRequests[1].input.any { item -> item is ResponseFunctionCallInputItem && item.callId == "call_1" })
        assertTrue(openAIAPI.responsesRequests[1].input.any { item -> item is ResponseFunctionCallOutputItem && item.callId == "call_1" })
    }

    @Test
    fun `auto web search uses openrouter native chat completion tools`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatToolCallFlow(
                    callId = "call_1",
                    name = "web_search",
                    arguments = """{"query":"current Android target SDK"}"""
                ),
                chatCompletionFlow("Final searched answer")
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What is the current Android target SDK?", platformType = null)),
            assistantMessages = emptyList(),
            platform = openRouterPlatform()
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.ToolStarted("web_search", "current Android target SDK"),
                ApiState.ToolFinished("web_search", "current Android target SDK"),
                ApiState.SourcesUpdated(
                    listOf(
                        MessageSourceMetadata(
                            title = "Example Source",
                            url = "https://example.com/source",
                            snippet = "Example search snippet",
                            sourceToolName = "web_search"
                        )
                    )
                ),
                ApiState.Success("Final searched answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(listOf("current Android target SDK"), webSearchRepository.queries)
        assertEquals(2, openAIAPI.streamChatCompletionCalls)
        assertEquals(ChatCompletionToolChoice.Auto, openAIAPI.chatCompletionRequests[0].toolChoice)
        assertTrue(openAIAPI.chatCompletionRequests[0].tools.orEmpty().any { tool -> tool.function.name == "web_search" })
        assertFalse(openAIAPI.chatCompletionRequests[0].systemText().contains("Available tools:"))
        assertEquals(ChatCompletionToolChoice.Auto, openAIAPI.chatCompletionRequests[1].toolChoice)
        assertTrue(
            openAIAPI.chatCompletionRequests[1].messages.any { message ->
                message.role == OpenAIRole.ASSISTANT &&
                    message.toolCalls.orEmpty().any { call -> call.id == "call_1" && call.function.name == "web_search" }
            }
        )
        assertTrue(
            openAIAPI.chatCompletionRequests[1].messages.any { message ->
                message.role == OpenAIRole.TOOL &&
                    message.toolCallId == "call_1" &&
                    message.contentText.orEmpty().contains("Example Source")
            }
        )
    }

    @Test
    fun `auto web search uses anthropic native tools`() = runBlocking {
        val anthropicAPI = RecordingAnthropicAPI(
            responses = mutableListOf(
                flowOf(
                    ContentStartResponseChunk(
                        index = 0,
                        contentBlock = ContentBlock(
                            type = ContentBlockType.TOOL_USE,
                            id = "toolu_1",
                            name = "web_search",
                            input = buildJsonObject {}
                        )
                    ),
                    ContentDeltaResponseChunk(
                        index = 0,
                        delta = ContentBlock(
                            type = ContentBlockType.INPUT_JSON_DELTA,
                            partialJson = """{"query":"current Android target SDK"}"""
                        )
                    )
                ),
                flowOf(
                    ContentDeltaResponseChunk(
                        index = 0,
                        delta = ContentBlock(
                            type = ContentBlockType.DELTA,
                            text = "Final searched answer"
                        )
                    )
                )
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            anthropicAPI = anthropicAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What is the current Android target SDK?", platformType = null)),
            assistantMessages = emptyList(),
            platform = anthropicPlatform()
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.ToolStarted("web_search", "current Android target SDK"),
                ApiState.ToolFinished("web_search", "current Android target SDK"),
                ApiState.SourcesUpdated(
                    listOf(
                        MessageSourceMetadata(
                            title = "Example Source",
                            url = "https://example.com/source",
                            snippet = "Example search snippet",
                            sourceToolName = "web_search"
                        )
                    )
                ),
                ApiState.Success("Final searched answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(listOf("current Android target SDK"), webSearchRepository.queries)
        assertEquals(2, anthropicAPI.streamCalls)
        assertEquals(AnthropicToolChoice.Auto, anthropicAPI.requests[0].toolChoice)
        assertTrue(anthropicAPI.requests[0].tools.orEmpty().any { tool -> tool.name == "web_search" })
        assertFalse(anthropicAPI.requests[0].systemPrompt.orEmpty().contains("Available tools:"))
        assertEquals(AnthropicToolChoice.Auto, anthropicAPI.requests[1].toolChoice)
        assertTrue(
            anthropicAPI.requests[1].messages.any { message ->
                message.role.name == "ASSISTANT" &&
                    message.content.filterIsInstance<ToolUseContent>().any { call -> call.id == "toolu_1" && call.name == "web_search" }
            }
        )
        assertTrue(
            anthropicAPI.requests[1].messages.any { message ->
                message.role.name == "USER" &&
                    message.content.filterIsInstance<ToolResultContent>().any { result ->
                        result.toolUseId == "toolu_1" &&
                            result.content.contains("Example Source")
                    }
            }
        )
    }

    @Test
    fun `auto web search uses google native function calling`() = runBlocking {
        val googleAPI = RecordingGoogleAPI(
            responses = mutableListOf(
                flowOf(
                    GenerateContentResponse(
                        candidates = listOf(
                            Candidate(
                                content = Content(
                                    role = GoogleRole.MODEL,
                                    parts = listOf(
                                        Part.functionCall(
                                            id = "func_1",
                                            name = "web_search",
                                            args = buildJsonObject {
                                                put("query", JsonPrimitive("current Android target SDK"))
                                            }
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                flowOf(
                    GenerateContentResponse(
                        candidates = listOf(
                            Candidate(
                                content = Content(
                                    role = GoogleRole.MODEL,
                                    parts = listOf(Part.text("Final searched answer"))
                                )
                            )
                        )
                    )
                )
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            googleAPI = googleAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What is the current Android target SDK?", platformType = null)),
            assistantMessages = emptyList(),
            platform = googlePlatform()
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.ToolStarted("web_search", "current Android target SDK"),
                ApiState.ToolFinished("web_search", "current Android target SDK"),
                ApiState.SourcesUpdated(
                    listOf(
                        MessageSourceMetadata(
                            title = "Example Source",
                            url = "https://example.com/source",
                            snippet = "Example search snippet",
                            sourceToolName = "web_search"
                        )
                    )
                ),
                ApiState.Success("Final searched answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(listOf("current Android target SDK"), webSearchRepository.queries)
        assertEquals(2, googleAPI.streamCalls)
        assertEquals(GoogleToolConfig.Auto, googleAPI.requests[0].toolConfig)
        assertTrue(googleAPI.requests[0].tools.orEmpty().flatMap { tool -> tool.functionDeclarations }.any { declaration -> declaration.name == "web_search" })
        assertFalse(googleAPI.requests[0].systemInstruction?.parts.orEmpty().any { part -> part.text.orEmpty().contains("Available tools:") })
        assertEquals(GoogleToolConfig.Auto, googleAPI.requests[1].toolConfig)
        assertTrue(
            googleAPI.requests[1].contents.any { content ->
                content.role == GoogleRole.MODEL &&
                    content.parts.any { part -> part.functionCall?.id == "func_1" && part.functionCall.name == "web_search" }
            }
        )
        assertTrue(
            googleAPI.requests[1].contents.any { content ->
                content.role == GoogleRole.USER &&
                    content.parts.any { part ->
                        part.functionResponse?.id == "func_1" &&
                            part.functionResponse.response.toString().contains("Example Source")
                    }
            }
        )
    }

    @Test
    fun `auto tool loop parse failure falls back to normal chat completion`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow("not json"),
                chatCompletionFlow("Normal answer")
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What happened today?", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Success("Normal answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertTrue(webSearchRepository.queries.isEmpty())
        assertEquals(2, openAIAPI.streamChatCompletionCalls)
    }

    @Test
    fun `tool argument limit failure does not retry without tools`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow(
                    """{"type":"tool_calls","tool_calls":[{"name":"web_search","arguments":{"query":"oversized"}}]}"""
                )
            )
        )
        val webSearchRepository = RecordingWebSearchRepository()
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository,
            toolLoopOrchestrator = toolLoopOrchestrator(
                webSearchRepository,
                ToolLoopConfig(maxToolArgumentChars = 8)
            )
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What happened today?", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Error("tool_arguments_too_large"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertTrue(webSearchRepository.queries.isEmpty())
    }

    @Test
    fun `auto tool failure emits progress and still completes final answer`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow(
                    """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"current news"}}]}"""
                ),
                chatCompletionFlow("""{"type":"final_answer","content":"Draft after failed tool"}"""),
                chatCompletionFlow("Final answer despite tool failure")
            )
        )
        val webSearchRepository = RecordingWebSearchRepository(
            Result.failure(IllegalStateException("search unavailable"))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Auto,
                toolCallingMode = ToolCallingMode.Auto
            ),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What happened today?", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()

        assertEquals(ApiState.Loading, states.first())
        assertTrue(states.contains(ApiState.ToolStarted("web_search", "current news")))
        assertTrue(
            states.any { state ->
                state is ApiState.ToolFailed &&
                    state.toolName == "web_search" &&
                    state.message.contains("web_search_failed")
            }
        )
        assertTrue(states.contains(ApiState.Success("Final answer despite tool failure")))
        assertEquals(ApiState.Done, states.last())
    }

    @Test
    fun `tools available direct answers remain ordinary across provider paths`() = runBlocking {
        directUsageScenarios(includeProviderUsage = true).forEach { scenario ->
            val usage = scenario.collectSingleUsage()

            assertEquals("${scenario.name} input", 11, usage.inputTokens)
            assertEquals("${scenario.name} output", 7, usage.outputTokens)
            assertEquals("${scenario.name} total", 18, usage.totalTokens)
            assertEquals("${scenario.name} tool input", 0, usage.toolInputTokens)
            assertEquals("${scenario.name} tool output", 0, usage.toolOutputTokens)
            assertEquals("${scenario.name} tool total", 0, usage.toolTotalTokens)
            assertFalse("${scenario.name} should use provider usage", usage.isEstimated)
            assertTrue("${scenario.name} details should remain ordinary", usage.details.all { detail -> !detail.isToolRelated })
        }
    }

    @Test
    fun `actual tool calls aggregate each provider round exactly once`() = runBlocking {
        toolUsageScenarios().forEach { scenario ->
            val usage = scenario.usageScenario.collectSingleUsage()

            assertEquals("${scenario.usageScenario.name} answer input", scenario.answerInputTokens, usage.inputTokens)
            assertEquals("${scenario.usageScenario.name} answer output", scenario.answerOutputTokens, usage.outputTokens)
            assertEquals("${scenario.usageScenario.name} answer total", scenario.answerTotalTokens, usage.totalTokens)
            assertEquals("${scenario.usageScenario.name} tool input", scenario.toolInputTokens, usage.toolInputTokens)
            assertEquals("${scenario.usageScenario.name} tool output", scenario.toolOutputTokens, usage.toolOutputTokens)
            assertEquals("${scenario.usageScenario.name} tool total", scenario.toolTotalTokens, usage.toolTotalTokens)
            assertFalse("${scenario.usageScenario.name} should use provider usage", usage.isEstimated)
            assertTrue("${scenario.usageScenario.name} should classify the full loop", usage.details.all { detail -> detail.isToolRelated })
            assertEquals(
                "${scenario.usageScenario.name} detail totals must not duplicate rounds",
                scenario.toolTotalTokens,
                usage.details.sumOf { detail -> detail.totalTokens }
            )
        }
    }

    @Test
    fun `missing usage stays estimated and ordinary for direct answers across provider paths`() = runBlocking {
        directUsageScenarios(includeProviderUsage = false).forEach { scenario ->
            val usage = scenario.collectSingleUsage()

            assertTrue("${scenario.name} should estimate missing usage", usage.isEstimated)
            assertTrue("${scenario.name} should estimate input", usage.inputTokens > 0)
            assertTrue("${scenario.name} should estimate output", usage.outputTokens > 0)
            assertEquals("${scenario.name} tool input", 0, usage.toolInputTokens)
            assertEquals("${scenario.name} tool output", 0, usage.toolOutputTokens)
            assertEquals("${scenario.name} tool total", 0, usage.toolTotalTokens)
            assertTrue("${scenario.name} estimated detail should remain ordinary", usage.details.all { detail -> !detail.isToolRelated })
        }
    }

    @Test
    fun `missing usage estimates the complete loop only after an actual tool call`() = runBlocking {
        toolUsageScenarios(includeProviderUsage = false).forEach { scenario ->
            val usage = scenario.usageScenario.collectSingleUsage()

            assertTrue("${scenario.usageScenario.name} should estimate missing loop usage", usage.isEstimated)
            assertTrue("${scenario.usageScenario.name} should retain visible answer usage", usage.totalTokens > 0)
            assertTrue("${scenario.usageScenario.name} should aggregate the tool loop", usage.toolTotalTokens >= usage.totalTokens)
            assertEquals(
                "${scenario.usageScenario.name} estimated rounds must not duplicate",
                usage.toolTotalTokens,
                usage.details.sumOf { detail -> detail.totalTokens }
            )
            assertTrue("${scenario.usageScenario.name} estimated details should be tool related", usage.details.all { detail -> detail.isToolRelated })
        }
    }

    @Test
    fun `tool calling off emits ordinary provider usage`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatCompletionFlow(
                    content = "Normal answer",
                    usage = ProviderUsage(promptTokens = 11, completionTokens = 7, totalTokens = 18)
                )
            )
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Off
            )
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hello", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform()
        ).toList()
        val usage = states.filterIsInstance<ApiState.UsageUpdated>().single().usage

        assertEquals(18, usage.totalTokens)
        assertEquals(0, usage.toolTotalTokens)
        assertTrue(usage.details.all { detail -> !detail.isToolRelated })
        assertFalse(openAIAPI.chatCompletionRequests.single().systemText().contains("Available tools:"))
    }

    @Test
    fun `mergeSystemPrompt keeps base prompt and memory prompt`() {
        val merged = mergeSystemPrompt(
            basePrompt = "Base system prompt.",
            memoryPrompt = "Relevant long-term user memories:\n- Memory"
        )

        assertEquals(
            "Base system prompt.\n\nRelevant long-term user memories:\n- Memory",
            merged
        )
        assertEquals("Base", mergeSystemPrompt("Base", null))
        assertEquals("Memory", mergeSystemPrompt(null, "Memory"))
        assertNull(mergeSystemPrompt(" ", " "))
    }

    @Test
    fun `prompt section merge preserves memory search tool and context summary sections`() {
        val merged = mergePromptSections(
            "System instructions",
            "Local memory context",
            "Web search evidence",
            "Tool protocol",
            "Context summary"
        )

        assertEquals(
            "System instructions\n\nLocal memory context\n\nWeb search evidence\n\nTool protocol\n\nContext summary",
            merged
        )
    }

    @Test
    fun `openai responses final request merges memory exactly once with all prompt sections`() = runBlocking {
        val harness = providerPromptHarness()
        val platform = openAIPlatform(systemPrompt = PROVIDER_BASE_SYSTEM_MARKER)

        harness.execute(platform)

        assertProviderPromptSections(
            provider = "OpenAI Responses",
            prompt = harness.openAIAPI.responsesRequests.single().instructions.orEmpty()
        )
    }

    @Test
    fun `openrouter chat final request merges memory exactly once with all prompt sections`() = runBlocking {
        val harness = providerPromptHarness()
        val platform = openRouterPlatform(systemPrompt = PROVIDER_BASE_SYSTEM_MARKER)

        harness.execute(platform)

        assertProviderPromptSections(
            provider = "OpenRouter chat completions",
            prompt = harness.openAIAPI.chatCompletionRequests.single().systemText()
        )
    }

    @Test
    fun `ollama chat final request merges memory exactly once with all prompt sections`() = runBlocking {
        val harness = providerPromptHarness()
        val platform = ollamaPlatform(systemPrompt = PROVIDER_BASE_SYSTEM_MARKER)

        harness.execute(platform)

        assertProviderPromptSections(
            provider = "Ollama chat completions",
            prompt = harness.openAIAPI.chatCompletionRequests.single().systemText()
        )
    }

    @Test
    fun `groq final request merges memory exactly once with all prompt sections`() = runBlocking {
        val harness = providerPromptHarness()
        val platform = groqPlatform(reasoning = false, model = "llama-3.3-70b-versatile")
            .copy(systemPrompt = PROVIDER_BASE_SYSTEM_MARKER)

        harness.execute(platform)

        assertEquals(1, harness.groqAPI.streamCalls)
        assertProviderPromptSections(
            provider = "Groq",
            prompt = checkNotNull(harness.groqAPI.lastRequest).messages.systemText()
        )
    }

    @Test
    fun `anthropic final request merges memory exactly once with all prompt sections`() = runBlocking {
        val harness = providerPromptHarness()
        val platform = anthropicPlatform(systemPrompt = PROVIDER_BASE_SYSTEM_MARKER)

        harness.execute(platform)

        assertProviderPromptSections(
            provider = "Anthropic",
            prompt = harness.anthropicAPI.requests.single().systemPrompt.orEmpty()
        )
    }

    @Test
    fun `google final request merges memory exactly once with all prompt sections`() = runBlocking {
        val harness = providerPromptHarness()
        val platform = googlePlatform(systemPrompt = PROVIDER_BASE_SYSTEM_MARKER)

        harness.execute(platform)

        val prompt = harness.googleAPI.requests.single().systemInstruction
            ?.parts
            .orEmpty()
            .joinToString(separator = "\n") { part -> part.text.orEmpty() }
        assertProviderPromptSections(provider = "Google", prompt = prompt)
    }

    private fun directUsageScenarios(includeProviderUsage: Boolean): List<UsageScenario> {
        val providerUsage = ProviderUsage(
            promptTokens = 11,
            completionTokens = 7,
            totalTokens = 18
        ).takeIf { includeProviderUsage }
        val openAIResponsesAPI = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(responseTextFlow("Direct answer", providerUsage))
        )
        val openRouterAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(chatCompletionFlow("Direct answer", providerUsage))
        )
        val anthropicAPI = RecordingAnthropicAPI(
            responses = mutableListOf(
                anthropicTextFlow(
                    content = "Direct answer",
                    inputTokens = 8,
                    cacheCreationInputTokens = 2,
                    cacheReadInputTokens = 1,
                    outputTokens = 7,
                    includeProviderUsage = includeProviderUsage
                )
            )
        )
        val googleAPI = RecordingGoogleAPI(
            responses = mutableListOf(
                googleTextFlow(
                    content = "Direct answer",
                    usage = UsageMetadata(
                        promptTokenCount = 11,
                        candidatesTokenCount = 5,
                        thoughtsTokenCount = 2,
                        totalTokenCount = 18
                    ).takeIf { includeProviderUsage }
                )
            )
        )
        val groqAPI = FakeGroqAPI(
            responses = mutableListOf(groqCompletionFlow(directAnswerProtocol(), providerUsage))
        )
        val customAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(chatCompletionFlow(directAnswerProtocol(), providerUsage))
        )
        val ollamaAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(chatCompletionFlow(directAnswerProtocol(), providerUsage))
        )
        val settings = settingRepository(
            webSearchMode = WebSearchMode.Off,
            toolCallingMode = ToolCallingMode.Auto
        )

        return listOf(
            UsageScenario("OpenAI Responses", createRepository(openAIAPI = openAIResponsesAPI, settingRepository = settings), openAIPlatform()),
            UsageScenario("OpenRouter Chat Completions", createRepository(openAIAPI = openRouterAPI, settingRepository = settings), openRouterPlatform()),
            UsageScenario("Anthropic native", createRepository(anthropicAPI = anthropicAPI, settingRepository = settings), anthropicPlatform()),
            UsageScenario("Google native", createRepository(googleAPI = googleAPI, settingRepository = settings), googlePlatform()),
            UsageScenario("Groq fallback", createRepository(groqAPI = groqAPI, settingRepository = settings), groqPlatform(reasoning = false, model = "llama-3.3-70b-versatile")),
            UsageScenario("Custom fallback", createRepository(openAIAPI = customAPI, settingRepository = settings), customPlatform()),
            UsageScenario("Ollama fallback", createRepository(openAIAPI = ollamaAPI, settingRepository = settings), ollamaPlatform())
        )
    }

    private fun searchDecisionUsageScenarios(): List<UsageScenario> {
        val decisionUsage = ProviderUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
        val finalUsage = ProviderUsage(promptTokens = 20, completionTokens = 7, totalTokens = 27)
        val searchDecisionService = SearchDecisionService(
            SearchDecisionModelClient { _, _ ->
                Result.success(
                    SearchDecisionModelResponse(
                        content = """{"shouldSearch":true,"queries":["current facts"],"reason":"current"}""",
                        usage = decisionUsage
                    )
                )
            }
        )
        val settings = settingRepository(
            webSearchMode = WebSearchMode.Auto,
            toolCallingMode = ToolCallingMode.Auto
        )

        fun repository(
            openAIAPI: OpenAIAPI = RecordingOpenAIAPI(),
            anthropicAPI: AnthropicAPI = RecordingAnthropicAPI(),
            googleAPI: GoogleAPI = RecordingGoogleAPI()
        ): ChatRepositoryImpl {
            val searchRepository = RecordingWebSearchRepository(Result.success(listOf(webSearchResult())))
            return createRepository(
                openAIAPI = openAIAPI,
                anthropicAPI = anthropicAPI,
                googleAPI = googleAPI,
                settingRepository = settings,
                webSearchRepository = searchRepository,
                searchDecisionService = searchDecisionService
            )
        }

        val openAIResponsesAPI = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(responseTextFlow("Final answer", finalUsage))
        )
        val openRouterAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(chatCompletionFlow("Final answer", finalUsage))
        )
        val anthropicAPI = RecordingAnthropicAPI(
            responses = mutableListOf(
                anthropicTextFlow(
                    content = "Final answer",
                    inputTokens = 20,
                    cacheCreationInputTokens = 0,
                    cacheReadInputTokens = 0,
                    outputTokens = 7,
                    includeProviderUsage = true
                )
            )
        )
        val googleAPI = RecordingGoogleAPI(
            responses = mutableListOf(
                googleTextFlow(
                    content = "Final answer",
                    usage = UsageMetadata(
                        promptTokenCount = 20,
                        candidatesTokenCount = 7,
                        totalTokenCount = 27
                    )
                )
            )
        )
        val customAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(chatCompletionFlow("Final answer", finalUsage))
        )

        return listOf(
            UsageScenario("OpenAI Responses", repository(openAIAPI = openAIResponsesAPI), openAIPlatform()),
            UsageScenario("OpenRouter Chat Completions", repository(openAIAPI = openRouterAPI), openRouterPlatform()),
            UsageScenario("Anthropic native", repository(anthropicAPI = anthropicAPI), anthropicPlatform()),
            UsageScenario("Google native", repository(googleAPI = googleAPI), googlePlatform()),
            UsageScenario("JSON fallback", repository(openAIAPI = customAPI), customPlatform())
        )
    }

    private fun toolUsageScenarios(includeProviderUsage: Boolean = true): List<ToolUsageScenario> {
        val firstUsage = ProviderUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
            .takeIf { includeProviderUsage }
        val secondUsage = ProviderUsage(promptTokens = 20, completionTokens = 7, totalTokens = 27)
            .takeIf { includeProviderUsage }
        val finalUsage = ProviderUsage(promptTokens = 30, completionTokens = 10, totalTokens = 40)
            .takeIf { includeProviderUsage }
        val settings = settingRepository(
            webSearchMode = WebSearchMode.Off,
            toolCallingMode = ToolCallingMode.Auto
        )

        val openAIResponsesAPI = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(
                responseToolCallFlow(firstUsage),
                responseTextFlow("Final answer", secondUsage)
            )
        )
        val openRouterAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatToolCallFlow("call_1", ToolDefinition.CurrentDateTime.name, "{}", firstUsage),
                chatCompletionFlow("Final answer", secondUsage)
            )
        )
        val anthropicAPI = RecordingAnthropicAPI(
            responses = mutableListOf(
                anthropicToolCallFlow(
                    inputTokens = 8,
                    cacheCreationInputTokens = 2,
                    cacheReadInputTokens = 1,
                    outputTokens = 5,
                    includeProviderUsage = includeProviderUsage
                ),
                anthropicTextFlow(
                    content = "Final answer",
                    inputTokens = 14,
                    cacheCreationInputTokens = 4,
                    cacheReadInputTokens = 2,
                    outputTokens = 7,
                    includeProviderUsage = includeProviderUsage
                )
            )
        )
        val googleAPI = RecordingGoogleAPI(
            responses = mutableListOf(
                googleToolCallFlow(
                    UsageMetadata(
                        promptTokenCount = 10,
                        candidatesTokenCount = 0,
                        toolUsePromptTokenCount = 6,
                        thoughtsTokenCount = 2,
                        totalTokenCount = 12
                    ).takeIf { includeProviderUsage }
                ),
                googleTextFlow(
                    content = "Final answer",
                    usage = UsageMetadata(
                        promptTokenCount = 20,
                        candidatesTokenCount = 7,
                        toolUsePromptTokenCount = 9,
                        thoughtsTokenCount = 3,
                        totalTokenCount = 30
                    ).takeIf { includeProviderUsage }
                )
            )
        )
        val fallbackResponses = {
            mutableListOf(
                chatCompletionFlow(toolCallProtocol(), firstUsage),
                chatCompletionFlow(directAnswerProtocol(), secondUsage),
                chatCompletionFlow("Final answer", finalUsage)
            )
        }
        val groqAPI = FakeGroqAPI(
            responses = mutableListOf(
                groqCompletionFlow(toolCallProtocol(), firstUsage),
                groqCompletionFlow(directAnswerProtocol(), secondUsage),
                groqCompletionFlow("Final answer", finalUsage)
            )
        )
        val customAPI = RecordingOpenAIAPI(chatCompletionResponses = fallbackResponses())
        val ollamaAPI = RecordingOpenAIAPI(chatCompletionResponses = fallbackResponses())

        return listOf(
            ToolUsageScenario(
                usageScenario = UsageScenario("OpenAI Responses", createRepository(openAIAPI = openAIResponsesAPI, settingRepository = settings), openAIPlatform()),
                answerInputTokens = 20,
                answerOutputTokens = 7,
                answerTotalTokens = 27,
                toolInputTokens = 30,
                toolOutputTokens = 12,
                toolTotalTokens = 42
            ),
            ToolUsageScenario(
                usageScenario = UsageScenario("OpenRouter Chat Completions", createRepository(openAIAPI = openRouterAPI, settingRepository = settings), openRouterPlatform()),
                answerInputTokens = 20,
                answerOutputTokens = 7,
                answerTotalTokens = 27,
                toolInputTokens = 30,
                toolOutputTokens = 12,
                toolTotalTokens = 42
            ),
            ToolUsageScenario(
                usageScenario = UsageScenario("Anthropic native", createRepository(anthropicAPI = anthropicAPI, settingRepository = settings), anthropicPlatform()),
                answerInputTokens = 20,
                answerOutputTokens = 7,
                answerTotalTokens = 27,
                toolInputTokens = 31,
                toolOutputTokens = 12,
                toolTotalTokens = 43
            ),
            ToolUsageScenario(
                usageScenario = UsageScenario("Google native", createRepository(googleAPI = googleAPI, settingRepository = settings), googlePlatform()),
                answerInputTokens = 20,
                answerOutputTokens = 10,
                answerTotalTokens = 30,
                toolInputTokens = 30,
                toolOutputTokens = 12,
                toolTotalTokens = 42
            ),
            fallbackToolUsageScenario(
                name = "Groq fallback",
                repository = createRepository(groqAPI = groqAPI, settingRepository = settings),
                platform = groqPlatform(reasoning = false, model = "llama-3.3-70b-versatile")
            ),
            fallbackToolUsageScenario(
                name = "Custom fallback",
                repository = createRepository(openAIAPI = customAPI, settingRepository = settings),
                platform = customPlatform()
            ),
            fallbackToolUsageScenario(
                name = "Ollama fallback",
                repository = createRepository(openAIAPI = ollamaAPI, settingRepository = settings),
                platform = ollamaPlatform()
            )
        )
    }

    private fun fallbackToolUsageScenario(
        name: String,
        repository: ChatRepositoryImpl,
        platform: PlatformV2
    ): ToolUsageScenario = ToolUsageScenario(
        usageScenario = UsageScenario(name, repository, platform),
        answerInputTokens = 30,
        answerOutputTokens = 10,
        answerTotalTokens = 40,
        toolInputTokens = 60,
        toolOutputTokens = 22,
        toolTotalTokens = 82
    )

    private suspend fun UsageScenario.collectSingleUsage(): TokenUsageRecord {
        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What time is it?", platformType = null)),
            assistantMessages = emptyList(),
            platform = platform
        ).toList()
        val usageStates = states.filterIsInstance<ApiState.UsageUpdated>()
        assertEquals("$name should emit one final usage record", 1, usageStates.size)
        return usageStates.single().usage
    }

    private data class UsageScenario(
        val name: String,
        val repository: ChatRepositoryImpl,
        val platform: PlatformV2
    )

    private data class ToolUsageScenario(
        val usageScenario: UsageScenario,
        val answerInputTokens: Int,
        val answerOutputTokens: Int,
        val answerTotalTokens: Int,
        val toolInputTokens: Int,
        val toolOutputTokens: Int,
        val toolTotalTokens: Int
    )

    private fun createRepository(
        groqAPI: GroqAPI = FakeGroqAPI(emptyFlow()),
        openAIAPI: OpenAIAPI = RecordingOpenAIAPI(),
        anthropicAPI: AnthropicAPI = RecordingAnthropicAPI(),
        googleAPI: GoogleAPI = RecordingGoogleAPI(),
        settingRepository: SettingRepository = settingRepository(WebSearchMode.Off),
        webSearchRepository: WebSearchRepository = RecordingWebSearchRepository(),
        toolLoopOrchestrator: ToolLoopOrchestrator = toolLoopOrchestrator(webSearchRepository),
        searchDecisionService: SearchDecisionService? = null
    ): ChatRepositoryImpl = ChatRepositoryImpl(
        context = ContextWrapper(null),
        chatRoomDao = proxy(),
        messageDao = proxy(),
        chatRoomV2Dao = proxy(),
        messageV2Dao = proxy(),
        chatPlatformModelV2Dao = proxy(),
        settingRepository = settingRepository,
        openAIAPI = openAIAPI,
        groqAPI = groqAPI,
        anthropicAPI = anthropicAPI,
        googleAPI = googleAPI,
        attachmentUploadCoordinator = AttachmentUploadCoordinator(
            openAIAPI,
            anthropicAPI,
            googleAPI
        ),
        contextBuilder = ContextBuilder(),
        toolLoopOrchestrator = toolLoopOrchestrator,
        searchDecisionService = searchDecisionService
    )

    private fun providerPromptHarness(): ProviderPromptHarness {
        val openAIAPI = RecordingOpenAIAPI()
        val groqAPI = FakeGroqAPI(emptyFlow())
        val anthropicAPI = RecordingAnthropicAPI()
        val googleAPI = RecordingGoogleAPI()
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val searchDecisionService = SearchDecisionService(
            SearchDecisionModelClient { _, _ ->
                Result.success(
                    SearchDecisionModelResponse(
                        """{"shouldSearch":true,"queries":["provider prompt assembly evidence"],"reason":"test evidence"}"""
                    )
                )
            }
        )
        return ProviderPromptHarness(
            repository = createRepository(
                groqAPI = groqAPI,
                openAIAPI = openAIAPI,
                anthropicAPI = anthropicAPI,
                googleAPI = googleAPI,
                settingRepository = settingRepository(
                    webSearchMode = WebSearchMode.Auto,
                    toolCallingMode = ToolCallingMode.Auto
                ),
                webSearchRepository = webSearchRepository,
                searchDecisionService = searchDecisionService
            ),
            openAIAPI = openAIAPI,
            groqAPI = groqAPI,
            anthropicAPI = anthropicAPI,
            googleAPI = googleAPI
        )
    }

    private suspend fun ProviderPromptHarness.execute(platform: PlatformV2) {
        val (userMessages, assistantMessages) = promptAssemblyConversation(platform.uid)
        repository.completeChat(
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            platform = platform,
            memoryPrompt = "Relevant long-term user memories:\n- $PROVIDER_MEMORY_MARKER"
        ).toList()
    }

    private fun promptAssemblyConversation(
        platformUid: String
    ): Pair<List<MessageV2>, List<List<MessageV2>>> {
        val userMessages = (1..12).map { index ->
            MessageV2(
                id = index,
                content = if (index == 12) {
                    "Find current provider prompt assembly evidence"
                } else {
                    "provider-topic-$index user detail"
                },
                platformType = null
            )
        }
        val assistantMessages = (1..12).map { index ->
            if (index == 12) {
                emptyList()
            } else {
                listOf(
                    MessageV2(
                        id = 100 + index,
                        content = "provider-topic-$index assistant detail",
                        platformType = platformUid
                    )
                )
            }
        }
        return userMessages to assistantMessages
    }

    private fun assertProviderPromptSections(provider: String, prompt: String) {
        assertEquals(
            "$provider memory marker count",
            1,
            Regex(Regex.escape(PROVIDER_MEMORY_MARKER)).findAll(prompt).count()
        )
        assertTrue("$provider lost base system prompt", prompt.contains(PROVIDER_BASE_SYSTEM_MARKER))
        assertTrue("$provider lost runtime context", prompt.contains("Runtime context:"))
        assertTrue("$provider lost context summary", prompt.contains("Earlier conversation summary:"))
        assertTrue("$provider lost omitted context", prompt.contains("provider-topic-1"))
        assertTrue("$provider lost tool result prompt", prompt.contains("Tool results are available"))
        assertTrue("$provider lost search evidence", prompt.contains("https://example.com/source"))
    }

    private data class ProviderPromptHarness(
        val repository: ChatRepositoryImpl,
        val openAIAPI: RecordingOpenAIAPI,
        val groqAPI: FakeGroqAPI,
        val anthropicAPI: RecordingAnthropicAPI,
        val googleAPI: RecordingGoogleAPI
    )

    private companion object {
        const val PROVIDER_BASE_SYSTEM_MARKER = "__PROVIDER_BASE_SYSTEM_5CFA__"
        const val PROVIDER_MEMORY_MARKER = "__PROVIDER_MEMORY_EXACTLY_ONCE_7E31__"
    }

    private fun List<ApiState>.withoutUsageUpdates(): List<ApiState> =
        filterNot { it is ApiState.UsageUpdated }

    private fun groqPlatform(reasoning: Boolean, model: String) = PlatformV2(
        uid = "groq-platform",
        name = "Groq",
        compatibleType = ClientType.GROQ,
        apiUrl = "https://api.groq.com/openai/",
        model = model,
        reasoning = reasoning
    )

    private fun customPlatform(systemPrompt: String? = null) = PlatformV2(
        uid = "custom-platform",
        name = "Custom",
        compatibleType = ClientType.CUSTOM,
        apiUrl = "https://example.com",
        model = "custom-model",
        systemPrompt = systemPrompt,
        stream = true
    )

    private fun openAIPlatform(systemPrompt: String? = null) = PlatformV2(
        uid = "openai-platform",
        name = "OpenAI",
        compatibleType = ClientType.OPENAI,
        apiUrl = "https://api.openai.com/",
        token = "token",
        model = "gpt-5",
        systemPrompt = systemPrompt,
        stream = true
    )

    private fun googlePlatform(systemPrompt: String? = null) = PlatformV2(
        uid = "google-platform",
        name = "Google",
        compatibleType = ClientType.GOOGLE,
        apiUrl = "https://generativelanguage.googleapis.com",
        token = "token",
        model = "gemini-pro",
        systemPrompt = systemPrompt,
        stream = true
    )

    private fun anthropicPlatform(systemPrompt: String? = null) = PlatformV2(
        uid = "anthropic-platform",
        name = "Anthropic",
        compatibleType = ClientType.ANTHROPIC,
        apiUrl = "https://api.anthropic.com/",
        token = "token",
        model = "claude-sonnet",
        systemPrompt = systemPrompt,
        stream = true
    )

    private fun openRouterPlatform(systemPrompt: String? = null) = PlatformV2(
        uid = "openrouter-platform",
        name = "OpenRouter",
        compatibleType = ClientType.OPENROUTER,
        apiUrl = "https://openrouter.ai/api/",
        token = "token",
        model = "openrouter-model",
        systemPrompt = systemPrompt,
        stream = true
    )

    private fun ollamaPlatform(systemPrompt: String? = null) = PlatformV2(
        uid = "ollama-platform",
        name = "Ollama",
        compatibleType = ClientType.OLLAMA,
        apiUrl = "http://localhost:11434/",
        model = "llama3.2",
        systemPrompt = systemPrompt,
        stream = true
    )

    private fun systemText(openAIAPI: RecordingOpenAIAPI): String = openAIAPI.lastChatCompletionRequest
        ?.systemText()
        .orEmpty()

    private fun ChatCompletionRequest.systemText(): String = messages
        .firstOrNull()
        ?.content
        ?.filterIsInstance<OpenAITextContent>()
        ?.firstOrNull()
        ?.text
        .orEmpty()

    private fun List<ChatMessage>.systemText(): String =
        firstOrNull()
            ?.content
            ?.filterIsInstance<OpenAITextContent>()
            ?.firstOrNull()
            ?.text
            .orEmpty()

    private fun webSearchResult() = WebSearchResult(
        title = "Example Source",
        url = "https://example.com/source",
        snippet = "Example search snippet",
        source = "searxng"
    )

    private fun sourceProvider(
        definition: ToolDefinition,
        url: String
    ): ToolProvider = object : ToolProvider {
        override val definition: ToolDefinition = definition
        override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult = ToolResult(
            callId = call.id,
            name = call.name,
            content = "Source result",
            sources = listOf(ToolSource.PublicUrl(title = call.name, url = url))
        )
    }

    private fun settingRepository(
        webSearchMode: WebSearchMode,
        toolCallingMode: ToolCallingMode = ToolCallingMode.Off,
        webSearchBaseUrl: String = if (webSearchMode == WebSearchMode.Off) "" else "https://search.example",
        disabledToolNames: Set<String> = emptySet(),
        disabledToolNamesFailure: Throwable? = null
    ): SettingRepository {
        val handler = InvocationHandler { _, method, _ ->
            when (method.name) {
                "fetchToolCallingMode" -> toolCallingMode
                "updateToolCallingMode" -> Unit
                "fetchDisabledToolNames" -> disabledToolNamesFailure?.let { throwable -> throw throwable } ?: disabledToolNames
                "fetchToolEnablementOverrides" -> {
                    disabledToolNamesFailure?.let { throwable -> throw throwable }
                    ToolEnablementOverrides(
                        enabledToolNames = ToolDefinition.BuiltIns
                            .map { definition -> definition.name }
                            .toSet() - disabledToolNames,
                        disabledToolNames = disabledToolNames
                    )
                }
                "updateToolEnabled" -> Unit
                "fetchWebSearchMode" -> webSearchMode
                "updateWebSearchMode" -> Unit
                "fetchWebSearchSearxngBaseUrl" -> webSearchBaseUrl
                "updateWebSearchSearxngBaseUrl" -> Unit
                else -> defaultReturnValue(method.returnType)
            }
        }

        return Proxy.newProxyInstance(
            SettingRepository::class.java.classLoader,
            arrayOf(SettingRepository::class.java),
            handler
        ) as SettingRepository
    }

    private fun toolLoopOrchestrator(
        webSearchRepository: WebSearchRepository,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): ToolLoopOrchestrator =
        ToolLoopOrchestrator(
            ToolExecutor(
                BuiltInTools(
                    webSearchRepository = webSearchRepository,
                    webPageExtractor = WebPageExtractor()
                ).registry()
            ),
            config = config
        )

    private fun chatCompletionFlow(
        content: String,
        usage: ProviderUsage? = null
    ): Flow<ChatCompletionChunk> = flowOf(
        ChatCompletionChunk(
            choices = listOf(
                Choice(
                    index = 0,
                    delta = Delta(content = content)
                )
            )
        ),
        *usage?.let { providerUsage -> arrayOf(ChatCompletionChunk(usage = providerUsage)) }.orEmpty()
    )

    private fun chatToolCallFlow(
        callId: String,
        name: String,
        arguments: String,
        usage: ProviderUsage? = null
    ): Flow<ChatCompletionChunk> = flowOf(
        ChatCompletionChunk(
            choices = listOf(
                Choice(
                    index = 0,
                    delta = Delta(
                        toolCalls = listOf(
                            ChatCompletionToolCallDelta(
                                index = 0,
                                id = callId,
                                type = "function",
                                function = ChatCompletionFunctionCallDelta(
                                    name = name,
                                    arguments = arguments
                                )
                            )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            )
        ),
        *usage?.let { providerUsage -> arrayOf(ChatCompletionChunk(usage = providerUsage)) }.orEmpty()
    )

    private fun responseTextFlow(
        content: String,
        usage: ProviderUsage?
    ): Flow<ResponsesStreamEvent> = flowOf(
        OutputTextDeltaEvent(
            itemId = "message_1",
            outputIndex = 0,
            contentIndex = 0,
            delta = content
        ),
        *usage?.let { providerUsage ->
            arrayOf(
                ResponseCompletedEvent(
                    ResponseObject(
                        id = "response_1",
                        status = "completed",
                        usage = providerUsage
                    )
                )
            )
        }.orEmpty()
    )

    private fun responseToolCallFlow(usage: ProviderUsage?): Flow<ResponsesStreamEvent> = flowOf(
        FunctionCallArgumentsDoneEvent(
            itemId = "function_1",
            outputIndex = 0,
            callId = "call_1",
            name = ToolDefinition.CurrentDateTime.name,
            arguments = "{}"
        ),
        *usage?.let { providerUsage ->
            arrayOf(
                ResponseCompletedEvent(
                    ResponseObject(
                        id = "response_1",
                        status = "completed",
                        usage = providerUsage
                    )
                )
            )
        }.orEmpty()
    )

    private fun anthropicTextFlow(
        content: String,
        inputTokens: Int,
        cacheCreationInputTokens: Int,
        cacheReadInputTokens: Int,
        outputTokens: Int,
        includeProviderUsage: Boolean
    ): Flow<MessageResponseChunk> {
        val chunks = mutableListOf<MessageResponseChunk>()
        if (includeProviderUsage) {
            chunks += anthropicMessageStart(
                inputTokens = inputTokens,
                cacheCreationInputTokens = cacheCreationInputTokens,
                cacheReadInputTokens = cacheReadInputTokens
            )
        }
        chunks += ContentDeltaResponseChunk(
            index = 0,
            delta = ContentBlock(
                type = ContentBlockType.DELTA,
                text = content
            )
        )
        if (includeProviderUsage) {
            chunks += anthropicMessageDelta(outputTokens, StopReason.END_TURN)
        }
        return flowOf(*chunks.toTypedArray())
    }

    private fun anthropicToolCallFlow(
        inputTokens: Int,
        cacheCreationInputTokens: Int,
        cacheReadInputTokens: Int,
        outputTokens: Int,
        includeProviderUsage: Boolean
    ): Flow<MessageResponseChunk> {
        val chunks = mutableListOf<MessageResponseChunk>()
        if (includeProviderUsage) {
            chunks += anthropicMessageStart(
                inputTokens = inputTokens,
                cacheCreationInputTokens = cacheCreationInputTokens,
                cacheReadInputTokens = cacheReadInputTokens
            )
        }
        chunks += ContentStartResponseChunk(
            index = 0,
            contentBlock = ContentBlock(
                type = ContentBlockType.TOOL_USE,
                id = "toolu_1",
                name = ToolDefinition.CurrentDateTime.name,
                input = buildJsonObject {}
            )
        )
        if (includeProviderUsage) {
            chunks += anthropicMessageDelta(outputTokens, StopReason.TOOL_USE)
        }
        return flowOf(*chunks.toTypedArray())
    }

    private fun anthropicMessageStart(
        inputTokens: Int,
        cacheCreationInputTokens: Int,
        cacheReadInputTokens: Int
    ): MessageStartResponseChunk = MessageStartResponseChunk(
        message = MessageResponse(
            id = "message_1",
            content = emptyList(),
            model = "claude-sonnet",
            usage = Usage(
                inputTokens = inputTokens,
                cacheCreationInputTokens = cacheCreationInputTokens,
                cacheReadInputTokens = cacheReadInputTokens,
                outputTokens = 0
            )
        )
    )

    private fun anthropicMessageDelta(
        outputTokens: Int,
        stopReason: StopReason
    ): MessageDeltaResponseChunk = MessageDeltaResponseChunk(
        delta = StopReasonDelta(stopReason = stopReason),
        usage = UsageDelta(outputTokens = outputTokens)
    )

    private fun googleTextFlow(
        content: String,
        usage: UsageMetadata?
    ): Flow<GenerateContentResponse> = flowOf(
        GenerateContentResponse(
            candidates = listOf(
                Candidate(
                    content = Content(
                        role = GoogleRole.MODEL,
                        parts = listOf(Part.text(content))
                    )
                )
            ),
            usageMetadata = usage
        )
    )

    private fun googleToolCallFlow(usage: UsageMetadata?): Flow<GenerateContentResponse> = flowOf(
        GenerateContentResponse(
            candidates = listOf(
                Candidate(
                    content = Content(
                        role = GoogleRole.MODEL,
                        parts = listOf(
                            Part.functionCall(
                                id = "function_1",
                                name = ToolDefinition.CurrentDateTime.name,
                                args = buildJsonObject {}
                            )
                        )
                    )
                )
            ),
            usageMetadata = usage
        )
    )

    private fun groqCompletionFlow(
        content: String,
        usage: ProviderUsage?
    ): Flow<GroqChatCompletionChunk> = flowOf(
        GroqChatCompletionChunk(
            choices = listOf(
                GroqChoice(
                    index = 0,
                    delta = GroqDelta(content = content)
                )
            )
        ),
        *usage?.let { providerUsage -> arrayOf(GroqChatCompletionChunk(usage = providerUsage)) }.orEmpty()
    )

    private fun directAnswerProtocol(): String = """{"type":"final_answer","content":"Direct answer"}"""

    private fun toolCallProtocol(): String =
        """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"current_datetime","arguments":{}}]}"""

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(): T {
        val handler = InvocationHandler { _, method, _ ->
            defaultReturnValue(method.returnType)
        }

        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
            handler
        ) as T
    }

    private fun defaultReturnValue(returnType: Class<*>): Any? = when (returnType) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Unit::class.java -> Unit
        Void.TYPE -> null
        else -> null
    }

    private class RecordingWebSearchRepository(
        private val result: Result<List<WebSearchResult>> = Result.success(emptyList())
    ) : WebSearchRepository {
        val queries = mutableListOf<String>()
        val limits = mutableListOf<Int>()

        override suspend fun search(query: String, limit: Int): Result<List<WebSearchResult>> {
            queries += query
            limits += limit
            return result
        }
    }

    private class FakeGroqAPI(
        private val chunks: Flow<GroqChatCompletionChunk> = emptyFlow(),
        private val responses: MutableList<Flow<GroqChatCompletionChunk>> = mutableListOf()
    ) : GroqAPI {
        var streamCalls = 0
        var lastRequest: GroqChatCompletionRequest? = null

        override fun streamChatCompletion(
            request: GroqChatCompletionRequest,
            timeoutSeconds: Int,
            token: String?,
            apiUrl: String
        ): Flow<GroqChatCompletionChunk> {
            streamCalls += 1
            lastRequest = request
            return if (responses.isNotEmpty()) {
                responses.removeAt(0)
            } else {
                chunks
            }
        }
    }

    private class RecordingOpenAIAPI(
        private val chatCompletionResponses: MutableList<Flow<ChatCompletionChunk>> = mutableListOf(emptyFlow()),
        private val responsesResponses: MutableList<Flow<ResponsesStreamEvent>> = mutableListOf(emptyFlow())
    ) : OpenAIAPI {
        var streamChatCompletionCalls = 0
        var streamResponsesCalls = 0
        var lastChatCompletionRequest: ChatCompletionRequest? = null
        var lastResponsesRequest: ResponsesRequest? = null
        val chatCompletionRequests = mutableListOf<ChatCompletionRequest>()
        val responsesRequests = mutableListOf<ResponsesRequest>()

        override fun setToken(token: String?) = Unit

        override fun setAPIUrl(url: String) = Unit

        override fun streamChatCompletion(request: ChatCompletionRequest, timeoutSeconds: Int): Flow<ChatCompletionChunk> {
            streamChatCompletionCalls += 1
            lastChatCompletionRequest = request
            chatCompletionRequests += request
            return if (chatCompletionResponses.isNotEmpty()) {
                chatCompletionResponses.removeAt(0)
            } else {
                emptyFlow()
            }
        }

        override fun streamResponses(request: ResponsesRequest, timeoutSeconds: Int): Flow<ResponsesStreamEvent> {
            streamResponsesCalls += 1
            lastResponsesRequest = request
            responsesRequests += request
            return if (responsesResponses.isNotEmpty()) {
                responsesResponses.removeAt(0)
            } else {
                emptyFlow()
            }
        }

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String
        ): UploadedProviderFile = UploadedProviderFile(id = "file-uploaded", mimeType = mimeType)

        override suspend fun isFileAvailable(fileId: String): Boolean = false
    }

    private class RecordingAnthropicAPI(
        private val responses: MutableList<Flow<MessageResponseChunk>> = mutableListOf(emptyFlow())
    ) : AnthropicAPI {
        var streamCalls = 0
        val requests = mutableListOf<MessageRequest>()

        override fun setToken(token: String?) = Unit

        override fun setAPIUrl(url: String) = Unit

        override fun streamChatMessage(messageRequest: MessageRequest, timeoutSeconds: Int): Flow<MessageResponseChunk> {
            streamCalls += 1
            requests += messageRequest
            return if (responses.isNotEmpty()) {
                responses.removeAt(0)
            } else {
                emptyFlow()
            }
        }

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String
        ): UploadedProviderFile = UploadedProviderFile(id = "anthropic-file", mimeType = mimeType)

        override suspend fun isFileAvailable(fileId: String): Boolean = false
    }

    private class RecordingGoogleAPI(
        private val responses: MutableList<Flow<GenerateContentResponse>> = mutableListOf(emptyFlow())
    ) : GoogleAPI {
        var streamCalls = 0
        val requests = mutableListOf<GenerateContentRequest>()

        override fun setToken(token: String?) = Unit

        override fun setAPIUrl(url: String) = Unit

        override fun streamGenerateContent(
            request: GenerateContentRequest,
            model: String,
            timeoutSeconds: Int
        ): Flow<GenerateContentResponse> {
            streamCalls += 1
            requests += request
            return if (responses.isNotEmpty()) {
                responses.removeAt(0)
            } else {
                emptyFlow()
            }
        }

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String
        ): UploadedProviderFile = UploadedProviderFile(id = "google-file", mimeType = mimeType)

        override suspend fun isFileAvailable(fileName: String): Boolean = false
    }
}
