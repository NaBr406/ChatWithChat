package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.database.entity.AssistantRevision
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.snapshotLatestAssistantRevision
import cn.nabr.chatwithchat.data.richcontent.RichContentParser
import cn.nabr.chatwithchat.data.richcontent.RichNode
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionAction
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionBinding
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionBindingAdapter
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionEvent
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionRegistry
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionResult
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionRuntime
import cn.nabr.chatwithchat.util.StreamTerminalOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RichInteractionHostControllerTest {
    private val source = RichInteractionHostSource(
        chatId = 1,
        turnKey = "turn-1",
        platformUid = "platform-1",
        revisionKey = "revision:0",
        revisionFingerprint = "revision-1",
        content = "<ui-button key=\"go\" label=\"Fill\" prompt=\"Continue\" />"
    )

    private fun controller(maxSourceChars: Int = 1048576): RichInteractionHostController {
        val adapter = object : RichInteractionBindingAdapter {
            override val componentName = "ui-button"

            override fun bind(node: RichNode.Component) = RichInteractionBinding(
                actions = listOf(RichInteractionAction("go", node.attributes.getValue("prompt")))
            )
        }
        return RichInteractionHostController(
            RichInteractionRuntime(RichInteractionRegistry(listOf(adapter))),
            RichContentParser(),
            maxSourceChars = maxSourceChars
        )
    }

    @Test
    fun unknownHistoryAndUnsuccessfulOrUnsavedAnswersRemainStatic() {
        for (outcome in StreamTerminalOutcome.entries) {
            for (saved in listOf(false, true)) {
                val host = controller()
                assertFalse(host.bind(source, RichInteractionDraftGate()).session.value.isInteractionEnabled)
                host.beginAttempt(source, "attempt")
                host.completeAttempt(source, "attempt", outcome, saved)
                val binding = host.bind(source, RichInteractionDraftGate())
                assertTrue(binding.session.value.isInteractionEnabled == (saved && outcome == StreamTerminalOutcome.Success))
            }
        }
    }

    @Test
    fun everyComposerConflictRejectsActionsWithoutConsumingTheirEvent() {
        val host = controller()
        host.beginAttempt(source, "attempt")
        host.completeAttempt(source, "attempt", StreamTerminalOutcome.Success, true)
        val binding = host.bind(source, RichInteractionDraftGate())
        val event = RichInteractionEvent.InvokeAction(binding.session.value.token, "click", "go")
        val conflicts = listOf(
            RichInteractionDraftGate(isBusy = true),
            RichInteractionDraftGate(isPersisting = true),
            RichInteractionDraftGate(isEditing = true),
            RichInteractionDraftGate(hasDraftText = true),
            RichInteractionDraftGate(hasAttachments = true),
            RichInteractionDraftGate(isImportingAttachments = true),
            RichInteractionDraftGate(hasQueuedQuestion = true)
        )
        conflicts.forEach { gate ->
            assertTrue(host.dispatch(binding.identity, event, source, gate) is RichInteractionResult.Rejected)
        }
        val action = host.dispatch(binding.identity, event, source, RichInteractionDraftGate()) as RichInteractionResult.Action
        assertTrue(host.canAccept(action.snapshot, source, RichInteractionDraftGate()))
        assertFalse(host.canAccept(action.snapshot, source, RichInteractionDraftGate(hasDraftText = true)))
        assertFalse(host.canAccept(action.snapshot, source.copy(revisionFingerprint = "changed"), RichInteractionDraftGate()))
        assertTrue(host.dispatch(binding.identity, event, source, RichInteractionDraftGate()) is RichInteractionResult.Rejected)
    }

    @Test
    fun saveIdentityMigrationAndRepeatedBindingPreserveStableSession() {
        val host = controller()
        host.beginAttempt(source.copy(chatId = 0, turnKey = "temporary"), "attempt")
        host.completeAttempt(source, "attempt", StreamTerminalOutcome.Success, true)
        val binding = host.bind(source, RichInteractionDraftGate())
        assertTrue(binding.session.value.isInteractionEnabled)
        assertSame(binding, host.bind(source, RichInteractionDraftGate()))
    }

    @Test
    fun retryInvalidatesCapturedEventsButKnownArchivedRevisionCanBeRebound() {
        val host = controller()
        host.beginAttempt(source, "first")
        host.completeAttempt(source, "first", StreamTerminalOutcome.Success, true)
        val first = host.bind(source, RichInteractionDraftGate())
        val oldEvent = RichInteractionEvent.InvokeAction(first.session.value.token, "click", "go")
        host.archiveRevision(source, "archived")
        host.beginAttempt(source.copy(revisionFingerprint = "new"), "second")
        assertTrue(host.dispatch(first.identity, oldEvent, source, RichInteractionDraftGate()) is RichInteractionResult.Rejected)
        val archived = source.copy(revisionFingerprint = "archived", isLatestRevision = false)
        val restored = host.bind(archived, RichInteractionDraftGate())
        assertTrue(restored.session.value.isInteractionEnabled)
        assertTrue(host.dispatch(restored.identity, oldEvent, archived, RichInteractionDraftGate()) is RichInteractionResult.Rejected)
        val event = RichInteractionEvent.InvokeAction(restored.session.value.token, "new-click", "go")
        assertTrue(host.dispatch(restored.identity, event, archived, RichInteractionDraftGate()) is RichInteractionResult.Action)
    }

    @Test
    fun oversizedSourceIsProjectedBeforeParsingAndNeverDeclaresActions() {
        val host = controller(maxSourceChars = 4)
        host.beginAttempt(source, "attempt")
        host.completeAttempt(source, "attempt", StreamTerminalOutcome.Success, true)
        val binding = host.bind(source, RichInteractionDraftGate())
        assertTrue(binding.document.nodes.single() is RichNode.Markdown)
        assertTrue(binding.session.value.actions.isEmpty())
    }

    @Test
    fun identicalSameSecondVersionsCannotBorrowEachOthersSuccessEvidence() {
        val host = controller()
        val newest = source.copy(revisionKey = "revision:1")
        host.beginAttempt(newest, "latest-success")
        host.completeAttempt(newest, "latest-success", StreamTerminalOutcome.Success, true)
        assertTrue(host.bind(newest, RichInteractionDraftGate()).session.value.isInteractionEnabled)
        // Same content, thoughts and second produce the exact same fingerprint. The ordinal differs.
        val unknownHistory = newest.copy(revisionKey = "revision:0", isLatestRevision = false)
        assertFalse(host.bind(unknownHistory, RichInteractionDraftGate()).session.value.isInteractionEnabled)
    }

    @Test
    fun retryWhileHistoryIsSelectedArchivesTheActualLatestEvidence() {
        val host = controller()
        val message = MessageV2(
            content = source.content,
            thoughts = "latest thoughts",
            platformType = source.platformUid,
            createdAt = 10,
            revisions = listOf(AssistantRevision("old unknown version", createdAt = 5)),
            activeRevisionIndex = 0
        )
        fun resolved(revision: RichInteractionRevisionSource) = source.copy(
            revisionKey = revision.key,
            revisionFingerprint = revision.fingerprint,
            content = revision.content,
            isLatestRevision = revision.isLatest
        )
        val latest = resolved(message.richInteractionRevisionSource(useLatestRevision = true))
        val selectedOld = resolved(message.richInteractionRevisionSource())
        host.beginAttempt(latest, "first")
        host.completeAttempt(latest, "first", StreamTerminalOutcome.Success, true)
        assertFalse(host.bind(selectedOld, RichInteractionDraftGate()).session.value.isInteractionEnabled)

        val snapshot = checkNotNull(message.snapshotLatestAssistantRevision(timestamp = 20))
        host.archiveRevision(latest, richInteractionRevisionFingerprint(snapshot.content, snapshot.thoughts, snapshot.createdAt))
        val retried = message.copy(content = "new attempt", revisions = listOf(snapshot) + message.revisions)
        host.beginAttempt(resolved(retried.richInteractionRevisionSource(useLatestRevision = true)), "second")

        val archivedLatest = resolved(retried.copy(activeRevisionIndex = 0).richInteractionRevisionSource())
        assertEquals(latest.revisionKey, archivedLatest.revisionKey)
        assertTrue(host.bind(archivedLatest, RichInteractionDraftGate()).session.value.isInteractionEnabled)
        val stillUnknown = resolved(retried.copy(activeRevisionIndex = 1).richInteractionRevisionSource())
        assertEquals(selectedOld.revisionKey, stillUnknown.revisionKey)
        assertFalse(host.bind(stillUnknown, RichInteractionDraftGate()).session.value.isInteractionEnabled)
    }
}
