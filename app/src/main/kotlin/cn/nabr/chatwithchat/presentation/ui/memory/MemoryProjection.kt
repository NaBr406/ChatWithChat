package cn.nabr.chatwithchat.presentation.ui.memory

import cn.nabr.chatwithchat.data.memory.MarkdownMemoryCodec
import cn.nabr.chatwithchat.data.memory.MarkdownMemoryEntry
import cn.nabr.chatwithchat.data.memory.MarkdownMemoryParseResult
import cn.nabr.chatwithchat.data.memory.MemoryRecallState
import cn.nabr.chatwithchat.data.memory.MemoryValidity

enum class MemoryProjectionParseStatus {
    NOT_PARSED,
    READY,
    PARTIAL,
    FAILED
}

data class MemoryProjectionEntry(
    val id: String,
    val text: String,
    val type: String,
    val source: String,
    val sensitivity: String,
    val scope: String,
    val validity: String,
    val recallState: String,
    val supersededBy: String?,
    val lastObservedAt: Long,
    val updatedAt: Long
)

data class MemoryProjectionSection(
    val type: String,
    val entries: List<MemoryProjectionEntry>
)

data class MemoryProjection(
    val rawMarkdown: String,
    val sections: List<MemoryProjectionSection> = emptyList(),
    val entries: List<MemoryProjectionEntry> = emptyList(),
    val historyEntries: List<MemoryProjectionEntry> = emptyList(),
    val hiddenHistoryCount: Int = historyEntries.size,
    val parseStatus: MemoryProjectionParseStatus = MemoryProjectionParseStatus.READY,
    val hasUnclassifiedContent: Boolean = false
)

internal object MemoryProjectionMapper {

    const val OTHER_TYPE = "other"

    val CATEGORY_ORDER: List<String> = listOf(
        "stable_profile",
        "communication_style",
        "boundary",
        "project_context",
        "interest",
        "important_event",
        "important_person",
        "emotional_pattern",
        "life_context",
        "recurring_theme",
        "light_productivity_preference",
        OTHER_TYPE
    )

    private val KNOWN_TYPES = CATEGORY_ORDER.toSet() - OTHER_TYPE
    private val ACTIVE_RECALL_STATES = setOf(MemoryRecallState.CORE, MemoryRecallState.QUERY)

    fun project(
        markdown: String,
        codec: MarkdownMemoryCodec = MarkdownMemoryCodec()
    ): MemoryProjection = projectUsingParser(markdown) { value -> codec.parse(value) }

    internal fun projectUsingParser(
        markdown: String,
        parser: (String) -> MarkdownMemoryParseResult
    ): MemoryProjection = try {
        projectParsed(markdown, parser(markdown))
    } catch (_: Throwable) {
        MemoryProjection(
            rawMarkdown = markdown,
            parseStatus = MemoryProjectionParseStatus.FAILED,
            hasUnclassifiedContent = markdown.isNotBlank()
        )
    }

    internal fun projectParsed(
        markdown: String,
        parsed: MarkdownMemoryParseResult
    ): MemoryProjection {
        val recoverableEntries = parsed.skippedEntries.mapNotNull { skipped -> skipped.recoverableEntry }
        val displayableEntries = (parsed.entries + recoverableEntries).distinctBy(MarkdownMemoryEntry::id)
        val activeEntries = displayableEntries
            .filter { entry -> entry.isActiveForProjection() }
            .sortedWith(memoryEntryComparator())
            .map { entry -> entry.toProjectionEntry() }
        val historyEntries = displayableEntries
            .filterNot { entry -> entry.isActiveForProjection() }
            .sortedWith(memoryEntryComparator())
            .map { entry -> entry.toProjectionEntry() }
        val sections = activeEntries
            .groupBy { entry -> entry.type }
            .let { grouped ->
                CATEGORY_ORDER.mapNotNull { type ->
                    grouped[type]?.let { entries ->
                        MemoryProjectionSection(type = type, entries = entries)
                    }
                }
            }
        val hasUnclassifiedContent = parsed.skippedEntries.isNotEmpty() || containsHandwrittenContent(markdown)

        return MemoryProjection(
            rawMarkdown = markdown,
            sections = sections,
            entries = activeEntries,
            historyEntries = historyEntries,
            hiddenHistoryCount = historyEntries.size,
            parseStatus = if (hasUnclassifiedContent) {
                MemoryProjectionParseStatus.PARTIAL
            } else {
                MemoryProjectionParseStatus.READY
            },
            hasUnclassifiedContent = hasUnclassifiedContent
        )
    }

    private fun MarkdownMemoryEntry.isActiveForProjection(): Boolean =
        validity == MemoryValidity.CURRENT && recallState in ACTIVE_RECALL_STATES

    private fun MarkdownMemoryEntry.toProjectionEntry(): MemoryProjectionEntry = MemoryProjectionEntry(
        id = id,
        text = text,
        type = type.takeIf(KNOWN_TYPES::contains) ?: OTHER_TYPE,
        source = source,
        sensitivity = sensitivity,
        scope = scope,
        validity = validity,
        recallState = recallState,
        supersededBy = supersededBy,
        lastObservedAt = lastObservedAt,
        updatedAt = updatedAt
    )

    private fun memoryEntryComparator(): Comparator<MarkdownMemoryEntry> =
        compareByDescending<MarkdownMemoryEntry> { entry ->
            entry.lastObservedAt.takeIf { observedAt -> observedAt > 0L } ?: entry.updatedAt
        }
            .thenByDescending(MarkdownMemoryEntry::updatedAt)
            .thenBy(MarkdownMemoryEntry::id)

    private fun containsHandwrittenContent(markdown: String): Boolean {
        var awaitingEntryBullet = false
        var insideEntryBody = false

        markdown.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) return@forEach

            if (trimmed.startsWith("<!-- memory:")) {
                awaitingEntryBullet = true
                insideEntryBody = false
                return@forEach
            }
            if (awaitingEntryBullet && trimmed.startsWith("- ")) {
                awaitingEntryBullet = false
                insideEntryBody = true
                return@forEach
            }
            if (insideEntryBody && (line.startsWith("  ") || line.startsWith("\t"))) {
                return@forEach
            }

            awaitingEntryBullet = false
            insideEntryBody = false
            if (
                trimmed.startsWith("#") ||
                trimmed == "---"
            ) {
                return@forEach
            }
            if (trimmed.startsWith("<!--")) return true
            return true
        }
        return false
    }
}
