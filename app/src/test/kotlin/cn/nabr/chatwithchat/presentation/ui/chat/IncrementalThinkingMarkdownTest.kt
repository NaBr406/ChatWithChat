package cn.nabr.chatwithchat.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalThinkingMarkdownTest {
    @Test
    fun longLineAcrossChunks_preservesTextWhenNewlineFinallyArrives() {
        val accumulator = ThinkingMarkdownAccumulator()
        val text = StringBuilder()
        repeat(250) {
            text.append("推理内容 ".repeat(20))
            val snapshot = accumulator.update(text.toString(), forceFlush = false)
            assertEquals(text.toString(), snapshot.pendingText)
        }
        val snapshot = accumulator.update("$text\n\nnext\n", forceFlush = false)

        assertEquals(text.toString(), snapshot.blocks.single().content)
        assertEquals("next\n", snapshot.pendingText)
        assertEquals(text.length + 2, snapshot.pendingStart)
    }

    @Test
    fun rollbackDuringIncompleteLine_resetsNewlineSearchPosition() {
        val accumulator = ThinkingMarkdownAccumulator()
        accumulator.update("old\n\n" + "x".repeat(10_000), forceFlush = false)
        accumulator.update("new", forceFlush = false)

        val snapshot = accumulator.update("new\n\nnext\n", forceFlush = false)

        assertEquals(listOf("new"), snapshot.blocks.map { it.content })
        assertEquals("next\n", snapshot.pendingText)
    }

    @Test
    fun manyCommittedParagraphs_keepPublishedSnapshotsImmutable() {
        val accumulator = ThinkingMarkdownAccumulator()
        val text = (0 until 1_000).joinToString("\n\n") { "paragraph $it" }
        val first = accumulator.update("$text\n\npending\n", forceFlush = false)
        val appended = accumulator.update("$text\n\npending\nmore", forceFlush = false)
        assertSame(first.blocks, appended.blocks)

        val finished = accumulator.update("$text\n\npending\nmore", forceFlush = true)
        assertEquals(1_000, first.blocks.size)
        assertEquals(1_001, finished.blocks.size)
        first.blocks.forEachIndexed { index, block -> assertSame(block, finished.blocks[index]) }

        accumulator.update("replacement", forceFlush = true)
        assertEquals(1_001, finished.blocks.size)
        assertEquals("paragraph 0", first.blocks.first().content)
    }

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
    fun tableTail_staysPendingUntilParagraphBoundary() {
        val accumulator = ThinkingMarkdownAccumulator()

        val snapshot = accumulator.update(
            text = "| a | b |\n|---|---|\n| 1 | 2 |\n\n\nnext",
            forceFlush = false
        )

        assertEquals(listOf("| a | b |\n|---|---|\n| 1 | 2 |"), snapshot.blocks.map { it.content })
        assertEquals("next", snapshot.pendingText)
    }

    @Test
    fun unclosedFence_staysInPendingTailUntilClosingFenceArrives() {
        val accumulator = ThinkingMarkdownAccumulator()

        val pending = accumulator.update(
            text = "before\n\n```kotlin\nval value = 1\n",
            forceFlush = false
        )
        val completed = accumulator.update(
            text = "before\n\n```kotlin\nval value = 1\n```\n\nafter",
            forceFlush = false
        )

        assertEquals(listOf("before"), pending.blocks.map { it.content })
        assertEquals("```kotlin\nval value = 1\n", pending.pendingText)
        assertEquals(
            listOf("before", "```kotlin\nval value = 1\n```"),
            completed.blocks.map { it.content }
        )
        assertEquals("after", completed.pendingText)
    }

    @Test
    fun pendingPreview_keepsOnlyLatestWindow() {
        val content = "old reasoning ".repeat(2_000) + "latest reasoning"

        assertEquals(
            "…\n" + content.takeLast(100),
            pendingThinkingPreview(text = content, maxCharacters = 100)
        )
    }

    @Test
    fun softBreakCommit_splitsLongRunOfPlainLines() {
        val lines = (0 until 200).map { "第 $it 行推理内容，没有空行分隔。" }
        val text = lines.joinToString("\n") + "\n"
        val snapshot = ThinkingMarkdownAccumulator(softBreakCommitLength = 300).update(text, forceFlush = false)

        assertTrue("expected the run to be split, got ${snapshot.blocks.size} blocks", snapshot.blocks.size > 5)
        assertTrue(snapshot.blocks.all { it.content.length < 300 + lines.maxOf(String::length) + 1 })
        val rebuilt = (snapshot.blocks.map { it.content } + snapshot.pendingText.trimEnd('\n'))
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        assertEquals(lines.joinToString("\n"), rebuilt)
        snapshot.blocks.forEach { block ->
            assertEquals(block.content, text.substring(block.sourceStart, block.sourceStart + block.content.length))
        }
    }

    @Test
    fun softBreakCommit_matchesWhetherStreamedOrLoadedAtOnce() {
        val text = (0 until 120).joinToString("\n") { "line $it of a long thought" } + "\n\nend"
        val streamed = ThinkingMarkdownAccumulator(softBreakCommitLength = 200)
        var index = 0
        while (index < text.length) {
            index = (index + 7).coerceAtMost(text.length)
            streamed.update(text.substring(0, index), forceFlush = false)
        }
        val streamedBlocks = streamed.update(text, forceFlush = true).blocks
        val loadedBlocks = ThinkingMarkdownAccumulator(softBreakCommitLength = 200).update(text, forceFlush = true).blocks

        assertEquals(loadedBlocks, streamedBlocks)
    }

    @Test
    fun softBreakCommit_neverSplitsListsTablesOrDisplayMath() {
        val list = (0 until 60).joinToString("\n") { "- item $it with some words" }
        val table = "| a | b |\n| - | - |\n" + (0 until 60).joinToString("\n") { "| $it | value |" }
        val math = "intro line\n$$\n" + (0 until 60).joinToString("\n") { "x_$it + y_$it \\\\" } + "\n$$"

        listOf(list, table, math).forEach { block ->
            val snapshot = ThinkingMarkdownAccumulator(softBreakCommitLength = 100).update("$block\n", forceFlush = true)
            assertEquals(listOf(block), snapshot.blocks.map { it.content })
        }
    }

    @Test
    fun softBreakCommit_disabledByDefault() {
        val text = (0 until 200).joinToString("\n") { "plain line $it" } + "\n"

        val snapshot = ThinkingMarkdownAccumulator().update(text, forceFlush = false)

        assertEquals(emptyList<ThinkingMarkdownBlock>(), snapshot.blocks)
    }
}
