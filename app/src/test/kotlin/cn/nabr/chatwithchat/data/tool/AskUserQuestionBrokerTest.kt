package cn.nabr.chatwithchat.data.tool

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserQuestionBrokerTest {
    @Test
    fun `broker resumes the waiting provider with normalized answers`() = runBlocking {
        val broker = AskUserQuestionBroker()
        val call = ToolCall("call-1", "ask_user_question", "{}")
        val pendingResult = async {
            broker.awaitAnswer(call, questions(), chatId = 7, turnKey = "turn-7")
        }
        val pending = withTimeout(1_000) {
            broker.queued
                .first { questions -> questions.any { it.requestId.isNotBlank() } }
                .first()
        }

        assertEquals(7, pending.chatId)
        assertTrue(
            broker.submit(
                pending.requestId,
                listOf(
                    AskUserQuestionAnswer(
                        header = " 范围 ",
                        selectedOptions = listOf("界面和数据层", " 界面和数据层 ")
                    )
                )
            )
        )

        val decision = pendingResult.await()
        assertTrue(decision is AskUserQuestionBroker.Decision.Answered)
        val answers = (decision as AskUserQuestionBroker.Decision.Answered).answers
        assertEquals(listOf("界面和数据层"), answers.single().selectedOptions)
        assertEquals("范围", answers.single().header)
        assertTrue(broker.queued.value.isEmpty())
    }

    @Test
    fun `invalid answer does not complete the pending question`() = runBlocking {
        val broker = AskUserQuestionBroker()
        val call = ToolCall("call-2", "ask_user_question", "{}")
        val pendingResult = async { broker.awaitAnswer(call, questions()) }
        val pending = withTimeout(1_000) {
            broker.queued
                .first { questions -> questions.any { it.callId == call.id } }
                .first { question -> question.callId == call.id }
        }

        assertFalse(
            broker.submit(
                pending.requestId,
                listOf(
                    AskUserQuestionAnswer(
                        header = "范围",
                        selectedOptions = listOf("不存在")
                    )
                )
            )
        )
        assertEquals(1, broker.queued.value.size)
        assertTrue(broker.cancel(pending.requestId))
        assertTrue(pendingResult.await() is AskUserQuestionBroker.Decision.Cancelled)
    }

    @Test
    fun `normalization rejects single select other conflict`() {
        val answer = normalizeAnswers(
            questions = questions(),
            answers = listOf(
                AskUserQuestionAnswer(
                    header = "范围",
                    selectedOptions = listOf("只改界面"),
                    otherText = "自定义"
                )
            )
        )

        assertNull(answer)
    }

    private fun questions(): List<AskUserQuestion> = listOf(
        AskUserQuestion(
            header = "范围",
            question = "这次需要修改哪些部分？",
            options = listOf(
                AskUserQuestionOption("只改界面", "只调整 Compose UI"),
                AskUserQuestionOption("界面和数据层", "同时修改 UI、ViewModel 和数据层")
            )
        )
    )
}
