package dev.chungjungsoo.gptmobile.data.websearch

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDecisionServiceTest {

    @Test
    fun `time sensitive question can trigger search decision`() = runBlocking {
        val client = RecordingDecisionClient(
            Result.success(
                """{"shouldSearch":true,"queries":["latest Android target SDK 2026"],"reason":"current version"}"""
            )
        )
        val service = SearchDecisionService(client)

        val decision = service.decide(
            platform = platform(),
            latestUserMessage = "What is the latest Android target SDK?",
            recentContext = "User: We are updating an Android app."
        )

        assertTrue(decision.shouldSearch)
        assertEquals(listOf("latest Android target SDK 2026"), decision.queries)
        assertTrue(client.lastPrompt.contains("Latest user message"))
        assertTrue(client.lastPrompt.contains("We are updating an Android app."))
    }

    @Test
    fun `casual chat can skip search decision`() = runBlocking {
        val service = SearchDecisionService(
            RecordingDecisionClient(
                Result.success("""{"shouldSearch":false,"queries":[],"reason":"casual chat"}""")
            )
        )

        val decision = service.decide(
            platform = platform(),
            latestUserMessage = "Translate hello to Spanish.",
            recentContext = null
        )

        assertFalse(decision.shouldSearch)
        assertTrue(decision.queries.isEmpty())
    }

    @Test
    fun `invalid json defaults to no search`() = runBlocking {
        val service = SearchDecisionService(RecordingDecisionClient(Result.success("not json")))

        val decision = service.decide(platform(), "What happened today?", null)

        assertFalse(decision.shouldSearch)
        assertTrue(decision.queries.isEmpty())
    }

    @Test
    fun `too many queries are clipped`() = runBlocking {
        val service = SearchDecisionService(
            RecordingDecisionClient(
                Result.success(
                    """{"shouldSearch":true,"queries":["one","two","three"],"reason":"needs current data"}"""
                )
            )
        )

        val decision = service.decide(platform(), "What are today's AI headlines?", null)

        assertTrue(decision.shouldSearch)
        assertEquals(listOf("one", "two"), decision.queries)
    }

    @Test
    fun `decision request failure defaults to no search`() = runBlocking {
        val service = SearchDecisionService(
            RecordingDecisionClient(Result.failure(IllegalStateException("provider failed")))
        )

        val decision = service.decide(platform(), "What happened today?", null)

        assertFalse(decision.shouldSearch)
        assertTrue(decision.queries.isEmpty())
    }

    private fun platform() = PlatformV2(
        name = "Custom",
        compatibleType = ClientType.CUSTOM,
        apiUrl = "https://example.test",
        model = "custom-model"
    )

    private class RecordingDecisionClient(
        private val result: Result<String>
    ) : SearchDecisionModelClient {
        var lastPrompt: String = ""

        override suspend fun requestDecision(platform: PlatformV2, prompt: String): Result<String> {
            lastPrompt = prompt
            return result
        }
    }
}

