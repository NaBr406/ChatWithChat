package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexDefaults
import cn.nabr.chatwithchat.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class MemoryImportService @Inject constructor(
    private val memoryFileStore: MemoryFileStore,
    private val memoryMutationCoordinator: MemoryMutationCoordinator,
    private val memoryIntelligence: MemoryIntelligence,
    private val memoryModelResolver: MemoryModelResolver,
    private val settingRepository: SettingRepository
) {
    private val markdownMemoryCodec = MarkdownMemoryCodec()
    private val canonicalMemoryMergePolicy = CanonicalMemoryMergePolicy(markdownMemoryCodec)

    suspend fun importAppMemory(rawMarkdown: String): MemoryImportOutcome {
        val markdown = normalizeInput(rawMarkdown)
        if (markdown.isBlank()) throw MemoryImportException(MemoryImportException.Reason.EMPTY_INPUT)
        if (markdown.length > MAX_APP_IMPORT_CHARS) {
            throw MemoryImportException(MemoryImportException.Reason.INPUT_TOO_LARGE)
        }
        if (!markdownMemoryCodec.isValidLongTermDocument(markdown)) {
            throw MemoryImportException(MemoryImportException.Reason.INVALID_APP_FORMAT)
        }

        val imported = markdownMemoryCodec.parse(markdown).entries
        val baseMarkdown = readCurrentMarkdown()
        val baseParse = markdownMemoryCodec.parse(baseMarkdown)
        if (baseParse.skippedEntries.isNotEmpty()) {
            throw MemoryImportException(MemoryImportException.Reason.CURRENT_MEMORY_INVALID)
        }
        val baseEntries = baseParse.entries
        val existingIds = baseEntries.mapTo(mutableSetOf(), MarkdownMemoryEntry::id)
        val existingTextOwners = baseEntries
            .filter { entry -> entry.validity == MemoryValidity.CURRENT }
            .groupBy { entry -> normalizeExactMemoryText(entry.text) }
            .mapValues { (_, entries) -> entries.minBy(MarkdownMemoryEntry::id) }
            .mapValues { (_, entry) -> entry.id }
        val importedIdMapping = mutableMapOf<String, String>()
        val acceptedOriginalIds = mutableSetOf<String>()
        imported.groupBy { entry -> normalizeExactMemoryText(entry.text) }
            .forEach { (normalizedText, entries) ->
                val existingOwnerId = existingTextOwners[normalizedText]
                if (existingOwnerId != null) {
                    entries.forEach { entry -> importedIdMapping[entry.id] = existingOwnerId }
                } else {
                    val owner = entries
                        .sortedWith(compareBy<MarkdownMemoryEntry> { entry ->
                            if (entry.validity == MemoryValidity.CURRENT) 0 else 1
                        }.thenBy(MarkdownMemoryEntry::id))
                        .first()
                    val uniqueId = uniqueImportedId(owner.id, owner.text, existingIds)
                    existingIds += uniqueId
                    entries.forEach { entry -> importedIdMapping[entry.id] = uniqueId }
                    acceptedOriginalIds += owner.id
                }
            }
        val accepted = imported
            .filter { entry -> entry.id in acceptedOriginalIds }
            .map { entry ->
                entry.copy(
                    id = checkNotNull(importedIdMapping[entry.id]),
                    supersededBy = entry.supersededBy?.let { targetId ->
                        importedIdMapping[targetId] ?: targetId
                    }
                )
            }
        if (accepted.isEmpty()) {
            return MemoryImportOutcome.Imported(
                importedCount = 0,
                skippedCount = imported.size,
                rewrittenByModel = false
            )
        }

        val appendedMarkdown = markdownMemoryCodec.appendLongTermEntries(baseMarkdown, accepted)
        if (markdownMemoryCodec.parse(appendedMarkdown).skippedEntries.isNotEmpty()) {
            throw MemoryImportException(MemoryImportException.Reason.WRITE_FAILED)
        }
        val addressingCandidates = accepted
            .filter { entry ->
                entry.validity == MemoryValidity.CURRENT &&
                    MemoryCanonicalIdentityPolicy.isAddressingIdentity(entry.canonicalKey, entry.scope)
            }
            .map { entry ->
                CanonicalMemoryCandidate(
                    targetMemoryId = entry.id,
                    chatId = entry.chatId,
                    text = entry.text,
                    type = entry.type,
                    sensitivity = entry.sensitivity,
                    source = entry.source,
                    canonicalKey = checkNotNull(entry.canonicalKey),
                    scope = entry.scope,
                    evidenceAt = entry.lastObservedAt,
                    recallState = entry.recallState.takeIf { state ->
                        state in setOf(MemoryRecallState.CORE, MemoryRecallState.QUERY)
                    } ?: MemoryRecallState.QUERY,
                    evidenceRefs = entry.evidenceRefs
                )
            }
        val targetMarkdown = if (addressingCandidates.isEmpty()) {
            appendedMarkdown
        } else {
            try {
                canonicalMemoryMergePolicy.merge(
                    baseMarkdown = appendedMarkdown,
                    candidates = addressingCandidates,
                    mutationAt = System.currentTimeMillis() / 1_000L,
                    allowCanonicalRebinding = true,
                    promoteRecallState = true
                ).markdown
            } catch (throwable: Throwable) {
                throw MemoryImportException(MemoryImportException.Reason.WRITE_FAILED, throwable)
            }
        }
        commitLocalMutation(
            baseMarkdown = baseMarkdown,
            targetMarkdown = targetMarkdown,
            operationKey = "import_app_${markdown.sha256Utf8().take(OPERATION_HASH_LENGTH)}",
            materialMutationCount = accepted.size + addressingCandidates.size
        )
        return MemoryImportOutcome.Imported(
            importedCount = accepted.size,
            skippedCount = imported.size - accepted.size,
            rewrittenByModel = false
        )
    }

    suspend fun importExternalMemory(rawText: String): MemoryImportOutcome {
        val importedText = normalizeInput(rawText)
        if (importedText.isBlank()) throw MemoryImportException(MemoryImportException.Reason.EMPTY_INPUT)
        if (importedText.length > MAX_IMPORT_CHARS) {
            throw MemoryImportException(MemoryImportException.Reason.INPUT_TOO_LARGE)
        }

        val baseMarkdown = readCurrentMarkdown()
        val parsed = markdownMemoryCodec.parse(baseMarkdown)
        if (parsed.skippedEntries.isNotEmpty()) {
            throw MemoryImportException(MemoryImportException.Reason.CURRENT_MEMORY_INVALID)
        }
        val resolution = memoryModelResolver.resolvePreference(settingRepository.fetchMemoryModelPreference())
        val platform = when (resolution) {
            is MemoryModelResolution.Resolved -> resolution.platform
            is MemoryModelResolution.Unavailable -> {
                throw MemoryImportException(MemoryImportException.Reason.MODEL_UNAVAILABLE)
            }
        }
        val request = MemoryImportRequest(
            importedText = importedText,
            existingMemories = parsed.entries
                .sortedBy(MarkdownMemoryEntry::id)
                .take(MAX_EXISTING_MEMORIES)
                .map { entry -> entry.toExistingMemory() }
        )
        val modelVisibleMemoryIds = request.existingMemories.mapTo(mutableSetOf(), MemoryBatchExistingMemory::id)
        val proposal = try {
            memoryIntelligence.rewriteImportedMemory(request, platform)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw MemoryImportException(MemoryImportException.Reason.MODEL_REWRITE_FAILED, throwable)
        } ?: throw MemoryImportException(MemoryImportException.Reason.MODEL_REWRITE_FAILED)

        val operations = proposal.operations
        if (operations.size > MemoryControlledOperationPolicy.MAX_OPERATIONS) {
            throw MemoryImportException(MemoryImportException.Reason.MODEL_REWRITE_FAILED)
        }
        val mutationAt = System.currentTimeMillis() / 1_000L
        val evidenceRef = "import:${importedText.sha256Utf8().take(IMPORT_EVIDENCE_HASH_LENGTH)}"
        val candidates = operations.mapNotNull { operation ->
            when (operation.action) {
                MemoryImportAction.IGNORE -> null
                MemoryImportAction.CREATE,
                MemoryImportAction.REPLACE -> operation.toCanonicalCandidate(
                    evidenceAt = mutationAt,
                    evidenceRef = evidenceRef,
                    modelVisibleMemoryIds = modelVisibleMemoryIds
                )
                else -> throw MemoryImportException(MemoryImportException.Reason.MODEL_REWRITE_FAILED)
            }
        }
        if (candidates.isEmpty()) {
            return MemoryImportOutcome.Imported(
                importedCount = 0,
                skippedCount = operations.count { operation -> operation.action == MemoryImportAction.IGNORE },
                rewrittenByModel = true
            )
        }
        val merge = try {
            canonicalMemoryMergePolicy.merge(
                baseMarkdown = baseMarkdown,
                candidates = candidates,
                mutationAt = mutationAt
            )
        } catch (throwable: Throwable) {
            throw MemoryImportException(MemoryImportException.Reason.MODEL_REWRITE_FAILED, throwable)
        }
        if (merge.markdown == baseMarkdown || merge.changedEntryCount == 0) {
            return MemoryImportOutcome.Imported(
                importedCount = 0,
                skippedCount = candidates.size,
                rewrittenByModel = true
            )
        }
        commitLocalMutation(
            baseMarkdown = baseMarkdown,
            targetMarkdown = merge.markdown,
            operationKey = "import_external_${importedText.sha256Utf8().take(OPERATION_HASH_LENGTH)}",
            materialMutationCount = merge.materialMutationCount
        )
        return MemoryImportOutcome.Imported(
            importedCount = merge.acceptedCandidateCount,
            skippedCount = (candidates.size - merge.acceptedCandidateCount).coerceAtLeast(0),
            rewrittenByModel = true
        )
    }

    private suspend fun commitLocalMutation(
        baseMarkdown: String,
        targetMarkdown: String,
        operationKey: String,
        materialMutationCount: Int
    ) {
        val target = MemoryMutationTarget(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            baseContent = baseMarkdown,
            targetContent = targetMarkdown,
            targetIndexFingerprint = MemoryVectorIndexDefaults.configuration.fingerprint(),
            materialMutationCount = materialMutationCount.coerceAtLeast(0)
        )
        val mutation = try {
            memoryMutationCoordinator.prepareLocalMutation(operationKey, listOf(target))
        } catch (throwable: Throwable) {
            throw MemoryImportException(MemoryImportException.Reason.WRITE_FAILED, throwable)
        }
        try {
            when (memoryMutationCoordinator.reconcile(mutation)) {
                is MemoryMutationCommitResult.CanonicalCommitted -> Unit
                is MemoryMutationCommitResult.Conflict -> {
                    throw MemoryImportException(MemoryImportException.Reason.CONFLICT)
                }
            }
        } catch (exception: MemoryImportException) {
            throw exception
        } catch (throwable: Throwable) {
            throw MemoryImportException(MemoryImportException.Reason.WRITE_FAILED, throwable)
        }
    }

    private fun readCurrentMarkdown(): String = memoryFileStore.readLongTermMemory().getOrElse { throwable ->
        throw MemoryImportException(MemoryImportException.Reason.WRITE_FAILED, throwable)
    }

    private fun normalizeInput(value: String): String = value
        .removePrefix("\uFEFF")
        .trim()

    private fun uniqueImportedId(
        originalId: String,
        text: String,
        occupiedIds: Set<String>
    ): String {
        if (originalId !in occupiedIds) return originalId
        val base = "import_${"$originalId|$text".sha256Utf8().take(24)}"
        if (base !in occupiedIds) return base
        var suffix = 2
        while ("${base}_$suffix" in occupiedIds) suffix += 1
        return "${base}_$suffix"
    }

    private fun MarkdownMemoryEntry.toExistingMemory(): MemoryBatchExistingMemory = MemoryBatchExistingMemory(
        id = id,
        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
        text = text,
        type = type,
        sensitivity = sensitivity,
        source = source,
        updatedAt = updatedAt,
        createdAt = createdAt,
        canonicalKey = canonicalKey,
        scope = scope,
        lastObservedAt = lastObservedAt,
        validity = validity,
        supersededBy = supersededBy,
        recallState = recallState,
        evidenceRefs = evidenceRefs
    )

    private fun MemoryImportOperation.toCanonicalCandidate(
        evidenceAt: Long,
        evidenceRef: String,
        modelVisibleMemoryIds: Set<String>
    ): CanonicalMemoryCandidate {
        val targetId = when (action) {
            MemoryImportAction.CREATE -> {
                if (targetMemoryId != null) {
                    throw MemoryImportException(MemoryImportException.Reason.MODEL_REWRITE_FAILED)
                }
                null
            }
            MemoryImportAction.REPLACE -> targetMemoryId
                ?.takeIf(String::isNotBlank)
                ?.takeIf(modelVisibleMemoryIds::contains)
                ?: throw MemoryImportException(MemoryImportException.Reason.MODEL_REWRITE_FAILED)
            else -> throw MemoryImportException(MemoryImportException.Reason.MODEL_REWRITE_FAILED)
        }
        val canonicalKey = canonicalKey ?: throw MemoryImportException(MemoryImportException.Reason.MODEL_REWRITE_FAILED)
        val scope = scope ?: throw MemoryImportException(MemoryImportException.Reason.MODEL_REWRITE_FAILED)
        val recallState = recallState ?: throw MemoryImportException(MemoryImportException.Reason.MODEL_REWRITE_FAILED)
        return CanonicalMemoryCandidate(
            targetMemoryId = targetId,
            text = text,
            type = type,
            sensitivity = sensitivity,
            source = source,
            canonicalKey = canonicalKey,
            scope = scope,
            evidenceAt = evidenceAt,
            recallState = recallState,
            evidenceRefs = listOf(evidenceRef)
        )
    }

    private companion object {
        const val MAX_IMPORT_CHARS = 32_000
        const val MAX_APP_IMPORT_CHARS = 256 * 1024
        const val MAX_EXISTING_MEMORIES = 64
        const val OPERATION_HASH_LENGTH = 24
        const val IMPORT_EVIDENCE_HASH_LENGTH = 24
    }
}
