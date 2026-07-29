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
    fun `successful sticker send enters user-reasoning final state`() {
        val machine = createMachine()
        machine.advance(listOf(stickerSearchResult(candidateCount = 1)))

        val state = machine.advance(listOf(stickerSendResult(isError = false)))

        assertFinalOnly(state)
    }

    @Test
    fun `failed sticker send still enters user-reasoning final state`() {
        val machine = createMachine()
        machine.advance(listOf(stickerSearchResult(candidateCount = 1)))

        val state = machine.advance(listOf(stickerSendResult(isError = true)))

        assertFinalOnly(state)
    }

    @Test
    fun `non-sticker results leave the initial state unchanged`() {
        val machine = createMachine()

        val state = machine.advance(
            listOf(
                ToolResult(
                    callId = "web_1",
                    name = ToolDefinition.WebSearch.name,
                    content = "search result"
                )
            )
        )

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
