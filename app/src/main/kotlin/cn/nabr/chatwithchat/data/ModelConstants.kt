package cn.nabr.chatwithchat.data

import cn.nabr.chatwithchat.data.model.ApiType

object ModelConstants {
    // LinkedHashSet should be used to guarantee item order
    val openaiModels = linkedSetOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4")
    val anthropicModels = linkedSetOf("claude-3-5-sonnet-20240620", "claude-3-opus-20240229", "claude-3-sonnet-20240229", "claude-3-haiku-20240307")
    val googleModels = linkedSetOf("gemini-1.5-pro-latest", "gemini-1.5-flash-latest", "gemini-1.0-pro")
    val groqModels = linkedSetOf("llama-3.2-3b-preview", "llama-3.2-1b-preview", "llama-3.1-70b-versatile", "llama-3.1-8b-instant", "gemma2-9b-it")
    val ollamaModels = linkedSetOf<String>()

    const val OPENAI_API_URL = "https://api.openai.com/"
    const val ANTHROPIC_API_URL = "https://api.anthropic.com/"
    const val GOOGLE_API_URL = "https://generativelanguage.googleapis.com"
    const val GROQ_API_URL = "https://api.groq.com/openai/"
    const val OPENROUTER_API_URL = "https://openrouter.ai/api/"
    const val OLLAMA_API_URL = "http://localhost:11434"

    fun getDefaultAPIUrl(apiType: ApiType) = when (apiType) {
        ApiType.OPENAI -> OPENAI_API_URL
        ApiType.ANTHROPIC -> ANTHROPIC_API_URL
        ApiType.GOOGLE -> GOOGLE_API_URL
        ApiType.GROQ -> GROQ_API_URL
        ApiType.OLLAMA -> ""
    }

    const val ANTHROPIC_MAXIMUM_TOKEN = 4096

    const val OPENAI_PROMPT =
        "你是一位乐于助人、聪明且非常友好的助手。" +
            "你熟悉世界上的多种语言。" +
            "请准确回答我的问题。"

    const val DEFAULT_PROMPT = ""

    const val CHAT_TITLE_GENERATE_PROMPT =
        "请为这段聊天生成一个概括性标题。" +
            "输出必须使用用户和助手正在使用的语言，并且少于 50 个字符。" +
            "输出只能包含纯文本句子，不要使用项目符号、双星号或 Markdown 语法。\n" +
            "[聊天内容]\n"
}
