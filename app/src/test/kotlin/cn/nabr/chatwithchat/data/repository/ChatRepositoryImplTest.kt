package cn.nabr.chatwithchat.data.repository

import android.content.ContextWrapper
import cn.nabr.chatwithchat.data.context.ContextBuilder
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.database.entity.MessageStickerRef
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.debug.PromptTraceStage
import cn.nabr.chatwithchat.data.debug.PromptTraceStore
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.dto.ProviderUsage
import cn.nabr.chatwithchat.data.dto.anthropic.common.MessageRole
import cn.nabr.chatwithchat.data.dto.anthropic.common.TextContent as AnthropicTextContent
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
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputContent
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputMessage
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
import cn.nabr.chatwithchat.data.tool.ToolDiscoveryMetadata
import cn.nabr.chatwithchat.data.tool.ToolEnablementOverrides
import cn.nabr.chatwithchat.data.tool.ToolExecutor
import cn.nabr.chatwithchat.data.tool.ToolExposure
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
    fun `sticker history stays semantic text and never enters upload or image parts`() = runBlocking {
        val expectedMarker = "[assistant sent sticker: A crying cat offering empathy]"
        val userMessage = MessageV2(
            id = 1,
            chatId = 73,
            content = "hello",
            platformType = null
        )
        val assistantMessage = MessageV2(
            id = 2,
            chatId = 73,
            content = "",
            platformType = "provider",
            stickerRefs = listOf(
                MessageStickerRef(
                    instanceId = "instance-1",
                    stickerId = "builtin.reactions.crying_cat",
                    assetKey = "sha256:sticker",
                    altText = "A crying cat offering empathy"
                )
            )
        )
        val assistantHistory = listOf(listOf(assistantMessage))

        val openAIResponses = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(responseTextFlow("ok", null))
        )
        createRepository(openAIAPI = openAIResponses).completeChat(
            userMessages = listOf(userMessage),
            assistantMessages = assistantHistory,
            platform = openAIPlatform().copy(uid = "provider")
        ).toList()
        val responsesAssistant = openAIResponses.responsesRequests
            .single()
            .input
            .filterIsInstance<ResponseInputMessage>()
            .single { input -> input.role == "assistant" }
        assertEquals(ResponseInputContent.Text(expectedMarker), responsesAssistant.content)
        assertEquals(0, openAIResponses.uploadFileCalls)

        val openAIChat = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(chatCompletionFlow("ok"))
        )
        createRepository(openAIAPI = openAIChat).completeChat(
            userMessages = listOf(userMessage),
            assistantMessages = assistantHistory,
            platform = openRouterPlatform().copy(uid = "provider")
        ).toList()
        val chatAssistant = openAIChat.chatCompletionRequests
            .single()
            .messages
            .single { message -> message.role == OpenAIRole.ASSISTANT }
        assertEquals(listOf(OpenAITextContent(text = expectedMarker)), chatAssistant.content)
        assertEquals(0, openAIChat.uploadFileCalls)

        val anthropic = RecordingAnthropicAPI(
            responses = mutableListOf(
                anthropicTextFlow(
                    content = "ok",
                    inputTokens = 0,
                    cacheCreationInputTokens = 0,
                    cacheReadInputTokens = 0,
                    outputTokens = 0,
                    includeProviderUsage = false
                )
            )
        )
        createRepository(anthropicAPI = anthropic).completeChat(
            userMessages = listOf(userMessage),
            assistantMessages = assistantHistory,
            platform = anthropicPlatform().copy(uid = "provider")
        ).toList()
        val anthropicAssistant = anthropic.requests
            .single()
            .messages
            .single { message -> message.role == MessageRole.ASSISTANT }
        assertEquals(listOf(AnthropicTextContent(text = expectedMarker)), anthropicAssistant.content)
        assertEquals(0, anthropic.uploadFileCalls)

        val google = RecordingGoogleAPI(
            responses = mutableListOf(googleTextFlow("ok", null))
        )
        createRepository(googleAPI = google).completeChat(
            userMessages = listOf(userMessage),
            assistantMessages = assistantHistory,
            platform = googlePlatform().copy(uid = "provider")
        ).toList()
        val googleAssistant = google.requests
            .single()
            .contents
            .single { content -> content.role == GoogleRole.MODEL }
        assertEquals(listOf(Part.text(expectedMarker)), googleAssistant.parts)
        assertEquals(0, google.uploadFileCalls)
    }

    @Test
    fun `sticker-only assistant payload remains sendable`() {
        val assistantMessage = MessageV2(
            content = "",
            platformType = "provider",
            stickerRefs = listOf(
                MessageStickerRef(
                    instanceId = "instance-only",
                    stickerId = "builtin.reactions.crying_cat",
                    assetKey = "a".repeat(64),
                    altText = "A crying cat"
                )
            )
        )

        assertTrue(assistantMessage.hasSendableAssistantPayload())
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
    fun `custom chat completions expose deepseek reasoning content`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                flowOf(
                    ChatCompletionChunk(
                        choices = listOf(
                            Choice(
                                index = 0,
                                delta = Delta(reasoningContent = "", reasoning = "Plan ")
                            )
                        )
                    ),
                    ChatCompletionChunk(
                        choices = listOf(
                            Choice(
                                index = 0,
                                delta = Delta(reasoningContent = "carefully", content = "Answer")
                            )
                        )
                    )
                )
            )
        )
        val repository = createRepository(openAIAPI = openAIAPI)

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform().copy(
                apiUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro"
            )
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Thinking("Plan "),
                ApiState.Thinking("carefully"),
                ApiState.Success("Answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        val request = openAIAPI.chatCompletionRequests.single()
        assertEquals("enabled", request.thinking?.type)
        assertNull(request.temperature)
        assertNull(request.topP)
    }

    @Test
    fun `official deepseek uses native tools while preserving thinking for a direct answer`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                flowOf(
                    ChatCompletionChunk(
                        choices = listOf(
                            Choice(
                                index = 0,
                                delta = Delta(
                                    reasoningContent = "Plan directly",
                                    content = "Direct answer"
                                )
                            )
                        )
                    )
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

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Answer without tools", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform().copy(
                apiUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro"
            )
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Thinking("Plan directly"),
                ApiState.Success("Direct answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        val request = openAIAPI.chatCompletionRequests.single()
        assertEquals("enabled", request.thinking?.type)
        assertNull(request.temperature)
        assertNull(request.topP)
        assertEquals(ChatCompletionToolChoice.Auto, request.toolChoice)
        assertTrue(request.systemText().contains("运行时上下文"))
        assertTrue(request.systemText().contains("除非工具定义明确允许用于自主表达"))
        val discoveryTool = request.tools.orEmpty().single { tool ->
            tool.function.name == ToolDefinition.DiscoverTools.name
        }
        assertTrue(discoveryTool.function.description.contains("按需工具"))
        assertTrue(discoveryTool.function.parameters.toString().contains("\"query\""))
        assertTrue(discoveryTool.function.parameters.toString().contains("\"type\":\"string\""))
        assertFalse(discoveryTool.function.parameters.toString().contains("\"查询\""))
        assertFalse(request.systemText().contains("Enabled tool signatures:"))
    }

    @Test
    fun `official deepseek falls back to json tools after a native tools rejection without losing reasoning`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                flowOf(
                    ChatCompletionChunk(
                        choices = listOf(
                            Choice(
                                index = 0,
                                delta = Delta(reasoningContent = "Native plan")
                            )
                        )
                    ),
                    ChatCompletionChunk(
                        error = ErrorDetail(message = "Invalid value for parameter tools")
                    )
                ),
                chatCompletionFlow(
                    content = """{"type":"final_answer","content":"Fallback answer"}""",
                    reasoningContent = "Fallback plan"
                )
            )
        )
        val stickerProviders = listOf(
            noOpToolProvider(
                definition = ToolDefinition.SearchStickers,
                discovery = ToolDiscoveryMetadata(
                    exposure = ToolExposure.Resident,
                    requiredCompanionToolNames = setOf(ToolDefinition.SendSticker.name)
                )
            ),
            noOpToolProvider(
                definition = ToolDefinition.SendSticker,
                discovery = ToolDiscoveryMetadata(
                    exposure = ToolExposure.Resident,
                    requiredCompanionToolNames = setOf(ToolDefinition.SearchStickers.name)
                )
            )
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            toolLoopOrchestrator = ToolLoopOrchestrator(
                ToolExecutor(ToolRegistry(stickerProviders))
            )
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Answer with available tools", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform().copy(
                apiUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro"
            )
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Thinking("Native plan"),
                ApiState.Thinking("Fallback plan"),
                ApiState.Success("Fallback answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(2, openAIAPI.streamChatCompletionCalls)
        val nativeRequest = openAIAPI.chatCompletionRequests[0]
        val fallbackRequest = openAIAPI.chatCompletionRequests[1]
        assertEquals(ChatCompletionToolChoice.Auto, nativeRequest.toolChoice)
        assertEquals(
            listOf(ToolDefinition.SearchStickers.name, ToolDefinition.SendSticker.name),
            nativeRequest.tools.orEmpty().map { tool -> tool.function.name }
        )
        assertTrue(nativeRequest.systemText().contains("正常但可选的回复方式"))
        val nativeSearchTool = nativeRequest.tools.orEmpty().single { tool ->
            tool.function.name == ToolDefinition.SearchStickers.name
        }
        assertTrue(nativeSearchTool.function.description.contains("你此刻自然产生的反应"))
        assertTrue(nativeSearchTool.function.parameters.toString().contains("\"query\""))
        assertFalse(nativeRequest.systemText().contains("Enabled tool signatures:"))
        assertNull(fallbackRequest.toolChoice)
        assertNull(fallbackRequest.tools)
        assertTrue(fallbackRequest.systemText().contains("Enabled tool signatures:"))
        assertTrue(fallbackRequest.systemText().contains("回答前可以调用已启用的工具"))
        assertTrue(fallbackRequest.systemText().contains("\"type\":\"final_answer\""))
        assertTrue(fallbackRequest.systemText().contains("search_stickers(query:string!, limit:integer)"))
        assertTrue(fallbackRequest.systemText().contains("send_sticker(sticker_id:string!)"))
        assertFalse(fallbackRequest.systemText().contains("\"类型\""))
    }

    @Test
    fun `official deepseek ordinary service error does not switch to json tools`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                flowOf(ChatCompletionChunk(error = ErrorDetail(message = "provider unavailable"))),
                chatCompletionFlow(content = "Direct recovery")
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
            userMessages = listOf(MessageV2(content = "Answer normally", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform().copy(
                apiUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro"
            )
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Success("Direct recovery"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(2, openAIAPI.streamChatCompletionCalls)
        val nativeRequest = openAIAPI.chatCompletionRequests[0]
        val directRequest = openAIAPI.chatCompletionRequests[1]
        assertTrue(nativeRequest.tools.orEmpty().isNotEmpty())
        assertNull(directRequest.toolChoice)
        assertNull(directRequest.tools)
        assertFalse(directRequest.systemText().contains("Enabled tool signatures:"))
    }

    @Test
    fun `official deepseek native tool error after a tool call does not switch protocols`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatToolCallFlow(
                    callId = "call_1",
                    name = ToolDefinition.CurrentDateTime.name,
                    arguments = "{}"
                ),
                flowOf(ChatCompletionChunk(error = ErrorDetail(message = "Unsupported parameter: tools")))
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
            platform = customPlatform().copy(
                apiUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro"
            )
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.ToolStarted("current_datetime", "current_datetime"),
                ApiState.ToolFinished("current_datetime", "current_datetime"),
                ApiState.Error("Unsupported parameter: tools"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(2, openAIAPI.streamChatCompletionCalls)
        assertTrue(openAIAPI.chatCompletionRequests.all { request -> request.tools.orEmpty().isNotEmpty() })
        assertTrue(
            openAIAPI.chatCompletionRequests.none { request ->
                request.systemText().contains("Enabled tool signatures:")
            }
        )
    }

    @Test
    fun `official deepseek native tool loop preserves thinking across tool rounds`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatToolCallFlow(
                    callId = "call_1",
                    name = "web_search",
                    arguments = """{"query":"current Android target SDK"}""",
                    reasoningContent = "Need current sources"
                ),
                chatCompletionFlow(
                    content = "Final searched answer",
                    reasoningContent = "Preparing final answer"
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
            platform = customPlatform().copy(
                apiUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro"
            )
        ).toList()

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Thinking("Need current sources"),
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
                ApiState.Thinking("Preparing final answer"),
                ApiState.Success("Final searched answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(2, openAIAPI.chatCompletionRequests.size)
        assertTrue(openAIAPI.chatCompletionRequests.all { request -> request.thinking?.type == "enabled" })
        assertTrue(openAIAPI.chatCompletionRequests.all { request -> request.temperature == null && request.topP == null })
        assertTrue(
            openAIAPI.chatCompletionRequests[1].messages.any { message ->
                message.reasoningContent == "Need current sources"
            }
        )
    }

    @Test
    fun `custom chat completions extract thinking tags split across chunks`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                flowOf(
                    ChatCompletionChunk(
                        choices = listOf(
                            Choice(
                                index = 0,
                                delta = Delta(content = "<thinkin")
                            )
                        )
                    ),
                    ChatCompletionChunk(
                        choices = listOf(
                            Choice(
                                index = 0,
                                delta = Delta(content = "g>Plan")
                            )
                        )
                    ),
                    ChatCompletionChunk(
                        choices = listOf(
                            Choice(
                                index = 0,
                                delta = Delta(content = "</thinking>Answer")
                            )
                        )
                    )
                )
            )
        )
        val repository = createRepository(openAIAPI = openAIAPI)

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform().copy(model = "deepseek-reasoner")
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
        assertTrue(systemText.contains("较早对话摘要"))
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
                chatCompletionFlow("<thinking>Checked sources</thinking>Final searched answer")
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
                ApiState.Thinking("Checked sources"),
                ApiState.Success("Final searched answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(listOf("current Android target SDK"), webSearchRepository.queries)
        assertEquals(3, openAIAPI.streamChatCompletionCalls)
        assertTrue(openAIAPI.chatCompletionRequests[0].systemText().contains("Enabled tool signatures:"))
        assertTrue(openAIAPI.chatCompletionRequests[1].systemText().contains("Tool scratchpad:"))
        assertTrue(openAIAPI.chatCompletionRequests[1].systemText().contains("Example Source"))
        assertTrue(openAIAPI.chatCompletionRequests[1].systemText().contains("https://example.com/source"))
        assertTrue(openAIAPI.chatCompletionRequests[2].systemText().contains("已有针对用户最新请求的工具结果"))
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
        assertFalse(openAIAPI.chatCompletionRequests.single().systemText().contains("Enabled tool signatures:"))
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
        assertTrue(toolPrompt.contains("Enabled tool signatures:"))
        assertTrue(toolPrompt.contains("discover_tools"))
        assertFalse(toolPrompt.contains("current_datetime"))
        assertFalse(toolPrompt.contains("device_location"))
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
        assertTrue(toolPrompt.contains("Enabled tool signatures:"))
        assertTrue(toolPrompt.contains(ToolDefinition.DiscoverTools.name))
        assertFalse(toolPrompt.contains(ToolDefinition.CurrentDateTime.name))
        assertFalse(toolPrompt.contains(ToolDefinition.DeviceLocation.name))
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
        assertFalse(openAIAPI.chatCompletionRequests.single().systemText().contains("Enabled tool signatures:"))
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
        assertFalse(openAIAPI.chatCompletionRequests.single().systemText().contains("Enabled tool signatures:"))
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
        assertTrue(toolPrompt.contains("Enabled tool signatures:"))
        assertTrue(toolPrompt.contains("discover_tools"))
        assertFalse(toolPrompt.contains("current_datetime"))
        assertFalse(toolPrompt.contains("device_location"))
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
        assertTrue(firstToolPrompt.contains("Enabled tool signatures:"))
        assertTrue(firstToolPrompt.contains(ToolDefinition.DiscoverTools.name))
        assertTrue(firstToolPrompt.contains("current_datetime"))
        assertFalse(firstToolPrompt.contains("device_location"))
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
        val promptTraceStore = PromptTraceStore()
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
            webSearchRepository = webSearchRepository,
            toolLoopOrchestrator = toolLoopOrchestrator(
                webSearchRepository = webSearchRepository,
                config = ToolLoopConfig(maxToolRounds = 1)
            ),
            promptTraceStore = promptTraceStore
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
            listOf(ToolDefinition.DiscoverTools.name, ToolDefinition.CurrentDateTime.name),
            openAIAPI.responsesRequests[0].tools.orEmpty().map { tool -> tool.name }
        )
        assertFalse(openAIAPI.responsesRequests[0].instructions.orEmpty().contains("web_search"))
        assertNull(openAIAPI.responsesRequests[1].tools)
        assertTrue(openAIAPI.responsesRequests[1].instructions.orEmpty().contains("不要再调用工具"))
        assertTrue(openAIAPI.responsesRequests[1].instructions.orEmpty().contains("function_call_output"))
        val chronologicalTraces = promptTraceStore.entries.value.asReversed()
        assertEquals(
            listOf(PromptTraceStage.toolRequest(1), PromptTraceStage.TOOL_FINAL_ANSWER),
            chronologicalTraces.map { trace -> trace.stage }
        )
        assertEquals(
            openAIAPI.responsesRequests.map { request -> request.instructions.orEmpty() },
            chronologicalTraces.map { trace -> trace.systemPrompt }
        )
    }

    @Test
    fun `openai native sticker tools encourage proactive self expression and forbid text imitation`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(responseTextFlow("That calls for a celebration.", null))
        )
        val stickerProviders = listOf(
            noOpToolProvider(
                definition = ToolDefinition.SearchStickers,
                discovery = ToolDiscoveryMetadata(
                    exposure = ToolExposure.Resident,
                    requiredCompanionToolNames = setOf(ToolDefinition.SendSticker.name)
                )
            ),
            noOpToolProvider(
                definition = ToolDefinition.SendSticker,
                discovery = ToolDiscoveryMetadata(
                    exposure = ToolExposure.Resident,
                    requiredCompanionToolNames = setOf(ToolDefinition.SearchStickers.name)
                )
            )
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            toolLoopOrchestrator = ToolLoopOrchestrator(
                ToolExecutor(ToolRegistry(stickerProviders))
            )
        )

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "I finally wrapped up that exhausting project.", platformType = null)),
            assistantMessages = emptyList(),
            platform = openAIPlatform()
        ).toList()

        val request = openAIAPI.responsesRequests.single()
        assertEquals(
            listOf(ToolDefinition.SearchStickers.name, ToolDefinition.SendSticker.name),
            request.tools.orEmpty().map { tool -> tool.name }
        )
        assertTrue(request.instructions.orEmpty().contains("正常但可选的回复方式"))
        assertTrue(request.instructions.orEmpty().contains("通常就用一张贴图表达"))
        assertTrue(request.instructions.orEmpty().contains("不要因为用户没提贴图就跳过"))
        assertTrue(request.instructions.orEmpty().contains("只取决于你自己想表达什么"))
        assertTrue(request.instructions.orEmpty().contains("不要每次回复都强行发送"))
        assertTrue(request.instructions.orEmpty().contains("用户明确要求时必须发送"))
        assertTrue(request.instructions.orEmpty().contains("除非工具定义明确允许用于自主表达"))
        assertFalse(request.instructions.orEmpty().contains("Use the available tools only when the latest user request needs them"))
        assertTrue(request.instructions.orEmpty().contains("只有成功调用 send_sticker 才算真正发送贴图"))
        assertTrue(request.instructions.orEmpty().contains("[assistant sent sticker: ...]"))
    }

    @Test
    fun `native web guidance does not gate proactive stickers on a web information need`() {
        val instruction = openAINativeToolInstruction(
            listOf(
                ToolDefinition.WebSearch,
                ToolDefinition.SearchStickers,
                ToolDefinition.SendSticker
            )
        )

        assertTrue(instruction.contains("只有需要当前网页信息或核查来源时才调用 web_search"))
        assertFalse(instruction.contains(ToolDefinition.FetchUrl.name))
        assertTrue(instruction.contains("除非工具定义明确允许用于自主表达"))
        assertTrue(instruction.contains("通常就用一张贴图表达"))
        assertTrue(instruction.contains("不要因为用户没提贴图就跳过"))
        assertFalse(instruction.contains("Use the available tools only when the latest user request needs current web information"))

        val noStickerInstruction = openAINativeToolInstruction(listOf(ToolDefinition.CurrentDateTime))
        assertFalse(noStickerInstruction.contains(ToolDefinition.SearchStickers.name))
        assertFalse(noStickerInstruction.contains(ToolDefinition.SendSticker.name))
    }

    @Test
    fun `openai native sticker flow directs search to send without an automatic retry`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            responsesResponses = mutableListOf(
                flowOf(
                    FunctionCallArgumentsDoneEvent(
                        itemId = "sticker_search",
                        outputIndex = 0,
                        callId = "sticker_search_1",
                        name = ToolDefinition.SearchStickers.name,
                        arguments = "{\"query\":\"sticker\"}"
                    )
                ),
                flowOf(
                    FunctionCallArgumentsDoneEvent(
                        itemId = "sticker_send",
                        outputIndex = 0,
                        callId = "sticker_send_1",
                        name = ToolDefinition.SendSticker.name,
                        arguments = "{\"sticker_id\":\"builtin.reactions.crying_cat\"}"
                    )
                ),
                responseTextFlow("Final answer without a marker", null)
            )
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            toolLoopOrchestrator = ToolLoopOrchestrator(
                ToolExecutor(
                    ToolRegistry(
                        listOf(
                            noOpToolProvider(
                                definition = ToolDefinition.SearchStickers,
                                content = "sticker_id=builtin.reactions.crying_cat",
                                metadata = mapOf("candidate_count" to "1")
                            ),
                            noOpToolProvider(
                                definition = ToolDefinition.SendSticker,
                                content = "Sticker queued for local rendering."
                            )
                        )
                    )
                )
            )
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Send a sticker", platformType = null)),
            assistantMessages = emptyList(),
            platform = openAIPlatform()
        ).toList()

        assertTrue(states.contains(ApiState.Success("Final answer without a marker")))
        assertEquals(3, openAIAPI.streamResponsesCalls)
        val sendRequest = openAIAPI.responsesRequests[1]
        assertTrue(sendRequest.instructions.orEmpty().contains("你自身反应"))
        assertTrue(sendRequest.instructions.orEmpty().contains("立即调用 send_sticker"))
        assertTrue(sendRequest.instructions.orEmpty().contains("再调用一次 search_stickers"))
        val finalRequest = openAIAPI.responsesRequests[2]
        assertTrue(finalRequest.instructions.orEmpty().contains("贴图已进入本地渲染队列"))
        assertTrue(finalRequest.instructions.orEmpty().contains("不要再调用贴图工具"))
    }

    @Test
    fun `openrouter sticker search candidates inject send sticker continuation`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(
            chatCompletionResponses = mutableListOf(
                chatToolCallFlow(
                    callId = "sticker_search_1",
                    name = ToolDefinition.SearchStickers.name,
                    arguments = """{"query":"crying cat"}"""
                ),
                chatCompletionFlow("Final sticker answer")
            )
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            toolLoopOrchestrator = ToolLoopOrchestrator(
                ToolExecutor(
                    ToolRegistry(
                        listOf(
                            noOpToolProvider(
                                definition = ToolDefinition.SearchStickers,
                                content = "sticker_id=builtin.reactions.crying_cat",
                                metadata = mapOf("candidate_count" to "1")
                            ),
                            noOpToolProvider(ToolDefinition.SendSticker)
                        )
                    )
                )
            )
        )

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "Send a sticker", platformType = null)),
            assistantMessages = emptyList(),
            platform = openRouterPlatform()
        ).toList()

        assertEquals(2, openAIAPI.chatCompletionRequests.size)
        val continuationPrompt = openAIAPI.chatCompletionRequests[1]
            .messages
            .filter { message -> message.role == OpenAIRole.SYSTEM }
            .flatMap { message -> message.content }
            .filterIsInstance<OpenAITextContent>()
            .joinToString("\n") { content -> content.text }
        assertTrue(continuationPrompt.contains("立即调用 send_sticker"))
        assertTrue(continuationPrompt.contains("你自身反应"))
    }

    @Test
    fun `anthropic sticker search candidates inject send sticker continuation`() = runBlocking {
        val anthropicAPI = RecordingAnthropicAPI(
            responses = mutableListOf(
                flowOf(
                    ContentStartResponseChunk(
                        index = 0,
                        contentBlock = ContentBlock(
                            type = ContentBlockType.TOOL_USE,
                            id = "sticker_search_1",
                            name = ToolDefinition.SearchStickers.name,
                            input = buildJsonObject {}
                        )
                    ),
                    ContentDeltaResponseChunk(
                        index = 0,
                        delta = ContentBlock(
                            type = ContentBlockType.INPUT_JSON_DELTA,
                            partialJson = """{"query":"crying cat"}"""
                        )
                    )
                ),
                flowOf(
                    ContentDeltaResponseChunk(
                        index = 0,
                        delta = ContentBlock(
                            type = ContentBlockType.DELTA,
                            text = "Final sticker answer"
                        )
                    )
                )
            )
        )
        val repository = createRepository(
            anthropicAPI = anthropicAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            toolLoopOrchestrator = ToolLoopOrchestrator(
                ToolExecutor(
                    ToolRegistry(
                        listOf(
                            noOpToolProvider(
                                definition = ToolDefinition.SearchStickers,
                                content = "sticker_id=builtin.reactions.crying_cat",
                                metadata = mapOf("candidate_count" to "1")
                            ),
                            noOpToolProvider(ToolDefinition.SendSticker)
                        )
                    )
                )
            )
        )

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "Send a sticker", platformType = null)),
            assistantMessages = emptyList(),
            platform = anthropicPlatform()
        ).toList()

        assertEquals(2, anthropicAPI.requests.size)
        val continuationPrompt = anthropicAPI.requests[1].systemPrompt.orEmpty()
        assertTrue(continuationPrompt.contains("立即调用 send_sticker"))
        assertTrue(continuationPrompt.contains("你自身反应"))
    }

    @Test
    fun `google sticker search candidates inject send sticker continuation`() = runBlocking {
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
                                            id = "sticker_search_1",
                                            name = ToolDefinition.SearchStickers.name,
                                            args = buildJsonObject {
                                                put("query", JsonPrimitive("crying cat"))
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
                                    parts = listOf(Part.text("Final sticker answer"))
                                )
                            )
                        )
                    )
                )
            )
        )
        val repository = createRepository(
            googleAPI = googleAPI,
            settingRepository = settingRepository(
                webSearchMode = WebSearchMode.Off,
                toolCallingMode = ToolCallingMode.Auto
            ),
            toolLoopOrchestrator = ToolLoopOrchestrator(
                ToolExecutor(
                    ToolRegistry(
                        listOf(
                            noOpToolProvider(
                                definition = ToolDefinition.SearchStickers,
                                content = "sticker_id=builtin.reactions.crying_cat",
                                metadata = mapOf("candidate_count" to "1")
                            ),
                            noOpToolProvider(ToolDefinition.SendSticker)
                        )
                    )
                )
            )
        )

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "Send a sticker", platformType = null)),
            assistantMessages = emptyList(),
            platform = googlePlatform()
        ).toList()

        assertEquals(2, googleAPI.requests.size)
        val continuationPrompt = googleAPI.requests[1]
            .systemInstruction
            ?.parts
            .orEmpty()
            .mapNotNull { part -> part.text }
            .joinToString("\n")
        assertTrue(continuationPrompt.contains("立即调用 send_sticker"))
        assertTrue(continuationPrompt.contains("你自身反应"))
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
                        sourceProvider(
                            definition = ToolDefinition.CurrentDateTime,
                            url = "https://example.com/first",
                            discovery = ToolDiscoveryMetadata(exposure = ToolExposure.Resident)
                        ),
                        sourceProvider(
                            definition = ToolDefinition.DeviceLocation,
                            url = "https://example.com/second",
                            discovery = ToolDiscoveryMetadata(exposure = ToolExposure.Resident)
                        )
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
                assertTrue(prompt.contains("运行时上下文"))
                assertTrue(prompt.contains("当前本地日期和时间"))
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
        assertTrue(openAIAPI.chatCompletionRequests.single().systemText().contains("已有针对用户最新请求的工具结果"))
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
                chatCompletionFlow("<thinking>Checked native sources</thinking>Final searched answer")
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
                ApiState.Thinking("Checked native sources"),
                ApiState.Success("Final searched answer"),
                ApiState.Done
            ),
            states.withoutUsageUpdates()
        )
        assertEquals(listOf("current Android target SDK"), webSearchRepository.queries)
        assertEquals(2, openAIAPI.streamChatCompletionCalls)
        assertEquals(ChatCompletionToolChoice.Auto, openAIAPI.chatCompletionRequests[0].toolChoice)
        assertTrue(openAIAPI.chatCompletionRequests[0].tools.orEmpty().any { tool -> tool.function.name == "web_search" })
        assertFalse(openAIAPI.chatCompletionRequests[0].systemText().contains("Enabled tool signatures:"))
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
        assertFalse(anthropicAPI.requests[0].systemPrompt.orEmpty().contains("Enabled tool signatures:"))
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
        assertFalse(googleAPI.requests[0].systemInstruction?.parts.orEmpty().any { part -> part.text.orEmpty().contains("Enabled tool signatures:") })
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
        assertFalse(openAIAPI.chatCompletionRequests.single().systemText().contains("Enabled tool signatures:"))
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

        harness.assertTracedProviderPrompt(
            provider = "OpenAI Responses",
            prompt = harness.openAIAPI.responsesRequests.single().instructions.orEmpty()
        )
    }

    @Test
    fun `prompt trace captures exact final instructions sent to provider`() = runBlocking {
        val harness = providerPromptHarness()
        val platform = openAIPlatform(systemPrompt = PROVIDER_BASE_SYSTEM_MARKER)

        harness.execute(platform)

        val sentPrompt = harness.openAIAPI.responsesRequests.single().instructions.orEmpty()
        val trace = harness.promptTraceStore.entries.value.single()
        assertEquals(sentPrompt, trace.systemPrompt)
        harness.assertTracedProviderPrompt(provider = "Prompt trace", prompt = trace.systemPrompt)
        assertEquals(PromptTraceStage.ANSWER_WITH_EXTRA_INSTRUCTIONS, trace.stage)
        assertEquals(PROVIDER_CHAT_ID, trace.chatId)
        assertEquals(12, trace.turnNumber)
        assertEquals(12, trace.userMessageId)
        assertEquals(platform.uid, trace.platformUid)
        assertEquals(platform.model, trace.model)
    }

    @Test
    fun `openrouter chat final request merges memory exactly once with all prompt sections`() = runBlocking {
        val harness = providerPromptHarness()
        val platform = openRouterPlatform(systemPrompt = PROVIDER_BASE_SYSTEM_MARKER)

        harness.execute(platform)

        harness.assertTracedProviderPrompt(
            provider = "OpenRouter chat completions",
            prompt = harness.openAIAPI.chatCompletionRequests.single().systemText()
        )
    }

    @Test
    fun `ollama chat final request merges memory exactly once with all prompt sections`() = runBlocking {
        val harness = providerPromptHarness()
        val platform = ollamaPlatform(systemPrompt = PROVIDER_BASE_SYSTEM_MARKER)

        harness.execute(platform)

        harness.assertTracedProviderPrompt(
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
        harness.assertTracedProviderPrompt(
            provider = "Groq",
            prompt = checkNotNull(harness.groqAPI.lastRequest).messages.systemText()
        )
    }

    @Test
    fun `anthropic final request merges memory exactly once with all prompt sections`() = runBlocking {
        val harness = providerPromptHarness()
        val platform = anthropicPlatform(systemPrompt = PROVIDER_BASE_SYSTEM_MARKER)

        harness.execute(platform)

        harness.assertTracedProviderPrompt(
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
        harness.assertTracedProviderPrompt(provider = "Google", prompt = prompt)
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
        searchDecisionService: SearchDecisionService? = null,
        promptTraceStore: PromptTraceStore = PromptTraceStore()
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
        searchDecisionService = searchDecisionService,
        promptTraceStore = promptTraceStore
    )

    private fun providerPromptHarness(): ProviderPromptHarness {
        val openAIAPI = RecordingOpenAIAPI()
        val groqAPI = FakeGroqAPI(emptyFlow())
        val anthropicAPI = RecordingAnthropicAPI()
        val googleAPI = RecordingGoogleAPI()
        val promptTraceStore = PromptTraceStore()
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
                searchDecisionService = searchDecisionService,
                promptTraceStore = promptTraceStore
            ),
            openAIAPI = openAIAPI,
            groqAPI = groqAPI,
            anthropicAPI = anthropicAPI,
            googleAPI = googleAPI,
            promptTraceStore = promptTraceStore
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
                chatId = PROVIDER_CHAT_ID,
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
                        chatId = PROVIDER_CHAT_ID,
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
        assertTrue("$provider lost runtime context", prompt.contains("运行时上下文："))
        assertTrue("$provider lost context summary", prompt.contains("较早对话摘要："))
        assertTrue("$provider lost omitted context", prompt.contains("provider-topic-1"))
        assertTrue("$provider lost tool result prompt", prompt.contains("已有针对用户最新请求的工具结果"))
        assertTrue("$provider lost search evidence", prompt.contains("https://example.com/source"))
    }

    private fun ProviderPromptHarness.assertTracedProviderPrompt(provider: String, prompt: String) {
        assertProviderPromptSections(provider = provider, prompt = prompt)
        assertEquals("$provider trace differs from the sent prompt", prompt, promptTraceStore.entries.value.single().systemPrompt)
    }

    private data class ProviderPromptHarness(
        val repository: ChatRepositoryImpl,
        val openAIAPI: RecordingOpenAIAPI,
        val groqAPI: FakeGroqAPI,
        val anthropicAPI: RecordingAnthropicAPI,
        val googleAPI: RecordingGoogleAPI,
        val promptTraceStore: PromptTraceStore
    )

    private companion object {
        const val PROVIDER_BASE_SYSTEM_MARKER = "__PROVIDER_BASE_SYSTEM_5CFA__"
        const val PROVIDER_MEMORY_MARKER = "__PROVIDER_MEMORY_EXACTLY_ONCE_7E31__"
        const val PROVIDER_CHAT_ID = 73
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
        url: String,
        discovery: ToolDiscoveryMetadata = ToolDiscoveryMetadata()
    ): ToolProvider = object : ToolProvider {
        override val definition: ToolDefinition = definition
        override val discoveryMetadata: ToolDiscoveryMetadata = discovery
        override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult = ToolResult(
            callId = call.id,
            name = call.name,
            content = "Source result",
            sources = listOf(ToolSource.PublicUrl(title = call.name, url = url))
        )
    }

    private fun noOpToolProvider(
        definition: ToolDefinition,
        content: String = "ok",
        metadata: Map<String, String> = emptyMap(),
        discovery: ToolDiscoveryMetadata = ToolDiscoveryMetadata()
    ): ToolProvider = object : ToolProvider {
        override val definition: ToolDefinition = definition
        override val discoveryMetadata: ToolDiscoveryMetadata = discovery
        override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPrivate

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult = ToolResult(
            callId = call.id,
            name = call.name,
            content = content,
            metadata = metadata
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
        usage: ProviderUsage? = null,
        reasoningContent: String? = null
    ): Flow<ChatCompletionChunk> = flowOf(
        ChatCompletionChunk(
            choices = listOf(
                Choice(
                    index = 0,
                    delta = Delta(content = content, reasoningContent = reasoningContent)
                )
            )
        ),
        *usage?.let { providerUsage -> arrayOf(ChatCompletionChunk(usage = providerUsage)) }.orEmpty()
    )

    private fun chatToolCallFlow(
        callId: String,
        name: String,
        arguments: String,
        usage: ProviderUsage? = null,
        reasoningContent: String? = null
    ): Flow<ChatCompletionChunk> = flowOf(
        ChatCompletionChunk(
            choices = listOf(
                Choice(
                    index = 0,
                    delta = Delta(
                        reasoningContent = reasoningContent,
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
        var uploadFileCalls = 0
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
        ): UploadedProviderFile {
            uploadFileCalls += 1
            return UploadedProviderFile(id = "file-uploaded", mimeType = mimeType)
        }

        override suspend fun isFileAvailable(fileId: String): Boolean = false
    }

    private class RecordingAnthropicAPI(
        private val responses: MutableList<Flow<MessageResponseChunk>> = mutableListOf(emptyFlow())
    ) : AnthropicAPI {
        var streamCalls = 0
        var uploadFileCalls = 0
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
        ): UploadedProviderFile {
            uploadFileCalls += 1
            return UploadedProviderFile(id = "anthropic-file", mimeType = mimeType)
        }

        override suspend fun isFileAvailable(fileId: String): Boolean = false
    }

    private class RecordingGoogleAPI(
        private val responses: MutableList<Flow<GenerateContentResponse>> = mutableListOf(emptyFlow())
    ) : GoogleAPI {
        var streamCalls = 0
        var uploadFileCalls = 0
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
        ): UploadedProviderFile {
            uploadFileCalls += 1
            return UploadedProviderFile(id = "google-file", mimeType = mimeType)
        }

        override suspend fun isFileAvailable(fileName: String): Boolean = false
    }
}
