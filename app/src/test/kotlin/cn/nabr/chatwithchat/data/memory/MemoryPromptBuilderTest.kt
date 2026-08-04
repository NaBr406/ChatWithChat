package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.token.TokenUsageEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptBuilderTest {

    @Test
    fun `prompt includes natural language facts and one global privacy guidance`() {
        val rendered = MemoryPromptBuilder().build(
            coreFacts = listOf(ModelVisibleMemoryFact("The user prefers natural Chinese conversation.")),
            queryFacts = listOf(ModelVisibleMemoryFact("The current project is ChatWithChat."))
        )
        val prompt = rendered.prompt.orEmpty()

        assertTrue(prompt.contains("用户记忆"))
        assertTrue(prompt.contains("The user prefers natural Chinese conversation."))
        assertTrue(prompt.contains("The current project is ChatWithChat."))
        assertFalse(prompt.contains("MEMORY.md"))
        assertFalse(prompt.contains("canonical_key"))
        assertFalse(prompt.contains("type:"))
        assertFalse(prompt.contains("sensitivity:"))
        assertFalse(prompt.contains("source:"))
        assertEquals(1, prompt.split("不要提及记忆存储").size - 1)
    }

    @Test
    fun `prompt returns null when both recall layers are empty`() {
        val rendered = MemoryPromptBuilder().build(emptyList(), emptyList())

        assertNull(rendered.prompt)
        assertEquals(0, rendered.estimatedTokens)
        assertTrue(rendered.coreFacts.isEmpty())
        assertTrue(rendered.queryFacts.isEmpty())
    }

    @Test
    fun `builder keeps every core fact and up to eight query facts with exact deduplication`() {
        val rendered = MemoryPromptBuilder().build(
            coreFacts = (1..6).map { index -> ModelVisibleMemoryFact("Core fact $index.") },
            queryFacts = listOf(
                ModelVisibleMemoryFact("Query fact 1."),
                ModelVisibleMemoryFact("  QUERY   FACT 1.  "),
                ModelVisibleMemoryFact("Query fact 2."),
                ModelVisibleMemoryFact("Query fact 3."),
                ModelVisibleMemoryFact("Query fact 4.")
            )
        )

        assertEquals(6, rendered.coreFacts.size)
        assertEquals(4, rendered.queryFacts.size)
        assertEquals(1, normalizeExactMemoryText(rendered.prompt.orEmpty()).split("query fact 1.").size - 1)
    }

    @Test
    fun `builder deduplicates equivalent facts across core and query layers`() {
        val rendered = MemoryPromptBuilder().build(
            coreFacts = listOf(ModelVisibleMemoryFact("Call the user Alex.")),
            queryFacts = listOf(
                ModelVisibleMemoryFact("  CALL   THE USER ALEX.  "),
                ModelVisibleMemoryFact("The current project is ChatWithChat.")
            )
        )

        assertEquals(listOf("Call the user Alex."), rendered.coreFacts.map { fact -> fact.text })
        assertEquals(
            listOf("The current project is ChatWithChat."),
            rendered.queryFacts.map { fact -> fact.text }
        )
        assertEquals(1, normalizeExactMemoryText(rendered.prompt.orEmpty()).split("call the user alex.").size - 1)
    }

    @Test
    fun `core and query facts are not dropped by a memory token budget`() {
        val oversized = "oversized-marker " + "detail ".repeat(1_000)
        val retained = "Short relevant fact."
        val rendered = MemoryPromptBuilder().build(
            coreFacts = listOf(ModelVisibleMemoryFact(retained)),
            queryFacts = listOf(ModelVisibleMemoryFact(oversized), ModelVisibleMemoryFact("Second query fact."))
        )
        val prompt = rendered.prompt.orEmpty()

        assertTrue(prompt.contains(retained))
        assertTrue(prompt.contains("Second query fact."))
        assertTrue(prompt.contains("oversized-marker"))
        assertTrue(rendered.estimatedTokens > 500)
        assertEquals(
            rendered.estimatedTokens,
            TokenUsageEstimator.estimateText(prompt, model = "", clientType = ClientType.OPENAI)
        )
    }

    @Test
    fun `more than four core facts remain visible`() {
        val rendered = MemoryPromptBuilder().build(
            coreFacts = (1..6).map { index ->
                ModelVisibleMemoryFact("Core fact $index. " + "core-detail ".repeat(120))
            },
            queryFacts = (1..3).map { index ->
                ModelVisibleMemoryFact("Query fact $index. " + "query-detail ".repeat(120))
            }
        )

        assertEquals(6, rendered.coreFacts.size)
        assertEquals(3, rendered.queryFacts.size)
        assertTrue(rendered.estimatedTokens > 500)
        assertEquals(
            rendered.estimatedTokens,
            TokenUsageEstimator.estimateText(rendered.prompt.orEmpty(), model = "", clientType = ClientType.OPENAI)
        )
    }

    @Test
    fun `model visible fact rejects internal metadata shaped text`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ModelVisibleMemoryFact("scope: general")
        }

        assertEquals("Memory fact text must not contain internal metadata", error.message)
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
        embeddingContentHash = "$id-hash",
        lexicalScore = fusedScore,
        fusedScore = fusedScore,
        updatedAt = fusedScore.toLong()
    )
}
