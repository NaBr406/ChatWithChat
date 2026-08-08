package cn.nabr.chatwithchat.data.context

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is what stops a provider outage from turning into an unbounded retry loop.
 *
 * Every turn in a long chat hashes a slightly different batch, so the queue's idempotency key never
 * collides and every turn would queue a brand new fold — each one walking its three attempts into the
 * same dead provider, silently, forever. These tests pin the two behaviours that prevent that: the
 * cooldown holds folds back, and exactly one probe is let through when it expires.
 */
class ContextFoldDegradationRegistryTest {

    @Test
    fun `a healthy pair may always queue a fold`() {
        val registry = ContextFoldDegradationRegistry(clock = MutableClock())

        assertTrue(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))
        assertTrue(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))
        assertNull(registry.current(CHAT_ID, PLATFORM_UID))
    }

    @Test
    fun `recording exhaustion blocks further folds during the cooldown`() {
        val clock = MutableClock()
        val registry = ContextFoldDegradationRegistry(clock = clock, cooldownSeconds = COOLDOWN)

        registry.recordBackgroundFoldExhausted(CHAT_ID, PLATFORM_UID, "context_compaction_call_failed")

        assertFalse(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))
        clock.advanceSeconds(COOLDOWN - 1)
        assertFalse(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))
    }

    @Test
    fun `exactly one probe is let through once the cooldown elapses`() {
        val clock = MutableClock()
        val registry = ContextFoldDegradationRegistry(clock = clock, cooldownSeconds = COOLDOWN)
        registry.recordBackgroundFoldExhausted(CHAT_ID, PLATFORM_UID, "context_compaction_timed_out")

        clock.advanceSeconds(COOLDOWN)

        assertTrue(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))
        // The successful claim refreshed the window, so the very next send does not get a second probe.
        assertFalse(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))
    }

    @Test
    fun `a declined claim does not consume the probe`() {
        val clock = MutableClock()
        val registry = ContextFoldDegradationRegistry(clock = clock, cooldownSeconds = COOLDOWN)
        registry.recordBackgroundFoldExhausted(CHAT_ID, PLATFORM_UID, "context_compaction_call_failed")
        val recordedAt = registry.current(CHAT_ID, PLATFORM_UID)!!.recordedAtEpochSecond

        clock.advanceSeconds(COOLDOWN / 2)
        assertFalse(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))

        assertEquals(recordedAt, registry.current(CHAT_ID, PLATFORM_UID)!!.recordedAtEpochSecond)
    }

    @Test
    fun `a successful fold clears the degradation immediately`() {
        val clock = MutableClock()
        val registry = ContextFoldDegradationRegistry(clock = clock, cooldownSeconds = COOLDOWN)
        registry.recordBackgroundFoldExhausted(CHAT_ID, PLATFORM_UID, "context_compaction_invalid_output")
        assertFalse(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))

        registry.clear(CHAT_ID, PLATFORM_UID)

        assertNull(registry.current(CHAT_ID, PLATFORM_UID))
        assertTrue(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))
    }

    @Test
    fun `clearing a pair that was never degraded is a no-op`() {
        val registry = ContextFoldDegradationRegistry(clock = MutableClock())

        registry.clear(CHAT_ID, PLATFORM_UID)

        assertNull(registry.current(CHAT_ID, PLATFORM_UID))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `repeated exhaustion accumulates the occurrence count and refreshes the window`() {
        val clock = MutableClock()
        val registry = ContextFoldDegradationRegistry(clock = clock, cooldownSeconds = COOLDOWN)

        registry.recordBackgroundFoldExhausted(CHAT_ID, PLATFORM_UID, "context_compaction_call_failed")
        clock.advanceSeconds(COOLDOWN * 2)
        val second = registry.recordBackgroundFoldExhausted(CHAT_ID, PLATFORM_UID, "context_compaction_timed_out")

        assertEquals(2, second.occurrenceCount)
        assertEquals("context_compaction_timed_out", second.reason)
        assertEquals(clock.instant().epochSecond, second.recordedAtEpochSecond)
        assertFalse(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))
    }

    @Test
    fun `count survives the cooldown so a repeatedly failing pair stays distinguishable`() {
        val clock = MutableClock()
        val registry = ContextFoldDegradationRegistry(clock = clock, cooldownSeconds = COOLDOWN)
        registry.recordBackgroundFoldExhausted(CHAT_ID, PLATFORM_UID, "context_compaction_call_failed")

        clock.advanceSeconds(COOLDOWN)
        assertTrue(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))

        val current = registry.current(CHAT_ID, PLATFORM_UID)
        assertNotNull(current)
        assertEquals(1, current!!.occurrenceCount)
    }

    @Test
    fun `degradation is scoped to one chat and provider pair`() {
        val clock = MutableClock()
        val registry = ContextFoldDegradationRegistry(clock = clock, cooldownSeconds = COOLDOWN)

        registry.recordBackgroundFoldExhausted(CHAT_ID, PLATFORM_UID, "context_compaction_call_failed")

        assertFalse(registry.claimBackgroundFoldSlot(CHAT_ID, PLATFORM_UID))
        assertTrue(registry.claimBackgroundFoldSlot(CHAT_ID, "other-platform"))
        assertTrue(registry.claimBackgroundFoldSlot(CHAT_ID + 1, PLATFORM_UID))
        assertNull(registry.current(CHAT_ID, "other-platform"))
    }

    @Test
    fun `snapshot lists every degraded pair in a stable order`() {
        val registry = ContextFoldDegradationRegistry(clock = MutableClock(), cooldownSeconds = COOLDOWN)

        registry.recordBackgroundFoldExhausted(9, "zeta", "context_compaction_call_failed")
        registry.recordBackgroundFoldExhausted(2, "beta", "context_compaction_timed_out")
        registry.recordBackgroundFoldExhausted(2, "alpha", "context_compaction_invalid_output")

        assertEquals(
            listOf(2 to "alpha", 2 to "beta", 9 to "zeta"),
            registry.snapshot().map { it.chatRoomId to it.platformUid }
        )
    }

    @Test
    fun `default cooldown is thirty minutes`() {
        assertEquals(30 * 60L, ContextFoldDegradationRegistry.DEFAULT_COOLDOWN_SECONDS)
    }

    /** A [Clock] whose instant only moves when a test moves it, so cooldowns are exact. */
    private class MutableClock(
        private var current: Instant = Instant.ofEpochSecond(1_700_000_000L),
        private val zone: ZoneId = ZoneOffset.UTC
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }

    private companion object {
        const val CHAT_ID = 7
        const val PLATFORM_UID = "platform"
        const val COOLDOWN = 600L
    }
}
