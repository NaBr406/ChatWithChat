package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.token.TokenUsageEstimator

class MarkdownLexicalRetriever(
    private val snapshotSource: MemoryCorpusSnapshotSource
) : MemoryRetriever, MemoryMaintenanceCorpusReader {

    override suspend fun retrieve(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> =
        retrieveForCorpus(request, MemoryCorpus.CHAT_RECALL_LONG_TERM)

    override suspend fun retrieveWorkingSet(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> =
        retrieveForCorpus(request, MemoryCorpus.MAINTENANCE_WORKING_SET)

    private suspend fun retrieveForCorpus(
        request: MemoryRetrievalRequest,
        requiredCorpus: MemoryCorpus
    ): Result<List<MemoryRetrievalResult>> = runCatching {
        require(request.corpus == requiredCorpus) {
            "Expected corpus $requiredCorpus but received ${request.corpus}"
        }
        check(request.strategy == MemoryRetrievalStrategy.LEXICAL) {
            "Markdown lexical retrieval only supports the lexical strategy"
        }
        if (request.limit <= 0 || request.tokenBudget <= 0) return@runCatching emptyList()
        val combinedQuery = listOfNotNull(
            request.query.trim().takeIf { it.isNotBlank() },
            request.recentContext?.trim()?.takeIf { it.isNotBlank() }
        ).joinToString(separator = "\n").take(MAX_COMBINED_QUERY_CHARS)
        if (combinedQuery.isBlank()) return@runCatching emptyList()

        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val snapshots = snapshotSource.snapshots(request.corpus).getOrThrow()
            val results = retrieveFromSnapshots(request, combinedQuery, snapshots)
            if (snapshotSource.isCurrent(snapshots).getOrThrow()) {
                return@runCatching results
            }
        }
        emptyList()
    }

    private fun retrieveFromSnapshots(
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
                request.includePrivate || chunk.sensitivity == null ||
                    chunk.sensitivity !in setOf(MemorySensitivity.PRIVATE, MemorySensitivity.SENSITIVE)
            }
            .mapNotNull { chunk ->
                val score = chunk.score(tokens, combinedQuery)
                if (score <= 0f) null else ScoredCorpusChunk(chunk, score)
            }
            .sortedWith(
                compareByDescending<ScoredCorpusChunk> { result -> result.score }
                    .thenBy { result -> if (result.chunk.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) 0 else 1 }
                    .thenByDescending { result -> result.chunk.updatedAt }
                    .thenBy { result -> result.chunk.chunkIndex }
                    .thenBy { result -> result.chunk.chunkId }
            )
            .take(candidateLimit)
            .map { result -> result.toRetrievalResult() }
            .distinctBy { result -> result.entryId?.let { entryId -> "entry:$entryId" } ?: "hash:${result.contentHash}" }
            .toList()

        var usedTokens = 0
        return ranked.filter { result ->
            val resultTokens = TokenUsageEstimator.estimateText(
                text = result.text,
                model = "",
                clientType = ClientType.OPENAI
            ) + RESULT_TOKEN_OVERHEAD
            if (usedTokens + resultTokens > request.tokenBudget) {
                false
            } else {
                usedTokens += resultTokens
                true
            }
        }.take(request.limit)
    }

    private fun MemoryCorpusChunk.score(tokens: List<String>, rawQuery: String): Float {
        val searchableText = normalizeSearchText(listOfNotNull(heading, text, type, source).joinToString(" "))
        val searchableTokens = tokenize(searchableText).toSet()
        val normalizedQuery = normalizeSearchText(rawQuery)
        var score = if (normalizedQuery.isNotBlank() && searchableText.contains(normalizedQuery)) {
            EXACT_QUERY_SCORE
        } else {
            0f
        }

        tokens.forEach { token ->
            if (token in searchableTokens || searchableText.contains(token)) {
                score += when {
                    token.isCjkToken() && token.length >= 3 -> CJK_TRIGRAM_MATCH_SCORE
                    token.isCjkToken() -> CJK_BIGRAM_MATCH_SCORE
                    else -> TOKEN_MATCH_SCORE
                }
            }
        }
        if (sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME && score > 0f) {
            score += LONG_TERM_BONUS
        }
        return score
    }

    private fun tokenize(query: String): List<String> = buildList {
        val normalized = normalizeSearchText(query)
        LATIN_TOKEN_REGEX.findAll(normalized).forEach { match ->
            match.value.takeIf { token -> token.length >= MIN_TOKEN_LENGTH }?.let(::add)
        }
        CJK_SEQUENCE_REGEX.findAll(normalized).forEach { match ->
            val sequence = match.value
            if (sequence.length == 1) add(sequence)
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
        contentHash = chunk.contentHash,
        lexicalScore = score,
        vectorScore = null,
        fusedScore = score,
        updatedAt = chunk.updatedAt
    )

    private fun normalizeSearchText(text: String): String = text
        .lowercase()
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    private fun String.isCjkToken(): Boolean = any { character -> character.code in 0x3400..0x9FFF }

    private data class ScoredCorpusChunk(
        val chunk: MemoryCorpusChunk,
        val score: Float
    )

    companion object {
        private val LATIN_TOKEN_REGEX = Regex("[a-z0-9_-]+")
        private val CJK_SEQUENCE_REGEX = Regex("[\\u3400-\\u9fff]+")
        private val CJK_GRAM_SIZES = listOf(2, 3)
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val MIN_TOKEN_LENGTH = 2
        private const val EXACT_QUERY_SCORE = 6f
        private const val TOKEN_MATCH_SCORE = 1f
        private const val CJK_BIGRAM_MATCH_SCORE = 1f
        private const val CJK_TRIGRAM_MATCH_SCORE = 1.5f
        private const val LONG_TERM_BONUS = 0.25f
        private const val RESULT_TOKEN_OVERHEAD = 24
        private const val MAX_COMBINED_QUERY_CHARS = 8_000
        private const val MAX_CANDIDATE_LIMIT = 500
        private const val MAX_SNAPSHOT_ATTEMPTS = 2
    }
}
