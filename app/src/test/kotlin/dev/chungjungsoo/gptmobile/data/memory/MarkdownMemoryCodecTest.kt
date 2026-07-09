package dev.chungjungsoo.gptmobile.data.memory

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMemoryCodecTest {

    private val codec = MarkdownMemoryCodec()

    @Test
    fun `render and parse long term entries keeps metadata and text`() {
        val markdown = codec.renderLongTerm(
            listOf(
                MarkdownMemoryEntry(
                    id = "mem_20260709_153012",
                    text = "The user prefers natural Chinese explanations with concrete implementation steps.",
                    type = "communication_style",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.EXPLICIT_USER_STATEMENT,
                    createdAt = 1783572612L,
                    updatedAt = 1783572612L
                ),
                MarkdownMemoryEntry(
                    id = "mem_20260709_153244",
                    text = "ChatWithChat should preserve attachments, export, edit, retry, multi-provider, and memory flows.",
                    type = "project_context",
                    sensitivity = MemorySensitivity.PRIVATE,
                    source = MemorySource.ASSISTANT_INFERRED,
                    createdAt = 1783572764L,
                    updatedAt = 1783572764L
                )
            )
        )

        val parsed = codec.parse(markdown)

        assertTrue(markdown.startsWith("# ChatWithChat Memory\n\n"))
        assertTrue(markdown.contains("## Stable Preferences"))
        assertTrue(markdown.contains("## Projects"))
        assertEquals(emptyList<SkippedMarkdownMemoryEntry>(), parsed.skippedEntries)
        assertEquals(2, parsed.entries.size)
        val preferenceEntry = parsed.entries.single { it.id == "mem_20260709_153012" }
        val projectEntry = parsed.entries.single { it.id == "mem_20260709_153244" }
        assertEquals(MemorySensitivity.NORMAL, preferenceEntry.sensitivity)
        assertEquals(MemorySource.EXPLICIT_USER_STATEMENT, preferenceEntry.source)
        assertTrue(projectEntry.text.contains("multi-provider"))
        assertEquals(MemorySensitivity.PRIVATE, projectEntry.sensitivity)
    }

    @Test
    fun `render daily entries uses date title and conversation notes section`() {
        val markdown = codec.renderDaily(
            date = LocalDate.parse("2026-07-09"),
            entries = listOf(
                MarkdownMemoryEntry(
                    id = "day_20260709_210501",
                    text = "User asked to evaluate Markdown-first memory architecture.",
                    type = "project_context",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.EXPLICIT_USER_STATEMENT,
                    chatId = 123,
                    createdAt = 1783598701L,
                    updatedAt = 1783598701L
                )
            )
        )

        val parsed = codec.parse(markdown)

        assertTrue(markdown.startsWith("# 2026-07-09\n\n"))
        assertTrue(markdown.contains("## Conversation Notes"))
        assertTrue(markdown.contains("chat=123"))
        assertEquals("day_20260709_210501", parsed.entries.single().id)
        assertEquals(123, parsed.entries.single().chatId)
    }

    @Test
    fun `ordinary markdown does not prevent parseable entries from being recovered`() {
        val markdown = """
            # ChatWithChat Memory

            Intro text that should remain ordinary Markdown.

            ## Projects

            ### Human note
            This paragraph is not a memory entry.

            <!-- memory:id=mem_1 type=project_context sensitivity=normal source=assistant_inferred created=10 updated=20 -->
            - Preserve existing chat capabilities during memory refactors.
        """.trimIndent()

        val parsed = codec.parse(markdown)

        assertEquals(markdown, parsed.rawMarkdown)
        assertEquals(emptyList<SkippedMarkdownMemoryEntry>(), parsed.skippedEntries)
        assertEquals("mem_1", parsed.entries.single().id)
        assertEquals("Projects", parsed.entries.single().section)
        assertEquals("Preserve existing chat capabilities during memory refactors.", parsed.entries.single().text)
    }

    @Test
    fun `malformed metadata is reported as skipped entry`() {
        val markdown = """
            # ChatWithChat Memory

            ## Stable Preferences

            <!-- memory:id=mem_missing_source type=communication_style sensitivity=normal -->
            - User likes concise answers.
        """.trimIndent()

        val parsed = codec.parse(markdown)

        assertTrue(parsed.entries.isEmpty())
        assertEquals(1, parsed.skippedEntries.size)
        assertEquals(5, parsed.skippedEntries.single().lineNumber)
        assertTrue(parsed.skippedEntries.single().reason.contains("source"))
    }

    @Test
    fun `remove entries by id deletes only matching memory blocks`() {
        val markdown = """
            # ChatWithChat Memory

            Intro text should stay.

            ## Stable Preferences

            <!-- memory:id=mem_keep type=communication_style sensitivity=normal source=explicit_user_statement created=10 updated=20 -->
            - Keep this memory.

            <!-- memory:id=mem_delete type=communication_style sensitivity=normal source=explicit_user_statement created=11 updated=21 -->
            - Delete this memory.
              This continuation line should also disappear.

            ## Projects

            Handwritten project note should stay.

            <!-- memory:id=mem_delete type=project_context sensitivity=normal source=assistant_inferred created=12 updated=22 -->
            - Duplicate id should also be deleted.
        """.trimIndent()

        val result = codec.removeEntriesById(markdown, setOf("mem_delete"))
        val parsed = codec.parse(result.markdown)

        assertEquals(2, result.deletedCount)
        assertTrue(result.markdown.contains("Intro text should stay."))
        assertTrue(result.markdown.contains("Handwritten project note should stay."))
        assertTrue(result.markdown.contains("mem_keep"))
        assertTrue(result.markdown.contains("Keep this memory."))
        assertTrue(result.markdown.endsWith("\n"))
        assertEquals(listOf("mem_keep"), parsed.entries.map { it.id })
        assertTrue(!result.markdown.contains("Delete this memory."))
        assertTrue(!result.markdown.contains("Duplicate id should also be deleted."))
    }

    @Test
    fun `replace entries by id updates matching memory blocks and keeps handwritten markdown`() {
        val markdown = """
            # ChatWithChat Memory

            Intro text should stay.

            ## Projects

            Handwritten project note should stay.

            <!-- memory:id=mem_progress type=project_context sensitivity=private source=assistant_inferred created=10 updated=20 -->
            - The user is learning Kotlin coroutines and has finished Flow basics.

            <!-- memory:id=mem_keep type=project_context sensitivity=normal source=assistant_inferred created=11 updated=21 -->
            - Keep this project memory.
        """.trimIndent()

        val result = codec.replaceEntriesById(
            markdown,
            listOf(
                MarkdownMemoryEntry(
                    id = "mem_progress",
                    text = "The user is learning Kotlin coroutines and has finished Flow basics plus cancellation handling.",
                    type = "project_context",
                    sensitivity = MemorySensitivity.PRIVATE,
                    source = MemorySource.ASSISTANT_INFERRED,
                    createdAt = 10,
                    updatedAt = 30
                )
            )
        )
        val parsed = codec.parse(result.markdown)

        assertEquals(1, result.replacedCount)
        assertTrue(result.markdown.contains("Intro text should stay."))
        assertTrue(result.markdown.contains("Handwritten project note should stay."))
        assertTrue(result.markdown.contains("Flow basics plus cancellation handling."))
        assertTrue(!result.markdown.contains("has finished Flow basics."))
        assertEquals(listOf("mem_progress", "mem_keep"), parsed.entries.map { it.id })
        assertEquals(10, parsed.entries.single { it.id == "mem_progress" }.createdAt)
        assertEquals(30, parsed.entries.single { it.id == "mem_progress" }.updatedAt)
    }

    @Test
    fun `personal memory can be converted to markdown entry`() {
        val personalMemory = testMemory(
            id = 42,
            recallText = "The user prefers implementation before long explanations.",
            type = "communication_style",
            source = MemorySource.USER_CONFIRMED,
            sensitivity = MemorySensitivity.NORMAL,
            updatedAt = 200L
        )

        val entry = personalMemory.toMarkdownMemoryEntry()

        assertEquals("personal_42", entry.id)
        assertEquals(personalMemory.recallText, entry.text)
        assertEquals("communication_style", entry.type)
        assertEquals(MemorySource.USER_CONFIRMED, entry.source)
        assertEquals(200L, entry.createdAt)
        assertEquals(200L, entry.updatedAt)
    }
}
