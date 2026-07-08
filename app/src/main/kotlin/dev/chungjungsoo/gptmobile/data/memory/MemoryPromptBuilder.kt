package dev.chungjungsoo.gptmobile.data.memory

class MemoryPromptBuilder(
    private val maxMemories: Int = 8,
    private val maxCharacters: Int = 2200
) {
    fun build(selectedMemories: List<SelectedPersonalMemory>): String? {
        if (selectedMemories.isEmpty()) return null

        val lines = mutableListOf<String>()
        selectedMemories
            .sortedWith(compareByDescending<SelectedPersonalMemory> { it.relevance }.thenByDescending { it.memory.importance })
            .take(maxMemories)
            .forEach { selectedMemory ->
                val memory = selectedMemory.memory
                val usageGuidance = when (selectedMemory.usage) {
                    MemoryUsage.TONE_ONLY -> "Do not mention explicitly; use it only for tone."
                    MemoryUsage.EXPLICIT_IF_NATURAL -> "Use only if relevant; do not force mentioning it."
                    else -> "Use as quiet background context."
                }
                val sensitivityGuidance = when (memory.sensitivity) {
                    MemorySensitivity.PRIVATE, MemorySensitivity.SENSITIVE -> "Handle carefully and do not reveal unless clearly relevant."
                    else -> null
                }
                val typeGuidance = if (memory.type == "important_event") {
                    "Only if relevant."
                } else {
                    null
                }
                val guidance = listOfNotNull(usageGuidance, typeGuidance, sensitivityGuidance).joinToString(" ")
                lines.add("- ${memory.recallText}. usage: ${selectedMemory.usage}. $guidance")
            }

        val body = buildString {
            appendLine("Relevant long-term user memories:")
            lines.forEach { line -> appendLine(line) }
            appendLine()
            appendLine("Guidance:")
            appendLine("Use these memories to adapt tone, continuity, and understanding.")
            appendLine("Do not explicitly mention a memory unless it naturally helps the current reply.")
            appendLine("Do not reveal that a private memory exists unless the user's current message makes it clearly relevant.")
        }.trim()

        return body.take(maxCharacters)
    }

    fun buildMarkdown(selectedMemories: List<MemoryIndexSearchResult>): String? {
        if (selectedMemories.isEmpty()) return null

        val lines = mutableListOf<String>()
        selectedMemories
            .sortedWith(compareByDescending<MemoryIndexSearchResult> { it.score }.thenBy { it.sourcePath }.thenBy { it.chunkIndex })
            .take(maxMemories)
            .forEach { memory ->
                val sensitivityGuidance = when (memory.sensitivity) {
                    MemorySensitivity.PRIVATE, MemorySensitivity.SENSITIVE -> "Handle carefully and do not reveal unless clearly relevant."
                    else -> null
                }
                val metadata = listOfNotNull(
                    memory.type?.let { "type: $it" },
                    memory.sensitivity?.let { "sensitivity: $it" },
                    memory.source?.let { "source: $it" },
                    memory.entryId?.let { "id: $it" },
                    "path: ${memory.sourcePath}"
                ).joinToString(", ")
                val guidance = listOfNotNull(
                    "Use only if relevant; do not force mentioning it.",
                    sensitivityGuidance
                ).joinToString(" ")
                lines.add("- ${memory.text.trim()}. $metadata. $guidance")
            }

        val body = buildString {
            appendLine("Relevant long-term user memories:")
            lines.forEach { line -> appendLine(line) }
            appendLine()
            appendLine("Guidance:")
            appendLine("Use these memories to adapt tone, continuity, and understanding.")
            appendLine("Do not explicitly mention a memory unless it naturally helps the current reply.")
            appendLine("Do not reveal that a private memory exists unless the user's current message makes it clearly relevant.")
        }.trim()

        return body.take(maxCharacters)
    }
}
