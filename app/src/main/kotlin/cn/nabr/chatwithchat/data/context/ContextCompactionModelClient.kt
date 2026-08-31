package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.ProviderUsage
import cn.nabr.chatwithchat.data.dto.anthropic.common.MessageRole as AnthropicRole
import cn.nabr.chatwithchat.data.dto.anthropic.common.TextContent as AnthropicTextContent
import cn.nabr.chatwithchat.data.dto.anthropic.request.InputMessage
import cn.nabr.chatwithchat.data.dto.anthropic.request.MessageRequest
import cn.nabr.chatwithchat.data.dto.anthropic.response.ContentDeltaResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.ErrorResponseChunk as AnthropicErrorResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageDeltaResponseChunk
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageStartResponseChunk
import cn.nabr.chatwithchat.data.dto.google.common.Content
import cn.nabr.chatwithchat.data.dto.google.common.Part
import cn.nabr.chatwithchat.data.dto.google.common.Role as GoogleRole
import cn.nabr.chatwithchat.data.dto.google.request.GenerateContentRequest
import cn.nabr.chatwithchat.data.dto.google.request.GenerationConfig
import cn.nabr.chatwithchat.data.dto.groq.request.GroqChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.common.Role as OpenAIRole
import cn.nabr.chatwithchat.data.dto.openai.common.TextContent as OpenAITextContent
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionStreamOptions
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionThinkingConfig
import cn.nabr.chatwithchat.data.dto.openai.request.ChatMessage
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputContent
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputMessage
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.dto.openai.response.OutputTextDeltaEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseCompletedEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseErrorEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseFailedEvent
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.GroqAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.network.ProviderEndpoint
import cn.nabr.chatwithchat.data.network.providerEndpoint
import cn.nabr.chatwithchat.data.repository.usesOfficialDeepSeekApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

data class ContextCompactionModelResponse(
    val content: String,
    val usage: ProviderUsage? = null
)

/**
 * The network seam for one-shot auxiliary model calls: one system prompt, one user payload, a token
 * cap, no tool plumbing. Compaction is its original caller; chat-title generation
 * ([cn.nabr.chatwithchat.data.title.ChatTitleGenerationService]) rides the same seam rather than
 * carrying a second copy of the five-provider fan-out.
 *
 * A `fun interface` rather than a direct dependency on the four API clients so the services above it
 * can be unit-tested without a network stack, and so the "compaction never enters the tool loop"
 * rule is structural instead of a comment: there is simply no tool plumbing on this path.
 */
fun interface ContextCompactionModelClient {
    suspend fun requestCompaction(
        platform: PlatformV2,
        systemPrompt: String,
        userPayload: String,
        maxOutputTokens: Int,
        timeoutSeconds: Int
    ): Result<ContextCompactionModelResponse>
}

/**
 * Real implementation over the four provider clients.
 *
 * Three deliberate choices:
 *
 * Errors are raised as exceptions inside `runCatching` rather than returned, so a provider error
 * event and a transport failure reach the caller through one path and neither can be mistaken for a
 * successful empty summary.
 *
 * `stream = true` everywhere. Only OpenAI exposes a non-streaming endpoint, and it is compaction
 * specific; streaming-then-accumulating is what the rest of the app does and keeps one code path.
 *
 * This used to hold a mutex. `OpenAIAPI`, `AnthropicAPI` and `GoogleAPI` carried mutable token/URL
 * fields, so two concurrent folds on different providers could interleave `setToken` and send one
 * platform's payload to another's host. The lock kept this file's own calls from colliding but could
 * not cover the chat path, which wrote the same fields without taking it. Credentials now travel per
 * call as a [ProviderEndpoint], so there is nothing left to serialise and folds on different
 * providers may run concurrently again.
 */
class ProviderContextCompactionModelClient(
    private val openAIAPI: OpenAIAPI,
    private val groqAPI: GroqAPI,
    private val anthropicAPI: AnthropicAPI,
    private val googleAPI: GoogleAPI
) : ContextCompactionModelClient {

    override suspend fun requestCompaction(
        platform: PlatformV2,
        systemPrompt: String,
        userPayload: String,
        maxOutputTokens: Int,
        timeoutSeconds: Int
    ): Result<ContextCompactionModelResponse> = try {
        Result.success(
            when (platform.compatibleType) {
                ClientType.OPENAI ->
                    openAIResponses(platform, systemPrompt, userPayload, maxOutputTokens, timeoutSeconds)

                ClientType.GROQ ->
                    groq(platform, systemPrompt, userPayload, maxOutputTokens, timeoutSeconds)

                ClientType.OLLAMA,
                ClientType.OPENROUTER,
                ClientType.CUSTOM,
                ClientType.GLM ->
                    openAICompatible(platform, systemPrompt, userPayload, maxOutputTokens, timeoutSeconds)

                ClientType.ANTHROPIC ->
                    anthropic(platform, systemPrompt, userPayload, maxOutputTokens, timeoutSeconds)

                ClientType.GOOGLE ->
                    google(platform, systemPrompt, userPayload, maxOutputTokens, timeoutSeconds)
            }
        )
    } catch (cancellation: CancellationException) {
        // Explicitly not folded into the failure branch. `runCatching` would swallow this and report
        // a cancelled scope — or the caller's own timeout — as an ordinary provider failure, which
        // breaks structured concurrency and hides the real reason the fold stopped.
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    /**
     * Reached only by an official OpenAI model that *failed* [NativeCompactionModelGate] — an
     * unknown, mistyped or retired model name. A gated-in model folds server-side and must never see
     * this prompt; `context_management` is therefore deliberately absent from this request.
     */
    private suspend fun openAIResponses(
        platform: PlatformV2,
        systemPrompt: String,
        userPayload: String,
        maxOutputTokens: Int,
        timeoutSeconds: Int
    ): ContextCompactionModelResponse {
        val output = StringBuilder()
        var usage: ProviderUsage? = null
        openAIAPI.streamResponses(
            request = ResponsesRequest(
                model = platform.model,
                input = listOf(
                    ResponseInputMessage(
                        role = "user",
                        content = ResponseInputContent.text(userPayload)
                    )
                ),
                stream = true,
                instructions = systemPrompt,
                maxOutputTokens = maxOutputTokens
            ),
            timeoutSeconds = timeoutSeconds,
            endpoint = platform.providerEndpoint()
        ).collect { event ->
            when (event) {
                is OutputTextDeltaEvent -> output.append(event.delta)
                is ResponseCompletedEvent -> usage = event.response.usage ?: usage
                is ResponseFailedEvent ->
                    throw IllegalStateException(event.response.error?.message ?: "context_compaction_failed")
                is ResponseErrorEvent -> throw IllegalStateException(event.message)
                else -> Unit
            }
        }
        return ContextCompactionModelResponse(output.toString(), usage)
    }

    private suspend fun openAICompatible(
        platform: PlatformV2,
        systemPrompt: String,
        userPayload: String,
        maxOutputTokens: Int,
        timeoutSeconds: Int
    ): ContextCompactionModelResponse {
        val output = StringBuilder()
        var usage: ProviderUsage? = null
        // The official DeepSeek API rejects temperature/top_p when a thinking config is present, so
        // the same pairing the chat path uses is mirrored here rather than re-derived.
        val thinking = ChatCompletionThinkingConfig(type = "disabled")
            .takeIf { platform.usesOfficialDeepSeekApi() }
        openAIAPI.streamChatCompletion(
            request = ChatCompletionRequest(
                model = platform.model,
                messages = chatMessages(systemPrompt, userPayload),
                stream = true,
                temperature = 0f.takeIf { thinking == null },
                topP = 1f.takeIf { thinking == null },
                maxTokens = maxOutputTokens,
                thinking = thinking,
                streamOptions = ChatCompletionStreamOptions()
                    .takeIf { platform.compatibleType == ClientType.OPENROUTER }
            ),
            timeoutSeconds = timeoutSeconds,
            endpoint = platform.providerEndpoint()
        ).collect { chunk ->
            chunk.error?.let { error -> throw IllegalStateException(error.message) }
            chunk.choices?.firstOrNull()?.delta?.content?.let { output.append(it) }
            usage = chunk.usage ?: usage
        }
        return ContextCompactionModelResponse(output.toString(), usage)
    }

    private suspend fun groq(
        platform: PlatformV2,
        systemPrompt: String,
        userPayload: String,
        maxOutputTokens: Int,
        timeoutSeconds: Int
    ): ContextCompactionModelResponse {
        val output = StringBuilder()
        var usage: ProviderUsage? = null
        groqAPI.streamChatCompletion(
            request = GroqChatCompletionRequest(
                model = platform.model,
                messages = chatMessages(systemPrompt, userPayload),
                stream = true,
                temperature = 0f,
                topP = 1f,
                maxCompletionTokens = maxOutputTokens
            ),
            timeoutSeconds = timeoutSeconds,
            endpoint = platform.providerEndpoint()
        ).collect { chunk ->
            chunk.error?.let { error -> throw IllegalStateException(error.message) }
            val choice = chunk.choices?.firstOrNull()
            choice?.delta?.content?.let { output.append(it) }
            choice?.message?.content?.let { output.append(it) }
            usage = chunk.usage ?: chunk.xGroq?.usage ?: usage
        }
        return ContextCompactionModelResponse(output.toString(), usage)
    }

    private suspend fun anthropic(
        platform: PlatformV2,
        systemPrompt: String,
        userPayload: String,
        maxOutputTokens: Int,
        timeoutSeconds: Int
    ): ContextCompactionModelResponse {
        val output = StringBuilder()
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        anthropicAPI.streamChatMessage(
            messageRequest = MessageRequest(
                model = platform.model,
                messages = listOf(
                    InputMessage(
                        role = AnthropicRole.USER,
                        content = listOf(AnthropicTextContent(userPayload))
                    )
                ),
                maxTokens = maxOutputTokens,
                stream = true,
                systemPrompt = systemPrompt,
                temperature = 0f,
                topP = 1f
            ),
            timeoutSeconds = timeoutSeconds,
            endpoint = platform.providerEndpoint()
        ).collect { chunk ->
            when (chunk) {
                is ContentDeltaResponseChunk -> chunk.delta.text?.let { output.append(it) }
                is MessageStartResponseChunk -> {
                    inputTokens = chunk.message.usage.let { usage ->
                        usage.inputTokens +
                            (usage.cacheCreationInputTokens ?: 0) +
                            (usage.cacheReadInputTokens ?: 0)
                    }
                    outputTokens = chunk.message.usage.outputTokens
                }
                is MessageDeltaResponseChunk -> outputTokens = chunk.usage.outputTokens
                is AnthropicErrorResponseChunk -> throw IllegalStateException(chunk.error.message)
                else -> Unit
            }
        }

        return ContextCompactionModelResponse(
            content = output.toString(),
            usage = if (inputTokens != null || outputTokens != null) {
                ProviderUsage(inputTokens = inputTokens ?: 0, outputTokens = outputTokens ?: 0)
            } else {
                null
            }
        )
    }

    private suspend fun google(
        platform: PlatformV2,
        systemPrompt: String,
        userPayload: String,
        maxOutputTokens: Int,
        timeoutSeconds: Int
    ): ContextCompactionModelResponse {
        val output = StringBuilder()
        var usage: ProviderUsage? = null
        googleAPI.streamGenerateContent(
            request = GenerateContentRequest(
                contents = listOf(
                    Content(
                        role = GoogleRole.USER,
                        parts = listOf(Part.text(userPayload))
                    )
                ),
                generationConfig = GenerationConfig(
                    temperature = 0f,
                    topP = 1f,
                    maxOutputTokens = maxOutputTokens
                ),
                systemInstruction = Content(parts = listOf(Part.text(systemPrompt)))
            ),
            model = platform.model,
            timeoutSeconds = timeoutSeconds,
            endpoint = platform.providerEndpoint()
        ).collect { response ->
            response.error?.let { error -> throw IllegalStateException(error.message) }
            response.usageMetadata?.let { metadata ->
                usage = ProviderUsage(
                    promptTokens = metadata.promptTokenCount,
                    completionTokens = (metadata.candidatesTokenCount ?: 0) + (metadata.thoughtsTokenCount ?: 0),
                    totalTokens = metadata.totalTokenCount
                )
            }
            response.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                if (part.thought != true) {
                    part.text?.let { output.append(it) }
                }
            }
        }
        return ContextCompactionModelResponse(output.toString(), usage)
    }

    private fun chatMessages(systemPrompt: String, userPayload: String): List<ChatMessage> = listOf(
        ChatMessage(
            role = OpenAIRole.SYSTEM,
            content = listOf(OpenAITextContent(systemPrompt))
        ),
        ChatMessage(
            role = OpenAIRole.USER,
            content = listOf(OpenAITextContent(userPayload))
        )
    )
}
