package cn.nabr.chatwithchat.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCorpusQualityFixtureTest {
    private val codec = MarkdownMemoryCodec()
    private val policy = MemoryLongTermConsolidationPolicy()
    private val controller = MemoryLongTermConsolidationOperationController(
        markdownMemoryCodec = codec,
        targetIndexFingerprint = "fingerprint"
    )

    @Test
    fun `exported corpus quality fixture preserves durable facts and hides retired diagnostics`() = runBlocking {
        val fixture = exportedCorpusFixture()
        assertEquals(23, fixture.size)
        assertEquals(19, fixture.count { entry -> entry.validity == MemoryValidity.CURRENT })
        assertEquals(4, fixture.count { entry -> entry.validity == MemoryValidity.OBSOLETE })
        assertEquals(2, fixture.count { entry -> entry.recallState == MemoryRecallState.CORE })

        val partition = policy.nextPartition(fixture, cursor = 0)
        val groups = policy.candidateGroups(
            allEntries = fixture,
            partition = partition,
            alreadyAssignedIds = emptySet(),
            forceReview = true
        )
        val diagnostic = fixture.single { entry -> entry.id == "fixture_recall_debug" }
        val diagnosticGroup = groups.single { group ->
            group.entries.any { candidate -> candidate.memoryId == diagnostic.id }
        }
        val proposal = policy.validateAndMergeProposal(
            existing = MemoryLongTermPersistedProposal(),
            partitionRequest = MemoryLongTermConsolidationPartitionRequest(
                checkpointId = "fixture-checkpoint",
                partitionStart = partition.start,
                partitionEndExclusive = partition.endExclusive,
                candidateGroups = groups
            ),
            proposal = MemoryLongTermConsolidationProposal(
                decisions = listOf(
                    MemoryLongTermCanonicalDecision(
                        action = MemoryLongTermDecisionAction.RETIRE,
                        memoryIds = listOf(diagnostic.id),
                        canonicalKey = diagnostic.canonicalKey,
                        scope = diagnostic.scope,
                        reason = "hard-negative: recall diagnostics are temporary state"
                    )
                )
            )
        )
        assertTrue(diagnostic.id in diagnosticGroup.entries.map { candidate -> candidate.memoryId })

        val baseMarkdown = codec.renderLongTerm(fixture)
        val rendered = controller.render(
            baseMarkdown = baseMarkdown,
            entries = fixture,
            proposal = proposal,
            renderedAt = 20L
        )
        val retiredMarkdown = rendered.targets.single().targetContent
        val retiredEntries = codec.parse(retiredMarkdown).entries
        val retired = retiredEntries.single { entry -> entry.id == diagnostic.id }
        val mergedEntries = retiredEntries.count { entry ->
            entry.validity == MemoryValidity.OBSOLETE && entry.supersededBy != null
        }
        val keptEntries = retiredEntries.count { entry -> entry.validity == MemoryValidity.CURRENT }
        assertEquals(MemoryValidity.OBSOLETE, retired.validity)
        assertEquals(MemoryRecallState.MAINTENANCE_ONLY, retired.recallState)
        assertEquals(diagnostic.text, retired.text)
        assertEquals(diagnostic.evidenceRefs, retired.evidenceRefs)
        assertEquals(16, keptEntries)
        assertEquals(2, mergedEntries)

        val chunking = MemoryChunker(codec).chunksFor(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown = retiredMarkdown,
            projectionPolicy = MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        )
        assertFalse(chunking.chunks.any { chunk -> chunk.entryId == diagnostic.id })
        val snapshot = MemoryCorpusSnapshot(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            canonicalSourceHash = retiredMarkdown.sha256Utf8(),
            recallProjectionHash = chunking.projectionHash,
            generation = 1L,
            chunks = chunking.chunks
        )
        val results = MarkdownLexicalRetriever(StaticCorpusSnapshotSource(snapshot)).retrieve(
            MemoryRetrievalRequest(
                corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
                query = "recall threshold diagnostic",
                limit = 8,
                candidateLimit = 24,
                tokenBudget = null
            )
        ).getOrThrow()
        assertTrue(results.none { result -> result.entryId == diagnostic.id })
        println(
            "memory-quality-fixture|total=23|current=19|obsolete=4|core=2|" +
                "kept=$keptEntries|merged=$mergedEntries|retired=1|ignored=10"
        )
    }

    private fun exportedCorpusFixture(): List<MarkdownMemoryEntry> = buildList {
        add(entry("fixture_preferred_address", "Call the user Alex.", "stable_profile", "identity.preferred_address", recallState = MemoryRecallState.CORE))
        add(entry("fixture_assistant_name", "The assistant name is Small C.", "stable_profile", "identity.assistant_name", recallState = MemoryRecallState.CORE))
        add(entry("fixture_collaboration_boundary", "Make minimal code changes and preserve existing behavior.", "boundary", "collaboration.minimal_changes"))
        add(entry("fixture_minecraft_server", "The Minecraft server uses Paper and runs in offline mode.", "project_context", "project.minecraft.server", scope = "project:minecraft"))
        add(entry("fixture_chat_memory_invariant", "ChatWithChat long-term memory uses Markdown as its source of truth.", "project_context", "project.chatwithchat.memory_source", scope = "project:chatwithchat"))
        add(entry("fixture_chat_provider_invariant", "ChatWithChat supports multiple provider routes for chat completion.", "project_context", "project.chatwithchat.provider_routes", scope = "project:chatwithchat"))
        add(entry("fixture_ai_interest_1", "The user follows AI post-training research.", "interest", "interest.ai_post_training"))
        add(entry("fixture_ai_interest_2", "The user is interested in reinforcement learning for language models.", "interest", "interest.ai_post_training"))
        add(entry("fixture_ai_interest_3", "The user tracks practical methods for improving language-model training.", "interest", "interest.ai_post_training"))
        add(entry("fixture_compliance_opinion", "The user disliked a one-off compliance proposal.", "important_event", "event.compliance_opinion"))
        add(entry("fixture_model_price", "A model price was discussed once.", "important_event", "event.model_price"))
        add(entry("fixture_data_labeling", "A one-time data-labeling reaction was recorded.", "important_event", "event.data_labeling"))
        add(entry("fixture_recall_debug", "Current recall threshold diagnostic.", "project_context", "project.chatwithchat.recall_debug", scope = "project:chatwithchat"))
        add(entry("fixture_copyright_plan", "A one-time software-copyright plan was discussed.", "important_event", "event.software_copyright"))
        add(entry("fixture_mbti", "The user may have an uncertain MBTI result.", "stable_profile", "profile.mbti"))
        add(entry("fixture_project_snapshot", "The current memory index generation is 17.", "project_context", "project.chatwithchat.index_generation", scope = "project:chatwithchat"))
        add(entry("fixture_temporary_task", "The user is currently debugging a recall test.", "light_productivity_preference", "task.current_recall_debug"))
        add(entry("fixture_tool_policy", "The application should always call a tool before answering.", "project_context", "policy.tool_calling"))
        add(entry("fixture_stable_profile", "The user studies computer science.", "stable_profile", "profile.education"))
        add(obsolete("fixture_old_project_1", "An obsolete project snapshot.", "project.chatwithchat.old_snapshot"))
        add(obsolete("fixture_old_project_2", "A superseded diagnostic snapshot.", "project.chatwithchat.old_diagnostic"))
        add(obsolete("fixture_old_interest", "An obsolete interest wording.", "interest.ai_old"))
        add(obsolete("fixture_old_event", "An obsolete historical event note.", "event.old_note"))
    }

    private fun entry(
        id: String,
        text: String,
        type: String,
        canonicalKey: String,
        scope: String = MemoryScope.GENERAL,
        recallState: String = MemoryRecallState.QUERY
    ): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = type,
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = 1L,
        updatedAt = 2L,
        canonicalKey = canonicalKey,
        scope = scope,
        lastObservedAt = 2L,
        recallState = recallState,
        evidenceRefs = listOf("fixture:$id")
    )

    private fun obsolete(id: String, text: String, canonicalKey: String): MarkdownMemoryEntry = entry(
        id = id,
        text = text,
        type = "project_context",
        canonicalKey = canonicalKey,
        scope = "project:chatwithchat"
    ).copy(
        validity = MemoryValidity.OBSOLETE,
        recallState = MemoryRecallState.MAINTENANCE_ONLY,
        evidenceRefs = listOf("fixture:$id")
    )
}

private class StaticCorpusSnapshotSource(
    private val snapshot: MemoryCorpusSnapshot
) : MemoryCorpusSnapshotSource {
    override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> =
        Result.success(listOf(snapshot))

    override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> = Result.success(true)
}
