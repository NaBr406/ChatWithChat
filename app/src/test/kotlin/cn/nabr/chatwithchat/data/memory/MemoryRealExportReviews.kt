package cn.nabr.chatwithchat.data.memory

internal object MemoryRealExportReviews {
    const val PROJECT_ID = "mem_can_bb93370d6976079aa732d242"
    const val PROJECT_TEXT = "用户维护 ChatWithChat，一个基于 GPTMobile 的 Android AI 聊天客户端，使用 Kotlin 和 Jetpack Compose，支持端侧长期记忆、参考聊天历史召回、上下文管理、MCP 工具和多模型切换。"

    fun projectReview(markdown: String): MemoryProjectReview {
        val original = MarkdownMemoryCodec().parse(markdown).entries.single { it.id == PROJECT_ID }
        return MemoryProjectReview(
            entryId = original.id,
            sourceTextHash = original.text.sha256Utf8(),
            projectText = PROJECT_TEXT
        )
    }
}
