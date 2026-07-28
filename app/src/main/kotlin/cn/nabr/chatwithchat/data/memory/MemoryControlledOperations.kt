package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexDefaults

internal object MemoryControlledOperationPolicy {
    const val MAX_OPERATIONS = 32
    const val MAX_MEMORY_TEXT_CHARS = 4_000
    const val MAX_REASON_CHARS = 240
    const val MAX_EVIDENCE_KEYS = 24

    val validTypes = setOf(
        "stable_profile",
        "communication_style",
        "project_context",
        "interest",
        "important_event",
        "important_person",
        "emotional_pattern",
        "boundary",
        "life_context",
        "recurring_theme",
        "light_productivity_preference"
    )
    val validSensitivities = setOf(
        MemorySensitivity.NORMAL,
        MemorySensitivity.PRIVATE,
        MemorySensitivity.SENSITIVE
    )
    val validSources = setOf(
        MemorySource.EXPLICIT_USER_STATEMENT,
        MemorySource.ASSISTANT_INFERRED,
        MemorySource.USER_CONFIRMED
    )
}

class MemoryDailyDistillationOperationController(
    private val markdownMemoryCodec: MarkdownMemoryCodec,
    private val targetIndexFingerprint: String = MemoryVectorIndexDefaults.configuration.fingerprint()
) {
    private val canonicalMemoryMergePolicy = CanonicalMemoryMergePolicy(markdownMemoryCodec)

    fun validate(
        input: MemoryDailyDistillationFrozenInput,
        operations: List<MemoryDailyDistillationOperation>
    ): List<MemoryDailyDistillationOperation> {
        require(input.dailySourcePath.startsWith("${MemoryFilePaths.DAILY_MEMORY_DIRECTORY_NAME}/"))
        require(input.dailyEvidence.isNotEmpty())
        require(input.dailyEvidence.map { evidence -> evidence.evidenceKey }.distinct().size == input.dailyEvidence.size)
        require(input.existingMemories.map { memory -> memory.id }.distinct().size == input.existingMemories.size)
        require(input.existingMemories.all { memory -> memory.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME })
        require(operations.size <= MemoryControlledOperationPolicy.MAX_OPERATIONS)

        val evidenceByKey = input.dailyEvidence.associateBy(MemoryDailyDistillationEvidence::evidenceKey)
        require(input.dailyEvidence.all { evidence -> evidence.createdAt >= 0L && evidence.updatedAt >= 0L })
        val existingById = input.existingMemories.associateBy(MemoryBatchExistingMemory::id)
        val targetedIds = mutableSetOf<String>()

        return operations.map { operation ->
            require(operation.action in VALID_ACTIONS)
            require(operation.type in MemoryControlledOperationPolicy.validTypes)
            require(operation.sensitivity in MemoryControlledOperationPolicy.validSensitivities)
            require(operation.source in MemoryControlledOperationPolicy.validSources)
            require(operation.reason.length <= MemoryControlledOperationPolicy.MAX_REASON_CHARS)
            require(operation.evidenceKeys.size <= MemoryControlledOperationPolicy.MAX_EVIDENCE_KEYS)
            require(operation.evidenceKeys.distinct().size == operation.evidenceKeys.size)
            require(operation.evidenceKeys.all(evidenceByKey::containsKey))

            when (operation.action) {
                MemoryDailyDistillationAction.IGNORE -> {
                    require(operation.targetMemoryId.isNullOrBlank())
                    require(operation.text.isBlank())
                    require(operation.canonicalKey == null)
                    require(operation.scope == null)
                    require(operation.evidenceAt == null)
                    require(operation.recallState == null)
                    operation.copy(reason = operation.reason.trim())
                }
                MemoryDailyDistillationAction.CREATE,
                MemoryDailyDistillationAction.REPLACE -> {
                    require(operation.evidenceKeys.isNotEmpty())
                    val normalizedText = normalizeWriteText(operation.text)
                    val target = if (operation.action == MemoryDailyDistillationAction.REPLACE) {
                        val targetId = requireNotNull(operation.targetMemoryId?.takeIf(String::isNotBlank))
                        require(targetedIds.add(targetId))
                        requireNotNull(existingById[targetId])
                    } else {
                        require(operation.targetMemoryId.isNullOrBlank())
                        null
                    }
                    val evidence = operation.evidenceKeys.map(evidenceByKey::getValue)
                    val canonicalKey = requireNotNull(operation.canonicalKey)
                    val scope = requireNotNull(operation.scope)
                    val evidenceAt = evidence.maxOf { item -> maxOf(item.createdAt, item.updatedAt) }
                    val recallState = requireNotNull(operation.recallState)
                    require(MarkdownMemoryMetadataPolicy.isCanonicalKey(canonicalKey))
                    require(MarkdownMemoryMetadataPolicy.isScope(scope))
                    require(operation.evidenceAt == evidenceAt)
                    require(recallState in ACTIVE_RECALL_STATES)
                    target?.let { existing ->
                        require(existing.type == operation.type)
                        require(existing.canonicalKey == null || existing.canonicalKey == canonicalKey)
                        require(existing.canonicalKey == null || existing.scope == scope)
                    }
                    operation.copy(
                        targetMemoryId = target?.id,
                        text = normalizedText,
                        sensitivity = derivedSensitivity(evidence, target),
                        source = derivedSource(evidence),
                        evidenceKeys = operation.evidenceKeys.sorted(),
                        canonicalKey = canonicalKey,
                        scope = scope,
                        evidenceAt = evidenceAt,
                        recallState = recallState,
                        reason = operation.reason.trim()
                    )
                }
                else -> error("Unsupported daily distillation action")
            }
        }
    }

    fun render(
        input: MemoryDailyDistillationFrozenInput,
        baseMarkdown: String,
        validatedOperations: List<MemoryDailyDistillationOperation>,
        renderedAt: Long = input.createdAt
    ): RenderedMemoryDailyDistillation {
        require(baseMarkdown.toByteArray(Charsets.UTF_8).sha256Hex() == input.targetBaseHash)
        val parsed = parseEntriesOrThrow(baseMarkdown)
        require(parsed.entries.map(MarkdownMemoryEntry::id).distinct().size == parsed.entries.size)
        val frozenExistingById = input.existingMemories.associateBy(MemoryBatchExistingMemory::id)
        val baseEntriesById = parsed.entries.associateBy(MarkdownMemoryEntry::id)
        frozenExistingById.forEach { (id, frozen) ->
            val current = requireNotNull(baseEntriesById[id])
            require(current.text == frozen.text)
            require(current.type == frozen.type)
            require(current.sensitivity == frozen.sensitivity)
            require(current.source == frozen.source)
            require(current.updatedAt == frozen.updatedAt)
        }

        val candidates = validatedOperations
            .filter { operation ->
                operation.action in setOf(
                    MemoryDailyDistillationAction.CREATE,
                    MemoryDailyDistillationAction.REPLACE
                )
            }
            .map { operation ->
                CanonicalMemoryCandidate(
                    targetMemoryId = operation.targetMemoryId,
                    text = operation.text,
                    type = operation.type,
                    sensitivity = operation.sensitivity,
                    source = operation.source,
                    canonicalKey = requireNotNull(operation.canonicalKey),
                    scope = requireNotNull(operation.scope),
                    evidenceAt = requireNotNull(operation.evidenceAt),
                    recallState = requireNotNull(operation.recallState),
                    evidenceRefs = operation.evidenceKeys
                )
            }
        val mergeResult = canonicalMemoryMergePolicy.merge(
            baseMarkdown = baseMarkdown,
            candidates = candidates,
            mutationAt = renderedAt
        )
        val targets = if (mergeResult.markdown == baseMarkdown) {
            emptyList()
        } else {
            listOf(
                MemoryMutationTarget(
                    sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                    baseContent = baseMarkdown,
                    targetContent = mergeResult.markdown,
                    targetIndexFingerprint = targetIndexFingerprint.takeIf { mergeResult.requiresIndexSync }
                )
            )
        }
        return RenderedMemoryDailyDistillation(
            targets = targets,
            writeCount = mergeResult.acceptedCandidateCount,
            targetSourceHash = mergeResult.markdown.toByteArray(Charsets.UTF_8).sha256Hex()
        )
    }

    private fun parseEntriesOrThrow(markdown: String): MarkdownMemoryParseResult = markdownMemoryCodec
        .parse(markdown)
        .also { parsed ->
            require(parsed.skippedEntries.isEmpty()) { "unsafe_memory_metadata" }
        }

    private fun normalizeWriteText(text: String): String {
        val normalized = text.trim().replace(WHITESPACE_REGEX, " ")
        require(normalized.isNotBlank())
        require(normalized.length <= MemoryControlledOperationPolicy.MAX_MEMORY_TEXT_CHARS)
        require(!normalized.startsWith("The user said:", ignoreCase = true))
        require(!normalized.startsWith("## "))
        require(!normalized.startsWith("<!-- memory:", ignoreCase = true))
        return normalized
    }

    private fun derivedSensitivity(
        evidence: List<MemoryDailyDistillationEvidence>,
        target: MemoryBatchExistingMemory?
    ): String = (evidence.map { item -> item.sensitivity } + listOfNotNull(target?.sensitivity))
        .maxBy(::sensitivityRank)

    private fun derivedSource(evidence: List<MemoryDailyDistillationEvidence>): String = evidence
        .map { item -> item.source }
        .maxBy(::sourceRank)

    private fun sensitivityRank(value: String): Int = when (value) {
        MemorySensitivity.NORMAL -> 0
        MemorySensitivity.PRIVATE -> 1
        MemorySensitivity.SENSITIVE -> 2
        else -> error("Unknown memory sensitivity")
    }

    private fun sourceRank(value: String): Int = when (value) {
        MemorySource.ASSISTANT_INFERRED -> 0
        MemorySource.EXPLICIT_USER_STATEMENT -> 1
        MemorySource.USER_CONFIRMED -> 2
        else -> error("Unknown memory source")
    }

    private companion object {
        val VALID_ACTIONS = setOf(
            MemoryDailyDistillationAction.CREATE,
            MemoryDailyDistillationAction.REPLACE,
            MemoryDailyDistillationAction.IGNORE
        )
        val ACTIVE_RECALL_STATES = setOf(MemoryRecallState.CORE, MemoryRecallState.QUERY)
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}

data class RenderedMemoryDailyDistillation(
    val targets: List<MemoryMutationTarget>,
    val writeCount: Int,
    val targetSourceHash: String
)
