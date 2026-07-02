package dev.chungjungsoo.gptmobile.data.tool

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentDateTimeToolProviderTest {
    @Test
    fun `current datetime tool returns bounded deterministic local time`() = runBlocking {
        val provider = CurrentDateTimeToolProvider(
            clock = Clock.fixed(Instant.parse("2026-07-02T03:04:05Z"), ZoneOffset.UTC),
            zoneId = ZoneId.of("Asia/Shanghai")
        )

        val result = provider.execute(
            ToolCall(
                id = "call_datetime",
                name = "current_datetime",
                arguments = "{}"
            ),
            ToolLoopConfig.Default
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("Current local date/time:"))
        assertTrue(result.content.contains("ISO: 2026-07-02T11:04:05+08:00"))
        assertEquals("2026-07-02T11:04:05+08:00", result.metadata["iso_datetime"])
        assertEquals("2026-07-02", result.metadata["date"])
        assertEquals("11:04:05", result.metadata["time"])
        assertEquals("Asia/Shanghai", result.metadata["timezone"])
    }

    @Test
    fun `current datetime tool exposes low read only policy`() {
        val policy = CurrentDateTimeToolProvider().policy

        assertEquals(1, policy.maxCallsPerRequest)
        assertEquals(2, policy.maxCallsPerChat)
        assertEquals(2L, policy.timeoutSeconds)
        assertEquals(500, policy.maxResultChars)
    }
}
