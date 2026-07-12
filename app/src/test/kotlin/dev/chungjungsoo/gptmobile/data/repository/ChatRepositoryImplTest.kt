package dev.chungjungsoo.gptmobile.data.repository

import android.content.ContextWrapper
import dev.chungjungsoo.gptmobile.data.context.ContextBuilder
import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceMetadata
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.ToolResultContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.ToolUseContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.AnthropicToolChoice
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlock
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlockType
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentDeltaResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentStartResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.google.common.Content
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part
import dev.chungjungsoo.gptmobile.data.dto.google.common.Role as GoogleRole
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest
import dev.chungjungsoo.gptmobile.data.dto.google.request.GoogleToolConfig
import dev.chungjungsoo.gptmobile.data.dto.google.response.Candidate
import dev.chungjungsoo.gptmobile.data.dto.google.response.GenerateContentResponse
import dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqChoice
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqDelta
import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role as OpenAIRole
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent as OpenAITextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionToolChoice
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseFunctionCallInputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseFunctionCallOutputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseToolChoice
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionFunctionCallDelta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionToolCallDelta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.FunctionCallArgumentsDoneEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputTextDeltaEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ReasoningMode
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.UploadedProviderFile
import dev.chungjungsoo.gptmobile.data.tool.BuiltInTools
import dev.chungjungsoo.gptmobile.data.tool.ToolCall
import dev.chungjungsoo.gptmobile.data.tool.ToolCallingMode
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolEnablementOverrides
import dev.chungjungsoo.gptmobile.data.tool.ToolExecutor
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopConfig
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopOrchestrator
import dev.chungjungsoo.gptmobile.data.tool.ToolProvider
import dev.chungjungsoo.gptmobile.data.tool.ToolRegistry
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import dev.chungjungsoo.gptmobile.data.tool.ToolSecurityPolicy
import dev.chungjungsoo.gptmobile.data.tool.ToolSource
import dev.chungjungsoo.gptmobile.data.websearch.SearchDecisionModelClient
import dev.chungjungsoo.gptmobile.data.websearch.SearchDecisionService
import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchMode
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchResult
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                Result.success("""{"shouldSearch":true,"queries":["latest Kotlin release"],"reason":"latest requested"}""")
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

    private fun chatCompletionFlow(content: String): Flow<ChatCompletionChunk> = flowOf(
        ChatCompletionChunk(
            choices = listOf(
                Choice(
                    index = 0,
                    delta = Delta(content = content)
                )
            )
        )
    )

    private fun chatToolCallFlow(
        callId: String,
        name: String,
        arguments: String
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
        )
    )

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
        private val chunks: Flow<GroqChatCompletionChunk>
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
            return chunks
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
