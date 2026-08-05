package cn.nabr.chatwithchat.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryQueryNormalizerTest {
    @Test
    fun normalizesLatinWhitespaceAndCjkNgramsDeterministically() {
        val terms = ChatHistoryQueryNormalizer.normalize("  Project   项目管理  ")
        assertEquals("project 项目管理", terms.normalizedText)
        assertTrue(terms.tokens.contains("project"))
        assertTrue(terms.tokens.any { it.startsWith("cjk_") })
        assertTrue(terms.matchQuery.contains("\"project\""))
    }

    @Test
    fun emojiDoesNotBecomeAQueryToken() {
        val terms = ChatHistoryQueryNormalizer.normalize("🙂")
        assertEquals(emptyList<String>(), terms.tokens)
        assertEquals("", terms.matchQuery)
    }
}
