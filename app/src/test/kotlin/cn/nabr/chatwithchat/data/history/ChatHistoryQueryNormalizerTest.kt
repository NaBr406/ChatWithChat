package cn.nabr.chatwithchat.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryQueryNormalizerTest {
    @Test
    fun `normalizes whitespace and creates deterministic CJK ngrams`() {
        assertEquals("hello world", ChatHistoryQueryNormalizer.normalize("  HELLO   World "))

        val terms = ChatHistoryQueryNormalizer.searchTerms("北京旅行")

        assertTrue(terms.contains("北京"))
        assertTrue(terms.contains("京旅"))
        assertTrue(terms.contains("北京旅"))
        assertEquals(terms, ChatHistoryQueryNormalizer.searchTerms("北京旅行"))
    }

    @Test
    fun `fts expression quotes terms`() {
        val expression = ChatHistoryQueryNormalizer.ftsMatchExpression("hello world")
        assertEquals("\"hello\" OR \"world\"", expression)
    }

    @Test
    fun `cjk expression requires all deterministic ngrams`() {
        val expression = ChatHistoryQueryNormalizer.ftsMatchExpression("北京")
        assertEquals("\"北\" AND \"京\" AND \"北京\"", expression)
    }
}
