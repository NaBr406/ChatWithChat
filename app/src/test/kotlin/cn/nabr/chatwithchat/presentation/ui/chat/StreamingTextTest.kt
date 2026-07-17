package cn.nabr.chatwithchat.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTextTest {
    @Test
    fun streamingStep_largeBacklog_keepsStablePace() {
        val text = "reasoning ".repeat(2_000)

        val nextEnd = nextStreamingTextEnd(text, currentIndex = 0)

        assertTrue(nextEnd in 3..16)
    }

    @Test
    fun completionDrain_largeBacklog_startsSmallAndFinishesWithinFrameBudget() {
        val text = "reasoning ".repeat(2_000)
        var currentIndex = 0

        repeat(STREAMING_TEXT_COMPLETION_FRAME_COUNT) { frameIndex ->
            val previousIndex = currentIndex
            currentIndex = nextCompletedStreamingTextEnd(
                text = text,
                currentIndex = currentIndex,
                completionStartIndex = 0,
                completionFrame = frameIndex + 1
            )

            if (frameIndex == 0) {
                assertTrue(currentIndex - previousIndex in 3..16)
            }
            if (frameIndex == 12) {
                assertTrue(currentIndex < text.length)
            }
        }

        assertEquals(text.length, currentIndex)
    }

    @Test
    fun completionDrain_surrogatePairs_neverSplitsPair() {
        val text = "思考😀中".repeat(4_000)
        var currentIndex = 0

        repeat(STREAMING_TEXT_COMPLETION_FRAME_COUNT) { frameIndex ->
            currentIndex = nextCompletedStreamingTextEnd(
                text = text,
                currentIndex = currentIndex,
                completionStartIndex = 0,
                completionFrame = frameIndex + 1
            )

            assertFalse(
                currentIndex in 1 until text.length && Character.isLowSurrogate(text[currentIndex])
            )
        }

        assertEquals(text.length, currentIndex)
    }
}
