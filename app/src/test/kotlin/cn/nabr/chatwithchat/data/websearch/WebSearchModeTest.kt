package cn.nabr.chatwithchat.data.websearch

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchModeTest {
    @Test
    fun `only off and auto modes remain available`() {
        assertEquals(listOf(WebSearchMode.Off, WebSearchMode.Auto), WebSearchMode.entries)
    }

    @Test
    fun `legacy always storage value migrates to auto`() {
        assertEquals(WebSearchMode.Auto, WebSearchMode.fromStorageValue("always"))
        assertEquals(WebSearchMode.Auto, WebSearchMode.fromStorageValue(" ALWAYS "))
    }

    @Test
    fun `unknown and missing storage values remain off`() {
        assertEquals(WebSearchMode.Off, WebSearchMode.fromStorageValue(null))
        assertEquals(WebSearchMode.Off, WebSearchMode.fromStorageValue("unknown"))
    }
}
