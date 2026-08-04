package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexDefaults

class MemoryLongTermConsolidationOperationController(
    private val markdownMemoryCodec: MarkdownMemoryCodec,
    private val targetIndexFingerprint: String = MemoryVectorIndexDefaults.configuration.fingerprint()
) {
    private val consolidationPolicy = MemoryLongTermConsolidationPolicy()
    private val mergePolicy = CanonicalMemoryMergePolicy(markdownMemoryCodec)

    fun render(
        baseMarkdown: String,
        entries: List<MarkdownMemoryEntry>,
        proposal: MemoryLongTermPersistedProposal,
        renderedAt: Long
    ): RenderedMemoryLongTermConsolidation {
        val retirementIds = consolidationPolicy.retirementMemoryIds(entries, proposal)
        val retirementDecisionById = proposal.decisions
            .filter { decision -> decision.action == MemoryLongTermDecisionAction.RETIRE }
            .flatMap { decision -> decision.memoryIds.map { memoryId -> memoryId to decision } }
            .toMap()
        val entriesById = entries.associateBy(MarkdownMemoryEntry::id)
        val selectedRetirementIds = retirementIds.take(MemoryControlledOperationPolicy.MAX_OPERATIONS)
        val retiredMarkdown = if (selectedRetirementIds.isEmpty()) {
            baseMarkdown
        } else {
            val replacements = selectedRetirementIds.map { memoryId ->
                val entry = requireNotNull(entriesById[memoryId])
                val decision = requireNotNull(retirementDecisionById[memoryId])
                entry.copy(
                    canonicalKey = decision.canonicalKey ?: entry.canonicalKey
                        ?: "maintenance.retired.${memoryId.sha256Utf8().take(16)}",
                    scope = decision.scope ?: entry.scope,
                    updatedAt = renderedAt,
                    validity = MemoryValidity.OBSOLETE,
                    supersededBy = null,
                    recallState = MemoryRecallState.MAINTENANCE_ONLY
                )
            }
            val replacement = markdownMemoryCodec.replaceEntriesById(baseMarkdown, replacements)
            require(replacement.replacedCount == replacements.size) { "retirement target changed before render" }
            replacement.markdown
        }
        val proposalGroups = consolidationPolicy.proposalCandidates(entries, proposal)
        val proposalIds = (
            proposalGroups.flatten().mapNotNull(CanonicalMemoryCandidate::targetMemoryId) + retirementIds
            ).toSet()
        val localCandidates = consolidationPolicy
            .locallyDeterministicCandidates(entries)
            .filterNot { candidate -> candidate.targetMemoryId in proposalIds }
        val (selectedCandidates, hasMoreCandidates) = consolidationPolicy.selectBoundedCandidateGroups(
            localCandidates = localCandidates,
            proposalCandidateGroups = proposalGroups,
            maxOperations = MemoryControlledOperationPolicy.MAX_OPERATIONS - selectedRetirementIds.size
        )
        val merge = mergePolicy.merge(
            baseMarkdown = retiredMarkdown,
            candidates = selectedCandidates,
            mutationAt = renderedAt,
            allowCanonicalRebinding = true,
            promoteRecallState = true,
            maxEntryMutations = MemoryControlledOperationPolicy.MAX_OPERATIONS
        )
        val targets = if (merge.markdown == baseMarkdown) {
            emptyList()
        } else {
            listOf(
                MemoryMutationTarget(
                    sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                    baseContent = baseMarkdown,
                    targetContent = merge.markdown,
                    targetIndexFingerprint = targetIndexFingerprint,
                    materialMutationCount = selectedRetirementIds.size + merge.materialEntryMutationCount
                )
            )
        }
        return RenderedMemoryLongTermConsolidation(
            targets = targets,
            targetSourceHash = merge.markdown.toByteArray(Charsets.UTF_8).sha256Hex(),
            operationCount = selectedRetirementIds.size + merge.changedEntryCount,
            hasMoreCandidates = retirementIds.size > selectedRetirementIds.size ||
                hasMoreCandidates ||
                merge.hasMoreMutations
        )
    }

    fun renderStructuralRepair(
        baseMarkdown: String,
        repairedMarkdown: String,
        repairedCount: Int
    ): RenderedMemoryLongTermConsolidation {
        require(repairedCount in 1..MemoryControlledOperationPolicy.MAX_OPERATIONS) {
            "invalid structural memory repair count"
        }
        require(baseMarkdown != repairedMarkdown) { "structural memory repair must change the document" }
        return RenderedMemoryLongTermConsolidation(
            targets = listOf(
                MemoryMutationTarget(
                    sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                    baseContent = baseMarkdown,
                    targetContent = repairedMarkdown,
                    targetIndexFingerprint = targetIndexFingerprint,
                    materialMutationCount = repairedCount
                )
            ),
            targetSourceHash = repairedMarkdown.toByteArray(Charsets.UTF_8).sha256Hex(),
            operationCount = repairedCount,
            hasMoreCandidates = true
        )
    }
}

data class RenderedMemoryLongTermConsolidation(
    val targets: List<MemoryMutationTarget>,
    val targetSourceHash: String,
    val operationCount: Int,
    val hasMoreCandidates: Boolean
)
