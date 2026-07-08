package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceMetadata
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    @Test
    fun `token budget keeps current turn and summarizes omitted history`() {
        val context = ContextBuilder().buildContext(
            userMessages = (1..5).map { userMessage(it, "topic-$it user detail " + "extra ".repeat(40)) },
            assistantMessages = (1..5).map { listOf(assistantMessage(it, "topic-$it assistant reply " + "extra ".repeat(40))) },
            platform = platform(),
            policy = policy(
                recentTurnWindow = 4,
                maxContextTokens = 180,
                summaryTokenBudget = 80,
                maxSummaryTurns = 2
            )
        )

        assertEquals("topic-5 user detail " + "extra ".repeat(40), context.turns.last().userMessage.content)
        assertTrue(context.turns.size < 5)
        assertNotNull(context.summary)
        assertTrue(context.summary!!.contains("Earlier conversation summary"))
        assertTrue(context.estimatedTokens <= 180)
        assertTrue(context.omittedTurns.isNotEmpty())
        assertTrue(context.omittedTurns.none { it.isCurrentTurn })
    }

    @Test
    fun `context stays unsummarized when turn window and token budget fit`() {
        val context = ContextBuilder().buildContext(
            userMessages = (1..2).map { userMessage(it, "topic-$it user") },
            assistantMessages = (1..2).map { listOf(assistantMessage(it, "topic-$it assistant")) },
            platform = platform(),
            policy = policy(
                recentTurnWindow = 4,
                maxContextTokens = 300,
                summaryTokenBudget = 40
            )
        )

        assertEquals(2, context.turns.size)
        assertNull(context.summary)
    }

    @Test
    fun `older turns beyond recent window are summarized when budget is available`() {
        val context = ContextBuilder().buildContext(
            userMessages = (1..4).map { userMessage(it, "topic-$it user") },
            assistantMessages = (1..4).map { listOf(assistantMessage(it, "topic-$it assistant")) },
            platform = platform(),
            policy = policy(
                recentTurnWindow = 2,
                maxContextTokens = 300,
                summaryTokenBudget = 80
            )
        )

        assertEquals(listOf("topic-2 user", "topic-3 user", "topic-4 user"), context.turns.map { it.userMessage.content })
        assertNotNull(context.summary)
        assertTrue(context.summary!!.contains("topic-1"))
    }

    @Test
    fun `budget trimming does not skip a too large recent turn to include older turns`() {
        val context = ContextBuilder().buildContext(
            userMessages = listOf(
                userMessage(1, "small old turn"),
                userMessage(2, "oversized recent turn " + "detail ".repeat(120)),
                userMessage(3, "current turn")
            ),
            assistantMessages = listOf(
                listOf(assistantMessage(1, "small old reply")),
                listOf(assistantMessage(2, "oversized recent reply " + "detail ".repeat(120))),
                listOf(assistantMessage(3, ""))
            ),
            platform = platform(),
            policy = policy(
                recentTurnWindow = 2,
                maxContextTokens = 80,
                summaryTokenBudget = 60
            )
        )

        assertEquals(listOf("current turn"), context.turns.map { it.userMessage.content })
        assertFalse(context.turns.any { it.userMessage.content == "small old turn" })
        assertNotNull(context.summary)
    }

    @Test
    fun `assistant web sources are included in reusable context`() {
        val source = MessageSourceMetadata(
            title = "Example Source",
            url = "https://example.com/source",
            snippet = "Example search snippet",
            sourceToolName = "web_search"
        )

        val context = ContextBuilder().buildContext(
            userMessages = listOf(
                userMessage(1, "What changed?"),
                userMessage(2, "Use the previous source again")
            ),
            assistantMessages = listOf(
                listOf(assistantMessage(1, "Answer from search", listOf(source))),
                listOf(assistantMessage(2, ""))
            ),
            platform = platform(),
            policy = policy(
                recentTurnWindow = 4,
                maxContextTokens = 600,
                summaryTokenBudget = 80
            )
        )

        val assistantContext = context.turns.first().assistantMessage?.effectiveContent().orEmpty()
        assertTrue(assistantContext.contains("Answer from search"))
        assertTrue(assistantContext.contains("Referenced web sources from this answer"))
        assertTrue(assistantContext.contains("Example Source"))
        assertTrue(assistantContext.contains("https://example.com/source"))
        assertTrue(assistantContext.contains("Example search snippet"))
    }

    private fun policy(
        recentTurnWindow: Int,
        maxContextTokens: Int,
        summaryTokenBudget: Int,
        maxSummaryTurns: Int = 8
    ) = ProviderContextPolicy(
        recentTurnWindow = recentTurnWindow,
        historicalImageTurnWindow = 0,
        maxInlineAttachmentBytes = null,
        preferProviderFileRefs = false,
        maxContextTokens = maxContextTokens,
        summaryTokenBudget = summaryTokenBudget,
        maxSummaryTurns = maxSummaryTurns
    )

    private fun platform() = PlatformV2(
        uid = PLATFORM_UID,
        name = "Custom",
        compatibleType = ClientType.CUSTOM,
        apiUrl = "https://example.com",
        model = "custom-model"
    )

    private fun userMessage(id: Int, content: String) = MessageV2(
        id = id,
        content = content,
        platformType = null
    )

    private fun assistantMessage(
        id: Int,
        content: String,
        sourceMetadata: List<MessageSourceMetadata> = emptyList()
    ) = MessageV2(
        id = 100 + id,
        content = content,
        sourceMetadata = sourceMetadata,
        platformType = PLATFORM_UID
    )

    private companion object {
        const val PLATFORM_UID = "platform"
    }
}
