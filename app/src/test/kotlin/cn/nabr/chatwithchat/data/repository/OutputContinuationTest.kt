package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputContinuationTest {
    @Test
    fun `continuation preserves exact code boundaries and sums each request usage once`() = runBlocking {
        val requests = mutableListOf<String>()
        lateinit var result: OutputContinuationRound<Unit>
        val states = flow {
            result = withOutputContinuation("question", { initial, text, _ -> "$initial:$text" }) { request ->
                requests += request
                emit(ApiState.Success(if (requests.size == 1) "```svg\n<sv" else "g/>\n```"))
                OutputContinuationRound(Unit, requests.size == 1, TokenUsageRecord(inputTokens = 10, outputTokens = 5))
            }
        }.toList()

        assertEquals(listOf("question", "question:```svg\n<sv"), requests)
        assertEquals("```svg\n<svg/>\n```", states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk })
        assertEquals(30, result.usage?.totalTokens)
        assertNull(result.errorMessage)
    }

    @Test
    fun `reconnect rolls back only failed segment before constructing next continuation`() = runBlocking {
        val requests = mutableListOf<String>()
        var attempts = 0
        flow {
            withOutputContinuation("question", { _, text, _ -> text }) { request ->
                requests += request
                withChatNetworkReconnect(waitBeforeRetry = {}) {
                    attempts++
                    if (attempts == 2) {
                        emit(ApiState.Success("discard"))
                        throw IOException("disconnect")
                    }
                    emit(ApiState.Success(if (attempts == 1) "A" else "B"))
                    OutputContinuationRound(Unit, truncated = attempts < 4)
                }
            }
        }.toList()

        assertEquals(listOf("question", "A", "AB"), requests)
        assertEquals(4, attempts)
    }

    @Test
    fun `empty truncated output stops without retry`() = runBlocking {
        var calls = 0
        lateinit var result: OutputContinuationRound<Unit>
        flow {
            result = withOutputContinuation(Unit, { _, _, _ -> Unit }) {
                calls++
                emit(ApiState.Thinking("reasoning only"))
                OutputContinuationRound(Unit, truncated = true)
            }
        }.toList()
        assertEquals(1, calls)
        assertTrue(result.errorMessage.orEmpty().contains("没有可接续"))
    }

    @Test
    fun `truncated tool calls never become text continuations`() = runBlocking {
        var calls = 0
        lateinit var result: OutputContinuationRound<Unit>
        flow {
            result = withOutputContinuation(Unit, { _, _, _ -> Unit }) {
                calls++
                emit(ApiState.Success("working"))
                OutputContinuationRound(Unit, truncated = true, hasToolCalls = true)
            }
        }.toList()
        assertEquals(1, calls)
        assertTrue(result.errorMessage.orEmpty().contains("工具调用参数"))
    }

    @Test
    fun `repeated output limits stop at bounded request count`() = runBlocking {
        var calls = 0
        lateinit var result: OutputContinuationRound<Unit>
        val states = flow {
            result = withOutputContinuation(Unit, { _, _, _ -> Unit }) {
                calls++
                emit(ApiState.Success("piece"))
                OutputContinuationRound(Unit, truncated = true)
            }
        }.toList()
        assertEquals(1 + MAX_OUTPUT_CONTINUATIONS, calls)
        assertEquals(calls, states.filterIsInstance<ApiState.Success>().size)
        assertTrue(result.errorMessage.orEmpty().contains("仍达到输出上限"))
    }

    @Test
    fun `errors do not trigger continuation even with truncated marker`() = runBlocking {
        var calls = 0
        lateinit var result: OutputContinuationRound<Unit>
        flow {
            result = withOutputContinuation(Unit, { _, _, _ -> Unit }) {
                calls++
                emit(ApiState.Success("partial"))
                OutputContinuationRound(Unit, truncated = true, errorMessage = "provider error")
            }
        }.toList()
        assertEquals(1, calls)
        assertEquals("provider error", result.errorMessage)
    }

    @Test
    fun `user cancellation propagates without further requests`() = runBlocking {
        var calls = 0
        var cancelled = false
        try {
            flow {
                withOutputContinuation<Unit, Unit>(Unit, { _, _, _ -> Unit }) {
                    calls++
                    emit(ApiState.Success("partial"))
                    throw CancellationException("stop")
                }
            }.toList()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(1, calls)
    }

    @Test
    fun `normal stop performs one request`() = runBlocking {
        var calls = 0
        lateinit var result: OutputContinuationRound<Unit>
        flow {
            result = withOutputContinuation(Unit, { _, _, _ -> Unit }) {
                calls++
                emit(ApiState.Success("complete"))
                OutputContinuationRound(Unit)
            }
        }.toList()
        assertEquals(1, calls)
        assertFalse(result.truncated)
        assertNull(result.errorMessage)
    }
}
