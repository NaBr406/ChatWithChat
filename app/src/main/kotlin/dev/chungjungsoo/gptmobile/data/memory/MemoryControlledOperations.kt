package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexDefaults

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
        val existingById = input.existingMemories.associateBy(MemoryBatchExistingMemory::id)
        val targetedIds = mutableSetOf<String>()
        val normalizedWriteTexts = mutableSetOf<String>()
        val normalizedExistingTexts = input.existingMemories.map { memory -> normalizeText(memory.text) }.toSet()

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
                    operation.copy(reason = operation.reason.trim())
                }
                MemoryDailyDistillationAction.CREATE,
                MemoryDailyDistillationAction.REPLACE -> {
                    require(operation.evidenceKeys.isNotEmpty())
                    val normalizedText = normalizeWriteText(operation.text)
                    require(normalizedWriteTexts.add(normalizeText(normalizedText)))
                    val target = if (operation.action == MemoryDailyDistillationAction.REPLACE) {
                        val targetId = requireNotNull(operation.targetMemoryId?.takeIf(String::isNotBlank))
                        require(targetedIds.add(targetId))
                        requireNotNull(existingById[targetId])
                    } else {
                        require(operation.targetMemoryId.isNullOrBlank())
                        require(normalizeText(normalizedText) !in normalizedExistingTexts)
                        null
                    }
                    val evidence = operation.evidenceKeys.map(evidenceByKey::getValue)
                    operation.copy(
                        targetMemoryId = target?.id,
                        text = normalizedText,
                        sensitivity = derivedSensitivity(evidence, target),
                        source = derivedSource(evidence, target),
                        evidenceKeys = operation.evidenceKeys.sorted(),
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
        val parsed = markdownMemoryCodec.parse(baseMarkdown)
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

        var markdown = baseMarkdown
        var writeCount = 0
        validatedOperations.forEachIndexed { index, operation ->
            when (operation.action) {
                MemoryDailyDistillationAction.IGNORE -> Unit
                MemoryDailyDistillationAction.CREATE -> {
                    val id = generatedEntryId(input.batchId, index)
                    val currentEntries = markdownMemoryCodec.parse(markdown).entries
                    val duplicateText = currentEntries.any { entry ->
                        normalizeText(entry.text) == normalizeText(operation.text)
                    }
                    require(!duplicateText)
                    currentEntries.firstOrNull { entry -> entry.id == id }?.let { existing ->
                        require(existing.text == operation.text)
                        return@forEachIndexed
                    }
                    val append = markdownMemoryCodec.renderLongTermAppend(
                        listOf(operation.toEntry(id = id, createdAt = renderedAt))
                    )
                    markdown = markdown.trimEnd() + "\n\n" + append.trim() + "\n"
                    writeCount += 1
                }
                MemoryDailyDistillationAction.REPLACE -> {
                    val targetId = requireNotNull(operation.targetMemoryId)
                    val currentEntries = markdownMemoryCodec.parse(markdown).entries.associateBy(MarkdownMemoryEntry::id)
                    val existing = requireNotNull(currentEntries[targetId])
                    val replacement = markdownMemoryCodec.replaceEntriesById(
                        markdown,
                        listOf(
                            operation.toEntry(
                                id = targetId,
                                createdAt = existing.createdAt,
                                chatId = existing.chatId,
                                section = existing.section,
                                updatedAt = renderedAt
                            )
                        )
                    )
                    require(replacement.replacedCount == 1)
                    markdown = replacement.markdown
                    writeCount += 1
                }
            }
        }
        val normalizedTarget = markdown.trimEnd() + "\n"
        val targets = if (normalizedTarget == baseMarkdown) {
            emptyList()
        } else {
            listOf(
                MemoryMutationTarget(
                    sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                    baseContent = baseMarkdown,
                    targetContent = normalizedTarget,
                    targetIndexFingerprint = targetIndexFingerprint
                )
            )
        }
        return RenderedMemoryDailyDistillation(
            targets = targets,
            writeCount = writeCount,
            targetSourceHash = normalizedTarget.toByteArray(Charsets.UTF_8).sha256Hex()
        )
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

    private fun normalizeText(text: String): String = text.trim().lowercase().replace(WHITESPACE_REGEX, " ")

    private fun derivedSensitivity(
        evidence: List<MemoryDailyDistillationEvidence>,
        target: MemoryBatchExistingMemory?
    ): String = (evidence.map { item -> item.sensitivity } + listOfNotNull(target?.sensitivity))
        .maxBy(::sensitivityRank)

    private fun derivedSource(
        evidence: List<MemoryDailyDistillationEvidence>,
        target: MemoryBatchExistingMemory?
    ): String = (evidence.map { item -> item.source } + listOfNotNull(target?.source))
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

    private fun generatedEntryId(batchId: String, operationIndex: Int): String =
        "mem_${"$batchId|$operationIndex|long_term".sha256Utf8().take(ID_HASH_LENGTH)}"

    private fun MemoryDailyDistillationOperation.toEntry(
        id: String,
        createdAt: Long,
        chatId: Int? = null,
        section: String? = null,
        updatedAt: Long = createdAt
    ): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = type,
        sensitivity = sensitivity,
        source = source,
        chatId = chatId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        section = section
    )

    private companion object {
        const val ID_HASH_LENGTH = 24
        val VALID_ACTIONS = setOf(
            MemoryDailyDistillationAction.CREATE,
            MemoryDailyDistillationAction.REPLACE,
            MemoryDailyDistillationAction.IGNORE
        )
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}

data class RenderedMemoryDailyDistillation(
    val targets: List<MemoryMutationTarget>,
    val writeCount: Int,
    val targetSourceHash: String
)
