package cn.nabr.chatwithchat.data.richcontent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichContentParserTest {
    private val parser = RichContentParser()

    @Test
    fun parse_nestedComponents_preservesOrderAndDecodesAttributes() {
        val source = "Before\n<ui-card title=\"A &amp; B\">\nfirst\n<ui-metric label=\"Count\" value=\"3\" />\nlast\n</ui-card>\nAfter"
        val document = parser.parse(source)

        assertEquals(3, document.nodes.size)
        val card = document.nodes[1] as RichNode.Component
        assertEquals("A & B", card.attributes["title"])
        assertEquals(listOf("first", "ui-metric", "last"), card.children.map {
            if (it is RichNode.Component) it.name else (it as RichNode.Markdown).text.trim()
        })
        val projected = RichContentProjection.toMarkdown(document)
        assertTrue(projected.startsWith("Before"))
        assertTrue(projected.endsWith("After"))
        assertTrue(projected.contains("**A & B**"))
        assertTrue(projected.contains("**Count:** 3"))
    }

    @Test
    fun parse_singleLineNestedComponents_ignoresTagsInsideMarkdownCode() {
        val source = "Before <ui-card title=\"Summary\"><ui-row><ui-col>Left</ui-col><ui-col>Right</ui-col></ui-row></ui-card> after ` <ui-card title=\"inline\"> `\n```xml\n<ui-card title=\"fenced\"><ui-row></ui-row></ui-card>\n```"

        val document = parser.parse(source)

        val card = document.nodes[1] as RichNode.Component
        assertEquals("ui-row", (card.children.single() as RichNode.Component).name)
        assertEquals(2, ((card.children.single() as RichNode.Component).children).size)
        assertTrue(document.nodes.drop(2).all { it is RichNode.Markdown })
        assertTrue(RichContentProjection.toMarkdown(document).contains("<ui-card title=\"inline\">"))
        assertTrue(RichContentProjection.toMarkdown(document).contains("<ui-card title=\"fenced\"><ui-row></ui-row></ui-card>"))
    }

    @Test
    fun parse_singleLineNestedComponents_withTrailingMetadata_remainsRich() {
        val source = "<ui-card title=\"Metrics\">**bold** and `code`<ui-row><ui-metric label=\"Latency\" value=\"120 ms\" trend=\"down\" /><ui-metric label=\"Stable\" value=\"99.9%\" /></ui-row><ui-callout tone=\"info\">Streaming content remains stable.</ui-callout><ui-steps><ui-step title=\"Parse\">Build a stable document.</ui-step><ui-step title=\"Render\">Project into Compose.</ui-step></ui-steps></ui-card> [debug-turn-1:debug-memory-seed]"

        val document = parser.parse(source)

        assertTrue(document.nodes.first() is RichNode.Component)
        assertTrue(document.diagnostics.none { it.code == "invalid-child" })
    }

    @Test
    fun parse_rowAcceptsMetricsAndColumns_withOneToThreeItems() {
        val source = "<ui-row><ui-metric label=\"Latency\" value=\"120 ms\" /><ui-col>Details</ui-col><ui-metric label=\"Uptime\" value=\"99.9%\" /></ui-row>"

        val document = parser.parse(source)
        val row = document.nodes.single() as RichNode.Component

        assertEquals(listOf("ui-metric", "ui-col", "ui-metric"), row.children.map { (it as RichNode.Component).name })
        assertEquals(3, row.children.size)
        assertTrue(document.diagnostics.none { it.code == "invalid-child" })
        assertTrue(RichContentProjection.toMarkdown(document).contains("**Latency:** 120 ms"))
    }

    @Test
    fun parse_rowRejectsMoreThanThreeItems() {
        val source = "<ui-row><ui-metric label=\"A\" value=\"1\" /><ui-metric label=\"B\" value=\"2\" /><ui-col>Three</ui-col><ui-metric label=\"D\" value=\"4\" /></ui-row>"

        val document = parser.parse(source)

        assertTrue(document.nodes.single() is RichNode.Invalid)
        assertTrue(document.diagnostics.any { it.code == "invalid-child" })
        assertEquals(source, RichContentProjection.toMarkdown(document))
    }

    @Test
    fun parse_codeAndNonProtocolMarkup_areKeptAsMarkdown() {
        val source = """Inline `<ui-card title="x">`.

            ```xml
            <ui-card title="fenced">
            ```

                <ui-button key="indented" label="x" prompt="y" />

            <div>html</div> and <T>
        """.trimIndent()

        val document = parser.parse(source)
        assertTrue(document.nodes.all { it is RichNode.Markdown })
        assertEquals(source, RichContentProjection.toMarkdown(document))
    }

    @Test
    fun parse_unknownAndInvalidComponents_degradeToOriginalText() {
        val source = """<ui-unknown x="1" />
            <ui-slider key="s" label="S" min="0" max="1" step="0" default="0" />
            tail""".trimIndent()

        val document = parser.parse(source)
        assertTrue(document.nodes.first() is RichNode.Invalid)
        assertTrue(document.nodes.any { it is RichNode.Invalid })
        assertEquals(source, RichContentProjection.toMarkdown(document))
    }

    @Test
    fun parse_unclosedTag_isIncompleteWhileStreamingAndLiteralWhenFinal() {
        val source = "Hello\n<ui-card title=\"x\">\nbody"

        val streaming = parser.parse(source, isStreaming = true)
        val final = parser.parse(source, isStreaming = false)

        assertTrue(streaming.nodes.last() is RichNode.Incomplete)
        assertTrue(final.nodes.last() is RichNode.Invalid)
        assertEquals(source, RichContentProjection.toMarkdown(streaming))
        assertEquals(source, RichContentProjection.toMarkdown(final))
    }

    @Test
    fun parse_unclosedAttributeQuote_isIncompleteDuringStream() {
        val source = "<ui-card title=\"still writing"

        assertTrue(parser.parse(source, isStreaming = true).nodes.single() is RichNode.Incomplete)
        assertTrue(parser.parse(source, isStreaming = false).nodes.single() is RichNode.Invalid)
    }

    @Test
    fun parser_usesInjectedCatalogForValidation() {
        val custom = mapOf("ui-card" to ComponentSpec("ui-card", mapOf("name" to AttributeSpec(required = true))))
        val document = RichContentParser(custom).parse("<ui-card name=\"x\">body</ui-card>")

        assertTrue(document.nodes.single() is RichNode.Component)
    }

    @Test
    fun parser_everyCharacterChunkBoundary_hasSameFinalProjection() {
        val source = "Start\n<ui-card title=\"A\">\n<ui-callout tone=\"info\">\nhello\n</ui-callout>\n</ui-card>\nEnd"
        val accumulator = RichContentAccumulator(parser)
        var last = RichContentUpdate(parser.parse(""), true, null)
        source.forEachIndexed { index, char ->
            last = accumulator.update(source.substring(0, index + 1), true, 1)
        }
        last = accumulator.update(source, false, 1)

        assertEquals(RichContentProjection.toMarkdown(parser.parse(source)), RichContentProjection.toMarkdown(last.document))
        assertFalse(last.document.isStreaming)
    }

    @Test
    fun accumulator_detectsRollbackPrefixReplacementAndGenerationReset() {
        val accumulator = RichContentAccumulator(parser)
        assertEquals(ResetReason.INITIAL, accumulator.update("abcdef", true, 1).resetReason)
        assertEquals(ResetReason.TEXT_SHORTENED, accumulator.update("abc", true, 1).resetReason)
        assertEquals(ResetReason.PREFIX_REPLACED, accumulator.update("xyz", true, 1).resetReason)
        assertEquals(ResetReason.GENERATION_CHANGED, accumulator.update("xyzz", true, 2).resetReason)
        assertEquals(null, accumulator.update("xyzz!", true, 2).resetReason)
    }

    @Test
    fun projection_includesAllTabsAndCollapsedContent() {
        val source = """|<ui-tabs key="t">
            |<ui-tab value="a" label="First">
            |one
            |</ui-tab>
            |<ui-tab value="b" label="Second">
            |two
            |</ui-tab>
            |</ui-tabs>
            |<ui-collapse key="c" title="Details" default="closed">
            |hidden
            |</ui-collapse>""".trimMargin()
        val projected = RichContentProjection.toMarkdown(parser.parse(source))

        assertTrue(projected.contains("### First"))
        assertTrue(projected.contains("### Second"))
        assertTrue(projected.contains("hidden"))
    }

    @Test
    fun parse_duplicateInteractiveKey_degradesDuplicateToOriginalText() {
        val source = """|<ui-segmented key="plan" label="Plan">
            |<ui-option value="a" label="A" />
            |</ui-segmented>
            |<ui-collapse key="plan" title="Details">body</ui-collapse>""".trimMargin()

        val document = parser.parse(source)

        assertTrue(document.nodes.first() is RichNode.Component)
        assertTrue(document.nodes.last() is RichNode.Invalid)
        assertTrue(document.diagnostics.any { it.code == "duplicate-key" })
        assertTrue(RichContentProjection.toMarkdown(document).contains("<ui-collapse key=\"plan\""))
    }
}
