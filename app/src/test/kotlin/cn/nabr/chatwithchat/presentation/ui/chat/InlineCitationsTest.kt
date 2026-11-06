package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.ui.text.buildAnnotatedString
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineCitationsTest {

    private val sources = CitationSources.of(
        listOf(
            source("Reuters story", "https://www.reuters.com/world/story-1/"),
            source("AP story", "https://apnews.com/article/abc?utm_source=feed"),
            source("维基百科", "https://zh.wikipedia.org/wiki/%E4%B8%AD%E5%9B%BD"),
            source("BBC", "https://news.bbc.co.uk/2/hi/123")
        )
    )

    private fun source(title: String, url: String) = MessageSourceMetadata(
        title = title,
        url = url,
        sourceToolName = "web_search"
    )

    @Test
    fun `url key ignores scheme, www, trailing slash, fragment and tracking parameters`() {
        assertEquals(
            citationUrlKey("https://www.reuters.com/world/story-1/"),
            citationUrlKey("http://reuters.com/world/story-1#top")
        )
        assertEquals(
            citationUrlKey("https://apnews.com/article/abc"),
            citationUrlKey("https://apnews.com/article/abc?utm_source=feed&utm_medium=x")
        )
        assertEquals(
            citationUrlKey("https://zh.wikipedia.org/wiki/%E4%B8%AD%E5%9B%BD"),
            citationUrlKey("https://zh.wikipedia.org/wiki/中国")
        )
    }

    @Test
    fun `url key keeps parameters and paths that identify a different page`() {
        assertFalse(citationUrlKey("https://example.com/a?id=1") == citationUrlKey("https://example.com/a?id=2"))
        assertFalse(citationUrlKey("https://example.com/a") == citationUrlKey("https://example.com/b"))
        assertNull(citationUrlKey("javascript:alert(1)"))
        assertNull(citationUrlKey("not a url"))
    }

    @Test
    fun `only urls among the message sources resolve`() {
        assertEquals(0, sources.indexOfUrl("https://reuters.com/world/story-1"))
        assertEquals(1, sources.indexOfUrl("https://apnews.com/article/abc"))
        assertNull(sources.indexOfUrl("https://reuters.com/world/another-story"))
    }

    @Test
    fun `site name drops subdomains and public suffixes`() {
        assertEquals("reuters", citationSiteName(sources.items[0]))
        assertEquals("wikipedia", citationSiteName(sources.items[2]))
        assertEquals("bbc", citationSiteName(sources.items[3]))
    }

    @Test
    fun `descriptive link text is not a citation marker`() {
        assertTrue(isCitationLabelLike("Reuters"))
        assertTrue(isCitationLabelLike("1"))
        assertFalse(isCitationLabelLike("据路透社报道，该公司本季度营收增长了百分之二十。"))
        assertFalse(isCitationLabelLike("a long descriptive sentence that keeps going"))
    }

    @Test
    fun `pre-scan groups adjacent citations and nothing else`() {
        val markdown = "增长了 5%[Reuters](https://reuters.com/world/story-1) [AP](https://apnews.com/article/abc)。" +
            "另见 [官网](https://example.com/unrelated)，以及 [BBC](https://news.bbc.co.uk/2/hi/123)。"

        assertEquals(setOf(citationGroupId(listOf(0, 1))), scanCitationGroupIds(markdown, sources))
    }

    @Test
    fun `annotator renders a run of citations as one chip and keeps other links`() {
        val markdown = "Growth was 5% [Reuters](https://reuters.com/world/story-1) [AP](https://apnews.com/article/abc). " +
            "See [the company site](https://example.com/x)."
        val registered = (sources.items.indices.map { citationGroupId(listOf(it)) } + scanCitationGroupIds(markdown, sources)).toSet()

        val rendered = renderParagraph(markdown, registered)

        val chips = rendered.getStringAnnotations(INLINE_CONTENT_TAG, 0, rendered.length).map { it.item }
        assertEquals(listOf(citationGroupId(listOf(0, 1))), chips)
        assertTrue(rendered.text.startsWith("Growth was 5% [reuters, apnews]."))
        // The unrelated link is not a source, so the annotator leaves it to the normal link renderer.
        assertTrue(rendered.text.contains("<link>"))
    }

    @Test
    fun `annotator falls back to one chip per source when a run was not registered`() {
        val markdown = "Fact [Reuters](https://reuters.com/world/story-1)[AP](https://apnews.com/article/abc)"
        val singlesOnly = sources.items.indices.map { citationGroupId(listOf(it)) }.toSet()

        val rendered = renderParagraph(markdown, singlesOnly)

        val chips = rendered.getStringAnnotations(INLINE_CONTENT_TAG, 0, rendered.length).map { it.item }
        assertEquals(listOf(citationGroupId(listOf(0)), citationGroupId(listOf(1))), chips)
    }

    @Test
    fun `bare source url becomes a chip and keeps the full-width punctuation after it`() {
        val markdown = "详见 https://reuters.com/world/story-1。"
        val registered = sources.items.indices.map { citationGroupId(listOf(it)) }.toSet()

        val rendered = renderParagraph(markdown, registered)

        val chips = rendered.getStringAnnotations(INLINE_CONTENT_TAG, 0, rendered.length).map { it.item }
        assertEquals(listOf(citationGroupId(listOf(0))), chips)
        assertTrue(rendered.text.endsWith("。"))
    }

    @Test
    fun `trailing partial link is hidden while streaming`() {
        assertEquals("Growth was 5% ", "Growth was 5% [Reuters](https://reu".withoutTrailingPartialLink())
        assertEquals("Growth was 5% ", "Growth was 5% [Reuters]".withoutTrailingPartialLink())
        assertEquals("Done [Reuters](https://reuters.com).", "Done [Reuters](https://reuters.com).".withoutTrailingPartialLink())
        assertEquals("an array [i", "an array [i".withoutTrailingPartialLink())
    }

    @Test
    fun `composer stays open while there is draft text and folds after sending`() {
        assertTrue(composerExpansionTarget(isInputFocused = true, hasDraftText = false, collapsedAfterSend = false))
        assertFalse(composerExpansionTarget(isInputFocused = false, hasDraftText = false, collapsedAfterSend = false))
        assertTrue(composerExpansionTarget(isInputFocused = false, hasDraftText = true, collapsedAfterSend = false))
        assertFalse(composerExpansionTarget(isInputFocused = true, hasDraftText = false, collapsedAfterSend = true))
        assertTrue(composerExpansionTarget(isInputFocused = true, hasDraftText = true, collapsedAfterSend = true))
    }

    /** Mimics the renderer: the annotator first, then plain text for anything it does not consume. */
    private fun renderParagraph(markdown: String, registered: Set<String>) = buildAnnotatedString {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
        val paragraph = tree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        paragraph.children.forEach { child: ASTNode ->
            if (!appendCitationIfMatched(markdown, child, sources, registered)) {
                if (child.type == MarkdownElementTypes.INLINE_LINK) {
                    append("<link>")
                } else {
                    append(markdown.substring(child.startOffset, child.endOffset))
                }
            }
        }
    }

    private companion object {
        const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"
    }
}
