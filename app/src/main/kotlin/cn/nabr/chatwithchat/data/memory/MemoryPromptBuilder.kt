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
            .map { memory -> "- ${memory.text.trim()}" }

        return buildString {
            appendLine("用户记忆：")
            lines.forEach(::appendLine)
            appendLine()
            appendLine("将这些事实作为不言明的上下文；只在有助于当前回答时使用，不要提及记忆存储，也不要在非必要时透露私密信息。")
        }.trim().take(maxCharacters)
    }
}
