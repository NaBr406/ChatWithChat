package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.dto.ApiState
import org.junit.Assert.assertEquals
import org.junit.Test

class LeakedToolCallMarkupFilterTest {
    @Test
    fun `delimiter wrapped envelope is diverted out of the visible answer`() {
        assertFiltered(
            text = "大哥，我去看看。<|DSML|tool_calls> <|DSML|invoke name=\"mcp_github_get_file_contents\">",
            expectedVisible = "大哥，我去看看。",
            expectedThinking = "<|DSML|tool_calls> <|DSML|invoke name=\"mcp_github_get_file_contents\">"
        )
    }

    /**
     * The form actually observed in the wild, which the first version of the filter missed entirely: the
     * delimiter is a doubled pipe, not a single one.
     */
    @Test
    fun `doubled pipe delimiters are recognised`() {
        val envelope = "<||DSML||tool_calls> <||DSML||invoke name=\"mcp_github_get_file_contents\"> " +
            "<||DSML||parameter name=\"owner\" string=\"true\">NaBr406</||DSML||parameter>"

        assertFiltered(
            text = "找到了你的仓库。$envelope",
            expectedVisible = "找到了你的仓库。",
            expectedThinking = envelope
        )
    }

    @Test
    fun `closing envelope tags are recognised on their own`() {
        listOf("</||DSML||parameter>", "</||DSML||invoke>", "</|DSML|tool_calls>").forEach { envelope ->
            assertFiltered(
                text = "text $envelope",
                expectedVisible = "text ",
                expectedThinking = envelope
            )
        }
    }

    @Test
    fun `fullwidth pipe delimiters are recognised`() {
        assertFiltered(
            text = "先看看仓库。<｜DSML｜parameter name=\"fields\" string=\"false\">",
            expectedVisible = "先看看仓库。",
            expectedThinking = "<｜DSML｜parameter name=\"fields\" string=\"false\">"
        )
    }

    @Test
    fun `special token style tool call openings are recognised`() {
        listOf(
            "<｜tool▁calls▁begin｜>",
            "<|tool_calls_begin|>",
            "<|tool_call_begin|>",
            "<|tool_calls|>"
        ).forEach { envelope ->
            assertFiltered(
                text = "查一下。$envelope",
                expectedVisible = "查一下。",
                expectedThinking = envelope
            )
        }
    }

    @Test
    fun `bare protocol tags are recognised`() {
        listOf("<invoke name=\"f\">", "<tool_call>", "<tool_calls>", "<function_call>").forEach { envelope ->
            assertFiltered(
                text = "Working on it. $envelope",
                expectedVisible = "Working on it. ",
                expectedThinking = envelope
            )
        }
    }

    @Test
    fun `everything after the envelope stays out of the answer`() {
        assertFiltered(
            text = "Answer so far.<tool_call>{\"a\":1}</tool_call>and more prose after it",
            expectedVisible = "Answer so far.",
            expectedThinking = "<tool_call>{\"a\":1}</tool_call>and more prose after it"
        )
    }

    @Test
    fun `ordinary markup and prose pass through untouched`() {
        listOf(
            "see <div>x</div> and <span>y</span>",
            "<html><body><p>hi</p></body></html>",
            "<br/> plus <img src=\"a.png\"/>",
            "if a<b and c<d then swap",
            "a comparison ending in a bare <",
            "**bold** `code` [link](url)\n- item\n- item",
            "I will invoke name resolution on the host",
            "the tool_call field is where arguments live",
            "just some text with no markup at all",
            // `<parameter>` and `<invoke>` without a delimiter or a name= attribute stay renderable.
            "<parameter>generic xml</parameter>",
            "<invoke>bare</invoke>",
            "a pipe table: | a | b |",
            "a < b | c > d"
        ).forEach { text ->
            assertFiltered(text = text, expectedVisible = text, expectedThinking = "")
        }
    }

    @Test
    fun `reasoning tags are left for the reasoning parser`() {
        val text = "<think>plan</think>answer"

        assertFiltered(text = text, expectedVisible = text, expectedThinking = "")
    }

    @Test
    fun `suppressed markup is diverted whole rather than partly shown`() {
        assertFiltered(
            text = "prose<tool_call>{\"name\":\"x\"}",
            expectedVisible = "prose",
            expectedThinking = "<tool_call>{\"name\":\"x\"}"
        )
    }

    @Test
    fun `nothing is emitted for empty chunks or an exhausted filter`() {
        val filter = LeakedToolCallMarkupFilter()

        assertEquals(emptyList<ApiState>(), filter.append(""))
        assertEquals(emptyList<ApiState>(), filter.flush())
    }

    /**
     * Asserts the outcome holds however the provider happens to slice the stream.
     *
     * A sentinel split across chunks is the case that matters: the filter has to hold back just enough of
     * the tail to still recognise `<|DSM` + `L|tool_calls>` as one envelope, without stalling a `<div>`.
     */
    private fun assertFiltered(
        text: String,
        expectedVisible: String,
        expectedThinking: String
    ) {
        assertFiltered(text, expectedVisible, expectedThinking, listOf(text))
        assertFiltered(text, expectedVisible, expectedThinking, text.map(Char::toString))
        for (splitIndex in 1 until text.length) {
            assertFiltered(
                text,
                expectedVisible,
                expectedThinking,
                listOf(text.substring(0, splitIndex), text.substring(splitIndex))
            )
        }
    }

    private fun assertFiltered(
        text: String,
        expectedVisible: String,
        expectedThinking: String,
        chunks: List<String>
    ) {
        val filter = LeakedToolCallMarkupFilter()
        val emitted = buildList {
            chunks.forEach { chunk -> addAll(filter.append(chunk)) }
            addAll(filter.flush())
        }

        assertEquals(
            "visible text for [$text] chunked as $chunks",
            expectedVisible,
            emitted.filterIsInstance<ApiState.Success>().joinToString(separator = "") { it.textChunk }
        )
        assertEquals(
            "thinking text for [$text] chunked as $chunks",
            expectedThinking,
            emitted.filterIsInstance<ApiState.Thinking>().joinToString(separator = "") { it.thinkingChunk }
        )
    }
}
