package cn.nabr.chatwithchat.data.richcontent

import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiEnvelope
import cn.nabr.chatwithchat.data.tool.JsonToolCallParser
import cn.nabr.chatwithchat.data.tool.JsonToolModelOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichContentPromptBuilderTest {
    @Test
    fun promptAdvertisesExactlyTheBundled55ComponentsWithSignatures() {
        val prompt = RichContentPromptBuilder().build()
        val names = listOf(
            "Col", "Row", "Box", "Grid", "Card", "Carousel", "Popover", "Divider", "Spacer",
            "Title", "Text", "Caption", "Label", "Badge", "Link", "List", "ListItem", "Markdown", "Quote", "Code", "Math",
            "Button", "IconButton", "Pressable", "Input", "TextArea", "Select", "SegmentedControl", "Slider", "Checkbox", "RadioGroup", "DatePicker", "Timer", "Form",
            "SearchImage", "Image", "Icon", "Table", "Chart", "Map", "MapMarker", "YouTube", "Flashcard", "Celebration",
            "Svg", "Circle", "Ellipse", "Rect", "Line", "Path", "Polyline", "Polygon", "G", "SvgText", "HtmlView"
        )
        assertEquals(55, names.size)
        names.forEach { name -> assertTrue("Missing $name signature", Regex("(?m)^" + name + "\\(").containsMatchIn(prompt)) }
        assertTrue(prompt.contains("Arguments are POSITIONAL"))
        assertTrue(prompt.contains("@ToAssistant"))
        assertTrue(prompt.contains("@Set"))
        assertFalse(prompt.contains("ENTIRE response must be valid openui"))
        assertFalse(prompt.contains("<ui-card"))
    }

    @Test
    fun promptRequiresSemanticNarrativeAndReviewBeforeSending() {
        val prompt = RichContentPromptBuilder().build()
        assertEquals(prompt, RichContentPromptBuilder().build())
        assertTrue(prompt.contains("OUTSIDE every openui fence"))
        assertTrue(prompt.contains("NEVER sends automatically"))
        assertTrue(prompt.contains("Local variables and input state stay on the device"))
        assertFalse(prompt.contains("their values go with any @ToAssistant"))
        assertFalse(prompt.contains("submit Button sends"))
        assertFalse(prompt.contains("No network except script CDNs"))
        assertFalse(prompt.contains("never say you cannot show photos"))
        assertFalse(prompt.contains("root = Card("))
        assertTrue(prompt.contains("Tailor my Lisbon trip with a "))
        assertTrue(prompt.length < 40_000)
    }

    @Test
    fun jsonAnswerExampleRetainsJsonEnvelopeAndDecodedOpenUiFence() {
        val prompt = RichContentPromptBuilder().buildJsonAnswerContentPrompt()
        val answer = JsonToolCallParser().parse(prompt.substringAfter("JSON envelope example: ")).getOrThrow() as JsonToolModelOutput.FinalAnswer
        val parsed = OpenUiEnvelope.parse(answer.content)
        assertEquals("Here is the summary.", parsed.markdown.trim())
        assertEquals("root = Col([Text(\"Summary\")])", parsed.source)
        assertTrue(parsed.isComplete)
    }
}
