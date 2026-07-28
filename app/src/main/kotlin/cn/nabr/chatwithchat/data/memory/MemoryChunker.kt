package cn.nabr.chatwithchat.data.memory

class MemoryChunker(
    private val markdownMemoryCodec: MarkdownMemoryCodec = MarkdownMemoryCodec(),
    private val maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS
) {

    init {
        require(maxChunkChars > 0) { "maxChunkChars must be positive" }
    }

    fun titleFor(markdown: String): String =
        markdown
            .lineSequence()
            .firstOrNull { it.trimStart().startsWith("# ") }
            ?.trim()
            ?.removePrefix("# ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Untitled Memory"

    fun chunksFor(
        sourcePath: String,
        markdown: String,
        projectionPolicy: MemoryProjectionPolicy
    ): MemoryChunkingResult {
        val parsed = markdownMemoryCodec.parse(markdown)
        if (
            projectionPolicy == MemoryProjectionPolicy.CHAT_ACTIVE_ONLY &&
            parsed.skippedEntries.isNotEmpty()
        ) {
            return chunkingResult(
                chunks = emptyList(),
                diagnostics = listOf(
                    MemoryProjectionDiagnostic(
                        code = DIAGNOSTIC_CHAT_PARSE_FAILED,
                        sourcePath = sourcePath,
                        count = parsed.skippedEntries.size
                    )
                )
            )
        }

        val projectedEntries = when (projectionPolicy) {
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY -> parsed.entries.filter { entry ->
                entry.validity == MemoryValidity.CURRENT &&
                    entry.recallState in setOf(MemoryRecallState.CORE, MemoryRecallState.QUERY)
            }
            MemoryProjectionPolicy.MAINTENANCE_FULL -> parsed.entries
        }
        val parsedEntryChunks = projectedEntries.flatMapIndexed { entryIndex, entry ->
            splitText(entry.text).mapIndexed { partIndex, chunkText ->
                corpusChunk(
                    chunkId = chunkId(sourcePath, entry.id, partIndex),
                    sourcePath = sourcePath,
                    chunkIndex = entryIndex * CHUNK_INDEX_STRIDE + partIndex,
                    heading = entry.section,
                    text = chunkText,
                    entryId = entry.id,
                    type = entry.type,
                    sensitivity = entry.sensitivity,
                    source = entry.source,
                    chatId = entry.chatId,
                    createdAt = entry.createdAt,
                    updatedAt = entry.updatedAt,
                    canonicalKey = entry.canonicalKey,
                    scope = entry.scope,
                    validity = entry.validity,
                    recallState = entry.recallState
                )
            }
        }
        if (parsedEntryChunks.isNotEmpty() || parsed.entries.isNotEmpty()) {
            return chunkingResult(
                chunks = parsedEntryChunks,
                diagnostics = parsed.skippedEntries.takeIf { it.isNotEmpty() }?.let { skipped ->
                    listOf(
                        MemoryProjectionDiagnostic(
                            code = DIAGNOSTIC_MAINTENANCE_PARSE_SKIPPED,
                            sourcePath = sourcePath,
                            count = skipped.size
                        )
                    )
                }.orEmpty()
            )
        }
        if (parsed.skippedEntries.isNotEmpty()) {
            return chunkingResult(
                chunks = emptyList(),
                diagnostics = listOf(
                    MemoryProjectionDiagnostic(
                        code = DIAGNOSTIC_MAINTENANCE_PARSE_SKIPPED,
                        sourcePath = sourcePath,
                        count = parsed.skippedEntries.size
                    )
                )
            )
        }
        if (projectionPolicy == MemoryProjectionPolicy.CHAT_ACTIVE_ONLY) {
            val hasUnstructuredBody = markdown
                .replace(HIDDEN_COMMENT_REGEX, "")
                .lineSequence()
                .map(String::trim)
                .any { line -> line.isNotBlank() && !line.startsWith("#") }
            return chunkingResult(
                chunks = emptyList(),
                diagnostics = if (hasUnstructuredBody) {
                    listOf(
                        MemoryProjectionDiagnostic(
                            code = DIAGNOSTIC_CHAT_UNSTRUCTURED,
                            sourcePath = sourcePath
                        )
                    )
                } else {
                    emptyList()
                }
            )
        }

        return chunkingResult(fallbackChunks(sourcePath, markdown))
    }

    private fun fallbackChunks(
        sourcePath: String,
        markdown: String
    ): List<MemoryCorpusChunk> {
        val sections = splitSections(markdown.replace(HIDDEN_COMMENT_REGEX, ""))
        return sections.flatMapIndexed { sectionIndex, section ->
            splitText(section.text).mapIndexed { partIndex, chunkText ->
                corpusChunk(
                    chunkId = chunkId(sourcePath, "section_$sectionIndex", partIndex),
                    sourcePath = sourcePath,
                    chunkIndex = sectionIndex * CHUNK_INDEX_STRIDE + partIndex,
                    heading = section.heading,
                    text = chunkText,
                    entryId = null,
                    type = null,
                    sensitivity = null,
                    source = null,
                    chatId = null,
                    createdAt = 0L,
                    updatedAt = 0L,
                    canonicalKey = null,
                    scope = null,
                    validity = null,
                    recallState = null
                )
            }
        }
    }

    private fun corpusChunk(
        chunkId: String,
        sourcePath: String,
        chunkIndex: Int,
        heading: String?,
        text: String,
        entryId: String?,
        type: String?,
        sensitivity: String?,
        source: String?,
        chatId: Int?,
        createdAt: Long,
        updatedAt: Long,
        canonicalKey: String?,
        scope: String?,
        validity: String?,
        recallState: String?
    ): MemoryCorpusChunk {
        val embeddingText = text.normalizedNaturalLanguage()
        val embeddingContentHash = embeddingText.sha256Utf8()
        val rankingHash = listOf(
            hashField("embeddingContentHash", embeddingContentHash),
            hashField("heading", heading),
            hashField("type", type),
            hashField("sensitivity", sensitivity),
            hashField("canonicalKey", canonicalKey),
            hashField("scope", scope),
            hashField("validity", validity),
            hashField("recallState", recallState)
        ).joinToString(separator = "")
            .sha256Utf8()
        return MemoryCorpusChunk(
            chunkId = chunkId,
            entryId = entryId,
            sourcePath = sourcePath,
            chunkIndex = chunkIndex,
            heading = heading,
            text = text,
            type = type,
            sensitivity = sensitivity,
            source = source,
            chatId = chatId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            canonicalKey = canonicalKey,
            scope = scope,
            validity = validity,
            recallState = recallState,
            embeddingText = embeddingText,
            embeddingContentHash = embeddingContentHash,
            rankingHash = rankingHash
        )
    }

    private fun chunkingResult(
        chunks: List<MemoryCorpusChunk>,
        diagnostics: List<MemoryProjectionDiagnostic> = emptyList()
    ): MemoryChunkingResult {
        val projectionHash = chunks.mapIndexed { index, chunk ->
            listOf(
                hashField("index", index.toString()),
                hashField("chunkId", chunk.chunkId),
                hashField("embeddingContentHash", chunk.embeddingContentHash),
                hashField("rankingHash", chunk.rankingHash)
            ).joinToString(separator = "")
        }.joinToString(separator = "")
            .sha256Utf8()
        return MemoryChunkingResult(
            chunks = chunks,
            projectionHash = projectionHash,
            diagnostics = diagnostics
        )
    }

    private fun hashField(name: String, value: String?): String {
        val normalized = value?.normalizedHashValue()
        return "$name:${normalized?.length ?: -1}:${normalized.orEmpty()}"
    }

    private fun String.normalizedHashValue(): String = normalizedNaturalLanguage()

    private fun String.normalizedNaturalLanguage(): String = trim().replace(WHITESPACE_REGEX, " ")

    private fun splitSections(markdown: String): List<MarkdownSection> {
        val sections = mutableListOf<MarkdownSection>()
        var currentHeading: String? = null
        val currentLines = mutableListOf<String>()

        markdown.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("## ")) {
                appendSection(sections, currentHeading, currentLines)
                currentHeading = trimmed.removePrefix("## ").trim().takeIf { it.isNotBlank() }
                currentLines.clear()
            } else if (!trimmed.startsWith("# ")) {
                currentLines += line
            }
        }
        appendSection(sections, currentHeading, currentLines)

        return sections.ifEmpty {
            val text = markdown.lines()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString("\n")
                .trim()
            if (text.isBlank()) emptyList() else listOf(MarkdownSection(null, text))
        }
    }

    private fun appendSection(
        sections: MutableList<MarkdownSection>,
        heading: String?,
        lines: List<String>
    ) {
        val text = lines.joinToString("\n").trim()
        if (text.isNotBlank()) {
            sections += MarkdownSection(heading, text)
        }
    }

    private fun splitText(text: String): List<String> {
        val normalized = text.trim()
        if (normalized.isBlank()) return emptyList()
        if (normalized.length <= maxChunkChars) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            val hardEnd = (start + maxChunkChars).coerceAtMost(normalized.length)
            val naturalEnd = normalized
                .lastIndexOf('\n', startIndex = hardEnd - 1)
                .takeIf { it > start + MIN_NATURAL_BREAK_DISTANCE }
                ?: normalized
                    .lastIndexOf(' ', startIndex = hardEnd - 1)
                    .takeIf { it > start + MIN_NATURAL_BREAK_DISTANCE }
                ?: hardEnd
            val end = safeTextEnd(normalized, naturalEnd)
            chunks += normalized.substring(start, end).trim()
            start = end
        }
        return chunks.filter { it.isNotBlank() }
    }

    private fun safeTextEnd(text: String, end: Int): Int {
        if (end <= 0 || end >= text.length) return end.coerceIn(0, text.length)
        return if (Character.isHighSurrogate(text[end - 1])) {
            (end + 1).coerceAtMost(text.length)
        } else {
            end
        }
    }

    private fun chunkId(
        sourcePath: String,
        localId: String,
        partIndex: Int
    ): String = "$sourcePath#$localId#$partIndex"

    private data class MarkdownSection(
        val heading: String?,
        val text: String
    )

    companion object {
        private const val DEFAULT_MAX_CHUNK_CHARS = 1200
        private const val CHUNK_INDEX_STRIDE = 100
        private const val MIN_NATURAL_BREAK_DISTANCE = 240
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val HIDDEN_COMMENT_REGEX = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL))
        private const val DIAGNOSTIC_CHAT_PARSE_FAILED = "chat_projection_parse_failed"
        private const val DIAGNOSTIC_CHAT_UNSTRUCTURED = "chat_projection_unstructured"
        private const val DIAGNOSTIC_MAINTENANCE_PARSE_SKIPPED = "maintenance_projection_entries_skipped"
    }
}
