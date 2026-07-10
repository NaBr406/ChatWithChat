package dev.chungjungsoo.gptmobile.data.memory

import android.util.Log
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageRole as AnthropicMessageRole
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.TextContent as AnthropicTextContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.InputMessage as AnthropicInputMessage
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest as AnthropicMessageRequest
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlockType
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentDeltaResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ErrorResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.google.common.Content as GoogleContent
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part as GooglePart
import dev.chungjungsoo.gptmobile.data.dto.google.common.Role as GoogleRole
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerationConfig
import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ReasoningConfig
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputTextDeltaEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputTextDoneEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseFailedEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LlmMemoryIntelligence @Inject constructor(
    private val settingRepository: SettingRepository,
    private val openAIAPI: OpenAIAPI,
    private val anthropicAPI: AnthropicAPI,
    private val googleAPI: GoogleAPI
) : MemoryIntelligence {

    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
    }

    override suspend fun consolidateMemoryBatch(
        request: MemoryBatchConsolidationRequest,
        preferredPlatform: PlatformV2?
    ): MemoryBatchConsolidationProposal? {
        val response = requestJson(
            operation = "consolidate_batch",
            systemPrompt = BATCH_CONSOLIDATION_PROMPT,
            userJson = strictJson.encodeToString(request),
            preferredPlatform = preferredPlatform
        ) ?: return null
        return try {
            strictJson.decodeFromString<MemoryBatchConsolidationProposal>(extractJsonObject(response))
        } catch (e: SerializationException) {
            runCatching { Log.w(TAG, "Memory consolidate_batch returned invalid JSON", e) }
            null
        } catch (e: IllegalArgumentException) {
            runCatching { Log.w(TAG, "Memory consolidate_batch returned invalid JSON", e) }
            null
        }
    }

    private suspend fun requestJson(
        operation: String,
        systemPrompt: String,
        userJson: String,
        preferredPlatform: PlatformV2?
    ): String? {
        val platform = resolveMemoryPlatform(preferredPlatform)
        if (platform == null) {
            Log.w(TAG, "Memory $operation skipped: no enabled OpenAI-compatible memory platform")
            return null
        }
        if (platform.model.isBlank()) {
            Log.w(TAG, "Memory $operation skipped: selected platform ${platform.name} has no model")
            return null
        }
        if (platform.requiresToken() && platform.token.isNullOrBlank()) {
            Log.w(TAG, "Memory $operation skipped: selected platform ${platform.name} has no token")
            return null
        }

        openAIAPI.setToken(platform.token)
        openAIAPI.setAPIUrl(platform.apiUrl)

        return when (platform.compatibleType) {
            ClientType.OPENAI -> requestResponsesJson(operation, platform, systemPrompt, userJson)
            ClientType.ANTHROPIC -> requestAnthropicJson(operation, platform, systemPrompt, userJson)
            ClientType.GOOGLE -> requestGoogleJson(operation, platform, systemPrompt, userJson)
            ClientType.GROQ, ClientType.OPENROUTER, ClientType.OLLAMA, ClientType.CUSTOM -> requestChatCompletionsJson(
                operation,
                platform,
                systemPrompt,
                userJson
            )
        }
    }

    private suspend fun requestChatCompletionsJson(
        operation: String,
        platform: PlatformV2,
        systemPrompt: String,
        userJson: String
    ): String? {
        val timeoutSeconds = platform.memoryTimeoutSeconds(operation)
        val startedAt = System.currentTimeMillis()
        logRequestStart(operation, platform, timeoutSeconds)
        val chunks = runCatching {
            openAIAPI.streamChatCompletion(
                request = ChatCompletionRequest(
                    model = platform.model,
                    messages = listOf(
                        ChatMessage(
                            role = Role.SYSTEM,
                            content = listOf(TextContent(systemPrompt))
                        ),
                        ChatMessage(
                            role = Role.USER,
                            content = listOf(TextContent(userJson))
                        )
                    ),
                    stream = true,
                    temperature = if (platform.reasoning) null else 0f,
                    topP = if (platform.reasoning) null else 1f,
                    maxTokens = if (platform.reasoning) null else memoryMaxOutputTokens(operation),
                    maxCompletionTokens = if (platform.reasoning) memoryMaxOutputTokens(operation) else null,
                    reasoningEffort = if (platform.reasoning) "low" else null
                ),
                timeoutSeconds = timeoutSeconds
            ).toList()
        }.onSuccess {
            logRequestSuccess(operation, platform, timeoutSeconds, startedAt)
        }.onFailure { throwable ->
            logRequestFailure(operation, platform, timeoutSeconds, startedAt, throwable)
        }.getOrNull() ?: return null

        chunks.firstNotNullOfOrNull { it.error }?.let { error ->
            Log.w(TAG, "Memory $operation request returned ${error.type ?: "error"}: ${error.message}")
            return null
        }

        return collectContent(chunks).takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "Memory $operation request returned blank content from ${platform.name}")
                null
            }
    }

    private suspend fun requestResponsesJson(
        operation: String,
        platform: PlatformV2,
        systemPrompt: String,
        userJson: String
    ): String? {
        val timeoutSeconds = platform.memoryTimeoutSeconds(operation)
        val startedAt = System.currentTimeMillis()
        logRequestStart(operation, platform, timeoutSeconds)
        val events = runCatching {
            openAIAPI.streamResponses(
                request = ResponsesRequest(
                    model = platform.model,
                    input = listOf(
                        ResponseInputMessage(
                            role = "user",
                            content = ResponseInputContent.text(userJson)
                        )
                    ),
                    stream = true,
                    instructions = systemPrompt,
                    maxOutputTokens = memoryMaxOutputTokens(operation),
                    temperature = if (platform.reasoning) null else 0f,
                    topP = if (platform.reasoning) null else 1f,
                    reasoning = if (platform.reasoning) {
                        ReasoningConfig(effort = "low", summary = null)
                    } else {
                        null
                    }
                ),
                timeoutSeconds = timeoutSeconds
            ).toList()
        }.onSuccess {
            logRequestSuccess(operation, platform, timeoutSeconds, startedAt)
        }.onFailure { throwable ->
            logRequestFailure(operation, platform, timeoutSeconds, startedAt, throwable)
        }.getOrNull() ?: return null

        events.firstMemoryErrorOrNull()?.let { error ->
            Log.w(TAG, "Memory $operation Responses request returned error: $error")
            return null
        }

        return collectResponsesContent(events).takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "Memory $operation Responses request returned blank content from ${platform.name}")
                null
            }
    }

    private suspend fun requestAnthropicJson(
        operation: String,
        platform: PlatformV2,
        systemPrompt: String,
        userJson: String
    ): String? {
        val timeoutSeconds = platform.memoryTimeoutSeconds(operation)
        val startedAt = System.currentTimeMillis()
        logRequestStart(operation, platform, timeoutSeconds)
        val chunks = runCatching {
            anthropicAPI.setToken(platform.token)
            anthropicAPI.setAPIUrl(platform.apiUrl)
            anthropicAPI.streamChatMessage(
                messageRequest = AnthropicMessageRequest(
                    model = platform.model,
                    messages = listOf(
                        AnthropicInputMessage(
                            role = AnthropicMessageRole.USER,
                            content = listOf(AnthropicTextContent(userJson))
                        )
                    ),
                    maxTokens = memoryMaxOutputTokens(operation),
                    stream = true,
                    systemPrompt = systemPrompt,
                    temperature = 0f,
                    topP = 1f,
                    thinking = null
                ),
                timeoutSeconds = timeoutSeconds
            ).toList()
        }.onSuccess {
            logRequestSuccess(operation, platform, timeoutSeconds, startedAt)
        }.onFailure { throwable ->
            logRequestFailure(operation, platform, timeoutSeconds, startedAt, throwable)
        }.getOrNull() ?: return null

        chunks.filterIsInstance<ErrorResponseChunk>().firstOrNull()?.let { error ->
            Log.w(TAG, "Memory $operation Anthropic request returned ${error.error.type}: ${error.error.message}")
            return null
        }

        return chunks
            .filterIsInstance<ContentDeltaResponseChunk>()
            .mapNotNull { chunk ->
                when (chunk.delta.type) {
                    ContentBlockType.TEXT, ContentBlockType.DELTA -> chunk.delta.text
                    else -> null
                }
            }
            .joinToString("")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "Memory $operation Anthropic request returned blank content from ${platform.name}")
                null
            }
    }

    private suspend fun requestGoogleJson(
        operation: String,
        platform: PlatformV2,
        systemPrompt: String,
        userJson: String
    ): String? {
        val timeoutSeconds = platform.memoryTimeoutSeconds(operation)
        val startedAt = System.currentTimeMillis()
        logRequestStart(operation, platform, timeoutSeconds)
        val responses = runCatching {
            googleAPI.setToken(platform.token)
            googleAPI.setAPIUrl(platform.apiUrl)
            googleAPI.streamGenerateContent(
                request = GenerateContentRequest(
                    contents = listOf(
                        GoogleContent(
                            role = GoogleRole.USER,
                            parts = listOf(GooglePart.text(userJson))
                        )
                    ),
                    generationConfig = GenerationConfig(
                        temperature = 0f,
                        topP = 1f,
                        maxOutputTokens = memoryMaxOutputTokens(operation),
                        thinkingConfig = null
                    ),
                    systemInstruction = GoogleContent(parts = listOf(GooglePart.text(systemPrompt)))
                ),
                model = platform.model,
                timeoutSeconds = timeoutSeconds
            ).toList()
        }.onSuccess {
            logRequestSuccess(operation, platform, timeoutSeconds, startedAt)
        }.onFailure { throwable ->
            logRequestFailure(operation, platform, timeoutSeconds, startedAt, throwable)
        }.getOrNull() ?: return null

        responses.firstNotNullOfOrNull { it.error }?.let { error ->
            Log.w(TAG, "Memory $operation Google request returned ${error.status ?: error.code}: ${error.message}")
            return null
        }

        val text = responses
            .flatMap { response -> response.candidates.orEmpty() }
            .flatMap { candidate -> candidate.content.parts }
            .mapNotNull { part ->
                if (part.thought == true) {
                    null
                } else {
                    part.text
                }
            }
            .joinToString("")
            .trim()

        return text.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "Memory $operation Google request returned blank content from ${platform.name}")
                null
            }
    }

    private suspend fun resolveMemoryPlatform(preferredPlatform: PlatformV2?): PlatformV2? {
        if (preferredPlatform?.isSupportedMemoryPlatform() == true) {
            return preferredPlatform
        }

        return settingRepository.fetchPlatformV2s()
            .firstOrNull { platform -> platform.isSupportedMemoryPlatform() }
    }

    private fun collectContent(chunks: List<ChatCompletionChunk>): String = chunks
        .mapNotNull { chunk -> chunk.choices?.firstOrNull()?.delta?.content }
        .joinToString("")
        .trim()

    private fun collectResponsesContent(events: List<ResponsesStreamEvent>): String {
        val deltaText = events
            .filterIsInstance<OutputTextDeltaEvent>()
            .joinToString("") { it.delta }
            .trim()
        if (deltaText.isNotBlank()) return deltaText

        return events
            .filterIsInstance<OutputTextDoneEvent>()
            .joinToString("") { it.text }
            .trim()
    }

    private fun List<ResponsesStreamEvent>.firstMemoryErrorOrNull(): String? = firstNotNullOfOrNull { event ->
        when (event) {
            is ResponseErrorEvent -> event.message
            is ResponseFailedEvent -> event.response.error?.message ?: event.response.status
            else -> null
        }
    }

    private fun extractJsonObject(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) {
            throw IllegalArgumentException("No JSON object found")
        }
        return trimmed.substring(start, end + 1)
    }

    private fun PlatformV2.requiresToken(): Boolean = compatibleType != ClientType.OLLAMA

    private fun PlatformV2.memoryTimeoutSeconds(operation: String): Int {
        if (timeout == 0) return 0
        check(operation == "consolidate_batch")
        return maxOf(timeout, MEMORY_CONSOLIDATION_TIMEOUT_SECONDS)
    }

    private fun memoryMaxOutputTokens(operation: String): Int {
        check(operation == "consolidate_batch")
        return MEMORY_CONSOLIDATION_MAX_OUTPUT_TOKENS
    }

    private fun logRequestStart(
        operation: String,
        platform: PlatformV2,
        timeoutSeconds: Int
    ) {
        runCatching {
            Log.i(TAG, "Memory $operation request starting on ${platform.name}, model=${platform.model}, timeout=${timeoutSeconds}s")
        }
    }

    private fun logRequestSuccess(
        operation: String,
        platform: PlatformV2,
        timeoutSeconds: Int,
        startedAt: Long
    ) {
        runCatching {
            Log.i(TAG, "Memory $operation request completed on ${platform.name}, timeout=${timeoutSeconds}s, elapsed=${System.currentTimeMillis() - startedAt}ms")
        }
    }

    private fun logRequestFailure(
        operation: String,
        platform: PlatformV2,
        timeoutSeconds: Int,
        startedAt: Long,
        throwable: Throwable
    ) {
        runCatching {
            Log.w(
                TAG,
                "Memory $operation request failed on ${platform.name}, timeout=${timeoutSeconds}s, elapsed=${System.currentTimeMillis() - startedAt}ms",
                throwable
            )
        }
    }

    private fun PlatformV2.isSupportedMemoryPlatform(): Boolean = enabled &&
        model.isNotBlank() &&
        compatibleType in setOf(
        ClientType.OPENAI,
        ClientType.ANTHROPIC,
        ClientType.GOOGLE,
        ClientType.GROQ,
        ClientType.OPENROUTER,
        ClientType.OLLAMA,
        ClientType.CUSTOM
    )

    companion object {
        private const val TAG = "MemoryIntelligence"
        private const val MEMORY_CONSOLIDATION_TIMEOUT_SECONDS = 120
        private const val MEMORY_CONSOLIDATION_MAX_OUTPUT_TOKENS = 1200

        private const val BATCH_CONSOLIDATION_PROMPT = """
Consolidate one immutable batch of completed chat turns into controlled personal-memory operations.
Return only strict JSON matching this schema:
{"operations":[{"destination":"daily|long_term","action":"create|replace|remove|ignore","targetMemoryId":"an id from existingMemories or null","text":"complete semantic memory text, or empty for remove/ignore","type":"stable_profile|communication_style|project_context|interest|important_event|important_person|emotional_pattern|boundary|life_context|recurring_theme|light_productivity_preference","sensitivity":"normal|private|sensitive","source":"explicit_user_statement|assistant_inferred|user_confirmed","evidenceTurnKeys":["a turnKey from turns"],"reason":"short reason"}]}
The user's messages are the source of truth. Assistant content is only context for resolving references and must not become a user fact unless the user confirmed it.
Remember durable preferences, communication style, important people or events, interests, boundaries, life context, recurring themes, and ongoing personal or project context.
For progress or corrections, replace the complete matching existing memory by its supplied id instead of creating a neighboring duplicate.
Use replace or remove only with an id present in existingMemories. Never invent ids, paths, destinations, actions, or evidence keys.
Do not copy raw transcripts, do not prefix text with "The user said:", and do not duplicate one fact into both daily and long-term destinations.
Use ignore or an empty operations list when nothing durable should be written.
"""
    }
}
