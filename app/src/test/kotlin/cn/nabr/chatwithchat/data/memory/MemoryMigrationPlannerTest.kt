package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMigrationPlannerTest {

    private val codec = MarkdownMemoryCodec()

    @Test
    fun `reviewed project removes mixed details and preserves recoverable history`() {
        val original = entry(
            id = "project_bundle",
            text = "The user maintains ChatWithChat, version 1.2, with MEMORY.md as its fact source.",
            canonicalKey = "project.chatwithchat",
            scope = "project:chatwithchat",
            source = MemorySource.USER_CONFIRMED
        )
        val source = codec.renderLongTerm(listOf(original))
        val review = MemoryProjectReview(original.id, original.text.sha256Utf8(), "The user maintains the ChatWithChat Android chat app.")
        val proposal = MemoryMigrationPlanner.propose(source, 100L, projectReviews = listOf(review))
        val entries = codec.parse(proposal.targetMarkdown).entries
        val current = entries.single { it.validity == MemoryValidity.CURRENT }
        val history = entries.single { it.validity == MemoryValidity.OBSOLETE }

        assertEquals(review.projectText, current.text)
        assertEquals(original.text, history.text)
        assertEquals(current.id, history.supersededBy)
        assertEquals(MemoryRecallState.MAINTENANCE_ONLY, history.recallState)
        assertTrue(current.text.toModelVisibleMemoryFactOrNull() != null)
        assertTrue(MemoryMigrationPlanner.propose(proposal.targetMarkdown, 101L, projectReviews = listOf(review)).isNoOp)
        assertTrue(MemoryMigrationPlanner.propose(proposal.targetMarkdown, 101L).isNoOp)
        assertTrue(
            runCatching {
                MemoryMigrationPlanner.propose(proposal.targetMarkdown, 101L, projectReviews = listOf(review.copy(projectText = "A different review.")))
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching {
                MemoryMigrationPlanner.propose(source, 100L, projectReviews = listOf(review.copy(sourceTextHash = "changed")))
            }.exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun `proposal normalizes aliases and isolates obsolete entries`() {
        val legacy = entry(
            id = "legacy_project",
            text = "The user maintains ChatWithChat.",
            canonicalKey = "project.gptmobile_ai_app",
            scope = "project:chatwithchat"
        )
        val obsolete = entry(
            id = "old_project",
            text = "An old project note.",
            canonicalKey = "project.gptmobile_ai_app",
            scope = "project:chatwithchat",
            validity = MemoryValidity.OBSOLETE,
            supersededBy = legacy.id,
            recallState = MemoryRecallState.MAINTENANCE_ONLY
        )

        val proposal = MemoryMigrationPlanner.propose(
            codec.renderLongTerm(listOf(legacy, obsolete)),
            mutationAt = 100L
        )

        assertFalse(proposal.isNoOp)
        assertEquals(listOf("legacy_project:project.gptmobile_ai_app", "old_project:project.gptmobile_ai_app"), proposal.beforeAudit.aliasKeys)
        assertTrue(proposal.afterAudit.isHealthy)
        val entries = codec.parse(proposal.targetMarkdown).entries
        assertEquals("project.chatwithchat", entries.first { it.id == legacy.id }.canonicalKey)
        assertEquals(MemoryRecallState.MAINTENANCE_ONLY, entries.first { it.id == obsolete.id }.recallState)
        assertTrue(codec.parse(codec.renderLongTermActiveProjection(proposal.targetMarkdown)).entries.none { it.id == obsolete.id })
    }

    @Test
    fun `real project alias scopes converge and replay without mutations`() {
        val legacy = entry(
            id = "mem_can_867dd03ca661b8273a6fce9c",
            text = "The user maintains the Android chat project.",
            canonicalKey = "project.gptmobile_ai_app",
            scope = "project:gptmobile-ai-app"
        )
        val current = entry(
            id = "mem_can_bb93370d6976079aa732d242",
            text = "The user maintains ChatWithChat.",
            canonicalKey = "project.chatwithchat",
            scope = "project:chatwithchat",
            source = MemorySource.USER_CONFIRMED,
            updatedAt = 2L
        )

        val proposal = MemoryMigrationPlanner.propose(codec.renderLongTerm(listOf(legacy, current)), 100L)
        val entries = codec.parse(proposal.targetMarkdown).entries
        assertTrue(proposal.afterAudit.isHealthy)
        assertEquals(1, entries.count { it.validity == MemoryValidity.CURRENT })
        assertEquals("project:chatwithchat", entries.first { it.id == legacy.id }.scope)
        val winner = entries.single { it.validity == MemoryValidity.CURRENT }
        val retired = entries.single { it.validity == MemoryValidity.OBSOLETE }
        assertEquals(current.text, winner.text)
        assertEquals(winner.id, retired.supersededBy)
        assertTrue(MemoryMigrationPlanner.propose(proposal.targetMarkdown, 101L).isNoOp)
    }

    @Test
    fun `reviewed addressing bundle separates emoji boundary and preserves source`() {
        val bundled = entry(
            id = "import_0db7c068d4b81028079b91ca",
            text = "用户希望以后被称呼为“大哥”；用户为 AI 取名为“小c”；用户不希望使用😅这个流汗黄豆表情。",
            canonicalKey = "identity.preferred_address"
        ).copy(
            type = "communication_style",
            source = MemorySource.USER_CONFIRMED,
            evidenceRefs = listOf("chat:1:user:1", "chat:1:user:3", "chat:30:user:178")
        )
        val source = codec.renderLongTerm(listOf(bundled))
        assertEquals(listOf(bundled.id), MemoryCanonicalCorpusAuditor.audit(listOf(bundled)).identityBundleIds)
        val review = MemoryAddressingReview(
            entryId = bundled.id,
            sourceTextHash = bundled.text.sha256Utf8(),
            addressingText = "用户希望被称呼为“大哥”，并将 AI 称为“小c”。",
            boundaries = listOf(
                MemoryReviewedBoundary(
                    canonicalKey = "boundary.emoji_usage",
                    text = "用户不希望使用😅表情。",
                    evidenceRefs = listOf("chat:30:user:178")
                )
            )
        )

        val proposal = MemoryMigrationPlanner.propose(source, 100L, listOf(review))
        val entries = codec.parse(proposal.targetMarkdown).entries
        assertTrue(proposal.afterAudit.isHealthy)
        assertEquals(1, entries.count { it.validity == MemoryValidity.CURRENT && it.canonicalKey == "boundary.emoji_usage" })
        assertEquals(1, entries.count { it.validity == MemoryValidity.OBSOLETE && it.text == bundled.text })
        assertEquals(review.addressingText, entries.first { it.id == bundled.id }.text)
        assertTrue(MemoryMigrationPlanner.propose(proposal.targetMarkdown, 101L).isNoOp)
    }

    @Test
    fun `duplicate current identities converge and replay is a no op`() {
        val first = entry(
            id = "first",
            text = "The user prefers concise answers.",
            canonicalKey = "communication.response_style",
            source = MemorySource.EXPLICIT_USER_STATEMENT
        )
        val second = entry(
            id = "second",
            text = "The user prefers detailed answers.",
            canonicalKey = "communication.response_style",
            source = MemorySource.ASSISTANT_INFERRED,
            updatedAt = 2L
        )

        val firstProposal = MemoryMigrationPlanner.propose(codec.renderLongTerm(listOf(first, second)), 50L)
        val current = codec.parse(firstProposal.targetMarkdown).entries.filter { it.validity == MemoryValidity.CURRENT }
        assertEquals(1, current.size)
        assertEquals(0, MemoryCanonicalCorpusAuditor.audit(current).activeDuplicateKeys.size)

        val replay = MemoryMigrationPlanner.propose(firstProposal.targetMarkdown, 51L)
        assertTrue(replay.isNoOp)
        assertEquals(firstProposal.targetMarkdown, replay.targetMarkdown)
    }

    @Test
    fun `assistant inferred core is demoted during migration`() {
        val inferred = entry(
            id = "inferred",
            text = "The user likes a particular workflow.",
            canonicalKey = "interest.workflow",
            source = MemorySource.ASSISTANT_INFERRED,
            recallState = MemoryRecallState.CORE
        )

        val proposal = MemoryMigrationPlanner.propose(codec.renderLongTerm(listOf(inferred)), 50L)

        val migrated = codec.parse(proposal.targetMarkdown).entries.single()
        assertEquals(MemoryRecallState.QUERY, migrated.recallState)
        assertNotEquals(proposal.sourceMarkdown, proposal.targetMarkdown)
    }

    @Test
    fun `malformed input is rejected without producing a target`() {
        val malformed = """
            # ChatWithChat Memory

            <!-- memory:id=broken type=interest sensitivity=normal source=not_a_source canonical_key=interest.broken scope=general validity=current recall=query -->
            - Missing the managed section layout.
        """.trimIndent()

        val exception = runCatching { MemoryMigrationPlanner.propose(malformed, 1L) }.exceptionOrNull()

        assertTrue(exception is MemoryMigrationPlanningException)
    }

    private fun entry(
        id: String,
        text: String,
        canonicalKey: String,
        scope: String = MemoryScope.GENERAL,
        source: String = MemorySource.EXPLICIT_USER_STATEMENT,
        validity: String = MemoryValidity.CURRENT,
        supersededBy: String? = null,
        recallState: String = MemoryRecallState.QUERY,
        updatedAt: Long = 1L
    ): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = if (scope.startsWith("project:")) "project_context" else "interest",
        sensitivity = MemorySensitivity.NORMAL,
        source = source,
        createdAt = 1L,
        updatedAt = updatedAt,
        canonicalKey = canonicalKey,
        scope = scope,
        validity = validity,
        supersededBy = supersededBy,
        recallState = recallState
    )
}
