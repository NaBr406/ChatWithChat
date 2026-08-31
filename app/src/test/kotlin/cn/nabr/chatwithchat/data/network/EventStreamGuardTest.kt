package cn.nabr.chatwithchat.data.network

import io.ktor.http.ContentType
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStreamGuardTest {
    @Test
    fun `refuses an html body and names the api url`() {
        val message = htmlResponseErrorOrNull(ContentType.parse("text/html; charset=utf-8"))

        assertTrue(message.orEmpty().contains("API URL"))
    }

    @Test
    fun `accepts an event stream body`() {
        assertNull(htmlResponseErrorOrNull(ContentType.Text.EventStream))
    }

    @Test
    fun `accepts a mislabelled json body so working providers keep streaming`() {
        assertNull(htmlResponseErrorOrNull(ContentType.Application.Json))
    }

    @Test
    fun `accepts a missing content type`() {
        assertNull(htmlResponseErrorOrNull(null))
    }
}
