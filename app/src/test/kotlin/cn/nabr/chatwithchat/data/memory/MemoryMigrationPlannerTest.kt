package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMigrationPlannerTest {

    private val codec = MarkdownMemoryCodec()

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
