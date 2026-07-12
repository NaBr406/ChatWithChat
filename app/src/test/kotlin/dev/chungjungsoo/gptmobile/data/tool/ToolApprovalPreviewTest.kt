package dev.chungjungsoo.gptmobile.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalPreviewTest {
    @Test
    fun `preview carries presentation identity and bounded human readable summary`() {
        val preview = ToolApprovalPreview.create(
            call = ToolCall("call_1", "create_reminder", """{"at":"tomorrow"}"""),
            presentationKey = "tool.create_reminder",
            fallbackDisplayName = "Create reminder",
            humanReadableArgumentSummary = "  Create reminder    for tomorrow at 9 AM. ${"details ".repeat(80)}"
        ).getOrThrow()

        assertEquals("create_reminder", preview.toolName)
        assertEquals("tool.create_reminder", preview.presentationKey)
        assertEquals("Create reminder", preview.fallbackDisplayName)
        assertTrue(preview.argumentSummary.length <= ToolApprovalPreview.MAX_ARGUMENT_SUMMARY_CHARS)
        assertTrue(preview.argumentSummary.endsWith("..."))
        assertFalse(preview.argumentSummary.contains("  "))
    }

    @Test
    fun `preview derives a stable fallback name`() {
        val preview = ToolApprovalPreview.create(
            call = ToolCall("call_1", "local_write_tool", "{}"),
            presentationKey = null,
            fallbackDisplayName = "",
            humanReadableArgumentSummary = "Update one local item"
        ).getOrThrow()

        assertEquals("Local Write Tool", preview.fallbackDisplayName)
        assertNull(preview.presentationKey)
    }

    @Test
    fun `raw JSON is rejected as an approval summary`() {
        val result = ToolApprovalPreview.create(
            call = ToolCall("call_1", "fake_write_tool", """{"recipient":"person@example.com"}"""),
            presentationKey = "tool.fake_write",
            fallbackDisplayName = "Fake write",
            humanReadableArgumentSummary = """{"recipient":"person@example.com","secret":"token"}"""
        )

        assertTrue(result.isFailure)
    }
}
