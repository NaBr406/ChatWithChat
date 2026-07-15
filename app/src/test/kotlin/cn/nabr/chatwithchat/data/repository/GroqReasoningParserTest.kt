package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.dto.ApiState
import org.junit.Assert.assertEquals
import org.junit.Test

class GroqReasoningParserTest {
    @Test
    fun `parsed reasoning is emitted separately from content`() {
        val parser = GroqReasoningParser()

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
    fun `raw think tags are extracted into thinking state`() {
        val parser = GroqReasoningParser()

        val emitted = parser.append(contentChunk = "<think>Reasoning</think>Answer") + parser.flush()

        assertEquals(
            listOf(
                ApiState.Thinking("Reasoning"),
                ApiState.Success("Answer")
            ),
            emitted
        )
    }

    @Test
    fun `empty think block does not leave blank answer gap`() {
        val parser = GroqReasoningParser()

        val emitted = parser.append(contentChunk = "<think>   \n </think>\n\nAnswer") + parser.flush()

        assertEquals(
            listOf(
                ApiState.Success("Answer")
            ),
            emitted
        )
    }

    @Test
    fun `split think tags across chunks are reconstructed`() {
        val parser = GroqReasoningParser()

        val emitted = buildList {
            addAll(parser.append(contentChunk = "<thi"))
            addAll(parser.append(contentChunk = "nk>Plan"))
            addAll(parser.append(contentChunk = "</thi"))
            addAll(parser.append(contentChunk = "nk> Answer"))
            addAll(parser.flush())
        }

        assertEquals(
            listOf(
                ApiState.Thinking("Plan"),
                ApiState.Success("Answer")
            ),
            emitted
        )
    }

    @Test
    fun `unclosed think block falls back to visible text`() {
        val parser = GroqReasoningParser()

        val emitted = parser.append(contentChunk = "<think>unfinished") + parser.flush()

        assertEquals(
            listOf(
                ApiState.Success("<think>unfinished")
            ),
            emitted
        )
    }
}
