package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexDefaults

class MemoryLongTermConsolidationOperationController(
    markdownMemoryCodec: MarkdownMemoryCodec,
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
        val proposalGroups = consolidationPolicy.proposalCandidates(entries, proposal)
        val proposalIds = proposalGroups.flatten().mapNotNull(CanonicalMemoryCandidate::targetMemoryId).toSet()
        val localCandidates = consolidationPolicy
            .locallyDeterministicCandidates(entries)
            .filterNot { candidate -> candidate.targetMemoryId in proposalIds }
        val (selectedCandidates, hasMoreCandidates) = consolidationPolicy.selectBoundedCandidateGroups(
            localCandidates = localCandidates,
            proposalCandidateGroups = proposalGroups
        )
        val merge = mergePolicy.merge(
            baseMarkdown = baseMarkdown,
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
                    materialMutationCount = merge.materialEntryMutationCount
                )
            )
        }
        return RenderedMemoryLongTermConsolidation(
            targets = targets,
            targetSourceHash = merge.markdown.toByteArray(Charsets.UTF_8).sha256Hex(),
            operationCount = merge.changedEntryCount,
            hasMoreCandidates = hasMoreCandidates || merge.hasMoreMutations
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
