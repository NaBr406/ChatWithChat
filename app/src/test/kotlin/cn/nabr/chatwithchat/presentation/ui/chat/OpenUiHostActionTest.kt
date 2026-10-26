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
        assertFalse(host.canAcceptOpenUi(event, source.copy(isLatestRevision = false), RichInteractionDraftGate()))
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
