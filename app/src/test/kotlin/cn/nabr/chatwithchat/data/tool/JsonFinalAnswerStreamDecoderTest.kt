package cn.nabr.chatwithchat.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonFinalAnswerStreamDecoderTest {
    @Test
    fun `every split preserves escaped strings and unicode without exposing the envelope`() {
        val envelope = """{"type":"final_answer","content":"<ui-card title=\"计划\">\n\u4F60\u597D \uD83D\uDE00 \\ \/ \b\f\r\t\n</ui-card>"}"""
        val expected = "<ui-card title=\"计划\">\n你好 😀 \\ / \b\u000C\r\t\n</ui-card>"
        for (split in 0..envelope.length) {
            val decoder = JsonFinalAnswerStreamDecoder()
            assertEquals("split=$split", expected, decoder.append(envelope.take(split)) + decoder.append(envelope.drop(split)))
            assertEquals(expected, decoder.content)
        }
        val decoder = JsonFinalAnswerStreamDecoder()
        assertEquals(expected, envelope.map { decoder.append(it.toString()) }.joinToString(""))
    }

    @Test
    fun `body appears before closing string and partial escapes stay private`() {
        val decoder = JsonFinalAnswerStreamDecoder()
        assertEquals("Hello", decoder.append(""" { "type" : "final_answer", "content" : "Hello\uD83D"""))
        assertEquals("", decoder.append("\\uDE"))
        assertEquals("😀\n", decoder.append("00\\n"))
        assertEquals("", decoder.append("\"}"))
    }

    @Test
    fun `tool arguments and unconfirmed field order remain buffered`() {
        listOf(
            """{"type":"tool_calls","tool_calls":[{"arguments":{"content":"secret"}}]}""",
            """{"content":"buffer until parsed","type":"final_answer"}""",
            """{"other":{"type":"final_answer","content":"nested"}}""",
            """{"type":"final_answer","other":"ignore","content":"buffer until parsed"}"""
        ).forEach { envelope ->
            val decoder = JsonFinalAnswerStreamDecoder()
            assertEquals("", envelope.map { decoder.append(it.toString()) }.joinToString(""))
        }
    }

    @Test
    fun `invalid escape stops preview rather than exposing raw protocol`() {
        val decoder = JsonFinalAnswerStreamDecoder()
        assertEquals("prefix", decoder.append("""{"type":"final_answer","content":"prefix\qprivate"}"""))
        assertEquals("", decoder.append("more"))
        assertTrue(decoder.content == "prefix")
    }

    @Test
    fun `decoded leading and trailing answer whitespace is preserved`() {
        val decoder = JsonFinalAnswerStreamDecoder()
        assertEquals(" \nAnswer\t ", decoder.append("""{"type":"final_answer","content":" \nAnswer\t "}"""))
    }
}
