package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptBuilderTest {

    @Test
    fun `prompt includes selected memories and guidance`() {
        val prompt = MemoryPromptBuilder().build(
            listOf(
                SelectedPersonalMemory(
                    memory = testMemory(1, "The user prefers natural Chinese conversation."),
                    usage = MemoryUsage.TONE_ONLY,
                    relevance = 0.9f,
                    reason = "Tone"
                ),
                SelectedPersonalMemory(
                    memory = testMemory(2, "The user has an important exam soon.", type = "important_event"),
                    usage = MemoryUsage.EXPLICIT_IF_NATURAL,
                    relevance = 0.8f,
                    reason = "Relevant"
                )
            )
        )

        assertTrue(prompt!!.contains("Relevant long-term user memories"))
        assertTrue(prompt.contains("usage: tone_only"))
        assertTrue(prompt.contains("Do not explicitly mention"))
        assertTrue(prompt.contains("Only if relevant"))
    }

    @Test
    fun `prompt returns null when there are no selected memories`() {
        assertFalse(MemoryPromptBuilder().build(emptyList()).orEmpty().isNotBlank())
    }
}
