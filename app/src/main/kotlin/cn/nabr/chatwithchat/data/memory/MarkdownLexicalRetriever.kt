package cn.nabr.chatwithchat.data.memory

class MarkdownLexicalRetriever(
    private val snapshotSource: MemoryCorpusSnapshotSource
) : MemoryRetriever,
    MemoryMaintenanceCorpusReader {

    override suspend fun retrieve(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> =
        retrieveReport(request, MemoryCorpus.CHAT_RECALL_LONG_TERM).map { report ->
            report.coreResults + report.results
        }

    override suspend fun retrieveWithDiagnostics(request: MemoryRetrievalRequest): Result<MemoryRetrievalReport> =
        retrieveReport(request, MemoryCorpus.CHAT_RECALL_LONG_TERM)

    override suspend fun retrieveWorkingSet(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> =
        retrieveReport(request, MemoryCorpus.MAINTENANCE_WORKING_SET).map(MemoryRetrievalReport::results)

    private suspend fun retrieveReport(
        request: MemoryRetrievalRequest,
        requiredCorpus: MemoryCorpus
    ): Result<MemoryRetrievalReport> = runCatching {
        require(request.corpus == requiredCorpus) {
            "Expected corpus $requiredCorpus but received ${request.corpus}"
        }
        check(request.strategy == MemoryRetrievalStrategy.LEXICAL) {
            "Markdown lexical retrieval only supports the lexical strategy"
        }
        if (request.limit <= 0 || request.tokenBudget <= 0) {
            return@runCatching MemoryRetrievalReport(emptyList(), MemoryRetrievalMode.NONE)
        }
        val lexicalQuery = request.lexicalQuery()
        if (lexicalQuery.isBlank()) {
            return@runCatching MemoryRetrievalReport(emptyList(), MemoryRetrievalMode.NONE)
        }

        var lastSnapshots: List<MemoryCorpusSnapshot> = emptyList()
        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val snapshots = snapshotSource.snapshots(request.corpus).getOrThrow()
            lastSnapshots = snapshots
            val diagnostics = snapshots.flatMap(MemoryCorpusSnapshot::diagnostics)
            val hasChatProjectionFailure = requiredCorpus == MemoryCorpus.CHAT_RECALL_LONG_TERM &&
                diagnostics.any { diagnostic -> diagnostic.code.startsWith(CHAT_PROJECTION_DIAGNOSTIC_PREFIX) }
            val canonicalSnapshot = snapshots.singleOrNull()
            val coreResults = if (hasChatProjectionFailure || requiredCorpus != MemoryCorpus.CHAT_RECALL_LONG_TERM) {
                emptyList()
            } else {
                canonicalSnapshot?.selectCoreResults(request.includePrivate).orEmpty()
            }
            val coreKeys = coreResults.mapTo(mutableSetOf(), MemoryRetrievalResult::deduplicationKey)
            val results = if (hasChatProjectionFailure) {
                emptyList()
            } else {
                rankCandidates(request, lexicalQuery, snapshots)
                    .filterNot { result -> result.deduplicationKey() in coreKeys }
                    .packFor(request.queryLayerRequest())
            }
            if (snapshotSource.isProjectionCurrent(snapshots).getOrThrow()) {
                return@runCatching MemoryRetrievalReport(
                    results = results,
                    mode = when {
                        hasChatProjectionFailure -> MemoryRetrievalMode.FAILED
                        results.isNotEmpty() -> MemoryRetrievalMode.LEXICAL
                        else -> MemoryRetrievalMode.NONE
                    },
                    errorMessage = diagnostics.toBoundedErrorMessage().takeIf { hasChatProjectionFailure },
                    recallProjectionHash = canonicalSnapshot?.recallProjectionHash,
                    diagnostics = diagnostics,
                    coreResults = coreResults,
                    canonicalRevision = canonicalSnapshot?.generation,
                    canonicalSourceHash = canonicalSnapshot?.canonicalSourceHash
                )
            }
        }
        val canonicalSnapshot = lastSnapshots.singleOrNull()
        val freshnessDiagnostic = MemoryProjectionDiagnostic(
            code = RECALL_SNAPSHOT_CHANGED_DIAGNOSTIC,
            sourcePath = canonicalSnapshot?.sourcePath ?: MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            count = MAX_SNAPSHOT_ATTEMPTS
        )
        MemoryRetrievalReport(
            results = emptyList(),
            mode = MemoryRetrievalMode.FAILED,
            errorMessage = listOf(freshnessDiagnostic).toBoundedErrorMessage(),
            recallProjectionHash = canonicalSnapshot?.recallProjectionHash,
            diagnostics = lastSnapshots.flatMap(MemoryCorpusSnapshot::diagnostics) + freshnessDiagnostic,
            canonicalRevision = canonicalSnapshot?.generation,
            canonicalSourceHash = canonicalSnapshot?.canonicalSourceHash
        )
    }

    internal fun rankCandidates(
        request: MemoryRetrievalRequest,
        combinedQuery: String,
        snapshots: List<MemoryCorpusSnapshot>
    ): List<MemoryRetrievalResult> {
        val tokens = tokenize(combinedQuery)
        if (tokens.isEmpty()) return emptyList()
        val candidateLimit = request.candidateLimit.coerceIn(1, MAX_CANDIDATE_LIMIT)
        val ranked = snapshots
            .asSequence()
            .filter { snapshot -> snapshot.corpus == request.corpus }
            .flatMap { snapshot -> snapshot.chunks.asSequence() }
            .filter { chunk ->
                request.corpus != MemoryCorpus.CHAT_RECALL_LONG_TERM ||
                    chunk.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
            }
            .filter { chunk ->
                request.corpus != MemoryCorpus.CHAT_RECALL_LONG_TERM ||
                    (chunk.scope ?: MemoryScope.GENERAL) == request.recallScope
            }
            .filter { chunk ->
                request.includePrivate ||
                    chunk.sensitivity == null ||
                    chunk.sensitivity !in setOf(MemorySensitivity.PRIVATE, MemorySensitivity.SENSITIVE)
            }
            .mapNotNull { chunk ->
                val lexicalMatch = chunk.score(tokens, combinedQuery)
                val passesRelevanceGate = when (request.corpus) {
                    MemoryCorpus.CHAT_RECALL_LONG_TERM ->
                        lexicalMatch.hasMeaningfulMatch && lexicalMatch.score >= CHAT_RECALL_LEXICAL_SCORE_FLOOR
                    MemoryCorpus.MAINTENANCE_WORKING_SET -> lexicalMatch.score > 0f
                }
                if (passesRelevanceGate) ScoredCorpusChunk(chunk, lexicalMatch.score) else null
            }
            .sortedWith(
                compareByDescending<ScoredCorpusChunk> { result -> result.score }
                    .thenBy { result -> if (result.chunk.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) 0 else 1 }
                    .thenByDescending { result -> result.chunk.updatedAt }
                    .thenBy { result -> result.chunk.chunkIndex }
                    .thenBy { result -> result.chunk.chunkId }
            )
            .map { result -> result.toRetrievalResult() }
            .distinctBy(MemoryRetrievalResult::deduplicationKey)
            .distinctBy { result -> normalizeExactMemoryText(result.text) }
            .take(candidateLimit)
            .toList()
        return ranked
    }

    private fun MemoryCorpusChunk.score(tokens: List<String>, rawQuery: String): LexicalMatch {
        // Only the natural-language fact participates in relevance scoring.
        // Headings and type labels are maintenance metadata, not user evidence.
        val searchableText = normalizeSearchText(text)
        val searchableTokens = tokenize(searchableText).toSet()
        val normalizedQuery = normalizeSearchText(rawQuery)
        var score = if (normalizedQuery.isNotBlank() && searchableText.contains(normalizedQuery)) {
            EXACT_QUERY_SCORE
        } else {
            0f
        }
        var hasMeaningfulMatch = false

        tokens.forEach { token ->
            val matchesExactToken = token in searchableTokens
            val matchesContainedText = searchableText.contains(token)
            if (matchesExactToken || matchesContainedText) {
                score += when {
                    token.isCjkToken() && token.length >= 3 -> CJK_TRIGRAM_MATCH_SCORE
                    token.isCjkToken() && token.length == 1 -> CJK_SINGLE_CHAR_MATCH_SCORE
                    token.isCjkToken() -> CJK_BIGRAM_MATCH_SCORE
                    else -> TOKEN_MATCH_SCORE
                }
                if (
                    (token.isCjkToken() && token.length >= MIN_MEANINGFUL_CJK_TOKEN_LENGTH && matchesContainedText) ||
                    (!token.isCjkToken() && matchesExactToken)
                ) {
                    hasMeaningfulMatch = true
                }
            }
        }
        if (sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME && score > 0f) {
            score += LONG_TERM_BONUS
        }
        return LexicalMatch(score = score, hasMeaningfulMatch = hasMeaningfulMatch)
    }

    private fun tokenize(query: String): List<String> = buildList {
        val normalized = normalizeSearchText(query)
        LATIN_TOKEN_REGEX.findAll(normalized).forEach { match ->
            match.value.takeIf { token -> token.length >= MIN_TOKEN_LENGTH }?.let(::add)
        }
        CJK_SEQUENCE_REGEX.findAll(normalized).forEach { match ->
            val sequence = match.value
            sequence
                .asSequence()
                .filterNot { character -> character in CJK_SINGLE_CHAR_STOPWORDS }
                .map(Char::toString)
                .forEach(::add)
            CJK_GRAM_SIZES.forEach { gramSize ->
                if (sequence.length >= gramSize) {
                    for (index in 0..sequence.length - gramSize) {
                        add(sequence.substring(index, index + gramSize))
                    }
                }
            }
        }
    }.distinct()

    private fun ScoredCorpusChunk.toRetrievalResult(): MemoryRetrievalResult = MemoryRetrievalResult(
        chunkId = chunk.chunkId,
        entryId = chunk.entryId,
        sourcePath = chunk.sourcePath,
        text = chunk.text,
        type = chunk.type,
        sensitivity = chunk.sensitivity,
        source = chunk.source,
        chatId = chunk.chatId,
        createdAt = chunk.createdAt,
        section = chunk.heading,
        canonicalKey = chunk.canonicalKey,
        scope = chunk.scope,
        recallState = chunk.recallState,
        validity = chunk.validity,
        lastObservedAt = chunk.lastObservedAt,
        supersededBy = chunk.supersededBy,
        evidenceRefs = chunk.evidenceRefs,
        extraMetadata = chunk.extraMetadata,
        embeddingContentHash = chunk.embeddingContentHash,
        rankingHash = chunk.rankingHash,
        lexicalScore = score,
        vectorScore = null,
        fusedScore = score,
        updatedAt = chunk.updatedAt
    )

    private fun normalizeSearchText(text: String): String = normalizeExactMemoryText(text)

    private fun String.isCjkToken(): Boolean = any { character -> character.code in 0x3400..0x9FFF }

    private data class ScoredCorpusChunk(
        val chunk: MemoryCorpusChunk,
        val score: Float
    )

    private data class LexicalMatch(
        val score: Float,
        val hasMeaningfulMatch: Boolean
    )

    companion object {
        private val LATIN_TOKEN_REGEX = Regex("[a-z0-9]+")
        private val CJK_SEQUENCE_REGEX = Regex("[\\u3400-\\u9fff]+")
        private val CJK_GRAM_SIZES = listOf(2, 3)
        private val CJK_SINGLE_CHAR_STOPWORDS = setOf(
            '我', '你', '他', '她', '它', '的', '了', '和', '与', '或', '是', '吗', '呢', '么',
            '这', '那', '在', '有', '就', '也', '都', '为', '从', '到', '让', '请', '给', '把', '能', '要'
        )
        private const val MIN_TOKEN_LENGTH = 2
        private const val MIN_MEANINGFUL_CJK_TOKEN_LENGTH = 2
        private const val CHAT_RECALL_LEXICAL_SCORE_FLOOR = 1.25f
        private const val EXACT_QUERY_SCORE = 6f
        private const val TOKEN_MATCH_SCORE = 1f
        private const val CJK_SINGLE_CHAR_MATCH_SCORE = 0.35f
        private const val CJK_BIGRAM_MATCH_SCORE = 1f
        private const val CJK_TRIGRAM_MATCH_SCORE = 1.5f
        private const val LONG_TERM_BONUS = 0.25f
        private const val MAX_CANDIDATE_LIMIT = 500
        private const val MAX_SNAPSHOT_ATTEMPTS = 2
        private const val CHAT_PROJECTION_DIAGNOSTIC_PREFIX = "chat_projection_"
        private const val RECALL_SNAPSHOT_CHANGED_DIAGNOSTIC = "recall_snapshot_changed_during_retrieval"
    }
}
