package cn.nabr.chatwithchat.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTextRevealTest {
    @Test
    fun append_keepsOldRangesOnTheirOriginalClock() {
        val session = StreamingRevealSession()
        session.updateSource("old", true, now = 0)
        assertTrue(session.observe("p", "old", 0, 0).isEmpty())
        session.updateSource("old first", true, now = 0)
        assertEquals(listOf(StreamingRevealRange(3, 9, 10)), session.observe("p", "old first", 0, 10))
        session.updateSource("old first next", true, now = 0)
        assertEquals(listOf(StreamingRevealRange(3, 9, 10), StreamingRevealRange(9, 14, 60)), session.observe("p", "old first next", 0, 60))
        assertEquals(listOf(StreamingRevealRange(9, 14, 60)), session.observe("p", "old first next", 0, 220))
        assertTrue(session.observe("p", "old first next", 0, 260).isEmpty())
    }

    @Test
    fun newNode_animatesWhileHistoricalFirstMountDoesNot() {
        val session = StreamingRevealSession()
        session.updateSource("first", true, now = 0)
        session.observe("p", "first", 0, 0)
        session.updateSource("first\n\nsecond", true, now = 0)
        assertEquals(listOf(StreamingRevealRange(0, 6, 10)), session.observe("second", "second", 7, 10))
        val history = StreamingRevealSession()
        history.updateSource("first\n\nsecond", false, now = 0)
        assertTrue(history.observe("second", "second", 7, 10).isEmpty())
    }

    @Test
    fun replacementAndRollback_clearMessageRangesAndEstablishBaseline() {
        listOf("old", "new text", "rewritten and longer").forEach { replacement ->
            val session = StreamingRevealSession()
            session.updateSource("old", true, now = 0)
            session.observe("p", "old", 0, 0)
            session.updateSource("old text", true, now = 0)
            session.observe("p", "old text", 0, 10)
            session.updateSource(replacement, true, now = 0)
            assertTrue(session.observe("p", replacement, 0, 20).isEmpty())
        }
    }

    @Test
    fun formattingRewrite_doesNotReplayOldVisibleText() {
        val session = StreamingRevealSession()
        session.updateSource("**word", true, now = 0)
        session.observe("p", "**word", 0, 0)
        session.updateSource("**word**", true, now = 0)
        assertTrue(session.observe("p", "word", 0, 20).isEmpty())
    }

    @Test
    fun completion_keepsPendingFadeAndDoesNotAnimateNewMounts() {
        val session = StreamingRevealSession()
        session.updateSource("a", true, now = 0)
        session.observe("p", "a", 0, 0)
        session.updateSource("ab", true, now = 0)
        session.observe("p", "ab", 0, 10)
        session.updateSource("ab", false, now = 0)
        assertEquals(listOf(StreamingRevealRange(1, 2, 10)), session.observe("p", "ab", 0, 20))
        assertTrue(session.observe("new", "ab", 0, 20).isEmpty())
    }

    @Test
    fun extendingAnimatedGrapheme_keepsWholeExtendedClusterClear() {
        listOf("👩" to "👩‍💻", "e" to "e\u0301", "\uD83D" to "😀").forEach { (initial, extended) ->
            val session = StreamingRevealSession()
            session.updateSource("a", true, now = 0)
            session.observe("p", "a", 0, 0)
            session.updateSource("a$initial", true, now = 0)
            session.observe("p", "a$initial", 0, 10)
            session.updateSource("a${extended}b", true, now = 0)
            assertEquals(listOf(StreamingRevealRange(1 + extended.length, 2 + extended.length, 20)), session.observe("p", "a${extended}b", 0, 20))
        }
    }

    @Test
    fun rate_fastBurstsShortenFadeWithoutDelayingContent() {
        val session = StreamingRevealSession()
        session.updateSource("old", true, 0)
        session.observe("p", "old", 0, 0)
        val fast = "old" + "字".repeat(20)
        session.updateSource(fast, true, 50)
        val range = session.observe("p", fast, 0, 50).single()
        assertEquals(3, range.start)
        assertEquals(fast.length, range.end)
        assertEquals(100L, range.profile.durationMillis)
        assertEquals(0.55f, range.profile.hiddenAlpha, 0.001f)
        assertTrue(session.observe("p", fast, 0, 150).isEmpty())
    }

    @Test
    fun rate_slowTextKeepsFullFadeAndUnicodeCountsAsCodePoints() {
        val rate = StreamingRevealRate()
        rate.reset(0)
        rate.append("😀".repeat(4), 0, 50)
        assertEquals(StreamingRevealProfile(), rate.profile)
        rate.append("x".repeat(40), 0, 100)
        assertEquals(100L, rate.profile.durationMillis)
        repeat(12) { rate.append("x", 0, 150L + it * 50L) }
        assertEquals(StreamingRevealProfile(), rate.profile)
    }

    @Test
    fun rate_idleRollbackAndReplacementResetEstimate() {
        val rate = StreamingRevealRate()
        rate.reset(0)
        rate.append("x".repeat(40), 0, 50)
        rate.append("x", 0, 700)
        assertEquals(StreamingRevealProfile(), rate.profile)
        listOf("old", "replacement").forEach { replacement ->
            val session = StreamingRevealSession()
            session.updateSource("old", true, 0)
            session.observe("p", "old", 0, 0)
            session.updateSource("old" + "x".repeat(40), true, 50)
            session.observe("p", "old" + "x".repeat(40), 0, 50)
            session.updateSource(replacement, true, 100)
            assertTrue(session.observe("p", replacement, 0, 100).isEmpty())
            session.updateSource(replacement + "a", true, 150)
            assertEquals(StreamingRevealProfile(), session.observe("p", replacement + "a", 0, 150).single().profile)
        }
    }

    @Test
    fun thinkingPromotion_onlyNewPlainSuffixFadesAndFormattingDoesNotReplay() {
        val session = StreamingRevealSession()
        session.updateSource("old", true, 0)
        session.observe("pending", "old", 0, 0)
        session.updateSource("old new\n\nnext", true, 100)
        val ranges = session.observe("block:0", "old new", 0, 100, newLeafBaseline = session.appendStart)
        assertEquals(3, ranges.single().start)
        assertEquals(7, ranges.single().end)
        session.updateSource("old new\n\nnext added", true, 200)
        assertTrue(session.observe("remounted", "old new", 0, 200, newLeafBaseline = session.appendStart).isEmpty())

        val formatted = StreamingRevealSession()
        formatted.updateSource("**word", true, 0)
        formatted.updateSource("**word**\n\nnext", true, 100)
        assertTrue(formatted.observe("block", "word", 0, 100, newLeafBaseline = formatted.appendStart).isEmpty())
    }

    @Test
    fun thinkingPreviewWindowShift_onlyNewSuffixFades() {
        val session = StreamingRevealSession()
        val original = "a".repeat(4_100)
        session.updateSource(original, true, 0)
        val before = pendingThinkingPreview(original)
        assertTrue(session.observe("pending", before, 100, 0, windowPrefixLength = 2).isEmpty())
        session.updateSource(original + "new", true, 50)
        val after = pendingThinkingPreview(original + "new")
        val range = session.observe("pending", after, 103, 50, windowPrefixLength = 2).single()
        assertEquals(after.length - 3, range.start)
        assertEquals(after.length, range.end)
    }

    @Test
    fun thinkingPreview_identicalSlidingTextStillRevealsNewCharacters() {
        val session = StreamingRevealSession()
        val original = "a".repeat(4_100)
        session.updateSource(original, true, 0)
        val preview = pendingThinkingPreview(original)
        session.observe("pending", preview, 100, 0, windowPrefixLength = 2)
        session.updateSource(original + "aaa", true, 50)
        val range = session.observe("pending", preview, 103, 50, windowPrefixLength = 2).single()
        assertEquals(preview.length - 3, range.start)
        assertEquals(preview.length, range.end)
    }

    @Test
    fun rate_interpolatesAt200AndSaturatesAt240CodePointsPerSecond() {
        val medium = StreamingRevealRate()
        medium.reset(0)
        medium.append("字".repeat(10), 0, 50)
        assertEquals(125L, medium.profile.durationMillis)
        assertEquals(0.6625f, medium.profile.hiddenAlpha, 0.001f)
        val high = StreamingRevealRate()
        high.reset(0)
        high.append("字".repeat(12), 0, 50)
        assertEquals(100L, high.profile.durationMillis)
        assertEquals(0.55f, high.profile.hiddenAlpha, 0.001f)
    }
}
