package cn.nabr.chatwithchat.data.tool.provider

class AnthropicToolAdapter(
    private val fallbackAdapter: ToolCallingAdapter = OpenAICompatibleJsonToolAdapter()
) : ToolCallingAdapter by fallbackAdapter {
    override val name: String = "anthropic_json_fallback"
    override val supportsNativeToolCalling: Boolean = false
}
