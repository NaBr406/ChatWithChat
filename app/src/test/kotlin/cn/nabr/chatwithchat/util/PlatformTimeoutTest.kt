package cn.nabr.chatwithchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformTimeoutTest {

    @Test
    fun `platform timeout maps to socket timeout millis`() {
        assertEquals(30_000L, platformTimeoutSecondsToSocketTimeoutMillis(30))
    }

    @Test
    fun `zero platform timeout disables socket timeout`() {
        assertNull(platformTimeoutSecondsToSocketTimeoutMillis(0))
    }

    @Test
    fun `formatPlatformTimeout shows off when disabled`() {
        assertEquals("Off", formatPlatformTimeout(0, "Off"))
    }

    @Test
    fun `formatPlatformTimeout shows seconds in chinese`() {
        assertEquals("30 秒", formatPlatformTimeout(30, "关闭"))
    }
}
