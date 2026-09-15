package cn.nabr.chatwithchat.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IncrementalThinkingMarkdownTest {
    @Test
    fun update_commitsOnlyCompletedParagraphs() {
        val accumulator = ThinkingMarkdownAccumulator()

        val snapshot = accumulator.update(
            text = "first paragraph\n\nsecond paragraph\n",
            forceFlush = false
        )

        assertEquals(listOf("first paragraph"), snapshot.blocks.map { it.content })
        assertEquals("second paragraph\n", snapshot.pendingText)
    }

    @Test
    fun appendOnlyUpdate_reusesPreviouslyCommittedBlock() {
        val accumulator = ThinkingMarkdownAccumulator()
        val first = accumulator.update(
            text = "first paragraph\n\nnext line\n",
            forceFlush = false
        )
        val second = accumulator.update(
            text = "first paragraph\n\nnext line\n\nsecond paragraph\n",
            forceFlush = false
        )

        assertSame(first.blocks.single(), second.blocks.first())
        assertEquals(
            listOf("first paragraph", "next line"),
            second.blocks.map { it.content }
        )
        assertEquals("second paragraph\n", second.pendingText)
    }

    @Test
    fun fencedCodeBlock_commitsAsOneBlockAfterClosingFence() {
        val accumulator = ThinkingMarkdownAccumulator()
        val snapshot = accumulator.update(
            text = "before\n\n```kotlin\nval first = 1\n\nval second = 2\n```\n\nafter",
            forceFlush = false
        )

        assertEquals(listOf("before", "```kotlin\nval first = 1\n\nval second = 2\n```"), snapshot.blocks.map { it.content })
        assertEquals("after", snapshot.pendingText)
    }

    @Test
    fun forceFlush_commitsPendingTail() {
        val accumulator = ThinkingMarkdownAccumulator()
        val pending = accumulator.update(
            text = "unfinished paragraph",
            forceFlush = false
        )
        val flushed = accumulator.update(
            text = "unfinished paragraph",
            forceFlush = true
        )

        assertEquals("unfinished paragraph", pending.pendingText)
        assertEquals(listOf("unfinished paragraph"), flushed.blocks.map { it.content })
        assertEquals("", flushed.pendingText)
    }

    @Test
    fun rewrite_resetsCommittedBlocksAndIdentifiers() {
        val accumulator = ThinkingMarkdownAccumulator()
        accumulator.update(text = "old paragraph\n\nnext line\n", forceFlush = false)

        val rewritten = accumulator.update(text = "new paragraph\n\nnext line\n", forceFlush = false)

        assertEquals(0, rewritten.blocks.single().id)
        assertEquals(listOf("new paragraph"), rewritten.blocks.map { it.content })
    }

    @Test
    fun listWithBlankLines_staysInOneMarkdownBlock() {
        val accumulator = ThinkingMarkdownAccumulator()
        val streaming = accumulator.update(
            text = "1. first item\n\n2. second item\n",
            forceFlush = false
        )
        val flushed = accumulator.update(
            text = "1. first item\n\n2. second item\n",
            forceFlush = true
        )

        assertEquals(emptyList<String>(), streaming.blocks.map { it.content })
        assertEquals(listOf("1. first item\n\n2. second item"), flushed.blocks.map { it.content })
    }

    @Test
    fun pendingPreview_keepsOnlyLatestWindow() {
        val content = "old reasoning ".repeat(2_000) + "latest reasoning"

        assertEquals(
            "…\n" + content.takeLast(100),
            pendingThinkingPreview(text = content, maxCharacters = 100)
        )
    }
}
