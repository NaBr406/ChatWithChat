package cn.nabr.chatwithchat.data.token

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.database.entity.TokenUsageRecordConverter
import cn.nabr.chatwithchat.data.dto.ProviderUsage
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.presentation.ui.chat.roundTotalTokens
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TokenAccountingContractTest {

    @Test
    fun `provider usage accepts both chat completion and responses field names`() {
        val json = Json { ignoreUnknownKeys = true }
        val chatCompletionUsage = json.decodeFromString<ProviderUsage>(
            """{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}"""
        )
        val responsesUsage = json.decodeFromString<ProviderUsage>(
            """{"input_tokens":12,"output_tokens":8,"total_tokens":20}"""
        )

        listOf(chatCompletionUsage, responsesUsage).forEach { usage ->
            val record = usage.toTokenUsageRecord(
                platform = platform,
                label = "provider round"
            )

            requireNotNull(record)
            assertEquals(12, record.inputTokens)
            assertEquals(8, record.outputTokens)
            assertEquals(20, record.totalTokens)
            assertEquals(0, record.toolTotalTokens)
            assertFalse(record.isEstimated)
            assertTrue(record.details.none { detail -> detail.isToolRelated })
        }
    }

    @Test
    fun `explicit zero provider usage remains exact instead of falling back to estimation`() {
        val record = ProviderUsage(
            inputTokens = 0,
            outputTokens = 0,
            totalTokens = 0
        ).toTokenUsageRecord(
            platform = platform,
            label = "failed provider round"
        )

        requireNotNull(record)
        assertEquals(0, record.inputTokens)
        assertEquals(0, record.outputTokens)
        assertEquals(0, record.totalTokens)
        assertFalse(record.isEstimated)
        assertTrue(record.details.none { detail -> detail.isEstimated })
    }

    @Test
    fun `provider cache usage is normalized and weighted`() {
        val deepSeek = Json { ignoreUnknownKeys = true }.decodeFromString<ProviderUsage>(
            """{"prompt_tokens":1200,"completion_tokens":80,"total_tokens":1280,"prompt_cache_hit_tokens":900,"prompt_cache_miss_tokens":300}"""
        ).toTokenUsageRecord(platform, "DeepSeek")
        requireNotNull(deepSeek)
        assertEquals(900, deepSeek.cacheHitTokens)
        assertEquals(300, deepSeek.cacheMissTokens)
        assertEquals(TokenCacheStatus.KNOWN, deepSeek.cacheStatus)
        assertEquals(0.75, deepSeek.cacheHitRate!!, 0.0001)

        val openAi = Json { ignoreUnknownKeys = true }.decodeFromString<ProviderUsage>(
            """{"prompt_tokens":1200,"completion_tokens":80,"prompt_tokens_details":{"cached_tokens":900}}"""
        ).toTokenUsageRecord(platform, "OpenAI")
        requireNotNull(openAi)
        assertEquals(300, openAi.cacheMissTokens)
        assertEquals(0.75, openAi.cacheHitRate!!, 0.0001)
    }

    @Test
    fun `invalid cache values remain unavailable while ordinary usage is preserved`() {
        val record = ProviderUsage(
            inputTokens = 100,
            outputTokens = 20,
            promptCacheHitTokens = 110,
            promptCacheMissTokens = 1
        ).toTokenUsageRecord(platform, "invalid")

        requireNotNull(record)
        assertEquals(100, record.inputTokens)
        assertEquals(20, record.outputTokens)
        assertEquals(TokenCacheStatus.UNAVAILABLE, record.cacheStatus)
        assertNull(record.cacheHitTokens)
        assertNull(record.cacheHitRate)
    }

    @Test
    fun `anthropic cache read and write stay out of ordinary input total`() {
        val record = ProviderUsage(
            inputTokens = 300,
            outputTokens = 40,
            cacheReadInputTokens = 900,
            cacheCreationInputTokens = 0
        ).toTokenUsageRecord(platform, "Anthropic")

        requireNotNull(record)
        assertEquals(300, record.inputTokens)
        assertEquals(340, record.totalTokens)
        assertEquals(900, record.cacheHitTokens)
        assertEquals(300, record.cacheMissTokens)
        assertEquals(TokenCacheStatus.KNOWN, record.cacheStatus)
    }

    @Test
    fun `gemini cached content and session aggregation use weighted tokens`() {
        val gemini = ProviderUsage(
            promptTokens = 1_000,
            completionTokens = 20,
            cachedContentTokenCount = 700
        ).toTokenUsageRecord(platform, "Gemini")
        requireNotNull(gemini)
        assertEquals(700, gemini.cacheHitTokens)
        assertEquals(300, gemini.cacheMissTokens)

        val unknown = TokenUsageRecord(inputTokens = 10, outputTokens = 2)
        val summary = listOf(
            TokenUsageRecord(
                inputTokens = 100,
                outputTokens = 10,
                cacheHitTokens = 90,
                cacheMissTokens = 10,
                cacheStatus = TokenCacheStatus.KNOWN
            ),
            TokenUsageRecord(
                inputTokens = 200,
                outputTokens = 10,
                cacheHitTokens = 100,
                cacheMissTokens = 100,
                cacheStatus = TokenCacheStatus.KNOWN
            ),
            unknown
        ).aggregateCacheUsage()

        requireNotNull(summary)
        assertEquals(190, summary.hitTokens)
        assertEquals(110, summary.missTokens)
        assertEquals(TokenCacheStatus.PARTIAL, summary.status)
        assertEquals(0.6333, summary.hitRate!!, 0.0001)
    }

    @Test
    fun `token usage converter reads legacy defaults and round trips current records`() {
        val converter = TokenUsageRecordConverter()
        val legacy = requireNotNull(
            converter.fromString(
                """{"inputTokens":4,"outputTokens":3,"totalTokens":7,"isEstimated":false,"provider":"OpenAI"}"""
            )
        )

        assertEquals(0, legacy.toolInputTokens)
        assertEquals(0, legacy.toolOutputTokens)
        assertEquals(0, legacy.toolTotalTokens)
        assertTrue(legacy.details.isEmpty())

        val current = TokenUsageRecord(
            inputTokens = 10,
            outputTokens = 5,
            totalTokens = 15,
            toolInputTokens = 30,
            toolOutputTokens = 12,
            toolTotalTokens = 42,
            isEstimated = false,
            provider = "OpenAI",
            platformUid = "openai-platform",
            model = "gpt-5",
            details = listOf(
                TokenUsageDetail(
                    label = "tool decision",
                    inputTokens = 10,
                    outputTokens = 5,
                    totalTokens = 15,
                    isEstimated = false,
                    isToolRelated = true
                ),
                TokenUsageDetail(
                    label = "final answer",
                    inputTokens = 20,
                    outputTokens = 7,
                    totalTokens = 27,
                    isEstimated = false,
                    isToolRelated = true
                )
            ),
            cacheHitTokens = 9,
            cacheMissTokens = 1,
            cacheStatus = TokenCacheStatus.KNOWN
        )

        assertEquals(current, converter.fromString(converter.fromRecord(current)))
    }

    @Test
    fun `round total counts ordinary answers or complete tool loops once per platform`() {
        val ordinary = TokenUsageRecord(totalTokens = 18)
        val toolLoop = TokenUsageRecord(totalTokens = 27, toolTotalTokens = 42)
        val secondPlatform = TokenUsageRecord(totalTokens = 9)

        assertEquals(18, listOf(ordinary).roundTotalTokens())
        assertEquals(42, listOf(toolLoop).roundTotalTokens())
        assertEquals(69, listOf(ordinary, toolLoop, secondPlatform).roundTotalTokens())
    }

    private val platform = PlatformV2(
        uid = "openai-platform",
        name = "OpenAI",
        compatibleType = ClientType.OPENAI,
        apiUrl = "https://api.openai.com/",
        model = "gpt-5"
    )
}
