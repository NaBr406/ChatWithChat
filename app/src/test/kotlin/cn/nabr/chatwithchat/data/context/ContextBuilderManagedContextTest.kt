package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strategy matrix.
 *
 * [ContextBuilder.buildManagedContext] is deliberately pure — no network, no database, no suspension
 * — and that is what makes this file possible: every decision the feature makes about *what to send*
 * can be asserted here without a provider, a Room instance or a coroutine dispatcher. It also makes
 * the single most important assertion in the plan trivially checkable: a conversation that still fits
 * its budget cannot possibly have issued a compaction call, because this function has nothing to call
 * with.
 *
 * Sizes are asserted structurally rather than numerically wherever possible. Token counts come from a
 * real BPE encoder, so pinning "this turn costs 187 tokens" would break on an encoder upgrade without
 * anything actually being wrong; "the tail is bounded under AUTO_COMPACT and unbounded under
 * RAW_PREFERRED" is the property that matters and it holds regardless.
 */
class ContextBuilderManagedContextTest {

    private val builder = ContextBuilder()

    @Test
    fun `empty conversation produces an empty result`() {
        val result = builder.buildManagedContext(request(emptyList(), emptyList()))

        assertTrue(result.turns.isEmpty())
        assertNull(result.checkpointBody)
        assertNull(result.deterministicSummary)
        assertEquals(ContextCompactionState.NONE, result.compactionState)
        assertFalse(result.didCompact)
    }

    @Test
    fun `raw preferred sends the whole conversation verbatim while it fits`() {
        val (users, assistants) = conversation(5) { "turn-$it short question" }

        val result = builder.buildManagedContext(
            request(users, assistants, strategy = ContextStrategy.RAW_PREFERRED)
        )

        assertEquals(users.map { it.id }, result.turns.map { it.userMessage.id })
        assertEquals(users.map { it.content }, result.turns.map { it.userMessage.content })
        assertNull(result.checkpointBody)
        assertNull(result.deterministicSummary)
        assertNull(result.contextPromptBlock)
        assertEquals(ContextCompactionState.NONE, result.compactionState)
        assertFalse(result.didCompact)
        assertFalse(result.isNearBudgetLimit)
        assertFalse(result.exceedsEffectiveInputBudget)
        assertTrue(result.pendingFoldTurns.isEmpty())
        assertFalse(result.shouldEnqueueBackgroundFold)
    }

    @Test
    fun `auto compact bounds the raw tail where raw preferred does not`() {
        val (users, assistants) = conversation(5) { chunkyText("turn-$it") }

        val raw = builder.buildManagedContext(
            request(users, assistants, strategy = ContextStrategy.RAW_PREFERRED)
        )
        val auto = builder.buildManagedContext(
            request(users, assistants, strategy = ContextStrategy.AUTO_COMPACT_BALANCED)
        )

        // Same conversation, same budget: only the strategy differs.
        assertEquals(5, raw.turns.size)
        assertTrue(auto.turns.size < raw.turns.size)
        assertNull(raw.deterministicSummary)
        assertNotNull(auto.deterministicSummary)
        assertEquals(ContextCompactionState.NONE, raw.compactionState)
        assertEquals(ContextCompactionState.DETERMINISTIC_FALLBACK, auto.compactionState)
    }

    @Test
    fun `raw preferred auto compacts once the conversation stops fitting`() {
        val (users, assistants) = conversation(4) { "turn-$it " + chunkyText("body-$it").repeat(6) }

        val result = builder.buildManagedContext(
            request(users, assistants, strategy = ContextStrategy.RAW_PREFERRED)
        )

        // No confirmation prompt exists anywhere in this path: exceeding the budget degrades the
        // request automatically and the send goes through.
        assertTrue(result.turns.size < users.size)
        assertNotNull(result.deterministicSummary)
        assertEquals(ContextCompactionState.DETERMINISTIC_FALLBACK, result.compactionState)
    }

    @Test
    fun `nothing is discarded when the raw tail overflows`() {
        val (users, assistants) = conversation(5) { chunkyText("turn-$it") }

        val result = builder.buildManagedContext(
            request(users, assistants, strategy = ContextStrategy.AUTO_COMPACT_BALANCED)
        )

        // Every turn is either sent verbatim or handed to the fold batch that the extractive summary
        // already covers. Nothing falls between the two.
        val accountedFor = (result.pendingFoldTurns + result.turns).map { it.userMessage.id }
        assertEquals(users.map { it.id }, accountedFor)
        assertTrue(result.pendingFoldTurns.isNotEmpty())
        assertNotNull(result.deterministicSummary)
    }

    @Test
    fun `raw preferred still computes a fold batch so folding does not wait for the budget`() {
        val (users, assistants) = conversation(5) { chunkyText("turn-$it") }

        val result = builder.buildManagedContext(
            request(users, assistants, strategy = ContextStrategy.RAW_PREFERRED)
        )

        // Everything is sent raw, yet the pre-fold boundary is still computed against recentRawTail.
        // Otherwise a RAW_PREFERRED chat would only ever start folding once it was already in trouble.
        assertEquals(5, result.turns.size)
        assertTrue(result.pendingFoldTurns.isNotEmpty())
        assertTrue(result.pendingFoldTurns.none { it.isCurrentTurn })
    }

    @Test
    fun `the current turn is admitted even when it alone exceeds the budget`() {
        val users = listOf(
            userMessage(1, "earlier question"),
            userMessage(2, "lorem ipsum dolor sit amet ".repeat(800))
        )
        val assistants = listOf(listOf(assistantMessage(1, "earlier answer")), emptyList())

        val result = builder.buildManagedContext(request(users, assistants))

        assertTrue(result.turns.isNotEmpty())
        assertEquals(2, result.turns.last().userMessage.id)
        assertTrue(result.turns.last().isCurrentTurn)
        // Sent anyway: the provider's own limit is authoritative, but the state is observable.
        assertTrue(result.exceedsEffectiveInputBudget)
    }

    @Test
    fun `turns already folded into a checkpoint are never replayed raw`() {
        val (users, assistants) = conversation(8) { "turn-$it short question" }

        val result = builder.buildManagedContext(
            request(users, assistants, chain = chainThrough(endUserMessageId = 4))
        )

        assertEquals(listOf(5, 6, 7, 8), result.turns.map { it.userMessage.id })
        assertEquals(CHECKPOINT_BODY, result.checkpointBody)
        assertEquals(4, result.checkpointBoundaryUserMessageId)
        assertEquals(ContextCompactionState.REUSED_CHECKPOINT, result.compactionState)
        assertTrue(result.didCompact)
    }

    @Test
    fun `the checkpoint block is framed as history rather than instructions`() {
        val (users, assistants) = conversation(8) { "turn-$it short question" }

        val block = builder.buildManagedContext(
            request(users, assistants, chain = chainThrough(endUserMessageId = 4))
        ).contextPromptBlock

        assertNotNull(block)
        assertTrue(block!!.startsWith(ContextBuildResult.HISTORICAL_CONTEXT_HEADER))
        assertTrue(block.contains(CHECKPOINT_BODY))
    }

    @Test
    fun `a checkpoint boundary whose turn was deleted still separates folded history`() {
        val users = listOf(1, 2, 5, 6).map { userMessage(it, "turn-$it short question") }
        val assistants = listOf(1, 2, 5, 6).map { listOf(assistantMessage(it, "turn-$it short answer")) }

        val result = builder.buildManagedContext(
            request(users, assistants, chain = chainThrough(endUserMessageId = 4))
        )

        assertEquals(listOf(5, 6), result.turns.map { it.userMessage.id })
    }

    @Test
    fun `an invalidated chain whose text still fits triggers no compaction at all`() {
        val (users, assistants) = conversation(5) { "turn-$it short question" }

        val result = builder.buildManagedContext(
            request(
                users,
                assistants,
                chain = CheckpointChainView(validSegments = emptyList(), invalidatedFromSegmentIndex = 0)
            )
        )

        // This is the common path after an edit or a delete: the checkpoint is gone, the raw text fits,
        // and re-summarizing would be pure waste. Nothing here can issue a provider call — the builder
        // takes no client — and nothing asks for one either.
        assertEquals(users.map { it.id }, result.turns.map { it.userMessage.id })
        assertNull(result.checkpointBody)
        assertNull(result.deterministicSummary)
        assertTrue(result.pendingFoldTurns.isEmpty())
        assertFalse(result.shouldEnqueueBackgroundFold)
        assertEquals(ContextCompactionState.NONE, result.compactionState)
        assertFalse(result.didCompact)
    }

    @Test
    fun `an invalidated chain that no longer fits degrades to the extractive summary`() {
        val (users, assistants) = conversation(5) { chunkyText("turn-$it") }

        val result = builder.buildManagedContext(
            request(
                users,
                assistants,
                chain = CheckpointChainView(validSegments = emptyList(), invalidatedFromSegmentIndex = 0)
            )
        )

        // The current request never waits for a rebuild: it goes out with the deterministic summary,
        // and the real fold is queued from pendingFoldTurns afterwards.
        assertNull(result.checkpointBody)
        assertNotNull(result.deterministicSummary)
        assertTrue(result.pendingFoldTurns.isNotEmpty())
        assertEquals(ContextCompactionState.DETERMINISTIC_FALLBACK, result.compactionState)
    }

    @Test
    fun `a reply from another provider enters as plain text and is counted`() {
        val users = listOf(userMessage(1, "asked elsewhere"), userMessage(2, "asked here"))
        val foreignReply = MessageV2(
            id = 1001,
            content = "answered by another provider",
            platformType = "other-platform"
        )
        val assistants = listOf(listOf(foreignReply), emptyList())

        val result = builder.buildManagedContext(request(users, assistants))

        assertEquals(listOf(1, 2), result.turns.map { it.userMessage.id })
        assertEquals(1, result.crossProviderDegradedTurnCount)
        val degraded = result.turns.first().assistantMessage
        assertNotNull(degraded)
        assertEquals("answered by another provider", degraded!!.content)
        assertTrue(degraded.attachments.isEmpty())
        // platformType is left alone on purpose: a degraded turn must stay distinguishable.
        assertEquals("other-platform", degraded.platformType)
    }

    @Test
    fun `a turn answered only by another provider keeps the user half of the exchange`() {
        val users = listOf(userMessage(1, "the question that led to the switch"), userMessage(2, "follow-up"))
        val assistants = listOf(
            listOf(MessageV2(id = 1001, content = "foreign answer", platformType = "other-platform")),
            emptyList()
        )

        val result = builder.buildManagedContext(request(users, assistants))

        assertTrue(result.turns.any { it.userMessage.content == "the question that led to the switch" })
    }

    @Test
    fun `same provider replies are never counted as degraded`() {
        val (users, assistants) = conversation(3) { "turn-$it short question" }

        val result = builder.buildManagedContext(request(users, assistants))

        assertEquals(0, result.crossProviderDegradedTurnCount)
    }

    @Test
    fun `history turns with no reply are dropped but the current turn survives`() {
        val users = (1..3).map { userMessage(it, "turn-$it short question") }
        val assistants = listOf(
            listOf(assistantMessage(1, "turn-1 short answer")),
            emptyList(),
            emptyList()
        )

        val result = builder.buildManagedContext(request(users, assistants))

        assertEquals(listOf(1, 3), result.turns.map { it.userMessage.id })
        assertTrue(result.turns.last().isCurrentTurn)
        assertNull(result.turns.last().assistantMessage)
    }

    @Test
    fun `a range anchor starts the context at a complete turn`() {
        val (users, assistants) = conversation(5) { "turn-$it short question" }

        val result = builder.buildManagedContext(
            request(users, assistants, range = ContextRange(CHAT_ID, PLATFORM_UID, startUserMessageId = 3))
        )

        assertEquals(listOf(3, 4, 5), result.turns.map { it.userMessage.id })
        assertEquals(ContextRangeCorrection.NONE, result.rangeCorrection)
    }

    @Test
    fun `an anchor on a deleted message widens back to the full history`() {
        val (users, assistants) = conversation(4) { "turn-$it short question" }

        val result = builder.buildManagedContext(
            request(users, assistants, range = ContextRange(CHAT_ID, PLATFORM_UID, startUserMessageId = 999))
        )

        // Correction widens, never narrows: a stale anchor must not hide turns the user can still see.
        assertEquals(users.map { it.id }, result.turns.map { it.userMessage.id })
        assertEquals(ContextRangeCorrection.ANCHOR_MISSING, result.rangeCorrection)
    }

    @Test
    fun `an anchor pointing at an assistant message reports a boundary correction`() {
        val (users, assistants) = conversation(4) { "turn-$it short question" }
        val assistantId = assistants.first().first().id

        val result = builder.buildManagedContext(
            request(users, assistants, range = ContextRange(CHAT_ID, PLATFORM_UID, startUserMessageId = assistantId))
        )

        assertEquals(users.map { it.id }, result.turns.map { it.userMessage.id })
        assertEquals(ContextRangeCorrection.ANCHOR_NOT_A_TURN_BOUNDARY, result.rangeCorrection)
    }

    @Test
    fun `the native path replays the stored window instead of an app side checkpoint`() {
        val (users, assistants) = conversation(8) { "turn-$it short question" }

        val result = builder.buildManagedContext(
            request(
                users,
                assistants,
                platform = nativePlatform(),
                budget = LARGE_BUDGET,
                nativeState = nativeState(boundaryUserMessageId = 4, estimatedTokens = 900)
            )
        )

        assertEquals(NATIVE_ITEMS_JSON, result.nativeCompactionItemsJson)
        assertEquals(listOf(5, 6, 7, 8), result.turns.map { it.userMessage.id })
        assertNull(result.checkpointBody)
        assertNull(result.checkpointBoundaryUserMessageId)
        assertEquals(ContextCompactionState.REUSED_NATIVE_STATE, result.compactionState)
        assertFalse(result.nativeInitializationRequired)
        // Server-side folding happens inside the ordinary request; an app-side fold would be a second,
        // competing summary of the same turns.
        assertTrue(result.pendingFoldTurns.isEmpty())
        assertFalse(result.shouldEnqueueBackgroundFold)
    }

    @Test
    fun `the native path without stored state simply enables server side compaction`() {
        val (users, assistants) = conversation(4) { "turn-$it short question" }

        val result = builder.buildManagedContext(
            request(users, assistants, platform = nativePlatform(), budget = LARGE_BUDGET)
        )

        assertNull(result.nativeCompactionItemsJson)
        assertEquals(ContextCompactionState.NATIVE_SERVER_SIDE, result.compactionState)
        assertFalse(result.nativeInitializationRequired)
        assertEquals(users.map { it.id }, result.turns.map { it.userMessage.id })
    }

    @Test
    fun `native initialization is required when the range no longer fits alongside the window`() {
        val (users, assistants) = conversation(5) { chunkyText("turn-$it") }

        val result = builder.buildManagedContext(
            request(
                users,
                assistants,
                platform = nativePlatform(),
                budget = SMALL_BUDGET,
                nativeState = nativeState(boundaryUserMessageId = 0, estimatedTokens = 1_600)
            )
        )

        assertTrue(result.nativeInitializationRequired)
        // Even here the overflow reaches the model somehow: the extractive summary is the floor if the
        // standalone initialization call fails.
        assertNotNull(result.deterministicSummary)
    }

    @Test
    fun `a model that answered unsupported parameter falls back to the app side path`() {
        val (users, assistants) = conversation(4) { "turn-$it short question" }

        val result = builder.buildManagedContext(
            request(
                users,
                assistants,
                platform = nativePlatform(),
                budget = LARGE_BUDGET,
                nativeState = nativeState(
                    boundaryUserMessageId = 2,
                    estimatedTokens = 900
                ).copy(nativeUnsupported = true),
                chain = chainThrough(endUserMessageId = 2)
            )
        )

        // The gate guessed wrong; the provider's answer is authoritative from then on.
        assertNull(result.nativeCompactionItemsJson)
        assertEquals(CHECKPOINT_BODY, result.checkpointBody)
        assertEquals(ContextCompactionState.REUSED_CHECKPOINT, result.compactionState)
    }

    @Test
    fun `a native state that is not ready for input is ignored`() {
        val (users, assistants) = conversation(4) { "turn-$it short question" }

        val result = builder.buildManagedContext(
            request(
                users,
                assistants,
                platform = nativePlatform(),
                budget = LARGE_BUDGET,
                nativeState = nativeState(boundaryUserMessageId = 2, estimatedTokens = 900)
                    .copy(isReadyForInput = false)
            )
        )

        assertNull(result.nativeCompactionItemsJson)
        assertEquals(ContextCompactionState.NATIVE_SERVER_SIDE, result.compactionState)
        // Nothing was folded, so nothing is skipped.
        assertEquals(users.map { it.id }, result.turns.map { it.userMessage.id })
    }

    @Test
    fun `every provider family goes through the same managed entry point`() {
        val (users, assistants) = conversation(3) { "turn-$it short question" }

        ClientType.entries.forEach { clientType ->
            val platform = PlatformV2(
                uid = PLATFORM_UID,
                name = clientType.name,
                compatibleType = clientType,
                apiUrl = "https://example.com",
                model = "some-model",
                token = "token"
            )

            val result = builder.buildManagedContext(
                request(users, assistants, platform = platform, budget = LARGE_BUDGET)
            )

            assertEquals(
                "context for ${clientType.name} lost the current turn",
                3,
                result.turns.last().userMessage.id
            )
            assertTrue("context for ${clientType.name} was empty", result.turns.isNotEmpty())
        }
    }

    private fun request(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        strategy: ContextStrategy = ContextStrategy.AUTO_COMPACT_BALANCED,
        platform: PlatformV2 = platform(),
        budget: ContextBudget = SMALL_BUDGET,
        range: ContextRange = ContextRange.fullHistory(CHAT_ID, PLATFORM_UID),
        chain: CheckpointChainView = CheckpointChainView(),
        nativeState: OpenAINativeCompactionState? = null
    ) = ContextAssemblyRequest(
        platform = platform,
        strategy = strategy,
        userMessages = userMessages,
        assistantMessages = assistantMessages,
        budget = budget,
        range = range,
        chain = chain,
        nativeState = nativeState
    )

    private fun conversation(
        turnCount: Int,
        content: (Int) -> String
    ): Pair<List<MessageV2>, List<List<MessageV2>>> {
        val users = (1..turnCount).map { userMessage(it, content(it)) }
        val assistants = (1..turnCount).map { listOf(assistantMessage(it, content(it) + " answered")) }
        return users to assistants
    }

    private fun chainThrough(endUserMessageId: Int) = CheckpointChainView(
        validSegments = listOf(
            ConversationCheckpoint(
                chatRoomId = CHAT_ID,
                platformUid = PLATFORM_UID,
                segmentIndex = 0,
                startUserMessageId = 1,
                endUserMessageId = endUserMessageId,
                body = CHECKPOINT_BODY,
                sourceTurnsHash = "hash-0",
                estimatedTokens = 40
            )
        )
    )

    private fun nativeState(
        boundaryUserMessageId: Int,
        estimatedTokens: Int
    ) = OpenAINativeCompactionState(
        chatRoomId = CHAT_ID,
        platformUid = PLATFORM_UID,
        compactionItemsJson = NATIVE_ITEMS_JSON,
        boundaryUserMessageId = boundaryUserMessageId,
        sourceTurnsHash = "native-hash",
        estimatedTokens = estimatedTokens
    )

    private fun platform() = PlatformV2(
        uid = PLATFORM_UID,
        name = "Custom",
        compatibleType = ClientType.CUSTOM,
        apiUrl = "https://example.com",
        model = "custom-model",
        token = "token"
    )

    private fun nativePlatform() = PlatformV2(
        uid = PLATFORM_UID,
        name = "OpenAI",
        compatibleType = ClientType.OPENAI,
        apiUrl = "https://api.openai.com",
        model = "gpt-5.3",
        token = "token"
    )

    private fun userMessage(id: Int, content: String) = MessageV2(
        id = id,
        content = content,
        platformType = null
    )

    private fun assistantMessage(id: Int, content: String) = MessageV2(
        id = 1_000 + id,
        content = content,
        platformType = PLATFORM_UID
    )

    /** Big enough that a handful of turns overflow [ContextBudget.recentRawTail], small enough that
     * the same handful still fits [ContextBudget.effectiveInputBudget] verbatim. */
    private fun chunkyText(seed: String): String =
        (1..8).joinToString(separator = " ") { "$seed alpha bravo charlie delta echo foxtrot golf hotel" }

    private companion object {
        const val CHAT_ID = 11
        const val PLATFORM_UID = "platform"
        const val CHECKPOINT_BODY = "已折叠的较早对话要点。"
        const val NATIVE_ITEMS_JSON = """[{"type":"compaction","id":"cmp_1","encrypted_content":"opaque"}]"""

        /** effectiveInputBudget 2048, recentRawTail 614: the two differ enough to be observable. */
        val SMALL_BUDGET: ContextBudget = ContextBudget.of(modelContextWindow = 2_048, providerSummaryTarget = 768)

        val LARGE_BUDGET: ContextBudget = ContextBudget.of(modelContextWindow = 32_768, providerSummaryTarget = 1_200)
    }
}
