package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTokenUsageIndexTest {
    @Test
    fun indexGroupsUsageByPlatformAndModelWithoutRescanningRows() {
        val first = MessageV2(
            content = "first",
            platformType = "openai",
            tokenUsage = TokenUsageRecord(
                inputTokens = 10,
                outputTokens = 4,
                platformUid = "openai",
                model = "gpt"
            )
        )
        val second = MessageV2(
            content = "second",
            platformType = "anthropic",
            tokenUsage = TokenUsageRecord(
                inputTokens = 20,
                outputTokens = 5,
                platformUid = "anthropic",
                model = "sonnet"
            )
        )

        val index = buildSessionTokenUsageIndex(listOf(listOf(first), listOf(second)))

        assertEquals(
            listOf(14),
            sessionTokenUsagesFor(index, "openai", "gpt").map(TokenUsageRecord::totalTokens)
        )
        assertEquals(
            listOf(25),
            sessionTokenUsagesFor(index, "anthropic", "sonnet").map(TokenUsageRecord::totalTokens)
        )
        assertEquals(emptyList<TokenUsageRecord>(), sessionTokenUsagesFor(index, "", "gpt"))
    }
}
