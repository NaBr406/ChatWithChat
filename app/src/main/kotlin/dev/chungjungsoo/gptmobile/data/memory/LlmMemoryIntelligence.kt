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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LlmMemoryIntelligence @Inject constructor(
    private val settingRepository: SettingRepository,
    private val openAIAPI: OpenAIAPI,
    private val anthropicAPI: AnthropicAPI,
    private val googleAPI: GoogleAPI
) : MemoryIntelligence {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    override suspend fun classifyConversation(
        request: ConversationClassificationRequest,
        preferredPlatform: PlatformV2?
    ): ConversationClassificationResult? = decodeOrNull(
        operation = "classify",
        prompt = CLASSIFICATION_PROMPT,
        userJson = json.encodeToString(request),
        preferredPlatform = preferredPlatform
    )

    override suspend fun selectMemories(
        request: MemorySelectionRequest,
        preferredPlatform: PlatformV2?
    ): MemorySelectionResult? = decodeOrNull(
        operation = "select",
        prompt = SELECTION_PROMPT,
        userJson = json.encodeToString(request),
        preferredPlatform = preferredPlatform
    )

    override suspend fun extractMemoryCandidates(
        request: MemoryExtractionRequest,
        preferredPlatform: PlatformV2?
    ): List<MemoryCandidate> = decodeOrNull<MemoryExtractionResponse>(
        operation = "extract",
        prompt = EXTRACTION_PROMPT,
        userJson = json.encodeToString(request),
        preferredPlatform = preferredPlatform
    )?.candidates.orEmpty()

    override suspend fun planMemoryUpdates(
        request: MemoryUpdatePlanningRequest,
        preferredPlatform: PlatformV2?
    ): MemoryUpdatePlan? = decodeOrNull(
        operation = "plan",
        prompt = PLANNING_PROMPT,
        userJson = json.encodeToString(request),
        preferredPlatform = preferredPlatform
    )

    override suspend fun proposeMarkdownMemoryWrites(
        request: MarkdownMemoryLearningRequest,
        preferredPlatform: PlatformV2?
    ): MarkdownMemoryLearningProposal? = decodeOrNull(
        operation = "markdown_write",
        prompt = MARKDOWN_LEARNING_PROMPT,
        userJson = json.encodeToString(request),
        preferredPlatform = preferredPlatform
    )

    private suspend inline fun <reified T> decodeOrNull(
        operation: String,
        prompt: String,
        userJson: String,
        preferredPlatform: PlatformV2?
    ): T? {
        val response = requestJson(operation, prompt, userJson, preferredPlatform) ?: return null
        return try {
            json.decodeFromString<T>(extractJsonObject(response))
        } catch (e: SerializationException) {
            runCatching { Log.w(TAG, "Memory $operation returned invalid JSON: ${response.take(LOG_RESPONSE_LIMIT)}", e) }
            null
        } catch (e: IllegalArgumentException) {
            runCatching { Log.w(TAG, "Memory $operation returned invalid JSON: ${response.take(LOG_RESPONSE_LIMIT)}", e) }
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
        val minimumSeconds = when (operation) {
            "classify", "select" -> MEMORY_FAST_OPERATION_TIMEOUT_SECONDS
            "extract", "plan", "markdown_write" -> MEMORY_DEEP_OPERATION_TIMEOUT_SECONDS
            else -> MEMORY_DEFAULT_OPERATION_TIMEOUT_SECONDS
        }
        return maxOf(timeout, minimumSeconds)
    }

    private fun memoryMaxOutputTokens(operation: String): Int = when (operation) {
        "classify", "select" -> MEMORY_FAST_OPERATION_MAX_OUTPUT_TOKENS
        "extract", "plan", "markdown_write" -> MEMORY_DEEP_OPERATION_MAX_OUTPUT_TOKENS
        else -> MEMORY_DEFAULT_OPERATION_MAX_OUTPUT_TOKENS
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
        private const val LOG_RESPONSE_LIMIT = 500
        private const val MEMORY_FAST_OPERATION_TIMEOUT_SECONDS = 60
        private const val MEMORY_DEEP_OPERATION_TIMEOUT_SECONDS = 120
        private const val MEMORY_DEFAULT_OPERATION_TIMEOUT_SECONDS = 90
        private const val MEMORY_FAST_OPERATION_MAX_OUTPUT_TOKENS = 512
        private const val MEMORY_DEEP_OPERATION_MAX_OUTPUT_TOKENS = 1200
        private const val MEMORY_DEFAULT_OPERATION_MAX_OUTPUT_TOKENS = 900

        private const val CLASSIFICATION_PROMPT = """
You classify the current chat for a long-term personal memory system.
Return only valid JSON matching this schema:
{"mode":"casual_chat|personal_update|emotional_support|advice_seeking|relationship_talk|interest_chat|creative_play|learning_light|productivity_light","intent":"venting|sharing|asking_advice|playful_chat|factual_question|light_task|self_reflection|relationship_discussion|other","memoryNeeds":["stable_profile|communication_style|interests|important_events|important_people|emotional_patterns|boundaries|life_context|recurring_themes|light_productivity_preference"],"domains":["string"],"entities":["string"],"emotionalTone":"string or null","shouldUseMemories":true,"shouldLearnMemories":true,"sensitivity":"normal|private|sensitive","confidence":0.0}
Do not use keyword rules. Make semantic judgments from the conversation.
If the user explicitly asks the assistant to remember something, or clearly states a durable preference, boundary, important event, important person, interest, or life context, set shouldLearnMemories to true.
"""

        private const val SELECTION_PROMPT = """
Select which candidate memories should guide this reply.
Return only valid JSON matching this schema:
{"selected":[{"memoryId":1,"relevance":0.0,"usage":"tone_only|implicit_context|explicit_if_natural","reason":"short reason"}]}
Use no memories if none are genuinely helpful. Do not force explicit mention.
"""

        private const val EXTRACTION_PROMPT = """
Extract stable long-term personal memory candidates from the conversation.
Return only valid JSON matching this schema:
{"candidates":[{"summary":"short stable memory summary","details":"optional details","recallText":"short prompt-ready memory text","type":"stable_profile|communication_style|interest|important_event|important_person|emotional_pattern|boundary|life_context|recurring_theme|light_productivity_preference","scope":"global|personal|domain|chat","domains":["string"],"entities":["string"],"tags":["string"],"applicableModes":["string"],"avoidModes":["string"],"importance":0.0,"confidence":0.0,"source":"explicit_user_statement|assistant_inferred|user_confirmed","sensitivity":"normal|private|sensitive","suggestedStatus":"active|pending_confirmation|resolved|archived","evidence":"short explanation, not full transcript","requiresConfirmation":true,"reason":"why this should or should not be remembered"}]}
Only extract durable preferences, boundaries, important people/events, interests, life context, and recurring themes. If unsure, return an empty list.
For explicit non-sensitive user statements, use source user_confirmed, suggestedStatus active, and requiresConfirmation false.
"""

        private const val PLANNING_PROMPT = """
Plan safe updates for long-term personal memories.
Return only valid JSON matching this schema:
{"operations":[{"action":"create|update|merge|mark_resolved|archive|ignore","targetMemoryIds":[1],"candidateIndex":0,"result":{"summary":"short stable memory summary","details":"optional details","recallText":"short prompt-ready memory text","type":"stable_profile|communication_style|interest|important_event|important_person|emotional_pattern|boundary|life_context|recurring_theme|light_productivity_preference","scope":"global|personal|domain|chat","domains":["string"],"entities":["string"],"tags":["string"],"applicableModes":["string"],"avoidModes":["string"],"importance":0.0,"confidence":0.0,"source":"explicit_user_statement|assistant_inferred|user_confirmed","sensitivity":"normal|private|sensitive","suggestedStatus":"active|pending_confirmation|resolved|archived","evidence":"short explanation, not full transcript","requiresConfirmation":true,"reason":"why this should or should not be remembered"},"reason":"short reason"}]}
Prefer ignore over unsafe or weak inferred memories. Do not create memories without enough evidence.
"""

        private const val MARKDOWN_LEARNING_PROMPT = """
Propose controlled Markdown memory writes for the completed chat turn.
Return only valid JSON matching this schema:
{"daily_notes":[{"text":"short memory note","type":"stable_profile|communication_style|project_context|interest|important_event|important_person|emotional_pattern|boundary|life_context|recurring_theme|light_productivity_preference","sensitivity":"normal|private|sensitive","source":"explicit_user_statement|assistant_inferred|user_confirmed","targetMemoryId":null,"reason":"why this should be remembered"}],"long_term_updates":[{"text":"complete replacement text for MEMORY.md","type":"stable_profile|communication_style|project_context|interest|important_event|important_person|emotional_pattern|boundary|life_context|recurring_theme|light_productivity_preference","sensitivity":"normal|private|sensitive","source":"explicit_user_statement|assistant_inferred|user_confirmed","targetMemoryId":"existing Markdown memory id or null","reason":"why this may be long-term"}]}
Only include durable preferences, boundaries, important people/events, interests, life context, project context, and recurring themes.
Do not repeat existing Markdown memories or existing Room memories listed in the request.
If a long-term update is about the same user preference, project, course, learning progress, or ongoing thread as an existing Markdown memory, set targetMemoryId to that existing Markdown memory id and rewrite the full updated memory text instead of creating a new neighboring memory.
Use targetMemoryId only for ids present in existingMarkdownMemories. Do not invent ids. Use null only for genuinely new long-term memories.
Write semantic, condensed memory notes. Do not transcribe full user messages, and do not prefix notes with "The user said:".
Daily notes may capture useful newly learned facts from this turn. Long-term updates should be concise and stable enough for MEMORY.md.
If nothing should be remembered, return empty arrays.
"""
    }
}

@Serializable
private data class MemoryExtractionResponse(
    val candidates: List<MemoryCandidate> = emptyList()
)
