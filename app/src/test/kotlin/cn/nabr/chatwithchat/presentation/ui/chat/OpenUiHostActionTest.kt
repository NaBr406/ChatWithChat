package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.richcontent.RichContentParser
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionRegistry
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionRuntime
import cn.nabr.chatwithchat.util.StreamTerminalOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUiHostActionTest {
    private val source = RichInteractionHostSource(1, "turn", "provider", "revision:0", "fingerprint", "Facts.\n```openui\nroot = Col([])\n```")

    private fun host() = RichInteractionHostController(RichInteractionRuntime(RichInteractionRegistry(emptyList())), RichContentParser())

    @Test
    fun onlyCompletedSavedLatestAttemptEnablesActionsAndNewAttemptRevokesOldCallback() {
        val host = host()
        host.beginAttempt(source, "one")
        val binding = host.bindOpenUi(source, RichInteractionDraftGate())
        val event = OpenUiHostAction(binding.identity, binding.revision, "ToAssistant", "Continue")
        assertFalse(host.canAcceptOpenUi(event, source, RichInteractionDraftGate()))
        host.completeAttempt(source, "one", StreamTerminalOutcome.Success, false)
        assertFalse(host.canAcceptOpenUi(event, source, RichInteractionDraftGate()))
        host.completeAttempt(source, "one", StreamTerminalOutcome.Success, true)
        assertTrue(host.canAcceptOpenUi(event, source, RichInteractionDraftGate()))
        assertTrue(host.canAcceptOpenUi(event, source.copy(isLatestRevision = false), RichInteractionDraftGate()))
        assertFalse(host.canAcceptOpenUi(event, source.copy(revisionFingerprint = "changed"), RichInteractionDraftGate()))
        assertFalse(host.canAcceptOpenUi(event, source.copy(isStreaming = true), RichInteractionDraftGate()))
        host.beginAttempt(source, "two")
        assertFalse(host.canAcceptOpenUi(event, source, RichInteractionDraftGate()))
    }

    @Test
    fun draftPopulationRejectsExistingTextAttachmentsEditingAndPendingWork() {
        val host = host()
        host.beginAttempt(source, "one")
        host.completeAttempt(source, "one", StreamTerminalOutcome.Success, true)
        val binding = host.bindOpenUi(source, RichInteractionDraftGate())
        val event = OpenUiHostAction(binding.identity, binding.revision, "ToAssistant", "Continue")
        listOf(
            RichInteractionDraftGate(hasDraftText = true),
            RichInteractionDraftGate(hasAttachments = true),
            RichInteractionDraftGate(isImportingAttachments = true),
            RichInteractionDraftGate(isEditing = true),
            RichInteractionDraftGate(isBusy = true),
            RichInteractionDraftGate(isPersisting = true),
            RichInteractionDraftGate(hasQueuedQuestion = true)
        ).forEach { assertFalse(host.canAcceptOpenUi(event, source, it)) }
    }

    @Test
    fun persistedProgramsRemainInteractiveAfterReopeningAndCacheEviction() {
        val saved = source.copy(isPersisted = true)
        val host = host()
        val first = host.bindOpenUi(saved, RichInteractionDraftGate())
        repeat(40) { index ->
            val next = source.copy(turnKey = "turn-$index")
            host.beginAttempt(next, "attempt-$index")
            host.completeAttempt(next, "attempt-$index", StreamTerminalOutcome.Success, true)
        }
        assertTrue(host.bindOpenUi(saved, RichInteractionDraftGate()).interactive)
        host.clear()
        val reopened = host.bindOpenUi(saved, RichInteractionDraftGate())
        assertTrue(reopened.interactive)
        assertTrue(host.canAcceptOpenUi(OpenUiHostAction(reopened.identity, reopened.revision, "ToAssistant", "Continue"), saved, RichInteractionDraftGate()))
        assertFalse(host.canAcceptOpenUi(OpenUiHostAction(first.identity, "old-fingerprint", "ToAssistant", "Continue"), saved, RichInteractionDraftGate()))
        assertTrue(host.bindOpenUi(saved.copy(isLatestRevision = false), RichInteractionDraftGate()).interactive)
        assertFalse(host.bindOpenUi(saved.copy(isStreaming = true), RichInteractionDraftGate()).interactive)
        assertFalse(host.bindOpenUi(saved.copy(content = saved.content.removeSuffix("```")), RichInteractionDraftGate()).interactive)
        host.beginAttempt(saved, "retry")
        assertFalse(host.canAcceptOpenUi(OpenUiHostAction(reopened.identity, reopened.revision, "ToAssistant", "Continue"), saved, RichInteractionDraftGate()))
        assertFalse(host.bindOpenUi(saved, RichInteractionDraftGate()).interactive)
    }

    @Test
    fun nextTurnDoesNotDisableLocalControlsCopyOrLinksButStillProtectsComposer() {
        val saved = source.copy(isPersisted = true)
        val host = host()
        val gate = RichInteractionDraftGate(isBusy = true, isPersisting = true)
        val before = host.bindOpenUi(saved, RichInteractionDraftGate())
        host.beginAttempt(source.copy(turnKey = "next-turn"), "next")
        val binding = host.bindOpenUi(saved, gate)
        assertTrue(binding.interactive)
        assertTrue(binding == before)
        val copy = OpenUiHostAction(binding.identity, binding.revision, "CopyToClipboard", "Copied")
        assertTrue(host.canAcceptOpenUi(copy, saved, gate))
        assertTrue(host.canAcceptOpenUi(copy.copy(action = "OpenUrl", value = "https://example.com"), saved, gate))
        assertFalse(host.canAcceptOpenUi(copy.copy(action = "ToAssistant"), saved, gate))
        assertTrue(host.canAcceptOpenUi(copy.copy(action = "ToAssistant"), saved, RichInteractionDraftGate()))
    }

    @Test
    fun nativePolicyBoundsPayloadsAndRejectsNonHttpNavigation() {
        val event = OpenUiHostAction("id", "rev", "OpenUrl", "https://example.com/path")
        assertTrue(OpenUiActionPolicy.accepts(event))
        listOf("javascript:alert(1)", "file:///sdcard/file", "intent://app", "https://user:password@example.com", "https://", "http://example.com/\u0000").forEach {
            assertFalse(OpenUiActionPolicy.accepts(event.copy(value = it)))
        }
        assertFalse(OpenUiActionPolicy.accepts(event.copy(action = "send")))
        assertFalse(OpenUiActionPolicy.accepts(event.copy(action = "ToAssistant", value = "a".repeat(32769))))
    }
}
