package cn.nabr.chatwithchat.data.memory

data class ModelVisibleMemoryFact(
    val text: String
) {
    init {
        require(text.isNotBlank()) { "Memory fact text must not be blank" }
        require(!text.containsInternalMemoryMetadata()) {
            "Memory fact text must not contain internal metadata"
        }
    }
}

internal fun String.toModelVisibleMemoryFactOrNull(): ModelVisibleMemoryFact? =
    trim()
        .takeIf { text -> text.isNotBlank() && !text.containsInternalMemoryMetadata() }
        ?.let(::ModelVisibleMemoryFact)

internal fun String.containsInternalMemoryMetadata(): Boolean =
    INTERNAL_MEMORY_METADATA_MARKERS.any { marker -> marker.containsMatchIn(this) }

data class TieredMemoryRecall(
    val coreResults: List<MemoryRetrievalResult> = emptyList(),
    val queryResults: List<MemoryRetrievalResult> = emptyList()
)

data class RenderedMemoryPrompt(
    val prompt: String? = null,
    val coreFacts: List<ModelVisibleMemoryFact> = emptyList(),
    val queryFacts: List<ModelVisibleMemoryFact> = emptyList(),
    val estimatedTokens: Int = 0
)

data class TurnRecallSnapshot(
    val canonicalRevision: Long? = null,
    val canonicalSourceHash: String? = null,
    val recallProjectionHash: String? = null,
    val coreFacts: List<ModelVisibleMemoryFact> = emptyList(),
    val queryFacts: List<ModelVisibleMemoryFact> = emptyList(),
    val mode: MemoryRetrievalMode = MemoryRetrievalMode.NONE,
    val errorMessage: String? = null,
    val diagnostics: List<MemoryProjectionDiagnostic> = emptyList(),
    val prompt: String? = null,
    val estimatedTokens: Int = 0
)

internal fun MemoryCorpusSnapshot.selectCoreResults(includePrivate: Boolean): List<MemoryRetrievalResult> {
    return chunks
        .asSequence()
        .filter { chunk -> chunk.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME }
        .filter { chunk -> chunk.validity == MemoryValidity.CURRENT }
        .filter { chunk -> chunk.recallState == MemoryRecallState.CORE }
        .filter { chunk -> includePrivate || !chunk.isPrivateOrSensitive() }
        .filter { chunk -> chunk.text.toModelVisibleMemoryFactOrNull() != null }
        .sortedWith(coreChunkComparator())
        .distinctBy { chunk -> normalizeExactMemoryText(chunk.text) }
        .map(MemoryCorpusChunk::toCoreRetrievalResult)
        .toList()
}

private fun MemoryCorpusChunk.isPrivateOrSensitive(): Boolean =
    sensitivity in setOf(MemorySensitivity.PRIVATE, MemorySensitivity.SENSITIVE)

private fun MemoryCorpusChunk.sourcePriority(): Int = when (source) {
    MemorySource.USER_CONFIRMED -> 3
    MemorySource.EXPLICIT_USER_STATEMENT -> 2
    MemorySource.ASSISTANT_INFERRED -> 1
    else -> 0
}

private fun coreChunkComparator(): Comparator<MemoryCorpusChunk> =
    compareByDescending<MemoryCorpusChunk>(MemoryCorpusChunk::sourcePriority)
        .thenByDescending(MemoryCorpusChunk::lastObservedAt)
        .thenByDescending(MemoryCorpusChunk::updatedAt)
        .thenBy { chunk -> chunk.canonicalKey.orEmpty() }
        .thenBy { chunk -> chunk.entryId.orEmpty() }
        .thenBy(MemoryCorpusChunk::chunkId)

private fun coreResultComparator(): Comparator<MemoryRetrievalResult> =
    compareByDescending<MemoryRetrievalResult> { result -> sourcePriority(result.source) }
        .thenByDescending(MemoryRetrievalResult::lastObservedAt)
        .thenByDescending(MemoryRetrievalResult::updatedAt)
        .thenBy { result -> result.canonicalKey.orEmpty() }
        .thenBy { result -> result.entryId.orEmpty() }
        .thenBy(MemoryRetrievalResult::chunkId)

private fun sourcePriority(source: String?): Int = when (source) {
    MemorySource.USER_CONFIRMED -> 3
    MemorySource.EXPLICIT_USER_STATEMENT -> 2
    MemorySource.ASSISTANT_INFERRED -> 1
    else -> 0
}

private fun MemoryCorpusChunk.toCoreRetrievalResult(): MemoryRetrievalResult = MemoryRetrievalResult(
    chunkId = chunkId,
    entryId = entryId,
    sourcePath = sourcePath,
    text = text,
    type = type,
    sensitivity = sensitivity,
    source = source,
    updatedAt = updatedAt,
    chatId = chatId,
    createdAt = createdAt,
    section = heading,
    canonicalKey = canonicalKey,
    scope = scope,
    recallState = recallState,
    validity = validity,
    lastObservedAt = lastObservedAt,
    supersededBy = supersededBy,
    evidenceRefs = evidenceRefs,
    extraMetadata = extraMetadata,
    embeddingContentHash = embeddingContentHash,
    rankingHash = rankingHash,
    fusedScore = 0f
)

private val INTERNAL_MEMORY_METADATA_MARKERS = listOf(
    Regex("""<!--"""),
    Regex(
        """[\"']?(?:entry|chunk|memory|job|checkpoint|maintenance|activity[_-]?run|run|attempt)[_-]?ids?[\"']?\s*[:=]""",
        RegexOption.IGNORE_CASE
    ),
    Regex("""\b(?:mem|chunk)_[a-z0-9][a-z0-9._-]*\b""", RegexOption.IGNORE_CASE),
    Regex("""[\"']?source[_-]?path[\"']?\s*[:=]""", RegexOption.IGNORE_CASE),
    Regex("""\bMEMORY\.md\b""", RegexOption.IGNORE_CASE),
    Regex("""\bmemory[\\/]\d{4}-\d{2}-\d{2}\.md\b""", RegexOption.IGNORE_CASE),
    Regex(
        """[\"']?(?:canonical[_-]?key|last[_-]?observed[_-]?at|superseded[_-]?by|recall[_-]?state|evidence[_-]?refs?)[\"']?\s*[:=]""",
        RegexOption.IGNORE_CASE
    ),
    Regex(
        """\btype\s*[:=]\s*(?:stable_profile|communication_style|project_context|interest|important_event|important_person|emotional_pattern|boundary|life_context|recurring_theme|light_productivity_preference)\b""",
        RegexOption.IGNORE_CASE
    ),
    Regex("""\bsensitivity\s*[:=]\s*(?:normal|private|sensitive)\b""", RegexOption.IGNORE_CASE),
    Regex(
        """\b(?:source|provenance)\s*[:=]\s*(?:explicit_user_statement|assistant_inferred|user_confirmed)\b""",
        RegexOption.IGNORE_CASE
    ),
    Regex(
        """[\"']?(?:scope|observed|validity|recall|evidence|status|created(?:[_-]?at)?|updated(?:[_-]?at)?|started(?:[_-]?at)?|finished(?:[_-]?at)?|completed(?:[_-]?at)?|scheduled(?:[_-]?at)?|next[_-]?run[_-]?at|diagnostics?|diagnostic[_-]?codes?|error[_-]?(?:message|code)|canonical[_-]?revision|generation|phase|category|job[_-]?type|trigger[_-]?reason|platform[_-]?(?:uid|name)|model[_-]?(?:id|name)|row[_-]?version|cursor|duration(?:[_-]?ms)?|prompt[_-]?estimated[_-]?tokens?)[\"']?\s*[:=]""",
        RegexOption.IGNORE_CASE
    ),
    Regex(
        """[\"']?(?:hit|core|query|input|output|operation|entry|mutation|provider[_-]?request)[_-]?count[\"']?\s*[:=]""",
        RegexOption.IGNORE_CASE
    ),
    Regex(
        """\bmode\s*[:=]\s*(?:lexical|lexical_fallback|semantic|hybrid|failed|none)\b""",
        RegexOption.IGNORE_CASE
    ),
    Regex(
        """[\"']?(?:embedding[_-]?content|content|projection|recall[_-]?projection|canonical[_-]?source|source|target[_-]?(?:base|source)|job|checkpoint|maintenance|activity[_-]?run|snapshot|base|file|proposal|input|output)[_-]?hash[\"']?\s*[:=]""",
        RegexOption.IGNORE_CASE
    ),
    Regex("""[\"']?sha[-_]?256[\"']?\s*[:=]\s*[\"']?[a-f0-9]{8,}""", RegexOption.IGNORE_CASE),
    Regex("""\b[a-f0-9]{64}\b""", RegexOption.IGNORE_CASE)
)
