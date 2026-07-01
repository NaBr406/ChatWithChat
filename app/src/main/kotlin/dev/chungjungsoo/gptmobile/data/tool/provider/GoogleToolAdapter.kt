package dev.chungjungsoo.gptmobile.data.tool.provider

class GoogleToolAdapter(
    private val fallbackAdapter: ToolCallingAdapter = OpenAICompatibleJsonToolAdapter()
) : ToolCallingAdapter by fallbackAdapter {
    override val name: String = "google_json_fallback"
    override val supportsNativeToolCalling: Boolean = false
}
