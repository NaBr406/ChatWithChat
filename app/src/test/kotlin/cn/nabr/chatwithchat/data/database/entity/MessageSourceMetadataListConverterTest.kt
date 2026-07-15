package cn.nabr.chatwithchat.data.database.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSourceMetadataListConverterTest {
    private val converter = MessageSourceMetadataListConverter()

    @Test
    fun `legacy public source json decodes with safe defaults`() {
        val sources = converter.fromString(
            """[{"title":"Android docs","url":"https://developer.android.com/","snippet":"Docs","sourceToolName":"web_search"}]"""
        )

        assertEquals(1, sources.size)
        assertEquals(MessageSourceType.PUBLIC_URL, sources.single().sourceType)
        assertNull(sources.single().localEntityId)
        assertNull(sources.single().appNavigationTarget)
        assertEquals(
            SafeMessageSourceTarget.PublicUrl("https://developer.android.com/"),
            sources.single().safeNavigationTarget()
        )
    }

    @Test
    fun `local app source round trips without a raw uri or file path`() {
        val source = MessageSourceMetadata(
            title = "Project chat",
            snippet = "Relevant discussion",
            sourceToolName = "local_search",
            sourceType = MessageSourceType.LOCAL_APP,
            localEntityId = "chat:42",
            appNavigationTarget = AppSourceNavigationTarget.CHAT_ROOM
        )

        val decoded = converter.fromString(converter.fromList(listOf(source)))

        assertEquals(listOf(source), decoded)
        assertTrue(decoded.single().url.isBlank())
        assertEquals(
            SafeMessageSourceTarget.LocalApp(AppSourceNavigationTarget.CHAT_ROOM, "chat:42"),
            decoded.single().safeNavigationTarget()
        )
    }

    @Test
    fun `only absolute http and https urls are safe public targets`() {
        assertEquals("https://example.com/path", "https://example.com/path".safeHttpUrlOrNull())
        assertEquals("HTTP://example.com", "HTTP://example.com".safeHttpUrlOrNull())
        assertNull("javascript:alert(1)".safeHttpUrlOrNull())
        assertNull("file:///private/file".safeHttpUrlOrNull())
        assertNull("content://app/private".safeHttpUrlOrNull())
        assertNull("/relative/path".safeHttpUrlOrNull())
        assertNull("https://user:secret@example.com".safeHttpUrlOrNull())
    }

    @Test
    fun `public source dedupe normalizes host but preserves path case`() {
        val upperHost = MessageSourceMetadata(
            title = "Upper host",
            url = "HTTPS://EXAMPLE.COM/Report",
            sourceToolName = "web_search"
        )
        val lowerHost = upperHost.copy(url = "https://example.com/Report")
        val differentPath = upperHost.copy(url = "https://example.com/report")

        assertEquals(upperHost.safeDedupeKey(), lowerHost.safeDedupeKey())
        assertFalse(upperHost.safeDedupeKey() == differentPath.safeDedupeKey())
    }

    @Test
    fun `raw paths and arbitrary uris are not safe local entity ids`() {
        assertTrue("chat:42".isSafeLocalEntityId())
        assertTrue("memory_entry-1".isSafeLocalEntityId())
        assertFalse("C:\\Users\\name\\secret.txt".isSafeLocalEntityId())
        assertFalse("/data/user/0/app/file".isSafeLocalEntityId())
        assertFalse("content://app/private".isSafeLocalEntityId())
    }

    @Test
    fun `local source with a raw url is not a safe navigation target`() {
        val source = MessageSourceMetadata(
            title = "Unsafe local source",
            url = "file:///private/file",
            sourceToolName = "local_search",
            sourceType = MessageSourceType.LOCAL_APP,
            localEntityId = "entry_1",
            appNavigationTarget = AppSourceNavigationTarget.MEMORY
        )

        assertNull(source.safeNavigationTarget())
        assertFalse(source.isSafeSource())
    }
}
