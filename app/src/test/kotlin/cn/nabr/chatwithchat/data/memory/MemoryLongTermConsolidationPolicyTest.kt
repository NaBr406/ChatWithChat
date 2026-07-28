package cn.nabr.chatwithchat.data.memory

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLongTermConsolidationPolicyTest {
    private val policy = MemoryLongTermConsolidationPolicy()

    @Test
    fun `twenty four independent eligible anchors all become candidate groups`() {
        val entries = (0 until MemoryLongTermConsolidationPolicy.MAX_PARTITION_ENTRIES).map { index ->
            entry(id = "anchor_$index", text = "lexeme${index}x")
        }
        val partition = policy.nextPartition(entries, cursor = 0)

        val groups = policy.candidateGroups(
            allEntries = entries,
            partition = partition,
            alreadyAssignedIds = emptySet()
        )

        assertEquals(MemoryLongTermConsolidationPolicy.MAX_PARTITION_ENTRIES, groups.size)
        assertEquals(entries.map(MarkdownMemoryEntry::id), groups.flatMap { group -> group.anchorMemoryIds })
        assertEquals(entries.map(MarkdownMemoryEntry::id).toSet(), groups.flatMap { group -> group.entries }.map { it.memoryId }.toSet())
        assertTrue(groups.all { group -> group.entries.size == 1 })
    }

    @Test
    fun `oversize and ineligible legacy entries never enter a request`() {
        val eligible = entry(id = "eligible", text = "durable preference")
        val entries = listOf(
            eligible,
            entry(
                id = "oversize",
                text = "x".repeat(MemoryControlledOperationPolicy.MAX_MEMORY_TEXT_CHARS + 1)
            ),
            entry(id = "invalid_type", text = "legacy type", type = "legacy_unknown"),
            entry(id = "obsolete", text = "obsolete fact", validity = MemoryValidity.OBSOLETE),
            entry(
                id = "maintenance_only",
                text = "maintenance-only fact",
                recallState = MemoryRecallState.MAINTENANCE_ONLY
            )
        )
        val partition = policy.nextPartition(entries, cursor = 0)

        val requestIds = policy.candidateGroups(
            allEntries = entries,
            partition = partition,
            alreadyAssignedIds = emptySet()
        ).flatMap { group -> group.entries }.map(MemoryLongTermCandidateEntry::memoryId)

        assertEquals(listOf(eligible.id), requestIds)
    }

    @Test
    fun `candidate groups and aggregate request obey entry and character caps`() {
        val sharedText = "shared ${"x".repeat(700)} preference"
        val entries = (0 until 40).map { index ->
            entry(id = "similar_$index", text = "$sharedText $index")
        }
        val partition = policy.nextPartition(entries, cursor = 0)

        val groups = policy.candidateGroups(
            allEntries = entries,
            partition = partition,
            alreadyAssignedIds = emptySet()
        )

        assertTrue(groups.isNotEmpty())
        assertTrue(groups.size <= MemoryLongTermConsolidationPolicy.MAX_GROUPS_PER_PARTITION)
        assertTrue(groups.all { group -> group.entries.size <= MemoryLongTermConsolidationPolicy.MAX_GROUP_ENTRIES })
        assertTrue(
            groups.all { group ->
                group.requestCharacterCount() <= MemoryLongTermConsolidationPolicy.MAX_GROUP_CHARS
            }
        )
        assertTrue(
            groups.sumOf { group -> group.requestCharacterCount() } <=
                MemoryLongTermConsolidationPolicy.MAX_PARTITION_CHARS
        )
        val requestIds = groups.flatMap { group -> group.entries }.map(MemoryLongTermCandidateEntry::memoryId)
        assertEquals(requestIds.size, requestIds.distinct().size)
    }

    @Test
    fun `partition cursor traverses every ordered entry exactly once`() {
        val entries = (0 until 53).map { index ->
            entry(id = "ordered_$index", text = "cursor${index}x")
        }
        val visitedIds = mutableListOf<String>()
        var cursor = 0
        var partitionCount = 0

        while (cursor < entries.size) {
            val partition = policy.nextPartition(entries, cursor)
            assertEquals(cursor, partition.start)
            assertTrue(partition.endExclusive > cursor)
            visitedIds += partition.entries.map(MarkdownMemoryEntry::id)
            cursor = partition.endExclusive
            partitionCount += 1
        }

        assertEquals(entries.map(MarkdownMemoryEntry::id), visitedIds)
        assertEquals(entries.size, visitedIds.distinct().size)
        assertTrue(partitionCount >= 3)
        assertEquals(MemoryLongTermPartition(entries.size, entries.size, emptyList()), policy.nextPartition(entries, cursor))
    }

    @Test
    fun `serialized requests with escaped text stay bounded while every entry is scanned`() {
        val entries = (0 until 31).map { index ->
            entry(
                id = "escaped_$index",
                text = ("quoted \\\"path\\\\segment\\\" $index ").repeat(140).take(3_900)
            )
        }
        val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
            explicitNulls = false
        }
        val visitedIds = mutableListOf<String>()
        var cursor = 0
        var requestCount = 0

        while (cursor < entries.size) {
            val bounded = policy.nextBoundedRequest(
                checkpointId = "checkpoint-with-real-serialized-budget",
                orderedEntries = entries,
                cursor = cursor,
                alreadyAssignedIds = emptySet()
            )

            assertTrue(bounded.partition.endExclusive > cursor)
            assertEquals(bounded.serializedRequest, json.encodeToString(bounded.request))
            assertTrue(
                bounded.serializedRequest.length <=
                    MemoryLongTermConsolidationPolicy.MAX_SERIALIZED_REQUEST_CHARS
            )
            visitedIds += bounded.partition.entries.map(MarkdownMemoryEntry::id)
            cursor = bounded.partition.endExclusive
            requestCount += 1
        }

        assertEquals(entries.map(MarkdownMemoryEntry::id), visitedIds)
        assertTrue(requestCount > 1)
    }

    @Test
    fun `oversize canonical collision group is bounded and requires continuation`() {
        val entries = (0 until MemoryControlledOperationPolicy.MAX_OPERATIONS + 8).map { index ->
            entry(
                id = "collision_$index",
                text = "Preference revision $index",
                canonicalKey = "communication.response_style"
            )
        }

        val (selected, hasMore) = policy.selectBoundedCandidateGroups(
            localCandidates = policy.locallyDeterministicCandidates(entries),
            proposalCandidateGroups = emptyList()
        )

        assertEquals(MemoryControlledOperationPolicy.MAX_OPERATIONS, selected.size)
        assertEquals(entries.take(selected.size).map(MarkdownMemoryEntry::id), selected.map { candidate -> candidate.targetMemoryId })
        assertTrue(hasMore)
    }

    @Test
    fun `proposal decision cannot cross candidate group boundaries`() {
        val entries = listOf(
            entry(id = "first", text = "Prefers concise responses"),
            entry(id = "second", text = "Enjoys hiking on weekends")
        )
        val partition = policy.nextPartition(entries, cursor = 0)
        val groups = policy.candidateGroups(entries, partition, emptySet())
        assertEquals(2, groups.size)
        val request = MemoryLongTermConsolidationPartitionRequest(
            checkpointId = "checkpoint",
            partitionStart = partition.start,
            partitionEndExclusive = partition.endExclusive,
            candidateGroups = groups
        )

        assertThrows(IllegalStateException::class.java) {
            policy.validateAndMergeProposal(
                existing = MemoryLongTermPersistedProposal(),
                partitionRequest = request,
                proposal = MemoryLongTermConsolidationProposal(
                    decisions = listOf(
                        MemoryLongTermCanonicalDecision(
                            action = MemoryLongTermDecisionAction.CANONICALIZE,
                            memoryIds = groups.map { group -> group.entries.single().memoryId },
                            canonicalKey = "communication.response_style",
                            scope = MemoryScope.GENERAL,
                            recallState = MemoryRecallState.QUERY
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `same scope semantic duplicates with different legacy keys form one candidate group`() {
        val entries = listOf(
            entry(
                id = "old_key",
                text = "Address the user as Captain",
                canonicalKey = "identity.nickname"
            ),
            entry(
                id = "new_key",
                text = "The user's preferred address is Captain",
                canonicalKey = "identity.preferred_address"
            ),
            entry(
                id = "work_scope",
                text = "Address the user as Captain at work",
                canonicalKey = "identity.work_address",
                scope = MemoryScope.WORK
            )
        )
        val partition = policy.nextPartition(entries, cursor = 0)

        val groups = policy.candidateGroups(entries, partition, emptySet())

        assertEquals(1, groups.size)
        assertEquals(setOf("old_key", "new_key"), groups.single().entries.map { entry -> entry.memoryId }.toSet())
    }

    private fun MemoryLongTermCandidateGroup.requestCharacterCount(): Int =
        entries.sumOf { entry -> entry.text.length + ENTRY_OVERHEAD_CHARS }

    private fun entry(
        id: String,
        text: String,
        type: String = "stable_profile",
        validity: String = MemoryValidity.CURRENT,
        recallState: String = MemoryRecallState.QUERY,
        canonicalKey: String? = null,
        scope: String = MemoryScope.GENERAL
    ): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = type,
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = 1,
        updatedAt = 1,
        lastObservedAt = 1,
        validity = validity,
        canonicalKey = canonicalKey,
        scope = scope,
        recallState = recallState
    )

    private companion object {
        const val ENTRY_OVERHEAD_CHARS = 160
    }
}
