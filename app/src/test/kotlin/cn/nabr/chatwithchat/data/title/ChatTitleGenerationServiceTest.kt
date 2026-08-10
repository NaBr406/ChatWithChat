package cn.nabr.chatwithchat.data.title

import cn.nabr.chatwithchat.data.ModelConstants
import cn.nabr.chatwithchat.data.context.ContextCompactionModelClient
import cn.nabr.chatwithchat.data.context.ContextCompactionModelResponse
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LLM chat-title generation.
 *
 * The rule that matters most here is that nothing this service does may ever reach the user as an
 * error: a title is cosmetic, and the caller already holds the truncated first message. So every
 * failure path is asserted to return `null` rather than throw — with genuine cancellation as the one
 * deliberate exception.
 *
 * The other property under test is that the model is never chosen here. The platform the caller
 * passes is the platform that gets called, which is what makes "the title model is the chat model"
 * structural rather than a setting that can drift.
 */
class ChatTitleGenerationServiceTest {

    @Test
    fun `the platform passed in is the platform called`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse("导出功能改后台任务")))
        val platform = platform(model = "qwen3:8b", clientType = ClientType.OLLAMA, token = null)

        val title = ChatTitleGenerationService(client).generateTitle(platform, turns())

        assertEquals("导出功能改后台任务", title)
        // A chat running on a local model must not quietly bill a cloud key to name itself.
        assertEquals("qwen3:8b", client.calls.single().platform.model)
        assertEquals(PLATFORM_UID, client.calls.single().platform.uid)
    }

    @Test
    fun `the dead constant is the prompt actually sent`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse("标题")))

        ChatTitleGenerationService(client).generateTitle(platform(), turns())

        // Wiring up this specific constant is the point of the path; re-wording it here would leave
        // the original just as dead as it was.
        val payload = client.calls.single().userPayload
        assertTrue(payload.startsWith(ModelConstants.CHAT_TITLE_GENERATE_PROMPT))
        assertTrue(payload.contains("用户: 帮我把导出功能改成后台任务"))
        assertTrue(payload.contains("助手: 可以用 WorkManager"))
    }

    @Test
    fun `a provider missing its credentials is never called`() = runBlocking {
        val client = RecordingClient()
        val service = ChatTitleGenerationService(client)

        listOf(
            platform(token = null),
            platform(token = "  "),
            platform(model = ""),
            platform(apiUrl = "")
        ).forEach { unusable ->
            assertNull(service.generateTitle(unusable, turns()))
        }

        // Ollama, the one provider that legitimately has no token, is covered above.
        assertEquals(0, client.calls.size)
    }

    @Test
    fun `a transport failure degrades to no title`() = runBlocking {
        val client = RecordingClient(response = Result.failure(IOException("network down")))

        assertNull(ChatTitleGenerationService(client).generateTitle(platform(), turns()))
    }

    @Test
    fun `a timeout degrades rather than propagating`() = runBlocking {
        // Raised at the seam rather than by waiting out the real deadline: what matters is that a
        // TimeoutCancellationException surfacing from the call degrades instead of propagating.
        val client = RecordingClient(raiseTimeout = true)

        assertNull(ChatTitleGenerationService(client).generateTitle(platform(), turns()))
    }

    @Test
    fun `an outer cancellation is not swallowed`() {
        val client = RecordingClient(raiseCancellation = true)

        // If the scope is gone there is nobody left to name the chat for. Swallowing this would break
        // structured concurrency.
        var propagated = false
        try {
            runBlocking { ChatTitleGenerationService(client).generateTitle(platform(), turns()) }
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
    }

    @Test
    fun `an empty conversation is not sent`() = runBlocking {
        val client = RecordingClient()

        assertNull(ChatTitleGenerationService(client).generateTitle(platform(), emptyList()))
        assertNull(
            ChatTitleGenerationService(client).generateTitle(
                platform(),
                listOf(ChatTitleTurn(userMessage = "   ", assistantMessage = null))
            )
        )
        assertEquals(0, client.calls.size)
    }

    @Test
    fun `a title is still generated before the assistant has answered`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse("导出功能")))

        val title = ChatTitleGenerationService(client).generateTitle(
            platform(),
            listOf(ChatTitleTurn(userMessage = "帮我把导出功能改成后台任务", assistantMessage = null))
        )

        assertEquals("导出功能", title)
    }

    @Test
    fun `the title call gets a short leash regardless of a generous chat timeout`() = runBlocking {
        val client = RecordingClient(response = Result.success(ContextCompactionModelResponse("标题")))

        ChatTitleGenerationService(client).generateTitle(platform(timeout = 600), turns())

        // Naming a chat is the lowest-value call the app makes; it must not hold a coroutine for ten
        // minutes because the user configured a long chat timeout.
        assertEquals(30, client.calls.single().timeoutSeconds)
    }

    @Test
    fun `a zero timeout means no limit`() = runBlocking {
        val client = RecordingClient(
            response = Result.success(ContextCompactionModelResponse("标题")),
            delayMillis = 50
        )

        val title = ChatTitleGenerationService(client).generateTitle(platform(timeout = 0), turns())

        assertEquals("标题", title)
        assertEquals(0, client.calls.single().timeoutSeconds)
    }

    @Test
    fun `output with nothing usable in it produces no title`() = runBlocking {
        listOf("", "   ", "\n\n", "<think>让我想想</think>").forEach { unusable ->
            val client = RecordingClient(response = Result.success(ContextCompactionModelResponse(unusable)))
            assertNull(ChatTitleGenerationService(client).generateTitle(platform(), turns()))
        }
    }

    private data class RecordedCall(
        val platform: PlatformV2,
        val systemPrompt: String,
        val userPayload: String,
        val maxOutputTokens: Int,
        val timeoutSeconds: Int
    )

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
            // A genuine TimeoutCancellationException; the class cannot be constructed directly.
            if (raiseTimeout) withTimeout(1) { delay(Long.MAX_VALUE) }
            if (delayMillis > 0) delay(delayMillis)
            return response
        }
    }

    private fun turns(): List<ChatTitleTurn> = listOf(
        ChatTitleTurn(
            userMessage = "帮我把导出功能改成后台任务",
            assistantMessage = "可以用 WorkManager 来做。"
        )
    )

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
    }
}
