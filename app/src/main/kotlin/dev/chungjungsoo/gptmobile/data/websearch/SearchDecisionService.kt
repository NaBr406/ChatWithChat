package dev.chungjungsoo.gptmobile.data.websearch

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageRole as AnthropicRole
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.TextContent as AnthropicTextContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.InputMessage
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentDeltaResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ErrorResponseChunk as AnthropicErrorResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.google.common.Content
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part
import dev.chungjungsoo.gptmobile.data.dto.google.common.Role as GoogleRole
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerationConfig
import dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role as OpenAIRole
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent as OpenAITextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputTextDeltaEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseFailedEvent
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import kotlinx.coroutines.flow.collect

class SearchDecisionService(
    private val modelClient: SearchDecisionModelClient,
    private val promptBuilder: SearchDecisionPromptBuilder = SearchDecisionPromptBuilder()
) {
    suspend fun decide(
        platform: PlatformV2,
        latestUserMessage: String,
        recentContext: String?,
        runtimeContext: String? = null
    ): SearchDecision {
        val normalizedMessage = latestUserMessage.trim()
        if (normalizedMessage.isBlank()) return SearchDecision.NoSearch

        val prompt = promptBuilder.build(
            latestUserMessage = normalizedMessage,
            recentContext = recentContext,
            runtimeContext = runtimeContext
        )
        val rawDecision = modelClient.requestDecision(platform, prompt).getOrNull() ?: return SearchDecision.NoSearch
        return SearchDecisionParser.parse(rawDecision)
    }
}

fun interface SearchDecisionModelClient {
    suspend fun requestDecision(platform: PlatformV2, prompt: String): Result<String>
}

class ProviderSearchDecisionModelClient(
    private val openAIAPI: OpenAIAPI,
    private val groqAPI: GroqAPI,
    private val anthropicAPI: AnthropicAPI,
    private val googleAPI: GoogleAPI
) : SearchDecisionModelClient {

    override suspend fun requestDecision(platform: PlatformV2, prompt: String): Result<String> = runCatching {
        when (platform.compatibleType) {
            ClientType.OPENAI -> requestOpenAIResponsesDecision(platform, prompt)
            ClientType.GROQ -> requestGroqDecision(platform, prompt)
            ClientType.OLLAMA,
            ClientType.OPENROUTER,
            ClientType.CUSTOM -> requestOpenAICompatibleDecision(platform, prompt)
            ClientType.ANTHROPIC -> requestAnthropicDecision(platform, prompt)
            ClientType.GOOGLE -> requestGoogleDecision(platform, prompt)
        }
    }

    private suspend fun requestOpenAIResponsesDecision(platform: PlatformV2, prompt: String): String {
        openAIAPI.setToken(platform.token)
        openAIAPI.setAPIUrl(platform.apiUrl)
        val output = StringBuilder()
        openAIAPI.streamResponses(
            request = ResponsesRequest(
                model = platform.model,
                input = listOf(
                    ResponseInputMessage(
                        role = "user",
                        content = ResponseInputContent.text(prompt)
                    )
                ),
                stream = true,
                instructions = DECISION_SYSTEM_PROMPT,
                maxOutputTokens = DECISION_MAX_OUTPUT_TOKENS
            ),
            timeoutSeconds = platform.timeout
        ).collect { event ->
            when (event) {
                is OutputTextDeltaEvent -> output.append(event.delta)
                is ResponseFailedEvent -> throw IllegalStateException(event.response.error?.message ?: "search_decision_failed")
                is ResponseErrorEvent -> throw IllegalStateException(event.message)
                else -> Unit
            }
        }
        return output.toString()
    }

    private suspend fun requestOpenAICompatibleDecision(platform: PlatformV2, prompt: String): String {
        openAIAPI.setToken(platform.token)
        openAIAPI.setAPIUrl(platform.apiUrl)
        val output = StringBuilder()
        openAIAPI.streamChatCompletion(
            request = ChatCompletionRequest(
                model = platform.model,
                messages = decisionChatMessages(prompt),
                stream = true,
                temperature = 0f,
                topP = 1f,
                maxTokens = DECISION_MAX_OUTPUT_TOKENS
            ),
            timeoutSeconds = platform.timeout
        ).collect { chunk ->
            chunk.error?.let { error -> throw IllegalStateException(error.message) }
            chunk.choices?.firstOrNull()?.delta?.content?.let { output.append(it) }
        }
        return output.toString()
    }

    private suspend fun requestGroqDecision(platform: PlatformV2, prompt: String): String {
        val output = StringBuilder()
        groqAPI.streamChatCompletion(
            request = GroqChatCompletionRequest(
                model = platform.model,
                messages = decisionChatMessages(prompt),
                stream = true,
                temperature = 0f,
                topP = 1f,
                maxCompletionTokens = DECISION_MAX_OUTPUT_TOKENS
            ),
            timeoutSeconds = platform.timeout,
            token = platform.token,
            apiUrl = platform.apiUrl
        ).collect { chunk ->
            chunk.error?.let { error -> throw IllegalStateException(error.message) }
            val choice = chunk.choices?.firstOrNull()
            choice?.delta?.content?.let { output.append(it) }
            choice?.message?.content?.let { output.append(it) }
        }
        return output.toString()
    }

    private suspend fun requestAnthropicDecision(platform: PlatformV2, prompt: String): String {
        anthropicAPI.setToken(platform.token)
        anthropicAPI.setAPIUrl(platform.apiUrl)
        val output = StringBuilder()
        anthropicAPI.streamChatMessage(
            messageRequest = MessageRequest(
                model = platform.model,
                messages = listOf(
                    InputMessage(
                        role = AnthropicRole.USER,
                        content = listOf(AnthropicTextContent(prompt))
                    )
                ),
                maxTokens = DECISION_MAX_OUTPUT_TOKENS,
                stream = true,
                systemPrompt = DECISION_SYSTEM_PROMPT,
                temperature = 0f,
                topP = 1f
            ),
            timeoutSeconds = platform.timeout
        ).collect { chunk ->
            when (chunk) {
                is ContentDeltaResponseChunk -> chunk.delta.text?.let { output.append(it) }
                is AnthropicErrorResponseChunk -> throw IllegalStateException(chunk.error.message)
                else -> Unit
            }
        }
        return output.toString()
    }

    private suspend fun requestGoogleDecision(platform: PlatformV2, prompt: String): String {
        googleAPI.setToken(platform.token)
        googleAPI.setAPIUrl(platform.apiUrl)
        val output = StringBuilder()
        googleAPI.streamGenerateContent(
            request = GenerateContentRequest(
                contents = listOf(
                    Content(
                        role = GoogleRole.USER,
                        parts = listOf(Part.text(prompt))
                    )
                ),
                generationConfig = GenerationConfig(
                    temperature = 0f,
                    topP = 1f,
                    maxOutputTokens = DECISION_MAX_OUTPUT_TOKENS
                ),
                systemInstruction = Content(parts = listOf(Part.text(DECISION_SYSTEM_PROMPT)))
            ),
            model = platform.model,
            timeoutSeconds = platform.timeout
        ).collect { response ->
            response.error?.let { error -> throw IllegalStateException(error.message) }
            response.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                if (part.thought != true) {
                    part.text?.let { output.append(it) }
                }
            }
        }
        return output.toString()
    }

    private fun decisionChatMessages(prompt: String): List<ChatMessage> = listOf(
        ChatMessage(
            role = OpenAIRole.SYSTEM,
            content = listOf(OpenAITextContent(DECISION_SYSTEM_PROMPT))
        ),
        ChatMessage(
            role = OpenAIRole.USER,
            content = listOf(OpenAITextContent(prompt))
        )
    )

    companion object {
        private const val DECISION_MAX_OUTPUT_TOKENS = 400
        private const val DECISION_SYSTEM_PROMPT =
            "You are a web-search planner. Decide if search is needed, then output optimized search queries. Return only the requested JSON object and no markdown."
    }
}
