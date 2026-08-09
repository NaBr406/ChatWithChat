package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.ProviderUsage
import cn.nabr.chatwithchat.data.model.ClientType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-side semantic compaction.
 *
 * The service owns one decision — should this batch be turned into checkpoint text, and what happens
 * when that fails — and it must never do anything else. Several of these tests assert absences: no
 * tools, no persistence, exactly one call. Those are the properties that would silently rot, because
 * nothing about a passing "it summarized the turns" test would notice a second call appearing or a
 * write sneaking in.
 *
 * Every failure path is asserted to carry a fallback summary. That is the "nothing is silently
 * discarded" rule at its most load-bearing: when compaction is down, the extractive summary is the
 * only thing standing between the user and turns that vanish.
 */
class ContextCompactionServiceTest {

    @Test
    fun `an empty batch is not an error and produces no fallback`() = runBlocking {
        val client = RecordingClient()
        val outcome = ContextCompactionService(client).compact(
            platform = platform(),
            previousCheckpointBody = null,
            turns = emptyList(),
            budget = BUDGET
        )

        val failed = outcome as ContextCompactionOutcome.Failed
        assertEquals(ContextCompactionFailure.NOTHING_TO_COMPACT, failed.reason)
        // Nothing to summarize means nothing to degrade to; a fallback here would be fabricated text.
        assertNull(failed.fallbackSummary)
        assertEquals(0, client.calls.size)
    }

    @Test
    fun `a natively compacting model is never sent the app side prompt`() = runBlocking {
        val client = RecordingClient()
        val outcome = ContextCompactionService(client).compact(
            platform = platform(clientType = ClientType.OPENAI, model = "gpt-5.3"),
            previousCheckpointBody = null,
            turns = turns(3),
            budget = BUDGET
        )

        val failed = outcome as ContextCompactionOutcome.Failed
        assertEquals(ContextCompactionFailure.NATIVE_PATH_REQUIRED, failed.reason)
        assertNotNull(failed.fallbackSummary)
        assertEquals(0, client.calls.size)
    }

    @Test
    fun `a provider missing its credentials is reported as not eligible`() = runBlocking {
        val client = RecordingClient()
        val service = ContextCompactionService(client)

        listOf(
            platform(token = null),
            platform(token = "  "),
            platform(model = ""),
            platform(apiUrl = "")
        ).forEach { platform ->
            val outcome = service.compact(platform, null, turns(2), BUDGET)

            val failed = outcome as ContextCompactionOutcome.Failed
            assertEquals(ContextCompactionFailure.PROVIDER_NOT_ELIGIBLE, failed.reason)
            assertNotNull(failed.fallbackSummary)
        }
        assertEquals(0, client.calls.size)
    }

    @Test
    fun `ollama needs no token to be eligible`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse(COMPACTED_BODY)))

        val outcome = ContextCompactionService(client).compact(
            platform = platform(clientType = ClientType.OLLAMA, token = null, model = "qwen3"),
            previousCheckpointBody = null,
            turns = turns(2),
            budget = BUDGET
        )

        assertTrue(outcome is ContextCompactionOutcome.Compacted)
        assertEquals(1, client.calls.size)
    }

    @Test
    fun `a successful fold returns the body exactly once`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse(COMPACTED_BODY)))

        val outcome = ContextCompactionService(client).compact(
            platform = platform(),
            previousCheckpointBody = "上一段检查点",
            turns = turns(3),
            budget = BUDGET
        )

        val compacted = outcome as ContextCompactionOutcome.Compacted
        assertEquals(COMPACTED_BODY, compacted.body)
        assertTrue(compacted.estimatedTokens > 0)
        assertTrue(compacted.followedFormat)
        // One isolated call. Compaction never enters the tool loop and never recurses into itself.
        assertEquals(1, client.calls.size)
    }

    @Test
    fun `the payload carries the previous checkpoint and every turn being folded`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse(COMPACTED_BODY)))

        ContextCompactionService(client).compact(
            platform = platform(),
            previousCheckpointBody = "上一段检查点",
            turns = turns(3),
            budget = BUDGET
        )

        val call = client.calls.single()
        assertTrue(call.userPayload.contains("上一段检查点"))
        // Coverage over fidelity: a turn missing from the payload is a turn the checkpoint can never
        // mention again.
        (1..3).forEach { index ->
            assertTrue("turn $index missing from payload", call.userPayload.contains("[轮次 $index]"))
        }
        assertTrue(call.systemPrompt.contains(ContextCompactionPrompt.SYSTEM_PROMPT))
        assertTrue(call.maxOutputTokens > 0)
    }

    @Test
    fun `a first fold declares that it has no previous checkpoint`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse(COMPACTED_BODY)))

        ContextCompactionService(client).compact(platform(), null, turns(2), BUDGET)

        assertTrue(client.calls.single().userPayload.contains("这是本次会话的第一次压缩"))
    }

    @Test
    fun `a call failure degrades to the extractive summary`() = runBlocking {
        val client = RecordingClient(response = Result.failure(IOException("connection reset by peer")))

        val outcome = ContextCompactionService(client).compact(platform(), null, turns(3), BUDGET)

        val failed = outcome as ContextCompactionOutcome.Failed
        assertEquals(ContextCompactionFailure.CALL_FAILED, failed.reason)
        assertNotNull(failed.fallbackSummary)
        assertEquals("connection reset by peer", failed.detail)
    }

    @Test
    fun `a very long provider error is truncated before it is stored`() = runBlocking {
        val client = RecordingClient(response = Result.failure(IOException("x".repeat(4_000))))

        val outcome = ContextCompactionService(client).compact(platform(), null, turns(2), BUDGET)

        val failed = outcome as ContextCompactionOutcome.Failed
        assertEquals(500, failed.detail?.length)
    }

    @Test
    fun `a timeout is recoverable and degrades rather than propagating`() = runBlocking {
        // The timeout is raised at the seam rather than by letting the real deadline elapse. The
        // module has no kotlinx-coroutines-test dependency, so there is no virtual clock to advance,
        // and the compaction floor is 90 seconds — a test that waited it out would be unusable. What
        // matters here is the branch, not who started the stopwatch: a TimeoutCancellationException
        // surfacing from the call must degrade, not propagate.
        val client = RecordingClient(raiseTimeout = true)

        val outcome = ContextCompactionService(client).compact(
            platform = platform(timeout = 120),
            previousCheckpointBody = null,
            turns = turns(3),
            budget = BUDGET
        )

        val failed = outcome as ContextCompactionOutcome.Failed
        assertEquals(ContextCompactionFailure.TIMED_OUT, failed.reason)
        assertNotNull(failed.fallbackSummary)
    }

    @Test
    fun `an outer cancellation is not swallowed as a timeout`() {
        val client = RecordingClient(raiseCancellation = true)

        // TimeoutCancellationException is caught; CancellationException at large must not be. The
        // caller being gone is not a condition to degrade around — swallowing it would break
        // structured concurrency and leave a cancelled scope believing the fold succeeded.
        var propagated = false
        try {
            runBlocking {
                ContextCompactionService(client).compact(platform(), null, turns(2), BUDGET)
            }
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
    }

    @Test
    fun `a compaction timeout floor is applied over a short chat timeout`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse(COMPACTED_BODY)))

        ContextCompactionService(client).compact(platform(timeout = 30), null, turns(2), BUDGET)

        // Compaction reads a long payload before it writes anything, so a first-token timeout is the
        // wrong shape for it.
        assertEquals(90, client.calls.single().timeoutSeconds)
    }

    @Test
    fun `a deliberately longer provider timeout is honoured`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse(COMPACTED_BODY)))

        ContextCompactionService(client).compact(platform(timeout = 600), null, turns(2), BUDGET)

        assertEquals(600, client.calls.single().timeoutSeconds)
    }

    @Test
    fun `a zero timeout means no limit`() = runBlocking {
        val client = RecordingClient(
            response = Result.success(ContextCompactionModelResponse(COMPACTED_BODY)),
            delayMillis = 50
        )

        val outcome = ContextCompactionService(client).compact(
            platform = platform(timeout = 0),
            previousCheckpointBody = null,
            turns = turns(2),
            budget = BUDGET
        )

        assertTrue(outcome is ContextCompactionOutcome.Compacted)
        assertEquals(0, client.calls.single().timeoutSeconds)
    }

    @Test
    fun `output with no usable content is rejected`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse("  ok  ")))

        val outcome = ContextCompactionService(client).compact(platform(), null, turns(3), BUDGET)

        val failed = outcome as ContextCompactionOutcome.Failed
        assertEquals(ContextCompactionFailure.INVALID_OUTPUT, failed.reason)
        assertNotNull(failed.fallbackSummary)
    }

    @Test
    fun `prose that ignored the requested sections is kept and flagged`() = runBlocking {
        val prose = "用户想把导出功能改成后台任务，已确认使用 WorkManager，尚未决定失败重试次数。"
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse(prose)))

        val outcome = ContextCompactionService(client).compact(platform(), null, turns(3), BUDGET)

        // Losing a usable body over a missing header would be strictly worse than the extractive
        // fallback it would degrade to.
        val compacted = outcome as ContextCompactionOutcome.Compacted
        assertEquals(prose, compacted.body)
        assertFalse(compacted.followedFormat)
    }

    @Test
    fun `code fences the prompt forbade are stripped rather than rejected`() = runBlocking {
        val fenced = "```markdown\n$COMPACTED_BODY\n```"
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse(fenced)))

        val outcome = ContextCompactionService(client).compact(platform(), null, turns(2), BUDGET)

        val compacted = outcome as ContextCompactionOutcome.Compacted
        assertFalse(compacted.body.contains("```"))
        assertTrue(compacted.body.contains("## 目标与约束"))
    }

    @Test
    fun `provider reported usage is recorded against the compaction label`() = runBlocking {
        val client = RecordingClient(
            response = Result.success(
                ContextCompactionModelResponse(
                    content = COMPACTED_BODY,
                    usage = ProviderUsage(promptTokens = 1_200, completionTokens = 300, totalTokens = 1_500)
                )
            )
        )

        val outcome = ContextCompactionService(client).compact(platform(), null, turns(3), BUDGET)

        val usage = (outcome as ContextCompactionOutcome.Compacted).usage
        assertNotNull(usage)
        assertEquals(1_200, usage!!.inputTokens)
        assertEquals(300, usage.outputTokens)
        assertEquals(1_500, usage.totalTokens)
        assertFalse(usage.isEstimated)
        // Compaction is real spend that belongs to no message row; leaving it unlabelled would make
        // the totals quietly understate what the feature costs.
        assertEquals("上下文压缩", usage.details.single().label)
        assertEquals(PLATFORM_UID, usage.platformUid)
    }

    @Test
    fun `usage is estimated when the provider reports none`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse(COMPACTED_BODY)))

        val outcome = ContextCompactionService(client).compact(platform(), null, turns(3), BUDGET)

        val usage = (outcome as ContextCompactionOutcome.Compacted).usage
        assertNotNull(usage)
        assertTrue(usage!!.isEstimated)
        assertTrue(usage.totalTokens > 0)
    }

    private data class RecordedCall(
        val platform: PlatformV2,
        val systemPrompt: String,
        val userPayload: String,
        val maxOutputTokens: Int,
        val timeoutSeconds: Int
    )

    /**
     * The only network seam compaction has. Note what it cannot express: there is no tool list, no
     * conversation history and no streaming callback, which is how "compaction never enters the tool
     * loop" is enforced structurally rather than by assertion.
     */
    private class RecordingClient(
        private val response: Result<ContextCompactionModelResponse> =
            Result.success(ContextCompactionModelResponse("")),
        private val delayMillis: Long = 0,
        private val raiseTimeout: Boolean = false,
        private val raiseCancellation: Boolean = false
    ) : ContextCompactionModelClient {
        val calls = mutableListOf<RecordedCall>()

        override suspend fun requestCompaction(
            platform: PlatformV2,
            systemPrompt: String,
            userPayload: String,
            maxOutputTokens: Int,
            timeoutSeconds: Int
        ): Result<ContextCompactionModelResponse> {
            calls += RecordedCall(platform, systemPrompt, userPayload, maxOutputTokens, timeoutSeconds)
            if (raiseCancellation) throw CancellationException("caller went away")
            // A genuine TimeoutCancellationException — the class cannot be constructed directly, and
            // a hand-rolled stand-in would not prove the real one is caught.
            if (raiseTimeout) withTimeout(1) { delay(Long.MAX_VALUE) }
            if (delayMillis > 0) delay(delayMillis)
            return response
        }
    }

    private fun turns(count: Int): List<ConversationTurn> = (1..count).map { index ->
        ConversationTurn(
            userMessage = MessageV2(id = index, content = "第 $index 轮的问题描述", platformType = null),
            assistantMessage = MessageV2(
                id = 1_000 + index,
                content = "第 $index 轮的回答内容",
                platformType = PLATFORM_UID
            ),
            isCurrentTurn = false
        )
    }

    private fun platform(
        clientType: ClientType = ClientType.CUSTOM,
        model: String = "custom-model",
        apiUrl: String = "https://example.com",
        token: String? = "token",
        timeout: Int = 120
    ) = PlatformV2(
        uid = PLATFORM_UID,
        name = "Provider",
        compatibleType = clientType,
        apiUrl = apiUrl,
        token = token,
        model = model,
        timeout = timeout
    )

    private companion object {
        const val PLATFORM_UID = "platform"

        val COMPACTED_BODY: String = """
            ## 目标与约束
            - 把导出功能改成后台任务。
            ## 已确认的决定
            - 使用 WorkManager。
            ## 未解决的问题
            - 失败重试次数未定。
            ## 关键结果与引用
            - 无
            ## 事实与推断的区分
            - 无
        """.trimIndent()

        val BUDGET: ContextBudget = ContextBudget.of(modelContextWindow = 32_768, providerSummaryTarget = 1_200)
    }
}
