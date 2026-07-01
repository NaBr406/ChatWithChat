package dev.chungjungsoo.gptmobile.data.websearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchPromptBuilderTest {

    @Test
    fun `empty results return null`() {
        val builder = WebSearchPromptBuilder()

        assertNull(builder.build(emptyList()))
    }

    @Test
    fun `prompt includes source fields and citation instruction`() {
        val builder = WebSearchPromptBuilder()

        val prompt = builder.build(
            listOf(
                WebSearchResult(
                    title = "Example Title",
                    url = "https://example.com/page",
                    snippet = "Example snippet",
                    source = "searxng",
                    publishedAt = "2026-07-01"
                )
            )
        ).orEmpty()

        assertTrue(prompt.contains("Use these results only when they are relevant"))
        assertTrue(prompt.contains("cite the source URLs"))
        assertTrue(prompt.contains("Example Title"))
        assertTrue(prompt.contains("https://example.com/page"))
        assertTrue(prompt.contains("Example snippet"))
        assertTrue(prompt.contains("searxng"))
        assertTrue(prompt.contains("2026-07-01"))
    }

    @Test
    fun `prompt dedupes limits and clips snippets`() {
        val builder = WebSearchPromptBuilder(maxResults = 2, maxSnippetChars = 8, maxPromptChars = 2_000)

        val prompt = builder.build(
            listOf(
                result("One", "https://one.example", "1234567890"),
                result("Duplicate", "https://one.example", "duplicate"),
                result("Two", "https://two.example", "abcdefghi"),
                result("Three", "https://three.example", "third")
            )
        ).orEmpty()

        assertTrue(prompt.contains("1. One"))
        assertTrue(prompt.contains("2. Two"))
        assertFalse(prompt.contains("Duplicate"))
        assertFalse(prompt.contains("Three"))
        assertTrue(prompt.contains("Snippet: 12345678"))
        assertFalse(prompt.contains("1234567890"))
    }

    @Test
    fun `prompt is clipped to max prompt length`() {
        val builder = WebSearchPromptBuilder(maxResults = 1, maxSnippetChars = 1_000, maxPromptChars = 80)

        val prompt = builder.build(listOf(result("One", "https://one.example", "x".repeat(500)))).orEmpty()

        assertEquals(80, prompt.length)
    }

    private fun result(title: String, url: String, snippet: String) = WebSearchResult(
        title = title,
        url = url,
        snippet = snippet,
        source = "test"
    )
}

