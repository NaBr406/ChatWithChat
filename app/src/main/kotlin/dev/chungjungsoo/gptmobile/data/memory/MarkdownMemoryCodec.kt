package dev.chungjungsoo.gptmobile.data.memory

import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MarkdownMemoryCodec {

    fun renderLongTerm(entries: List<MarkdownMemoryEntry>): String =
        renderDocument(
            title = "ChatWithChat Memory",
            entries = entries,
            defaultSection = null
        )

    fun renderDaily(
        date: LocalDate,
        entries: List<MarkdownMemoryEntry>
    ): String = renderDocument(
        title = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
        entries = entries,
        defaultSection = DAILY_CONVERSATION_SECTION
    )

    fun renderDailyAppend(entries: List<MarkdownMemoryEntry>): String =
        renderEntryBlocks(entries, defaultSection = DAILY_CONVERSATION_SECTION)

    fun renderLongTermAppend(entries: List<MarkdownMemoryEntry>): String =
        renderEntryBlocks(entries, defaultSection = null)

    internal fun removeEntriesById(
        markdown: String,
        entryIds: Set<String>
    ): MarkdownMemoryRemovalResult {
        val targetIds = entryIds.mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()
        if (targetIds.isEmpty()) {
            return MarkdownMemoryRemovalResult(markdown = normalizeEditedMarkdown(markdown))
        }

        val lines = markdown.lines()
        val retained = BooleanArray(lines.size) { true }
        var deletedCount = 0
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (!trimmed.startsWith(MEMORY_COMMENT_PREFIX)) {
                index += 1
                continue
            }

            val metadata = parseMetadata(trimmed)
            if (metadata["id"] !in targetIds) {
                index += 1
                continue
            }

            val bulletIndex = nextMeaningfulLineIndex(lines, index + 1)
            val endExclusive = entryBlockEndExclusive(lines, index, bulletIndex)
            for (lineIndex in index until endExclusive) {
                retained[lineIndex] = false
            }
            deletedCount += 1
            index = endExclusive
        }

        val editedMarkdown = lines
            .filterIndexed { lineIndex, _ -> retained[lineIndex] }
            .joinToString("\n")
        return MarkdownMemoryRemovalResult(
            markdown = normalizeEditedMarkdown(editedMarkdown),
            deletedCount = deletedCount
        )
    }

    internal fun replaceEntriesById(
        markdown: String,
        replacements: List<MarkdownMemoryEntry>
    ): MarkdownMemoryReplacementResult {
        val replacementsById = replacements
            .mapNotNull { entry ->
                val id = entry.id.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                id to entry.copy(id = id)
            }
            .toMap()
        if (replacementsById.isEmpty()) {
            return MarkdownMemoryReplacementResult(markdown = normalizeEditedMarkdown(markdown))
        }

        val lines = markdown.lines()
        val edited = mutableListOf<String>()
        var replacedCount = 0
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (!trimmed.startsWith(MEMORY_COMMENT_PREFIX)) {
                edited += lines[index]
                index += 1
                continue
            }

            val metadata = parseMetadata(trimmed)
            val replacement = metadata["id"]?.let { replacementsById[it] }
            if (replacement == null) {
                edited += lines[index]
                index += 1
                continue
            }

            val bulletIndex = nextMeaningfulLineIndex(lines, index + 1)
            val endExclusive = entryBlockEndExclusive(lines, index, bulletIndex)
            edited += metadataComment(replacement)
            edited += renderBullet(replacement.text)
            edited += ""
            replacedCount += 1
            index = endExclusive
        }

        return MarkdownMemoryReplacementResult(
            markdown = normalizeEditedMarkdown(edited.joinToString("\n")),
            replacedCount = replacedCount
        )
    }

    fun parse(markdown: String): MarkdownMemoryParseResult {
        val entries = mutableListOf<MarkdownMemoryEntry>()
        val skippedEntries = mutableListOf<SkippedMarkdownMemoryEntry>()
        val lines = markdown.lines()
        var currentSection: String? = null
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.startsWith("## ")) {
                currentSection = trimmed.removePrefix("## ").trim().takeIf { it.isNotBlank() }
                index += 1
                continue
            }

            if (!trimmed.startsWith(MEMORY_COMMENT_PREFIX)) {
                index += 1
                continue
            }

            val lineNumber = index + 1
            val metadata = parseMetadata(trimmed)
            val bulletIndex = nextMeaningfulLineIndex(lines, index + 1)
            val text = bulletIndex
                ?.takeIf { lines[it].trimStart().startsWith("- ") }
                ?.let { parseBulletText(lines, it) }

            val entry = buildEntry(metadata, text, currentSection)
            if (entry == null) {
                skippedEntries += SkippedMarkdownMemoryEntry(
                    lineNumber = lineNumber,
                    reason = skippedReason(metadata, text),
                    metadata = metadata
                )
            } else {
                entries += entry
            }
            index = entryBlockEndExclusive(lines, index, bulletIndex)
        }

        return MarkdownMemoryParseResult(
            entries = entries,
            skippedEntries = skippedEntries,
            rawMarkdown = markdown
        )
    }

    fun metadataComment(entry: MarkdownMemoryEntry): String =
        buildString {
            append(MEMORY_COMMENT_PREFIX)
            append("id=").append(entry.id.trim())
            append(" type=").append(entry.type.trim())
            append(" sensitivity=").append(entry.sensitivity.trim())
            append(" source=").append(entry.source.trim())
            entry.chatId?.let { append(" chat=").append(it) }
            if (entry.createdAt > 0L) append(" created=").append(entry.createdAt)
            if (entry.updatedAt > 0L) append(" updated=").append(entry.updatedAt)
            append(" -->")
        }

    private fun renderDocument(
        title: String,
        entries: List<MarkdownMemoryEntry>,
        defaultSection: String?
    ): String {
        val grouped = entries
            .filter { it.text.isNotBlank() && it.id.isNotBlank() }
            .sortedWith(
                compareBy<MarkdownMemoryEntry> { sectionFor(it, defaultSection) }
                    .thenBy { it.type }
                    .thenBy { it.id }
            )
            .groupBy { sectionFor(it, defaultSection) }

        return buildString {
            appendLine("# $title")
            grouped.forEach { (section, sectionEntries) ->
                appendLine()
                appendLine("## $section")
                appendLine()
                sectionEntries.forEach { entry ->
                    appendLine(metadataComment(entry))
                    appendLine(renderBullet(entry.text))
                    appendLine()
                }
            }
        }.trimEnd() + "\n"
    }

    private fun renderEntryBlocks(
        entries: List<MarkdownMemoryEntry>,
        defaultSection: String?
    ): String {
        val grouped = entries
            .filter { it.text.isNotBlank() && it.id.isNotBlank() }
            .groupBy { sectionFor(it, defaultSection) }

        if (grouped.isEmpty()) return ""

        return buildString {
            grouped.forEach { (section, sectionEntries) ->
                appendLine("## $section")
                appendLine()
                sectionEntries.forEach { entry ->
                    appendLine(metadataComment(entry))
                    appendLine(renderBullet(entry.text))
                    appendLine()
                }
            }
        }.trimEnd() + "\n"
    }

    private fun sectionFor(
        entry: MarkdownMemoryEntry,
        defaultSection: String?
    ): String = entry.section
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: defaultSection
        ?: sectionTitle(entry.type)

    private fun renderBullet(text: String): String {
        val normalizedLines = text.trim().lines()
        if (normalizedLines.isEmpty()) return "- "
        return buildString {
            append("- ").append(normalizedLines.first().trim())
            normalizedLines.drop(1).forEach { line ->
                appendLine()
                append("  ").append(line.trim())
            }
        }
    }

    private fun parseMetadata(commentLine: String): Map<String, String> {
        val body = commentLine
            .removePrefix(MEMORY_COMMENT_PREFIX)
            .substringBefore("-->")
            .trim()
        if (body.isBlank()) return emptyMap()
        return body
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                val separatorIndex = token.indexOf('=')
                if (separatorIndex <= 0 || separatorIndex == token.lastIndex) {
                    null
                } else {
                    token.substring(0, separatorIndex) to token.substring(separatorIndex + 1)
                }
            }
            .toMap()
    }

    private fun nextMeaningfulLineIndex(
        lines: List<String>,
        startIndex: Int
    ): Int? {
        var index = startIndex
        while (index < lines.size) {
            if (lines[index].isNotBlank()) return index
            index += 1
        }
        return null
    }

    private fun parseBulletText(
        lines: List<String>,
        bulletIndex: Int
    ): String {
        val textLines = mutableListOf(lines[bulletIndex].trimStart().removePrefix("- ").trimEnd())
        var index = bulletIndex + 1
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("  ") || line.startsWith("\t")) {
                textLines += line.trim()
                index += 1
            } else {
                break
            }
        }
        return textLines.joinToString("\n").trim()
    }

    private fun normalizeEditedMarkdown(markdown: String): String =
        markdown.trimEnd() + "\n"

    private fun entryBlockEndExclusive(
        lines: List<String>,
        commentIndex: Int,
        bulletIndex: Int?
    ): Int {
        var index = commentIndex + 1
        if (bulletIndex != null && lines[bulletIndex].trimStart().startsWith("- ")) {
            index = bulletIndex + 1
            while (index < lines.size && (lines[index].startsWith("  ") || lines[index].startsWith("\t"))) {
                index += 1
            }
        }
        while (index < lines.size && lines[index].isBlank()) {
            index += 1
        }
        return index
    }

    private fun buildEntry(
        metadata: Map<String, String>,
        text: String?,
        section: String?
    ): MarkdownMemoryEntry? {
        val id = metadata["id"]?.takeIf { it.isNotBlank() } ?: return null
        val type = metadata["type"]?.takeIf { it.isNotBlank() } ?: return null
        val sensitivity = metadata["sensitivity"]?.takeIf { it.isNotBlank() } ?: return null
        val source = metadata["source"]?.takeIf { it.isNotBlank() } ?: return null
        val entryText = text?.takeIf { it.isNotBlank() } ?: return null
        return MarkdownMemoryEntry(
            id = id,
            text = entryText,
            type = type,
            sensitivity = sensitivity,
            source = source,
            chatId = metadata["chat"]?.toIntOrNull(),
            createdAt = metadata["created"]?.toLongOrNull() ?: 0L,
            updatedAt = metadata["updated"]?.toLongOrNull() ?: 0L,
            section = section
        )
    }

    private fun skippedReason(
        metadata: Map<String, String>,
        text: String?
    ): String {
        val missing = REQUIRED_METADATA_KEYS.filter { key -> metadata[key].isNullOrBlank() }
        return when {
            missing.isNotEmpty() -> "missing metadata: ${missing.joinToString()}"
            text.isNullOrBlank() -> "missing memory bullet"
            else -> "malformed memory entry"
        }
    }

    private fun sectionTitle(type: String): String = when (type) {
        "stable_profile" -> "Stable Profile"
        "communication_style" -> "Stable Preferences"
        "project_context" -> "Projects"
        "important_event" -> "Important Events"
        "important_person" -> "Important People"
        "emotional_pattern" -> "Emotional Patterns"
        "boundary" -> "Boundaries"
        "life_context" -> "Life Context"
        "recurring_theme" -> "Recurring Themes"
        "light_productivity_preference" -> "Productivity Preferences"
        else -> "Other Memories"
    }

    companion object {
        private const val DAILY_CONVERSATION_SECTION = "Conversation Notes"
        private const val MEMORY_COMMENT_PREFIX = "<!-- memory:"
        private val REQUIRED_METADATA_KEYS = setOf("id", "type", "sensitivity", "source")
    }
}

internal data class MarkdownMemoryRemovalResult(
    val markdown: String,
    val deletedCount: Int = 0
)

internal data class MarkdownMemoryReplacementResult(
    val markdown: String,
    val replacedCount: Int = 0
)
