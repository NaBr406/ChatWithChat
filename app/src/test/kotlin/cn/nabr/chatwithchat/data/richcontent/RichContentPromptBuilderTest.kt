package cn.nabr.chatwithchat.data.richcontent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichContentPromptBuilderTest {
    @Test
    fun build_includesEveryCatalogComponentAndItsConstraints() {
        val prompt = RichContentPromptBuilder().build()

        setOf("ui-card", "ui-row", "ui-col", "ui-metric", "ui-callout", "ui-steps", "ui-step").forEach { name ->
            assertTrue("missing component $name", prompt.contains("<$name"))
        }
        assertFalse(prompt.contains("<ui-button"))
        assertFalse(prompt.contains("<ui-slider"))
        assertTrue(prompt.contains("label=\"text\""))
        assertTrue(prompt.contains("[trend=\"{down|neutral|up}\"]"))
        assertTrue(prompt.contains("children: ui-col"))
        assertTrue(prompt.contains(" />"))
    }

    @Test
    fun build_isDeterministicAndStatesSafetyBoundaries() {
        val first = RichContentPromptBuilder().build()
        val second = RichContentPromptBuilder().build()

        assertEquals(first, second)
        assertTrue(first.contains("ordinary Markdown"))
        assertTrue(first.contains("fenced, inline, or indented code"))
        assertTrue(first.contains("Do not emit HTML, CSS, JavaScript"))
        assertTrue(first.contains("model-provided text"))
        assertTrue(first.contains("Interactive controls are not advertised"))
        assertFalse(first.contains("<script"))
    }

    @Test
    fun build_sortsCustomCatalogForStableOutput() {
        val reverse = RichComponentCatalog.components.entries
            .reversed()
            .associate { (name, spec) -> name to spec }

        val prompt = RichContentPromptBuilder(reverse).build()
        val cardIndex = prompt.indexOf("- <ui-card")
        val calloutIndex = prompt.indexOf("- <ui-callout")

        assertTrue(cardIndex >= 0)
        assertTrue(calloutIndex < cardIndex)
    }
}
