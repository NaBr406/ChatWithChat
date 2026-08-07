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
    val materialEntryMutationCount: Int = 0,
    val requiresIndexSync: Boolean = false,
    val hasMoreMutations: Boolean = false
)

internal class CanonicalMemoryMergePolicy(
    private val markdownMemoryCodec: MarkdownMemoryCodec
) {
    fun merge(
        baseMarkdown: String,
        candidates: List<CanonicalMemoryCandidate>,
        mutationAt: Long,
        allowCanonicalRebinding: Boolean = false,
        promoteRecallState: Boolean = false,
        maxEntryMutations: Int? = null
    ): CanonicalMemoryMergeResult {
        require(mutationAt >= 0L) { "invalid canonical mutation time" }
        require(candidates.size <= MemoryControlledOperationPolicy.MAX_OPERATIONS) {
            "too many canonical candidates"
        }
        require(maxEntryMutations == null || maxEntryMutations in 1..MemoryControlledOperationPolicy.MAX_OPERATIONS) {
            "invalid canonical entry mutation limit"
        }
        val parsed = parseEntriesOrThrow(baseMarkdown)
        if (candidates.isEmpty()) return CanonicalMemoryMergeResult(markdown = baseMarkdown)

        val entriesById = parsed.entries.associateBy(MarkdownMemoryEntry::id)
        val normalizedCandidates = candidates.map { candidate ->
            normalizeAndValidate(candidate, entriesById, allowCanonicalRebinding)
        }
        validateTargetIdentities(normalizedCandidates)

        val replacements = linkedMapOf<String, MarkdownMemoryEntry>()
        val removals = linkedSetOf<String>()
        val appends = linkedMapOf<String, MarkdownMemoryEntry>()
        val observationUpdates = linkedMapOf<String, MarkdownMemoryObservationUpdate>()
        val reservedIds = entriesById.keys.toMutableSet()
        val explicitlyTargetedIds = normalizedCandidates.mapNotNull(CanonicalMemoryCandidate::targetMemoryId).toSet()
        val touchedIdentities = linkedSetOf<CanonicalMemoryIdentity>()
        val completedIdentities = linkedSetOf<CanonicalMemoryIdentity>()
        var acceptedCandidateCount = 0
        var materialMutationCount = 0
        var requiresIndexSync = false
        var hasMoreMutations = false
        var remainingEntryMutations = maxEntryMutations ?: Int.MAX_VALUE
        var stopPlanning = false

        normalizedCandidates
            .groupBy { candidate -> candidate.identity() }
            .toSortedMap(compareBy<CanonicalMemoryIdentity> { identity -> identity.canonicalKey }.thenBy { it.scope })
            .forEach { (identity, identityCandidates) ->
                if (stopPlanning) {
                    hasMoreMutations = true
                    return@forEach
                }
                val addressingIdentity = MemoryCanonicalIdentityPolicy.isAddressingIdentity(
                    canonicalKey = identity.canonicalKey,
                    scope = identity.scope
                )
                val targetIds = identityCandidates.mapNotNull(CanonicalMemoryCandidate::targetMemoryId).toSet()
                val candidateTexts = identityCandidates
                    .map { candidate -> normalizeExactMemoryText(candidate.text) }
                    .toSet()
                val identityEntries = parsed.entries.filter { entry ->
                    entry.matchesIdentity(identity) ||
                        entry.id in targetIds ||
                        (
                            addressingIdentity &&
                                entry.scope == identity.scope &&
                                MemoryCanonicalIdentityPolicy.isAddressingKey(entry.canonicalKey)
                            ) ||
                        (
                            entry.canonicalKey == null &&
                                entry.validity == MemoryValidity.CURRENT &&
                                entry.id !in explicitlyTargetedIds &&
                                normalizeExactMemoryText(entry.text) in candidateTexts
                            )
                }
                if (!allowCanonicalRebinding) {
                    require(
                        identityEntries.all { entry ->
                            entry.canonicalKey == null ||
                                entry.matchesIdentity(identity) ||
                                (entry.validity == MemoryValidity.OBSOLETE && entry.id in targetIds)
                        }
                    ) { "canonical target identity mismatch" }
                }
                val currentEntries = identityEntries
                    .filter { entry -> entry.validity == MemoryValidity.CURRENT }
                    .sortedBy(MarkdownMemoryEntry::id)
                val candidateTypes = identityCandidates
                    .map(CanonicalMemoryCandidate::type)
                    .distinct()
                require(candidateTypes.isNotEmpty()) { "canonical candidates have incompatible types" }
                val candidateType = candidateTypes.singleOrNull()
                    ?: identityCandidates.sortedWith(candidateComparator).first().type
                if (!addressingIdentity) {
                    require(candidateTypes.size == 1) { "canonical candidates have incompatible types" }
                    require(currentEntries.all { entry -> entry.type == candidateType }) {
                        "canonical candidate type mismatch"
                    }
                }
                val reactivatableObsoleteEntries = if (currentEntries.isEmpty()) {
                    identityCandidates
                        .mapNotNull { candidate -> candidate.targetMemoryId?.let(entriesById::get) }
                        .filter { entry ->
                            entry.validity == MemoryValidity.OBSOLETE &&
                                currentSuccessor(entry, entriesById)?.matchesIdentity(identity) != true
                        }
                        .distinctBy(MarkdownMemoryEntry::id)
                        .sortedBy(MarkdownMemoryEntry::id)
                } else {
                    emptyList()
                }
                val activeEntries = (currentEntries + reactivatableObsoleteEntries)
                    .distinctBy(MarkdownMemoryEntry::id)
                    .sortedBy(MarkdownMemoryEntry::id)
                val existingVariants = (currentEntries + reactivatableObsoleteEntries.filter { entry ->
                    addressingIdentity || entry.type == candidateType
                }).toExistingVariants()
                val promotedRecallState = if (
                    promoteRecallState &&
                    (activeEntries.any { entry -> entry.recallState == MemoryRecallState.CORE } ||
                        identityCandidates.any { candidate -> candidate.recallState == MemoryRecallState.CORE })
                ) {
                    MemoryRecallState.CORE
                } else {
                    null
                }

                val candidateVariants = identityCandidates.toCandidateVariants()
                val winningVariant = (existingVariants + candidateVariants).sortedWith(variantComparator).first()
                val targetedAddressingHistory = if (addressingIdentity) {
                    identityEntries.filter { entry ->
                        entry.validity == MemoryValidity.OBSOLETE && entry.id in targetIds
                    }
                } else {
                    emptyList()
                }
                val composedAddressingText = if (addressingIdentity) {
                    composeAddressingText(
                        existingTexts = (activeEntries + targetedAddressingHistory)
                            .map(MarkdownMemoryEntry::text),
                        candidateTexts = identityCandidates.map(CanonicalMemoryCandidate::text),
                        preferredText = winningVariant.text
                    )
                } else {
                    winningVariant.text
                }
                val composedAddressing = addressingIdentity &&
                    normalizeExactMemoryText(composedAddressingText) != winningVariant.normalizedText
                val selectedText = composedAddressingText
                require(selectedText.length <= MemoryControlledOperationPolicy.MAX_MEMORY_TEXT_CHARS) {
                    "combined addressing memory text is too large"
                }
                val selectedNormalizedText = normalizeExactMemoryText(selectedText)
                val acceptedCandidates = if (composedAddressing) {
                    identityCandidates
                } else {
                    identityCandidates.filter { candidate ->
                        normalizeExactMemoryText(candidate.text) == winningVariant.normalizedText
                    }
                }
                acceptedCandidateCount += acceptedCandidates.size

                val survivor = activeEntries.firstOrNull()
                val survivorWasReactivated = survivor?.validity == MemoryValidity.OBSOLETE
                val identityReplacements = linkedMapOf<String, MarkdownMemoryEntry>()
                val identityRemovals = linkedSetOf<String>()
                val identityAppends = linkedMapOf<String, MarkdownMemoryEntry>()
                val identityObservationUpdates = linkedMapOf<String, MarkdownMemoryObservationUpdate>()
                val activeId: String
                val losingCurrentIds: Set<String>
                var identityMaterialMutationCount = 0
                var identityRequiresIndexSync = false
                if (
                    survivor != null &&
                    !survivorWasReactivated &&
                    activeEntries.size == 1 &&
                    selectedNormalizedText == normalizeExactMemoryText(survivor.text)
                ) {
                    activeId = survivor.id
                    losingCurrentIds = emptySet()
                    val evidenceRefs = mergeEvidenceRefs(
                        existing = survivor.evidenceRefs,
                        additions = acceptedCandidates.flatMap(CanonicalMemoryCandidate::evidenceRefs)
                    )
                    val lastObservedAt = maxOf(
                        survivor.lastObservedAt,
                        acceptedCandidates.maxOfOrNull(CanonicalMemoryCandidate::evidenceAt) ?: 0L
                    )
                    val recallState = promotedRecallState ?: survivor.recallState
                    if (survivor.identityOrNull() != identity || survivor.recallState != recallState) {
                        identityReplacements[survivor.id] = survivor.copy(
                            canonicalKey = identity.canonicalKey,
                            scope = identity.scope,
                            updatedAt = mutationAt,
                            lastObservedAt = lastObservedAt,
                            recallState = recallState,
                            evidenceRefs = evidenceRefs
                        )
                        identityMaterialMutationCount = 1
                    } else if (lastObservedAt > survivor.lastObservedAt || evidenceRefs != survivor.evidenceRefs) {
                        identityObservationUpdates[survivor.id] = MarkdownMemoryObservationUpdate(
                            entryId = survivor.id,
                            lastObservedAt = lastObservedAt,
                            evidenceRefs = evidenceRefs
                        )
                    }
                } else {
                    activeId = survivor?.id ?: generatedActiveId(identity)
                    if (survivor == null) {
                        require(reservedIds.add(activeId)) { "generated canonical active id conflict" }
                    }
                    val winningExistingEntries = activeEntries.filter { entry ->
                        normalizeExactMemoryText(entry.text) == selectedNormalizedText
                    }
                    val winningText = winningExistingEntries.firstOrNull()?.text ?: selectedText
                    val evidenceRefs = mergeEvidenceRefs(
                        existing = if (addressingIdentity) {
                            (activeEntries + targetedAddressingHistory)
                                .flatMap(MarkdownMemoryEntry::evidenceRefs)
                        } else {
                            winningExistingEntries.flatMap(MarkdownMemoryEntry::evidenceRefs)
                        },
                        additions = acceptedCandidates.flatMap(CanonicalMemoryCandidate::evidenceRefs)
                    )
                    val lastObservedAt = maxOf(
                        if (addressingIdentity) {
                            (activeEntries + targetedAddressingHistory)
                                .maxOfOrNull(MarkdownMemoryEntry::lastObservedAt) ?: 0L
                        } else {
                            winningVariant.evidenceAt
                        },
                        acceptedCandidates.maxOfOrNull(CanonicalMemoryCandidate::evidenceAt) ?: 0L
                    )
                    val activeBase = survivor ?: MarkdownMemoryEntry(
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
                    val activeAtExistingTimestamp = activeBase.copy(
                        id = activeId,
                        text = winningText,
                        type = winningVariant.type,
                        sensitivity = winningVariant.sensitivity,
                        source = winningVariant.source,
                        updatedAt = survivor?.updatedAt ?: mutationAt,
                        canonicalKey = identity.canonicalKey,
                        scope = identity.scope,
                        lastObservedAt = lastObservedAt,
                        validity = MemoryValidity.CURRENT,
                        supersededBy = null,
                        recallState = promotedRecallState ?: winningVariant.recallState,
                        evidenceRefs = evidenceRefs
                    )
                    val materialActiveChanged = survivor == null ||
                        activeAtExistingTimestamp.copy(
                            lastObservedAt = survivor.lastObservedAt,
                            evidenceRefs = survivor.evidenceRefs
                        ) != survivor
                    val survivorAlreadyStable = survivor != null &&
                        activeEntries.drop(1).all { loser -> survivor.updatedAt > loser.updatedAt }
                    val shouldRefreshStableTimestamp = activeEntries.size > 1 && !survivorAlreadyStable
                    val shouldReplaceActive = materialActiveChanged || shouldRefreshStableTimestamp
                    val activeEntry = activeAtExistingTimestamp.copy(
                        updatedAt = if (shouldReplaceActive) mutationAt else activeAtExistingTimestamp.updatedAt
                    )

                    if (survivor == null) {
                        identityAppends[activeId] = activeEntry
                    } else if (shouldReplaceActive) {
                        identityReplacements[activeId] = activeEntry
                    } else if (
                        activeEntry.lastObservedAt != survivor.lastObservedAt ||
                        activeEntry.evidenceRefs != survivor.evidenceRefs
                    ) {
                        identityObservationUpdates[activeId] = MarkdownMemoryObservationUpdate(
                            entryId = activeId,
                            lastObservedAt = activeEntry.lastObservedAt,
                            evidenceRefs = activeEntry.evidenceRefs
                        )
                    }

                    losingCurrentIds = activeEntries.drop(1).map(MarkdownMemoryEntry::id).toSet()
                    activeEntries.drop(1).forEach { loser ->
                        if (normalizeExactMemoryText(loser.text) == selectedNormalizedText) {
                            identityRemovals += loser.id
                        } else {
                            identityReplacements[loser.id] = loser.toHistory(activeId, identity, mutationAt)
                        }
                    }
                    parsed.entries
                        .filter { entry ->
                            entry.validity == MemoryValidity.OBSOLETE && entry.supersededBy in losingCurrentIds
                        }
                        .forEach { obsolete ->
                            identityReplacements[obsolete.id] = obsolete.copy(supersededBy = activeId)
                        }

                    survivor?.takeIf { entry ->
                        normalizeExactMemoryText(entry.text) != selectedNormalizedText
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
                                    existingHistory.matchesIdentity(identity) &&
                                        normalizeExactMemoryText(existingHistory.text) == oldNormalizedText &&
                                        existingHistory.validity == MemoryValidity.OBSOLETE &&
                                        existingHistory.supersededBy == activeId
                                ) { "generated canonical history id conflict" }
                            } ?: run {
                                require(reservedIds.add(historyId)) { "generated canonical history id conflict" }
                                identityAppends[historyId] = replacedFact.copy(id = historyId).toHistory(
                                    activeId = activeId,
                                    identity = identity,
                                    mutationAt = mutationAt
                                )
                            }
                        }
                    }
                    identityMaterialMutationCount = 1
                    identityRequiresIndexSync = true
                }

                if (!survivorWasReactivated) {
                    identityEntries
                        .filter { entry ->
                            entry.validity == MemoryValidity.OBSOLETE &&
                                !entry.matchesIdentity(identity) &&
                                currentSuccessor(entry, entriesById)?.matchesIdentity(identity) == true
                        }
                        .forEach { obsolete ->
                            identityReplacements[obsolete.id] = obsolete.copy(
                                canonicalKey = identity.canonicalKey,
                                scope = identity.scope
                            )
                        }
                }

                if (identityReplacements.isNotEmpty() && identityMaterialMutationCount == 0) {
                    identityMaterialMutationCount = 1
                    identityRequiresIndexSync = true
                }

                pruneExpandedHistoryAppends(
                    originalEntries = parsed.entries,
                    replacements = identityReplacements,
                    removals = identityRemovals,
                    appends = identityAppends
                )
                val selection = selectBoundedIdentityMutations(
                    plan = CanonicalIdentityMutationPlan(
                        activeId = activeId,
                        losingCurrentIds = losingCurrentIds,
                        replacements = identityReplacements,
                        removals = identityRemovals,
                        appends = identityAppends,
                        observationUpdates = identityObservationUpdates
                    ),
                    originalEntries = parsed.entries,
                    originalEntriesById = entriesById,
                    limit = remainingEntryMutations
                )
                replacements.putAll(selection.replacements)
                removals.addAll(selection.removals)
                appends.putAll(selection.appends)
                observationUpdates.putAll(selection.observationUpdates)
                remainingEntryMutations -= selection.changedEntryCount
                if (selection.changedEntryCount > 0) touchedIdentities += identity
                if (selection.isComplete) {
                    completedIdentities += identity
                } else {
                    hasMoreMutations = true
                    stopPlanning = true
                }
                if (selection.materialEntryMutationCount > 0) {
                    materialMutationCount += identityMaterialMutationCount
                    requiresIndexSync = requiresIndexSync || identityRequiresIndexSync
                }
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
            markdown = markdownMemoryCodec.appendLongTermEntries(
                markdown,
                appends.values.sortedBy { it.id }
            )
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
            touchedIdentities = touchedIdentities,
            completedIdentities = completedIdentities
        )
        val changedEntryCount = replacements.size + removals.size + appends.size + observationUpdates.size
        require(maxEntryMutations == null || changedEntryCount <= maxEntryMutations) {
            "canonical entry mutation limit exceeded"
        }
        return CanonicalMemoryMergeResult(
            markdown = markdown,
            acceptedCandidateCount = acceptedCandidateCount,
            changedEntryCount = changedEntryCount,
            materialMutationCount = materialMutationCount,
            materialEntryMutationCount = replacements.size + removals.size + appends.size,
            requiresIndexSync = requiresIndexSync,
            hasMoreMutations = hasMoreMutations
        )
    }

    private fun normalizeAndValidate(
        candidate: CanonicalMemoryCandidate,
        entriesById: Map<String, MarkdownMemoryEntry>,
        allowCanonicalRebinding: Boolean
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
        require(MarkdownMemoryMetadataPolicy.isScope(candidate.scope)) { "invalid canonical memory scope" }
        val canonicalKey = MemoryCanonicalIdentityPolicy.normalizeCanonicalKey(
            canonicalKey = candidate.canonicalKey,
            scope = candidate.scope
        )
        require(MarkdownMemoryMetadataPolicy.isCanonicalKey(canonicalKey)) { "invalid canonical memory key" }
        require(candidate.evidenceAt >= 0L) { "invalid canonical evidence time" }
        require(candidate.recallState in ACTIVE_RECALL_STATES) { "invalid canonical recall state" }
        val evidenceRefs = candidate.evidenceRefs.distinct().sorted()
        MarkdownMemoryMetadataPolicy.encodeEvidenceRefs(evidenceRefs)
        candidate.targetMemoryId?.let { targetId ->
            require(MarkdownMemoryMetadataPolicy.isSafeReference(targetId)) { "invalid canonical target id" }
            val target = requireNotNull(entriesById[targetId]) { "unknown canonical target" }
            require(target.validity == MemoryValidity.OBSOLETE || target.type == candidate.type) {
                "canonical target type mismatch"
            }
            if (target.validity != MemoryValidity.OBSOLETE) {
                if (!allowCanonicalRebinding) {
                    require(
                        target.canonicalKey == null ||
                            MemoryCanonicalIdentityPolicy.allowsRebinding(
                                fromKey = target.canonicalKey,
                                fromScope = target.scope,
                                toKey = canonicalKey,
                                toScope = candidate.scope
                            )
                    ) { "canonical target key mismatch" }
                    require(target.canonicalKey == null || target.scope == candidate.scope) {
                        "canonical target scope mismatch"
                    }
                } else {
                    require(
                        MemoryCanonicalIdentityPolicy.allowsRebinding(
                            fromKey = target.canonicalKey,
                            fromScope = target.scope,
                            toKey = canonicalKey,
                            toScope = candidate.scope
                        )
                    ) { "canonical target identity rebinding is not allowed" }
                }
            }
        }
        return candidate.copy(
            text = text,
            canonicalKey = canonicalKey,
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

    private fun selectBoundedIdentityMutations(
        plan: CanonicalIdentityMutationPlan,
        originalEntries: List<MarkdownMemoryEntry>,
        originalEntriesById: Map<String, MarkdownMemoryEntry>,
        limit: Int
    ): CanonicalIdentityMutationSelection {
        require(limit >= 0) { "invalid remaining canonical mutation budget" }
        val effectiveReplacements = linkedMapOf<String, MarkdownMemoryEntry>()
        plan.replacements.forEach { (id, replacement) ->
            if (originalEntriesById[id] != replacement) effectiveReplacements[id] = replacement
        }
        val effectiveRemovals = plan.removals.filterTo(linkedSetOf()) { id -> id in originalEntriesById }
        val effectiveAppends = linkedMapOf<String, MarkdownMemoryEntry>()
        plan.appends.forEach { (id, append) ->
            if (id !in originalEntriesById) effectiveAppends[id] = append
        }
        val effectiveObservationUpdates = linkedMapOf<String, MarkdownMemoryObservationUpdate>()
        plan.observationUpdates.forEach { (id, update) ->
            val original = checkNotNull(originalEntriesById[id])
            if (original.lastObservedAt != update.lastObservedAt || original.evidenceRefs != update.evidenceRefs) {
                effectiveObservationUpdates[id] = update
            }
        }
        val fullSelection = CanonicalIdentityMutationSelection(
            replacements = effectiveReplacements,
            removals = effectiveRemovals,
            appends = effectiveAppends,
            observationUpdates = effectiveObservationUpdates,
            isComplete = true
        )
        if (fullSelection.changedEntryCount <= limit) return fullSelection

        val selectedReplacements = linkedMapOf<String, MarkdownMemoryEntry>()
        val selectedRemovals = linkedSetOf<String>()
        val selectedAppends = linkedMapOf<String, MarkdownMemoryEntry>()
        val selectedObservationUpdates = linkedMapOf<String, MarkdownMemoryObservationUpdate>()
        var remaining = limit

        fun selection(isComplete: Boolean) = CanonicalIdentityMutationSelection(
            replacements = selectedReplacements,
            removals = selectedRemovals,
            appends = selectedAppends,
            observationUpdates = selectedObservationUpdates,
            isComplete = isComplete
        )

        val activeReplacement = effectiveReplacements[plan.activeId]
        val activeAppend = effectiveAppends[plan.activeId]
        val activeObservationUpdate = effectiveObservationUpdates[plan.activeId]
        val historyAppends = effectiveAppends
            .filterKeys { id -> id != plan.activeId }
            .toSortedMap()
        val activeBundleSize =
            listOfNotNull(activeReplacement, activeAppend, activeObservationUpdate).size + historyAppends.size
        if (activeBundleSize > remaining) return selection(isComplete = false)
        activeReplacement?.let { replacement -> selectedReplacements[plan.activeId] = replacement }
        activeAppend?.let { append -> selectedAppends[plan.activeId] = append }
        activeObservationUpdate?.let { update -> selectedObservationUpdates[plan.activeId] = update }
        selectedAppends.putAll(historyAppends)
        remaining -= activeBundleSize

        plan.losingCurrentIds.sorted().forEach { loserId ->
            val dependentIds = originalEntries
                .asSequence()
                .filter { entry ->
                    entry.validity == MemoryValidity.OBSOLETE && entry.supersededBy == loserId
                }
                .map(MarkdownMemoryEntry::id)
                .filter(effectiveReplacements::containsKey)
                .sorted()
                .toList()
            dependentIds.forEach { dependentId ->
                if (remaining == 0) return selection(isComplete = false)
                selectedReplacements[dependentId] = checkNotNull(effectiveReplacements[dependentId])
                remaining -= 1
            }

            val loserReplacement = effectiveReplacements[loserId]
            val removeLoser = loserId in effectiveRemovals
            check(loserReplacement == null || !removeLoser) { "canonical loser has conflicting mutations" }
            if (loserReplacement != null || removeLoser) {
                if (remaining == 0) return selection(isComplete = false)
                if (loserReplacement != null) {
                    selectedReplacements[loserId] = loserReplacement
                } else {
                    selectedRemovals += loserId
                }
                remaining -= 1
            }
        }

        effectiveObservationUpdates.toSortedMap().forEach { (id, update) ->
            if (id in selectedObservationUpdates) return@forEach
            if (remaining == 0) return selection(isComplete = false)
            selectedObservationUpdates[id] = update
            remaining -= 1
        }
        check(selectedReplacements.keys == effectiveReplacements.keys) { "unclassified canonical replacement" }
        check(selectedRemovals == effectiveRemovals) { "unclassified canonical removal" }
        check(selectedAppends.keys == effectiveAppends.keys) { "unclassified canonical append" }
        check(selectedObservationUpdates.keys == effectiveObservationUpdates.keys) {
            "unclassified canonical observation update"
        }
        return selection(isComplete = true)
    }

    private fun validateRenderedDocument(
        baseMarkdown: String,
        renderedMarkdown: String,
        touchedIdentities: Set<CanonicalMemoryIdentity>,
        completedIdentities: Set<CanonicalMemoryIdentity>
    ) {
        val parsed = parseEntriesOrThrow(renderedMarkdown)
        require(parsed.entries.map(MarkdownMemoryEntry::id).distinct().size == parsed.entries.size) {
            "duplicate canonical memory id"
        }
        completedIdentities.forEach { identity ->
            require(
                parsed.entries.count { entry ->
                    entry.matchesIdentity(identity) && entry.validity == MemoryValidity.CURRENT
                } <= 1
            ) { "duplicate current canonical identity" }
        }
        val entriesById = parsed.entries.associateBy(MarkdownMemoryEntry::id)
        parsed.entries
            .filter { entry ->
                touchedIdentities.any { identity -> entry.matchesIdentity(identity) } &&
                    entry.validity == MemoryValidity.OBSOLETE
            }
            .forEach { obsolete ->
                val successor = obsolete.supersededBy?.let(entriesById::get)
                if (successor != null) {
                    require(successor.validity == MemoryValidity.CURRENT && successor.hasSameCanonicalIdentity(obsolete)) {
                        "canonical history successor mismatch"
                    }
                } else {
                    require(obsolete.recallState == MemoryRecallState.MAINTENANCE_ONLY) {
                        "retired canonical entry must be maintenance only"
                    }
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

    private fun MarkdownMemoryEntry.matchesIdentity(identity: CanonicalMemoryIdentity): Boolean {
        val key = canonicalKey ?: return false
        return scope == identity.scope &&
            MemoryCanonicalIdentityPolicy.normalizeCanonicalKey(key, scope) == identity.canonicalKey
    }

    private fun MarkdownMemoryEntry.hasSameCanonicalIdentity(other: MarkdownMemoryEntry): Boolean {
        val leftKey = canonicalKey ?: return other.canonicalKey == null && scope == other.scope
        val rightKey = other.canonicalKey ?: return false
        return scope == other.scope &&
            MemoryCanonicalIdentityPolicy.normalizeCanonicalKey(leftKey, scope) ==
            MemoryCanonicalIdentityPolicy.normalizeCanonicalKey(rightKey, other.scope)
    }

    private fun MarkdownMemoryEntry.identityOrNull(): CanonicalMemoryIdentity? = canonicalKey?.let { key ->
        CanonicalMemoryIdentity(canonicalKey = key, scope = scope)
    }

    private fun composeAddressingText(
        existingTexts: List<String>,
        candidateTexts: List<String>,
        preferredText: String
    ): String {
        val normalizedExisting = existingTexts
            .map(::normalizeExactMemoryText)
            .distinct()
        val normalizedCandidates = candidateTexts
            .map(::normalizeExactMemoryText)
            .distinct()

        if (normalizedExisting.isEmpty() && normalizedCandidates.isEmpty()) return preferredText
        val candidateCoveringExisting = candidateTexts
            .distinctBy(::normalizeExactMemoryText)
            .sortedWith(compareByDescending<String>(String::length).thenBy(::normalizeExactMemoryText))
            .firstOrNull { candidate ->
                val normalizedCandidate = normalizeExactMemoryText(candidate)
                normalizedExisting.all { existing ->
                    existing == normalizedCandidate || normalizedCandidate.contains(existing)
                } && normalizedCandidates.all { known ->
                    known == normalizedCandidate || normalizedCandidate.contains(known)
                }
            }
        if (candidateCoveringExisting != null) return candidateCoveringExisting.trim()

        val existingContainingAllCandidates = existingTexts
            .distinctBy(::normalizeExactMemoryText)
            .sortedWith(compareByDescending<String>(String::length).thenBy(::normalizeExactMemoryText))
            .firstOrNull { existing ->
                val normalizedExistingText = normalizeExactMemoryText(existing)
                normalizedExisting.all { known ->
                    known == normalizedExistingText || normalizedExistingText.contains(known)
                } && normalizedCandidates.all { candidate ->
                    candidate == normalizedExistingText || normalizedExistingText.contains(candidate)
                }
            }
        if (existingContainingAllCandidates != null) return existingContainingAllCandidates.trim()

        return (existingTexts + candidateTexts)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeExactMemoryText)
            .sortedBy(::normalizeExactMemoryText)
            .joinToString(separator = " ; ")
            .ifBlank { preferredText }
    }

    private fun currentSuccessor(
        entry: MarkdownMemoryEntry,
        entriesById: Map<String, MarkdownMemoryEntry>
    ): MarkdownMemoryEntry? {
        val visited = mutableSetOf<String>()
        var current = entry
        while (current.validity == MemoryValidity.OBSOLETE) {
            val successorId = current.supersededBy ?: return null
            if (!visited.add(current.id)) return null
            current = entriesById[successorId] ?: return null
        }
        return current.takeIf { candidate -> candidate.validity == MemoryValidity.CURRENT }
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

    private data class CanonicalIdentityMutationPlan(
        val activeId: String,
        val losingCurrentIds: Set<String>,
        val replacements: Map<String, MarkdownMemoryEntry>,
        val removals: Set<String>,
        val appends: Map<String, MarkdownMemoryEntry>,
        val observationUpdates: Map<String, MarkdownMemoryObservationUpdate>
    )

    private data class CanonicalIdentityMutationSelection(
        val replacements: Map<String, MarkdownMemoryEntry>,
        val removals: Set<String>,
        val appends: Map<String, MarkdownMemoryEntry>,
        val observationUpdates: Map<String, MarkdownMemoryObservationUpdate>,
        val isComplete: Boolean
    ) {
        val changedEntryCount: Int
            get() = replacements.size + removals.size + appends.size + observationUpdates.size

        val materialEntryMutationCount: Int
            get() = replacements.size + removals.size + appends.size
    }

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
