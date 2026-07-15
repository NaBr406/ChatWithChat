package cn.nabr.chatwithchat.data.memory

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
    fun `unknown evidence and existing duplicate create fail closed`() {
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
                val operations = controller.validate(
                    fixture.input,
                    listOf(operation(MemoryDailyDistillationAction.CREATE, text = existing.text))
                )
                controller.render(fixture.input, fixture.baseMarkdown, operations)
            }.isFailure
        )
    }

    @Test
    fun `create and replace cannot expand exact text multiplicity`() {
        val existing = memoryEntry("mem_existing", "Original stable preference.")
        val fixture = fixture(existing = listOf(existing))
        val operations = controller.validate(
            fixture.input,
            listOf(
                operation(MemoryDailyDistillationAction.CREATE, text = "Updated stable preference."),
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = existing.id,
                    text = "  UPDATED stable\npreference.  "
                )
            )
        )

        val failure = runCatching {
            controller.render(fixture.input, fixture.baseMarkdown, operations)
        }.exceptionOrNull()

        assertEquals("duplicate_exact_memory_text", failure?.message)
    }

    @Test
    fun `multiple replacements cannot expand exact text multiplicity`() {
        val first = memoryEntry("mem_first", "First stable preference.")
        val second = memoryEntry("mem_second", "Second stable preference.")
        val fixture = fixture(existing = listOf(first, second))
        val operations = controller.validate(
            fixture.input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = first.id,
                    text = "Shared stable preference."
                ),
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = second.id,
                    text = "\u00a0SHARED stable\u3000preference.  "
                )
            )
        )

        val failure = runCatching {
            controller.render(fixture.input, fixture.baseMarkdown, operations)
        }.exceptionOrNull()

        assertEquals("duplicate_exact_memory_text", failure?.message)
    }

    @Test
    fun `replace cannot match another canonical entry`() {
        val target = memoryEntry("mem_target", "Obsolete project context.")
        val canonical = memoryEntry("mem_canonical", "Current project context.")
        val fixture = fixture(existing = listOf(target, canonical))
        val operations = controller.validate(
            fixture.input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = target.id,
                    text = "  CURRENT project\ncontext.  "
                )
            )
        )

        val failure = runCatching {
            controller.render(fixture.input, fixture.baseMarkdown, operations)
        }.exceptionOrNull()

        assertEquals("duplicate_exact_memory_text", failure?.message)
    }

    @Test
    fun `historical exact duplicates remain while unique create succeeds`() {
        val first = memoryEntry("mem_duplicate_first", "Historical stable preference.")
        val second = memoryEntry("mem_duplicate_second", "HISTORICAL stable\u3000preference.")
        val fixture = fixture(existing = listOf(first, second))
        val operations = controller.validate(
            fixture.input,
            listOf(operation(MemoryDailyDistillationAction.CREATE, text = "New unique preference."))
        )

        val rendered = controller.render(fixture.input, fixture.baseMarkdown, operations)
        val entries = codec.parse(rendered.targets.single().targetContent).entries

        assertEquals(1, rendered.writeCount)
        assertEquals(
            2,
            entries.count { entry ->
                normalizeExactMemoryText(entry.text) == normalizeExactMemoryText(first.text)
            }
        )
        assertEquals(1, entries.count { entry -> entry.text == "New unique preference." })
    }

    @Test
    fun `replace cannot expand historical exact duplicate multiplicity`() {
        val first = memoryEntry("mem_duplicate_first", "Historical stable preference.")
        val second = memoryEntry("mem_duplicate_second", "HISTORICAL stable\u3000preference.")
        val target = memoryEntry("mem_target", "Unique project context.")
        val fixture = fixture(existing = listOf(first, second, target))
        val operations = controller.validate(
            fixture.input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = target.id,
                    text = "  historical stable\npreference.  "
                )
            )
        )

        val failure = runCatching {
            controller.render(fixture.input, fixture.baseMarkdown, operations)
        }.exceptionOrNull()

        assertEquals("duplicate_exact_memory_text", failure?.message)
    }

    @Test
    fun `replacements may swap exact texts without expanding multiplicity`() {
        val first = memoryEntry("mem_first", "First stable preference.")
        val second = memoryEntry("mem_second", "Second stable preference.")
        val fixture = fixture(existing = listOf(first, second))
        val operations = controller.validate(
            fixture.input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = first.id,
                    text = second.text
                ),
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = second.id,
                    text = first.text
                )
            )
        )

        val rendered = controller.render(fixture.input, fixture.baseMarkdown, operations)
        val entriesById = codec.parse(rendered.targets.single().targetContent).entries.associateBy { entry -> entry.id }

        assertEquals(2, rendered.writeCount)
        assertEquals(second.text, entriesById.getValue(first.id).text)
        assertEquals(first.text, entriesById.getValue(second.id).text)
    }

    @Test
    fun `create before replacement may move an exact text without expanding multiplicity`() {
        val existing = memoryEntry("mem_existing", "Stable preference to move.")
        val fixture = fixture(existing = listOf(existing))
        val operations = controller.validate(
            fixture.input,
            listOf(
                operation(MemoryDailyDistillationAction.CREATE, text = existing.text),
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = existing.id,
                    text = "Replacement preference."
                )
            )
        )

        val rendered = controller.render(fixture.input, fixture.baseMarkdown, operations)
        val entries = codec.parse(rendered.targets.single().targetContent).entries

        assertEquals(2, rendered.writeCount)
        assertEquals(1, entries.count { entry -> entry.text == existing.text })
        assertEquals("Replacement preference.", entries.single { entry -> entry.id == existing.id }.text)
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
