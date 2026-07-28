package cn.nabr.chatwithchat.data.memory

internal data class CanonicalMemoryCandidate(
    val targetMemoryId: String? = null,
    val chatId: Int? = null,
    val text: String,
    val type: String,
    val sensitivity: String,
    val source: String,
    val canonicalKey: String,
    val scope: String,
    val evidenceAt: Long,
    val recallState: String,
    val evidenceRefs: List<String>
)

internal data class CanonicalMemoryMergeResult(
    val markdown: String,
    val acceptedCandidateCount: Int = 0,
    val changedEntryCount: Int = 0,
    val materialMutationCount: Int = 0,
    val requiresIndexSync: Boolean = false
)

internal class CanonicalMemoryMergePolicy(
    private val markdownMemoryCodec: MarkdownMemoryCodec
) {
    fun merge(
        baseMarkdown: String,
        candidates: List<CanonicalMemoryCandidate>,
        mutationAt: Long
    ): CanonicalMemoryMergeResult {
        require(mutationAt >= 0L) { "invalid canonical mutation time" }
        require(candidates.size <= MemoryControlledOperationPolicy.MAX_OPERATIONS) {
            "too many canonical candidates"
        }
        val parsed = parseEntriesOrThrow(baseMarkdown)
        if (candidates.isEmpty()) return CanonicalMemoryMergeResult(markdown = baseMarkdown)

        val entriesById = parsed.entries.associateBy(MarkdownMemoryEntry::id)
        val normalizedCandidates = candidates.map { candidate -> normalizeAndValidate(candidate, entriesById) }
        validateTargetIdentities(normalizedCandidates)

        val replacements = linkedMapOf<String, MarkdownMemoryEntry>()
        val removals = linkedSetOf<String>()
        val appends = linkedMapOf<String, MarkdownMemoryEntry>()
        val observationUpdates = linkedMapOf<String, MarkdownMemoryObservationUpdate>()
        val reservedIds = entriesById.keys.toMutableSet()
        val explicitlyTargetedIds = normalizedCandidates.mapNotNull(CanonicalMemoryCandidate::targetMemoryId).toSet()
        var acceptedCandidateCount = 0
        var materialMutationCount = 0
        var requiresIndexSync = false

        normalizedCandidates
            .groupBy { candidate -> candidate.identity() }
            .toSortedMap(compareBy<CanonicalMemoryIdentity> { identity -> identity.canonicalKey }.thenBy { it.scope })
            .forEach { (identity, identityCandidates) ->
                val targetIds = identityCandidates.mapNotNull(CanonicalMemoryCandidate::targetMemoryId).toSet()
                val candidateTexts = identityCandidates
                    .map { candidate -> normalizeExactMemoryText(candidate.text) }
                    .toSet()
                val identityEntries = parsed.entries.filter { entry ->
                    entry.identityOrNull() == identity ||
                        entry.id in targetIds ||
                        (
                            entry.canonicalKey == null &&
                                entry.validity == MemoryValidity.CURRENT &&
                                entry.id !in explicitlyTargetedIds &&
                                normalizeExactMemoryText(entry.text) in candidateTexts
                            )
                }
                require(
                    identityEntries.all { entry ->
                        entry.canonicalKey == null || entry.identityOrNull() == identity
                    }
                ) { "canonical target identity mismatch" }
                val currentEntries = identityEntries
                    .filter { entry -> entry.validity == MemoryValidity.CURRENT }
                    .sortedBy(MarkdownMemoryEntry::id)
                val existingVariants = currentEntries.toExistingVariants()
                existingVariants.sortedWith(variantComparator).firstOrNull()?.type?.let { existingType ->
                    require(identityCandidates.all { candidate -> candidate.type == existingType }) {
                        "canonical candidate type mismatch"
                    }
                }
                require(identityCandidates.map(CanonicalMemoryCandidate::type).distinct().size == 1) {
                    "canonical candidates have incompatible types"
                }

                val candidateVariants = identityCandidates.toCandidateVariants()
                val winningVariant = (existingVariants + candidateVariants).sortedWith(variantComparator).first()
                val acceptedCandidates = identityCandidates.filter { candidate ->
                    normalizeExactMemoryText(candidate.text) == winningVariant.normalizedText
                }
                acceptedCandidateCount += acceptedCandidates.size

                val survivor = currentEntries.firstOrNull()
                if (
                    survivor != null &&
                    currentEntries.size == 1 &&
                    winningVariant.normalizedText == normalizeExactMemoryText(survivor.text)
                ) {
                    val evidenceRefs = mergeEvidenceRefs(
                        existing = survivor.evidenceRefs,
                        additions = acceptedCandidates.flatMap(CanonicalMemoryCandidate::evidenceRefs)
                    )
                    val lastObservedAt = maxOf(
                        survivor.lastObservedAt,
                        acceptedCandidates.maxOfOrNull(CanonicalMemoryCandidate::evidenceAt) ?: 0L
                    )
                    if (survivor.canonicalKey == null) {
                        replacements[survivor.id] = survivor.copy(
                            canonicalKey = identity.canonicalKey,
                            scope = identity.scope,
                            updatedAt = mutationAt,
                            lastObservedAt = lastObservedAt,
                            evidenceRefs = evidenceRefs
                        )
                        materialMutationCount += 1
                    } else if (lastObservedAt > survivor.lastObservedAt || evidenceRefs != survivor.evidenceRefs) {
                        observationUpdates[survivor.id] = MarkdownMemoryObservationUpdate(
                            entryId = survivor.id,
                            lastObservedAt = lastObservedAt,
                            evidenceRefs = evidenceRefs
                        )
                    }
                    return@forEach
                }

                val activeId = survivor?.id ?: generatedActiveId(identity)
                if (survivor == null) {
                    require(reservedIds.add(activeId)) { "generated canonical active id conflict" }
                }
                val winningExistingEntries = currentEntries.filter { entry ->
                    normalizeExactMemoryText(entry.text) == winningVariant.normalizedText
                }
                val winningText = winningExistingEntries.firstOrNull()?.text ?: winningVariant.text
                val evidenceRefs = mergeEvidenceRefs(
                    existing = winningExistingEntries.flatMap(MarkdownMemoryEntry::evidenceRefs),
                    additions = acceptedCandidates.flatMap(CanonicalMemoryCandidate::evidenceRefs)
                )
                val lastObservedAt = maxOf(
                    winningVariant.evidenceAt,
                    acceptedCandidates.maxOfOrNull(CanonicalMemoryCandidate::evidenceAt) ?: 0L
                )
                val activeEntry = (
                    survivor ?: MarkdownMemoryEntry(
                        id = activeId,
                        text = winningText,
                        type = winningVariant.type,
                        sensitivity = winningVariant.sensitivity,
                        source = winningVariant.source,
                        chatId = winningVariant.chatId,
                        createdAt = mutationAt,
                        updatedAt = mutationAt,
                        section = null
                    )
                    ).copy(
                    id = activeId,
                    text = winningText,
                    type = winningVariant.type,
                    sensitivity = winningVariant.sensitivity,
                    source = winningVariant.source,
                    updatedAt = mutationAt,
                    canonicalKey = identity.canonicalKey,
                    scope = identity.scope,
                    lastObservedAt = lastObservedAt,
                    validity = MemoryValidity.CURRENT,
                    supersededBy = null,
                    recallState = winningVariant.recallState,
                    evidenceRefs = evidenceRefs
                )

                if (survivor == null) {
                    appends[activeId] = activeEntry
                } else {
                    replacements[activeId] = activeEntry
                }

                val losingCurrentIds = currentEntries.drop(1).map(MarkdownMemoryEntry::id).toSet()
                currentEntries.drop(1).forEach { loser ->
                    if (normalizeExactMemoryText(loser.text) == winningVariant.normalizedText) {
                        removals += loser.id
                    } else {
                        replacements[loser.id] = loser.toHistory(activeId, identity, mutationAt)
                    }
                }
                parsed.entries
                    .filter { entry ->
                        entry.validity == MemoryValidity.OBSOLETE && entry.supersededBy in losingCurrentIds
                    }
                    .forEach { obsolete -> replacements[obsolete.id] = obsolete.copy(supersededBy = activeId) }

                survivor?.takeIf { entry ->
                    normalizeExactMemoryText(entry.text) != winningVariant.normalizedText
                }?.let { replacedFact ->
                    val oldNormalizedText = normalizeExactMemoryText(replacedFact.text)
                    val oldFactAlreadyPreserved = identityEntries.any { entry ->
                        entry.id != replacedFact.id &&
                            normalizeExactMemoryText(entry.text) == oldNormalizedText &&
                            (entry.validity == MemoryValidity.OBSOLETE || entry.id in losingCurrentIds)
                    }
                    if (!oldFactAlreadyPreserved) {
                        val historyId = generatedHistoryId(identity, activeId, replacedFact)
                        entriesById[historyId]?.let { existingHistory ->
                            require(
                                existingHistory.identityOrNull() == identity &&
                                    normalizeExactMemoryText(existingHistory.text) == oldNormalizedText &&
                                    existingHistory.validity == MemoryValidity.OBSOLETE &&
                                    existingHistory.supersededBy == activeId
                            ) { "generated canonical history id conflict" }
                        } ?: run {
                            require(reservedIds.add(historyId)) { "generated canonical history id conflict" }
                            appends[historyId] = replacedFact.copy(id = historyId).toHistory(
                                activeId = activeId,
                                identity = identity,
                                mutationAt = mutationAt
                            )
                        }
                    }
                }
                materialMutationCount += 1
                requiresIndexSync = true
            }

        pruneExpandedHistoryAppends(
            originalEntries = parsed.entries,
            replacements = replacements,
            removals = removals,
            appends = appends
        )

        var markdown = baseMarkdown
        if (replacements.isNotEmpty()) {
            val replacement = markdownMemoryCodec.replaceEntriesById(markdown, replacements.values.sortedBy { it.id })
            require(replacement.replacedCount == replacements.size) { "canonical replacement target missing" }
            markdown = replacement.markdown
        }
        if (removals.isNotEmpty()) {
            val removal = markdownMemoryCodec.removeEntriesById(markdown, removals)
            require(removal.deletedCount == removals.size) { "canonical redundant target missing" }
            markdown = removal.markdown
        }
        if (appends.isNotEmpty()) {
            val append = markdownMemoryCodec.renderLongTermAppend(appends.values.sortedBy { it.id })
            markdown = markdown.trimEnd() + "\n\n" + append.trim() + "\n"
        }
        if (observationUpdates.isNotEmpty()) {
            markdown = markdownMemoryCodec.updateObservations(
                markdown,
                observationUpdates.values.sortedBy(MarkdownMemoryObservationUpdate::entryId)
            ).markdown
        }
        validateRenderedDocument(
            baseMarkdown = baseMarkdown,
            renderedMarkdown = markdown,
            touchedIdentities = normalizedCandidates.map { candidate -> candidate.identity() }.toSet()
        )
        return CanonicalMemoryMergeResult(
            markdown = markdown,
            acceptedCandidateCount = acceptedCandidateCount,
            changedEntryCount = replacements.size + removals.size + appends.size + observationUpdates.size,
            materialMutationCount = materialMutationCount,
            requiresIndexSync = requiresIndexSync
        )
    }

    private fun normalizeAndValidate(
        candidate: CanonicalMemoryCandidate,
        entriesById: Map<String, MarkdownMemoryEntry>
    ): CanonicalMemoryCandidate {
        val text = candidate.text.trim().replace(WHITESPACE_REGEX, " ")
        require(text.isNotBlank() && text.length <= MemoryControlledOperationPolicy.MAX_MEMORY_TEXT_CHARS) {
            "invalid canonical memory text"
        }
        require(candidate.type in MemoryControlledOperationPolicy.validTypes) { "invalid canonical memory type" }
        require(candidate.sensitivity in MemoryControlledOperationPolicy.validSensitivities) {
            "invalid canonical memory sensitivity"
        }
        require(candidate.source in MemoryControlledOperationPolicy.validSources) { "invalid canonical memory source" }
        require(MarkdownMemoryMetadataPolicy.isCanonicalKey(candidate.canonicalKey)) { "invalid canonical memory key" }
        require(MarkdownMemoryMetadataPolicy.isScope(candidate.scope)) { "invalid canonical memory scope" }
        require(candidate.evidenceAt >= 0L) { "invalid canonical evidence time" }
        require(candidate.recallState in ACTIVE_RECALL_STATES) { "invalid canonical recall state" }
        val evidenceRefs = candidate.evidenceRefs.distinct().sorted()
        MarkdownMemoryMetadataPolicy.encodeEvidenceRefs(evidenceRefs)
        candidate.targetMemoryId?.let { targetId ->
            require(MarkdownMemoryMetadataPolicy.isSafeReference(targetId)) { "invalid canonical target id" }
            val target = requireNotNull(entriesById[targetId]) { "unknown canonical target" }
            require(target.type == candidate.type) { "canonical target type mismatch" }
            require(target.canonicalKey == null || target.canonicalKey == candidate.canonicalKey) {
                "canonical target key mismatch"
            }
            require(target.canonicalKey == null || target.scope == candidate.scope) {
                "canonical target scope mismatch"
            }
        }
        return candidate.copy(
            text = text,
            evidenceRefs = evidenceRefs
        )
    }

    private fun validateTargetIdentities(candidates: List<CanonicalMemoryCandidate>) {
        candidates
            .mapNotNull { candidate -> candidate.targetMemoryId?.let { targetId -> targetId to candidate.identity() } }
            .groupBy({ pair -> pair.first }, { pair -> pair.second })
            .forEach { (_, identities) ->
                require(identities.distinct().size == 1) { "canonical target proposed for multiple identities" }
            }
    }

    private fun List<MarkdownMemoryEntry>.toExistingVariants(): List<FactVariant> =
        groupBy { entry -> normalizeExactMemoryText(entry.text) }
            .map { (normalizedText, entries) ->
                val representative = entries.sortedWith(existingEntryComparator).first()
                FactVariant(
                    normalizedText = normalizedText,
                    text = representative.text,
                    type = representative.type,
                    sensitivity = entries.maxBy { entry -> sensitivityRank(entry.sensitivity) }.sensitivity,
                    source = representative.source,
                    chatId = representative.chatId,
                    evidenceAt = entries.maxOf(MarkdownMemoryEntry::lastObservedAt),
                    recallState = representative.recallState,
                    origin = VariantOrigin.EXISTING,
                    tieId = entries.minOf(MarkdownMemoryEntry::id)
                )
            }

    private fun List<CanonicalMemoryCandidate>.toCandidateVariants(): List<FactVariant> =
        groupBy { candidate -> normalizeExactMemoryText(candidate.text) }
            .map { (normalizedText, candidates) ->
                val representative = candidates.sortedWith(candidateComparator).first()
                FactVariant(
                    normalizedText = normalizedText,
                    text = representative.text,
                    type = representative.type,
                    sensitivity = candidates.maxBy { candidate -> sensitivityRank(candidate.sensitivity) }.sensitivity,
                    source = candidates.maxBy { candidate -> sourceRank(candidate.source) }.source,
                    chatId = representative.chatId,
                    evidenceAt = candidates.maxOf(CanonicalMemoryCandidate::evidenceAt),
                    recallState = representative.recallState,
                    origin = VariantOrigin.CANDIDATE,
                    tieId = candidates.minOf(::candidateTieId)
                )
            }

    private fun mergeEvidenceRefs(existing: List<String>, additions: List<String>): List<String> {
        val merged = mutableListOf<String>()
        (existing + additions).distinct().sorted().forEach { reference ->
            val candidate = merged + reference
            if (runCatching { MarkdownMemoryMetadataPolicy.encodeEvidenceRefs(candidate) }.isSuccess) {
                merged += reference
            }
        }
        return merged
    }

    private fun MarkdownMemoryEntry.toHistory(
        activeId: String,
        identity: CanonicalMemoryIdentity,
        mutationAt: Long
    ): MarkdownMemoryEntry = copy(
        canonicalKey = identity.canonicalKey,
        scope = identity.scope,
        updatedAt = mutationAt,
        validity = MemoryValidity.OBSOLETE,
        supersededBy = activeId,
        recallState = MemoryRecallState.MAINTENANCE_ONLY
    )

    private fun validateRenderedDocument(
        baseMarkdown: String,
        renderedMarkdown: String,
        touchedIdentities: Set<CanonicalMemoryIdentity>
    ) {
        val parsed = parseEntriesOrThrow(renderedMarkdown)
        require(parsed.entries.map(MarkdownMemoryEntry::id).distinct().size == parsed.entries.size) {
            "duplicate canonical memory id"
        }
        touchedIdentities.forEach { identity ->
            require(
                parsed.entries.count { entry ->
                    entry.identityOrNull() == identity && entry.validity == MemoryValidity.CURRENT
                } <= 1
            ) { "duplicate current canonical identity" }
        }
        val entriesById = parsed.entries.associateBy(MarkdownMemoryEntry::id)
        parsed.entries
            .filter { entry ->
                entry.identityOrNull() in touchedIdentities && entry.validity == MemoryValidity.OBSOLETE
            }
            .forEach { obsolete ->
                val successor = requireNotNull(obsolete.supersededBy?.let(entriesById::get)) {
                    "canonical history is missing its active successor"
                }
                require(successor.validity == MemoryValidity.CURRENT && successor.identityOrNull() == obsolete.identityOrNull()) {
                    "canonical history successor mismatch"
                }
            }
        val originalCounts = exactTextCounts(baseMarkdown)
        exactTextCounts(renderedMarkdown).forEach { (normalizedText, renderedCount) ->
            require(renderedCount <= maxOf(originalCounts[normalizedText] ?: 0, 1)) {
                "duplicate_exact_memory_text"
            }
        }
    }

    private fun pruneExpandedHistoryAppends(
        originalEntries: List<MarkdownMemoryEntry>,
        replacements: Map<String, MarkdownMemoryEntry>,
        removals: Set<String>,
        appends: MutableMap<String, MarkdownMemoryEntry>
    ) {
        val originalCounts = originalEntries
            .groupingBy { entry -> normalizeExactMemoryText(entry.text) }
            .eachCount()
        val projectedCounts = buildList {
            originalEntries.forEach { entry ->
                if (entry.id !in removals) add(replacements[entry.id] ?: entry)
            }
            addAll(appends.values.filter { entry -> entry.validity != MemoryValidity.OBSOLETE })
        }.groupingBy { entry -> normalizeExactMemoryText(entry.text) }.eachCount().toMutableMap()

        appends.values
            .filter { entry -> entry.validity == MemoryValidity.OBSOLETE }
            .sortedBy(MarkdownMemoryEntry::id)
            .forEach { history ->
                val normalizedText = normalizeExactMemoryText(history.text)
                val allowedCount = maxOf(originalCounts[normalizedText] ?: 0, 1)
                val projectedCount = projectedCounts[normalizedText] ?: 0
                if (projectedCount >= allowedCount) {
                    appends.remove(history.id)
                } else {
                    projectedCounts[normalizedText] = projectedCount + 1
                }
            }
    }

    private fun parseEntriesOrThrow(markdown: String): MarkdownMemoryParseResult = markdownMemoryCodec
        .parse(markdown)
        .also { parsed -> require(parsed.skippedEntries.isEmpty()) { "unsafe_memory_metadata" } }

    private fun exactTextCounts(markdown: String): Map<String, Int> = parseEntriesOrThrow(markdown)
        .entries
        .groupingBy { entry -> normalizeExactMemoryText(entry.text) }
        .eachCount()

    private fun generatedActiveId(identity: CanonicalMemoryIdentity): String =
        "mem_can_${stableHash("canonical-active-v1", identity.canonicalKey, identity.scope).take(ID_HASH_LENGTH)}"

    private fun generatedHistoryId(
        identity: CanonicalMemoryIdentity,
        activeId: String,
        entry: MarkdownMemoryEntry
    ): String = "mem_hist_${
        stableHash(
            "canonical-history-v1",
            identity.canonicalKey,
            identity.scope,
            activeId,
            normalizeExactMemoryText(entry.text),
            entry.type,
            entry.source
        ).take(ID_HASH_LENGTH)
    }"

    private fun candidateTieId(candidate: CanonicalMemoryCandidate): String = stableHash(
        listOf(
            "canonical-candidate-v1",
            candidate.canonicalKey,
            candidate.scope,
            normalizeExactMemoryText(candidate.text),
            candidate.type,
            candidate.source,
            candidate.chatId?.toString().orEmpty(),
            candidate.evidenceAt.toString(),
            candidate.recallState
        ) + candidate.evidenceRefs.sorted()
    )

    private fun stableHash(vararg values: String): String = values
        .joinToString(separator = "") { value -> "${value.length}:$value" }
        .sha256Utf8()

    private fun stableHash(values: List<String>): String = values
        .joinToString(separator = "") { value -> "${value.length}:$value" }
        .sha256Utf8()

    private fun CanonicalMemoryCandidate.identity(): CanonicalMemoryIdentity =
        CanonicalMemoryIdentity(canonicalKey = canonicalKey, scope = scope)

    private fun MarkdownMemoryEntry.identityOrNull(): CanonicalMemoryIdentity? = canonicalKey?.let { key ->
        CanonicalMemoryIdentity(canonicalKey = key, scope = scope)
    }

    private fun sourceRank(value: String): Int = when (value) {
        MemorySource.ASSISTANT_INFERRED -> 0
        MemorySource.EXPLICIT_USER_STATEMENT -> 1
        MemorySource.USER_CONFIRMED -> 2
        else -> error("Unknown memory source")
    }

    private fun sensitivityRank(value: String): Int = when (value) {
        MemorySensitivity.NORMAL -> 0
        MemorySensitivity.PRIVATE -> 1
        MemorySensitivity.SENSITIVE -> 2
        else -> error("Unknown memory sensitivity")
    }

    private val existingEntryComparator =
        compareByDescending<MarkdownMemoryEntry> { entry -> sourceRank(entry.source) }
            .thenByDescending(MarkdownMemoryEntry::lastObservedAt)
            .thenBy(MarkdownMemoryEntry::id)

    private val candidateComparator =
        compareByDescending<CanonicalMemoryCandidate> { candidate -> sourceRank(candidate.source) }
            .thenByDescending(CanonicalMemoryCandidate::evidenceAt)
            .thenBy { candidate -> candidateTieId(candidate) }

    private val variantComparator =
        compareByDescending<FactVariant> { variant -> sourceRank(variant.source) }
            .thenByDescending(FactVariant::evidenceAt)
            .thenBy(FactVariant::origin)
            .thenBy(FactVariant::tieId)

    private data class CanonicalMemoryIdentity(
        val canonicalKey: String,
        val scope: String
    )

    private data class FactVariant(
        val normalizedText: String,
        val text: String,
        val type: String,
        val sensitivity: String,
        val source: String,
        val chatId: Int?,
        val evidenceAt: Long,
        val recallState: String,
        val origin: VariantOrigin,
        val tieId: String
    )

    private enum class VariantOrigin {
        EXISTING,
        CANDIDATE
    }

    private companion object {
        const val ID_HASH_LENGTH = 24
        val ACTIVE_RECALL_STATES = setOf(MemoryRecallState.CORE, MemoryRecallState.QUERY)
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
