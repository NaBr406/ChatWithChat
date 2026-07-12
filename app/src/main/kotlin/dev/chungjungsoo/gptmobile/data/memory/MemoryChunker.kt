package dev.chungjungsoo.gptmobile.data.memory

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
        markdown: String
    ): List<MemoryCorpusChunk> {
        val parsed = markdownMemoryCodec.parse(markdown)
        val parsedEntryChunks = parsed.entries.flatMapIndexed { entryIndex, entry ->
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
                    updatedAt = entry.updatedAt
                )
            }
        }
        if (parsedEntryChunks.isNotEmpty()) return parsedEntryChunks

        return fallbackChunks(sourcePath, markdown)
    }

    private fun fallbackChunks(
        sourcePath: String,
        markdown: String
    ): List<MemoryCorpusChunk> {
        val sections = splitSections(markdown)
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
                    updatedAt = 0L
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
        updatedAt: Long
    ): MemoryCorpusChunk {
        val contentHash = listOf(
            hashField("entryId", entryId),
            hashField("sourcePath", sourcePath),
            hashField("chunkIndex", chunkIndex.toString()),
            hashField("heading", heading),
            hashField("text", text),
            hashField("type", type),
            hashField("sensitivity", sensitivity),
            hashField("source", source),
            hashField("chatId", chatId?.toString()),
            hashField("createdAt", createdAt.toString()),
            hashField("updatedAt", updatedAt.toString())
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
            contentHash = contentHash
        )
    }

    private fun hashField(name: String, value: String?): String {
        val normalized = value?.normalizedHashValue()
        return "$name:${normalized?.length ?: -1}:${normalized.orEmpty()}"
    }

    private fun String.normalizedHashValue(): String = trim().replace(WHITESPACE_REGEX, " ")

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
    }
}
