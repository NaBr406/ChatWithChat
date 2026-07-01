package dev.chungjungsoo.gptmobile.data.repository

import android.content.ContextWrapper
import dev.chungjungsoo.gptmobile.data.context.ContextBuilder
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest
import dev.chungjungsoo.gptmobile.data.dto.google.response.GenerateContentResponse
import dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqChoice
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqDelta
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent as OpenAITextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ReasoningMode
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.UploadedProviderFile
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
            states
        )
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
            states
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
    fun `web search always searches latest user message`() = runBlocking {
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            settingRepository = settingRepository(WebSearchMode.Always),
            webSearchRepository = webSearchRepository
        )

        repository.completeChat(
            userMessages = listOf(
                MessageV2(content = "old question", platformType = null),
                MessageV2(content = "latest question", platformType = null)
            ),
            assistantMessages = listOf(emptyList(), emptyList()),
            platform = customPlatform()
        ).toList()

        assertEquals(listOf("latest question"), webSearchRepository.queries)
        assertEquals(listOf(5), webSearchRepository.limits)
    }

    @Test
    fun `web search prompt merges with system memory and summary prompts`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI()
        val webSearchRepository = RecordingWebSearchRepository(
            Result.success(listOf(webSearchResult()))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(WebSearchMode.Always),
            webSearchRepository = webSearchRepository
        )
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
            platform = customPlatform.copy(systemPrompt = "Base system prompt"),
            memoryPrompt = "Relevant long-term user memories:\n- Memory item"
        ).toList()

        val systemText = systemText(openAIAPI)
        assertTrue(systemText.contains("Base system prompt"))
        assertTrue(systemText.contains("Relevant long-term user memories"))
        assertTrue(systemText.contains("Earlier conversation summary"))
        assertTrue(systemText.contains("Web search results"))
        assertTrue(systemText.contains("https://example.com/source"))
    }

    @Test
    fun `web search failure does not break normal chat completion`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI()
        val webSearchRepository = RecordingWebSearchRepository(
            Result.failure(IllegalStateException("search unavailable"))
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            settingRepository = settingRepository(WebSearchMode.Always),
            webSearchRepository = webSearchRepository
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "latest question", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform(systemPrompt = "Base system prompt")
        ).toList()

        assertEquals(listOf(ApiState.Loading, ApiState.Done), states)
        assertEquals(listOf("latest question"), webSearchRepository.queries)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
        assertFalse(systemText(openAIAPI).contains("Web search results"))
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

    private fun createRepository(
        groqAPI: GroqAPI = FakeGroqAPI(emptyFlow()),
        openAIAPI: OpenAIAPI = RecordingOpenAIAPI(),
        settingRepository: SettingRepository = settingRepository(WebSearchMode.Off),
        webSearchRepository: WebSearchRepository = RecordingWebSearchRepository()
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
        anthropicAPI = FakeAnthropicAPI(),
        googleAPI = FakeGoogleAPI(),
        attachmentUploadCoordinator = AttachmentUploadCoordinator(
            openAIAPI,
            FakeAnthropicAPI(),
            FakeGoogleAPI()
        ),
        contextBuilder = ContextBuilder(),
        webSearchRepository = webSearchRepository
    )

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

    private fun systemText(openAIAPI: RecordingOpenAIAPI): String = openAIAPI.lastChatCompletionRequest
        ?.messages
        ?.firstOrNull()
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

    private fun settingRepository(webSearchMode: WebSearchMode): SettingRepository {
        val handler = InvocationHandler { _, method, _ ->
            when (method.name) {
                "fetchWebSearchMode" -> webSearchMode
                "updateWebSearchMode" -> Unit
                else -> defaultReturnValue(method.returnType)
            }
        }

        return Proxy.newProxyInstance(
            SettingRepository::class.java.classLoader,
            arrayOf(SettingRepository::class.java),
            handler
        ) as SettingRepository
    }

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

    private class RecordingOpenAIAPI : OpenAIAPI {
        var streamChatCompletionCalls = 0
        var lastChatCompletionRequest: ChatCompletionRequest? = null

        override fun setToken(token: String?) = Unit

        override fun setAPIUrl(url: String) = Unit

        override fun streamChatCompletion(request: ChatCompletionRequest, timeoutSeconds: Int): Flow<ChatCompletionChunk> {
            streamChatCompletionCalls += 1
            lastChatCompletionRequest = request
            return emptyFlow()
        }

        override fun streamResponses(request: ResponsesRequest, timeoutSeconds: Int): Flow<ResponsesStreamEvent> = emptyFlow()

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String
        ): UploadedProviderFile = UploadedProviderFile(id = "file-uploaded", mimeType = mimeType)

        override suspend fun isFileAvailable(fileId: String): Boolean = false
    }

    private class FakeAnthropicAPI : AnthropicAPI {
        override fun setToken(token: String?) = Unit

        override fun setAPIUrl(url: String) = Unit

        override fun streamChatMessage(messageRequest: MessageRequest, timeoutSeconds: Int): Flow<MessageResponseChunk> = emptyFlow()

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String
        ): UploadedProviderFile = UploadedProviderFile(id = "anthropic-file", mimeType = mimeType)

        override suspend fun isFileAvailable(fileId: String): Boolean = false
    }

    private class FakeGoogleAPI : GoogleAPI {
        override fun setToken(token: String?) = Unit

        override fun setAPIUrl(url: String) = Unit

        override fun streamGenerateContent(
            request: GenerateContentRequest,
            model: String,
            timeoutSeconds: Int
        ): Flow<GenerateContentResponse> = emptyFlow()

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String
        ): UploadedProviderFile = UploadedProviderFile(id = "google-file", mimeType = mimeType)

        override suspend fun isFileAvailable(fileName: String): Boolean = false
    }
}
