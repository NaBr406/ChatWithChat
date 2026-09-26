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

/** Semantic output supplied by the reviewer; Kotlin does not infer or split natural-language facts. */
internal data class MemoryAddressingReview(
    val entryId: String,
    val sourceTextHash: String,
    val addressingText: String,
    val boundaries: List<MemoryReviewedBoundary> = emptyList()
)

internal data class MemoryReviewedBoundary(
    val canonicalKey: String,
    val text: String,
    val evidenceRefs: List<String>
)

internal data class MemoryProjectReview(
    val entryId: String,
    val sourceTextHash: String,
    val projectText: String
)

internal object MemoryMigrationPlanner {
    private val codec = MarkdownMemoryCodec()
    private val mergePolicy = CanonicalMemoryMergePolicy(codec)

    fun propose(
        markdown: String,
        mutationAt: Long,
        addressingReviews: List<MemoryAddressingReview> = emptyList(),
        projectReviews: List<MemoryProjectReview> = emptyList()
    ): MemoryMigrationProposal {
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

        require(addressingReviews.map { it.entryId }.distinct().size == addressingReviews.size) {
            "duplicate addressing review"
        }
        require(projectReviews.map { it.entryId }.distinct().size == projectReviews.size) {
            "duplicate project review"
        }
        val reviewedEntries = parsed.entries.toMutableList()
        addressingReviews.forEach { review ->
            val index = reviewedEntries.indexOfFirst { it.id == review.entryId }
            require(index >= 0) { "addressing review target missing" }
            val original = reviewedEntries[index]
            require(
                original.validity == MemoryValidity.CURRENT &&
                    MemoryCanonicalIdentityPolicy.isAddressingIdentity(original.canonicalKey, original.scope)
            ) { "addressing review target invalid" }
            if (original.extraMetadata[MemoryCanonicalCorpusAuditor.ADDRESSING_REVIEW_HASH] == original.text.sha256Utf8()) {
                return@forEach
            }
            require(original.text.sha256Utf8() == review.sourceTextHash) { "addressing review source changed" }
            validateReviewedText(review.addressingText)
            review.boundaries.forEach { boundary ->
                validateReviewedText(boundary.text)
                require(
                    boundary.canonicalKey.startsWith("boundary.") &&
                        MarkdownMemoryMetadataPolicy.isCanonicalKey(boundary.canonicalKey)
                ) { "addressing boundary key invalid" }
                require(boundary.evidenceRefs.isNotEmpty() && boundary.evidenceRefs.all(original.evidenceRefs::contains)) {
                    "addressing boundary requires original evidence"
                }
                val id = "mem_split_${"${original.id}|${boundary.canonicalKey}".sha256Utf8().take(24)}"
                require(reviewedEntries.none { it.id == id }) { "addressing boundary id conflict" }
                reviewedEntries += original.copy(
                    id = id,
                    text = boundary.text,
                    type = "boundary",
                    canonicalKey = boundary.canonicalKey,
                    recallState = if (original.source == MemorySource.ASSISTANT_INFERRED) {
                        MemoryRecallState.QUERY
                    } else {
                        original.recallState
                    },
                    evidenceRefs = boundary.evidenceRefs.distinct().sorted(),
                    durability = MemoryDurability.DURABLE,
                    freshness = MemoryFreshness.STABLE,
                    lifecycleState = MemoryLifecycleState.ACTIVE,
                    refreshAfterDays = null,
                    extraMetadata = original.extraMetadata - MemoryCanonicalCorpusAuditor.ADDRESSING_REVIEW_HASH
                )
                changedEntryCount++
                materialMutationCount++
            }
            if (review.addressingText != original.text) {
                val historyId = "mem_hist_${"addressing-split|${original.id}|${original.text.sha256Utf8()}".sha256Utf8().take(24)}"
                require(reviewedEntries.none { it.id == historyId }) { "addressing history id conflict" }
                reviewedEntries += original.copy(
                    id = historyId,
                    validity = MemoryValidity.OBSOLETE,
                    recallState = MemoryRecallState.MAINTENANCE_ONLY,
                    supersededBy = original.id,
                    updatedAt = mutationAt
                )
                changedEntryCount++
                materialMutationCount++
            }
            reviewedEntries[index] = original.copy(
                text = review.addressingText,
                updatedAt = if (review.addressingText == original.text) original.updatedAt else mutationAt,
                extraMetadata = original.extraMetadata +
                    (MemoryCanonicalCorpusAuditor.ADDRESSING_REVIEW_HASH to review.addressingText.sha256Utf8())
            )
        }
        projectReviews.forEach { review ->
            val historyId = "mem_hist_${"project-review|${review.entryId}|${review.sourceTextHash}".sha256Utf8().take(24)}"
            val history = reviewedEntries.firstOrNull { it.id == historyId }
            if (history?.validity == MemoryValidity.OBSOLETE &&
                history.text.sha256Utf8() == review.sourceTextHash &&
                reviewedEntries.any { it.id == history.supersededBy && it.validity == MemoryValidity.CURRENT && it.text == review.projectText }
            ) {
                return@forEach
            }
            val index = reviewedEntries.indexOfFirst { it.id == review.entryId }
            require(index >= 0) { "project review target missing" }
            val original = reviewedEntries[index]
            require(original.validity == MemoryValidity.CURRENT && original.type == "project_context") {
                "project review target invalid"
            }
            require(original.text.sha256Utf8() == review.sourceTextHash) { "project review source changed" }
            validateReviewedText(review.projectText)
            require(review.projectText != original.text) { "project review must change the text" }
            require(reviewedEntries.none { it.id == historyId }) { "project history id conflict" }
            reviewedEntries += original.copy(
                id = historyId,
                validity = MemoryValidity.OBSOLETE,
                recallState = MemoryRecallState.MAINTENANCE_ONLY,
                supersededBy = original.id,
                updatedAt = mutationAt
            )
            reviewedEntries[index] = original.copy(
                text = review.projectText,
                updatedAt = mutationAt,
                extraMetadata = original.extraMetadata + (PROJECT_REVIEW_HASH to review.projectText.sha256Utf8())
            )
            changedEntryCount++
            materialMutationCount++
        }
        val normalizedEntries = reviewedEntries.map { entry -> normalizeEntry(entry) }
        val replacements = normalizedEntries.filterIndexed { index, entry ->
            index < parsed.entries.size && entry != parsed.entries[index]
        }
        if (replacements.isNotEmpty()) {
            val replacement = codec.replaceEntriesById(targetMarkdown, replacements)
            if (replacement.replacedCount != replacements.size) {
                throw MemoryMigrationPlanningException("migration replacement target missing")
            }
            targetMarkdown = replacement.markdown
            changedEntryCount += replacement.replacedCount
            materialMutationCount += replacements.count { entry ->
                val original = parsed.entries.first { it.id == entry.id }
                entry.canonicalKey != original.canonicalKey || entry.scope != original.scope || entry.text != original.text
            }
        }
        val additions = normalizedEntries.drop(parsed.entries.size)
        if (additions.isNotEmpty()) targetMarkdown = codec.appendLongTermEntries(targetMarkdown, additions)

        val normalizedParse = parseManaged(targetMarkdown)
        val duplicateGroups = normalizedParse.entries
            .filter { entry -> entry.validity == MemoryValidity.CURRENT && entry.canonicalKey != null }
            .groupBy { entry ->
                MemoryCanonicalIdentityPolicy.normalizeCanonicalKey(
                    canonicalKey = checkNotNull(entry.canonicalKey),
                    scope = entry.scope
                ) to MemoryCanonicalIdentityPolicy.normalizeScope(entry.canonicalKey, entry.scope)
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

    private fun validateReviewedText(text: String) {
        require(
            text.isNotBlank() &&
                text == text.trim() &&
                text.length <= MemoryControlledOperationPolicy.MAX_MEMORY_TEXT_CHARS &&
                text.toModelVisibleMemoryFactOrNull() != null
        ) { "addressing review text invalid" }
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
        scope = MemoryCanonicalIdentityPolicy.normalizeScope(entry.canonicalKey, entry.scope),
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

    private const val PROJECT_REVIEW_HASH = "project_review_hash"
}
