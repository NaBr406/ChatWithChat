package cn.nabr.chatwithchat.data.memory

/**
 * A read-only migration proposal for a complete, app-managed MEMORY.md document.
 * Callers must explicitly pass the proposal to the existing CAS mutation flow.
 */
internal data class MemoryMigrationProposal(
    val sourceMarkdown: String,
    val targetMarkdown: String,
    val beforeAudit: MemoryCanonicalCorpusAudit,
    val afterAudit: MemoryCanonicalCorpusAudit,
    val changedEntryCount: Int,
    val materialMutationCount: Int
) {
    val isNoOp: Boolean
        get() = sourceMarkdown == targetMarkdown && changedEntryCount == 0
}

internal class MemoryMigrationPlanningException(message: String) : IllegalArgumentException(message)

internal object MemoryMigrationPlanner {
    private val codec = MarkdownMemoryCodec()
    private val mergePolicy = CanonicalMemoryMergePolicy(codec)

    fun propose(markdown: String, mutationAt: Long): MemoryMigrationProposal {
        if (mutationAt < 0L) {
            throw MemoryMigrationPlanningException("invalid migration timestamp")
        }
        val parsed = codec.parse(markdown)
        if (parsed.skippedEntries.isNotEmpty() || !codec.isValidLongTermDocument(markdown)) {
            throw MemoryMigrationPlanningException("memory document is not a complete managed document")
        }

        val beforeAudit = MemoryCanonicalCorpusAuditor.audit(parsed.entries)
        var targetMarkdown = markdown
        var changedEntryCount = 0
        var materialMutationCount = 0

        val normalizedEntries = parsed.entries.map { entry -> normalizeEntry(entry) }
        val replacements = normalizedEntries.filterIndexed { index, entry ->
            entry != parsed.entries[index]
        }
        if (replacements.isNotEmpty()) {
            val replacement = codec.replaceEntriesById(targetMarkdown, replacements)
            if (replacement.replacedCount != replacements.size) {
                throw MemoryMigrationPlanningException("migration replacement target missing")
            }
            targetMarkdown = replacement.markdown
            changedEntryCount += replacement.replacedCount
            materialMutationCount += replacements.count { entry ->
                entry.canonicalKey != parsed.entries.first { original -> original.id == entry.id }.canonicalKey
            }
        }

        val normalizedParse = parseManaged(targetMarkdown)
        val duplicateGroups = normalizedParse.entries
            .filter { entry -> entry.validity == MemoryValidity.CURRENT && entry.canonicalKey != null }
            .groupBy { entry ->
                MemoryCanonicalIdentityPolicy.normalizeCanonicalKey(
                    canonicalKey = checkNotNull(entry.canonicalKey),
                    scope = entry.scope
                ) to entry.scope
            }
            .filterValues { entries -> entries.size > 1 }

        if (duplicateGroups.isNotEmpty()) {
            val candidates = duplicateGroups.values
                .flatten()
                .sortedBy(MarkdownMemoryEntry::id)
                .map { entry -> entry.toMigrationCandidate() }
            if (candidates.size > MemoryControlledOperationPolicy.MAX_OPERATIONS) {
                throw MemoryMigrationPlanningException("migration exceeds the deterministic operation limit")
            }
            val merge = try {
                mergePolicy.merge(
                    baseMarkdown = targetMarkdown,
                    candidates = candidates,
                    mutationAt = mutationAt,
                    allowCanonicalRebinding = true
                )
            } catch (throwable: Throwable) {
                throw MemoryMigrationPlanningException(
                    "canonical migration could not be planned: ${throwable.message ?: "invalid corpus"}"
                )
            }
            targetMarkdown = merge.markdown
            changedEntryCount += merge.changedEntryCount
            materialMutationCount += merge.materialMutationCount
        }

        val finalEntries = parseManaged(targetMarkdown).entries
        val afterAudit = MemoryCanonicalCorpusAuditor.audit(finalEntries)
        if (!afterAudit.isHealthy) {
            throw MemoryMigrationPlanningException(
                "migration requires manual review: ${afterAudit.unhealthyReasons().joinToString(", ") }"
            )
        }
        if (targetMarkdown == markdown) {
            changedEntryCount = 0
            materialMutationCount = 0
        }
        return MemoryMigrationProposal(
            sourceMarkdown = markdown,
            targetMarkdown = targetMarkdown,
            beforeAudit = beforeAudit,
            afterAudit = afterAudit,
            changedEntryCount = changedEntryCount,
            materialMutationCount = materialMutationCount
        )
    }

    private fun parseManaged(markdown: String): MarkdownMemoryParseResult {
        val parsed = codec.parse(markdown)
        if (parsed.skippedEntries.isNotEmpty() || !codec.isValidLongTermDocument(markdown)) {
            throw MemoryMigrationPlanningException("migration produced an invalid managed document")
        }
        return parsed
    }

    private fun normalizeEntry(entry: MarkdownMemoryEntry): MarkdownMemoryEntry = entry.copy(
        canonicalKey = entry.canonicalKey?.let { key ->
            MemoryCanonicalIdentityPolicy.normalizeCanonicalKey(key, entry.scope)
        },
        recallState = when {
            entry.validity == MemoryValidity.OBSOLETE -> MemoryRecallState.MAINTENANCE_ONLY
            entry.validity == MemoryValidity.CURRENT &&
                entry.source == MemorySource.ASSISTANT_INFERRED &&
                entry.recallState == MemoryRecallState.CORE -> MemoryRecallState.QUERY
            else -> entry.recallState
        }
    )

    private fun MarkdownMemoryEntry.toMigrationCandidate(): CanonicalMemoryCandidate =
        CanonicalMemoryCandidate(
            targetMemoryId = id,
            chatId = chatId,
            text = text,
            type = type,
            sensitivity = sensitivity,
            source = source,
            canonicalKey = checkNotNull(canonicalKey),
            scope = scope,
            evidenceAt = lastObservedAt,
            recallState = recallState.takeIf { state ->
                state == MemoryRecallState.CORE || state == MemoryRecallState.QUERY
            } ?: MemoryRecallState.QUERY,
            evidenceRefs = evidenceRefs
        )

    private fun MemoryCanonicalCorpusAudit.unhealthyReasons(): List<String> = buildList {
        if (activeDuplicateKeys.isNotEmpty()) add("active_duplicate_keys")
        if (aliasKeys.isNotEmpty()) add("alias_keys")
        if (missingCanonicalKeyIds.isNotEmpty()) add("missing_canonical_keys")
        if (obsoleteVisibleIds.isNotEmpty()) add("obsolete_visible_entries")
        if (identityBundleIds.isNotEmpty()) add("identity_bundle_entries")
    }
}
