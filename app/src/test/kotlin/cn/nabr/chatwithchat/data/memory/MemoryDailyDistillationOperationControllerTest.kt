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
    fun `same fact replace merges observation without index sync`() {
        val existing = memoryEntry(
            id = "mem_existing",
            text = "Current stable preference.",
            createdAt = 4,
            updatedAt = 5,
            section = "Stable Preferences"
        ).copy(
            canonicalKey = "communication.response_style",
            scope = MemoryScope.WORK,
            lastObservedAt = 6,
            evidenceRefs = listOf("turn:existing"),
            extraMetadata = mapOf("future_schema" to "v2")
        )
        val fixture = fixture(existing = listOf(existing))
        val evidence = fixture.input.dailyEvidence.single().copy(
            evidenceKey = "evidence-2",
            createdAt = 11,
            updatedAt = 12
        )
        val input = fixture.input.copy(dailyEvidence = listOf(evidence))
        val operations = controller.validate(
            input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = existing.id,
                    text = "  CURRENT stable\npreference.  ",
                    evidenceKeys = listOf(evidence.evidenceKey),
                    canonicalKey = checkNotNull(existing.canonicalKey),
                    scope = existing.scope,
                    evidenceAt = 12
                )
            )
        )

        val rendered = controller.render(input, fixture.baseMarkdown, operations, renderedAt = 20)
        val target = rendered.targets.single()
        val observed = codec.parse(target.targetContent).entries.single()

        assertEquals(existing.id, observed.id)
        assertEquals(existing.text, observed.text)
        assertEquals(existing.createdAt, observed.createdAt)
        assertEquals(existing.updatedAt, observed.updatedAt)
        assertEquals(12, observed.lastObservedAt)
        assertEquals(listOf("evidence-2", "turn:existing"), observed.evidenceRefs)
        assertEquals("v2", observed.extraMetadata["future_schema"])
        assertEquals(null, target.targetIndexFingerprint)
        assertEquals(1, rendered.writeCount)
    }

    @Test
    fun `malformed base fails closed before daily distillation writes`() {
        val fixture = fixture(
            trailingMarkdown =
            "\n\n<!-- memory:id=malformed id=duplicate type=communication_style " +
                "sensitivity=normal source=explicit_user_statement -->\n" +
                "- This malformed entry must not be rewritten.\n"
        )
        val operations = controller.validate(
            fixture.input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.CREATE,
                    text = "A valid write must not bypass malformed canonical metadata."
                )
            )
        )

        val failure = runCatching {
            controller.render(fixture.input, fixture.baseMarkdown, operations)
        }.exceptionOrNull()

        assertEquals("unsafe_memory_metadata", failure?.message)
    }

    @Test
    fun `unknown evidence fails closed and same fact create adopts legacy entry`() {
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
        val operations = controller.validate(
            fixture.input,
            listOf(operation(MemoryDailyDistillationAction.CREATE, text = existing.text))
        )
        val rendered = controller.render(fixture.input, fixture.baseMarkdown, operations)
        val entries = codec.parse(rendered.targets.single().targetContent).entries

        assertEquals(1, entries.size)
        assertEquals(existing.id, entries.single().id)
        assertEquals("communication.response_style", entries.single().canonicalKey)
        assertEquals(MemoryScope.GENERAL, entries.single().scope)
        assertEquals(1, rendered.writeCount)
    }

    @Test
    fun `canonical contract derives trust and latest evidence time locally`() {
        val fixture = fixture()
        val laterEvidence = fixture.input.dailyEvidence.single().copy(
            evidenceKey = "evidence-2",
            source = MemorySource.USER_CONFIRMED,
            createdAt = 6L,
            updatedAt = 7L
        )
        val input = fixture.input.copy(dailyEvidence = fixture.input.dailyEvidence + laterEvidence)

        val validated = controller.validate(
            input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.CREATE,
                    text = "Prefers locally validated answers.",
                    evidenceKeys = listOf("evidence-1", "evidence-2"),
                    evidenceAt = 7L
                )
            )
        ).single()

        assertEquals(7L, validated.evidenceAt)
        assertEquals(MemorySource.USER_CONFIRMED, validated.source)
        assertEquals(listOf("evidence-1", "evidence-2"), validated.evidenceKeys)
    }

    @Test
    fun `canonical contract rejects invalid identity evidence time and active state`() {
        val fixture = fixture()
        val valid = operation(
            action = MemoryDailyDistillationAction.CREATE,
            text = "Prefers locally validated answers."
        )
        val invalidOperations = listOf(
            valid.copy(canonicalKey = "identity"),
            valid.copy(scope = "project:Not-Safe"),
            valid.copy(evidenceAt = 3L),
            valid.copy(recallState = MemoryRecallState.MAINTENANCE_ONLY)
        )

        invalidOperations.forEach { operation ->
            assertTrue(runCatching { controller.validate(fixture.input, listOf(operation)) }.isFailure)
        }
    }

    @Test
    fun `identical same fact replay is byte stable and creates no target`() {
        val existing = memoryEntry("mem_existing", "Current stable preference.").copy(
            canonicalKey = "communication.response_style",
            scope = MemoryScope.GENERAL,
            lastObservedAt = 2,
            evidenceRefs = listOf("turn:existing")
        )
        val fixture = fixture(existing = listOf(existing))
        val evidence = fixture.input.dailyEvidence.single().copy(
            evidenceKey = "evidence-2",
            createdAt = 11,
            updatedAt = 12
        )
        val input = fixture.input.copy(dailyEvidence = listOf(evidence))
        val proposed = operation(
            action = MemoryDailyDistillationAction.REPLACE,
            targetMemoryId = existing.id,
            text = existing.text,
            evidenceKeys = listOf(evidence.evidenceKey),
            canonicalKey = checkNotNull(existing.canonicalKey),
            scope = existing.scope,
            evidenceAt = 12
        )
        val first = controller.render(
            input = input,
            baseMarkdown = fixture.baseMarkdown,
            validatedOperations = controller.validate(input, listOf(proposed)),
            renderedAt = 20
        )
        val observedMarkdown = first.targets.single().targetContent
        val replayInput = input.copy(
            targetBaseHash = observedMarkdown.toByteArray(Charsets.UTF_8).sha256Hex()
        )

        val replay = controller.render(
            input = replayInput,
            baseMarkdown = observedMarkdown,
            validatedOperations = controller.validate(replayInput, listOf(proposed)),
            renderedAt = 21
        )

        assertTrue(replay.targets.isEmpty())
        assertEquals(1, replay.writeCount)
        assertEquals(replayInput.targetBaseHash, replay.targetSourceHash)
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
    fun `newer assistant inferred evidence cannot replace user confirmed fact`() {
        val existing = memoryEntry("mem_confirmed", "The user prefers concise answers.").copy(
            source = MemorySource.USER_CONFIRMED,
            canonicalKey = "communication.response_style",
            scope = MemoryScope.GENERAL,
            lastObservedAt = 10,
            evidenceRefs = listOf("turn:confirmed")
        )
        val fixture = fixture(existing = listOf(existing))
        val weakEvidence = fixture.input.dailyEvidence.single().copy(
            source = MemorySource.ASSISTANT_INFERRED,
            sensitivity = MemorySensitivity.NORMAL,
            createdAt = 40,
            updatedAt = 50
        )
        val input = fixture.input.copy(dailyEvidence = listOf(weakEvidence))
        val validated = controller.validate(
            input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = existing.id,
                    text = "The user prefers detailed answers.",
                    source = MemorySource.USER_CONFIRMED,
                    canonicalKey = checkNotNull(existing.canonicalKey),
                    scope = existing.scope,
                    evidenceAt = 50
                )
            )
        )

        val rendered = controller.render(input, fixture.baseMarkdown, validated, renderedAt = 60)

        assertEquals(MemorySource.ASSISTANT_INFERRED, validated.single().source)
        assertTrue(rendered.targets.isEmpty())
        assertEquals(0, rendered.writeCount)
        assertEquals(fixture.input.targetBaseHash, rendered.targetSourceHash)
    }

    @Test
    fun `stronger replacement keeps active id and creates deterministic history`() {
        val existing = memoryEntry(
            id = "mem_stable_active",
            text = "The user prefers detailed answers.",
            createdAt = 11,
            updatedAt = 20
        ).copy(
            source = MemorySource.ASSISTANT_INFERRED,
            canonicalKey = "communication.response_style",
            scope = MemoryScope.GENERAL,
            lastObservedAt = 20,
            evidenceRefs = listOf("turn:inferred")
        )
        val fixture = fixture(existing = listOf(existing))
        val strongEvidence = fixture.input.dailyEvidence.single().copy(
            evidenceKey = "evidence-confirmed",
            source = MemorySource.USER_CONFIRMED,
            sensitivity = MemorySensitivity.NORMAL,
            createdAt = 40,
            updatedAt = 50
        )
        val input = fixture.input.copy(dailyEvidence = listOf(strongEvidence))
        val operation = operation(
            action = MemoryDailyDistillationAction.REPLACE,
            targetMemoryId = existing.id,
            text = "The user prefers concise answers.",
            evidenceKeys = listOf(strongEvidence.evidenceKey),
            canonicalKey = checkNotNull(existing.canonicalKey),
            scope = existing.scope,
            evidenceAt = 50
        )

        val rendered = controller.render(
            input = input,
            baseMarkdown = fixture.baseMarkdown,
            validatedOperations = controller.validate(input, listOf(operation)),
            renderedAt = 80
        )
        val entries = codec.parse(rendered.targets.single().targetContent).entries
        val active = entries.single { entry -> entry.validity == MemoryValidity.CURRENT }
        val history = entries.single { entry -> entry.validity == MemoryValidity.OBSOLETE }

        assertEquals(existing.id, active.id)
        assertEquals("The user prefers concise answers.", active.text)
        assertEquals(existing.createdAt, active.createdAt)
        assertEquals(80, active.updatedAt)
        assertEquals(50, active.lastObservedAt)
        assertEquals(MemorySource.USER_CONFIRMED, active.source)
        assertTrue(history.id != active.id)
        assertEquals(existing.text, history.text)
        assertEquals(MemoryValidity.OBSOLETE, history.validity)
        assertEquals(MemoryRecallState.MAINTENANCE_ONLY, history.recallState)
        assertEquals(active.id, history.supersededBy)
        assertEquals("fingerprint", rendered.targets.single().targetIndexFingerprint)
        assertEquals(1, rendered.writeCount)
    }

    @Test
    fun `stronger replacement replay does not create a second history entry`() {
        val existing = memoryEntry("mem_stable_active", "The user prefers detailed answers.").copy(
            source = MemorySource.ASSISTANT_INFERRED,
            canonicalKey = "communication.response_style",
            scope = MemoryScope.GENERAL,
            lastObservedAt = 20,
            evidenceRefs = listOf("turn:inferred")
        )
        val fixture = fixture(existing = listOf(existing))
        val strongEvidence = fixture.input.dailyEvidence.single().copy(
            evidenceKey = "evidence-confirmed",
            source = MemorySource.USER_CONFIRMED,
            sensitivity = MemorySensitivity.NORMAL,
            createdAt = 40,
            updatedAt = 50
        )
        val input = fixture.input.copy(dailyEvidence = listOf(strongEvidence))
        val proposed = operation(
            action = MemoryDailyDistillationAction.REPLACE,
            targetMemoryId = existing.id,
            text = "The user prefers concise answers.",
            evidenceKeys = listOf(strongEvidence.evidenceKey),
            canonicalKey = checkNotNull(existing.canonicalKey),
            scope = existing.scope,
            evidenceAt = 50
        )
        val first = controller.render(
            input = input,
            baseMarkdown = fixture.baseMarkdown,
            validatedOperations = controller.validate(input, listOf(proposed)),
            renderedAt = 80
        )
        val firstMarkdown = first.targets.single().targetContent
        val firstEntries = codec.parse(firstMarkdown).entries
        val replayInput = input.copy(
            existingMemories = firstEntries.map { entry -> entry.toBatchExistingMemory() },
            targetBaseHash = firstMarkdown.toByteArray(Charsets.UTF_8).sha256Hex()
        )

        val replay = controller.render(
            input = replayInput,
            baseMarkdown = firstMarkdown,
            validatedOperations = controller.validate(replayInput, listOf(proposed)),
            renderedAt = 90
        )

        assertEquals(1, firstEntries.count { entry -> entry.validity == MemoryValidity.OBSOLETE })
        assertTrue(replay.targets.isEmpty())
        assertEquals(1, replay.writeCount)
        assertEquals(replayInput.targetBaseHash, replay.targetSourceHash)
    }

    @Test
    fun `material replacement preserves manual footer`() {
        val existing = memoryEntry("mem_footer_target", "The user prefers detailed answers.").copy(
            source = MemorySource.ASSISTANT_INFERRED,
            canonicalKey = "communication.response_style",
            scope = MemoryScope.GENERAL,
            lastObservedAt = 2
        )
        val footer = "Manual footer stays here.\n\n<!-- manual:keep -->"
        val fixture = fixture(existing = listOf(existing), trailingMarkdown = "\n$footer\n")
        val strongEvidence = fixture.input.dailyEvidence.single().copy(
            source = MemorySource.USER_CONFIRMED,
            createdAt = 11,
            updatedAt = 12
        )
        val input = fixture.input.copy(dailyEvidence = listOf(strongEvidence))
        val operations = controller.validate(
            input,
            listOf(
                operation(
                    action = MemoryDailyDistillationAction.REPLACE,
                    targetMemoryId = existing.id,
                    text = "The user prefers concise answers.",
                    canonicalKey = checkNotNull(existing.canonicalKey),
                    scope = existing.scope,
                    evidenceAt = 12
                )
            )
        )

        val rendered = controller.render(input, fixture.baseMarkdown, operations, renderedAt = 20)

        assertTrue(rendered.targets.single().targetContent.contains(footer))
        assertEquals("fingerprint", rendered.targets.single().targetIndexFingerprint)
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
            existingMemories = existing.map { entry -> entry.toBatchExistingMemory() },
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
        evidenceKeys: List<String> = listOf("evidence-1"),
        canonicalKey: String? = if (action in setOf(
                MemoryDailyDistillationAction.CREATE,
                MemoryDailyDistillationAction.REPLACE
            )
        ) {
            "communication.response_style"
        } else {
            null
        },
        scope: String? = canonicalKey?.let { MemoryScope.GENERAL },
        evidenceAt: Long? = canonicalKey?.let { 2L },
        recallState: String? = canonicalKey?.let { MemoryRecallState.QUERY }
    ) = MemoryDailyDistillationOperation(
        action = action,
        targetMemoryId = targetMemoryId,
        text = text,
        type = "communication_style",
        sensitivity = sensitivity,
        source = source,
        evidenceKeys = evidenceKeys,
        canonicalKey = canonicalKey,
        scope = scope,
        evidenceAt = evidenceAt,
        recallState = recallState,
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

    private fun MarkdownMemoryEntry.toBatchExistingMemory() = MemoryBatchExistingMemory(
        id = id,
        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
        text = text,
        type = type,
        sensitivity = sensitivity,
        source = source,
        updatedAt = updatedAt,
        createdAt = createdAt,
        canonicalKey = canonicalKey,
        scope = scope,
        lastObservedAt = lastObservedAt,
        validity = validity,
        supersededBy = supersededBy,
        recallState = recallState,
        evidenceRefs = evidenceRefs
    )

    private data class Fixture(
        val baseMarkdown: String,
        val input: MemoryDailyDistillationFrozenInput
    )
}
