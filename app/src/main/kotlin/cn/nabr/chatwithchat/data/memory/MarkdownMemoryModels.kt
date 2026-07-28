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
    val section: String? = null,
    val canonicalKey: String? = null,
    val scope: String = MemoryScope.GENERAL,
    val lastObservedAt: Long = if (updatedAt > 0L) updatedAt else createdAt,
    val validity: String = MemoryValidity.CURRENT,
    val supersededBy: String? = null,
    val recallState: String = MemoryRecallState.QUERY,
    val evidenceRefs: List<String> = emptyList(),
    val extraMetadata: Map<String, String> = emptyMap()
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

data class MarkdownMemoryObservationUpdate(
    val entryId: String,
    val lastObservedAt: Long,
    val evidenceRefs: List<String> = emptyList()
)

internal data class MarkdownMemoryObservationResult(
    val markdown: String,
    val updatedCount: Int = 0
)

object MemoryScope {
    const val GENERAL = "general"
    const val WORK = "work"
    const val PERSONAL = "personal"
}

object MemoryValidity {
    const val CURRENT = "current"
    const val CONTESTED = "contested"
    const val OBSOLETE = "obsolete"

    val VALUES = setOf(CURRENT, CONTESTED, OBSOLETE)
}

object MemoryRecallState {
    const val CORE = "core"
    const val QUERY = "query"
    const val MAINTENANCE_ONLY = "maintenance_only"

    val VALUES = setOf(CORE, QUERY, MAINTENANCE_ONLY)
}
