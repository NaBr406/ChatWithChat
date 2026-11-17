package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.database.entity.ACTIVE_REVISION_LATEST
import cn.nabr.chatwithchat.data.database.entity.AssistantRevision
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGlassPolicyTest {
    @Test
    fun streamingFenceDisablesCaptureBeforeAnyProgramBodyArrives() {
        val policy = ChatGlassPolicy()
        listOf("Hello", "Hello\n```", "Hello\n```ope").forEach { content ->
            assertTrue(policy.shouldCaptureLiveContent(listOf(listOf(message(content))), listOf(0)))
        }
        assertFalse(policy.shouldCaptureLiveContent(listOf(listOf(message("Hello\n```openui"))), listOf(0)))
    }

    @Test
    fun ordinaryFencesAndQuotedOpenUiExamplesKeepGlassEnabled() {
        val policy = ChatGlassPolicy()
        val examples = listOf(
            "```kotlin\nval value = 1\n```",
            "````markdown\n```openui\nroot = Col([])\n```\n````",
            "root = Col([])",
            "<ui-card>Legacy</ui-card>"
        )
        examples.forEach { content ->
            assertTrue(policy.shouldCaptureLiveContent(listOf(listOf(message(content))), listOf(0)))
        }
    }

    @Test
    fun openUiInEarlierTurnDisablesCaptureEvenWhenLatestAnswerIsPlain() {
        val policy = ChatGlassPolicy()
        val turns = listOf(listOf(message("```openui\nroot = Col([])\n```")), listOf(message("Latest answer")))
        assertFalse(policy.shouldCaptureLiveContent(turns, listOf(0, 0)))
    }

    @Test
    fun selectedPlatformChangeIsCheckedBeforeRenderingItsAnswer() {
        val policy = ChatGlassPolicy()
        val turns = listOf(listOf(message("Plain answer"), message("~~~openui")))
        assertTrue(policy.shouldCaptureLiveContent(turns, listOf(0)))
        assertFalse(policy.shouldCaptureLiveContent(turns, listOf(1)))
        assertFalse(policy.shouldCaptureLiveContent(turns, listOf(0)))
    }

    @Test
    fun selectedHistoricalRevisionUsesEffectiveContent() {
        val policy = ChatGlassPolicy()
        val latest = message("Plain latest answer").copy(
            revisions = listOf(AssistantRevision(content = "```openui", createdAt = 0)),
            activeRevisionIndex = ACTIVE_REVISION_LATEST
        )
        assertTrue(policy.shouldCaptureLiveContent(listOf(listOf(latest)), listOf(0)))
        assertFalse(policy.shouldCaptureLiveContent(listOf(listOf(latest.copy(activeRevisionIndex = 0))), listOf(0)))
    }

    @Test
    fun unchangedHistoryIsNotReparsedWhileLatestAnswerStreams() {
        val parsed = mutableListOf<String>()
        val policy = ChatGlassPolicy { content ->
            parsed += content
            OpenUiEnvelope.containsOpenUi(content)
        }
        val history = listOf(message("Historical answer"))
        assertTrue(policy.shouldCaptureLiveContent(listOf(history, listOf(message("Live"))), listOf(0, 0)))
        assertTrue(policy.shouldCaptureLiveContent(listOf(history, listOf(message("Live answer"))), listOf(0, 0)))
        assertTrue(policy.shouldCaptureLiveContent(listOf(history, listOf(message("Live answer"))), listOf(0, 0)))
        assertEquals(listOf("Historical answer", "Live", "Live answer"), parsed)
    }

    @Test
    fun internalStickerMarkersAreStrippedBeforeCheckingTheRenderedEnvelope() {
        val content = "[assistant sent sticker: smile]\n```openui"
        var parsed: String? = null
        val policy = ChatGlassPolicy {
            parsed = it
            OpenUiEnvelope.containsOpenUi(it)
        }
        assertFalse(policy.shouldCaptureLiveContent(listOf(listOf(message(content))), listOf(0)))
        assertEquals("\n```openui", parsed)
    }

    @Test
    fun captureStaysDisabledAfterRollbackRemovalAndReplacementUntilNewSession() {
        val policy = ChatGlassPolicy()
        assertFalse(policy.shouldCaptureLiveContent(listOf(listOf(message("```openui"))), emptyList()))
        assertFalse(policy.shouldCaptureLiveContent(listOf(listOf(message("```ope"))), emptyList()))
        assertFalse(policy.shouldCaptureLiveContent(emptyList(), emptyList()))
        assertFalse(policy.shouldCaptureLiveContent(listOf(listOf(message("Replacement"))), listOf(0)))
        assertTrue(ChatGlassPolicy().shouldCaptureLiveContent(listOf(listOf(message("Replacement"))), listOf(0)))
    }

    private fun message(content: String) = MessageV2(content = content, platformType = "probe")
}
