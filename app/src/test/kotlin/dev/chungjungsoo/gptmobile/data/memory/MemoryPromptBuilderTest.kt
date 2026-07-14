package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptBuilderTest {

    @Test
    fun `prompt includes locally retrieved markdown memory and privacy guidance`() {
        val prompt = MemoryPromptBuilder().buildRetrieved(
            listOf(
                MemoryRetrievalResult(
                    chunkId = "MEMORY.md#mem_1#0",
                    entryId = "mem_1",
                    sourcePath = "MEMORY.md",
                    text = "The user prefers natural Chinese conversation.",
                    type = "communication_style",
                    sensitivity = MemorySensitivity.PRIVATE,
                    source = MemorySource.USER_CONFIRMED,
                    contentHash = "hash",
                    lexicalScore = 1f,
                    fusedScore = 1f,
                    updatedAt = 100L
                )
            )
        )

        assertTrue(prompt!!.contains("Potentially relevant user memories"))
        assertTrue(prompt.contains("path: MEMORY.md"))
        assertTrue(prompt.contains("Handle carefully"))
        assertTrue(prompt.contains("Do not reveal private or sensitive context"))
    }

    @Test
    fun `prompt returns null when local retrieval returns no memories`() {
        assertFalse(MemoryPromptBuilder().buildRetrieved(emptyList()).orEmpty().isNotBlank())
    }

    @Test
    fun `prompt defense keeps one exact text representative before memory limit`() {
        val prompt = MemoryPromptBuilder(maxMemories = 2).buildRetrieved(
            listOf(
                retrievalResult("mem_duplicate_a", "The user prefers CAFÉ answers.", fusedScore = 3f),
                retrievalResult("mem_duplicate_b", "\u00a0THE user prefers\u3000café\nanswers.  ", fusedScore = 2f),
                retrievalResult("mem_unique", "The user keeps a unique project context.", fusedScore = 1f)
            )
        ).orEmpty()
        val normalizedPrompt = normalizeExactMemoryText(prompt)

        assertEquals(1, normalizedPrompt.split("the user prefers café answers.").size - 1)
        assertTrue(normalizedPrompt.contains("the user keeps a unique project context."))
    }

    @Test
    fun `final pack exact deduplication happens before token budget`() {
        val packed = listOf(
            retrievalResult("mem_duplicate_a", "Shared duplicate.", fusedScore = 3f),
            retrievalResult("mem_duplicate_b", "  SHARED   DUPLICATE.  ", fusedScore = 2f),
            retrievalResult("mem_unique", "Shared unique.", fusedScore = 1f)
        ).packFor(
            MemoryRetrievalRequest(
                corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
                query = "shared",
                limit = 3,
                candidateLimit = 3,
                tokenBudget = 60
            )
        )

        assertEquals(listOf("mem_duplicate_a", "mem_unique"), packed.map { result -> result.entryId })
    }

    private fun retrievalResult(
        id: String,
        text: String,
        fusedScore: Float
    ): MemoryRetrievalResult = MemoryRetrievalResult(
        chunkId = "MEMORY.md#$id#0",
        entryId = id,
        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
        text = text,
        type = "communication_style",
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        contentHash = "$id-hash",
        lexicalScore = fusedScore,
        fusedScore = fusedScore,
        updatedAt = fusedScore.toLong()
    )
}
