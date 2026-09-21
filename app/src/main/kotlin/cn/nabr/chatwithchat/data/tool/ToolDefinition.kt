package cn.nabr.chatwithchat.data.tool

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val toolProtocolJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Parameters
) {
    fun toPromptText(): String = buildString {
        appendLine("Name: $name")
        appendLine("Description: $description")
        append("Parameters: ")
        append(parameters.toSchemaJson(ToolSchemaDialect.JSON_FALLBACK))
    }

    @Serializable
    data class Parameters(
        val type: String = "object",
        val properties: Map<String, Parameter> = emptyMap(),
        val required: List<String> = emptyList(),
        val additionalProperties: Boolean = false,
        val description: String? = null
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Parameter(
        val type: String,
        val description: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val properties: Map<String, Parameter> = emptyMap(),
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val required: List<String> = emptyList(),
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val items: Parameter? = null,
        @SerialName("enum")
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val enumValues: List<String> = emptyList(),
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val additionalProperties: Boolean? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val minimum: Double? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val maximum: Double? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val minLength: Int? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val maxLength: Int? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val format: String? = null
    )

    companion object {
        val WebSearch = ToolDefinition(
            name = "web_search",
            description = "搜索公开网页中的近期新闻、变化中的事实或外部资料。将自然语言请求改写为简洁的搜索引擎 query；适合时加入实体、主题或类别、时间范围、地区或来源范围，以及官方或一手来源词。查询本地或区域事实时优先使用用户的语言。不要用它查询用户设备的本地日期、时间、时区、设备状态或应用设置。",
            parameters = Parameters(
                properties = mapOf(
                    "query" to Parameter(
                        type = "string",
                        description = "简洁、结构化的公开网页搜索 query。适合时加入具体日期或年份、规范名称、地区、类别或来源词，以及官方或一手来源词；不要只查询时钟或时间。"
                    )
                ),
                required = listOf("query")
            )
        )

        val FetchUrl = ToolDefinition(
            name = "fetch_url",
            description = "获取一个公开网页 URL，并提取其中可读的正文。",
            parameters = Parameters(
                properties = mapOf(
                    "url" to Parameter(
                        type = "string",
                        description = "要获取的 http 或 https URL。"
                    )
                ),
                required = listOf("url")
            )
        )

        val CurrentDateTime = ToolDefinition(
            name = "current_datetime",
            description = "返回设备当前的本地日期、时间、UTC 偏移和时区。回答本地日期或时间问题时使用；此工具不会访问网络。",
            parameters = Parameters()
        )

        val DeviceLocation = ToolDefinition(
            name = "device_location",
            description = "在已授予 Android 系统定位权限时，返回设备当前的纬度、经度、精度、provider 和时间戳。缺少权限时返回 tool_permission_denied，便于用户开启应用定位权限。仅当用户最新请求明确询问当前设备位置或需要基于位置的帮助时使用；此工具不会搜索网页。",
            parameters = Parameters()
        )

        val AddSchedule = ToolDefinition(
            name = "add_schedule",
            description = "向设备日历添加事件。start_time 使用 ISO-8601；可选填 end_time 或 duration_minutes。这是需要日历权限的本地外部写入；系统权限授予后，ChatWithChat 会直接添加事件，不再弹出其他应用的确认界面。",
            parameters = Parameters(
                properties = mapOf(
                    "title" to Parameter(
                        type = "string",
                        description = "日历事件标题。",
                        minLength = 1,
                        maxLength = 200
                    ),
                    "start_time" to Parameter(
                        type = "string",
                        description = "事件开始时间，使用 ISO-8601 date-time；可以时包含时区偏移。",
                        format = "date-time"
                    ),
                    "end_time" to Parameter(
                        type = "string",
                        description = "可选的事件结束时间，使用 ISO-8601 date-time。",
                        format = "date-time"
                    ),
                    "duration_minutes" to Parameter(
                        type = "integer",
                        description = "省略 end_time 时可选的持续分钟数。",
                        minimum = 1.0,
                        maximum = 1_440.0
                    ),
                    "description" to Parameter(
                        type = "string",
                        description = "可选的日历事件备注。",
                        maxLength = 2_000
                    ),
                    "location" to Parameter(
                        type = "string",
                        description = "可选的事件地点。",
                        maxLength = 500
                    ),
                    "all_day" to Parameter(
                        type = "boolean",
                        description = "是否将事件标记为全天事件。"
                    )
                ),
                required = listOf("title", "start_time")
            )
        )

        val SetAlarm = ToolDefinition(
            name = "set_alarm",
            description = "直接在设备的时钟应用中设置闹钟。提供本地 hour 和 minute，可选填 message 和每周重复的 days。ChatWithChat 使用系统闹钟能力直接创建，不打开编辑器，也不显示额外的应用确认界面。",
            parameters = Parameters(
                properties = mapOf(
                    "hour" to Parameter(
                        type = "integer",
                        description = "本地闹钟小时，范围为 0 到 23。",
                        minimum = 0.0,
                        maximum = 23.0
                    ),
                    "minute" to Parameter(
                        type = "integer",
                        description = "闹钟分钟，范围为 0 到 59。",
                        minimum = 0.0,
                        maximum = 59.0
                    ),
                    "message" to Parameter(
                        type = "string",
                        description = "可选的闹钟标签。",
                        maxLength = 200
                    ),
                    "days" to Parameter(
                        type = "array",
                        description = "可选的每周重复日期；1=Sunday，依次到 7=Saturday。",
                        items = Parameter(
                            type = "integer",
                            minimum = 1.0,
                            maximum = 7.0
                        )
                    )
                ),
                required = listOf("hour", "minute")
            )
        )

        val SearchStickers = ToolDefinition(
            name = "search_stickers",
            description = "搜索已启用的本地贴图，用来表达你此刻自然产生的反应或语气，无需用户明确要求。搜索你自己想表达的感觉，不要照搬用户的情绪标签。只返回候选；没有合适结果时最多换一种描述重试一次。",
            parameters = Parameters(
                properties = mapOf(
                    "query" to Parameter(
                        type = "string",
                        description = "你在这次回复中自己想表达的感觉，而不是用户的情绪。",
                        minLength = 1,
                        maxLength = 120
                    ),
                    "limit" to Parameter(
                        type = "integer",
                        description = "可选的最大候选返回数量。",
                        minimum = 1.0,
                        maximum = 6.0
                    )
                ),
                required = listOf("query")
            )
        )

        val SendSticker = ToolDefinition(
            name = "send_sticker",
            description = "发送一张由 search_stickers 返回的贴图，表达你自己的自然反应。选择你想表达的感觉，不要给用户贴情绪标签。一次最多发送一张。",
            parameters = Parameters(
                properties = mapOf(
                    "sticker_id" to Parameter(
                        type = "string",
                        description = "当前 search_stickers 结果中返回的准确 sticker_id。",
                        minLength = 1,
                        maxLength = 160
                    )
                ),
                required = listOf("sticker_id")
            )
        )

        val ViewImage = ToolDefinition(
            name = "view_image",
            description = "查看当前聊天中的一张图片。先使用 list_visual_assets 找到 visual_ref，再把该引用传入。默认读取折叠后的压缩视图；只有确需原始像素时使用 detail=original。不能读取任意本地路径。",
            parameters = Parameters(
                properties = mapOf(
                    "visual_ref" to Parameter(
                        type = "string",
                        description = "当前聊天 Catalog 中的稳定图片引用，例如 v17-2。",
                        minLength = 3,
                        maxLength = 64
                    ),
                    "detail" to Parameter(
                        type = "string",
                        description = "读取精度；默认 auto。compact 请求压缩视图，original 请求原图。",
                        enumValues = listOf("auto", "compact", "original")
                    )
                ),
                required = listOf("visual_ref")
            )
        )

        val ListVisualAssets = ToolDefinition(
            name = "list_visual_assets",
            description = "列出当前聊天中可查看的图片索引，包含稳定 visual_ref、轮次、文件名、尺寸以及是否已折叠。该工具不返回图片像素。",
            parameters = Parameters(
                properties = mapOf(
                    "query" to Parameter(
                        type = "string",
                        description = "可选的文件名或 visual_ref 过滤文本。",
                        maxLength = 120
                    ),
                    "limit" to Parameter(
                        type = "integer",
                        description = "可选的最大返回数量，默认 50。",
                        minimum = 1.0,
                        maximum = 200.0
                    ),
                    "include_raw" to Parameter(
                        type = "boolean",
                        description = "是否包含尚未折叠的图片，默认 true。"
                    ),
                    "include_folded" to Parameter(
                        type = "boolean",
                        description = "是否包含已经折叠的图片，默认 true。"
                    )
                )
            )
        )

        val ProposeMemoryChange = ToolDefinition(
            name = "propose_memory_change",
            description = "提交一条记忆变更提案。此工具只保存提案证据，不会直接写入记忆；提案会在回合完成后进入记忆批处理。",
            parameters = Parameters(
                properties = mapOf(
                    "intent" to Parameter(
                        type = "string",
                        description = "记忆意图：remember、update 或 forget。",
                        enumValues = listOf("remember", "update", "forget")
                    ),
                    "targetRef" to Parameter(
                        type = "string",
                        description = "当前回合注入记忆的 [Rxx] 引用；remember 时省略。",
                        minLength = 3,
                        maxLength = 3
                    ),
                    "text" to Parameter(
                        type = "string",
                        description = "用户明确要保存的单条事实；forget 时省略。",
                        maxLength = 600
                    ),
                    "reason" to Parameter(
                        type = "string",
                        description = "简短说明为何提出这条变更。",
                        maxLength = 240
                    ),
                    "evidenceStrength" to Parameter(
                        type = "string",
                        description = "证据强度；user_explicit 表示用户明确表达，agent_inferred 表示模型推断。",
                        enumValues = listOf("user_explicit", "agent_inferred")
                    )
                ),
                required = listOf("intent", "reason", "evidenceStrength")
            )
        )

        val AskUserQuestion = ToolDefinition(
            name = "ask_user_question",
            description = "当缺少会实质影响结果、且无法从上下文合理推断的关键选择时，暂停并向用户提出 1 到 4 个简短问题。仅在问题会阻塞正确执行时使用，不要用于例行确认；每个问题提供 2 到 4 个互斥或有明确含义的选项。",
            parameters = Parameters(
                properties = mapOf(
                    "questions" to Parameter(
                        type = "array",
                        description = "要向用户提出的问题列表，最多 4 个。",
                        items = Parameter(
                            type = "object",
                            properties = mapOf(
                                "header" to Parameter(
                                    type = "string",
                                    description = "简短的问题标签，最多 24 个字符。",
                                    minLength = 1,
                                    maxLength = 24
                                ),
                                "question" to Parameter(
                                    type = "string",
                                    description = "完整、具体、可以直接回答的问题。",
                                    minLength = 1,
                                    maxLength = 600
                                ),
                                "multiSelect" to Parameter(
                                    type = "boolean",
                                    description = "是否允许同时选择多个选项，默认 false。"
                                ),
                                "options" to Parameter(
                                    type = "array",
                                    description = "该问题的候选选项，必须提供 2 到 4 个。",
                                    items = Parameter(
                                        type = "object",
                                        properties = mapOf(
                                            "label" to Parameter(
                                                type = "string",
                                                description = "简洁的选项名称。",
                                                minLength = 1,
                                                maxLength = 80
                                            ),
                                            "description" to Parameter(
                                                type = "string",
                                                description = "说明该选项的含义、影响或取舍。",
                                                minLength = 1,
                                                maxLength = 240
                                            )
                                        ),
                                        required = listOf("label", "description"),
                                        additionalProperties = false
                                    )
                                )
                            ),
                            required = listOf("header", "question", "options"),
                            additionalProperties = false
                        )
                    )
                ),
                required = listOf("questions")
            )
        )

        val DiscoverTools = ToolDefinition(
            name = "discover_tools",
            description = "为当前尚不可用的能力查找已启用的按需工具。返回的工具会从下一次响应开始可用。",
            parameters = Parameters(
                properties = mapOf(
                    "query" to Parameter(
                        type = "string",
                        description = "回答最新请求所需的能力。",
                        minLength = 1,
                        maxLength = 160
                    )
                ),
                required = listOf("query")
            )
        )

        val BuiltIns = listOf(
            WebSearch,
            FetchUrl,
            CurrentDateTime,
            DeviceLocation,
            AddSchedule,
            SetAlarm,
            SearchStickers,
            SendSticker,
            ViewImage,
            ListVisualAssets,
            ProposeMemoryChange,
            AskUserQuestion
        )
    }
}
