package cn.nabr.chatwithchat.data.richcontent.openui

import cn.nabr.chatwithchat.data.context.semanticAssistantContent
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUiEnvelopeTest {
    @Test
    fun incompleteStreamPublishesNarrativeAndProgramPrefixWithoutExposingSource() {
        val content = "First answer.\n\n```openui\nroot = Col([Title(\"Hel"
        val parsed = OpenUiEnvelope.parse(content, true)
        assertEquals("First answer.\n\n", parsed.markdown)
        assertEquals("root = Col([Title(\"Hel", parsed.source)
        assertFalse(parsed.isComplete)
        assertEquals("First answer.", OpenUiEnvelope.toMarkdown(content))
    }

    @Test
    fun fullReplacementAndRollbackNeverRetainOldProgram() {
        val original = "Before\n```openui\nroot = Col([])\n```\nAfter"
        assertTrue(OpenUiEnvelope.containsOpenUi(original))
        assertEquals(listOf(OpenUiPart.Markdown("Before\n"), OpenUiPart.Program("root = Col([])", true), OpenUiPart.Markdown("After")), OpenUiEnvelope.parse(original).parts)
        assertFalse(OpenUiEnvelope.containsOpenUi("Replacement"))
        assertEquals("Replacement", OpenUiEnvelope.toMarkdown("Replacement"))
    }

    @Test
    fun multipleProgramsPreserveTheirPositionAndSemanticNarrative() {
        val content = "One\n```openui\nroot = Col([])\n```\nTwo\n~~~openui\nroot = Col([])\n~~~\nThree"
        val parsed = OpenUiEnvelope.parse(content)
        assertEquals(5, parsed.parts.size)
        assertEquals("One\nTwo\nThree", parsed.markdown)
        assertTrue(parsed.isComplete)
    }

    @Test
    fun ordinaryCodeAndOpenUiExamplesInsideLargerCodeFencesRemainText() {
        val code = "````markdown\n```openui\nroot = Col([])\n```\n````\n\n```kotlin\nval a = 1\n```"
        assertFalse(OpenUiEnvelope.containsOpenUi(code))
        assertEquals(code, OpenUiEnvelope.toMarkdown(code))
        assertFalse(OpenUiEnvelope.containsOpenUi("root = Col([])"))
        assertFalse(OpenUiEnvelope.containsOpenUi("<ui-card>Legacy</ui-card>"))
    }

    @Test
    fun semanticAssistantBoundaryStripsProgramsAndProjectsHistoricalXml() {
        val message = MessageV2(content = "Facts.\n```openui\nroot = Col([HtmlView(\"secret-source\")])\n```", platformType = "provider")
        assertEquals("Facts.", message.semanticAssistantContent())
        val legacy = message.copy(content = "<ui-card title=\"Summary\">\nFacts.\n</ui-card>")
        assertEquals("**Summary**\n\nFacts.", legacy.semanticAssistantContent())
        val missingNarrative = message.copy(content = "```openui\nroot = Col([])\n```").semanticAssistantContent()
        assertTrue(missingNarrative.isNotBlank())
        assertFalse(missingNarrative.contains("root"))
    }

    @Test
    fun partiallyArrivingFenceMarkerDoesNotFlashAsNarrative() {
        assertEquals("Facts.\n", OpenUiEnvelope.parse("Facts.\n```ope", true).markdown)
    }
}
