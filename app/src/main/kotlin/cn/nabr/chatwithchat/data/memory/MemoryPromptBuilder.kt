package cn.nabr.chatwithchat.data.memory

class MemoryPromptBuilder(
    private val maxMemories: Int = 8,
    private val maxCharacters: Int = 2200
) {
    fun buildRetrieved(retrievedMemories: List<MemoryRetrievalResult>): String? {
        if (retrievedMemories.isEmpty()) return null

        val lines = retrievedMemories
            .sortedWith(
                compareByDescending<MemoryRetrievalResult> { it.type == "communication_style" }
                    .thenByDescending { it.fusedScore }
                    .thenBy { it.sourcePath }
                    .thenBy { it.chunkId }
            )
            .distinctBy(MemoryRetrievalResult::deduplicationKey)
            .distinctBy { memory -> normalizeExactMemoryText(memory.text) }
            .take(maxMemories)
            .map { memory ->
                val sensitivityGuidance = when (memory.sensitivity) {
                    MemorySensitivity.PRIVATE, MemorySensitivity.SENSITIVE -> "谨慎处理；只有明确相关时才可透露。"
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
                    if (memory.type == "communication_style") {
                        "面向用户回复时将其作为默认沟通偏好，不要刻意提及。"
                    } else {
                        "仅在确实相关时使用，不要强行提及。"
                    },
                    sensitivityGuidance
                ).joinToString(" ")
                "- ${memory.text.trim()}. $metadata. $guidance"
            }

        return buildString {
            appendLine("可能相关的用户记忆：")
            lines.forEach(::appendLine)
            appendLine()
            appendLine("只使用确实有助于回答当前请求的记忆。")
            appendLine("将其作为不言明的上下文，不要提及记忆存储，也不要强行引用。")
            appendLine("除非当前请求明确需要，否则不要透露私密或敏感上下文。")
        }.trim().take(maxCharacters)
    }
}
