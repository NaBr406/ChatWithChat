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

    /** Simulates 120 Hz frames; `arrivals` maps a frame index to characters arriving on it. */
    private fun simulatePacer(
        frames: Int,
        streamingFrames: Int,
        arrivals: (Int) -> Int
    ): List<Int> {
        val pacer = StreamingTextPacer()
        var target = 0
        var visible = 0
        return (0 until frames).map { frame ->
            target += arrivals(frame)
            val release = pacer.advance(
                frameMillis = 1_000f / 120f,
                targetTotal = target,
                backlog = target - visible,
                isStreaming = frame < streamingFrames
            )
            assertTrue(release <= target - visible)
            visible += release
            release
        }
    }

    @Test
    fun pacer_burstyArrivals_releaseEvenlyOnceRateIsKnown() {
        // 150 characters every 300 ms (36 frames): 500 characters a second, delivered in bursts.
        val releases = simulatePacer(frames = 1_200, streamingFrames = 1_200) { frame ->
            if (frame % 36 == 0) 150 else 0
        }
        val steady = releases.drop(600).take(576)
        // 25 characters per 50 ms on average; the bursts must not show up in the pace.
        val perFiftyMillis = steady.windowed(6).map { it.sum() }

        assertTrue("pace ${perFiftyMillis.min()}..${perFiftyMillis.max()}", perFiftyMillis.all { it in 21..29 })
        assertTrue(steady.max() <= 8)
        // Few frames stall: the reserve covers the gap until the next burst.
        assertTrue(steady.count { it == 0 } < steady.size / 3)
    }

    @Test
    fun pacer_afterStreamEnds_drainsBacklogInBoundedTime() {
        val releases = simulatePacer(frames = 600, streamingFrames = 30) { frame ->
            if (frame == 0) 2_000 else 0
        }
        var shown = 0
        val drainedAt = releases.indexOfFirst { release -> shown += release; shown == 2_000 }

        assertTrue("drained at frame $drainedAt", drainedAt in 30..300)
    }

    @Test
    fun pacer_withoutBacklog_releasesNothing() {
        val releases = simulatePacer(frames = 120, streamingFrames = 120) { 0 }

        assertTrue(releases.all { it == 0 })
    }
}
