package cn.nabr.chatwithchat.data.websearch

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.ProviderUsage
import cn.nabr.chatwithchat.data.dto.openai.common.TextContent
import cn.nabr.chatwithchat.data.model.ClientType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDecisionServiceTest {

    @Test
    fun `time sensitive question can trigger search decision`() = runBlocking {
        val client = RecordingDecisionClient(
            Result.success(
                response("""{"shouldSearch":true,"queries":["latest Android target SDK 2026"],"reason":"current version"}""")
            )
        )
        val service = SearchDecisionService(client)

        val decision = service.decide(
            platform = platform(),
            latestUserMessage = "What is the latest Android target SDK?",
            recentContext = "User: We are updating an Android app.",
            runtimeContext = "Current local date/time: 2026-07-02T16:00:00+08:00 (Asia/Shanghai)."
        )

        assertTrue(decision.shouldSearch)
        assertEquals(listOf("latest Android target SDK 2026"), decision.queries)
        assertTrue(client.lastPrompt.contains("用户最新消息"))
        assertTrue(client.lastPrompt.contains("We are updating an Android app."))
        assertTrue(client.lastPrompt.contains("运行时上下文"))
        assertTrue(client.lastPrompt.contains("2026-07-02"))
        assertTrue(client.lastPrompt.contains("将用户的自然语言请求改写为搜索引擎 query"))
        assertTrue(client.lastPrompt.contains("\"shouldSearch\""))
        assertTrue(client.lastPrompt.contains("不得翻译 JSON key"))
    }

    @Test
    fun `casual chat can skip search decision`() = runBlocking {
        val service = SearchDecisionService(
            RecordingDecisionClient(
                Result.success(response("""{"shouldSearch":false,"queries":[],"reason":"casual chat"}"""))
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
    fun `false decision preserves the actual request prompt and usage`() = runBlocking {
        val service = SearchDecisionService(
            RecordingDecisionClient(
                Result.success(
                    response(
                        content = """{"shouldSearch":false,"queries":[],"reason":"not needed"}""",
                        usage = ProviderUsage(promptTokens = 11, completionTokens = 3, totalTokens = 14)
                    )
                )
            )
        )

        val outcome = service.decideWithUsage(
            platform = platform(),
            latestUserMessage = "Explain this without current facts.",
            recentContext = null
        )

        assertFalse(outcome.decision.shouldSearch)
        assertTrue(outcome.wasRequested)
        assertTrue(outcome.requestPrompt.orEmpty().contains("Explain this without current facts."))
        assertEquals(14, outcome.usage?.totalTokens)
        assertEquals("搜索决策", outcome.usage?.details?.single()?.label)
    }

    @Test
    fun `blank message reports that no decision request was made`() = runBlocking {
        val client = RecordingDecisionClient(Result.failure(IllegalStateException("must not run")))
        val service = SearchDecisionService(client)

        val outcome = service.decideWithUsage(platform(), "   ", null)

        assertFalse(outcome.wasRequested)
        assertNull(outcome.requestPrompt)
        assertNull(outcome.usage)
        assertTrue(client.lastPrompt.isEmpty())
    }

    @Test
    fun `failed decision request still exposes the attempted prompt`() = runBlocking {
        val service = SearchDecisionService(
            RecordingDecisionClient(Result.failure(IllegalStateException("provider failed")))
        )

        val outcome = service.decideWithUsage(platform(), "What happened today?", null)

        assertFalse(outcome.decision.shouldSearch)
        assertTrue(outcome.wasRequested)
        assertTrue(outcome.requestPrompt.orEmpty().contains("What happened today?"))
        assertNull(outcome.usage)
    }

    @Test
    fun `invalid json defaults to no search`() = runBlocking {
        val service = SearchDecisionService(RecordingDecisionClient(Result.success(response("not json"))))

        val decision = service.decide(platform(), "What happened today?", null)

        assertFalse(decision.shouldSearch)
        assertTrue(decision.queries.isEmpty())
    }

    @Test
    fun `too many queries are clipped`() = runBlocking {
        val service = SearchDecisionService(
            RecordingDecisionClient(
                Result.success(
                    response("""{"shouldSearch":true,"queries":["one","two","three"],"reason":"needs current data"}""")
                )
            )
        )

        val decision = service.decide(platform(), "What are today's AI headlines?", null)

        assertTrue(decision.shouldSearch)
        assertEquals(listOf("one", "two"), decision.queries)
    }

    @Test
    fun `decision prompt instructs broad requests to use sensible default scopes`() = runBlocking {
        val client = RecordingDecisionClient(
            Result.success(
                response("""{"shouldSearch":true,"queries":["2026-07-01 top news headlines","2026-07-01 international news"],"reason":"broad news request"}""")
            )
        )
        val service = SearchDecisionService(client)

        service.decide(
            platform = platform(),
            latestUserMessage = "昨天有什么新闻吗",
            recentContext = null,
            runtimeContext = "Current local date/time: 2026-07-02T16:00:00+08:00 (Asia/Shanghai)."
        )

        assertTrue(client.lastPrompt.contains("将今天、昨天、本周、最新、当前等相对日期转换成具体日期或年份"))
        assertTrue(client.lastPrompt.contains("请求宽泛或不够具体时，选择合理的默认范围"))
        assertTrue(client.lastPrompt.contains("一条中文 query 加一条英文 query"))
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

    @Test
    fun `decision usage prefers exact provider counts`() = runBlocking {
        val service = SearchDecisionService(
            RecordingDecisionClient(
                Result.success(
                    response(
                        content = """{"shouldSearch":true,"queries":["current facts"],"reason":"current"}""",
                        usage = ProviderUsage(promptTokens = 13, completionTokens = 5, totalTokens = 18)
                    )
                )
            )
        )

        val outcome = service.decideWithUsage(platform(), "What happened today?", null)

        assertEquals(13, outcome.usage?.inputTokens)
        assertEquals(5, outcome.usage?.outputTokens)
        assertEquals(18, outcome.usage?.totalTokens)
        assertFalse(outcome.usage?.isEstimated ?: true)
    }

    @Test
    fun `decision usage is estimated when provider omits counts`() = runBlocking {
        val service = SearchDecisionService(
            RecordingDecisionClient(
                Result.success(response("""{"shouldSearch":false,"queries":[],"reason":"enough context"}"""))
            )
        )

        val outcome = service.decideWithUsage(platform(), "Summarize this", null)

        assertTrue(outcome.usage?.isEstimated == true)
        assertTrue((outcome.usage?.totalTokens ?: 0) > 0)
        assertTrue(outcome.usage?.details?.all { detail -> !detail.isToolRelated } == true)
    }

    @Test
    fun `openrouter decision streaming requests usage without changing other compatible providers`() {
        assertTrue(searchDecisionStreamOptionsFor(ClientType.OPENROUTER)?.includeUsage == true)
        assertEquals(null, searchDecisionStreamOptionsFor(ClientType.CUSTOM))
        assertEquals(null, searchDecisionStreamOptionsFor(ClientType.OLLAMA))
    }

    @Test
    fun `official deepseek decision request disables thinking without unsupported sampling parameters`() {
        val request = createOpenAICompatibleSearchDecisionRequest(
            platform = platform(
                apiUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-flash"
            ),
            prompt = "Decide whether search is needed."
        )

        assertEquals("disabled", request.thinking?.type)
        assertNull(request.temperature)
        assertNull(request.topP)
        val systemPrompt = request.messages.first().content.filterIsInstance<TextContent>().single().text
        assertTrue(systemPrompt.contains("网页搜索规划器"))
        assertTrue(systemPrompt.contains("搜索 query"))
        assertTrue(systemPrompt.contains("JSON key"))
    }

    @Test
    fun `deepseek proxy decision request keeps ordinary compatible parameters`() {
        val request = createOpenAICompatibleSearchDecisionRequest(
            platform = platform(
                apiUrl = "https://api.deepseek.com.proxy.example/v1",
                model = "deepseek-v4-flash"
            ),
            prompt = "Decide whether search is needed."
        )

        assertNull(request.thinking)
        assertEquals(0f, request.temperature)
        assertEquals(1f, request.topP)
    }

    private fun platform(
        apiUrl: String = "https://example.test",
        model: String = "custom-model"
    ) = PlatformV2(
        name = "Custom",
        compatibleType = ClientType.CUSTOM,
        apiUrl = apiUrl,
        model = model
    )

    private class RecordingDecisionClient(
        private val result: Result<SearchDecisionModelResponse>
    ) : SearchDecisionModelClient {
        var lastPrompt: String = ""

        override suspend fun requestDecision(
            platform: PlatformV2,
            prompt: String
        ): Result<SearchDecisionModelResponse> {
            lastPrompt = prompt
            return result
        }
    }

    private companion object {
        fun response(
            content: String,
            usage: ProviderUsage? = null
        ) = SearchDecisionModelResponse(content = content, usage = usage)
    }
}
