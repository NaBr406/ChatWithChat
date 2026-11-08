package cn.nabr.chatwithchat.data.model

import cn.nabr.chatwithchat.data.ModelConstants

/** Wire format, independent of provider brand and tool calling strategy. */
enum class ApiProtocol(val displayName: String, val defaultApiUrl: String, val legacyClientType: ClientType) {
    OPENAI_CHAT_COMPLETIONS("OpenAI Chat Completions", ModelConstants.OPENAI_API_URL, ClientType.CUSTOM),
    OPENAI_RESPONSES("OpenAI Responses", ModelConstants.OPENAI_API_URL, ClientType.OPENAI),
    ANTHROPIC_MESSAGES("Anthropic Messages", ModelConstants.ANTHROPIC_API_URL, ClientType.ANTHROPIC),
    GOOGLE_GENERATIVE_AI("Google Generative AI", ModelConstants.GOOGLE_API_URL, ClientType.GOOGLE);

    companion object {
        val selectable: List<ApiProtocol> = listOf(OPENAI_CHAT_COMPLETIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES)

        fun fromLegacy(clientType: ClientType): ApiProtocol = when (clientType) {
            ClientType.OPENAI -> OPENAI_RESPONSES
            ClientType.ANTHROPIC -> ANTHROPIC_MESSAGES
            ClientType.GOOGLE -> GOOGLE_GENERATIVE_AI
            else -> OPENAI_CHAT_COMPLETIONS
        }
    }
}
