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
            )
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
