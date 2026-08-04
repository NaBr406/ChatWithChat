package cn.nabr.chatwithchat.data.memory

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class MemoryLongTermPartition(
    val start: Int,
    val endExclusive: Int,
    val entries: List<MarkdownMemoryEntry>
)

internal data class MemoryLongTermBoundedPartitionRequest(
    val partition: MemoryLongTermPartition,
    val request: MemoryLongTermConsolidationPartitionRequest,
    val serializedRequest: String
)

internal class MemoryLongTermConsolidationPolicy {
    private val requestJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
    }

    fun nextPartition(
        orderedEntries: List<MarkdownMemoryEntry>,
        cursor: Int
    ): MemoryLongTermPartition {
        require(cursor in 0..orderedEntries.size) { "invalid long-term consolidation cursor" }
        if (cursor == orderedEntries.size) {
            return MemoryLongTermPartition(cursor, cursor, emptyList())
        }
        val selected = mutableListOf<MarkdownMemoryEntry>()
        var charCount = 0
        var index = cursor
        while (index < orderedEntries.size && selected.size < MAX_PARTITION_ENTRIES) {
            val entry = orderedEntries[index]
            val entryChars = entry.text.length + ENTRY_OVERHEAD_CHARS
            if (selected.isNotEmpty() && charCount + entryChars > MAX_PARTITION_CHARS) break
            selected += entry
            charCount += entryChars
            index += 1
        }
        return MemoryLongTermPartition(cursor, index, selected)
    }

    fun nextBoundedRequest(
        checkpointId: String,
        orderedEntries: List<MarkdownMemoryEntry>,
        cursor: Int,
        alreadyAssignedIds: Set<String>,
        forceReview: Boolean = false
    ): MemoryLongTermBoundedPartitionRequest {
        var partition = nextPartition(orderedEntries, cursor)
        while (true) {
            val groups = candidateGroups(
                allEntries = orderedEntries,
                partition = partition,
                alreadyAssignedIds = alreadyAssignedIds,
                forceReview = forceReview
            )
            val request = partition.toRequest(checkpointId, groups)
            val serialized = requestJson.encodeToString(request)
            if (serialized.length <= MAX_SERIALIZED_REQUEST_CHARS) {
                return MemoryLongTermBoundedPartitionRequest(partition, request, serialized)
            }
            if (partition.entries.size > 1) {
                val entries = partition.entries.dropLast(1)
                partition = partition.copy(
                    endExclusive = partition.start + entries.size,
                    entries = entries
                )
                continue
            }

            val fittedGroups = fitSingleAnchorGroups(
                checkpointId = checkpointId,
                partition = partition,
                groups = groups
            )
            val fittedRequest = partition.toRequest(checkpointId, fittedGroups)
            val fittedSerialized = requestJson.encodeToString(fittedRequest)
            check(fittedSerialized.length <= MAX_SERIALIZED_REQUEST_CHARS) {
                "unable to bound long-term consolidation request"
            }
            return MemoryLongTermBoundedPartitionRequest(partition, fittedRequest, fittedSerialized)
        }
    }

    fun candidateGroups(
        allEntries: List<MarkdownMemoryEntry>,
        partition: MemoryLongTermPartition,
        alreadyAssignedIds: Set<String>,
        forceReview: Boolean = false
    ): List<MemoryLongTermCandidateGroup> {
        val eligibleAll = allEntries.filter { entry -> entry.isEligibleForCanonicalConsolidation() }
        val consumed = alreadyAssignedIds.toMutableSet()
        val partitionIds = partition.entries.map(MarkdownMemoryEntry::id).toSet()
        val futureIds = allEntries.drop(partition.endExclusive).mapTo(mutableSetOf(), MarkdownMemoryEntry::id)
        val groupedEntries = buildList {
            partition.entries.forEach { anchor ->
                if (
                    !anchor.isEligibleForCanonicalConsolidation() ||
                    !anchor.requiresSemanticReview(eligibleAll, consumed, forceReview) ||
                    !consumed.add(anchor.id)
                ) {
                    return@forEach
                }
                val related = partition.entries
                    .asSequence()
                    .filter { candidate ->
                        candidate.id !in consumed &&
                            candidate.isEligibleForCanonicalConsolidation() &&
                            candidate.type == anchor.type &&
                            candidate.scope == anchor.scope &&
                            anchor.canShareSemanticGroupWith(candidate)
                    }
                    .sortedWith(
                        compareByDescending<MarkdownMemoryEntry> { candidate -> groupingAffinity(anchor, candidate) }
                            .thenByDescending { candidate ->
                                localSimilarity(anchor.text, candidate.text)
                            }
                            .thenBy(MarkdownMemoryEntry::id)
                    )
                    .take(MAX_GROUP_ENTRIES - 1)
                    .toList()
                val grouped = (listOf(anchor) + related)
                    .takeByCharacterBudget(MAX_GROUP_CHARS)
                consumed += grouped.map(MarkdownMemoryEntry::id)
                add(grouped)
            }
        }
        require(groupedEntries.size <= MAX_GROUPS_PER_PARTITION) {
            "too many long-term candidate groups in one partition"
        }
        var requestChars = groupedEntries.flatten().sumOf { entry -> entry.requestCharacterCount() }
        val enrichedGroups = groupedEntries.map { initialGroup ->
            val group = initialGroup.toMutableList()
            var groupChars = group.sumOf { entry -> entry.requestCharacterCount() }
            val anchor = group.first()
            eligibleAll
                .asSequence()
                .filter { candidate ->
                    candidate.id !in consumed &&
                        candidate.id in futureIds &&
                        candidate.type == anchor.type &&
                        candidate.scope == anchor.scope &&
                        anchor.canShareSemanticGroupWith(candidate)
                }
                .sortedWith(
                    compareByDescending<MarkdownMemoryEntry> { candidate -> groupingAffinity(anchor, candidate) }
                        .thenByDescending { candidate ->
                            localSimilarity(anchor.text, candidate.text)
                        }
                        .thenBy(MarkdownMemoryEntry::id)
                )
                .forEach { candidate ->
                    val candidateChars = candidate.requestCharacterCount()
                    if (
                        group.size < MAX_GROUP_ENTRIES &&
                        groupChars + candidateChars <= MAX_GROUP_CHARS &&
                        requestChars + candidateChars <= MAX_PARTITION_CHARS
                    ) {
                        group += candidate
                        consumed += candidate.id
                        groupChars += candidateChars
                        requestChars += candidateChars
                    }
                }
            group
        }
        require(requestChars <= MAX_PARTITION_CHARS) {
            "long-term candidate request exceeded its character budget"
        }
        return enrichedGroups.map { grouped ->
            val groupIds = grouped.map(MarkdownMemoryEntry::id).sorted()
            MemoryLongTermCandidateGroup(
                groupId = stableGroupId(groupIds),
                anchorMemoryIds = grouped.map(MarkdownMemoryEntry::id).filter(partitionIds::contains),
                entries = grouped.map { entry -> entry.toCandidateEntry() }
            )
        }
    }

    fun validateAndMergeProposal(
        existing: MemoryLongTermPersistedProposal,
        partitionRequest: MemoryLongTermConsolidationPartitionRequest,
        proposal: MemoryLongTermConsolidationProposal
    ): MemoryLongTermPersistedProposal {
        require(proposal.decisions.size <= MAX_DECISIONS_PER_PARTITION) {
            "too many long-term consolidation decisions"
        }
        val groupsById = partitionRequest.candidateGroups.associateBy(MemoryLongTermCandidateGroup::groupId)
        require(groupsById.size == partitionRequest.candidateGroups.size) { "duplicate candidate group id" }
        val groupByMemoryId = mutableMapOf<String, MemoryLongTermCandidateGroup>()
        partitionRequest.candidateGroups.forEach { group ->
            require(group.anchorMemoryIds.isNotEmpty()) { "candidate group has no anchor" }
            val entryIds = group.entries.map(MemoryLongTermCandidateEntry::memoryId)
            require(entryIds.distinct().size == entryIds.size) { "candidate group has duplicate memory ids" }
            require(group.anchorMemoryIds.all(entryIds::contains)) { "candidate group anchor is missing" }
            entryIds.forEach { memoryId ->
                require(groupByMemoryId.put(memoryId, group) == null) {
                    "memory id belongs to multiple candidate groups"
                }
            }
        }
        val previouslyAssigned = existing.decisions
            .filter { decision -> decision.action in ASSIGNING_ACTIONS }
            .flatMap(MemoryLongTermCanonicalDecision::memoryIds)
            .toMutableSet()
        val normalized = proposal.decisions.map { decision ->
            require(decision.action in VALID_ACTIONS) { "invalid long-term consolidation action" }
            if (decision.action == MemoryLongTermDecisionAction.IGNORE) {
                require(decision.memoryIds.isEmpty()) { "ignore decision must not reference memories" }
                require(decision.canonicalKey == null && decision.scope == null && decision.recallState == null) {
                    "ignore decision must not carry canonical metadata"
                }
                return@map decision.copy(reason = decision.reason.take(MAX_REASON_CHARS))
            }
            val memoryIds = decision.memoryIds.distinct().sorted()
            require(memoryIds.isNotEmpty() && memoryIds.size <= MAX_GROUP_ENTRIES) {
                "invalid long-term consolidation memory group"
            }
            val candidateGroup = memoryIds
                .map { memoryId -> requireNotNull(groupByMemoryId[memoryId]) { "decision invented a memory id" } }
                .distinctBy(MemoryLongTermCandidateGroup::groupId)
                .singleOrNull()
                ?: error("decision crossed candidate group boundary")
            require(memoryIds.any(candidateGroup.anchorMemoryIds::contains)) {
                "decision does not include its candidate group anchor"
            }
            require(memoryIds.none(previouslyAssigned::contains)) { "memory id was assigned twice" }
            val allowedEntries = candidateGroup.entries.associateBy(MemoryLongTermCandidateEntry::memoryId)
            val entries = memoryIds.map { memoryId -> checkNotNull(allowedEntries[memoryId]) }
            require(entries.map(MemoryLongTermCandidateEntry::type).distinct().size == 1) {
                "decision mixed incompatible memory types"
            }
            if (decision.action == MemoryLongTermDecisionAction.RETIRE) {
                val canonicalKey = decision.canonicalKey ?: entries.mapNotNull(MemoryLongTermCandidateEntry::canonicalKey)
                    .distinct()
                    .singleOrNull()
                    ?: "maintenance.retired.${memoryIds.joinToString("_").sha256Utf8().take(16)}"
                val scope = decision.scope ?: entries.map(MemoryLongTermCandidateEntry::scope).distinct().single()
                require(MarkdownMemoryMetadataPolicy.isCanonicalKey(canonicalKey)) {
                    "invalid retired memory canonical key"
                }
                require(MarkdownMemoryMetadataPolicy.isScope(scope)) { "invalid retired memory scope" }
                require(entries.all { entry -> entry.canonicalKey == null || entry.canonicalKey == canonicalKey }) {
                    "retirement changed canonical identity"
                }
                require(entries.all { entry -> entry.scope == scope }) {
                    "retirement crossed memory scopes"
                }
                require(decision.reason.isNotBlank()) { "retirement requires a quality reason" }
                previouslyAssigned += memoryIds
                return@map decision.copy(
                    memoryIds = memoryIds,
                    canonicalKey = canonicalKey,
                    scope = scope,
                    recallState = MemoryRecallState.MAINTENANCE_ONLY,
                    reason = decision.reason.trim().take(MAX_REASON_CHARS)
                )
            }
            val canonicalKey = requireNotNull(decision.canonicalKey)
            val scope = requireNotNull(decision.scope)
            val recallState = requireNotNull(decision.recallState)
            require(MarkdownMemoryMetadataPolicy.isCanonicalKey(canonicalKey)) { "invalid canonical memory key" }
            require(MarkdownMemoryMetadataPolicy.isScope(scope)) { "invalid canonical memory scope" }
            require(recallState in ACTIVE_RECALL_STATES) { "invalid canonical recall state" }
            val reboundEntries = entries.filter { entry ->
                entry.canonicalKey != null && (entry.canonicalKey != canonicalKey || entry.scope != scope)
            }
            if (reboundEntries.isNotEmpty()) {
                require(memoryIds.size >= 2) { "single canonical memory cannot be rebound" }
                require(entries.map(MemoryLongTermCandidateEntry::scope).distinct() == listOf(scope)) {
                    "decision attempted to merge distinct memory scopes"
                }
            }
            previouslyAssigned += memoryIds
            decision.copy(
                memoryIds = memoryIds,
                canonicalKey = canonicalKey,
                scope = scope,
                recallState = consolidationRecallState(canonicalKey, scope, recallState),
                reason = decision.reason.take(MAX_REASON_CHARS)
            )
        }
        val decisions = (existing.decisions + normalized)
            .filter { decision -> decision.action in ASSIGNING_ACTIONS }
            .sortedWith(
                compareBy<MemoryLongTermCanonicalDecision> { decision -> decision.action }
                    .thenBy { decision -> decision.canonicalKey }
                    .thenBy { decision -> decision.scope }
                    .thenBy { decision -> decision.memoryIds.joinToString(",") }
            )
        require(decisions.size <= MAX_PERSISTED_DECISIONS) { "too many persisted consolidation decisions" }
        return MemoryLongTermPersistedProposal(decisions)
    }

    fun locallyDeterministicCandidates(entries: List<MarkdownMemoryEntry>): List<CanonicalMemoryCandidate> = entries
        .filter { entry -> entry.isEligibleForCanonicalConsolidation() }
        .filter { entry -> entry.canonicalKey != null }
        .groupBy { entry -> checkNotNull(entry.canonicalKey) to entry.scope }
        .toSortedMap(compareBy<Pair<String, String>> { pair -> pair.first }.thenBy { pair -> pair.second })
        .filterValues { identityEntries ->
            identityEntries.size > 1 || identityEntries.any { entry -> entry.requiresCoreRecallRepair() }
        }
        .values
        .flatten()
        .map { entry ->
            entry.toCanonicalCandidate(
                recallState = consolidationRecallState(
                    canonicalKey = checkNotNull(entry.canonicalKey),
                    scope = entry.scope,
                    recallState = entry.recallState
                )
            )
        }

    fun proposalCandidates(
        entries: List<MarkdownMemoryEntry>,
        proposal: MemoryLongTermPersistedProposal
    ): List<List<CanonicalMemoryCandidate>> {
        val entriesById = entries.associateBy(MarkdownMemoryEntry::id)
        return proposal.decisions
            .filter { decision -> decision.action == MemoryLongTermDecisionAction.CANONICALIZE }
            .map { decision ->
                decision.memoryIds.map { memoryId ->
                    val entry = requireNotNull(entriesById[memoryId]) {
                        "persisted proposal references a missing memory"
                    }
                    require(entry.isEligibleForCanonicalConsolidation()) {
                        "persisted proposal references an ineligible memory"
                    }
                    entry.toCanonicalCandidate(
                        canonicalKey = requireNotNull(decision.canonicalKey),
                        scope = requireNotNull(decision.scope),
                        recallState = consolidationRecallState(
                            canonicalKey = requireNotNull(decision.canonicalKey),
                            scope = requireNotNull(decision.scope),
                            recallState = requireNotNull(decision.recallState)
                        )
                    )
                }
            }
    }

    fun retirementMemoryIds(
        entries: List<MarkdownMemoryEntry>,
        proposal: MemoryLongTermPersistedProposal
    ): List<String> {
        val entriesById = entries.associateBy(MarkdownMemoryEntry::id)
        return proposal.decisions
            .filter { decision -> decision.action == MemoryLongTermDecisionAction.RETIRE }
            .flatMap(MemoryLongTermCanonicalDecision::memoryIds)
            .distinct()
            .sorted()
            .onEach { memoryId ->
                val entry = requireNotNull(entriesById[memoryId]) {
                    "retirement proposal references a missing memory"
                }
                require(entry.isCurrentActive()) { "retirement proposal references an inactive memory" }
            }
    }

    fun selectBoundedCandidateGroups(
        localCandidates: List<CanonicalMemoryCandidate>,
        proposalCandidateGroups: List<List<CanonicalMemoryCandidate>>,
        maxOperations: Int = MemoryControlledOperationPolicy.MAX_OPERATIONS
    ): Pair<List<CanonicalMemoryCandidate>, Boolean> {
        require(maxOperations in 0..MemoryControlledOperationPolicy.MAX_OPERATIONS) {
            "invalid bounded canonical operation limit"
        }
        val groups = buildList {
            localCandidates
                .groupBy { candidate -> candidate.canonicalKey to candidate.scope }
                .toSortedMap(compareBy<Pair<String, String>> { pair -> pair.first }.thenBy { pair -> pair.second })
                .values
                .forEach(::add)
            proposalCandidateGroups.forEach(::add)
        }
        val selected = mutableListOf<CanonicalMemoryCandidate>()
        var consumedGroups = 0
        var hasPartialGroup = false
        for (group in groups) {
            val remaining = maxOperations - selected.size
            if (remaining == 0) break
            val boundedGroup = group.take(remaining)
            selected += boundedGroup
            if (boundedGroup.size != group.size) {
                hasPartialGroup = true
                break
            }
            consumedGroups += 1
        }
        return selected to (hasPartialGroup || consumedGroups < groups.size)
    }

    private fun MarkdownMemoryEntry.isCurrentActive(): Boolean =
        validity == MemoryValidity.CURRENT && recallState in ACTIVE_RECALL_STATES

    private fun MarkdownMemoryEntry.isEligibleForCanonicalConsolidation(): Boolean =
        isCurrentActive() &&
            text.length <= MemoryControlledOperationPolicy.MAX_MEMORY_TEXT_CHARS &&
            type in MemoryControlledOperationPolicy.validTypes &&
            sensitivity in MemoryControlledOperationPolicy.validSensitivities &&
            source in MemoryControlledOperationPolicy.validSources

    private fun MarkdownMemoryEntry.requiresCoreRecallRepair(): Boolean =
        canonicalKey in CORE_IDENTITY_CANONICAL_KEYS &&
            scope == MemoryScope.GENERAL &&
            recallState != MemoryRecallState.CORE

    private fun consolidationRecallState(
        canonicalKey: String,
        scope: String,
        recallState: String
    ): String = if (
        canonicalKey in CORE_IDENTITY_CANONICAL_KEYS && scope == MemoryScope.GENERAL
    ) {
        MemoryRecallState.CORE
    } else {
        recallState
    }

    private fun MarkdownMemoryEntry.requiresSemanticReview(
        eligibleEntries: List<MarkdownMemoryEntry>,
        consumedIds: Set<String>,
        forceReview: Boolean
    ): Boolean {
        if (forceReview) return true
        if (canonicalKey == null) return true
        return eligibleEntries.any { candidate ->
            candidate.id != id &&
                candidate.id !in consumedIds &&
                candidate.type == type &&
                candidate.scope == scope &&
                canShareSemanticGroupWith(candidate)
        }
    }

    private fun MarkdownMemoryEntry.canShareSemanticGroupWith(other: MarkdownMemoryEntry): Boolean =
        canonicalKey == null || other.canonicalKey == null || canonicalKey != other.canonicalKey

    private fun groupingAffinity(
        anchor: MarkdownMemoryEntry,
        candidate: MarkdownMemoryEntry
    ): Int {
        val anchorKey = anchor.canonicalKey ?: return 1
        val candidateKey = candidate.canonicalKey ?: return 1
        return if (anchorKey.substringBeforeLast('.') == candidateKey.substringBeforeLast('.')) 2 else 0
    }

    private fun MarkdownMemoryEntry.toCandidateEntry(): MemoryLongTermCandidateEntry = MemoryLongTermCandidateEntry(
        memoryId = id,
        text = text,
        type = type,
        source = source,
        canonicalKey = canonicalKey,
        scope = scope,
        lastObservedAt = lastObservedAt,
        recallState = recallState
    )

    private fun MarkdownMemoryEntry.toCanonicalCandidate(
        canonicalKey: String = checkNotNull(this.canonicalKey),
        scope: String = this.scope,
        recallState: String = this.recallState
    ): CanonicalMemoryCandidate = CanonicalMemoryCandidate(
        targetMemoryId = id,
        chatId = chatId,
        text = text,
        type = type,
        sensitivity = sensitivity,
        source = source,
        canonicalKey = canonicalKey,
        scope = scope,
        evidenceAt = lastObservedAt,
        recallState = recallState,
        evidenceRefs = evidenceRefs
    )

    private fun localSimilarity(left: String, right: String): Float {
        val leftTokens = similarityTokens(left)
        val rightTokens = similarityTokens(right)
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0f
        val intersection = leftTokens.intersect(rightTokens).size
        val union = leftTokens.union(rightTokens).size
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    private fun similarityTokens(text: String): Set<String> {
        val normalized = normalizeExactMemoryText(text)
        val wordTokens = WORD_REGEX.findAll(normalized).map { match -> match.value }.toMutableSet()
        CJK_SEQUENCE_REGEX.findAll(normalized).forEach { match ->
            val value = match.value
            if (value.length == 1) {
                wordTokens += value
            } else {
                value.windowed(2).forEach(wordTokens::add)
            }
        }
        return wordTokens
    }

    private fun List<MarkdownMemoryEntry>.takeByCharacterBudget(limit: Int): List<MarkdownMemoryEntry> {
        val selected = mutableListOf<MarkdownMemoryEntry>()
        var chars = 0
        forEach { entry ->
            val entryChars = entry.requestCharacterCount()
            if (selected.isNotEmpty() && chars + entryChars > limit) return@forEach
            selected += entry
            chars += entryChars
        }
        return selected
    }

    private fun MarkdownMemoryEntry.requestCharacterCount(): Int = text.length + ENTRY_OVERHEAD_CHARS

    private fun MemoryLongTermPartition.toRequest(
        checkpointId: String,
        groups: List<MemoryLongTermCandidateGroup>
    ): MemoryLongTermConsolidationPartitionRequest = MemoryLongTermConsolidationPartitionRequest(
        checkpointId = checkpointId,
        partitionStart = start,
        partitionEndExclusive = endExclusive,
        candidateGroups = groups
    )

    private fun fitSingleAnchorGroups(
        checkpointId: String,
        partition: MemoryLongTermPartition,
        groups: List<MemoryLongTermCandidateGroup>
    ): List<MemoryLongTermCandidateGroup> {
        var fitted = groups
        while (
            requestJson.encodeToString(partition.toRequest(checkpointId, fitted)).length >
            MAX_SERIALIZED_REQUEST_CHARS
        ) {
            val lastGroup = fitted.lastOrNull() ?: return emptyList()
            fitted = if (lastGroup.entries.size == 1) {
                fitted.dropLast(1)
            } else {
                val entries = lastGroup.entries.dropLast(1)
                val entryIds = entries.map(MemoryLongTermCandidateEntry::memoryId).toSet()
                val anchors = lastGroup.anchorMemoryIds.filter(entryIds::contains)
                if (anchors.isEmpty()) {
                    fitted.dropLast(1)
                } else {
                    fitted.dropLast(1) + lastGroup.copy(
                        groupId = stableGroupId(entryIds.sorted()),
                        anchorMemoryIds = anchors,
                        entries = entries
                    )
                }
            }
        }
        return fitted
    }

    private fun stableGroupId(ids: List<String>): String =
        "lt_group_${ids.joinToString("|").sha256Utf8().take(ID_HASH_LENGTH)}"

    companion object {
        private const val PREFERRED_ADDRESS_CANONICAL_KEY = "identity.preferred_address"
        private const val ASSISTANT_NAME_CANONICAL_KEY = "identity.assistant_name"
        private val CORE_IDENTITY_CANONICAL_KEYS = setOf(
            PREFERRED_ADDRESS_CANONICAL_KEY,
            ASSISTANT_NAME_CANONICAL_KEY
        )
        const val MAX_PARTITION_ENTRIES = 24
        const val MAX_PARTITION_CHARS = 12_000
        const val MAX_SERIALIZED_REQUEST_CHARS = 12_000
        const val MAX_GROUPS_PER_PARTITION = MAX_PARTITION_ENTRIES
        const val MAX_GROUP_ENTRIES = 8
        const val MAX_GROUP_CHARS = 6_000
        const val MAX_DECISIONS_PER_PARTITION = 16
        const val MAX_PERSISTED_DECISIONS = 32
        private const val ENTRY_OVERHEAD_CHARS = 160
        private const val MAX_REASON_CHARS = 240
        private const val ID_HASH_LENGTH = 24
        private val ACTIVE_RECALL_STATES = setOf(MemoryRecallState.CORE, MemoryRecallState.QUERY)
        private val ASSIGNING_ACTIONS = setOf(
            MemoryLongTermDecisionAction.CANONICALIZE,
            MemoryLongTermDecisionAction.RETIRE
        )
        private val VALID_ACTIONS = ASSIGNING_ACTIONS + MemoryLongTermDecisionAction.IGNORE
        private val WORD_REGEX = Regex("[a-z0-9]+")
        private val CJK_SEQUENCE_REGEX = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]+")
    }
}
