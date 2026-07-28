package cn.nabr.chatwithchat.data.memory

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
            return MarkdownMemoryRemovalResult(markdown = markdown)
        }

        val lines = markdown.lines()
        val retained = BooleanArray(lines.size) { true }
        var deletedCount = 0
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (!trimmed.startsWith(MarkdownMemoryMetadataPolicy.COMMENT_PREFIX)) {
                index += 1
                continue
            }

            val metadataParse = parseMetadata(trimmed)
            if (metadataParse.error != null) {
                index += 1
                continue
            }
            val metadata = metadataParse.values
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
        return if (deletedCount == 0) {
            MarkdownMemoryRemovalResult(markdown = markdown)
        } else {
            MarkdownMemoryRemovalResult(
                markdown = normalizeEditedMarkdown(editedMarkdown),
                deletedCount = deletedCount
            )
        }
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
            return MarkdownMemoryReplacementResult(markdown = markdown)
        }

        val lines = markdown.lines()
        val edited = mutableListOf<String>()
        var replacedCount = 0
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (!trimmed.startsWith(MarkdownMemoryMetadataPolicy.COMMENT_PREFIX)) {
                edited += lines[index]
                index += 1
                continue
            }

            val metadataParse = parseMetadata(trimmed)
            if (metadataParse.error != null) {
                edited += lines[index]
                index += 1
                continue
            }
            val metadata = metadataParse.values
            val replacement = metadata["id"]?.let { replacementsById[it] }
            if (replacement == null) {
                edited += lines[index]
                index += 1
                continue
            }

            val bulletIndex = nextMeaningfulLineIndex(lines, index + 1)
            val endExclusive = entryBlockEndExclusive(lines, index, bulletIndex)
            val preservedExtraMetadata = metadata.filterKeys { key -> key !in MarkdownMemoryMetadataPolicy.KNOWN_KEYS }
            edited += metadataComment(
                replacement.copy(extraMetadata = preservedExtraMetadata + replacement.extraMetadata)
            )
            edited += renderBullet(replacement.text)
            edited += ""
            replacedCount += 1
            index = endExclusive
        }

        return if (replacedCount == 0) {
            MarkdownMemoryReplacementResult(markdown = markdown)
        } else {
            MarkdownMemoryReplacementResult(
                markdown = normalizeEditedMarkdown(edited.joinToString("\n")),
                replacedCount = replacedCount
            )
        }
    }

    fun parse(markdown: String): MarkdownMemoryParseResult {
        val candidates = mutableListOf<ParsedEntryCandidate>()
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

            if (!trimmed.startsWith(MarkdownMemoryMetadataPolicy.COMMENT_PREFIX)) {
                index += 1
                continue
            }

            val lineNumber = index + 1
            val metadataParse = parseMetadata(trimmed)
            val metadata = metadataParse.values
            val bulletIndex = nextMeaningfulLineIndex(lines, index + 1)
            val text = bulletIndex
                ?.takeIf { lines[it].trimStart().startsWith("- ") }
                ?.let { parseBulletText(lines, it) }

            val built = if (metadataParse.error == null) {
                buildEntry(metadata, text, currentSection)
            } else {
                EntryBuildResult(error = metadataParse.error)
            }
            val entry = built.entry
            if (entry == null) {
                skippedEntries += SkippedMarkdownMemoryEntry(
                    lineNumber = lineNumber,
                    reason = built.error ?: skippedReason(metadata, text),
                    metadata = metadata
                )
            } else {
                candidates += ParsedEntryCandidate(
                    lineNumber = lineNumber,
                    metadata = metadata,
                    entry = entry
                )
            }
            index = entryBlockEndExclusive(lines, index, bulletIndex)
        }

        val duplicateIds = candidates
            .groupingBy { candidate -> candidate.entry.id }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        val uniqueCandidates = candidates.filterNot { candidate -> candidate.entry.id in duplicateIds }
        candidates.filter { candidate -> candidate.entry.id in duplicateIds }.forEach { candidate ->
            skippedEntries += candidate.toSkipped("duplicate memory id")
        }
        val uniqueEntriesById = uniqueCandidates.associate { candidate -> candidate.entry.id to candidate.entry }
        val entries = mutableListOf<MarkdownMemoryEntry>()
        uniqueCandidates.forEach { candidate ->
            val documentError = documentLifecycleError(candidate.entry, uniqueEntriesById)
            if (documentError == null) {
                entries += candidate.entry
            } else {
                skippedEntries += candidate.toSkipped(documentError)
            }
        }

        return MarkdownMemoryParseResult(
            entries = entries,
            skippedEntries = skippedEntries,
            rawMarkdown = markdown
        )
    }

    internal fun updateObservations(
        markdown: String,
        updates: List<MarkdownMemoryObservationUpdate>
    ): MarkdownMemoryObservationResult {
        val updatesById = updates.associateBy(MarkdownMemoryObservationUpdate::entryId)
        require(updatesById.size == updates.size) { "duplicate observation update" }
        updates.forEach { update ->
            require(MarkdownMemoryMetadataPolicy.isSafeReference(update.entryId)) { "invalid observation entry id" }
            require(update.lastObservedAt >= 0L) { "invalid observation timestamp" }
            MarkdownMemoryMetadataPolicy.encodeEvidenceRefs(update.evidenceRefs)
        }
        if (updates.isEmpty()) return MarkdownMemoryObservationResult(markdown)

        val parsed = parse(markdown)
        require(parsed.skippedEntries.isEmpty()) { "unsafe memory metadata" }
        val entriesById = parsed.entries.associateBy(MarkdownMemoryEntry::id)
        updatesById.keys.forEach { entryId -> requireNotNull(entriesById[entryId]) { "unknown memory entry" } }

        var editedMarkdown = markdown
        var updatedCount = 0
        val sourceLines = sourceLines(markdown)
        memoryCommentLineIndexes(sourceLines.map(SourceLine::text)).asReversed().forEach { lineIndex ->
            val sourceLine = sourceLines[lineIndex]
            val commentLine = sourceLine.text
            val metadataParse = parseMetadata(commentLine.trim())
            if (metadataParse.error != null) return@forEach
            val entryId = metadataParse.values["id"] ?: return@forEach
            val update = updatesById[entryId] ?: return@forEach
            val current = checkNotNull(entriesById[entryId])
            val shouldUpdateObserved = update.lastObservedAt > current.lastObservedAt
            val evidenceRefs = (current.evidenceRefs + update.evidenceRefs).distinct()
            MarkdownMemoryMetadataPolicy.encodeEvidenceRefs(evidenceRefs)
            if (!shouldUpdateObserved && evidenceRefs == current.evidenceRefs) return@forEach

            var updatedLine = commentLine
            if (shouldUpdateObserved) {
                updatedLine = replaceOrInsertMetadataValue(updatedLine, "observed", update.lastObservedAt.toString())
            }
            if (evidenceRefs != current.evidenceRefs) {
                updatedLine = replaceOrInsertMetadataValue(
                    updatedLine,
                    "evidence",
                    MarkdownMemoryMetadataPolicy.encodeEvidenceRefs(evidenceRefs)
                )
            }
            val updatedMetadata = parseMetadata(updatedLine.trim())
            require(updatedMetadata.error == null) { "invalid observation metadata" }
            val rebuilt = buildEntry(updatedMetadata.values, current.text, current.section)
            require(rebuilt.error == null) { rebuilt.error ?: "invalid observation update" }

            editedMarkdown = editedMarkdown.replaceRange(sourceLine.startOffset, sourceLine.endOffset, updatedLine)
            updatedCount += 1
        }
        return MarkdownMemoryObservationResult(markdown = editedMarkdown, updatedCount = updatedCount)
    }

    fun metadataComment(entry: MarkdownMemoryEntry): String {
        require(MarkdownMemoryMetadataPolicy.validateEntry(entry) == null) {
            MarkdownMemoryMetadataPolicy.validateEntry(entry) ?: "invalid memory entry"
        }
        val comment = buildString {
            append(MarkdownMemoryMetadataPolicy.COMMENT_PREFIX)
            append("id=").append(entry.id.trim())
            append(" type=").append(entry.type.trim())
            append(" sensitivity=").append(entry.sensitivity.trim())
            append(" source=").append(entry.source.trim())
            entry.chatId?.let { append(" chat=").append(it) }
            if (entry.createdAt > 0L) append(" created=").append(entry.createdAt)
            if (entry.updatedAt > 0L) append(" updated=").append(entry.updatedAt)
            entry.canonicalKey?.let { append(" canonical_key=").append(it) }
            append(" scope=").append(entry.scope)
            if (entry.lastObservedAt > 0L) append(" observed=").append(entry.lastObservedAt)
            append(" validity=").append(entry.validity)
            entry.supersededBy?.let { append(" superseded_by=").append(it) }
            append(" recall=").append(entry.recallState)
            if (entry.evidenceRefs.isNotEmpty()) {
                append(" evidence=").append(MarkdownMemoryMetadataPolicy.encodeEvidenceRefs(entry.evidenceRefs))
            }
            entry.extraMetadata.toSortedMap().forEach { (key, value) ->
                append(' ').append(key).append('=').append(value)
            }
            append(" ").append(MarkdownMemoryMetadataPolicy.COMMENT_SUFFIX)
        }
        require(MarkdownMemoryMetadataPolicy.parseComment(comment).error == null) { "metadata comment exceeds bounds" }
        return comment
    }

    private fun renderDocument(
        title: String,
        entries: List<MarkdownMemoryEntry>,
        defaultSection: String?
    ): String {
        val renderableEntries = entries.filter { it.text.isNotBlank() && it.id.isNotBlank() }
        validateDocumentEntries(renderableEntries)
        val grouped = renderableEntries
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

    private fun parseMetadata(commentLine: String): MarkdownMemoryMetadataParse =
        MarkdownMemoryMetadataPolicy.parseComment(commentLine)

    private fun replaceOrInsertMetadataValue(
        commentLine: String,
        key: String,
        value: String
    ): String {
        require(MarkdownMemoryMetadataPolicy.isSafeMetadataKey(key)) { "invalid metadata key" }
        require(MarkdownMemoryMetadataPolicy.isSafeMetadataValue(value)) { "invalid metadata value" }
        val parsed = parseMetadata(commentLine.trim())
        require(parsed.error == null) { "unsafe memory metadata" }
        if (parsed.values[key] == value) return commentLine

        if (key in parsed.values) {
            val valueMatch = Regex("(?<![a-z0-9_])${Regex.escape(key)}=([^\\s]+)")
                .find(commentLine)
                ?.groups
                ?.get(1)
                ?: error("metadata key not found")
            return commentLine.replaceRange(valueMatch.range, value)
        }

        val suffixIndex = commentLine.lastIndexOf(MarkdownMemoryMetadataPolicy.COMMENT_SUFFIX)
        require(suffixIndex >= 0) { "metadata suffix not found" }
        var insertionIndex = suffixIndex
        while (insertionIndex > 0 && commentLine[insertionIndex - 1].isWhitespace()) {
            insertionIndex -= 1
        }
        return commentLine.substring(0, insertionIndex) +
            " $key=$value" +
            commentLine.substring(insertionIndex)
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
    ): EntryBuildResult {
        val missing = REQUIRED_METADATA_KEYS.filter { key -> metadata[key].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return EntryBuildResult(error = "missing metadata: ${missing.joinToString()}")
        }
        val entryText = text?.takeIf { it.isNotBlank() }
            ?: return EntryBuildResult(error = "missing memory bullet")
        val chatId = metadata.optionalParsed("chat", MarkdownMemoryMetadataPolicy::parseChatId)
        if (chatId.error != null) return EntryBuildResult(error = chatId.error)
        val createdAt = metadata.optionalParsed("created", MarkdownMemoryMetadataPolicy::parseTimestamp)
        if (createdAt.error != null) return EntryBuildResult(error = createdAt.error)
        val updatedAt = metadata.optionalParsed("updated", MarkdownMemoryMetadataPolicy::parseTimestamp)
        if (updatedAt.error != null) return EntryBuildResult(error = updatedAt.error)
        val observedAt = metadata.optionalParsed("observed", MarkdownMemoryMetadataPolicy::parseTimestamp)
        if (observedAt.error != null) return EntryBuildResult(error = observedAt.error)
        val evidenceRefs = MarkdownMemoryMetadataPolicy.decodeEvidenceRefs(metadata["evidence"])
            ?: return EntryBuildResult(error = "invalid evidence")
        val created = createdAt.value ?: 0L
        val updated = updatedAt.value ?: 0L
        val entry = MarkdownMemoryEntry(
            id = checkNotNull(metadata["id"]),
            text = entryText,
            type = checkNotNull(metadata["type"]),
            sensitivity = checkNotNull(metadata["sensitivity"]),
            source = checkNotNull(metadata["source"]),
            chatId = chatId.value,
            createdAt = created,
            updatedAt = updated,
            section = section,
            canonicalKey = metadata["canonical_key"],
            scope = metadata["scope"] ?: MemoryScope.GENERAL,
            lastObservedAt = observedAt.value ?: updated.takeIf { it > 0L } ?: created,
            validity = metadata["validity"] ?: MemoryValidity.CURRENT,
            supersededBy = metadata["superseded_by"],
            recallState = metadata["recall"] ?: MemoryRecallState.QUERY,
            evidenceRefs = evidenceRefs,
            extraMetadata = metadata.filterKeys { key -> key !in MarkdownMemoryMetadataPolicy.KNOWN_KEYS }
        )
        return EntryBuildResult(
            entry = entry.takeIf { MarkdownMemoryMetadataPolicy.validateEntry(it) == null },
            error = MarkdownMemoryMetadataPolicy.validateEntry(entry)
        )
    }

    private fun documentLifecycleError(
        entry: MarkdownMemoryEntry,
        entriesById: Map<String, MarkdownMemoryEntry>
    ): String? {
        if (entry.validity != MemoryValidity.OBSOLETE) return null
        val targetId = entry.supersededBy ?: return "obsolete entry requires supersession target"
        if (targetId == entry.id) return "obsolete entry cannot supersede itself"
        val target = entriesById[targetId] ?: return "supersession target not found"
        if (target.validity != MemoryValidity.CURRENT) return "supersession target must be current"
        if (target.canonicalKey != entry.canonicalKey || target.scope != entry.scope) {
            return "supersession target identity mismatch"
        }
        return null
    }

    private fun memoryCommentLineIndexes(lines: List<String>): List<Int> {
        val indexes = mutableListOf<Int>()
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (!trimmed.startsWith(MarkdownMemoryMetadataPolicy.COMMENT_PREFIX)) {
                index += 1
                continue
            }
            indexes += index
            val bulletIndex = nextMeaningfulLineIndex(lines, index + 1)
            index = entryBlockEndExclusive(lines, index, bulletIndex)
        }
        return indexes
    }

    private fun sourceLines(markdown: String): List<SourceLine> {
        val lines = mutableListOf<SourceLine>()
        var startOffset = 0
        LINE_BREAK_REGEX.findAll(markdown).forEach { lineBreak ->
            lines += SourceLine(
                text = markdown.substring(startOffset, lineBreak.range.first),
                startOffset = startOffset,
                endOffset = lineBreak.range.first
            )
            startOffset = lineBreak.range.last + 1
        }
        lines += SourceLine(
            text = markdown.substring(startOffset),
            startOffset = startOffset,
            endOffset = markdown.length
        )
        return lines
    }

    private fun validateDocumentEntries(entries: List<MarkdownMemoryEntry>) {
        require(entries.map(MarkdownMemoryEntry::id).distinct().size == entries.size) { "duplicate memory id" }
        val entriesById = entries.associateBy(MarkdownMemoryEntry::id)
        entries.forEach { entry ->
            val localError = MarkdownMemoryMetadataPolicy.validateEntry(entry)
            require(localError == null) { localError ?: "invalid memory entry" }
            val documentError = documentLifecycleError(entry, entriesById)
            require(documentError == null) { documentError ?: "invalid memory lifecycle" }
        }
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
        private val REQUIRED_METADATA_KEYS = setOf("id", "type", "sensitivity", "source")
        private val LINE_BREAK_REGEX = Regex("\\r\\n|\\n|\\r")
    }
}

private data class SourceLine(
    val text: String,
    val startOffset: Int,
    val endOffset: Int
)

private data class ParsedEntryCandidate(
    val lineNumber: Int,
    val metadata: Map<String, String>,
    val entry: MarkdownMemoryEntry
) {
    fun toSkipped(reason: String): SkippedMarkdownMemoryEntry = SkippedMarkdownMemoryEntry(
        lineNumber = lineNumber,
        reason = reason,
        metadata = metadata
    )
}

private data class EntryBuildResult(
    val entry: MarkdownMemoryEntry? = null,
    val error: String? = null
)

private data class OptionalParsedMetadata<T>(
    val value: T? = null,
    val error: String? = null
)

private fun <T> Map<String, String>.optionalParsed(
    key: String,
    parser: (String?) -> T?
): OptionalParsedMetadata<T> {
    val raw = this[key] ?: return OptionalParsedMetadata()
    return parser(raw)?.let { value -> OptionalParsedMetadata(value = value) }
        ?: OptionalParsedMetadata(error = "invalid $key")
}

internal data class MarkdownMemoryRemovalResult(
    val markdown: String,
    val deletedCount: Int = 0
)

internal data class MarkdownMemoryReplacementResult(
    val markdown: String,
    val replacedCount: Int = 0
)
