package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDailyDistillationOperationControllerTest {
    private val codec = MarkdownMemoryCodec()
    private val controller = MemoryDailyDistillationOperationController(
        markdownMemoryCodec = codec,
        targetIndexFingerprint = "fingerprint"
    )

    @Test
    fun `create derives conservative metadata and renders one long term target`() {
        val fixture = fixture()
        val operations = controller.validate(
            fixture.input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.CREATE,
                    text = "  Prefers   concise\nanswers.  ",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.ASSISTANT_INFERRED
                )
            )
        )

        val rendered = controller.render(fixture.input, fixture.baseMarkdown, operations)
        val created = codec.parse(rendered.targets.single().targetContent).entries.single()

        assertEquals("Prefers concise answers.", created.text)
        assertEquals(MemorySensitivity.SENSITIVE, created.sensitivity)
        assertEquals(MemorySource.EXPLICIT_USER_STATEMENT, created.source)
        assertEquals(1, rendered.writeCount)
        assertEquals("fingerprint", rendered.targets.single().targetIndexFingerprint)
    }

    @Test
    fun `replace keeps stable identity and preserves unrelated Markdown`() {
        val existing = memoryEntry("mem_existing", "Old stable preference.", createdAt = 4, updatedAt = 5)
        val fixture = fixture(existing = listOf(existing), trailingMarkdown = "\nManual footer stays here.\n")
        val operations = controller.validate(
            fixture.input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = existing.id,
                    text = "Updated stable preference."
                )
            )
        )

        val rendered = controller.render(fixture.input, fixture.baseMarkdown, operations, renderedAt = 20)
        val target = rendered.targets.single().targetContent
        val replaced = codec.parse(target).entries.single()

        assertEquals(existing.id, replaced.id)
        assertEquals(existing.createdAt, replaced.createdAt)
        assertEquals(20, replaced.updatedAt)
        assertTrue(target.contains("Manual footer stays here."))
    }

    @Test
    fun `unknown evidence and duplicate create fail closed`() {
        val existing = memoryEntry("mem_existing", "Existing stable preference.")
        val fixture = fixture(existing = listOf(existing))

        assertTrue(
            runCatching {
                controller.validate(
                    fixture.input,
                    listOf(operation(MemoryDailyDistillationAction.CREATE, text = "New fact.", evidenceKeys = listOf("missing")))
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                controller.validate(
                    fixture.input,
                    listOf(operation(MemoryDailyDistillationAction.CREATE, text = existing.text))
                )
            }.isFailure
        )
    }

    @Test
    fun `empty or ignore proposal creates a durable no file target result`() {
        val fixture = fixture()
        val ignored = controller.validate(
            fixture.input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.IGNORE,
                    text = "",
                    evidenceKeys = emptyList()
                )
            )
        )

        val rendered = controller.render(fixture.input, fixture.baseMarkdown, ignored)

        assertTrue(rendered.targets.isEmpty())
        assertEquals(0, rendered.writeCount)
        assertEquals(fixture.input.targetBaseHash, rendered.targetSourceHash)
    }

    @Test
    fun `parser skips consumed multiline continuation lines`() {
        val markdown = codec.renderLongTerm(
            listOf(
                memoryEntry("first", "First line\n## not a section\n<!-- memory:id=fake -->", section = "Stable Preferences"),
                memoryEntry("second", "Second entry.", section = "Stable Preferences")
            )
        )

        val parsed = codec.parse(markdown)

        assertEquals(listOf("first", "second"), parsed.entries.map { entry -> entry.id })
        assertEquals(listOf("Stable Preferences", "Stable Preferences"), parsed.entries.map { entry -> entry.section })
        assertTrue(parsed.skippedEntries.isEmpty())
    }

    private fun fixture(
        existing: List<MarkdownMemoryEntry> = emptyList(),
        trailingMarkdown: String = ""
    ): Fixture {
        val baseMarkdown = codec.renderLongTerm(existing).trimEnd() + trailingMarkdown.trimEnd() + "\n"
        val input = MemoryDailyDistillationFrozenInput(
            batchId = "daily-batch",
            batchKey = "batch-0000",
            dailySourcePath = "memory/2026-07-11.md",
            dailySourceHash = "d".repeat(64),
            dailyDate = "2026-07-11",
            dailyEvidence = listOf(
                MemoryDailyDistillationEvidence(
                    evidenceKey = "evidence-1",
                    entryId = "daily-1",
                    text = "The user explicitly asked for concise answers.",
                    type = "communication_style",
                    sensitivity = MemorySensitivity.SENSITIVE,
                    source = MemorySource.EXPLICIT_USER_STATEMENT,
                    createdAt = 1,
                    updatedAt = 2
                )
            ),
            existingMemories = existing.map { entry ->
                MemoryBatchExistingMemory(
                    id = entry.id,
                    sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                    text = entry.text,
                    type = entry.type,
                    sensitivity = entry.sensitivity,
                    source = entry.source,
                    updatedAt = entry.updatedAt
                )
            },
            targetBaseHash = baseMarkdown.toByteArray(Charsets.UTF_8).sha256Hex(),
            createdAt = 10
        )
        return Fixture(baseMarkdown, input)
    }

    private fun operation(
        action: String,
        targetMemoryId: String? = null,
        text: String,
        sensitivity: String = MemorySensitivity.NORMAL,
        source: String = MemorySource.ASSISTANT_INFERRED,
        evidenceKeys: List<String> = listOf("evidence-1")
    ) = MemoryDailyDistillationOperation(
        action = action,
        targetMemoryId = targetMemoryId,
        text = text,
        type = "communication_style",
        sensitivity = sensitivity,
        source = source,
        evidenceKeys = evidenceKeys,
        reason = "test"
    )

    private fun memoryEntry(
        id: String,
        text: String,
        createdAt: Long = 1,
        updatedAt: Long = 2,
        section: String? = null
    ) = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = "communication_style",
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = createdAt,
        updatedAt = updatedAt,
        section = section
    )

    private data class Fixture(
        val baseMarkdown: String,
        val input: MemoryDailyDistillationFrozenInput
    )
}
