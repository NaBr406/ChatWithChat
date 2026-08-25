package cn.nabr.chatwithchat.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRoundStateMachineTest {
    @Test
    fun `initial state exposes the full scoped definitions with user reasoning`() {
        val machine = createMachine()

        assertEquals(initialDefinitions, machine.current.definitions)
        assertEquals(initialDefinitions.map(ToolDefinition::name).toSet(), machine.current.allowedToolNames)
        assertEquals(ToolRoundReasoningPolicy.USER, machine.current.reasoningPolicy)
        assertFalse(machine.current.isFinalOnly)
    }

    @Test
    fun `non-empty sticker search narrows the next round to send with low reasoning`() {
        val machine = createMachine()

        val state = machine.advance(listOf(stickerSearchResult(candidateCount = 2)))

        assertEquals(listOf(ToolDefinition.SendSticker), state.definitions)
        assertEquals(setOf(ToolDefinition.SendSticker.name), state.allowedToolNames)
        assertEquals(ToolRoundReasoningPolicy.LOW, state.reasoningPolicy)
        assertFalse(state.isFinalOnly)
    }

    @Test
    fun `first empty sticker search allows one low-reasoning search retry`() {
        val machine = createMachine()

        val state = machine.advance(listOf(stickerSearchResult(candidateCount = 0)))

        assertEquals(listOf(ToolDefinition.SearchStickers), state.definitions)
        assertEquals(setOf(ToolDefinition.SearchStickers.name), state.allowedToolNames)
        assertEquals(ToolRoundReasoningPolicy.LOW, state.reasoningPolicy)
        assertFalse(state.isFinalOnly)
    }

    @Test
    fun `second empty sticker search exhausts retry and enters user-reasoning final state`() {
        val machine = createMachine()
        machine.advance(listOf(stickerSearchResult(callId = "search_1", candidateCount = 0)))

        val state = machine.advance(listOf(stickerSearchResult(callId = "search_2", candidateCount = 0)))

        assertFinalOnly(state)
    }

    @Test
    fun `successful sticker send withholds the sticker tools and keeps the rest reachable`() {
        val machine = createMachine()
        machine.advance(listOf(stickerSearchResult(candidateCount = 1)))

        val state = machine.advance(listOf(stickerSendResult(isError = false)))

        // A sticker is a reaction to the user's message, so the model reaches for one early — often before it
        // has done what the message asked. Ending the loop here left that work with no tool channel, and the
        // model wrote its next call out as prose instead.
        assertEquals(listOf(ToolDefinition.WebSearch), state.definitions)
        assertEquals(setOf(ToolDefinition.WebSearch.name), state.allowedToolNames)
        assertFalse(state.isFinalOnly)
        assertEquals(ToolRoundReasoningPolicy.USER, state.reasoningPolicy)
    }

    @Test
    fun `failed sticker send also withholds the sticker tools rather than retrying the send`() {
        val machine = createMachine()
        machine.advance(listOf(stickerSearchResult(candidateCount = 1)))

        val state = machine.advance(listOf(stickerSendResult(isError = true)))

        assertEquals(listOf(ToolDefinition.WebSearch), state.definitions)
        assertFalse(state.isFinalOnly)
    }

    @Test
    fun `a sticker-only turn still ends with nothing left to offer`() {
        val machine = ToolRoundStateMachine(
            listOf(ToolDefinition.SearchStickers, ToolDefinition.SendSticker)
        )
        machine.advance(listOf(stickerSearchResult(candidateCount = 1)))

        val state = machine.advance(listOf(stickerSendResult(isError = false)))

        // Empty definitions are what the loops already read as "go write the final answer", so a pure chat
        // turn behaves exactly as it did before the sticker phase stopped being terminal.
        assertEquals(emptyList<ToolDefinition>(), state.definitions)
        assertTrue(state.allowedToolNames.isEmpty())
    }

    @Test
    fun `sticker history may be dropped only while the turn has done nothing else`() {
        val stickerOnly = createMachine()
        stickerOnly.advance(listOf(stickerSearchResult(candidateCount = 1)))
        assertTrue(stickerOnly.advance(listOf(stickerSendResult(isError = false))).canDropStickerHistory)

        val afterRealWork = createMachine()
        afterRealWork.advance(listOf(webSearchResult()))
        afterRealWork.advance(listOf(stickerSearchResult(candidateCount = 1)))

        val state = afterRealWork.advance(listOf(stickerSendResult(isError = false)))

        // Dropping here would take the web results with it, and the model's own tool history is the only
        // record that the work happened — it re-derives the request from scratch without it.
        assertFalse(state.canDropStickerHistory)
        assertFalse(state.shouldCompactScratchpad)
    }

    @Test
    fun `non-sticker results leave the initial state unchanged`() {
        val machine = createMachine()

        val state = machine.advance(listOf(webSearchResult()))

        assertEquals(initialDefinitions, state.definitions)
        assertEquals(initialDefinitions.map(ToolDefinition::name).toSet(), state.allowedToolNames)
        assertEquals(ToolRoundReasoningPolicy.USER, state.reasoningPolicy)
        assertFalse(state.isFinalOnly)
    }

    private fun createMachine(): ToolRoundStateMachine = ToolRoundStateMachine(initialDefinitions)

    private fun ToolRoundStateMachine.advance(results: List<ToolResult>): ToolRoundState {
        onToolResults(results)
        return current
    }

    private fun stickerSearchResult(
        callId: String = "search_1",
        candidateCount: Int
    ): ToolResult = ToolResult(
        callId = callId,
        name = ToolDefinition.SearchStickers.name,
        content = if (candidateCount > 0) "sticker_id=builtin.reactions.wave" else "No matching stickers.",
        metadata = mapOf("candidate_count" to candidateCount.toString())
    )

    private fun stickerSendResult(isError: Boolean): ToolResult = ToolResult(
        callId = "send_1",
        name = ToolDefinition.SendSticker.name,
        content = if (isError) "sticker_send_failed" else "Sticker sent.",
        isError = isError
    )

    private fun webSearchResult(): ToolResult = ToolResult(
        callId = "web_1",
        name = ToolDefinition.WebSearch.name,
        content = "search result"
    )

    private fun assertFinalOnly(state: ToolRoundState) {
        assertTrue(state.isFinalOnly)
        assertEquals(emptyList<ToolDefinition>(), state.definitions)
        assertTrue(state.allowedToolNames.isEmpty())
        assertEquals(ToolRoundReasoningPolicy.USER, state.reasoningPolicy)
    }

    private companion object {
        val initialDefinitions = listOf(
            ToolDefinition.WebSearch,
            ToolDefinition.SearchStickers,
            ToolDefinition.SendSticker
        )
    }
}
