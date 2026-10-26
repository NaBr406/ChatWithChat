package cn.nabr.chatwithchat.presentation.ui.chat.richcontent

import cn.nabr.chatwithchat.data.richcontent.RichContentParser
import cn.nabr.chatwithchat.data.richcontent.RichNode
import cn.nabr.chatwithchat.data.richcontent.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Test

class RichChatLayoutTest {
    @Test
    fun rowBands_proseBetweenCells_preservesEveryMeaningfulChildInOrder() {
        val source = """
            <ui-row>
            Compare both options.
            <ui-col>First option</ui-col>
            **Read this before the second option.**
            <ui-col>Second option</ui-col>
            Final comparison note.
            </ui-row>
        """.trimIndent()
        val row = RichContentParser().parse(source).nodes.single() as RichNode.Component

        val bands = richRowBands(row.children)

        assertEquals(row.children.filterNot { it is RichNode.Markdown && it.text.isBlank() }, bands.flatten())
        assertEquals(listOf(1, 1, 1, 1, 1), bands.map(List<RichNode>::size))
        assertEquals("**Read this before the second option.**", (bands[2].single() as RichNode.Markdown).text.trim())
    }

    @Test
    fun rowBands_whitespaceBetweenMetrics_groupsAdjacentCellsWithoutSpacerBands() {
        val source = """
            <ui-row>
            <ui-metric label="Latency" value="120 ms" />

            <ui-metric label="Uptime" value="99.9%" />
            </ui-row>
        """.trimIndent()
        val row = RichContentParser().parse(source).nodes.single() as RichNode.Component

        val bands = richRowBands(row.children)

        assertEquals(1, bands.size)
        assertEquals(listOf("Latency", "Uptime"), bands.single().map { (it as RichNode.Component).attributes["label"] })
    }

    @Test
    fun rowBands_fallbackBetweenCells_keepsFallbackVisibleAndSeparatesCells() {
        val first = RichNode.Component("ui-col", emptyMap(), emptyList(), "first", SourceRange(0, 5))
        val fallback = RichNode.Invalid("unsupported source", "unknown-component", SourceRange(5, 23))
        val second = RichNode.Component("ui-col", emptyMap(), emptyList(), "second", SourceRange(23, 29))

        val bands = richRowBands(listOf(first, fallback, second))

        assertEquals(listOf(listOf(first), listOf(fallback), listOf(second)), bands)
    }
}
