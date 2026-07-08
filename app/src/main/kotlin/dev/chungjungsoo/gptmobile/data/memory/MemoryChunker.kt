package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryChunk

class MemoryChunker(
    private val markdownMemoryCodec: MarkdownMemoryCodec = MarkdownMemoryCodec(),
    private val maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS
) {

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
        indexedAt: Long
    ): List<MemoryChunk> {
        val parsed = markdownMemoryCodec.parse(markdown)
        val parsedEntryChunks = parsed.entries.flatMapIndexed { entryIndex, entry ->
            splitText(entry.text).mapIndexed { partIndex, chunkText ->
                MemoryChunk(
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
                    indexedAt = indexedAt
                )
            }
        }
        if (parsedEntryChunks.isNotEmpty()) return parsedEntryChunks

        return fallbackChunks(sourcePath, markdown, indexedAt)
    }

    private fun fallbackChunks(
        sourcePath: String,
        markdown: String,
        indexedAt: Long
    ): List<MemoryChunk> {
        val sections = splitSections(markdown)
        return sections.flatMapIndexed { sectionIndex, section ->
            splitText(section.text).mapIndexed { partIndex, chunkText ->
                MemoryChunk(
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
                    indexedAt = indexedAt
                )
            }
        }
    }

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
    }
}
