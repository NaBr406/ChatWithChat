package cn.nabr.chatwithchat.data.memory

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `escaped exported markdown is parsed and repeated managed sections are normalized`() {
        val markdown = """
            \# ChatWithChat Memory

            \## Projects

            <!-- memory:id=mem_project_a type=project_context sensitivity=normal source=explicit\_user\_statement created=1 updated=1 -->
            \- First project fact.

            \## Projects

            <!-- memory:id=mem_project_b type=project_context sensitivity=normal source=explicit\_user\_statement created=2 updated=2 -->
            \- Second project fact.
        """.trimIndent()

        val repaired = checkNotNull(codec.repairStructuralRelationships(markdown))

        assertEquals(2, repaired.entries.size)
        assertEquals(1, repaired.repairedCount)
        assertEquals(1, repaired.markdown.lines().count { it == "## Projects" })
        assertTrue(repaired.markdown.contains("mem_project_a"))
        assertTrue(repaired.markdown.contains("mem_project_b"))
    }

    @Test
    fun `active projection hides obsolete history while preserving current entries`() {
        val markdown = codec.renderLongTerm(
            listOf(
                MarkdownMemoryEntry(
                    id = "mem_current",
                    text = "Current preferred answer style.",
                    type = "communication_style",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.EXPLICIT_USER_STATEMENT,
                    canonicalKey = "communication.response_style",
                    validity = MemoryValidity.CURRENT,
                    recallState = MemoryRecallState.QUERY,
                    updatedAt = 2
                ),
                MarkdownMemoryEntry(
                    id = "mem_old",
                    text = "Old preferred answer style.",
                    type = "communication_style",
                    sensitivity = MemorySensitivity.NORMAL,
                    source = MemorySource.EXPLICIT_USER_STATEMENT,
                    canonicalKey = "communication.response_style",
                    validity = MemoryValidity.OBSOLETE,
                    supersededBy = "mem_current",
                    recallState = MemoryRecallState.MAINTENANCE_ONLY,
                    updatedAt = 1
                )
            )
        )

        val projection = codec.renderLongTermActiveProjection(markdown)

        assertTrue(projection.contains("Current preferred answer style."))
        assertTrue(!projection.contains("Old preferred answer style."))
        assertTrue(markdown.contains("Old preferred answer style."))
    }

    @Test
    fun `canonical lifecycle metadata round trips with unknown metadata and multilingual text`() {
        val entries = listOf(
            MarkdownMemoryEntry(
                id = "mem_address_current",
                text = "用户希望在工作场景中被称为北辰。\nPlease answer naturally in Chinese.",
                type = "communication_style",
                sensitivity = MemorySensitivity.PRIVATE,
                source = MemorySource.USER_CONFIRMED,
                chatId = 42,
                createdAt = 100,
                updatedAt = 200,
                section = "长期记忆",
                canonicalKey = "identity.preferred_address",
                scope = MemoryScope.WORK,
                lastObservedAt = 250,
                validity = MemoryValidity.CURRENT,
                recallState = MemoryRecallState.CORE,
                evidenceRefs = listOf("daily:2026-07-27:turn-9", "job/confirm-1"),
                extraMetadata = mapOf("future_flag" to "enabled")
            ),
            MarkdownMemoryEntry(
                id = "mem_address_old",
                text = "The user previously used another work name.",
                type = "communication_style",
                sensitivity = MemorySensitivity.PRIVATE,
                source = MemorySource.EXPLICIT_USER_STATEMENT,
                createdAt = 80,
                updatedAt = 190,
                section = "长期记忆",
                canonicalKey = "identity.preferred_address",
                scope = MemoryScope.WORK,
                lastObservedAt = 190,
                validity = MemoryValidity.OBSOLETE,
                supersededBy = "mem_address_current",
                recallState = MemoryRecallState.MAINTENANCE_ONLY,
                evidenceRefs = listOf("daily:2026-07-01:turn-2")
            ),
            MarkdownMemoryEntry(
                id = "mem_language_contested",
                text = "用户的长期回复语言仍需确认。",
                type = "communication_style",
                sensitivity = MemorySensitivity.NORMAL,
                source = MemorySource.ASSISTANT_INFERRED,
                createdAt = 120,
                updatedAt = 220,
                section = "长期记忆",
                canonicalKey = "locale.response_language",
                scope = MemoryScope.GENERAL,
                lastObservedAt = 230,
                validity = MemoryValidity.CONTESTED,
                recallState = MemoryRecallState.MAINTENANCE_ONLY,
                evidenceRefs = listOf("daily:2026-07-26:turn-4")
            )
        )
        val rendered = codec.renderLongTerm(entries)
        val markdown = rendered + "\n## 手写附录\n\n这段内容不是 memory entry，必须原样保留。\n"

        val parsed = codec.parse(markdown)

        assertEquals(markdown, parsed.rawMarkdown)
        assertEquals(emptyList<SkippedMarkdownMemoryEntry>(), parsed.skippedEntries)
        assertEquals(entries.associateBy { entry -> entry.id }, parsed.entries.associateBy { entry -> entry.id })
        assertTrue(markdown.contains("canonical_key=identity.preferred_address"))
        assertTrue(markdown.contains("validity=obsolete"))
        assertTrue(markdown.contains("superseded_by=mem_address_current"))
        assertTrue(markdown.contains("future_flag=enabled"))
        assertTrue(markdown.endsWith("这段内容不是 memory entry，必须原样保留。\n"))
    }

    @Test
    fun `legacy metadata uses defaults without rewriting raw bytes`() {
        val markdown = """
            # ChatWithChat Memory

            ## Projects

            <!-- memory:id=legacy_updated type=project_context sensitivity=normal source=assistant_inferred created=10 updated=20 -->
            - Updated time is the observation fallback.

            <!-- memory:id=legacy_created type=project_context sensitivity=normal source=assistant_inferred created=11 -->
            - Created time is the observation fallback.

            <!-- memory:id=legacy_unknown type=project_context sensitivity=normal source=assistant_inferred -->
            - Unknown times remain unknown.

            <!-- memory:id=explicit_zero type=project_context sensitivity=normal source=assistant_inferred created=12 updated=22 observed=0 -->
            - An explicitly unknown observation is not treated as a missing field.
        """.trimIndent()

        val parsed = codec.parse(markdown)
        val entries = parsed.entries.associateBy { entry -> entry.id }

        assertEquals(markdown, parsed.rawMarkdown)
        assertEquals(markdown, codec.updateObservations(markdown, emptyList()).markdown)
        assertEquals(emptyList<SkippedMarkdownMemoryEntry>(), parsed.skippedEntries)
        assertEquals(20, entries.getValue("legacy_updated").lastObservedAt)
        assertEquals(11, entries.getValue("legacy_created").lastObservedAt)
        assertEquals(0, entries.getValue("legacy_unknown").lastObservedAt)
        assertEquals(0, entries.getValue("explicit_zero").lastObservedAt)
        entries.values.forEach { entry ->
            assertEquals(null, entry.canonicalKey)
            assertEquals(MemoryScope.GENERAL, entry.scope)
            assertEquals(MemoryValidity.CURRENT, entry.validity)
            assertEquals(MemoryRecallState.QUERY, entry.recallState)
            assertEquals(emptyList<String>(), entry.evidenceRefs)
            assertEquals(emptyMap<String, String>(), entry.extraMetadata)
        }
    }

    @Test
    fun `present invalid optional metadata is rejected instead of using legacy defaults`() {
        val cases = linkedMapOf(
            "chat=2147483648" to "invalid chat",
            "created=-1" to "invalid created",
            "updated=tomorrow" to "invalid updated",
            "observed=-1" to "invalid observed",
            "canonical_key=identity" to "invalid canonical key",
            "scope=project:Upper" to "invalid scope",
            "validity=retired" to "invalid validity",
            "superseded_by=bad,ref" to "invalid supersession target",
            "recall=always" to "invalid recall state",
            "evidence=event:1,event:1" to "invalid evidence"
        )

        cases.forEach { (metadata, expectedReason) ->
            val markdown = """
                <!-- memory:id=mem_invalid type=project_context sensitivity=normal source=assistant_inferred $metadata -->
                - This entry must not receive a legacy default.
            """.trimIndent()

            val parsed = codec.parse(markdown)

            assertTrue("Expected $metadata to be rejected", parsed.entries.isEmpty())
            assertEquals("Unexpected reason for $metadata", expectedReason, parsed.skippedEntries.single().reason)
        }
    }

    @Test
    fun `duplicate unsafe oversized and unterminated metadata fail closed`() {
        val required = "type=project_context sensitivity=normal source=assistant_inferred"
        val tooManyFields = (1..25).joinToString(" ") { index -> "future_$index=value" }
        val cases = linkedMapOf(
            "<!-- memory:id=mem_one id=mem_two $required -->" to "duplicate metadata key",
            "<!-- memory:id=mem_unsafe_key $required bad-key=value -->" to "unsafe metadata key",
            "<!-- memory:id=mem_unsafe_value $required future=<unsafe> -->" to "unsafe metadata value",
            "<!-- memory:id=mem_too_many $required $tooManyFields -->" to "too many metadata fields",
            "<!-- memory:id=mem_oversized $required future=${"x".repeat(2_050)} -->" to "metadata comment too long",
            "<!-- memory:id=mem_unterminated $required" to "malformed metadata comment"
        )

        cases.forEach { (comment, expectedReason) ->
            val parsed = codec.parse("$comment\n- Malformed metadata must not become a memory entry.")

            assertTrue("Expected malformed comment to be rejected", parsed.entries.isEmpty())
            assertEquals(expectedReason, parsed.skippedEntries.single().reason)
        }
    }

    @Test
    fun `malformed lifecycle combinations are skipped`() {
        val required = "type=project_context sensitivity=normal source=assistant_inferred canonical_key=project.current scope=general"
        val cases = linkedMapOf(
            "validity=current superseded_by=mem_target recall=query" to "current entry cannot be superseded",
            "validity=contested recall=core" to "contested entry must be maintenance only",
            "validity=obsolete recall=maintenance_only" to "obsolete entry requires supersession target"
        )

        cases.entries.forEachIndexed { index, (lifecycle, expectedReason) ->
            val markdown = """
                <!-- memory:id=mem_lifecycle_$index $required $lifecycle -->
                - Invalid lifecycle state.
            """.trimIndent()

            val parsed = codec.parse(markdown)

            assertTrue(parsed.entries.isEmpty())
            assertEquals(expectedReason, parsed.skippedEntries.single().reason)
        }
    }

    @Test
    fun `document supersession requires a current target with matching identity`() {
        val current = canonicalEntry(id = "mem_target")
        val obsolete = canonicalEntry(
            id = "mem_old",
            validity = MemoryValidity.OBSOLETE,
            supersededBy = current.id,
            recallState = MemoryRecallState.MAINTENANCE_ONLY
        )
        val valid = codec.parse(codec.renderLongTerm(listOf(obsolete, current)))

        assertEquals(emptyList<SkippedMarkdownMemoryEntry>(), valid.skippedEntries)
        assertEquals(setOf("mem_old", "mem_target"), valid.entries.map { entry -> entry.id }.toSet())

        val invalidEntrySets = listOf(
            listOf(obsolete.copy(supersededBy = obsolete.id)) to
                "obsolete entry cannot supersede itself",
            listOf(obsolete.copy(supersededBy = "mem_missing")) to
                "supersession target not found",
            listOf(
                obsolete,
                current.copy(
                    validity = MemoryValidity.CONTESTED,
                    recallState = MemoryRecallState.MAINTENANCE_ONLY
                )
            ) to "supersession target must be current",
            listOf(obsolete, current.copy(canonicalKey = "project.other")) to
                "supersession target identity mismatch",
            listOf(obsolete, current.copy(scope = MemoryScope.PERSONAL)) to
                "supersession target identity mismatch"
        )

        invalidEntrySets.forEach { (entries, expectedReason) ->
            val renderError = assertThrows(IllegalArgumentException::class.java) {
                codec.renderLongTerm(entries)
            }
            val markdown = uncheckedDocument(*entries.toTypedArray())
            val parsed = codec.parse(markdown)

            assertEquals(expectedReason, renderError.message)
            assertTrue(parsed.entries.none { entry -> entry.id == obsolete.id })
            assertTrue(parsed.skippedEntries.any { skipped -> skipped.reason == expectedReason })
        }
    }

    @Test
    fun `duplicate document ids are all skipped`() {
        val entries = listOf(
            canonicalEntry(id = "mem_duplicate", text = "First value."),
            canonicalEntry(id = "mem_duplicate", text = "Second value.", canonicalKey = "project.other")
        )
        val renderError = assertThrows(IllegalArgumentException::class.java) {
            codec.renderLongTerm(entries)
        }
        val markdown = uncheckedDocument(*entries.toTypedArray())

        val parsed = codec.parse(markdown)

        assertEquals("duplicate memory id", renderError.message)
        assertTrue(parsed.entries.isEmpty())
        assertEquals(2, parsed.skippedEntries.size)
        assertTrue(parsed.skippedEntries.all { skipped -> skipped.reason == "duplicate memory id" })
    }

    @Test
    fun `structural repair deterministically renames duplicate ids without dropping content`() {
        val markdown = uncheckedDocument(
            canonicalEntry(id = "mem_duplicate", text = "First value."),
            canonicalEntry(id = "mem_duplicate", text = "Second value.", canonicalKey = "project.other")
        ) + "\n## Manual Notes\n\nKeep this paragraph unchanged.\n"

        val repaired = checkNotNull(codec.repairStructuralRelationships(markdown))
        val parsed = codec.parse(repaired.markdown)
        val replay = checkNotNull(codec.repairStructuralRelationships(repaired.markdown))

        assertEquals(1, repaired.repairedCount)
        assertTrue(parsed.skippedEntries.isEmpty())
        assertEquals(setOf("First value.", "Second value."), parsed.entries.map(MarkdownMemoryEntry::text).toSet())
        assertEquals(2, parsed.entries.map(MarkdownMemoryEntry::id).distinct().size)
        assertTrue(parsed.entries.any { entry -> entry.id == "mem_duplicate" })
        assertTrue(repaired.markdown.contains("## Manual Notes\n\nKeep this paragraph unchanged."))
        assertEquals(0, replay.repairedCount)
        assertEquals(repaired.markdown, replay.markdown)
    }

    @Test
    fun `structural repair coalesces repeated managed sections from exported sample`() {
        val profile = canonicalEntry(
            id = "education_major",
            text = "目前即将升入大二，专业是计算机科学与技术（计科）。",
            canonicalKey = "profile.education.major"
        ).copy(type = "stable_profile")
        val institution = canonicalEntry(
            id = "institution_tier",
            text = "就读于二本院校。",
            canonicalKey = "profile.education.institution_tier"
        ).copy(type = "stable_profile")
        val project = canonicalEntry(
            id = "chatwithchat_project",
            text = "正在开发一个基于 GPTMobile 改造的 AI 应用。",
            canonicalKey = "project.chatwithchat"
        )
        val markdown = buildString {
            appendLine("# ChatWithChat Memory")
            appendLine()
            appendLine(codec.renderLongTermAppend(listOf(profile)).trim())
            appendLine()
            appendLine(codec.renderLongTermAppend(listOf(project)).trim())
            appendLine()
            appendLine(codec.renderLongTermAppend(listOf(institution)).trim())
        }

        val repaired = checkNotNull(codec.repairStructuralRelationships(markdown))
        val parsed = codec.parse(repaired.markdown)
        val replay = checkNotNull(codec.repairStructuralRelationships(repaired.markdown))

        assertEquals(1, repaired.repairedCount)
        assertEquals(1, Regex("(?m)^## Stable Profile$").findAll(repaired.markdown).count())
        assertEquals(1, Regex("(?m)^## Projects$").findAll(repaired.markdown).count())
        assertEquals(setOf(profile.id, institution.id, project.id), parsed.entries.map { it.id }.toSet())
        assertEquals(0, replay.repairedCount)
        assertEquals(repaired.markdown, replay.markdown)
    }

    @Test
    fun `long term append reuses managed sections but preserves documents with manual prose`() {
        val first = canonicalEntry(
            id = "education_major",
            text = "专业是计算机科学与技术。",
            canonicalKey = "profile.education.major"
        ).copy(type = "stable_profile")
        val second = canonicalEntry(
            id = "institution_tier",
            text = "就读于二本院校。",
            canonicalKey = "profile.education.institution_tier"
        ).copy(type = "stable_profile")
        val managed = codec.appendLongTermEntries(codec.renderLongTerm(listOf(first)), listOf(second))
        val manualBase = codec.renderLongTerm(listOf(first)) + "\nManual note that must remain byte-preserved.\n"
        val manual = codec.appendLongTermEntries(manualBase, listOf(second))

        assertEquals(1, Regex("(?m)^## Stable Profile$").findAll(managed).count())
        assertEquals(setOf(first.id, second.id), codec.parse(managed).entries.map { it.id }.toSet())
        assertTrue(manual.startsWith(manualBase.trimEnd()))
        assertTrue(manual.contains("Manual note that must remain byte-preserved."))
        assertEquals(2, Regex("(?m)^## Stable Profile$").findAll(manual).count())
    }

    @Test
    fun `structural repair leaves repeated sections untouched when manual prose is present`() {
        val first = canonicalEntry(id = "first", text = "First managed fact.")
        val second = canonicalEntry(id = "second", text = "Second managed fact.", canonicalKey = "project.second")
        val markdown = buildString {
            appendLine("# ChatWithChat Memory")
            appendLine()
            appendLine(codec.renderLongTermAppend(listOf(first)).trim())
            appendLine()
            appendLine("Manual note that is not a managed memory entry.")
            appendLine()
            appendLine(codec.renderLongTermAppend(listOf(second)).trim())
        }

        val repaired = checkNotNull(codec.repairStructuralRelationships(markdown))

        assertEquals(0, repaired.repairedCount)
        assertEquals(markdown, repaired.markdown)
    }

    @Test
    fun `structural repair quarantines missing and dangling supersessions as contested history`() {
        val dangling = canonicalEntry(
            id = "mem_dangling",
            text = "Historical preference.",
            validity = MemoryValidity.OBSOLETE,
            supersededBy = "mem_missing",
            recallState = MemoryRecallState.MAINTENANCE_ONLY
        )
        val missingTarget = canonicalEntry(
            id = "mem_missing_target",
            text = "Historical preference without a successor.",
            validity = MemoryValidity.OBSOLETE,
            supersededBy = "mem_legacy_placeholder",
            recallState = MemoryRecallState.MAINTENANCE_ONLY
        )
        val markdown = uncheckedDocument(dangling, missingTarget)
            .replace(" superseded_by=mem_legacy_placeholder", "")

        val repaired = checkNotNull(codec.repairStructuralRelationships(markdown))
        val entries = codec.parse(repaired.markdown).entries.associateBy(MarkdownMemoryEntry::id)

        assertEquals(2, repaired.repairedCount)
        listOf(dangling, missingTarget).forEach { original ->
            val entry = checkNotNull(entries[original.id])
            assertEquals(original.text, entry.text)
            assertEquals(MemoryValidity.CONTESTED, entry.validity)
            assertEquals(MemoryRecallState.MAINTENANCE_ONLY, entry.recallState)
            assertEquals(null, entry.supersededBy)
        }
    }

    @Test
    fun `structural repair bounds actual edits instead of skipped duplicate rows`() {
        val entries = (0 until 17).flatMap { index ->
            listOf(
                canonicalEntry(
                    id = "mem_duplicate_$index",
                    text = "First duplicate value $index."
                ),
                canonicalEntry(
                    id = "mem_duplicate_$index",
                    text = "Second duplicate value $index.",
                    canonicalKey = "project.duplicate_$index"
                )
            )
        }
        val markdown = uncheckedDocument(*entries.toTypedArray())

        val repaired = checkNotNull(codec.repairStructuralRelationships(markdown))
        val parsed = codec.parse(repaired.markdown)

        assertEquals(34, codec.parse(markdown).skippedEntries.size)
        assertEquals(17, repaired.repairedCount)
        assertTrue(!repaired.hasRemainingRepairs)
        assertTrue(parsed.skippedEntries.isEmpty())
        assertEquals(entries.map(MarkdownMemoryEntry::text).toSet(), parsed.entries.map(MarkdownMemoryEntry::text).toSet())
    }

    @Test
    fun `structural repair continues oversized relationship damage in bounded batches`() {
        val entries = (0 until MemoryControlledOperationPolicy.MAX_OPERATIONS + 8).map { index ->
            canonicalEntry(
                id = "mem_dangling_$index",
                text = "Historical value $index.",
                canonicalKey = "project.history_$index",
                validity = MemoryValidity.OBSOLETE,
                supersededBy = "mem_missing_$index",
                recallState = MemoryRecallState.MAINTENANCE_ONLY
            )
        }
        val markdown = uncheckedDocument(*entries.toTypedArray())

        val first = checkNotNull(codec.repairStructuralRelationships(markdown))
        val second = checkNotNull(codec.repairStructuralRelationships(first.markdown))
        val replay = checkNotNull(codec.repairStructuralRelationships(second.markdown))
        val parsed = codec.parse(second.markdown)

        assertEquals(MemoryControlledOperationPolicy.MAX_OPERATIONS, first.repairedCount)
        assertTrue(first.hasRemainingRepairs)
        assertEquals(8, second.repairedCount)
        assertTrue(!second.hasRemainingRepairs)
        assertEquals(0, replay.repairedCount)
        assertEquals(second.markdown, replay.markdown)
        assertTrue(parsed.skippedEntries.isEmpty())
        assertEquals(entries.map(MarkdownMemoryEntry::text).toSet(), parsed.entries.map(MarkdownMemoryEntry::text).toSet())
    }

    @Test
    fun `targeted replace preserves canonical lifecycle and unknown metadata`() {
        val markdown = """
            # ChatWithChat Memory

            Intro text should stay.

            ## Projects

            <!-- memory:id=mem_progress type=project_context sensitivity=private source=explicit_user_statement created=10 updated=20 canonical_key=project.current scope=project:chatwithchat observed=20 validity=current recall=query evidence=turn:1 future_flag=enabled schema_hint=v2 -->
            - 原始项目事实。

            <!-- memory:id=mem_keep type=project_context sensitivity=normal source=assistant_inferred created=11 updated=21 -->
            - Keep this unrelated memory.

            ## 手写附录

            Footer text should stay.
        """.trimIndent()
        val existing = codec.parse(markdown).entries.single { entry -> entry.id == "mem_progress" }
        val replacement = existing.copy(
            text = "更新后的项目事实。",
            updatedAt = 30,
            lastObservedAt = 30
        )

        val result = codec.replaceEntriesById(markdown, listOf(replacement))
        val parsed = codec.parse(result.markdown)
        val replaced = parsed.entries.single { entry -> entry.id == existing.id }

        assertEquals(1, result.replacedCount)
        assertEquals("更新后的项目事实。", replaced.text)
        assertEquals(existing.createdAt, replaced.createdAt)
        assertEquals(30, replaced.updatedAt)
        assertEquals(30, replaced.lastObservedAt)
        assertEquals(existing.canonicalKey, replaced.canonicalKey)
        assertEquals(existing.scope, replaced.scope)
        assertEquals(existing.validity, replaced.validity)
        assertEquals(existing.supersededBy, replaced.supersededBy)
        assertEquals(existing.recallState, replaced.recallState)
        assertEquals(existing.evidenceRefs, replaced.evidenceRefs)
        assertEquals(mapOf("future_flag" to "enabled", "schema_hint" to "v2"), replaced.extraMetadata)
        assertTrue(result.markdown.contains("Intro text should stay."))
        assertTrue(result.markdown.contains("Keep this unrelated memory."))
        assertTrue(result.markdown.contains("Footer text should stay."))
    }

    @Test
    fun `observation update changes only the target hidden timestamp and older updates are no ops`() {
        val markdown = """
            # ChatWithChat Memory

            ## Projects

            <!-- memory:id=mem_target type=project_context sensitivity=normal source=explicit_user_statement created=10 updated=20 canonical_key=project.current scope=general observed=20 validity=current recall=query evidence=turn:1 future_flag=enabled -->
            - 目标正文必须保持不变。

            <!-- memory:id=mem_other type=project_context sensitivity=normal source=assistant_inferred created=11 updated=21 canonical_key=project.other scope=general observed=21 validity=current recall=query -->
            - Unrelated entry must remain byte-identical.
        """.trimIndent().replace("\n", "\r\n")
        val expected = markdown.replace("observed=20", "observed=30")

        val updated = codec.updateObservations(
            markdown,
            listOf(MarkdownMemoryObservationUpdate(entryId = "mem_target", lastObservedAt = 30))
        )
        val older = codec.updateObservations(
            updated.markdown,
            listOf(MarkdownMemoryObservationUpdate(entryId = "mem_target", lastObservedAt = 25))
        )
        val duplicate = codec.updateObservations(
            updated.markdown,
            listOf(
                MarkdownMemoryObservationUpdate(
                    entryId = "mem_target",
                    lastObservedAt = 30,
                    evidenceRefs = listOf("turn:1")
                )
            )
        )
        val entry = codec.parse(updated.markdown).entries.single { candidate -> candidate.id == "mem_target" }

        assertEquals(expected, updated.markdown)
        assertEquals(1, updated.updatedCount)
        assertEquals(updated.markdown, older.markdown)
        assertEquals(0, older.updatedCount)
        assertEquals(updated.markdown, duplicate.markdown)
        assertEquals(0, duplicate.updatedCount)
        assertEquals("目标正文必须保持不变。", entry.text)
        assertEquals(10, entry.createdAt)
        assertEquals(20, entry.updatedAt)
        assertEquals(30, entry.lastObservedAt)
        assertEquals(mapOf("future_flag" to "enabled"), entry.extraMetadata)
    }

    @Test
    fun `evidence only observation update does not materialize a legacy observed field`() {
        val markdown = """
            # ChatWithChat Memory

            ## Projects

            <!-- memory:id=mem_target type=project_context sensitivity=normal source=explicit_user_statement created=10 updated=20 canonical_key=project.current scope=general validity=current recall=query evidence=turn:1 future_flag=enabled -->
            - Evidence-only updates must not rewrite semantic or time fields.
        """.trimIndent()
        val expected = markdown.replace("evidence=turn:1", "evidence=turn:1,turn:2")

        val updated = codec.updateObservations(
            markdown,
            listOf(
                MarkdownMemoryObservationUpdate(
                    entryId = "mem_target",
                    lastObservedAt = 20,
                    evidenceRefs = listOf("turn:2")
                )
            )
        )
        val replay = codec.updateObservations(
            updated.markdown,
            listOf(
                MarkdownMemoryObservationUpdate(
                    entryId = "mem_target",
                    lastObservedAt = 20,
                    evidenceRefs = listOf("turn:2")
                )
            )
        )

        assertEquals(expected, updated.markdown)
        assertEquals(1, updated.updatedCount)
        assertEquals(updated.markdown, replay.markdown)
        assertEquals(0, replay.updatedCount)
    }

    @Test
    fun `observation update ignores metadata shaped continuation text`() {
        val metadataShapedText =
            "First line.\n" +
                "<!-- memory:id=mem_target type=project_context sensitivity=normal " +
                "source=explicit_user_statement observed=20 -->"
        val entry = canonicalEntry(id = "mem_target", text = metadataShapedText)
        val markdown = codec.renderLongTerm(listOf(entry))

        val updated = codec.updateObservations(
            markdown,
            listOf(MarkdownMemoryObservationUpdate(entryId = entry.id, lastObservedAt = 30))
        )
        val reparsed = codec.parse(updated.markdown).entries.single()

        assertEquals(1, updated.updatedCount)
        assertEquals(30, reparsed.lastObservedAt)
        assertEquals(entry.text, reparsed.text)
        assertTrue(reparsed.text.contains("observed=20"))
        assertEquals(1, Regex("observed=30").findAll(updated.markdown).count())
    }

    private fun canonicalEntry(
        id: String,
        text: String = "Canonical fact.",
        canonicalKey: String = "project.current",
        scope: String = MemoryScope.GENERAL,
        validity: String = MemoryValidity.CURRENT,
        supersededBy: String? = null,
        recallState: String = MemoryRecallState.QUERY
    ): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = "project_context",
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = 10,
        updatedAt = 20,
        canonicalKey = canonicalKey,
        scope = scope,
        lastObservedAt = 20,
        validity = validity,
        supersededBy = supersededBy,
        recallState = recallState,
        evidenceRefs = listOf("turn:1")
    )

    private fun uncheckedDocument(vararg entries: MarkdownMemoryEntry): String = buildString {
        appendLine("# ChatWithChat Memory")
        appendLine()
        appendLine("## Projects")
        appendLine()
        entries.forEach { entry ->
            appendLine(codec.metadataComment(entry))
            appendLine("- ${entry.text}")
            appendLine()
        }
    }
}
