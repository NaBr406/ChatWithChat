package cn.nabr.chatwithchat.data.memory

class MemoryPromptBuilder(
    private val maxMemories: Int = 8,
    private val maxCharacters: Int = 2200
) {
    fun buildRetrieved(retrievedMemories: List<MemoryRetrievalResult>): String? {
        if (retrievedMemories.isEmpty()) return null

        val lines = retrievedMemories
            .sortedWith(compareByDescending<MemoryRetrievalResult> { it.fusedScore }.thenBy { it.sourcePath }.thenBy { it.chunkId })
            .distinctBy(MemoryRetrievalResult::deduplicationKey)
            .distinctBy { memory -> normalizeExactMemoryText(memory.text) }
            .take(maxMemories)
            .map { memory ->
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
                    "Use only if genuinely relevant; never force mentioning it.",
                    sensitivityGuidance
                ).joinToString(" ")
                "- ${memory.text.trim()}. $metadata. $guidance"
            }

        return buildString {
            appendLine("Potentially relevant user memories:")
            lines.forEach(::appendLine)
            appendLine()
            appendLine("Use only memories that genuinely help answer the current request.")
            appendLine("Treat them as quiet context and do not mention memory storage or force an explicit reference.")
            appendLine("Do not reveal private or sensitive context unless the current request clearly requires it.")
        }.trim().take(maxCharacters)
    }
}
