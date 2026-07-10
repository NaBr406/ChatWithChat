package dev.chungjungsoo.gptmobile.data.memory

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
}
