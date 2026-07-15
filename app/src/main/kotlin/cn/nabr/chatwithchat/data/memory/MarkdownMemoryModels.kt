package cn.nabr.chatwithchat.data.memory

data class MarkdownMemoryEntry(
    val id: String,
    val text: String,
    val type: String,
    val sensitivity: String,
    val source: String,
    val chatId: Int? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val section: String? = null
)

data class MarkdownMemoryParseResult(
    val entries: List<MarkdownMemoryEntry>,
    val skippedEntries: List<SkippedMarkdownMemoryEntry> = emptyList(),
    val rawMarkdown: String
)

data class SkippedMarkdownMemoryEntry(
    val lineNumber: Int,
    val reason: String,
    val metadata: Map<String, String> = emptyMap()
)
