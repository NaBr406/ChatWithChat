package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.dto.ApiState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningStreamParserTest {
    @Test
    fun `structured reasoning is emitted separately from content`() {
        val parser = ReasoningStreamParser()

        val emitted = parser.append(
            reasoningChunk = "Thoughts",
            contentChunk = "Answer"
        ) + parser.flush()

        assertEquals(
            listOf(
                ApiState.Thinking("Thoughts"),
                ApiState.Success("Answer")
            ),
            emitted
        )
    }

    @Test
    fun `think and thinking tags are extracted into thinking state`() {
        listOf("think", "thinking").forEach { tagName ->
            val parser = ReasoningStreamParser()

            val emitted = parser.append(
                contentChunk = "<$tagName>Reasoning</$tagName>Answer"
            ) + parser.flush()

            assertCombinedText(
                expectedThoughts = "Reasoning",
                expectedContent = "Answer",
                emitted = emitted
            )
        }
    }

    @Test
    fun `both tag forms can be split at every character boundary`() {
        listOf("think", "thinking").forEach { tagName ->
            val response = "<$tagName>Plan</$tagName> Answer"
            for (splitIndex in 1 until response.length) {
                val parser = ReasoningStreamParser()
                val emitted = buildList {
                    addAll(parser.append(contentChunk = response.substring(0, splitIndex)))
                    addAll(parser.append(contentChunk = response.substring(splitIndex)))
                    addAll(parser.flush())
                }

                assertCombinedText(
                    expectedThoughts = "Plan",
                    expectedContent = "Answer",
                    emitted = emitted
                )
            }
        }
    }

    @Test
    fun `both tag forms can arrive one character per chunk`() {
        listOf("think", "thinking").forEach { tagName ->
            val parser = ReasoningStreamParser()
            val emitted = buildList {
                "<$tagName>Plan</$tagName>Answer".forEach { character ->
                    addAll(parser.append(contentChunk = character.toString()))
                }
                addAll(parser.flush())
            }

            assertCombinedText(
                expectedThoughts = "Plan",
                expectedContent = "Answer",
                emitted = emitted
            )
        }
    }

    @Test
    fun `tagged reasoning is emitted before the closing tag arrives`() {
        val parser = ReasoningStreamParser()

        val firstChunk = parser.append(contentChunk = "<think>First")
        val secondChunk = parser.append(contentChunk = " thought")
        val finalChunk = parser.append(contentChunk = "</think>Answer") + parser.flush()

        assertEquals(listOf(ApiState.Thinking("First")), firstChunk)
        assertEquals(listOf(ApiState.Thinking(" thought")), secondChunk)
        assertEquals(listOf(ApiState.Success("Answer")), finalChunk)
    }

    @Test
    fun `empty think block does not leave blank answer gap`() {
        val parser = ReasoningStreamParser()

        val emitted = parser.append(contentChunk = "<think>   \n </think>\n\nAnswer") + parser.flush()

        assertEquals(listOf(ApiState.Success("Answer")), emitted)
    }

    @Test
    fun `structured reasoning wins when the same response also contains tags`() {
        val parser = ReasoningStreamParser()

        val emitted = parser.append(
            reasoningChunk = "Canonical reasoning",
            contentChunk = "<thinking>Duplicated reasoning</thinking>Answer"
        ) + parser.flush()

        assertCombinedText(
            expectedThoughts = "Canonical reasoning",
            expectedContent = "Answer",
            emitted = emitted
        )
    }

    @Test
    fun `structured reasoning takes over an open tag before tagged text is emitted`() {
        val parser = ReasoningStreamParser()

        val emitted = buildList {
            addAll(parser.append(contentChunk = "<think>"))
            addAll(parser.append(reasoningChunk = "Canonical", contentChunk = "Duplicated reasoning</think>Answer"))
            addAll(parser.flush())
        }

        assertCombinedText(
            expectedThoughts = "Canonical",
            expectedContent = "Answer",
            emitted = emitted
        )
    }

    @Test
    fun `structured reasoning suppresses an unclosed duplicate tag at flush`() {
        val parser = ReasoningStreamParser()

        val emitted = parser.append(
            reasoningChunk = "Canonical",
            contentChunk = "<thinking>Duplicate"
        ) + parser.flush()

        assertEquals(listOf(ApiState.Thinking("Canonical")), emitted)
    }

    @Test
    fun `blank structured field allows tag fallback`() {
        val parser = ReasoningStreamParser()

        val emitted = parser.append(
            reasoningChunk = "   ",
            contentChunk = "<think>Fallback</think>Answer"
        ) + parser.flush()

        assertCombinedText(
            expectedThoughts = "Fallback",
            expectedContent = "Answer",
            emitted = emitted
        )
    }

    @Test
    fun `unclosed thinking block remains streamed thinking`() {
        val parser = ReasoningStreamParser()

        val firstChunk = parser.append(contentChunk = "<thinking>unfinished")
        val flushed = parser.flush()

        assertEquals(listOf(ApiState.Thinking("unfinished")), firstChunk)
        assertEquals(emptyList<ApiState>(), flushed)
        assertEquals(emptyList<ApiState>(), parser.flush())
    }

    @Test
    fun `literal tags after visible content remain in the answer`() {
        val parser = ReasoningStreamParser()
        val content = "Example: <think>literal markup</think>"

        val emitted = parser.append(contentChunk = content) + parser.flush()

        assertCombinedText(
            expectedThoughts = "",
            expectedContent = content,
            emitted = emitted
        )
    }

    private fun assertCombinedText(
        expectedThoughts: String,
        expectedContent: String,
        emitted: List<ApiState>
    ) {
        assertEquals(
            expectedThoughts,
            emitted.filterIsInstance<ApiState.Thinking>().joinToString(separator = "") { it.thinkingChunk }
        )
        assertEquals(
            expectedContent,
            emitted.filterIsInstance<ApiState.Success>().joinToString(separator = "") { it.textChunk }
        )
    }
}
