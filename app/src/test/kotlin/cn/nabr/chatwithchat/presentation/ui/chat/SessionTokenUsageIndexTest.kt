package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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

    /** Unchanged groups keep their list instance so older turns skip recomposition while one streams. */
    @Test
    fun rebuildReusesListsForUnchangedGroups() {
        fun message(platform: String, model: String, output: Int) = MessageV2(
            content = "x",
            platformType = platform,
            tokenUsage = TokenUsageRecord(inputTokens = 1, outputTokens = output, platformUid = platform, model = model)
        )
        val stable = message("openai", "gpt", 1)
        val first = buildSessionTokenUsageIndex(listOf(listOf(stable), listOf(message("anthropic", "sonnet", 1))))

        val second = buildSessionTokenUsageIndex(
            listOf(listOf(stable), listOf(message("anthropic", "sonnet", 2))),
            previous = first
        )

        assertSame(first[SessionTokenUsageKey("openai", "gpt")], second[SessionTokenUsageKey("openai", "gpt")])
        assertNotSame(first[SessionTokenUsageKey("anthropic", "sonnet")], second[SessionTokenUsageKey("anthropic", "sonnet")])
    }
}
