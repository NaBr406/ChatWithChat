package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLongTermConsolidationOperationControllerTest {
    private val codec = MarkdownMemoryCodec()
    private val policy = MemoryLongTermConsolidationPolicy()
    private val controller = MemoryLongTermConsolidationOperationController(
        markdownMemoryCodec = codec,
        targetIndexFingerprint = "fingerprint"
    )

    @Test
    fun `retire preserves id evidence and content while hiding entry from active recall`() {
        val entry = MarkdownMemoryEntry(
            id = "mem_diagnostic",
            text = "Current recall threshold diagnostic.",
            type = "project_context",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 10L,
            updatedAt = 12L,
            canonicalKey = "project.recall_diagnostic",
            scope = "project:chatwithchat",
            lastObservedAt = 11L,
            recallState = MemoryRecallState.QUERY,
            evidenceRefs = listOf("chat:1:user:2")
        )
        val baseMarkdown = codec.renderLongTerm(listOf(entry))
        val partition = policy.nextPartition(listOf(entry), 0)
        val group = policy.candidateGroups(listOf(entry), partition, emptySet(), forceReview = true).single()
        val request = MemoryLongTermConsolidationPartitionRequest(
            checkpointId = "checkpoint",
            partitionStart = partition.start,
            partitionEndExclusive = partition.endExclusive,
            candidateGroups = listOf(group)
        )
        val proposal = policy.validateAndMergeProposal(
            existing = MemoryLongTermPersistedProposal(),
            partitionRequest = request,
            proposal = MemoryLongTermConsolidationProposal(
                decisions = listOf(
                    MemoryLongTermCanonicalDecision(
                        action = MemoryLongTermDecisionAction.RETIRE,
                        memoryIds = listOf(entry.id),
                        canonicalKey = entry.canonicalKey,
                        scope = entry.scope,
                        reason = "hard-negative: diagnostic state is not durable"
                    )
                )
            )
        )

        val rendered = controller.render(
            baseMarkdown = baseMarkdown,
            entries = listOf(entry),
            proposal = proposal,
            renderedAt = 20L
        )
        val retired = codec.parse(rendered.targets.single().targetContent).entries.single()

        assertEquals(1, rendered.operationCount)
        assertEquals("fingerprint", rendered.targets.single().targetIndexFingerprint)
        assertEquals(entry.id, retired.id)
        assertEquals(entry.text, retired.text)
        assertEquals(entry.evidenceRefs, retired.evidenceRefs)
        assertEquals(MemoryValidity.OBSOLETE, retired.validity)
        assertEquals(MemoryRecallState.MAINTENANCE_ONLY, retired.recallState)
        assertEquals(null, retired.supersededBy)

        val replay = controller.render(
            baseMarkdown = rendered.targets.single().targetContent,
            entries = codec.parse(rendered.targets.single().targetContent).entries,
            proposal = MemoryLongTermPersistedProposal(),
            renderedAt = 21L
        )
        assertTrue(replay.targets.isEmpty())
    }

    @Test
    fun `retirement and canonical rebinding preserve obsolete supersession relationships`() {
        val active = MarkdownMemoryEntry(
            id = "project_active",
            text = "The project uses cross-session retrieval.",
            type = "project_context",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 10L,
            updatedAt = 12L,
            canonicalKey = "project.old_retrieval",
            scope = "project:cross-session-retrieval"
        )
        val history = (1..3).map { index ->
            active.copy(
                id = "project_history_$index",
                text = "Historical retrieval direction $index.",
                validity = MemoryValidity.OBSOLETE,
                supersededBy = active.id,
                recallState = MemoryRecallState.MAINTENANCE_ONLY
            )
        }
        val retiring = active.copy(
            id = "project_retiring",
            text = "Temporary retrieval diagnostic.",
            canonicalKey = "project.retrieval_diagnostic"
        )
        val entries = listOf(active, retiring) + history
        val proposal = MemoryLongTermPersistedProposal(
            decisions = listOf(
                MemoryLongTermCanonicalDecision(
                    action = MemoryLongTermDecisionAction.RETIRE,
                    memoryIds = listOf(retiring.id),
                    canonicalKey = retiring.canonicalKey,
                    scope = retiring.scope,
                    recallState = MemoryRecallState.MAINTENANCE_ONLY,
                    reason = "Stale diagnostic"
                ),
                MemoryLongTermCanonicalDecision(
                    action = MemoryLongTermDecisionAction.CANONICALIZE,
                    memoryIds = listOf(active.id),
                    canonicalKey = "project.cross_session_retrieval",
                    scope = active.scope,
                    recallState = MemoryRecallState.QUERY,
                    reason = "Canonical project key"
                )
            )
        )

        val rendered = controller.render(codec.renderLongTerm(entries), entries, proposal, renderedAt = 30L)
        val parsed = codec.parse(rendered.targets.single().targetContent)

        assertTrue(parsed.skippedEntries.isEmpty())
        assertEquals(5, rendered.operationCount)
        assertEquals(MemoryValidity.OBSOLETE, parsed.entries.single { it.id == retiring.id }.validity)
        assertTrue(parsed.entries.filter { it.id in history.map(MarkdownMemoryEntry::id) }
            .all { it.canonicalKey == "project.cross_session_retrieval" && it.supersededBy == active.id })
    }
}
