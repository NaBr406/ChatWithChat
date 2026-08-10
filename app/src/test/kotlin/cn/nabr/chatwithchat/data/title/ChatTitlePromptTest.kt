package cn.nabr.chatwithchat.data.title

import cn.nabr.chatwithchat.data.ModelConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload assembly and output cleanup for chat titles.
 *
 * Almost every case here is a real way models answer this prompt despite being told not to: a
 * Markdown heading, a "标题：" label, surrounding quotes, a `<think>` block, or a title followed by an
 * explanation. The instruction alone does not hold, so the parser has to.
 */
class ChatTitlePromptTest {

    @Test
    fun `the payload is the shipped constant plus the transcript`() {
        val payload = ChatTitlePrompt.buildUserPayload(
            listOf(ChatTitleTurn(userMessage = "怎么用 Room 做迁移", assistantMessage = "先写 Migration。"))
        )

        assertEquals(
            ModelConstants.CHAT_TITLE_GENERATE_PROMPT + "用户: 怎么用 Room 做迁移\n助手: 先写 Migration。",
            payload
        )
    }

    @Test
    fun `a conversation with nothing in it produces no payload`() {
        assertNull(ChatTitlePrompt.buildUserPayload(emptyList()))
        assertNull(ChatTitlePrompt.buildUserPayload(listOf(ChatTitleTurn("   ", "  "))))
    }

    @Test
    fun `long messages are truncated so naming a chat cannot cost a full context window`() {
        val payload = ChatTitlePrompt.buildUserPayload(
            listOf(ChatTitleTurn(userMessage = "长".repeat(5_000), assistantMessage = "答".repeat(5_000)))
        )

        assertTrue(payload!!.length < ModelConstants.CHAT_TITLE_GENERATE_PROMPT.length + 2_100)
    }

    @Test
    fun `only the first turns are sent`() {
        val payload = ChatTitlePrompt.buildUserPayload(
            (1..10).map { ChatTitleTurn(userMessage = "第 $it 个问题", assistantMessage = "第 $it 个回答") }
        )

        // A title describes what the chat is about, which is established at the start. Sending all of
        // it would make the cost grow with the conversation for no better title.
        assertTrue(payload!!.contains("第 1 个问题"))
        assertTrue(payload.contains("第 2 个问题"))
        assertFalse(payload.contains("第 3 个问题"))
    }

    @Test
    fun `a plain title survives untouched`() {
        assertEquals("Room 数据库迁移方案", ChatTitlePrompt.parse("Room 数据库迁移方案"))
    }

    @Test
    fun `markdown the prompt forbade is stripped rather than rejected`() {
        assertEquals("导出功能改造", ChatTitlePrompt.parse("## 导出功能改造"))
        assertEquals("导出功能改造", ChatTitlePrompt.parse("- 导出功能改造"))
        assertEquals("导出功能改造", ChatTitlePrompt.parse("**导出功能改造**"))
        assertEquals("导出功能改造", ChatTitlePrompt.parse("> 导出功能改造"))
    }

    @Test
    fun `a label the model added is removed`() {
        assertEquals("导出功能改造", ChatTitlePrompt.parse("标题：导出功能改造"))
        assertEquals("导出功能改造", ChatTitlePrompt.parse("Title: 导出功能改造"))
        assertEquals("导出功能改造", ChatTitlePrompt.parse("## 标题：导出功能改造"))
    }

    @Test
    fun `surrounding quotes are removed`() {
        assertEquals("导出功能改造", ChatTitlePrompt.parse("\"导出功能改造\""))
        assertEquals("导出功能改造", ChatTitlePrompt.parse("“导出功能改造”"))
        assertEquals("导出功能改造", ChatTitlePrompt.parse("「导出功能改造」"))
    }

    @Test
    fun `a reasoning block is not mistaken for the title`() {
        assertEquals(
            "导出功能改造",
            ChatTitlePrompt.parse("<think>用户在问导出功能，应该起一个简短的标题</think>\n导出功能改造")
        )
        assertEquals(
            "导出功能改造",
            ChatTitlePrompt.parse("<thinking>想一下</thinking>导出功能改造")
        )
    }

    @Test
    fun `an unclosed reasoning block leaves nothing usable`() {
        // Truncated mid-thought by the output cap. The thought text is not a title, and returning it
        // would be worse than keeping the truncated first message.
        assertNull(ChatTitlePrompt.parse("<think>用户想要一个标题，我应该先分析一下这段对话的主题"))
    }

    @Test
    fun `an explanation after the title is dropped`() {
        assertEquals(
            "导出功能改造",
            ChatTitlePrompt.parse("导出功能改造\n\n这个标题概括了用户想把导出改成后台任务的需求。")
        )
    }

    @Test
    fun `a title longer than the column allows is truncated`() {
        val parsed = ChatTitlePrompt.parse("很长的标题".repeat(50))

        assertEquals(ChatTitlePrompt.MAX_TITLE_LENGTH, parsed!!.length)
    }

    @Test
    fun `output with nothing usable in it is rejected`() {
        listOf("", "   ", "\n\n", "###", "\"\"").forEach { unusable ->
            assertNull(ChatTitlePrompt.parse(unusable))
        }
    }
}
