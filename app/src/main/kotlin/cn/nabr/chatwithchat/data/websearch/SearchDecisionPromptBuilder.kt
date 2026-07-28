package cn.nabr.chatwithchat.data.websearch

class SearchDecisionPromptBuilder(
    private val maxMessageChars: Int = DEFAULT_MAX_MESSAGE_CHARS,
    private val maxContextChars: Int = DEFAULT_MAX_CONTEXT_CHARS
) {
    fun build(
        latestUserMessage: String,
        recentContext: String?,
        runtimeContext: String? = null
    ): String = buildString {
        appendLine("判断回答用户最新消息是否需要实时网页搜索；需要时规划高质量的搜索引擎 query。")
        appendLine("只返回以下精确结构的 JSON；不得翻译 JSON key：")
        appendLine("""{"shouldSearch":true,"queries":["query 1","query 2"],"reason":"short reason"}""")
        appendLine("queries 最多包含 $MAX_SEARCH_DECISION_QUERIES 条 query。")
        appendLine("翻译、数学、代码语法、写作帮助或无需当前外部事实即可回答的闲聊，选择 shouldSearch=false。")
        appendLine("用户明确要求搜索、浏览、在线查询、使用网页或引用当前来源时，选择 shouldSearch=true。")
        appendLine("涉及时事、变化中的事实、价格、日程、法律、商品供应、软件版本，或要求最新、当前、今天的信息时，选择 shouldSearch=true。")
        appendLine("询问本地时钟、日期、时区、设备状态或应用设置时选择 shouldSearch=false，除非用户要求查找相关公开网页来源。")
        appendLine()
        appendLine("shouldSearch=true 时的 query 规划规则：")
        appendLine("- 将用户的自然语言请求改写为搜索引擎 query，不要只复制或略微删减原消息。")
        appendLine("- 可以推断时，加入主要实体、主题或类别、时间范围，以及地区或来源范围。")
        appendLine("- 有运行时上下文时，将今天、昨天、本周、最新、当前等相对日期转换成具体日期或年份。")
        appendLine("- 删除闲聊填充词、要求解释或总结的措辞，以及对网页搜索无帮助的应用或工具措辞。")
        appendLine("- 请求宽泛或不够具体时，选择合理的默认范围而非拒绝；使用用户的语言和上下文，适合时增加一条更宽泛的补充 query。")
        appendLine("- 天气、法律、金融、健康、发布和日程等事实数据优先搜索官方、一手或当地语言来源。")
        appendLine("- 优先使用规范名称和更可能匹配来源的术语；中文用户询问国际话题时，一条中文 query 加一条英文 query 往往优于两条近似 query。")
        appendLine("- 每条 query 应简洁、具体且便于检索；除非问题句本身就是检索目标，否则避免使用问题句。")
        runtimeContext?.trim()?.takeIf { it.isNotBlank() }?.let { context ->
            appendLine()
            appendLine("运行时上下文：")
            appendLine(context.withoutRuntimeContextHeader().clip(maxContextChars))
        }
        recentContext?.trim()?.takeIf { it.isNotBlank() }?.let { context ->
            appendLine()
            appendLine("近期对话上下文：")
            appendLine(context.clip(maxContextChars))
        }
        appendLine()
        appendLine("用户最新消息：")
        appendLine(latestUserMessage.trim().clip(maxMessageChars))
    }.trim()

    private fun String.withoutRuntimeContextHeader(): String = trim()
        .removePrefix("运行时上下文：")
        .removePrefix("Runtime context:")
        .trim()

    private fun String.clip(maxChars: Int): String {
        val boundedMax = maxChars.coerceAtLeast(0)
        if (length <= boundedMax) return this
        return take(boundedMax).trimEnd()
    }

    companion object {
        private const val DEFAULT_MAX_MESSAGE_CHARS = 1_000
        private const val DEFAULT_MAX_CONTEXT_CHARS = 1_000
    }
}
